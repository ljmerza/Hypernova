// The drawn look's type (lens/look.ts): the phone app's Manrope and JetBrains Mono, measured from the
// font files (type-table.ts, made by dev/type-metrics.mjs) so that lines are laid out the same way in
// the WebView that draws them and under node. Text is drawn without kerning, so a run is exactly as
// wide as its advances add up to. The firmware font's counterpart is font.ts. Pure: runs under node.
import { MONO, SANS } from './type-table.ts';
import { clean } from './font.ts';

export type Family = 'sans' | 'mono';

/** A font on the lens: family, size in px, weight; `track`: px added after each character (tracked caps). */
export interface Face { family: Family; size: number; weight: number; track?: number }

/** Thousandths of an em for a glyph a font lacks: it falls back to a system font, so assume it wide. */
const UNKNOWN = 800;
const WEIGHTS = Object.keys(SANS).map(Number).sort((a, b) => a - b);

const tables = new Map<string, Map<string, number>>();
function table(family: Family, weight: number): Map<string, number> {
  const w = family === 'mono' ? 0 : WEIGHTS.reduce((a, b) => (Math.abs(b - weight) < Math.abs(a - weight) ? b : a));
  const key = `${family}${w}`;
  let t = tables.get(key);
  if (!t) {
    const src = family === 'mono' ? MONO : SANS[w];
    t = new Map([...src.chars].map((ch, i) => [ch, src.widths[i]]));
    tables.set(key, t);
  }
  return t;
}

/** The font has a glyph for `ch` (else it is drawn by a fallback). */
export const hasFaceGlyph = (ch: string, f: Pick<Face, 'family' | 'weight'>) => table(f.family, f.weight).has(ch);

/** One character's advance in px, its tracking included. */
export function advance(ch: string, f: Face): number {
  return ((table(f.family, f.weight).get(ch) ?? UNKNOWN) * f.size) / 1000 + (f.track ?? 0);
}

/** How wide `s` is drawn in `f`, in px (the tracking after its last character included). */
export function measure(s: string, f: Face): number {
  const t = table(f.family, f.weight);
  let w = 0, n = 0;
  for (const ch of s) { w += t.get(ch) ?? UNKNOWN; n++; }
  return (w * f.size) / 1000 + n * (f.track ?? 0);
}

/** `s` shortened to at most `width` px, ending in "…" when something was cut. */
export function fitFace(s: string, width: number, f: Face): string {
  if (width <= 0) return '';
  if (measure(s, f) <= width) return s;
  const ell = advance('…', f);
  let w = 0, out = '';
  for (const ch of s) {
    const a = advance(ch, f);
    if (w + a + ell > width) break;
    out += ch;
    w += a;
  }
  out = out.trimEnd();
  return out ? `${out}…` : ell <= width ? '…' : '';
}

/**
 * One paragraph wrapped to `width` px in `f`, word by word; a word longer than a line (a path, a URL)
 * is broken where it reaches the edge. The same rules as the firmware font's wrapAll (text.ts).
 */
export function wrapFace(s: string, width: number, f: Face): string[] {
  const lines: string[] = [];
  let cur = '';
  for (const word of clean(s).split(' ').filter(Boolean)) {
    const next = cur ? `${cur} ${word}` : word;
    if (measure(next, f) <= width) { cur = next; continue; }
    if (cur) lines.push(cur);
    if (measure(word, f) <= width) { cur = word; continue; }
    cur = '';
    for (const ch of word) {
      if (cur && measure(cur + ch, f) > width) { lines.push(cur); cur = ''; }
      cur += ch;
    }
  }
  if (cur) lines.push(cur);
  return lines;
}

/** Plain text (one paragraph a line) as lines of at most `width` px, paragraph after paragraph. */
export function faceLines(plain: string, width: number, f: Face): string[] {
  return plain.split('\n').flatMap(p => wrapFace(p, width, f));
}

/** The CSS font for a canvas (the families are registered under these names by lens/painter.ts). */
export const FAMILY = { sans: 'HN Manrope', mono: 'HN Mono' } as const;
export const cssFont = (f: Face) => `${f.weight} ${f.size}px "${FAMILY[f.family]}"`;
