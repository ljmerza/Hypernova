// A text longer than the lens (a reply, a plan, a diff) as one text that scrolls, never pages. The
// lens scrolls it itself: a box of fixed size whose lines change, so a swipe, or a step of text
// arriving like speech, is one text update. Not the firmware's own scrolling, which the Even Hub
// simulator 0.9.5 showed can't do this: a box that takes the temple's swipes does scroll by itself
// (a line a swipe, with its own scrollbar), but the app is never told where it is, any new text in
// that box sends it back to its first line (even the same text, so words arriving would jump it to
// the top at every step, and it can't follow the newest line), a page rebuild does too, and a box
// holds about 1000 bytes, less than many replies. Pure.
import { BOX_BYTES, charWidth, nativeWidth, spread, utf8Bytes } from './font.ts';

export interface Scroll {
  /** The first line in view. */
  top: number;
  /** How many lines the text had when last shown: the newest line followed when it grows. */
  lines: number;
  /** New lines (words arriving, a voice reading on) bring the newest into view. */
  follow: boolean;
}

/** What marks more text under the window, at the right of its last line. */
const MORE = '↓';
/** The mark aims this far short of the line's end: spaces are 5 px, so it can overshoot by half of one. */
const SLACK = 3;
/** Text is wrapped this much narrower than its box, so the mark never cuts a line. */
export const MORE_W = nativeWidth(MORE) + 10 + SLACK;

/**
 * How many of `rows` lines a window on `lines` may show so that every window of the text, wherever
 * it's scrolled and whatever marks it carries (↓, and the talk box's ↑), fits in a text box
 * (font.ts BOX_BYTES). Most text fits a whole window with room to spare; a window of dense lines
 * (Russian is 2 bytes a letter, CJK 3) shows fewer of them, one number for the whole text, so a
 * swipe still moves by a window less a line and no line is ever skipped. `lines` is the whole
 * text's: while words arrive, the lines shown are these or the start of one of them, never more.
 * `width`: the box's line, which a mark's spaces may fill.
 */
export function fitRows(lines: string[], rows: number, width: number, max = BOX_BYTES): number {
  if (rows <= 1 || !lines.length) return Math.max(1, rows);
  const bytes = lines.map(utf8Bytes);
  // Two marks, each its spaces (a shorter line gets more) and an arrow.
  const marks = 2 * (Math.ceil(width / charWidth(' ')) + 1 + utf8Bytes(MORE));
  for (let r = rows; r > 1; r--) {
    let sum = 0, worst = 0;
    for (let i = 0; i < lines.length; i++) {
      sum += bytes[i];
      if (i >= r) sum -= bytes[i - r];
      worst = Math.max(worst, sum);
    }
    if (worst + (r - 1) + marks <= max) return r;
  }
  return 1;
}

/** A text opened afresh: its first line at the top, following. */
export const startScroll = (lines: number): Scroll => ({ top: 0, lines, follow: true });

/** The furthest the first line in view may go: the text's last line is then the window's last. */
export const lastTop = (lines: number, rows: number) => Math.max(0, lines - rows);

/**
 * `s` for the text as it is now, `lines` long, in a window of `rows`. `arriving`: the text grows as
 * words arrive (or as a voice reads it), so while following, its newest line comes into view: the
 * window moves down a line as each new line starts, like a teleprompter. Otherwise the window stays
 * where it was, within the text.
 */
export function settle(s: Scroll, lines: number, rows: number, arriving: boolean): Scroll {
  let top = s.top;
  if (arriving && s.follow && lines > s.lines) top = Math.max(top, lastTop(lines, rows));
  return { ...s, top: Math.max(0, Math.min(top, lastTop(lines, rows))), lines };
}

/**
 * A swipe: `d` 1 reads on, -1 goes back, by a window less a line (the line you were on stays in
 * view). Going back stops following; reaching the end follows again. `moved` false: already at
 * that end (a swipe on past the end, or back past the start).
 */
export function swipe(s: Scroll, d: 1 | -1, rows: number): { s: Scroll; moved: boolean } {
  const end = lastTop(s.lines, rows);
  const top = Math.max(0, Math.min(end, s.top + d * Math.max(1, rows - 1)));
  if (top === s.top) return { s: d > 0 ? { ...s, follow: true } : s, moved: false };
  return { s: { ...s, top, follow: d > 0 && top >= end }, moved: true };
}

/** A swipe that puts line `line` at the top (a diff's next file), as far as the text goes. */
export function scrollTo(s: Scroll, line: number, rows: number): Scroll {
  const top = Math.max(0, Math.min(line, lastTop(s.lines, rows)));
  return { ...s, top, follow: top >= lastTop(s.lines, rows) };
}

/** There is text under the window. */
export const hasMore = (s: Scroll, rows: number) => s.top < lastTop(s.lines, rows);

/**
 * The window: `rows` lines of `lines` from `top`, always `rows` long so its box keeps its size (a
 * change is a text update, never a new page), the last one marked at the right (`width`: the box's
 * line) when more follows. The lines were wrapped MORE_W narrower, so the mark fits beside them.
 * `text`: how many of the lines hold text (blank ones after them, there so the last part can come
 * to the top, aren't more to read).
 */
export function windowLines(lines: string[], top: number, rows: number, width: number, text = lines.length): string[] {
  const out = lines.slice(top, top + rows);
  if (top + rows < text && out.length) out[out.length - 1] = spread(out[out.length - 1], MORE, width - SLACK);
  while (out.length < rows) out.push('');
  return out;
}
