// "What changed?" and "show diff": the files a session's last turn changed, said in a sentence or
// two, and its diff as one text the lens scrolls. The diff comes with each edit's event (the daemon's
// `diff`, PROTOCOL.md "Types": its hunks, as Claude Code's own patch has them), so nothing more is
// asked of the Mac than the turn the lens already loads. Pure.
import type { HnEvent } from './types.ts';
import { charWidth, clean, GAP, nativeFit, nativeWidth, spaces } from './font.ts';
import { lastTurn, firstSentence } from './result.ts';

export interface Hunk { oldStart: number; oldLines: number; newStart: number; newLines: number; lines: string[] }

export interface FileChange {
  path: string;
  /** The file's own name: "Solar.kt". */
  name: string;
  added: number;
  removed: number;
  /** In the order they were made; empty when the Mac didn't send a diff (an older Hypernova). */
  hunks: Hunk[];
  /** Made in this turn (Write of a new file). */
  created: boolean;
}

const EDITS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit']);
const base = (p: string) => p.split('/').filter(Boolean).pop() ?? p;

/** The files the last turn changed, each once, in the order it first touched them. */
export function turnChanges(events: HnEvent[]): FileChange[] {
  const out = new Map<string, FileChange>();
  for (const e of lastTurn(events)) {
    if (e.kind !== 'tool' || !EDITS.has(e.name) || e.status === 'error') continue;
    const d = e.diff;
    const path = d?.path || e.path || String(e.input?.file_path ?? e.input?.notebook_path ?? '');
    if (!path) continue;
    let f = out.get(path);
    if (!f) { f = { path, name: base(path), added: 0, removed: 0, hunks: [], created: false }; out.set(path, f); }
    if (!d) continue;
    f.added += d.added ?? 0;
    f.removed += d.removed ?? 0;
    const hunks = Array.isArray(d.hunks) ? d.hunks : [];
    if (hunks.length && hunks.every(h => h.oldStart === 0 && h.oldLines === 0) && !f.hunks.length) f.created = true;
    f.hunks.push(...hunks);
  }
  return [...out.values()];
}

const lines = (n: number) => `${n} line${n === 1 ? '' : 's'}`;
/** "and"-joined: "A", "A and B", "A, B and C". */
const list = (xs: string[]) => xs.length < 2 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs.at(-1)}`;

/** "+12 -4", or nothing when the Mac sent no counts. The font has no minus sign: a hyphen is as wide as the plus. */
export function counts(f: { added: number; removed: number }): string {
  return f.added || f.removed ? `+${f.added} -${f.removed}` : '';
}

/**
 * One or two sentences: which files, how many lines, then what Claude said about it. "hyperscribe
 * changed ExportSheet.kt and SettingsStore.kt: 18 lines added, 4 removed. Fixed the export so it
 * remembers the last folder."
 */
export function changesSpoken(project: string, changes: FileChange[], reply = ''): string {
  if (!changes.length) return `${project}'s last turn didn't change any files.`;
  const added = changes.reduce((n, f) => n + f.added, 0), removed = changes.reduce((n, f) => n + f.removed, 0);
  const amount = [added ? `${lines(added)} added` : '', removed ? (added ? `${removed} removed` : `${lines(removed)} removed`) : ''].filter(Boolean).join(', ');
  let first: string;
  if (changes.length <= 3) first = `${project} changed ${list(changes.map(f => f.name))}${amount ? `: ${amount}` : ''}.`;
  else {
    const most = [...changes].sort((a, b) => b.added + b.removed - (a.added + a.removed))[0];
    first = `${project} changed ${changes.length} files${amount ? `, ${amount}` : ''}${most.added + most.removed ? `, the most in ${most.name}` : ''}.`;
  }
  const said = firstSentence(reply, 160);
  return said ? `${first} ${said}` : first;
}

/** The summary card's lines: one per file, its name and its counts at the right. */
export function changeRows(changes: FileChange[]): Array<{ name: string; counts: string }> {
  return changes.map(f => ({ name: f.created ? `${f.name} (new)` : f.name, counts: counts(f) }));
}

/** A diff as the lens shows it: one text that scrolls (core/scroll.ts), and whose each line is. */
export interface DiffText {
  lines: string[];
  /** The file (of `changes`) each line belongs to: the card's header names the one at the top. */
  file: number[];
  /** The line each file starts on (a tap goes to the next). */
  starts: number[];
}

/** The marker column: + and - are 10 px in the firmware font, so a context line's two spaces line up with them. */
const MARK = { '+': '+', '-': '-', ' ': '  ' } as const;
/** Indentation at half its width: four spaces of code take two on the lens (10 px), and at most this much. */
const MAX_INDENT_PX = 60;
/** A long file's lines, at most (about a dozen lensfuls): the rest is on the phone. */
const MAX_LINES_PER_FILE = 80;

/**
 * A diff for the lens as one text that scrolls, never pages. A line is its marker (+, - or two
 * spaces, which are as wide), a space, the code's indentation at half width (the font is
 * proportional, so this is as near to monospace as it gets) and the code, wrapped under itself.
 * Each hunk begins with where it is: "… line 42" ("new file" for a file made in this turn). Each
 * file after the first begins with its name and counts ("── PairTest.kt   +4 -0"), so where one
 * file ends and the next begins shows in the text as it scrolls by (the card's header names the
 * file at the top).
 */
export function diffText(changes: FileChange[], width: number): DiffText {
  const out: DiffText = { lines: [], file: [], starts: [] };
  changes.forEach((f, i) => {
    const body: string[] = [];
    if (i > 0) body.push(nativeFit(`── ${f.name}${counts(f) ? `${spaces(GAP)}${counts(f)}` : ''}`, width));
    if (!f.hunks.length) body.push('No diff for this file from the Mac.');
    for (const h of f.hunks) {
      body.push(f.created ? 'new file' : `… line ${h.newStart || h.oldStart}`);
      for (const raw of h.lines) body.push(...codeLines(raw, width));
    }
    if (body.length > MAX_LINES_PER_FILE) body.splice(MAX_LINES_PER_FILE - 1, Infinity, nativeFit('… more on the phone', width));
    out.starts.push(out.lines.length);
    for (const l of body) { out.lines.push(l); out.file.push(i); }
  });
  return out;
}

/** One line of a diff as lens lines: the marker, the indentation at half width, the code wrapped under itself. */
export function codeLines(raw: string, width: number): string[] {
  const sign = raw[0] === '+' || raw[0] === '-' ? raw[0] : ' ';
  const code = (raw[0] === '+' || raw[0] === '-' || raw[0] === ' ' ? raw.slice(1) : raw).replace(/\t/g, '    ');
  const lead = /^ */.exec(code)![0].length;
  const indent = spaces(Math.min(MAX_INDENT_PX, lead * charWidth(' ') / 2));
  const mark = `${MARK[sign as keyof typeof MARK]} `;
  const text = clean(code.slice(lead));
  if (!text) return [mark.trimEnd() || ' '];
  // A wrapped line's next parts sit a little further in (10 px), under the code, not under the marker.
  const cont = spaces(nativeWidth(mark) + nativeWidth(indent) + 10);
  const parts = wrapCode(text, width - nativeWidth(cont));
  return parts.map((p, k) => (k === 0 ? `${mark}${indent}${p}` : `${cont}${p}`));
}

/**
 * Code wrapped to `width` px: between words, and a word too long for a line (a call chain, a path)
 * after its last "(", ".", ",", "/" or similar that fits, so `rememberLauncher(OpenDocumentTree())`
 * breaks at the bracket, not inside a name.
 */
export function wrapCode(text: string, width: number): string[] {
  const out: string[] = [];
  let cur = '';
  const push = (word: string) => {
    let w = word;
    while (nativeWidth(w) > width) {
      let cut = 0, px = 0;
      for (let i = 0; i < w.length; i++) {
        px += nativeWidth(w[i]);
        if (px > width) break;
        if (/[(.,:/[{=]/.test(w[i]) && i > 0) cut = i + 1;
      }
      if (!cut) { cut = 1; px = nativeWidth(w[0]); for (let i = 1; i < w.length && px + nativeWidth(w[i]) <= width; i++) { px += nativeWidth(w[i]); cut = i + 1; } }
      out.push(w.slice(0, cut));
      w = w.slice(cut);
    }
    cur = w;
  };
  for (const word of text.split(' ').filter(Boolean)) {
    const next = cur ? `${cur} ${word}` : word;
    if (nativeWidth(next) <= width) { cur = next; continue; }
    if (cur) out.push(cur);
    cur = '';
    push(word);
  }
  if (cur) out.push(cur);
  return out;
}
