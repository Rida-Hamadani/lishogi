package lila.study

import lila.game.Game
import lila.round.JsonView.WithFlags

private object GameToRoot {

  def apply(game: Game, withClocks: Boolean): Node.Root = {
    val root = Node.Root.fromRoot {
      lila.round.TreeBuilder(
        game = game,
        analysis = none,
        withFlags = WithFlags(clocks = withClocks)
      )
    }
    endComment(game).fold(root) { comment =>
      root updateMainlineLast { _.setComment(comment) }
    }
  }

  private def endComment(game: Game) =
    game.finished option {
      import lila.tree.Node.Comment
      val status = lila.game.StatusText(game)
      val text   = s"$status"
      Comment(Comment.Id.make, Comment.Text(text), Comment.Author.Lishogi)
    }
}
