// Claude writes markdown; the lens shows plain lines in the firmware font, a few at a time. Pure.
import { clean, GAP, nativeFit, nativeWidth, spaces } from './font.ts';

type Kind = 'p' | 'li' | 'block' | 'blank';

function inline(t: string): string {
  return t
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/(\*\*|__)(?=\S)(.+?)(?<=\S)\1/g, '$2')
    .replace(/(^|[\s(])\*(?=\S)([^*]+?)(?<=\S)\*(?=[\s).,;:!?]|$)/g, '$1$2')
    .replace(/(^|[\s(])_(?=\S)([^_]+?)(?<=\S)_(?=[\s).,;:!?]|$)/g, '$1$2')
    .replace(/<\/?[a-z][^>]*>/gi, '')
    .trim();
}

/** Markdown to plain text: one paragraph, list item, heading or table row per line, no markers. */
export function plainText(md: string): string {
  const tagged: Array<{ t: string; k: Kind }> = [];
  let fence = false;
  for (const raw of md.replace(/\r/g, '').split('\n')) {
    const line = raw.replace(/\s+$/, '');
    if (/^\s*(```|~~~)/.test(line)) { fence = !fence; continue; }
    if (fence) { if (line.trim()) tagged.push({ t: line.trim(), k: 'block' }); continue; }
    if (!line.trim()) { tagged.push({ t: '', k: 'blank' }); continue; }
    if (/^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/.test(line)) continue;              // table rule
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { tagged.push({ t: '', k: 'blank' }); continue; } // horizontal rule
    if (/^\s*\|.*\|\s*$/.test(line)) {
      // A table's cells apart, a gap between them (wrapAll keeps a gap of three spaces or more).
      tagged.push({ t: line.trim().replace(/^\||\|$/g, '').split('|').map(c => inline(c)).filter(Boolean).join(CELL_GAP), k: 'block' });
      continue;
    }
    const h = /^\s*#{1,6}\s+(.*)$/.exec(line);
    if (h) { tagged.push({ t: inline(h[1]), k: 'block' }); continue; }
    const li = /^\s*(?:[-*+]\s+(?:\[[ xX]\]\s+)?|(\d+[.)])\s+)(.*)$/.exec(line);
    if (li) { tagged.push({ t: (li[1] ? `${li[1]} ` : '• ') + inline(li[2]), k: 'li' }); continue; }
    tagged.push({ t: inline(line.replace(/^\s*>\s?/, '')), k: 'p' });
  }
  // A plain line right after a paragraph or list item continues it (markdown soft wraps).
  const paras: string[] = [];
  let prev = 'blank' as Kind;
  for (const { t, k } of tagged) {
    if (k === 'blank') { prev = 'blank'; continue; }
    const continues = k === 'p' && (prev === 'p' || prev === 'li') && paras.length > 0;
    if (continues) paras[paras.length - 1] += ` ${t}`;
    else paras.push(t);
    // A list item's soft-wrapped lines stay part of the item.
    if (!continues) prev = k;
  }
  return paras.filter(Boolean).join('\n');
}

/** Between a table's cells: three spaces or more are a gap (GAP px), which wrapAll keeps. */
export const CELL_GAP = '   ';

/**
 * Wrap one paragraph to `width` px, breaking words that are longer than a line (paths, URLs). Three
 * spaces or more are a gap, kept as one (a table's cells): clean() alone would make them a space.
 */
export function wrapAll(s: string, width: number): string[] {
  const lines: string[] = [];
  let cur = '';
  s.split(/ {3,}/).forEach((segment, k) => {
    clean(segment).split(' ').filter(Boolean).forEach((word, i) => {
      const sep = !cur ? '' : i === 0 && k > 0 ? spaces(GAP) : ' ';
      const next = cur + sep + word;
      if (nativeWidth(next) <= width) { cur = next; return; }
      if (cur) lines.push(cur);
      if (nativeWidth(word) <= width) { cur = word; return; }
      cur = '';
      for (const ch of word) {
        if (cur && nativeWidth(cur + ch) > width) { lines.push(cur); cur = ''; }
        cur += ch;
      }
    });
  });
  if (cur) lines.push(cur);
  return lines;
}

/**
 * Plain text (plainText's, or as much of it as has arrived) as lines of at most `width` px, one
 * paragraph after another: a reply or a plan as one text that scrolls (core/scroll.ts), never pages.
 * Wrapping is greedy, so the lines of a part of the text are the whole text's, but for the last.
 */
export function textLines(plain: string, width: number): string[] {
  return plain.split('\n').flatMap(p => wrapAll(p, width));
}

const wrapText = (text: string, width: number) => textLines(plainText(text), width);

/** The last `rows` lines of plain `text` wrapped to `width`, for live captions. */
export function tailLines(text: string, width: number, rows: number): string[] {
  return wrapAll(text, width).slice(-rows);
}

/** The first `rows` lines, the last one ending in "…" when there is more. */
export function headLines(text: string, width: number, rows: number): string[] {
  const lines = wrapText(text, width);
  if (lines.length <= rows) return lines;
  const head = lines.slice(0, rows);
  let last = head[rows - 1];
  while (last && nativeWidth(last + '…') > width) last = last.slice(0, -1);
  head[rows - 1] = last.trimEnd() + '…';
  return head;
}

/** Plain text for speech: code blocks named rather than read, list items read as sentences, one
 * paragraph per line (so long text can be cut between paragraphs, see speechChunks). */
export function speakable(md: string): string {
  const noCode = md.replace(/```[\s\S]*?```/g, '\n(code)\n');
  return plainText(noCode).split('\n').map(l => l.replace(/^• /, '').replace(/([^.!?:)])$/, '$1.').replace(/\s+/g, ' ').trim()).filter(Boolean).join('\n');
}

/**
 * Text for read-aloud in pieces of at most `max` characters (the Mac renders at most 8000 at a
 * time, DAEMON.md 2), cut between paragraphs, else between sentences, else between words, and
 * played one after another. The first piece is also the first sound: a short one starts sooner.
 */
export function speechChunks(text: string, max = 8000): string[] {
  const out: string[] = [];
  let cur = '';
  const push = (piece: string, sep: string) => {
    if (!piece) return;
    if (cur && cur.length + sep.length + piece.length <= max) { cur += sep + piece; return; }
    if (cur) out.push(cur);
    cur = piece;
  };
  for (const para of text.split(/\n+/).map(p => p.trim()).filter(Boolean)) {
    if (para.length <= max) { push(para, '\n'); continue; }
    for (const sentence of para.match(/[^.!?]+(?:[.!?]+["”’)\]]*|$)\s*/g) ?? [para]) {
      const t = sentence.trim();
      if (t.length <= max) { push(t, ' '); continue; }
      for (const word of t.split(/\s+/)) {
        for (let i = 0; i < word.length; i += max) push(word.slice(i, i + max), ' ');
      }
    }
  }
  if (cur) out.push(cur);
  return out;
}

/** A plan in a few lines: its steps, one line each (a title line like "Plan" says nothing). */
export function summaryLines(md: string, width: number, rows: number): { lines: string[]; steps: number } {
  const lines = plainText(md).split('\n').filter(Boolean);
  const items = lines.filter(l => /^(• |\d+[.)] )/.test(l));
  const src = items.length ? items : lines.filter((l, i) => !(i === 0 && l.length < 40 && !/[.!?:]$/.test(l)));
  return { lines: src.slice(0, rows).map(l => nativeFit(clean(l), width)), steps: items.length };
}
