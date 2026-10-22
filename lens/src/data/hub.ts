// The Mac's sessions, live: the daemon's WebSocket (GET /v1/ws) pushes a snapshot and then every
// change, exactly as it does for the Android app. A browser can't put a header on a WebSocket, so
// the token rides in the query string, which the daemon accepts. If the socket can't stay up, the
// snapshot is polled instead. Actions are plain REST calls (docs/PROTOCOL.md).
//
// 'change' means something changed. While the Mac is away the hub keeps retrying and polling, and
// each attempt that ends the same way says nothing: every 'change' wakes the lens and the phone,
// and the lens takes it as a reason to keep its planets moving (LensOutput).
import { sealedSocketClass } from './seal.ts';
import { Api, ApiError } from './api.ts';
import type { PairLink } from '../core/pairing.ts';
import type { Pairing } from './store.ts';
import type { ScopeAsk } from '../core/telescope.ts';
import { unseenNotes, readNote, noteIds } from '../core/notes.ts';
import { askOfNote, readRunAsks, type AskCard, type RunAsks } from '../core/ask.ts';
import type { Account, Agent, Approval, ApprovalAnswer, AutomationNote, GuideState, HnEvent, MacAwake, MacBattery, Provider, Session, Shot, Snapshot, SttState, Usage } from '../core/types.ts';
import { readProgress, type PlayProgress, type PlayStart, type SpeechSetting } from '../core/speech-out.ts';
import { readBridgeSettings, WORDS, type BridgeSettings, type NewSessionChoice } from './bridge.ts';

/** A new session's provider and model in `POST /v1/sessions` (PROTOCOL.md, NewSession), when chosen; never `permissionMode`. */
export function chosen(choice: NewSessionChoice | null | undefined): { providerId?: string; model?: string } {
  if (!choice) return {};
  return { providerId: choice.providerId, ...(choice.model ? { model: choice.model } : {}) };
}

export type HubStatus = 'off' | 'connecting' | 'live' | 'polling' | 'offline' | 'unpaired';

export type HubEvent =
  | { t: 'change' }
  | { t: 'finished'; sessionId: string }
  | { t: 'approval'; approval: Approval }
  | { t: 'event'; sessionId: string; event: HnEvent }
  /** An agent of a session whose agents the lens has loaded started or changed. */
  | { t: 'agent'; sessionId: string; agent: Agent }
  /** An automation has news for the glasses (PROTOCOL.md, "Automations"): shown once, whether it came live or with the snapshot. */
  | { t: 'note'; note: AutomationNote }
  /** A run waits at an `ask` step (a note with `ask`, DAEMON.md 14): the card to show, once. */
  | { t: 'ask'; ask: AskCard }
  /** A run changed (`{t:"run"}`): its ask is over or not, and who answered (DAEMON.md 14). */
  | { t: 'run_ask'; run: RunAsks }
  /** The Mac will let itself sleep in `inSeconds` (Stay awake's guards: the battery, heat, 8 hours); `percent`: its battery. */
  | { t: 'sleep_soon'; inSeconds: number; reason: 'battery' | 'hot' | 'time'; percent?: number | null; stillAwake?: boolean; otherApp?: string | null }
  /** Backpack ends in about ten minutes (`until`, epoch ms): offer "+2 h" (`extendBy` seconds). */
  | { t: 'backpack_ending'; inSeconds: number; until: number; extendBy: number }
  /** How a play on the phone app goes (data/voice.ts): started, how far, ended, stopped, never played. */
  | { t: 'speech_progress'; progress: PlayProgress }
  /** Through the Hypernova app on this phone: the glasses' settings, as it keeps them (on connecting, and each change). */
  | { t: 'bridge_settings'; settings: BridgeSettings }
  /** Through the app: it no longer lets Lens in (Disconnect on the phone, or the Mac forgot the glasses). */
  | { t: 'revoked' }
  /** Through the app: it's newer than this Lens and refuses it (`update_lens`): Lens is to be updated. */
  | { t: 'old_lens' };

export interface MacVoice { id: string; name: string; lang: string }

export interface HubLike {
  readonly snapshot: Snapshot | null;
  readonly status: HubStatus;
  /** Why the hub is offline, in plain words. */
  readonly problem: string | null;
  /** When it went offline (epoch ms), while it is: what it shows is what the Mac said then. */
  readonly offlineSince?: number | null;
  readonly macName: string;
  /**
   * Sessions whose turn ended while agents it started worked, and whose finish hasn't come yet: the
   * job goes on, also in the moment between the last agent ending and its report's turn starting.
   */
  readonly held?: ReadonlySet<string>;
  events(sessionId: string): HnEvent[] | null;
  loadEvents(sessionId: string): Promise<HnEvent[]>;
  /** The agents of a session, as last loaded and kept up to date by the socket; null until loaded. */
  agents(sessionId: string): Agent[] | null;
  loadAgents(sessionId: string): Promise<Agent[]>;
  answer(approvalId: string, answer: ApprovalAnswer): Promise<void>;
  /** Answer a run's ask step (DAEMON.md 14); 409 `not_asking` when someone else did first. Missing from a hub without workflows. */
  answerAsk?(runId: string, stepId: string, answer: string): Promise<void>;
  /** `forked`: the session was open in a terminal the Mac doesn't share, so the prompt went to a new
   * session continuing it (`session`, with a new id). */
  prompt(sessionId: string, text: string, replace?: boolean): Promise<{ queued: boolean; session?: Session | null; forked?: boolean }>;
  /** `choice`: the provider and model to start on (the phone's last, data/bridge.ts); none, the Mac's defaults. Never a mode. */
  newSession(projectPath: string, prompt: string, choice?: NewSessionChoice | null): Promise<Session | null>;
  /** A new session in a new subfolder of Nebula, `name` made unique and `git init`ed by the Mac. */
  newNebulaSession?(name: string, prompt: string, choice?: NewSessionChoice | null): Promise<Session | null>;
  /** Stay awake's mode on the Mac ("stay awake", "let it sleep"); what the Mac does now. */
  setAwake?(mode: MacAwake['mode']): Promise<MacAwake | null>;
  /** Backpack's "+2 h" (in its last ten minutes); what the Mac does now. */
  extendAwake?(): Promise<MacAwake | null>;
  /**
   * "Plan first" / "go ahead": the session's permission mode. Only these two by voice: Auto and
   * Bypass are the phone's, behind its think-twice card. `applies`: "next_turn" while a turn runs.
   */
  setMode?(sessionId: string, mode: 'plan' | 'default'): Promise<{ applies: string | null }>;
  /** "Continue": the Mac's own words to carry on where it stopped. */
  continueSession?(sessionId: string): Promise<void>;
  /** "Note: …": a line in Nebula's notes.md, with the time and project; Claude isn't started. */
  note?(text: string, project: string | null): Promise<{ path: string }>;
  interrupt(sessionId: string): Promise<void>;
  menu(sessionId: string, menuId: string, pick: { choice: number } | { cancel: true }): Promise<void>;
  screenshot(sessionId: string, url?: string): Promise<Shot>;
  /** Telescope: a picture of what the session builds, from the source asked for or the one the Mac picks. */
  telescope(sessionId: string, ask: ScopeAsk): Promise<Shot>;
  preview(target: { port: number } | { path: string }): Promise<string>;
  speak(text: string, voice: string | null, rate: number): Promise<ArrayBuffer>;
  /**
   * Read aloud by the Hypernova app on the phone (PROTOCOL.md, "Read aloud"): null when no phone app
   * plays the glasses' speech (or the Mac is older), and the glasses play it themselves.
   */
  playOnPhone?(text: string, sessionId: string | null): Promise<PlayStart | null>;
  stopOnPhone?(id: string): Promise<void>;
  voices(): Promise<MacVoice[]>;
  subscribe(fn: (e: HubEvent) => void): () => void;
}

const appVersion = (import.meta.env?.VITE_APP_VERSION as string | undefined) ?? 'dev';

/** How long a session whose last agent just ended may take to show its report's turn starting. */
const REPORT_TURN_MS = 5000;

/** `POST <u>/v1/pair`: any 4xx means the code is no good (expired, used, or mistyped). */
export async function pair(link: PairLink, deviceName: string): Promise<Pairing> {
  const api = new Api(link.baseUrl, null);
  try {
    const r = await api.post<{ token: string; deviceId: string; mac: { id: string; name: string } }>('/v1/pair', {
      code: link.code, deviceName, model: 'Even G2 · Hypernova Lens', appVersion, client: 'lens',
    });
    if (!r.token) throw new ApiError('server', 'The Mac didn’t send a token.');
    return { baseUrl: link.baseUrl, token: r.token, macName: r.mac?.name ?? link.macName, macId: r.mac?.id ?? null, deviceId: r.deviceId ?? null };
  } catch (err) {
    if (err instanceof ApiError && err.status >= 400 && err.status < 500 && err.status !== 429) {
      throw new ApiError('code_expired', 'That code has expired or was already used. Show a new one on your Mac.', err.status, err.code);
    }
    throw err;
  }
}

export class LiveHub implements HubLike {
  snapshot: Snapshot | null = null;
  status: HubStatus = 'off';
  problem: string | null = null;
  offlineSince: number | null = null;
  readonly api: Api;
  private pairing: Pairing;
  private ws: WebSocket | null = null;
  private listeners = new Set<(e: HubEvent) => void>();
  private eventCache = new Map<string, HnEvent[]>();
  private agentCache = new Map<string, Agent[]>();
  /** This Mac's Hypernova has no agents route (older than agents): don't ask again. */
  private noAgents = false;
  private backoff = 1000;
  private failures = 0;
  private retry: ReturnType<typeof setTimeout> | null = null;
  private poll: ReturnType<typeof setInterval> | null = null;
  private quiet: ReturnType<typeof setTimeout> | null = null;
  private stopped = false;
  /** Each open through the app waits for the app to prove itself; only the latest goes on. */
  private opening = 0;
  private clientSeq = 0;
  /** Sessions whose turn ended while agents it started were still at work: the job isn't finished yet. */
  private holding = new Set<string>();
  /** Notes already shown (or too old to), by id. */
  private notesSeen = new Set<string>();
  private heldTimers = new Map<string, ReturnType<typeof setTimeout>>();

  /** 'bridge': through the Hypernova app on this phone (data/bridge.ts), whose token `p` carries. */
  readonly via: 'mac' | 'bridge';

  constructor(p: Pairing, via: 'mac' | 'bridge' = 'mac') {
    this.pairing = p;
    this.via = via;
    this.api = new Api(p.baseUrl, p.token, via, p.signer ?? null);
  }

  get macName(): string { return this.snapshot?.mac.name ?? this.pairing.macName; }
  get held(): ReadonlySet<string> { return this.holding; }

  subscribe(fn: (e: HubEvent) => void): () => void { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  private emit(e: HubEvent): void { for (const fn of this.listeners) { try { fn(e); } catch (err) { console.warn('[hub] listener', err); } } }
  private changed(): void { this.emit({ t: 'change' }); }

  /** A new status or problem, told only when it is new: a retry that fails like the last one isn't news. */
  private setStatus(status: HubStatus, problem: string | null = this.problem): void {
    if (status === this.status && problem === this.problem) return;
    this.since(status);
    this.status = status;
    this.problem = problem;
    this.changed();
  }

  /** Going offline starts the clock the lens says ("offline since 14:32"); anything else stops it. */
  private since(status: HubStatus): void {
    if (status !== 'offline') this.offlineSince = null;
    else if (this.status !== 'offline' || this.offlineSince === null) this.offlineSince = Date.now();
  }

  start(): void {
    this.stopped = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    if (this.retry) clearTimeout(this.retry);
    if (this.poll) clearInterval(this.poll);
    if (this.quiet) clearTimeout(this.quiet);
    this.retry = this.poll = this.quiet = null;
    for (const t of this.heldTimers.values()) clearTimeout(t);
    this.heldTimers.clear();
    try { this.ws?.close(); } catch { /* closed */ }
    this.ws = null;
    this.status = 'off';
  }

  /** The app came back to the front: don't wait out the backoff. */
  wake(): void {
    if (this.stopped || this.status === 'live' || this.status === 'unpaired') return;
    this.backoff = 1000;
    if (this.retry) { clearTimeout(this.retry); this.retry = null; }
    this.open();
  }

  /** Straight to a Mac: the token in the query (a page can't set a header on a socket). */
  private wsUrl(): string {
    return `${this.pairing.baseUrl.replace(/^http/i, 'ws').replace(/\/+$/, '')}/v1/ws?token=${encodeURIComponent(this.pairing.token)}`;
  }

  private open(): void {
    if (this.stopped) return;
    const old = this.ws;
    this.ws = null;
    try { old?.close(); } catch { /* closed */ }
    // A retry keeps saying "offline" (or polling) until it works, rather than "connecting" each time.
    if (this.status !== 'polling' && this.status !== 'offline') this.setStatus(this.snapshot ? 'offline' : 'connecting');
    const signer = this.pairing.signer;
    if (!signer) { this.connect(this.wsUrl(), WebSocket); return; }
    // Through the app, whoever listens on its port proves it's the app before anything is signed for
    // it, right before the socket opens; then the socket's address is signed (the bridge token never
    // travels) and every message on it is sealed under that socket's key (data/seal.ts).
    const attempt = ++this.opening;
    signer.ready(true).then(() => signer.socket('/v1/ws')).then(({ url, call }) => {
      if (attempt === this.opening && !this.stopped) this.connect(url, sealedSocketClass(WebSocket, call));
    }, err => {
      if (attempt !== this.opening || this.stopped) return;
      if ((err as { code?: string }).code === 'bridge_access') { this.revoked(); return; }
      this.failures++;
      this.lost();
    });
  }

  private connect(url: string, Socket: typeof WebSocket): void {
    let ws: WebSocket;
    try { ws = new Socket(url); } catch { this.lost(); return; }
    this.ws = ws;
    let opened = false;
    ws.onopen = () => { opened = true; };
    ws.onmessage = ev => { if (this.ws === ws) this.message(ev.data); };
    ws.onclose = ev => {
      if (this.ws !== ws) return;
      this.ws = null;
      // The app may have gone, and something else may answer on its port next: it proves itself again first.
      this.pairing.signer?.stale();
      // Through the app, the Mac closing the glasses' feed (4001) or the app taking Lens's access
      // back (4003) is asked about over HTTP at once: a refusal there is the app's word on it.
      if (this.via === 'bridge' && (ev.code === 4001 || ev.code === 4003)) { this.failures = Math.max(this.failures, 2); this.lost(); return; }
      if (ev.code === 4001) { this.unpaired(); return; }
      if (!opened) this.failures++;
      this.lost();
    };
    ws.onerror = () => { /* onclose follows */ };
  }

  /** No message for a minute (the daemon pings every 25 s): the link is dead even if TCP says not. */
  private touch(): void {
    if (this.quiet) clearTimeout(this.quiet);
    this.quiet = setTimeout(() => { try { this.ws?.close(); } catch { /* closed */ } }, 60_000);
  }

  private lost(): void {
    if (this.stopped) return;
    if (this.status === 'live') this.setStatus('offline');
    // The socket keeps failing: find out why over HTTP, and poll if HTTP works.
    if (this.failures >= 2 && this.status !== 'polling') void this.checkHttp();
    const wait = this.backoff;
    this.backoff = Math.min(30_000, this.backoff * 2);
    if (this.retry) clearTimeout(this.retry);
    this.retry = setTimeout(() => { this.retry = null; this.open(); }, wait);
  }

  private async checkHttp(): Promise<void> {
    try {
      const snap = await this.api.get<Snapshot>('/v1/snapshot', { timeoutMs: 8000 });
      if (this.stopped || this.status === 'live') return;
      this.applySnapshot(snap, 'polling', null);
      this.startPolling();
    } catch (err) {
      if (this.stopped) return;
      if (err instanceof ApiError && err.problem === 'revoked') { this.revoked(); return; }
      if (err instanceof ApiError && err.code === 'update_lens') { this.oldLens(); return; }
      if (err instanceof ApiError && err.problem === 'unauthorized') { this.unpaired(); return; }
      if (this.status !== 'live') this.setStatus('offline', err instanceof Error ? err.message : String(err));
    }
  }

  private startPolling(): void {
    if (this.poll) return;
    this.poll = setInterval(() => {
      if (this.status === 'live') { clearInterval(this.poll!); this.poll = null; return; }
      void this.api.get<Snapshot>('/v1/snapshot', { timeoutMs: 8000 })
        .then(s => { if (!this.stopped && this.status !== 'live') this.applySnapshot(s, 'polling', null); })
        .catch(err => {
          if (this.stopped || this.status === 'live') return;
          if (err instanceof ApiError && err.problem === 'revoked') this.revoked();
          else if (err instanceof ApiError && err.code === 'update_lens') this.oldLens();
          else if (err instanceof ApiError && err.problem === 'unauthorized') this.unpaired();
          else this.setStatus('offline', err instanceof Error ? err.message : String(err));
        });
    }, 4000);
  }

  private unpaired(): void {
    this.stop();
    this.status = 'unpaired';
    this.problem = this.via === 'bridge' ? 'Hypernova on this phone isn’t paired with your Mac any more.' : 'This phone isn’t paired with the Mac any more.';
    this.changed();
  }

  /** Through the app: Lens's access is gone. The link asks again when told to (data/link.ts). */
  private revoked(): void {
    this.stop();
    this.status = 'unpaired';
    this.problem = 'Disconnected on your phone.';
    this.emit({ t: 'revoked' });
    this.changed();
  }

  /** Through the app: it refuses this Lens as too old. The link says so (data/link.ts); nothing more is asked. */
  private oldLens(): void {
    this.stop();
    this.status = 'offline';
    this.problem = WORDS.update_lens;
    this.emit({ t: 'old_lens' });
    this.changed();
  }

  private message(data: unknown): void {
    if (typeof data !== 'string') return;
    let m: Record<string, any>;
    try { m = JSON.parse(data); } catch { return; }
    this.touch();
    switch (m.t) {
      case 'snapshot':
        this.failures = 0;
        this.backoff = 1000;
        if (this.poll) { clearInterval(this.poll); this.poll = null; }
        // A (re)connect: what happened while the socket was down never came, so what was kept of
        // sessions' turns and agents is asked for afresh when it's next needed.
        this.eventCache.clear();
        this.agentCache.clear();
        this.applySnapshot(m.snapshot as Snapshot, 'live', null);
        break;
      case 'ping':
        try { this.ws?.send('{"t":"pong"}'); } catch { /* closed */ }
        break;
      case 'session': this.applySession(m.session as Session); this.changed(); break;
      case 'session.removed':
        if (this.snapshot) this.snapshot = { ...this.snapshot, sessions: this.snapshot.sessions.filter(s => s.id !== m.id) };
        this.changed();
        break;
      case 'event': this.applyEvent(m.sessionId, m.event as HnEvent); break;
      case 'agent': this.applyAgent(m.agent as Agent); break;
      case 'approval':
        if (this.snapshot) {
          const a = m.approval as Approval;
          this.snapshot = { ...this.snapshot, approvals: [...this.snapshot.approvals.filter(x => x.id !== a.id), a] };
          this.emit({ t: 'approval', approval: a });
          this.changed();
        }
        break;
      case 'approval.resolved':
        if (this.snapshot) { this.snapshot = { ...this.snapshot, approvals: this.snapshot.approvals.filter(x => x.id !== m.id) }; this.changed(); }
        break;
      case 'usage':
        if (this.snapshot) { this.snapshot = { ...this.snapshot, usage: m.usage as Usage }; this.changed(); }
        break;
      case 'projects':
        if (this.snapshot) { this.snapshot = { ...this.snapshot, projects: m.projects }; this.changed(); }
        break;
      // Their trouble (out of credits, a refused key…) is what the lens says of a session on one.
      case 'providers':
        if (this.snapshot && Array.isArray(m.providers)) { this.snapshot = { ...this.snapshot, providers: m.providers as Provider[] }; this.changed(); }
        break;
      case 'battery': this.applyBattery((m.battery ?? null) as MacBattery | null); break;
      // Voice on the Mac (Soniox) and the Lens guide's place (DAEMON.md, 8 and 9).
      case 'stt':
        if (this.snapshot) { this.snapshot = { ...this.snapshot, stt: m.stt as SttState }; this.changed(); }
        break;
      // Read aloud: the Mac's setting, and how a play on the phone app goes (data/voice.ts).
      case 'speech':
        if (this.snapshot) { this.snapshot = { ...this.snapshot, speech: m.speech as SpeechSetting }; this.changed(); }
        break;
      case 'speech.progress': {
        const progress = readProgress(m.progress);
        if (progress) this.emit({ t: 'speech_progress', progress });
        break;
      }
      case 'guide':
        if (this.snapshot) { this.snapshot = { ...this.snapshot, guide: m.guide as GuideState }; this.changed(); }
        break;
      // Limits and the account (PROTOCOL.md, "Account"), and Stay awake (DAEMON.md, 10).
      case 'account':
        if (this.snapshot) { this.snapshot = { ...this.snapshot, account: m.account as Account }; this.changed(); }
        break;
      case 'awake': this.applyAwake((m.awake ?? null) as MacAwake | null); break;
      case 'note': this.applyNote(m.note); break;
      case 'run': { const run = readRunAsks(m.run); if (run) this.emit({ t: 'run_ask', run }); break; }
      case 'mac_backpack_ending':
        this.emit({ t: 'backpack_ending', inSeconds: Number(m.inSeconds) || 600, until: Number(m.until) || 0, extendBy: Number(m.extendBy) || 7200 });
        this.changed();
        break;
      // The app on this phone, not the Mac: the glasses' settings it keeps (data/bridge.ts).
      case 'bridge.settings': {
        const settings = this.via === 'bridge' ? readBridgeSettings(m.settings) : null;
        if (settings) this.emit({ t: 'bridge_settings', settings });
        break;
      }
      case 'mac_sleep_soon':
        this.emit({ t: 'sleep_soon', inSeconds: Number(m.inSeconds) || 120, reason: m.reason === 'hot' || m.reason === 'time' ? m.reason : 'battery', percent: typeof m.percent === 'number' ? m.percent : null, stillAwake: m.stillAwake === true, otherApp: typeof m.otherApp === 'string' && m.otherApp ? m.otherApp.slice(0, 40) : null });
        this.changed();
        break;
    }
  }

  /** An automation's note: the glasses' own, once (the id is kept, so the snapshot's copy of it later isn't shown again). */
  private applyNote(raw: unknown): void {
    const n = readNote(raw);
    if (!n || n.to !== 'glasses' || this.notesSeen.has(n.id)) return;
    this.notesSeen.add(n.id);
    this.emitNote(n);
    this.changed();
  }

  /** A note that asks is a card to answer, not a text to read; one already over shows nothing. */
  private emitNote(n: AutomationNote): void {
    if (!n.ask) { this.emit({ t: 'note', note: n }); return; }
    const card = askOfNote(n, Date.now());
    if (card) this.emit({ t: 'ask', ask: card });
  }

  /**
   * The notes a snapshot carries (the last 12 hours): the lens shows the ones for the glasses it
   * hasn't, if they're fresh. The glasses off for the morning don't read the morning's notes out.
   */
  private snapshotNotes(snap: Snapshot): AutomationNote[] {
    const list = snap.automations?.notes;
    const fresh = unseenNotes(list, this.notesSeen, Date.now());
    for (const id of noteIds(list)) this.notesSeen.add(id);
    return fresh;
  }

  /** Stay awake changed on the Mac (or this lens switched it). */
  private applyAwake(a: MacAwake | null): void {
    if (!this.snapshot || JSON.stringify(this.snapshot.mac.awake ?? null) === JSON.stringify(a)) return;
    this.snapshot = { ...this.snapshot, mac: { ...this.snapshot.mac, awake: a } };
    this.changed();
  }

  /** The Mac's battery changed (the daemon says so only when it does): a change only if it really did. */
  private applyBattery(b: MacBattery | null): void {
    if (!this.snapshot || JSON.stringify(this.snapshot.mac.battery ?? null) === JSON.stringify(b)) return;
    this.snapshot = { ...this.snapshot, mac: { ...this.snapshot.mac, battery: b } };
    this.changed();
  }

  /**
   * A whole snapshot (on connecting, or polled), with the status it brings. 'change' only when the
   * snapshot or the status differs: a poll every 4 s that finds the same sessions says nothing.
   */
  private applySnapshot(snap: Snapshot, status: HubStatus, problem: string | null): void {
    const before = this.snapshot;
    const next: Snapshot = { ...snap, approvals: snap.approvals ?? [], projects: snap.projects ?? [], sessions: snap.sessions ?? [] };
    const notes = this.snapshotNotes(next);
    if (before && JSON.stringify(before) === JSON.stringify(next)) { this.setStatus(status, problem); return; }
    this.snapshot = next;
    for (const s of next.sessions) {
      const prev = before?.sessions.find(x => x.id === s.id);
      if (prev) this.transition(prev, s);
      // Seen for the first time (the lens just opened, or the session began while it was away) with
      // its turn over and agents still at work: the job goes on. Without the hold, the last agent's
      // end would read as the job done, and the live view would flash DONE before its report's turn.
      else if (s.state === 'idle' && (s.agentsRunning ?? 0) > 0) this.holding.add(s.id);
    }
    if (before) for (const a of next.approvals) if (!before.approvals.some(x => x.id === a.id)) this.emit({ t: 'approval', approval: a });
    this.since(status);
    this.status = status;
    this.problem = problem;
    this.changed();
    // After the snapshot is in place: the lens reads it when it shows the note.
    for (const note of notes) this.emitNote(note);
  }

  private applySession(s: Session): void {
    if (!this.snapshot) return;
    const prev = this.snapshot.sessions.find(x => x.id === s.id);
    this.snapshot = { ...this.snapshot, sessions: prev ? this.snapshot.sessions.map(x => (x.id === s.id ? s : x)) : [...this.snapshot.sessions, s] };
    if (prev) this.transition(prev, s);
  }

  /**
   * The job ended: Claude was working and now waits for you (or stopped on an error). A turn that
   * ends while agents it started still work hasn't finished it: the last agent's report starts a
   * turn of the main agent's own, and that one's end says so, once (as on the phone). Agents that
   * end with no turn after them (stopped, none reporting) finish it a few seconds later.
   */
  private transition(prev: Session, next: Session): void {
    const busy = (x: Session) => x.state === 'working' || x.state === 'starting' || x.state === 'needs_you';
    const agents = (x: Session) => x.agentsRunning ?? 0;
    const id = next.id;
    if (busy(prev) && (next.state === 'idle' || next.state === 'error')) {
      if (next.state === 'idle' && agents(next) > 0) { this.holding.add(id); return; }
      this.holding.delete(id);
      this.emit({ t: 'finished', sessionId: id });
      return;
    }
    if (next.state === 'ended') { this.holding.delete(id); return; }
    // Agents at work with the main agent idle, also when the lens came in after the turn ended: the
    // job goes on.
    if (next.state === 'idle' && agents(next) > 0) { this.holding.add(id); return; }
    if (!this.holding.has(id) || next.state !== 'idle' || agents(prev) === 0 || agents(next) > 0) return;
    // The last agent ended with the main agent idle. Its report's turn shows as working within a
    // couple of seconds (the Mac reads Claude Code's registry every 2 s); if none has by then,
    // the job is done now.
    const old = this.heldTimers.get(id);
    if (old) clearTimeout(old);
    this.heldTimers.set(id, setTimeout(() => {
      this.heldTimers.delete(id);
      const s = this.snapshot?.sessions.find(x => x.id === id);
      if (this.stopped || !s || !this.holding.has(id) || s.state !== 'idle' || agents(s) > 0) return;
      this.holding.delete(id);
      this.emit({ t: 'finished', sessionId: id });
      this.changed();
    }, REPORT_TURN_MS));
  }

  private applyEvent(sessionId: string, e: HnEvent): void {
    const list = this.eventCache.get(sessionId);
    if (list) {
      const i = list.findIndex(x => x.id === e.id);
      if (i >= 0) list[i] = e; else list.push(e);
      // A session followed for hours keeps its last few turns, not all of them.
      if (list.length > 600) list.splice(0, list.length - 400);
    }
    this.emit({ t: 'event', sessionId, event: e });
  }

  /** Only sessions whose agents were loaded keep a list (the lens follows those); the rest are asked for when needed. */
  private applyAgent(a: Agent): void {
    const list = a?.sessionId ? this.agentCache.get(a.sessionId) : undefined;
    if (!list) return;
    const others = list.filter(x => x.id !== a.id);
    this.agentCache.set(a.sessionId, a.superseded ? others : [...others, a]);
    this.emit({ t: 'agent', sessionId: a.sessionId, agent: a });
  }

  events(sessionId: string): HnEvent[] | null { return this.eventCache.get(sessionId) ?? null; }

  agents(sessionId: string): Agent[] | null { return this.agentCache.get(sessionId) ?? null; }

  async loadAgents(sessionId: string): Promise<Agent[]> {
    if (this.noAgents) { this.agentCache.set(sessionId, []); return []; }
    try {
      const r = await this.api.get<{ agents?: Agent[] }>(`/v1/sessions/${encodeURIComponent(sessionId)}/agents`);
      const list = (r.agents ?? []).filter(a => !a.superseded);
      this.agentCache.set(sessionId, list);
      return list;
    } catch (err) {
      // A Hypernova from before agents: the session's own count is all there is.
      if (err instanceof ApiError && err.problem === 'missing') { this.noAgents = true; this.agentCache.set(sessionId, []); return []; }
      throw err;
    }
  }

  async loadEvents(sessionId: string): Promise<HnEvent[]> {
    // The last turn is all the lens reads, and of a command's output only the end (test counts):
    // the daemon cuts both (turn=last, output=), which saves most of the bytes on a phone
    // connection. 120 events covers a long turn.
    const base = `/v1/sessions/${encodeURIComponent(sessionId)}/events?limit=120&output=8192`;
    let r = await this.api.get<{ events: HnEvent[] }>(`${base}&turn=last`);
    // The daemon's last turn starts at whatever was typed last, a slash command too (/cost after a
    // reply); the lens' starts at the last prompt (lastTurn). Then the turns before are needed too.
    const first = r.events?.[0];
    if (first?.kind === 'user' && first.command && first.command.kind !== 'prompt' && first.command.kind !== 'skill') r = await this.api.get<{ events: HnEvent[] }>(base);
    const live = this.eventCache.get(sessionId) ?? [];
    const merged = [...(r.events ?? [])];
    // Keep what came over the socket while this loaded. Older events from an earlier load belong
    // to turns before this one: after it they would look like the last turn.
    const since = merged.length ? merged[merged.length - 1].ts : 0;
    for (const e of live) if (e.ts >= since && !merged.some(x => x.id === e.id)) merged.push(e);
    this.eventCache.set(sessionId, merged);
    return merged;
  }

  async answer(approvalId: string, answer: ApprovalAnswer): Promise<void> {
    await this.api.post(`/v1/approvals/${encodeURIComponent(approvalId)}`, answer);
  }

  /** The daemon decides who answered from the request itself: Lens through the phone is "glasses" (X-Hypernova-Via). */
  async answerAsk(runId: string, stepId: string, answer: string): Promise<void> {
    await this.api.post(`/v1/automations/runs/${encodeURIComponent(runId)}/answer`, { stepId, answer });
  }

  async prompt(sessionId: string, text: string, replace = false): Promise<{ queued: boolean; session?: Session | null; forked?: boolean }> {
    const clientId = `lens-${Date.now().toString(36)}-${(this.clientSeq++).toString(36)}`;
    return this.api.post(`/v1/sessions/${encodeURIComponent(sessionId)}/prompt`, { text, clientId, ...(replace ? { replace: true } : {}) });
  }

  async newSession(projectPath: string, prompt: string, choice?: NewSessionChoice | null): Promise<Session | null> {
    const r = await this.api.post<{ session: Session | null }>('/v1/sessions', { projectPath, prompt, ...chosen(choice) });
    return r.session ?? null;
  }

  async newNebulaSession(name: string, prompt: string, choice?: NewSessionChoice | null): Promise<Session | null> {
    const r = await this.api.post<{ session: Session | null }>('/v1/sessions', { newProject: { name, git: true, nebula: true }, prompt, ...chosen(choice) });
    return r.session ?? null;
  }

  async setAwake(mode: MacAwake['mode']): Promise<MacAwake | null> {
    const r = await this.api.post<{ awake: MacAwake | null }>('/v1/mac/awake', { mode });
    this.applyAwake(r.awake ?? null);
    return r.awake ?? null;
  }

  async extendAwake(): Promise<MacAwake | null> {
    const r = await this.api.post<{ awake: MacAwake | null }>('/v1/mac/awake', { extend: true });
    this.applyAwake(r.awake ?? null);
    return r.awake ?? null;
  }

  async interrupt(sessionId: string): Promise<void> {
    await this.api.post(`/v1/sessions/${encodeURIComponent(sessionId)}/interrupt`, {});
  }

  async setMode(sessionId: string, mode: 'plan' | 'default'): Promise<{ applies: string | null }> {
    // The session's new mode comes over the socket like any change.
    const r = await this.api.post<{ applies?: string }>(`/v1/sessions/${encodeURIComponent(sessionId)}/mode`, { permissionMode: mode });
    return { applies: r.applies ?? null };
  }

  async continueSession(sessionId: string): Promise<void> {
    await this.api.post(`/v1/sessions/${encodeURIComponent(sessionId)}/continue`, {});
  }

  async note(text: string, project: string | null): Promise<{ path: string }> {
    const r = await this.api.post<{ note: { path: string } }>('/v1/nebula/notes', { text, ...(project ? { project } : {}) });
    return { path: r.note?.path ?? 'Nebula/notes.md' };
  }

  async menu(sessionId: string, menuId: string, pick: { choice: number } | { cancel: true }): Promise<void> {
    await this.api.post(`/v1/sessions/${encodeURIComponent(sessionId)}/menu`, { menuId, ...pick });
  }

  async telescope(sessionId: string, ask: ScopeAsk): Promise<Shot> {
    try {
      // Like a screenshot's wait: Chrome takes 20 s at most, adb and the drawings far less.
      const r = await this.api.post<{ shot: Omit<Shot, 'sessionId'> }>(`/v1/sessions/${encodeURIComponent(sessionId)}/telescope`, ask, { timeoutMs: 45_000 });
      return { ...r.shot, sessionId, url: await this.shotUrl(r.shot.url) };
    } catch (err) {
      // A Hypernova from before Telescope shows pages only, through its screenshot route.
      if (err instanceof ApiError && err.problem === 'missing' && (!ask.source || ask.source === 'web')) return this.screenshot(sessionId, ask.url);
      throw err;
    }
  }

  async screenshot(sessionId: string, url?: string): Promise<Shot> {
    // Finding the page, then Chrome (20 s at most), maybe after another session's capture: the
    // Mac takes one at a time.
    const r = await this.api.post<{ shot: Omit<Shot, 'sessionId'> }>(`/v1/sessions/${encodeURIComponent(sessionId)}/screenshot`, url ? { url } : {}, { timeoutMs: 45_000 });
    return { ...r.shot, sessionId, url: await this.shotUrl(r.shot.url) };
  }

  /** The last picture fetched through the app, as a blob: URL, let go when the next one comes. */
  private lastShot: string | null = null;

  /**
   * Where a Telescope picture is shown from: straight to a Mac, its own link (its name is its key).
   * Through the app, the picture is fetched now, signed and sealed like everything else, its name
   * sealed too (a page's `<img>` can't sign, and a picture of the Mac's screen is nobody's to read
   * on the way), and shown from a blob: URL of its own.
   */
  private async shotUrl(link: string): Promise<string> {
    const u = new URL(link, this.pairing.baseUrl + '/');
    if (this.via !== 'bridge') return u.toString();
    // By its name, inside the sealed body (`POST /bridge/shot`): the Mac serves a picture by its name
    // alone, so a name read off the wire would be a picture for whoever read it.
    const name = /^\/v1\/shots\/([A-Za-z0-9_-]{1,128})\.png$/.exec(u.pathname)?.[1];
    if (!name) throw new ApiError('server', 'The Mac named a picture Lens can’t ask for.');
    const res = await this.api.fetch('POST', '/bridge/shot', { name }, { timeoutMs: 20_000 });
    const blob = await res.blob();
    if (this.lastShot) { try { URL.revokeObjectURL(this.lastShot); } catch { /* gone */ } }
    this.lastShot = URL.createObjectURL(blob);
    return this.lastShot;
  }

  async preview(target: { port: number } | { path: string }): Promise<string> {
    const r = await this.api.post<{ url: string }>('/v1/preview', target);
    const u = new URL(r.url, this.pairing.baseUrl + '/');
    // The Mac names its own address; through the app, the page comes the same way as everything else.
    if (this.via === 'bridge') return new URL(u.pathname + u.search, this.pairing.baseUrl + '/').toString();
    return u.toString();
  }

  async speak(text: string, voice: string | null, rate: number): Promise<ArrayBuffer> {
    // The Mac gives `say` 40 s for a piece; waiting a little longer never cuts off one it finishes.
    const res = await this.api.fetch('POST', '/v1/speech', { text, ...(voice ? { voice } : {}), rate }, { timeoutMs: 45_000 });
    return res.arrayBuffer();
  }

  async playOnPhone(text: string, sessionId: string | null): Promise<PlayStart | null> {
    try {
      const r = await this.api.post<{ play: PlayStart }>('/v1/speech/play', { text, ...(sessionId ? { sessionId } : {}) }, { timeoutMs: 8000 });
      return r.play?.id ? r.play : null;
    } catch (err) {
      // No phone app playing, or a Mac from before plays: the glasses read it themselves.
      if (err instanceof ApiError && (err.code === 'no_player' || err.problem === 'missing')) return null;
      throw err;
    }
  }

  async stopOnPhone(id: string): Promise<void> {
    try { await this.api.post(`/v1/speech/plays/${encodeURIComponent(id)}/stop`, {}, { timeoutMs: 5000 }); } catch { /* over already */ }
  }

  async voices(): Promise<MacVoice[]> {
    return (await this.api.get<{ voices: MacVoice[] }>('/v1/speech/voices')).voices ?? [];
  }

  async unpair(): Promise<void> {
    try { await this.api.del('/v1/devices/me', { timeoutMs: 5000 }); } catch { /* forget it here anyway */ }
  }
}
