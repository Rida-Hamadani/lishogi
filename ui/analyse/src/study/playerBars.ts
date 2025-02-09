import { defined } from 'common/common';
import { VNode, h } from 'snabbdom';
import { ops as treeOps } from 'tree';
import renderClocks from '../clocks';
import AnalyseCtrl from '../ctrl';
import { TagArray } from './interfaces';
import { findTag, isFinished, resultOf } from './studyChapters';

interface PlayerNames {
  sente: string;
  gote: string;
}

export default function (ctrl: AnalyseCtrl): VNode[] | undefined {
  const study = ctrl.study;
  if (!study || ctrl.embed) return;
  const tags = study.data.chapter.tags,
    playerNames = {
      sente: findTag(tags, '先手')!,
      gote: findTag(tags, '後手')!,
    };
  if (!playerNames.sente && !playerNames.gote && !treeOps.findInMainline(ctrl.tree.root, n => defined(n.clock))) return;
  const clocks = renderClocks(ctrl, false),
    ticking = !isFinished(study.data.chapter) && ctrl.turnColor();
  return (['sente', 'gote'] as Color[]).map(color =>
    renderPlayer(tags, clocks, playerNames, color, ticking === color, ctrl.bottomColor() !== color)
  );
}

function renderPlayer(
  tags: TagArray[],
  clocks: [VNode, VNode] | undefined,
  playerNames: PlayerNames,
  color: Color,
  ticking: boolean,
  top: boolean
): VNode {
  const title = findTag(tags, `${color}title`),
    elo = findTag(tags, `${color}elo`),
    result = resultOf(tags, color === 'sente');
  return h(
    `div.study__player.study__player-${top ? 'top' : 'bot'}`,
    {
      class: { ticking },
    },
    [
      h('div.left', [
        result && h('span.result', result),
        h('span.info', [
          title && h('span.title', title + ' '),
          h('span.name', playerNames[color]),
          elo && h('span.elo', elo),
        ]),
      ]),
      clocks && clocks[color === 'sente' ? 0 : 1],
    ]
  );
}
