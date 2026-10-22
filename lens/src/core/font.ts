// The G2 firmware font, as measured in the Even Hub simulator 0.9.5. The table comes from
// Hypertasks (its dev/font-probe.ts); the symbols in the second block were measured for Lens with
// dev/probe.ts (`?p=measure`). It has one size and no weight or spacing settings, so the lens is laid
// out around these numbers: text that is wider than its box wraps, and a box whose text does not fit
// draws a scrollbar, so everything is shortened here first. Pure: runs under node.

/** Line pitch. A box of n lines needs n * LINE + 2 * border + 2 * padding px of height. */
export const LINE = 27;
/** From a box's top edge (border 1, padding 0) to the top of a capital letter. */
export const CAP_TOP = 7;
export const CAP_H = 16;
/** Kept free at the right of every line: hardware may round widths differently from the simulator. */
export const SAFE = 8;

// Advance widths in px. Characters missing from the firmware font are not listed (they are dropped).
const W1: Record<string, number> = {
  ' ': 5, '!': 4, '"': 6, '#': 14.17, '$': 12.17, '%': 14, '&': 15.17, "'": 4, '(': 6.17, ')': 6.17, '*': 8, '+': 10, ',': 5,
  '-': 10, '.': 5, '/': 5.5, '0': 12, '1': 7.17, '2': 11.17, '3': 12, '4': 12.17, '5': 12, '6': 12, '7': 12.17, '8': 12,
  '9': 12, ':': 4, ';': 5, '<': 10, '=': 10, '>': 10, '?': 12, '@': 17, A: 13.17, B: 12, C: 11.17, D: 12, E: 10.17,
  F: 10.17, G: 12, H: 12, I: 5.17, J: 8.17, K: 12, L: 9.17, M: 16, N: 12, O: 12, P: 12, Q: 12, R: 12, S: 11.17, T: 10.33,
  U: 12, V: 13.17, W: 16, X: 13.17, Y: 13.17, Z: 12.17, '[': 7, '\\': 5.5, ']': 7, '^': 10, _: 9, a: 11.17, b: 11,
  c: 10.17, d: 11, e: 10.17, f: 7.5, g: 11, h: 11, i: 4.83, j: 5.33, k: 9.17, l: 4, m: 16, n: 11, o: 10.17, p: 11, q: 11,
  r: 7.17, s: 10.17, t: 6.33, u: 11.17, v: 11.17, w: 15.17, x: 11.17, y: 11.17, z: 9.17, '{': 8.17, '|': 4, '}': 8.17,
  '~': 16,
  А: 13.17, Б: 13, В: 13, Г: 10.17, Д: 15.17, Е: 12.17, Ж: 17.17, З: 12.17, И: 14, Й: 14, К: 13, Л: 14.17, М: 16, Н: 13,
  О: 14, П: 13, Р: 13, С: 13.17, Т: 12.33, У: 13.17, Ф: 16, Х: 13.17, Ц: 13.17, Ч: 12.17, Ш: 16, Щ: 17.17, Ъ: 15.33,
  Ы: 16, Ь: 13, Э: 13.17, Ю: 17, Я: 13, а: 12.17, б: 12, в: 12, г: 9.17, д: 12.17, е: 11.17, ж: 13.17, з: 11.17, и: 12,
  й: 12, к: 10.17, л: 12.17, м: 14, н: 12, о: 11.17, п: 11, р: 12, с: 11.17, т: 11.17, у: 11.17, ф: 13.17, х: 11.17,
  ц: 12, ч: 12, ш: 14, щ: 15, ъ: 13.17, ы: 14, ь: 12, э: 11.17, ю: 14, я: 11.17, Ё: 12.17, ё: 11.17,
  '…': 10, '—': 15, '–': 15, '·': 5, '•': 9, '○': 20, '●': 20, '□': 20, '■': 20, '›': 8, '‹': 8, '▶': 20, '◀': 20,
  '←': 17, '→': 17, '↑': 14, '↓': 14, '─': 20, '━': 20, '│': 20, '«': 12, '»': 12, '“': 8, '”': 8, '‘': 5, '’': 5,
  '№': 19, '×': 10, '°': 7, '€': 15.17, '　': 20, ' ': 5,
};
/** Anything not measured (other scripts, accents) is assumed wide, so it never overflows. */
const UNKNOWN = 15;

/** Characters the firmware drops (no glyph): replaced so titles keep their meaning. */
const REPLACE: Record<string, string> = {
  // Common in Claude's replies and missing from the font: keep the meaning.
  '✓': '·', '✔': '·', '✗': '×', '✘': '×', '⚠': '!', '▸': '›', '▹': '›', '➜': '→', '⇒': '→', '≈': '~', '≥': '>=', '≤': '<=', '≠': '!=',
  '\u00a0': ' ', '\u202f': ' ', '`': "'", ' ': ' ', ' ': '', ' ': ' ', ' ': ' ', ' ': ' ', '\t': ' ' };

/** The firmware font has this character (measured). */
export const hasGlyph = (ch: string) => ch in W1;

export function charWidth(ch: string): number {
  return W1[ch] ?? UNKNOWN;
}

export function nativeWidth(s: string): number {
  let w = 0;
  for (const ch of s) w += charWidth(ch);
  return w;
}

/** One line of firmware-safe text: no line breaks, no glyphs the font lacks, no emoji. */
export function clean(s: string): string {
  let out = '';
  for (const ch of s.replace(/[\r\n]+/g, ' ')) {
    const r = REPLACE[ch];
    if (r !== undefined) { out += r; continue; }
    const cp = ch.codePointAt(0)!;
    // Emoji, variation selectors and joiners render as nothing at best.
    if (cp >= 0x1f000 || (cp >= 0xfe00 && cp <= 0xfe0f) || cp === 0x200d || (cp >= 0x2600 && cp <= 0x27bf)) continue;
    out += ch;
  }
  return out.replace(/ {2,}/g, ' ').trim();
}

/** Shorten to at most maxW px, with an ellipsis when something was cut. */
export function nativeFit(s: string, maxW: number): string {
  if (maxW <= 0) return '';
  if (nativeWidth(s) <= maxW) return s;
  const ell = charWidth('…');
  let w = 0, out = '';
  for (const ch of s) {
    const cw = charWidth(ch);
    if (w + cw + ell > maxW) break;
    out += ch;
    w += cw;
  }
  out = out.trimEnd();
  return out ? out + '…' : nativeWidth('…') <= maxW ? '…' : '';
}

/** Spaces that move the next character about `px` to the right. */
export function spaces(px: number): string {
  return ' '.repeat(Math.max(0, Math.round(px / charWidth(' '))));
}

/**
 * A line in parts, kept apart until it's laid out (DECISIONS, "One design": no "·"-joined fields on
 * the lens): one part, or several. The firmware look puts a gap between them (gapJoin); the drawn
 * look its own seams. A screen's head is its label first ("NEEDS YOU"), then what it's about.
 */
export type Parts = string | readonly string[];
/** The gap between a line's parts (three spaces), and the wider one between a hint's gestures. */
export const GAP = 15, HINT_GAP = 25;

export const partsOf = (p: Parts | null | undefined): string[] => (p == null ? [] : typeof p === 'string' ? [p] : [...p]);

/**
 * `parts` as one line of firmware text: each cleaned, empty ones left out, a gap of `px` between
 * them. The gap goes in after clean(), which collapses runs of spaces: a line joined first and
 * cleaned after loses its gaps.
 */
export function gapJoin(parts: Parts | null | undefined, px = GAP): string {
  return partsOf(parts).map(clean).filter(Boolean).join(spaces(px));
}

/** `left` and `right` on one line of `width` px, `right` flush with the end; `left` gives way first. */
export function spread(left: string, right: string, width: number, minGap = 10): string {
  const rw = nativeWidth(right);
  const l = nativeFit(left, width - rw - minGap);
  const gap = width - nativeWidth(l) - rw;
  // Spaces are 5 px: round rather than floor, so right edges line up within 2.5 px (SAFE covers the overshoot).
  return l + ' '.repeat(Math.max(1, Math.round(gap / charWidth(' ')))) + right;
}

/** `s` centred in `width` px (the font has no alignment: leading spaces do it). */
export function centre(s: string, width: number): string {
  const t = nativeFit(s, width);
  return spaces((width - nativeWidth(t)) / 2) + t;
}

/** Word-wrap into at most `maxLines` lines of `width` px; the last line gets an ellipsis if cut. */
export function wrap(s: string, width: number, maxLines: number): string[] {
  const words = s.split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let cur = '';
  for (let i = 0; i < words.length; i++) {
    const next = cur ? `${cur} ${words[i]}` : words[i];
    if (nativeWidth(next) <= width) { cur = next; continue; }
    if (lines.length === maxLines - 1) { lines.push(nativeFit(words.slice(i).reduce((a, w) => `${a} ${w}`, cur).trim(), width)); return lines; }
    if (cur) lines.push(cur);
    cur = nativeWidth(words[i]) <= width ? words[i] : nativeFit(words[i], width);
  }
  if (cur) lines.push(cur);
  return lines.slice(0, maxLines);
}

export const boxHeight = (lines: number, border = 1, pad = 0) => lines * LINE + 2 * border + 2 * pad;

/**
 * The most a text box may hold, in UTF-8 bytes. The firmware refuses a box over about 999 bytes (the
 * simulator 0.9.5 took 998 and refused 1498; a refused rebuild changes nothing and a refused update
 * leaves the card blank), and widths don't bound bytes: Cyrillic is 2 bytes a letter, CJK 3, and a
 * line of quotes or dots (3 and 2 bytes, 5 px wide) can pass 300. A little room is kept below it.
 */
export const BOX_BYTES = 990;

/** The size of `s` in UTF-8, as the glasses count it. */
export function utf8Bytes(s: string): number {
  let n = 0;
  for (const ch of s) {
    const cp = ch.codePointAt(0)!;
    n += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
  }
  return n;
}

/**
 * `text` (lines joined by line breaks) cut to at most `max` bytes: whole lines go first, from its end
 * (`keepEnd`: from its start, for captions and a feed whose newest line matters most), then the one
 * line left loses characters, ending in "…". Text that fits comes back as it was.
 */
export function fitBytes(text: string, max = BOX_BYTES, keepEnd = false): string {
  if (utf8Bytes(text) <= max) return text;
  const lines = text.split('\n');
  while (lines.length > 1 && utf8Bytes(lines.join('\n')) > max) {
    if (keepEnd) lines.shift(); else lines.pop();
  }
  if (utf8Bytes(lines.join('\n')) <= max) return lines.join('\n');
  const one = lines[0];
  const ell = '…', room = max - utf8Bytes(ell);
  let out = '', n = 0;
  for (const ch of one) {
    const b = utf8Bytes(ch);
    if (n + b > room) break;
    out += ch;
    n += b;
  }
  return out.trimEnd() + ell;
}
