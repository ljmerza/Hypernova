// What a spoken utterance means: one of the voice words, or a prompt for a session, maybe routed by
// naming a project first ("in hyperscribe, fix …", "new session in 0stems: …"). Pure.
import type { Project, Session } from './types.ts';
import { spokenName } from './format.ts';
import { parseTelescope, type ScopeAsk } from './telescope.ts';
import { parseRoad } from './phrases.ts';

export type VoiceWord = 'status' | 'approve' | 'always' | 'deny' | 'stop' | 'read' | 'send' | 'telescope' | 'watch' | 'discard' | 'mute' | 'unmute'
  | 'battery' | 'awake' | 'sleep' | 'extend'
  // On a bike or a walk (core/phrases.ts).
  | 'debrief' | 'note' | 'next' | 'previous' | 'back' | 'option' | 'plan' | 'goahead' | 'continue' | 'eclipse' | 'eclipseoff'
  | 'moving' | 'still' | 'changes' | 'diff' | 'guide' | 'refused';

export type Route =
  | { kind: 'session'; sessionId: string; project: string }
  | { kind: 'new'; projectPath: string; project: string }
  /** A new session in a new subfolder of Nebula, named from its first prompt (core/nebula.ts). */
  | { kind: 'nebula'; project: string };

/** Where a new session goes when no project is named: a folder of its own in Nebula. */
export const NEBULA_ROUTE: Route = { kind: 'nebula', project: 'Nebula' };

export type Utterance =
  /**
   * `ask`: for "telescope", what to point it at (a page on the Mac: `rest` is its address as said).
   * `n`: for "one", "two", "three", the option. `rest`: after "approve", a risky command's verb
   * ("approve push"); after "note", the note.
   */
  | { kind: 'word'; word: VoiceWord; rest: string; ask?: ScopeAsk; n?: number }
  | { kind: 'prompt'; text: string; route: Route | null; sendNow: boolean };

export interface VoiceContext {
  sessions: Session[];
  projects: Project[];
  /** The session the lens points at, preferred when its project is named. */
  focusId: string | null;
  /** An approval, question or menu is on the lens: only then is "No, …" an answer to it. */
  needs?: boolean;
}

/** Lowercase words without punctuation (apostrophes inside words kept). */
export function words(s: string): string[] {
  return s.toLowerCase().replace(/[’']/g, "'").replace(/[^\p{L}\p{N}' ]+/gu, ' ').split(/\s+/).map(w => w.replace(/^'+|'+$/g, '')).filter(Boolean);
}
const squash = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '');

// Whole utterances that are commands. Leading "okay", "hey hypernova", "please" are ignored.
const WORDS: Array<[VoiceWord, RegExp]> = [
  ['status', /^(status|what's the status|whats the status|status update|how are things|what's going on|whats going on)$/],
  ['always', /^(always allow|allow always|always)$/],
  ['approve', /^(approve|approve it|approved|allow|allow it|yes approve|accept|accept it)$/],
  ['deny', /^(deny|deny it|reject|reject it|decline)$/],
  ['stop', /^(stop|stop it|stop now|interrupt|halt)$/],
  ['read', /^(read the reply|read the reply to me|read it|read it to me|read it out|read that|read me the reply|read reply|read)$/],
  ['send', /^(send it|send|send that|send it now)$/],
  // "Telescope" replaced "show me", which stays as a quiet alias only on its own: "show me …" is
  // how many prompts start ("show me the diff"). The rest of Telescope's phrases: parseTelescope.
  ['telescope', /^(show me)$/],
  // "Watch": the telescope's picture, looked at again every few seconds for a minute.
  ['watch', /^(watch|watch it|watch that|keep watching|telescope watch|watch telescope)$/],
  ['discard', /^(never mind|nevermind|discard|discard it|cancel|cancel that|scratch that)$/],
  // Reading aloud off and on (Settings' "Read replies aloud"). "Unmute" is often heard as two words.
  ['mute', /^(mute|mute it|mute reading|quiet|be quiet|silence|sound off|voice off)$/],
  ['unmute', /^(unmute|un mute|unmute it|sound on|voice on)$/],
  // The battery card: the glasses' and the Mac's.
  ['battery', /^(battery|batteries|battery level|power|how's the battery|hows the battery|what's the battery|whats the battery|show battery|show the battery)$/],
  // Stay awake: the Mac in the backpack.
  ['awake', /^(stay awake|keep awake|keep it awake|keep the mac awake|keep my mac awake|don't sleep|dont sleep|do not sleep|backpack|backpack mode)$/],
  // Backpack's "+2 h", offered ten minutes before its 8 hours end. Only these exact words: "extend the
  // parser" is a prompt.
  ['extend', /^(extend|extend it|extend backpack|extend stay awake|extend two hours|extend by two hours|extend for two hours|two more hours|plus two hours|add two hours|stay awake two more hours)$/],
  ['sleep', /^(let it sleep|let the mac sleep|let my mac sleep|you can sleep|you can sleep now|let it rest)$/],
];
const FILLER = /^((ok|okay|hey|hi)( hypernova| lens)?|hypernova|please)\s+/;
const POLITE = /\s+please$/;

/** Where the page is named, in the words as recognised (so "localhost:3000/settings" keeps its path). */
const LOCAL_AT = /(?:localhost|127\.0\.0\.1|\[::1\]|\bport\b|\b\d{4,5}\b).*$/i;

export function parseUtterance(raw: string, ctx: VoiceContext): Utterance {
  let text = raw.trim().replace(/\s+/g, ' ');
  let sendNow = false;
  const tail = /[\s,.;:!-]*\bsend it(?: now)?[.!]?$/i;
  const norm = () => words(text).join(' ').replace(FILLER, '').replace(POLITE, '');
  const whole = norm();
  for (const [word, re] of WORDS) if (re.test(whole)) return word === 'telescope' ? { kind: 'word', word, rest: '', ask: {} } : { kind: 'word', word, rest: '' };
  const road = parseRoad(text, whole);
  if (road) return road;
  // "deny, …" only with punctuation after the word, so "no tests ran" stays a prompt. And "No, …"
  // answers only a card that asks: anywhere else it is how you correct Claude ("No, use the other
  // file instead."), which belongs to the session in front of you.
  const denyRest = /^\s*(deny|no|reject)\b(?: it)?\s*[,:.;-]\s*(.+)$/i.exec(text);
  if (denyRest && (ctx.needs || denyRest[1].toLowerCase() !== 'no')) return { kind: 'word', word: 'deny', rest: denyRest[2].trim() };
  // "telescope", "telescope emulator", "telescope localhost 3000" (a page on this Mac, whose
  // address as said keeps its path: "localhost:3000/settings").
  const scope = parseTelescope(whole);
  if (scope && 'local' in scope) return { kind: 'word', word: 'telescope', rest: (LOCAL_AT.exec(text)?.[0] ?? '').replace(/[.!?,;]+$/, '').trim(), ask: { source: 'web' } };
  if (scope) return { kind: 'word', word: 'telescope', rest: '', ask: scope };
  if (tail.test(text) && whole !== 'send it') {
    sendNow = true;
    // "Fix the bug and send it": the "and" (or "then") belonged to the command, not the prompt.
    text = text.replace(tail, '').replace(/[\s,;:-]*\b(?:and(?:\s+then)?|then)$/i, '').trim();
  }

  // "new session in 0stems: …" starts one.
  const lead = (re: RegExp) => re.exec(text);
  const fresh = lead(/^\s*(?:(?:start|open|make|begin)\s+(?:a\s+)?)?new\s+session\s+(?:in|for|on|at)\s+(.+)$/i)
    ?? lead(/^\s*start\s+(?:a\s+)?session\s+(?:in|for|on|at)\s+(.+)$/i);
  if (fresh) {
    const hit = matchProject(fresh[1], ctx);
    if (hit) {
      const project = hit.project;
      return { kind: 'prompt', text: hit.rest, route: { kind: 'new', projectPath: project.path, project: project.name }, sendNow };
    }
  }
  // "new session: …", "new project, …": no project named, so a folder of its own in Nebula.
  const nebula = lead(/^\s*(?:(?:start|open|make|begin|create)\s+(?:a\s+)?)?new\s+(?:session|project)\s*[,:.;-]\s*(.+)$/i);
  if (nebula?.[1].trim()) return { kind: 'prompt', text: nebula[1].trim(), route: NEBULA_ROUTE, sendNow };
  // "in hyperscribe, fix …" / "for 0stems: …" / "hypernova, …" routes to that project's session.
  const named = lead(/^\s*(?:in|for|to|on|tell)\s+(.+)$/i);
  const hit = named ? matchProject(named[1], ctx) : matchProject(text, ctx, true);
  if (hit && hit.rest) return { kind: 'prompt', text: hit.rest.replace(/^to\s+/i, ''), route: routeTo(hit.project.name, hit.project.path, ctx), sendNow };
  return { kind: 'prompt', text, route: null, sendNow };
}

/** The session a prompt for `project` goes to: the focused one if it's there, else the busiest. */
export function routeTo(project: string, projectPath: string, ctx: VoiceContext): Route {
  const inProject = ctx.sessions.filter(s => s.state !== 'ended' && !s.switchedTo && (s.projectName.toLowerCase() === project.toLowerCase() || s.cwd === projectPath));
  const focused = inProject.find(s => s.id === ctx.focusId);
  const rank = (s: Session) => (s.state === 'needs_you' ? 3 : s.state === 'working' ? 2 : 1) * 1e13 + s.lastActivityAt;
  const best = focused ?? [...inProject].sort((a, b) => rank(b) - rank(a))[0];
  return best ? { kind: 'session', sessionId: best.id, project: best.projectName } : { kind: 'new', projectPath, project };
}

interface ProjectHit { project: { name: string; path: string }; rest: string }

/** Every project the Mac knows, with sessions' projects that aren't in the list. */
function candidates(ctx: VoiceContext): Array<{ name: string; path: string }> {
  const out = new Map<string, { name: string; path: string }>();
  for (const p of ctx.projects) out.set(p.name.toLowerCase(), { name: p.name, path: p.path });
  for (const s of ctx.sessions) if (!out.has(s.projectName.toLowerCase())) out.set(s.projectName.toLowerCase(), { name: s.projectName, path: s.cwd });
  return [...out.values()];
}

/** Levenshtein distance, capped for speed. */
function distance(a: string, b: string, cap = 3): number {
  if (Math.abs(a.length - b.length) > cap) return cap + 1;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    let best = i;
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      best = Math.min(best, cur[j]);
    }
    if (best > cap) return cap + 1;
    prev = cur;
  }
  return prev[b.length];
}

/**
 * The project named at the start of `text`, and what follows it. Names are compared as said
 * ("zero stems" is 0stems, "hyper scribe" is Hyperscribe), allowing a letter or two of
 * recognition error in longer names. `needsBreak`: the name must be followed by a comma or colon
 * (for an utterance that starts with a bare name, which could just be a sentence).
 */
export function matchProject(text: string, ctx: VoiceContext, needsBreak = false): ProjectHit | null {
  const tokens = text.trim().split(/\s+/);
  let best: { hit: ProjectHit; score: number } | null = null;
  for (const p of candidates(ctx)) {
    const forms = [...new Set([squash(p.name), squash(spokenName(p.name))])];
    for (let n = 1; n <= Math.min(4, tokens.length); n++) {
      const head = tokens.slice(0, n).join(' ');
      const brk = /[,:;.]$/.test(head);
      if (needsBreak && !brk) continue;
      const said = squash(head);
      if (!said) continue;
      for (const f of forms) {
        const d = said === f ? 0 : f.length >= 5 ? distance(said, f, f.length >= 9 ? 2 : 1) : 9;
        const limit = f.length >= 9 ? 2 : 1;
        if (d > limit) continue;
        const score = 10 - d * 3 + n * 0.1 + (brk ? 0.5 : 0);
        if (!best || score > best.score) best = { hit: { project: p, rest: tokens.slice(n).join(' ').replace(/^[,:;.\s-]+/, '').trim() }, score };
      }
    }
  }
  return best?.hit ?? null;
}
