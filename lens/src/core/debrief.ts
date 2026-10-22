// "Debrief": a catch-up since you last asked, spoken and shown: what finished, what needs you, what's
// running. Said on a bike or a walk, so each item is one sentence, in that order, and the lens lists
// the same items (a tap opens one). Built from the snapshot itself, so a turn that ended while the
// phone slept still counts. Pure: the controller passes the time and what it knows of each turn.
import type { Snapshot } from './types.ts';
import { activityLabel, activitySpoken, clockTime, spokenName, wantsLine } from './format.ts';
import { approvalsOf, agentsAtWork, agentsLine, AWAY_LINE, isBusy, stoppedAway } from './glance.ts';
import { blockedLine, blockedSpoken } from './limits.ts';
import { doneLine, firstSentence, spokenFacts, type TurnFacts } from './result.ts';

export type DebriefKind = 'done' | 'stopped' | 'needs' | 'working' | 'paused';

export interface DebriefItem {
  sessionId: string;
  kind: DebriefKind;
  /** On the lens, the project and what it did, apart: ["hyperscribe", "finished: 3 files, tests pass"]. */
  line: string[];
  /** Read aloud: one sentence. */
  spoken: string;
}

export interface Debrief {
  since: number;
  items: DebriefItem[];
  /** "1 done, 1 needs you, 2 at work", or "nothing new". */
  counts: string;
  /** All of it, read aloud. */
  spoken: string;
}

export interface DebriefInput {
  snap: Snapshot;
  /** Sessions whose job goes on after their turn (the hub's `held`). */
  held?: ReadonlySet<string>;
  /** What the lens worked out of each session's last turn, when it has. */
  facts: ReadonlyMap<string, TurnFacts>;
  since: number;
  now: number;
  /** Moving: one sentence per item, no facts after it. */
  brief?: boolean;
}

/** The catch-up: finished since `since`, then needing you, then at work. */
export function debrief(d: DebriefInput): Debrief {
  const { snap, since, now } = d;
  // A conversation that switched agents is told once, by the session it went on in.
  const live = snap.sessions.filter(s => s.state !== 'ended' && !s.switchedTo);
  const done: DebriefItem[] = [], needs: DebriefItem[] = [], working: DebriefItem[] = [];
  for (const s of live) {
    const name = spokenName(s.projectName);
    const asks = approvalsOf(snap, s.id)[0] ?? null;
    if (asks || s.menu || s.state === 'needs_you') {
      const wants = wantsLine(asks, s);
      needs.push({ sessionId: s.id, kind: 'needs', line: [s.projectName, wants], spoken: `${name} ${wants === 'needs you' ? 'needs you' : wants}.` });
      continue;
    }
    if (isBusy(s, d.held)) {
      const agents = agentsAtWork(s) || (s.agentsRunning ?? 0);
      const doing = s.state === 'idle' ? agentsLine(agents) : activityLabel(s.activity) || 'thinking';
      const spokenDoing = s.state === 'idle' ? `has ${agentsLine(agents)}` : `is ${activitySpoken(s.activity)}${agents ? `, with ${agentsLine(agents)}` : ''}`;
      working.push({ sessionId: s.id, kind: 'working', line: [s.projectName, doing, s.state !== 'idle' && agents ? agentsLine(agents) : ''].filter(Boolean), spoken: `${name} ${spokenDoing}.` });
      continue;
    }
    if (s.lastActivityAt <= since) continue;
    const paused = blockedLine(s, snap.account, now, snap.providers) ?? (stoppedAway(s) ? AWAY_LINE : null);
    if (paused) {
      done.push({ sessionId: s.id, kind: 'paused', line: [s.projectName, paused], spoken: blockedSpoken(name, s, snap.account, now, snap.providers) ?? `${name} ${AWAY_LINE}.` });
      continue;
    }
    const f = d.facts.get(s.id);
    const stopped = s.state === 'error' || (!!f && (!f.ok || !!f.error));
    if (!f && !s.lastText && !stopped) continue;
    const sentence = stopped ? f?.error ?? '' : firstSentence(f?.reply || s.lastText || '', 160);
    const facts = f ? doneLine(f) : '';
    const extra = !d.brief && f && !stopped ? spokenFacts(f) : '';
    done.push({
      sessionId: s.id, kind: stopped ? 'stopped' : 'done',
      line: [s.projectName, facts && facts !== 'finished' ? facts : `${stopped ? 'stopped' : 'finished'}${sentence ? `: ${sentence}` : ''}`],
      spoken: `${name} ${stopped ? 'stopped' : 'finished'}${sentence ? `: ${sentence.replace(/[.!?…]+$/, '')}.` : '.'}${extra ? ` ${extra.charAt(0).toUpperCase()}${extra.slice(1)}` : ''}`,
    });
  }
  // Newest first within each group; what needs you, oldest first (as the glance has it).
  const at = (id: string) => snap.sessions.find(s => s.id === id)?.lastActivityAt ?? 0;
  done.sort((a, b) => at(b.sessionId) - at(a.sessionId));
  working.sort((a, b) => at(b.sessionId) - at(a.sessionId));
  const items = [...done, ...needs, ...working];
  const n = (k: DebriefKind[]) => items.filter(i => k.includes(i.kind)).length;
  const counts = items.length ? [
    n(['done']) ? `${n(['done'])} done` : '', n(['stopped']) ? `${n(['stopped'])} stopped` : '', n(['paused']) ? `${n(['paused'])} paused` : '',
    n(['needs']) ? `${n(['needs'])} needs you` : '', n(['working']) ? `${n(['working'])} at work` : '',
  ].filter(Boolean).join(', ') : 'nothing new';
  const when = clockTime(since);
  const opening = done.length || needs.length ? `Since ${when}.` : `Nothing new since ${when}.`;
  const spoken = [opening, ...items.map(i => i.spoken), items.length ? '' : 'All quiet.'].filter(Boolean).join(' ');
  return { since, items, counts, spoken };
}
