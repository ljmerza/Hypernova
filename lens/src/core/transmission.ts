// "Transmission": text that arrives like someone saying it (Settings: "Text arrives: Like speech").
// Words come a few at a time at a speaking pace, with a short pause at a comma and a longer one at a
// full stop or a new paragraph. Code appears whole, never word by word. Each step is one text update
// on the glasses, and steps are never closer than MIN_STEP_MS, so the glasses get a few a second at
// most. Pure: the lens keeps one Stream per piece of text (src/lens/transmit.ts).

/** A range of the text that appears whole (code), [start, end). */
export type Atom = [number, number];

/** Words per step at most: "a few at a time". */
export const STEP_WORDS = 3;
/** No two steps closer than this: a few text updates a second at most. */
export const MIN_STEP_MS = 300;
/** The pace's range on the phone, in words a minute; a person reading aloud says about 160. */
export const PACE_MIN = 110, PACE_MAX = 260, PACE_DEFAULT = 170;

/** Pauses after a step, in words' time: at a comma, at the end of a sentence, at a paragraph. */
const PAUSE = { comma: 0.6, stop: 1.4, para: 2.2 } as const;

const atomAt = (atoms: Atom[], i: number) => atoms.find(([a, b]) => i >= a && i < b) ?? null;

/**
 * The next step from `from`: up to STEP_WORDS words, ending early at punctuation or a line break,
 * a code atom whole. `end` is where the shown text then ends; `ms` how long until the step after.
 */
export function nextStep(text: string, from: number, atoms: Atom[], wpm: number): { end: number; ms: number } {
  const wordMs = 60_000 / Math.max(60, wpm);
  let i = from, words = 0, pause = 0;
  while (i < text.length && words < STEP_WORDS) {
    // Spaces and line breaks before the word go with it.
    while (i < text.length && /\s/.test(text[i])) i++;
    if (i >= text.length) break;
    const atom = atomAt(atoms, i);
    if (atom) {
      // Code shows whole, and takes a moment more to look at than a word.
      i = atom[1];
      words += 2;
      pause = Math.max(pause, PAUSE.comma);
      break;
    }
    let j = i;
    while (j < text.length && !/\s/.test(text[j]) && !atomAt(atoms, j)) j++;
    const word = text.slice(i, j);
    i = j;
    words++;
    if (i >= text.length) break;
    // The spaces after the word: a line break ends a paragraph.
    let k = i;
    while (k < text.length && /\s/.test(text[k])) k++;
    if (text.slice(i, k).includes('\n')) { pause = PAUSE.para; break; }
    if (/[.!?…]["”’)\]]*$/.test(word)) { pause = PAUSE.stop; break; }
    if (/[,;:—–]["”’)\]]*$/.test(word) || /^[—–]/.test(text[k] ?? '')) { pause = PAUSE.comma; break; }
  }
  return { end: i, ms: Math.max(MIN_STEP_MS, Math.round((words + pause) * wordMs)) };
}

/** Where the shown text may end for `fraction` of it read aloud: at the end of a word, never inside code. */
export function syncEnd(text: string, atoms: Atom[], fraction: number): number {
  let end = Math.round(Math.max(0, Math.min(1, fraction)) * text.length);
  if (end >= text.length) return text.length;
  const atom = atomAt(atoms, end);
  if (atom) return atom[0] > 0 ? atom[0] : atom[1];
  while (end > 0 && !/\s/.test(text[end - 1])) end--;
  return end;
}

/**
 * The code in a reply, as ranges of its plain text (`plainText(md)`): every line of a fenced block,
 * the block as one range, and each `inline` span. They appear whole.
 */
export function codeAtoms(md: string, plain: string): Atom[] {
  const atoms: Atom[] = [];
  const fenced = new Set<string>();
  let inFence = false;
  for (const raw of md.replace(/\r/g, '').split('\n')) {
    if (/^\s*(```|~~~)/.test(raw)) { inFence = !inFence; continue; }
    if (inFence && raw.trim()) fenced.add(raw.trim());
  }
  if (fenced.size) {
    let off = 0;
    let open: Atom | null = null;
    for (const line of plain.split('\n')) {
      const isCode = fenced.has(line.trim());
      if (isCode) open = open ? [open[0], off + line.length] : [off, off + line.length];
      else if (open) { atoms.push(open); open = null; }
      off += line.length + 1;
    }
    if (open) atoms.push(open);
  }
  let cursor = 0;
  for (const m of md.matchAll(/`([^`\n]+)`/g)) {
    const at = plain.indexOf(m[1], cursor);
    if (at < 0) continue;
    if (!atomAt(atoms, at)) atoms.push([at, at + m[1].length]);
    cursor = at + m[1].length;
  }
  return atoms.sort((a, b) => a[0] - b[0]);
}

/** One piece of text arriving: how much shows, and when the next step is due. */
export interface Stream { text: string; atoms: Atom[]; shown: number; nextAt: number; seenAt: number }

/**
 * `s` at `now`: every step that's due, taken in order. New text after everything was shown starts
 * from now, not from when the last step was due (a reply that grows after a pause doesn't arrive in
 * one burst).
 */
export function advance(s: Stream, text: string, atoms: Atom[], now: number, wpm: number): Stream {
  let { shown, nextAt } = s;
  if (text.length < shown || !text.startsWith(s.text.slice(0, shown))) shown = Math.min(shown, commonPrefix(s.text, text));
  if (shown >= s.text.length && text.length > shown) nextAt = Math.max(nextAt, now);
  let guard = 0;
  while (shown < text.length && now >= nextAt && guard++ < 10_000) {
    const step = nextStep(text, shown, atoms, wpm);
    if (step.end <= shown) break;
    shown = step.end;
    nextAt += step.ms;
    // Far behind (a timer that slept): pick up from now rather than racing through the backlog.
    if (nextAt < now - 2000) nextAt = now;
  }
  return { text, atoms, shown, nextAt, seenAt: now };
}

/** How many characters `a` and `b` start with in common. */
export function commonPrefix(a: string, b: string): number {
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i++;
  return i;
}
