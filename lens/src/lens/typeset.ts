// How the controller lays text out for the look in use (look.ts): which font measures a line, how
// many lines a card holds, how a window onto a text that scrolls is marked. The native look's are
// the firmware font's (font.ts, pages.ts), exactly as before; the drawn look's are the app's type
// (core/type.ts, drawn.ts). Scrolling, arriving words and swipes work the same on both: only the
// lines are different. Pure: runs under node.
import { textLines, wrapAll } from '../core/text.ts';
import { fitRows, MORE_W, windowLines } from '../core/scroll.ts';
import { CARD_TEXT_W, cardRoom, factRows } from './pages.ts';
import { drawnCardRoom, drawnCommandLines, drawnCommandRoom, drawnFactLines, drawnReadLines, drawnReasonLines } from './drawn.ts';
import { drawsCards, drawsReplies, type Look } from './look.ts';

export interface Typeset {
  /** Whose type: the glasses' font, or the app's. */
  readonly look: 'native' | 'hypernova';
  /** A reply as the lines of one text that scrolls. */
  readLines(plain: string): string[];
  /** How many of its lines a reply's window shows (`whole`: all of them) over its `facts`, on up to two lines. */
  replyRows(whole: string[], facts: readonly string[]): number;
  /** The window onto `lines` from `top`, `rows` long; the native one marks "more below" in its text. */
  window(lines: string[], top: number, rows: number): string[];
  /** An approval's command as lines, cut to what fits under its lead and over its reason (`facts`) and its warning. */
  command(detail: string, facts: readonly string[], warn?: string): string[];
}

/** A text that scrolls is wrapped this much narrower than the card: room for the ↓ that says more follows. */
const READ_W = CARD_TEXT_W - MORE_W;

/** Cut `lines` to `room`, the last one kept ending in "…". */
function cut(lines: string[], room: number): string[] {
  return lines.length > room ? [...lines.slice(0, room - 1), `${lines[room - 1].slice(0, -1)}…`] : lines;
}

export const NATIVE_TYPE: Typeset = {
  look: 'native',
  readLines: plain => textLines(plain, READ_W),
  // Fewer lines in view when they're too dense for one text box (font.ts BOX_BYTES).
  replyRows: (whole, facts) => fitRows(whole, Math.min(cardRoom(false, factRows(facts, 2)), Math.max(1, whole.length)), CARD_TEXT_W),
  window: (lines, top, rows) => windowLines(lines, top, rows, CARD_TEXT_W),
  command: (detail, facts, warn) => cut(wrapAll(detail, CARD_TEXT_W), cardRoom(true, factRows(facts, warn ? 2 : 1, warn))),
};

export const DRAWN_TYPE: Typeset = {
  look: 'hypernova',
  readLines: drawnReadLines,
  // A picture has no byte limit: the window is as tall as the card allows.
  replyRows: (whole, facts) => Math.min(drawnCardRoom(facts.length ? drawnFactLines(facts, 2).length : 0), Math.max(1, whole.length)),
  // The scroll mark is drawn beside the text (drawn.ts), not written into it.
  window: (lines, top, rows) => { const out = lines.slice(top, top + rows); while (out.length < rows) out.push(''); return out; },
  command: (detail, facts, warn) => cut(drawnCommandLines(detail), drawnCommandRoom(true, drawnReasonLines(facts, warn).length + (warn ? 1 : 0))),
};

/** A reply's lines: drawn only where replies are drawn (look.ts: the development look). */
export const replyTypeset = (look: Look): Typeset => (drawsReplies(look) ? DRAWN_TYPE : NATIVE_TYPE);
/** An approval's: drawn in either drawn look. */
export const approvalTypeset = (look: Look): Typeset => (drawsCards(look) ? DRAWN_TYPE : NATIVE_TYPE);
