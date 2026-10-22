// The parts of the Hypernova protocol (docs/PROTOCOL.md) the lens reads. Unknown fields and enum
// values are ignored, as the protocol asks of every client.

export type SessionState = 'starting' | 'working' | 'needs_you' | 'idle' | 'error' | 'ended';

export interface MenuOption { label: string; detail: string | null; current: boolean }
export interface TermMenu {
  id: string;
  title: string | null;
  body: string[];
  options: MenuOption[];
  cursor: number;
  more: boolean;
  cancel: boolean;
}

export interface Session {
  id: string;
  title: string;
  cwd: string;
  projectName: string;
  origin: 'hypernova' | 'terminal';
  state: SessionState;
  model: string | null;
  permissionMode: string;
  createdAt: number;
  lastActivityAt: number;
  lastText: string | null;
  activity: string | null;
  pendingApprovalIds: string[];
  stoppedForAway: boolean;
  queued?: number;
  term?: { id: string } | null;
  menu?: TermMenu | null;
  /** Agents its main agent started that are at work now (at least one while a workflow runs); missing from an older Mac. */
  agentsRunning?: number;
  /** Why Claude can't reply here now (a limit, signed out…); a real reply clears it (PROTOCOL.md, "Account"). */
  blocked?: Blocked | null;
  /** The provider it runs on (`snapshot.providers`); missing from an older Mac. */
  providerId?: string;
  /** "codex": an OpenAI Codex session (PROTOCOL.md, "Codex"); absent for Claude Code's. */
  agent?: 'codex';
  /** It switched agents (PROTOCOL.md, "Switching agents"): the conversation goes on in that session. */
  switchedTo?: string;
}

/** The project's name, with "· Codex" for a Codex session (Claude's go without, as before). */
export const sessionName = (s: Pick<Session, 'projectName' | 'agent'>) => (s.agent === 'codex' ? `${s.projectName} · Codex` : s.projectName);

/** What stops a session replying (`session.blocked`). */
export interface Blocked {
  kind: 'limit' | 'model' | 'no_credit' | 'logged_out' | 'no_subscription' | 'on_hold' | 'provider';
  text: string;
  resetsAt: number | null;
  /** Claude Code carries on by itself then ("continuing automatically at 2am"). */
  autoResumeAt: number | null;
  /** Whether it carries on by itself (PROTOCOL.md, "Auto-continue"): `ready`, the limit is over and it waits for Continue. */
  resume?: Resume | null;
}

/** `Blocked.resume`: armed (carries on by itself at `at`), off, or ready (what stopped it is over). */
export interface Resume { state: 'armed' | 'off' | 'ready' | string; at: number | null }

/**
 * A provider Claude Code can run on (`snapshot.providers`, `{t: "providers"}`): only what the lens
 * shows, its trouble (PROTOCOL.md, "Providers"). `status` is always null for the built-in one (its
 * trouble is the Account's). Missing from an older Mac.
 */
export interface Provider {
  id: string;
  name: string;
  builtIn?: boolean;
  status: ProviderStatus | null;
}

/** A provider's trouble, until it replies again; an unknown `state` is shown generically. */
export interface ProviderStatus {
  state: 'no_credit' | 'bad_key' | 'limited' | 'down' | string;
  /** When this episode began: one pop-up per episode. */
  since: number | null;
  /** `limited` only, when the provider said. */
  resetsAt: number | null;
  /** `no_credit` from a cap someone set (the key's limit, a budget), not the account's balance. */
  budget?: boolean;
}

/** Whether Claude can reply on this Mac (`snapshot.account`, `{t: "account"}`): only what the lens shows. */
export interface Account {
  state: 'ok' | 'limited' | 'no_credit' | 'logged_out' | 'no_subscription' | 'on_hold' | 'unknown';
  limit: { window: 'five_hour' | 'seven_day' | 'model' | 'credits'; label: string; resetsAt: number | null; autoResumeAt: number | null; usingCredits: boolean } | null;
  /** When this episode began: one pop-up per episode, however many sessions hit it. */
  since: number | null;
}

/**
 * Stay awake (`snapshot.mac.awake`, `{t: "awake"}`): whether the daemon keeps the Mac awake, the lid
 * closed too in Backpack mode. Missing from a Mac without it.
 */
export interface MacAwake {
  mode: 'working' | 'backpack' | 'off';
  /** Holding the Mac awake right now. */
  holding: boolean;
  /** Awake with the lid closed (Backpack's helper is set up and holding). */
  lidClosedOk: boolean;
  /** Backpack mode can be chosen (its one-time setup is done). */
  backpackReady: boolean;
  /** When Backpack ends by itself (its 8 hours), epoch ms. */
  until: number | null;
  /** What stands in the way: a guard ('battery', 'hot', 'time'), or Backpack's switch ('setup', 'failed'). */
  reason: string | null;
  /** Backpack ends within 10 minutes: "+2 h" can be offered (`POST /v1/mac/awake {extend: true}`); missing from an older Mac. */
  extendable?: boolean;
  /** When a guard lets the Mac sleep (`mac_sleep_soon` said so); missing from an older Mac. */
  sleepAt?: number | null;
  /** The battery guard's level, in percent. */
  minBattery?: number;
}

export interface Question {
  question: string;
  header: string;
  multiSelect: boolean;
  options: Array<{ label: string; description?: string }>;
}

export interface Approval {
  id: string;
  sessionId: string;
  createdAt: number;
  expiresAt: number;
  kind: 'tool' | 'question' | 'plan';
  toolName: string;
  summary: string;
  input: Record<string, unknown>;
  reason: string | null;
  canSession: boolean;
  questions: Question[] | null;
  plan: string | null;
  /** An agent the main agent started asks, not the main agent (missing from an older Mac). */
  agentId?: string | null;
  /** What that agent is called: "Review the parser", "survey:agents". */
  agentLabel?: string | null;
}

export interface UsageWindow { pct: number; resetsAt: number }
/** A model's own week ("Fable", "Sonnet"), read off Claude Code's /usage screen; resetsAt null when it gave none. */
export interface ModelWindow { label: string; pct: number; resetsAt: number | null }
/**
 * The spend pace (PROTOCOL.md, Usage.rate): %/min over the last minutes of readings, and how long until
 * 100% at that pace when the limit comes before the reset. Sent only while a session or its agents work.
 */
export interface UsageRate { fiveHour: number | null; sevenDay: number | null; fiveHourEtaMs: number | null; sevenDayEtaMs: number | null; working: boolean }
/** `models`: missing from an older Mac (none); `rate`: only while something works (absent from an older Mac). */
export interface Usage { fiveHour: UsageWindow | null; sevenDay: UsageWindow | null; models?: ModelWindow[]; rate?: UsageRate | null; source: string; observedAt: number | null }

export interface Project { path: string; name: string; lastActivityAt: number | null; sessionCount: number }

/**
 * The Mac's battery (`snapshot.mac.battery`, and `{t: "battery"}` on the socket). null: a Mac without
 * one (a Mac mini, an iMac, a Mac Studio or Pro), which shows nothing; missing from an older Mac.
 */
export interface MacBattery { percent: number; charging: boolean; onAC: boolean; minutesLeft: number | null }

/** The glasses' own battery, as the Even App reports it. */
export interface GlassesBattery { percent: number; charging: boolean }

/** Speech to text on the Mac (Soniox): whether a hold can use it; missing from an older Mac. */
export interface SttState { provider: 'soniox' | null; ready: boolean; error: string | null }

export type GuideStep = 'about' | 'gestures' | 'voice';
/** The Lens guide's place, shared by the Mac's window and the phone (DAEMON.md, "The Lens guide"). */
export interface GuideState { step: GuideStep; seen: boolean; done: boolean }

/**
 * What an automation tells you (`{t: "note"}`, and `snapshot.automations.notes` for the last 12 hours;
 * PROTOCOL.md, "Automations"). `to` says where: the glasses show only their own.
 */
export interface AutomationNote {
  id: string;
  automationId: string | null;
  runId: string | null;
  /** The automation's name when it ran: "Morning brief". */
  name: string;
  text: string;
  to: 'phone' | 'glasses';
  at: number;
  /** A run waits at an `ask` step: the note's text is its question (PROTOCOL.md, "Answering an ask"). */
  ask?: AskInfo | null;
}

/** `Note.ask`: the run's question and the answers to pick; `default` is taken at `until`. */
export interface AskInfo {
  runId: string;
  stepId: string;
  question: string;
  answers: string[];
  default: string;
  until: number;
}

export interface Snapshot {
  mac: { id: string; name: string; daemonVersion: string; battery?: MacBattery | null; awake?: MacAwake | null };
  usage: Usage;
  sessions: Session[];
  approvals: Approval[];
  projects: Project[];
  stt?: SttState;
  guide?: GuideState;
  /** Missing from an older Mac. */
  account?: Account;
  /** The providers sessions run on (their trouble, PROTOCOL.md "Providers"); missing from an older Mac. */
  providers?: Provider[];
  /** Missing from a Mac without automations; the lens reads only the notes. */
  automations?: { paused?: boolean; notes?: AutomationNote[] };
  /** Read aloud: Soniox or the Mac's voice, and whether a phone app plays the glasses' speech (core/speech-out.ts). */
  speech?: import('./speech-out.ts').SpeechSetting;
  /** Claude Code on the Mac (missing from an older Mac, where it always was). */
  claude?: { available: boolean; version: string | null };
  /** Codex (PROTOCOL.md, "Codex"): null where it isn't installed; missing from an older Mac. */
  codex?: Codex | null;
}

/** What the lens reads of Codex: whether it's there and on, and its own windows. */
export interface Codex {
  available: boolean;
  enabled: boolean;
  usage: { fiveHour: UsageWindow | null; sevenDay: UsageWindow | null; limited: boolean; observedAt: number | null } | null;
}

/**
 * The agent the sun follows (PROTOCOL.md, "Which agent leads"): Claude Code where it's installed, else
 * Codex. Codex's windows come as a Usage (no pace), and Claude's account doesn't eclipse Codex's sun.
 */
export function leading(snap: Snapshot | null | undefined): { codex: boolean; usage: Usage | undefined; account: Account | undefined } {
  if (snap && snap.claude?.available === false && snap.codex?.available) {
    const u = snap.codex.usage;
    return { codex: true, usage: { fiveHour: u?.fiveHour ?? null, sevenDay: u?.sevenDay ?? null, source: u ? 'official' : 'none', observedAt: u?.observedAt ?? null }, account: undefined };
  }
  return { codex: false, usage: snap?.usage, account: snap?.account };
}

export type HnEvent =
  | { id: string; kind: 'user'; ts: number; text: string; command?: { name: string; args: string; kind: string } }
  | { id: string; kind: 'assistant'; ts: number; text: string; streaming: boolean }
  | { id: string; kind: 'thinking'; ts: number; chars: number }
  | {
      id: string; kind: 'tool'; ts: number; toolUseId: string; name: string; summary: string; input: Record<string, unknown>;
      status: 'running' | 'done' | 'error'; output: string | null; exitCode: number | null; durationMs: number | null;
      path: string | null;
      /** What an edit changed; `hunks` as Claude Code's own patch has them ("What changed?", "show diff"). */
      diff?: { path: string; added: number; removed: number; hunks?: Array<{ oldStart: number; oldLines: number; newStart: number; newLines: number; lines: string[] }> } | null;
    }
  | { id: string; kind: 'approval'; ts: number; approvalId: string; summary: string; resolution: string | null }
  | { id: string; kind: 'result'; ts: number; ok: boolean; durationMs: number; costUsd: number | null; turns: number; error: string | null }
  | { id: string; kind: 'notice'; ts: number; text: string; tone: 'info' | 'warn' | 'error' };

/**
 * An agent a session's main agent started (GET /v1/sessions/{id}/agents, and `agent` messages on the
 * socket; PROTOCOL.md, "Agents and workflows"). Only what the lens shows.
 */
export interface Agent {
  id: string;
  sessionId: string;
  parentAgentId: string | null;
  workflowId: string | null;
  /** "Review the parser"; a workflow's agents "survey:agents". */
  label: string;
  type: string;
  status: 'running' | 'done' | 'error' | 'stopped';
  /** While it runs: "Bash: ./gradlew build". */
  activity: string | null;
  startedAt: number;
  endedAt: number | null;
  lastActivityAt: number;
  /** Its report, at most 600 characters. */
  result: string | null;
  /** A workflow step's earlier attempt (the step ran again as a new agent): it no longer counts. */
  superseded?: boolean;
}

export type ApprovalAnswer =
  | { decision: 'allow' | 'deny' | 'session'; message?: string }
  | { decision: 'allow'; answers: Record<string, string> };

/** A picture of what a session builds (DAEMON.md, "Telescope"). */
export interface Shot {
  sessionId: string;
  /** Signed URL of the full image (no auth header needed), or a data: URL in demo mode. */
  url: string;
  width: number;
  height: number;
  title: string;
  /** Where it came from: Telescope's sources; `url` from a Mac before Telescope (a page). */
  source: 'web' | 'emulator' | 'simulator' | 'terminal' | 'image' | 'window' | 'url' | 'screen' | 'demo';
  /** Where, in a few characters: "localhost:5173/settings", "Pixel 8", "report.pdf · page 2 of 9". */
  label?: string;
  /** The address captured; null for an HTML file the session wrote (then `path` names it). */
  pageUrl: string | null;
  path?: string;
  /** A PDF's page shown, of `pages`; the Android device's id. */
  page?: number;
  pages?: number;
  device?: string;
  /** The picture's fingerprint: the same one means nothing changed. */
  digest?: string;
  at: number;
  /** Picking by itself, the Mac passed over this source for a reason the owner can fix: what to say. */
  skipped?: { source: string; code: string; message: string };
}
