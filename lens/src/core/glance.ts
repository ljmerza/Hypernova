// The glance: one line per session that matters right now, the one that needs you on top. Pure.
import type { Approval, Session, Snapshot } from './types.ts';
import { activityLabel, ago, approvalDetail, MIN, wantsLine } from './format.ts';
import { blockedLine } from './limits.ts';

export type RowState = 'needs' | 'working' | 'done' | 'error' | 'idle';

export interface GlanceRow {
  sessionId: string;
  project: string;
  state: RowState;
  /** After the project's column: "Bash: ./gradlew build", "wants to run git push origin main". */
  detail: string;
  /** The same, shorter, for a row with no room for `detail`: "wants to push to main" (the page picks). */
  detailShort?: string;
  /** Right edge: how long. */
  right: string;
  /** Kept whole after the detail, each apart: "2 agents running", "2 queued". */
  tag: string[];
}

export interface GlanceSettings {
  /** Only these projects (by name); null shows every active session. */
  projects: string[] | null;
}

export interface GlanceMemory {
  /** When the lens first saw each session working, for "2m" on a working row. */
  workingSince: Map<string, number>;
  /** Finished turns not looked at yet, with their line ("finished: 3 files, tests pass"; '' until their facts load). */
  unseen: Map<string, { at: number; facts: string }>;
  /**
   * Sessions whose turn ended while agents it started still worked, and whose finish hasn't come:
   * the last agent has ended and the turn its report starts is a moment away (the hub's).
   */
  held?: ReadonlySet<string>;
}

export const MAX_ROWS = 4;
/** An idle session stays on the glance this long after its last activity. */
export const IDLE_WINDOW = 45 * MIN;

export function approvalsOf(snap: Snapshot, sessionId: string): Approval[] {
  return snap.approvals.filter(a => a.sessionId === sessionId).sort((a, b) => a.createdAt - b.createdAt);
}

/**
 * Agents its main agent started still at work after its turn ended: the main agent is idle, but the
 * job isn't done, so the session reads as working.
 */
export function agentsAtWork(s: Session): number {
  return s.state === 'idle' ? s.agentsRunning ?? 0 : 0;
}

/**
 * At work: Claude, or agents it started, or (with `held`) a job whose last agent has just reported:
 * never done or idle in between.
 */
export const isBusy = (s: Session, held?: ReadonlySet<string>) =>
  s.state === 'working' || s.state === 'starting' || agentsAtWork(s) > 0 || (s.state === 'idle' && !!held?.has(s.id));

/**
 * A session whose question waited 10 minutes and was denied by itself (`stoppedForAway`): it waits
 * for "continue", in the phone's words.
 */
export const AWAY_LINE = 'paused after 10 minutes without an answer';
/** The same, for a row with no room for it. */
export const AWAY_SHORT = 'paused while you were away';
/** Stopped by itself while you were away, and not at work again since. */
export const stoppedAway = (s: Session) => !!s.stoppedForAway && s.state !== 'working' && s.state !== 'starting' && s.state !== 'needs_you';

/** "2 agents running", as the phone and the Mac say it. */
export const agentsLine = (n: number) => `${n} agent${n === 1 ? '' : 's'} running`;
/** A session that stopped on an error, in the words the phone and the Mac use. */
export const ERROR_LINE = 'stopped on an error';

export function rowState(s: Session, snap: Snapshot, mem: GlanceMemory): RowState {
  if (s.pendingApprovalIds?.length || s.state === 'needs_you' || s.menu || approvalsOf(snap, s.id).length) return 'needs';
  if (isBusy(s, mem.held)) return 'working';
  if (s.state === 'error') return 'error';
  if (mem.unseen.has(s.id)) return 'done';
  return 'idle';
}

const ORDER: Record<RowState, number> = { needs: 0, working: 1, done: 2, error: 3, idle: 4 };

/** Sessions worth a line, in glance order (not cut to MAX_ROWS). */
export function activeSessions(snap: Snapshot, settings: GlanceSettings, mem: GlanceMemory, now: number): Session[] {
  const allow = settings.projects?.length ? new Set(settings.projects.map(p => p.toLowerCase())) : null;
  const items = snap.sessions
    .filter(s => s.state !== 'ended')
    .filter(s => !allow || allow.has(s.projectName.toLowerCase()))
    .map(s => ({ s, st: rowState(s, snap, mem) }))
    // A session paused at a limit stays: it carries on when the limit lifts, as the phone shows. So
    // does one that stopped while you were away: it waits for "continue".
    .filter(({ s, st }) => st !== 'idle' || !!s.blocked || stoppedAway(s) || now - s.lastActivityAt < IDLE_WINDOW);
  const askedAt = (s: Session) => approvalsOf(snap, s.id)[0]?.createdAt ?? s.lastActivityAt;
  items.sort((a, b) => ORDER[a.st] - ORDER[b.st]
    || (a.st === 'needs' ? askedAt(a.s) - askedAt(b.s) : b.s.lastActivityAt - a.s.lastActivityAt));
  return items.map(x => x.s);
}

export function glanceRows(snap: Snapshot, settings: GlanceSettings, mem: GlanceMemory, now: number): GlanceRow[] {
  return activeSessions(snap, settings, mem, now).slice(0, MAX_ROWS).map(s => rowFor(s, snap, mem, now));
}

export function rowFor(s: Session, snap: Snapshot, mem: GlanceMemory, now: number): GlanceRow {
  const state = rowState(s, snap, mem);
  const base = { sessionId: s.id, project: s.projectName || 'session', state, tag: [] as string[] };
  switch (state) {
    case 'needs': {
      const a = approvalsOf(snap, s.id)[0] ?? null;
      // The phone's phrase ("wants to run git push origin main") where the row has room for it, the
      // short one ("wants to push to main") where it hasn't: the page picks.
      const short = wantsLine(a, s);
      const d = a?.kind === 'tool' ? approvalDetail(a) : null;
      const full = d ? `${d.lead} ${d.detail.split('\n')[0].trim()}`.trim() : short;
      return { ...base, detail: full, ...(full !== short ? { detailShort: short } : {}), right: ago(now - (a?.createdAt ?? s.lastActivityAt)) };
    }
    case 'working': {
      const since = mem.workingSince.get(s.id) ?? s.lastActivityAt;
      const right = ago(now - since);
      // Only its agents are seen at work: they are the line. Claude runs a tool too: the tool, and
      // how many agents.
      const agents = s.agentsRunning ?? 0, tool = activityLabel(s.activity);
      // Prompts waiting for Claude ("2 queued") stay whole too, as on the phone.
      const queued = s.queued ? `${s.queued} queued` : '';
      if (s.state === 'idle' || !tool) return { ...base, detail: agents ? agentsLine(agents) : 'thinking', right, tag: [queued].filter(Boolean) };
      return { ...base, detail: tool, right, tag: [agents ? agentsLine(agents) : '', queued].filter(Boolean) };
    }
    case 'done': {
      const u = mem.unseen.get(s.id)!;
      // A turn a limit cut short says so rather than "done", and so does one stopped while you were away.
      const blocked = blockedLine(s, snap.account, now, snap.providers);
      if (!blocked && stoppedAway(s)) return { ...base, detail: AWAY_LINE, detailShort: AWAY_SHORT, right: ago(now - u.at) };
      return { ...base, detail: blocked ?? (u.facts || 'finished'), right: ago(now - u.at) };
    }
    case 'error':
      return { ...base, detail: ERROR_LINE, right: ago(now - s.lastActivityAt) };
    default:
      const blocked = blockedLine(s, snap.account, now, snap.providers);
      if (!blocked && stoppedAway(s)) return { ...base, detail: AWAY_LINE, detailShort: AWAY_SHORT, right: ago(now - s.lastActivityAt) };
      return { ...base, detail: blocked ?? (s.title || 'idle'), right: ago(now - s.lastActivityAt) };
  }
}

/** Which session a fresh glance points at: the one that needs you, else the busiest, else the latest. */
export function defaultFocus(rows: GlanceRow[]): string | null {
  return rows[0]?.sessionId ?? null;
}
