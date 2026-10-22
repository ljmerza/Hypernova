// An automation run waiting at an `ask` step (docs/specs/workflows.md; PROTOCOL.md, "Workflows",
// "Answering an ask"). The Mac sends it as a note with `ask` set; the glasses show it as a card with
// its two or three answers, and answer with `POST /v1/automations/runs/{id}/answer`. This file is the
// words and the rules, all pure: reading the note, the countdown, the spoken answers, how a card ends.
import type { AskInfo, AutomationNote } from './types.ts';
import { ago } from './format.ts';

/** What the glasses show and answer: one ask of one run. */
export interface AskCard {
  /** `runId:stepId`: one card per ask, the dedupe key. */
  key: string;
  runId: string;
  stepId: string;
  /** The automation's name: "Review before merge". */
  name: string;
  question: string;
  /** Two or three, in the order the daemon gave them. */
  answers: string[];
  /** One of `answers`; taken when `until` passes. */
  default: string;
  until: number;
  at: number;
}

export type AskBy = 'phone' | 'glasses' | 'mac' | 'timeout';

/** How a card ended, in the daemon's words: who answered, or that the default was taken. */
export interface AskEnd { text: string; by: AskBy }

export const ASK_MAX_ANSWERS = 3;
/** The most of an answer's label the lens shows. */
const LABEL_MAX = 40;

const BY: AskBy[] = ['phone', 'glasses', 'mac', 'timeout'];

/** A `Note.ask` off the wire, or null if it isn't one the glasses can answer. */
export function readAskInfo(raw: unknown): AskInfo | null {
  const a = raw as Partial<AskInfo> | null | undefined;
  if (!a || typeof a !== 'object') return null;
  if (typeof a.stepId !== 'string' || !a.stepId || typeof a.runId !== 'string' || !a.runId) return null;
  if (!Array.isArray(a.answers)) return null;
  const answers = a.answers.filter((x): x is string => typeof x === 'string' && !!x.trim()).map(x => x.trim().slice(0, LABEL_MAX)).slice(0, ASK_MAX_ANSWERS);
  if (answers.length < 2) return null;
  const until = typeof a.until === 'number' && Number.isFinite(a.until) ? a.until : 0;
  if (!until) return null;
  const want = typeof a.default === 'string' ? a.default.trim().toLowerCase() : '';
  // The last answer is the default when the daemon names none we know.
  const def = answers.find(x => x.toLowerCase() === want) ?? answers[answers.length - 1];
  return {
    stepId: a.stepId, runId: a.runId,
    question: typeof a.question === 'string' ? a.question.slice(0, 1000) : '',
    answers, default: def, until,
  };
}

/** The card a note asks for, or null (no ask, or one that is over). */
export function askOfNote(n: AutomationNote | null | undefined, now: number): AskCard | null {
  const a = n?.ask;
  if (!n || !a || a.until <= now) return null;
  return {
    key: `${a.runId}:${a.stepId}`, runId: a.runId, stepId: a.stepId, name: n.name,
    question: (a.question || n.text).trim(), answers: a.answers, default: a.default, until: a.until, at: n.at,
  };
}

/** What `{t:"run"}` says about asks: the open one (or none), and every answered ask step with who answered. */
export interface RunAsks {
  runId: string;
  open: { stepId: string } | null;
  ended: Array<AskEnd & { stepId: string }>;
}

export function readRunAsks(raw: unknown): RunAsks | null {
  const r = raw as { id?: unknown; ask?: unknown; steps?: unknown } | null | undefined;
  if (!r || typeof r !== 'object' || typeof r.id !== 'string' || !r.id) return null;
  const ask = r.ask as { stepId?: unknown } | null | undefined;
  const open = ask && typeof ask === 'object' && typeof ask.stepId === 'string' ? { stepId: ask.stepId } : null;
  const ended: RunAsks['ended'] = [];
  if (Array.isArray(r.steps)) {
    for (const s of r.steps as Array<{ id?: unknown; answer?: { text?: unknown; by?: unknown } | null }>) {
      const a = s?.answer;
      if (!a || typeof s.id !== 'string' || typeof a.text !== 'string') continue;
      ended.push({ stepId: s.id, text: a.text, by: BY.includes(a.by as AskBy) ? (a.by as AskBy) : 'phone' });
    }
  }
  return { runId: r.id, open, ended };
}

/** "Skip in 9m", "Skip in 40s", "Skip now": the default and when it is taken, in one line. */
export function askCountdown(def: string, until: number, now: number): string {
  return `${def} ${until - now < 15_000 ? 'now' : `in ${ago(until - now)}`}`;
}

const WHERE: Record<AskBy, string> = { phone: 'on your phone', glasses: 'on the glasses', mac: 'on the Mac', timeout: '' };

/** The card's last words, calm: who answered first, or that the default was taken. */
export function askEndWords(end: AskEnd | null, def: string): string[] {
  if (!end) return ['Already answered'];
  if (end.by === 'timeout') return [`Took the default: ${end.text || def}`];
  return ['Already answered', `${end.text}, ${WHERE[end.by]}`];
}

/** The default taken when time ran out and the daemon has not said otherwise. */
export const askTimedOut = (def: string): string[] => askEndWords({ text: def, by: 'timeout' }, def);

/** What the glasses say aloud: "Review before merge asks: Post this review? Approve, or Skip." */
export function askSpoken(c: Pick<AskCard, 'name' | 'question' | 'answers'>): string {
  const q = c.question.replace(/\s+/g, ' ').trim();
  const list = c.answers.length === 2 ? `${c.answers[0]}, or ${c.answers[1]}` : `${c.answers.slice(0, -1).join(', ')}, or ${c.answers.at(-1)}`;
  return `${c.name.replace(/[.!?…:]+$/, '')} asks: ${q.replace(/[.!?…]+$/, '')}? ${list}.`.replace(/\?\?/g, '?');
}

const NUMBERS: Record<string, number> = { one: 0, '1': 0, first: 0, two: 1, '2': 1, second: 1, three: 2, '3': 2, third: 2 };
const norm = (s: string) => s.toLowerCase().replace(/[’']/g, '').replace(/[^\p{L}\p{N} ]+/gu, ' ').split(/\s+/).filter(Boolean).join(' ');

/**
 * Which answer did the wearer say (its index), or -1. The answer's own words ("skip", "approve",
 * "post it"), with a "please", "okay" or "it" around them, or its number ("one", "the second").
 * A sentence that only contains an answer is not one: it may be anything.
 */
export function matchAnswer(said: string, answers: readonly string[]): number {
  let s = norm(said).replace(/^(ok|okay|hey|hi|please|yes|the)\s+/, '');
  for (let more = true; more;) { const t = s.replace(/\s+(please|it|that)$/, ''); more = t !== s; s = t; }
  if (!s) return -1;
  const labels = answers.map(norm);
  const exact = labels.findIndex(l => l === s || l === norm(said));
  if (exact >= 0) return exact;
  const n = NUMBERS[s.replace(/^(number|option|answer)\s+/, '')];
  if (n !== undefined && n < answers.length) return n;
  const first = labels.findIndex(l => l.split(' ')[0] === s && s.length > 1);
  // Only when no other answer starts with the same word.
  if (first >= 0 && labels.filter(l => l.split(' ')[0] === s).length === 1) return first;
  return -1;
}
