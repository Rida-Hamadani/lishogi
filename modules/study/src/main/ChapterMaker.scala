package lila.study

import shogi.format.Tags
import shogi.format.forsyth.Sfen
import shogi.variant.Variant
import lila.chat.{ Chat, ChatApi }
import lila.game.{ Game, Namer }
import lila.user.User

final private class ChapterMaker(
    net: lila.common.config.NetConfig,
    lightUser: lila.user.LightUserApi,
    chatApi: ChatApi,
    gameRepo: lila.game.GameRepo,
    notationDump: lila.game.NotationDump
)(implicit ec: scala.concurrent.ExecutionContext) {

  import ChapterMaker._

  def apply(study: Study, data: Data, order: Int, userId: User.ID): Fu[Chapter] =
    data.game.??(parseGame) flatMap {
      case None       => fromSfenOrNotationOrBlank(study, data, order, userId)
      case Some(game) => fromGame(study, game, data, order, userId)
    } map { (c: Chapter) =>
      if (c.name.value.isEmpty) c.copy(name = Chapter defaultName order) else c
    }

  def fromSfenOrNotationOrBlank(study: Study, data: Data, order: Int, userId: User.ID): Fu[Chapter] =
    data.notation.filter(_.trim.nonEmpty) match {
      case Some(notation) => fromNotation(study, notation, data, order, userId)
      case None           => fuccess(fromSfenOrBlank(study, data, order, userId))
    }

  private def fromNotation(
      study: Study,
      notation: String,
      data: Data,
      order: Int,
      userId: User.ID
  ): Fu[Chapter] =
    for {
      contributors <- lightUser.asyncMany(study.members.contributorIds.toList)
      parsed <- NotationImport(notation, contributors.flatten).toFuture recoverWith { case e: Exception =>
        fufail(ValidationException(e.getMessage))
      }
    } yield Chapter.make(
      studyId = study.id,
      name = parsed.tags(_.Sente).flatMap { sente =>
        parsed
          .tags(_.Gote)
          .ifTrue {
            data.name.value.isEmpty || data.isDefaultName
          }
          .map { gote =>
            Chapter.Name(s"$sente - $gote")
          }
      } | data.name,
      setup = Chapter.Setup(
        none,
        parsed.variant,
        data.realOrientation,
        fromNotation = true
      ),
      root = parsed.root,
      tags = parsed.tags,
      order = order,
      ownerId = userId,
      practice = data.isPractice,
      gamebook = data.isGamebook,
      conceal = data.isConceal option Chapter.Ply(parsed.root.ply)
    )

  private def fromSfenOrBlank(study: Study, data: Data, order: Int, userId: User.ID): Chapter = {
    val variant = data.variant.flatMap(Variant.apply) | Variant.default
    (data.sfen.filterNot(_.initialOf(variant)).flatMap { _.toSituationPlus(variant) } match {
      case Some(parsed) =>
        Node.Root(
          ply = parsed.plies,
          sfen = parsed.toSfen,
          check = parsed.situation.check,
          clock = none,
          children = Node.emptyChildren
        ) -> true
      case None =>
        Node.Root(
          ply = 0,
          sfen = variant.initialSfen,
          check = false,
          clock = none,
          children = Node.emptyChildren
        ) -> false
    }) match {
      case (root, isFromSfen) =>
        Chapter.make(
          studyId = study.id,
          name = data.name,
          setup = Chapter.Setup(
            none,
            variant,
            data.realOrientation,
            fromSfen = isFromSfen
          ),
          root = root,
          tags = Tags.empty,
          order = order,
          ownerId = userId,
          practice = data.isPractice,
          gamebook = data.isGamebook,
          conceal = data.isConceal option Chapter.Ply(root.ply)
        )
    }
  }

  private[study] def fromGame(
      study: Study,
      game: Game,
      data: Data,
      order: Int,
      userId: User.ID
  ): Fu[Chapter] =
    for {
      tags <- notationDump.tags(game, withOpening = true, csa = false)
      name <- {
        if (data.isDefaultName)
          Namer.gameVsText(game, withRatings = false)(lightUser.async) dmap Chapter.Name.apply
        else fuccess(data.name)
      }
      root = GameToRoot(game, withClocks = true)
      _    = notifyChat(study, game, userId)
    } yield Chapter.make(
      studyId = study.id,
      name = name,
      setup = Chapter.Setup(
        !game.synthetic option game.id,
        game.variant,
        data.realOrientation
      ),
      root = root,
      tags = KifTags(tags),
      order = order,
      ownerId = userId,
      practice = data.isPractice,
      gamebook = data.isGamebook,
      conceal = data.isConceal option Chapter.Ply(root.ply)
    )

  def notifyChat(study: Study, game: Game, userId: User.ID) =
    if (study.isPublic) List(game.id, s"${game.id}/w") foreach { chatId =>
      chatApi.userChat.write(
        chatId = Chat.Id(chatId),
        userId = userId,
        text = s"I'm studying this game on ${net.domain}/study/${study.id}",
        publicSource = none,
        _.Study
      )
    }

  private val UrlRegex = {
    val escapedDomain = net.domain.value.replace(".", "\\.")
    s"""$escapedDomain/(\\w{8,12})"""
  }.r.unanchored

  @scala.annotation.tailrec
  private def parseGame(str: String): Fu[Option[Game]] =
    str match {
      case s if s.lengthIs == Game.gameIdSize => gameRepo game s
      case s if s.lengthIs == Game.fullIdSize => gameRepo game Game.takeGameId(s)
      case UrlRegex(id)                       => parseGame(id)
      case _                                  => fuccess(none)
    }
}

private[study] object ChapterMaker {

  case class ValidationException(message: String) extends lila.base.LilaException

  sealed trait Mode {
    def key = toString.toLowerCase
  }
  object Mode {
    case object Normal   extends Mode
    case object Practice extends Mode
    case object Gamebook extends Mode
    case object Conceal  extends Mode
    val all                = List(Normal, Practice, Gamebook, Conceal)
    def apply(key: String) = all.find(_.key == key)
  }

  trait ChapterData {
    def orientation: String
    def mode: String
    def realOrientation = shogi.Color.fromName(orientation) | shogi.Sente
    def isPractice      = mode == Mode.Practice.key
    def isGamebook      = mode == Mode.Gamebook.key
    def isConceal       = mode == Mode.Conceal.key
  }

  case class Data(
      name: Chapter.Name,
      game: Option[String] = None,
      variant: Option[String] = None,
      sfen: Option[Sfen] = None,
      notation: Option[String] = None,
      orientation: String = "sente",
      mode: String = ChapterMaker.Mode.Normal.key,
      initial: Boolean = false,
      isDefaultName: Boolean = true
  ) extends ChapterData {

    def manyGames =
      game
        .??(_.linesIterator.take(Study.maxChapters).toList)
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { g => copy(game = g.some) }
        .some
        .filter(_.sizeIs > 1)
  }

  case class EditData(
      id: Chapter.Id,
      name: Chapter.Name,
      orientation: String,
      mode: String,
      description: String // boolean
  ) extends ChapterData {
    def hasDescription = description.nonEmpty
  }

  case class DescData(id: Chapter.Id, desc: String)
}
