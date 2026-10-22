// What automations tell the glasses (docs/specs/spec-automations.md §8; DAEMON.md, "Automation
// notes"). The Mac sends `{t:"note"}` the moment one is made, and keeps the last 12 hours in the
// snapshot. The lens shows only the ones meant for it (`to: "glasses"`), once each, and never an old
// one: a note read out hours late, when the glasses were off, would be noise. Pure.
import type { AutomationNote } from './types.ts';
import { readAskInfo } from './ask.ts';

/** A note older than this when the lens first sees it (a reconnect, the glasses just put on) isn't news. */
export const NOTE_FRESH_MS = 5 * 60_000;
/** How many notes the lens keeps to open again. */
export const NOTES_KEPT = 20;

/** One note off the wire, or null if it isn't one the glasses can use. Unknown fields are left alone. */
export function readNote(raw: unknown): AutomationNote | null {
  const n = raw as Partial<AutomationNote> | null | undefined;
  if (!n || typeof n !== 'object') return null;
  if (typeof n.id !== 'string' || !n.id || typeof n.text !== 'string' || !n.text.trim()) return null;
  const ask = readAskInfo(n.ask);
  return {
    id: n.id,
    automationId: typeof n.automationId === 'string' ? n.automationId : null,
    runId: typeof n.runId === 'string' ? n.runId : null,
    name: typeof n.name === 'string' && n.name.trim() ? n.name.trim().slice(0, 60) : 'Automation',
    text: n.text.slice(0, 1000),
    to: n.to === 'glasses' ? 'glasses' : 'phone',
    at: typeof n.at === 'number' && Number.isFinite(n.at) ? n.at : 0,
    ...(ask ? { ask } : {}),
  };
}

/**
 * The notes in a snapshot the lens hasn't shown, oldest first: for the glasses, new enough, and not
 * in `seen` (which the caller fills). Without a time on one, it's taken as new.
 */
export function unseenNotes(list: readonly unknown[] | undefined, seen: ReadonlySet<string>, now: number): AutomationNote[] {
  const out: AutomationNote[] = [];
  for (const raw of list ?? []) {
    const n = readNote(raw);
    if (!n || n.to !== 'glasses' || seen.has(n.id)) continue;
    // An ask still waiting is news however long ago it came: it holds a run.
    if (n.at && now - n.at > NOTE_FRESH_MS && !(n.ask && n.ask.until > now)) continue;
    out.push(n);
  }
  return out.sort((a, b) => a.at - b.at);
}

/** The ids in a snapshot's notes, for marking old ones seen without showing them. */
export function noteIds(list: readonly unknown[] | undefined): string[] {
  return (list ?? []).flatMap(raw => { const n = readNote(raw); return n ? [n.id] : []; });
}
