// Moving mode ("moving" / "still"): on a bike or a walk the lens shows two short lines at most, so
// the road stays in view: what matters on this screen, and the one thing to do or know next. A
// pop-up takes the first line (a chime has said it came). Telescope's picture stays whole, since you
// asked to look. Pure: it reads a screen as the controller built it.
import type { LensView } from './pages.ts';
import { partsOf, type Parts } from '../core/font.ts';

type Brief = Extract<LensView, { kind: 'brief' }>;

const first = (xs: string[] | undefined) => (xs ?? []).map(x => x.trim()).find(Boolean) ?? '';

/**
 * The two lines for `v`, in parts like every line (a gap between them, never "·"). `toast`: a word
 * the controller just said ("Allowed", "Noted"), which takes the second line. `say`: what to say
 * next on a card that needs an answer ("say “approve push”").
 */
export function briefOf(v: LensView, toast = '', say = ''): LensView {
  if (v.kind === 'telescope' || v.kind === 'blank' || v.kind === 'brief') return v;
  let lines: [Parts, Parts];
  switch (v.kind) {
    case 'message': lines = [v.title, v.sub]; break;
    case 'glance': {
      const i = Math.max(0, v.rows.findIndex(r => r.focused));
      const r = v.rows[i];
      // The row's short phrase: the road leaves room for little.
      const top: Parts = v.newRow?.focused ? `+ ${v.newRow.label}` : r ? [r.project, r.detailShort ?? r.detail] : v.empty;
      const where = r && !v.newRow?.focused && v.rows.length > 1 ? `${i + 1} of ${v.rows.length}` : '';
      lines = [top, [where, v.clock ?? '', ...partsOf(v.right)].filter(Boolean)];
      break;
    }
    case 'card': lines = [v.head, say || [v.lead, first(v.body)].filter(Boolean).join(' ') || v.facts.join(', ')]; break;
    case 'live': lines = [v.head, first(v.now)]; break;
    case 'choice': lines = [v.head, say || v.prompt]; break;
    case 'talk': {
      const text = v.text.split('\n').filter(Boolean);
      // About to go by itself: that it's going is the first thing to know.
      lines = [v.sendIn ? [...partsOf(v.head), `sending in ${v.sendIn}`] : v.head, text.at(-1) ?? ''];
      break;
    }
    case 'power': lines = [v.rows.map(r => `${r.label} ${Math.round(r.percent)}%`), v.awake ?? '']; break;
  }
  if (toast) lines[1] = toast;
  // A pop-up: its one line first, then what the screen says: "0stems needs you: wants to push to main".
  const band = v.band;
  const said = !band ? '' : band.line ?? `${partsOf(band.head).join(' ')}: ${band.detail}`;
  const out: Brief = { kind: 'brief', lines: band ? [said, lines[0]] : lines.filter(l => partsOf(l).some(Boolean)) };
  return out;
}
