// Telescope on the lens (DAEMON.md, 3): which source a phrase names, the quarters a picture zooms
// into, and the label over it. Pure: runs under node.
import { ago } from './format.ts';
import type { Shot } from './types.ts';

export type ScopeSource = 'web' | 'emulator' | 'simulator' | 'terminal' | 'image' | 'window';

/**
 * What to point the telescope at: nothing named (it picks), a source, a page on the Mac, a PDF's
 * page; `prefer`: which kind of Android device ("telescope phone", "telescope emulator": the glasses
 * can't say a device id); `device`, `watch`: a watching look at the same device, which tells the Mac
 * to keep only the newest frames.
 */
export interface ScopeAsk { source?: ScopeSource; url?: string; page?: number; prefer?: 'phone' | 'emulator'; device?: string; watch?: boolean }

/** The picture under the label line: the whole width, the rest of the height. */
export const SCOPE = { x: 0, y: 32, w: 576, h: 256 } as const;
/** "Watch": a new look this long after the last answer came, for this long in all. */
export const WATCH_EVERY_MS = 3000;
export const WATCH_FOR_MS = 60_000;

/** The words that name a source after "telescope" (as recognised: lowercase, no punctuation). */
const NAMES: Array<[RegExp, ScopeAsk]> = [
  [/^(?:android )?emulator$/, { source: 'emulator', prefer: 'emulator' }],
  [/^(?:android )?(?:phone|device)$/, { source: 'emulator', prefer: 'phone' }],
  [/^android$/, { source: 'emulator' }],
  [/^(?:ios |iphone )?(?:simulator|iphone|ipad|ios)$/, { source: 'simulator' }],
  [/^(?:web(?: ?page| ?site| app)?|page|site|website|browser)$/, { source: 'web' }],
  [/^(?:terminal|console|shell|command line|cli)$/, { source: 'terminal' }],
  [/^(?:image|picture|chart|graph|plot|diagram|photo|pdf|document|screenshot)$/, { source: 'image' }],
  [/^(?:window|windows|mac app|mac|desktop app)$/, { source: 'window' }],
];
const NUMBERS: Record<string, number> = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, first: 1, second: 2, third: 3, last: 0 };
/** A page on this Mac, as said: "localhost 3000", "port 5173", "3000". */
const LOCAL = /^(?:(?:the )?(?:app|page|site|server) )?(?:(?:on|at) )?(?:(?:localhost|127 0 0 1)(?: \d{2,5}(?: [\p{L}\p{N}' ]+)?)?|port \d{2,5}|\d{4,5})$/u;

/**
 * How "telescope" is misheard (Soniox wrote "To the scope terminal" for "Telescope terminal"). These
 * are ordinary words too ("tell a scope…", "the telescope is…"), so one counts only on its own or
 * before a source's name, never at the start of anything longer.
 */
const NEAR = /^(?:tele scopes?|telescopes|the tele ?scopes?|to the scopes?|to a scopes?|tell a scopes?|tell the scopes?|tele a scopes?)(?: (.*))?$/;

/** The source a word or two after "telescope" name ("on the emulator", "my phone"), else null. */
function named(rest: string): ScopeAsk | null {
  const name = rest.replace(/^(?:on|at|into|to|in) /, '').replace(/^(?:the|my|our) /, '').replace(/^(?:last|latest|newest|current) /, '');
  for (const [re, ask] of NAMES) if (re.test(name)) return { ...ask };
  return null;
}

/**
 * "telescope" (it picks), "telescope emulator", "telescope on the simulator", "telescope pdf page 3",
 * "telescope localhost 3000". `words`: the utterance as recognised words, lowercase without
 * punctuation. Null when it doesn't start with "telescope". Whatever follows that it doesn't know
 * ("telescope my thing") lets the Mac pick: said to the glasses, "telescope" is never a prompt.
 * Its mishearings (NEAR) count only alone or before a source ("to the scope terminal"); anything
 * else after one stays a prompt.
 */
export function parseTelescope(words: string): ScopeAsk | { local: true } | null {
  const near = NEAR.exec(words);
  if (near) {
    if (!near[1]) return {};
    return named(near[1]);
  }
  const m = /^telescope(?: (.*))?$/.exec(words);
  if (!m) return null;
  const rest = (m[1] ?? '').replace(/^(?:on|at|into|to|in) /, '').trim();
  if (!rest || /^(?:it|this|that|the app|what you(?:'ve)? (?:built|made))$/.test(rest)) return {};
  if (LOCAL.test(rest)) return { local: true };
  const page = /^(?:(?:the )?(?:pdf|image|document) )?page (\d{1,4}|\w+)$/.exec(rest);
  if (page) {
    const n = /^\d+$/.test(page[1]) ? Number(page[1]) : NUMBERS[page[1]];
    return n ? { source: 'image', page: n } : { source: 'image' };
  }
  // "my phone", "the last pdf", "our latest chart".
  return named(rest) ?? {};
}

/** The word a picture's source goes by on the label. */
export function sourceWord(source: Shot['source'] | ScopeSource | undefined): string {
  switch (source) {
    case 'url': case 'demo': return 'web';
    case 'screen': return 'screen';
    case undefined: return '';
    default: return source;
  }
}

/** The label line's parts, ["TELESCOPE", "hypernova", "emulator"], and on its right how old the picture is ("20s ago", "just now"). */
export function scopeLabel(project: string, source: string, at: number | null, now: number): { head: string[]; age: string } {
  const age = at === null ? '' : ago(Math.max(0, now - at));
  return { head: ['TELESCOPE', project, source].filter(Boolean), age: age ? (age === 'now' ? 'just now' : `${age} ago`) : '' };
}

export interface Rect { x: number; y: number; w: number; h: number }

/**
 * The four parts a tap zooms into, in reading order: the corners of a wide picture, four bands down
 * a tall one (a phone's screen), four columns across a very wide one: whichever is closest in shape
 * to the lens' picture, so a quarter fills it best.
 */
export function quarters(w: number, h: number, view = SCOPE.w / SCOPE.h): Rect[] {
  const shapes: Array<[number, number]> = [[2, 2], [1, 4], [4, 1]];
  const fit = ([c, r]: [number, number]) => Math.abs(Math.log((w / c) / (h / r) / view));
  const [cols, rows] = shapes.reduce((a, b) => (fit(b) < fit(a) ? b : a));
  const out: Rect[] = [];
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
    const x = Math.round((c * w) / cols), y = Math.round((r * h) / rows);
    out.push({ x, y, w: Math.round(((c + 1) * w) / cols) - x, h: Math.round(((r + 1) * h) / rows) - y });
  }
  return out;
}

/**
 * The whole of a tall picture (a phone's screen) is a dim sliver on the wide lens: its top and
 * bottom halves side by side show it twice as large. True when that is so much larger it's worth it.
 */
export function sideBySide(w: number, h: number, view = SCOPE): boolean {
  const whole = Math.min(view.w / w, view.h / h);
  const halves = Math.min(view.w / (2 * w + gapFor(w)), view.h / Math.ceil(h / 2));
  return halves >= whole * 1.4;
}

/** The dark gap between the halves, in the picture's own pixels. */
export const gapFor = (w: number) => Math.round(w * 0.04);
