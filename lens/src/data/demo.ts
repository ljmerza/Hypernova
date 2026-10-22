// Sample data: a Mac with four projects at work, so Lens can be tried (and screenshotted) without a
// Mac. It behaves like the daemon: answering an approval lets the session carry on and finish,
// a prompt makes it work for a few seconds and reply. Hypernova's session works with a workflow of
// agents; once you open it, its turn plays out in about half a minute: agents report one by one,
// Claude builds and tests, and it finishes.
import type { HubEvent, HubLike, HubStatus, MacVoice } from './hub.ts';
import { ApiError } from './api.ts';
import type { Agent, Approval, ApprovalAnswer, AutomationNote, HnEvent, MacAwake, Session, Shot, Snapshot } from '../core/types.ts';
import { askOfNote } from '../core/ask.ts';
import type { ScopeAsk } from '../core/telescope.ts';
import { demoTelescope } from './demo-telescope.ts';
import { DEMO_READ_ALOUD, DemoPhoneSpeech } from './demo-speech.ts';
import type { PlayStart } from '../core/speech-out.ts';
import type { NewSessionChoice } from './bridge.ts';

const MIN = 60_000;

export const DEMO_SPEECH = [
  'in hyperscribe, make the export button remember the last folder and run the tests',
  'fix the failing pairing test and run it again',
  'status',
];

const REPLY = `Fixed the export so it remembers the last folder; all 48 tests pass now.

What changed:
- **ExportSheet.kt** keeps the folder in the app's settings and opens the picker there next time.
- **SettingsStore.kt** gained \`lastExportFolder\`, migrated from the old key.
- A test covers the round trip, and the flaky timing test now waits for the file instead of sleeping.

I didn't touch the share flow; it still asks every time, which matches the Android guidelines. Run \`./gradlew test\` to see the 48 tests.`;

/** The same reply, long enough to scroll on either look, in English and in Russian (look comparisons, localise). */
export const LONG_REPLY = {
  en: `Fixed the export so it remembers the last folder; all 48 tests pass now.

What changed:
- **ExportSheet.kt** keeps the folder in the app's settings and opens the picker there next time.
- **SettingsStore.kt** gained \`lastExportFolder\`, migrated from the old key, so nobody loses their choice after the update.
- A test covers the round trip, and the flaky timing test now waits for the file instead of sleeping for two seconds.

Two things I noticed but left alone:
1. The share flow still asks for a folder every time. That matches the Android guidelines, but if you want it to remember too, it is a three-line change in ShareSheet.kt.
2. Exports over 50 MB take about nine seconds on the Pixel 8 emulator, mostly in the WAV encoder. Moving it to a background thread would let the sheet close at once.

Run \`./gradlew test\` to see the 48 tests, or \`./gradlew connectedCheck\` for the two device tests.`,
  ru: `Исправил экспорт: теперь он запоминает последнюю папку, и все 48 тестов проходят.

Что изменилось:
- **ExportSheet.kt** хранит папку в настройках приложения и в следующий раз открывает выбор файла сразу там.
- В **SettingsStore.kt** появилось поле \`lastExportFolder\`; старый ключ переносится, так что после обновления выбор не теряется.
- Новый тест проверяет путь туда и обратно, а нестабильный тест на таймер теперь ждёт файл вместо двух секунд сна.

Две вещи заметил, но не трогал:
1. При отправке через «Поделиться» папку всё ещё спрашивают каждый раз. Так советуют рекомендации Android, но если хочешь, это три строки в ShareSheet.kt.
2. Экспорт больше 50 МБ занимает около девяти секунд на эмуляторе Pixel 8, в основном в кодировщике WAV. Если перенести его в фоновый поток, окно закроется сразу.

Запусти \`./gradlew test\`, чтобы увидеть все 48 тестов, или \`./gradlew connectedCheck\` для двух тестов на устройстве.`,
};

export class DemoHub implements HubLike {
  snapshot: Snapshot;
  status: HubStatus = 'live';
  problem: string | null = null;
  offlineSince: number | null = null;
  readonly macName = 'MacBook Air';
  private listeners = new Set<(e: HubEvent) => void>();
  private eventsBy = new Map<string, HnEvent[]>();
  private agentsBy = new Map<string, Agent[]>();
  private agentsAsked = new Set<string>();
  private seq = 0;
  /** Hypernova's turn plays out once, from the first time its agents are asked for. */
  private played = false;
  private skip = 0;
  /** The phone app playing the glasses' speech, as sample data (demo-speech.ts). */
  private phoneSpeech = new DemoPhoneSpeech(e => this.emit(e));

  constructor(now = Date.now()) {
    const s = (id: string, projectName: string, state: Session['state'], extra: Partial<Session> = {}): Session => ({
      id, title: projectName, cwd: `/Users/you/Programming/${projectName}`, projectName, origin: 'terminal', state, model: 'opus',
      permissionMode: 'default', createdAt: now - 40 * MIN, lastActivityAt: now - MIN, lastText: null, activity: null,
      pendingApprovalIds: [], stoppedForAway: false, ...extra,
    });
    const push: Approval = {
      id: 'ap-push', sessionId: 's-0stems', createdAt: now - 70_000, expiresAt: now + 9 * MIN, kind: 'tool', toolName: 'Bash',
      summary: 'Bash: git push origin main', input: { command: 'git push origin main' }, reason: null, canSession: false,
      questions: null, plan: null,
    };
    this.snapshot = {
      mac: { id: 'demo', name: this.macName, daemonVersion: 'demo', battery: { percent: 62, charging: false, onAC: false, minutesLeft: 214 },
        awake: { mode: 'working', holding: true, lidClosedOk: false, backpackReady: true, until: null, reason: null } },
      account: { state: 'ok', limit: null, since: null },
      usage: { fiveHour: { pct: 42, resetsAt: now + 2 * 3600_000 + 12 * MIN }, sevenDay: { pct: 18, resetsAt: now + 61 * 3600_000 }, source: 'official', observedAt: now },
      sessions: [
        s('s-0stems', '0stems', 'needs_you', { title: 'release landing copy', pendingApprovalIds: ['ap-push'], activity: 'Bash: git push origin main', lastActivityAt: now - 70_000 }),
        s('s-hypernova', 'hypernova', 'working', { title: 'cosmos renderer', activity: 'Bash: ./gradlew build', lastActivityAt: now - 2 * MIN - 5000, agentsRunning: 2 }),
        s('s-hyperscribe', 'hyperscribe', 'idle', { title: 'transcript export', lastActivityAt: now - 3 * MIN, lastText: REPLY.slice(0, 200) }),
        s('s-site', 'prudnikov-site', 'idle', { title: 'blog layout', lastActivityAt: now - 22 * MIN }),
      ],
      approvals: [push],
      projects: ['0stems', 'hypernova', 'hyperscribe', 'prudnikov-site', 'hypertasks'].map((name, i) => ({ path: `/Users/you/Programming/${name}`, name, lastActivityAt: now - i * MIN, sessionCount: 1 })),
      speech: DEMO_READ_ALOUD,
    };
    this.eventsBy.set('s-hyperscribe', this.turn('make the export button remember the last folder and run the tests', REPLY, now - 3 * MIN - 252_000, 252_000, [
      ['Edit', 'app/src/main/java/com/prudnikweb/hyperscribe/ExportSheet.kt'],
      ['Edit', 'app/src/main/java/com/prudnikweb/hyperscribe/SettingsStore.kt'],
      ['Write', 'app/src/test/java/com/prudnikweb/hyperscribe/ExportFolderTest.kt'],
    ], { command: './gradlew test', output: 'BUILD SUCCESSFUL in 41s\n48 tests completed, 0 failed', exitCode: 0 }));
    this.hypernovaTurn(now);
  }

  /** Hypernova's turn so far: a workflow of three surveys (one has reported), edits, a build running. */
  private hypernovaTurn(now: number): void {
    const start = now - 2 * MIN - 5000;
    const id = () => `e${this.seq++}`;
    const tool = (ts: number, name: string, summary: string, status: 'running' | 'done', extra: Partial<Extract<HnEvent, { kind: 'tool' }>> = {}): HnEvent =>
      ({ id: id(), kind: 'tool', ts, toolUseId: id(), name, summary, input: {}, status, output: null, exitCode: null, durationMs: null, path: null, ...extra });
    this.eventsBy.set('s-hypernova', [
      { id: id(), kind: 'user', ts: start, text: 'draw comets for sessions that finished in the last hour, and survey what still reads the old snapshot first' },
      { id: id(), kind: 'assistant', ts: start + 6000, text: 'I’ll survey the readers with a workflow of three agents while I start on the comets.', streaming: false },
      tool(start + 10_000, 'Workflow', 'survey', 'running'),
      tool(start + 22_000, 'Read', 'core/Solar.kt', 'done'),
      tool(start + 41_000, 'Edit', 'core/Comet.kt', 'done', { path: 'android/app/src/main/java/core/Comet.kt' }),
      tool(start + 58_000, 'Edit', 'ui/Cosmos.kt', 'done', { path: 'android/app/src/main/java/ui/Cosmos.kt' }),
      { id: id(), kind: 'assistant', ts: start + 96_000, text: 'Comets drift along their planet’s orbit and fade over an hour. Building to check the shader compiles.', streaming: false },
      tool(start + 110_000, 'Bash', './gradlew build', 'running', { input: { command: './gradlew build' } }),
    ]);
    const agent = (aid: string, label: string, startedAt: number, extra: Partial<Agent>): Agent => ({
      id: aid, sessionId: 's-hypernova', parentAgentId: null, workflowId: 'wf_demo', label, type: 'workflow-subagent', status: 'running',
      activity: null, startedAt, endedAt: null, lastActivityAt: now, result: null, ...extra,
    });
    this.agentsBy.set('s-hypernova', [
      agent('a0000000000000001', 'survey:parser', start + 12_000, { activity: 'Read: data/SnapshotParser.kt' }),
      agent('a0000000000000002', 'survey:widget', start + 13_000, { activity: 'Grep: “agentsRunning” in widget' }),
      agent('a0000000000000003', 'survey:types', start + 11_000, { status: 'done', endedAt: start + 85_000,
        result: 'Session and Agent match the daemon’s protocol; Lens alone was missing `superseded`, which drops a step’s earlier attempt.' }),
    ]);
  }

  /**
   * Hypernova's turn as it is `seconds` in, now, before the lens opens it (simulator scenes: a later
   * moment, shot while the simulator still runs timers); the rest plays from there.
   */
  fastForward(seconds: number): void {
    this.skip = seconds;
    this.play(true);
  }

  /** What happens next in Hypernova's turn, a few seconds apart, ending in its reply. */
  private play(now = false): void {
    if (this.played) return;
    this.played = true;
    const sid = 's-hypernova';
    // One step at a time, each timing the next. Steps up to `skip` happen at once.
    const steps: Array<[number, () => void]> = [];
    const at = (ms: number, fn: () => void) => steps.push([ms, fn]);
    let last = this.skip * 1000;
    const next = () => {
      while (steps[0] && steps[0][0] <= last) { if (this.session(sid)?.state !== 'working') return; steps.shift()![1](); }
      const step = steps.shift();
      if (!step) return;
      const [ms, fn] = step;
      setTimeout(() => { if (this.session(sid)?.state === 'working') { fn(); next(); } }, ms - last);
      last = ms;
    };
    const id = () => `e${this.seq++}`;
    const add = (e: HnEvent) => { this.eventsBy.set(sid, [...(this.eventsBy.get(sid) ?? []), e]); this.emit({ t: 'event', sessionId: sid, event: e }); };
    const finishTool = (name: string, output: string) => {
      const list = this.eventsBy.get(sid) ?? [];
      let i = list.length - 1;
      while (i >= 0 && !(list[i].kind === 'tool' && (list[i] as Extract<HnEvent, { kind: 'tool' }>).name === name && (list[i] as Extract<HnEvent, { kind: 'tool' }>).status === 'running')) i--;
      if (i < 0) return;
      const e = { ...(list[i] as Extract<HnEvent, { kind: 'tool' }>), status: 'done' as const, output, exitCode: 0, durationMs: 4000 };
      this.eventsBy.set(sid, list.map((x, k) => (k === i ? e : x)));
      this.emit({ t: 'event', sessionId: sid, event: e });
    };
    const agent = (aid: string, p: Partial<Agent>) => {
      const list = this.agentsBy.get(sid) ?? [];
      const a = { ...list.find(x => x.id === aid)!, ...p, lastActivityAt: Date.now() };
      this.agentsBy.set(sid, list.map(x => (x.id === aid ? a : x)));
      this.emit({ t: 'agent', sessionId: sid, agent: a });
      this.emit({ t: 'change' });
    };
    const tool = (name: string, summary: string, path: string | null = null) =>
      add({ id: id(), kind: 'tool', ts: Date.now(), toolUseId: id(), name, summary, input: name === 'Bash' ? { command: summary } : { file_path: path }, status: 'running', output: null, exitCode: null, durationMs: null, path });
    at(4000, () => agent('a0000000000000001', { activity: 'Grep: “snapshot” in data' }));
    at(8000, () => {
      finishTool('Bash', 'BUILD SUCCESSFUL in 38s');
      add({ id: id(), kind: 'assistant', ts: Date.now(), text: 'The build passes. Next, a test for the fade: a comet an hour old should be gone.', streaming: false });
      tool('Edit', 'test/CometTest.kt', 'android/app/src/test/java/core/CometTest.kt');
      this.patch(sid, { activity: 'Edit: test/CometTest.kt', lastActivityAt: Date.now() });
    });
    at(12_000, () => {
      agent('a0000000000000001', { status: 'done', activity: null, endedAt: Date.now(),
        result: 'Three readers still parse the old snapshot: SnapshotParser.kt, LegacyFeed.kt and the widget’s provider. None breaks on the new field.' });
      this.patch(sid, { agentsRunning: 1 });
    });
    at(16_000, () => {
      finishTool('Edit', '');
      tool('Bash', './gradlew test');
      this.patch(sid, { activity: 'Bash: ./gradlew test', lastActivityAt: Date.now() });
    });
    at(21_000, () => {
      agent('a0000000000000002', { status: 'done', activity: null, endedAt: Date.now(),
        result: 'The widget counts sessions by state only, so a session whose agents still work reads as idle there. One line in WidgetModel.kt fixes it.' });
      this.patch(sid, { agentsRunning: 0 });
    });
    at(27_000, () => {
      finishTool('Bash', 'BUILD SUCCESSFUL in 52s\n112 tests completed, 0 failed');
      const reply = 'Comets are in: each finished session leaves one that drifts along its orbit and fades within the hour. 112 tests pass.\n\nThe surveys found three readers of the old snapshot (none breaks) and a widget that ignores agents; both are noted in TODO.md.';
      add({ id: id(), kind: 'assistant', ts: Date.now(), text: reply, streaming: false });
      const first = this.eventsBy.get(sid)?.[0]?.ts ?? Date.now();
      add({ id: id(), kind: 'result', ts: Date.now(), ok: true, durationMs: Date.now() - first, costUsd: null, turns: 14, error: null });
      this.patch(sid, { state: 'idle', activity: null, lastActivityAt: Date.now(), lastText: reply.slice(0, 200) });
      this.emit({ t: 'finished', sessionId: sid });
    });
    // Not inside the call that asked for the agents: that is the lens drawing.
    if (now) next(); else void Promise.resolve().then(next);
  }

  private session(id: string): Session | undefined { return this.snapshot.sessions.find(s => s.id === id); }

  /** A whole turn of events: prompt, edits, a test run, the reply and the result. */
  private turn(prompt: string, reply: string, start: number, ms: number, edits: Array<[string, string]>, bash?: { command: string; output: string; exitCode: number }): HnEvent[] {
    const id = () => `e${this.seq++}`;
    const ev: HnEvent[] = [{ id: id(), kind: 'user', ts: start, text: prompt }];
    let t = start + 4000;
    for (const [name, path] of edits) {
      const diff = DEMO_DIFFS[path.split('/').pop() ?? ''] ?? null;
      ev.push({ id: id(), kind: 'tool', ts: (t += 20_000), toolUseId: id(), name, summary: path.split('/').slice(-2).join('/'), input: { file_path: path }, status: 'done', output: null, exitCode: null, durationMs: 40, path, diff: diff && { ...diff, path } });
    }
    if (bash) ev.push({ id: id(), kind: 'tool', ts: (t += 30_000), toolUseId: id(), name: 'Bash', summary: bash.command, input: { command: bash.command }, status: bash.exitCode ? 'error' : 'done', output: bash.output, exitCode: bash.exitCode, durationMs: 41_000, path: null });
    ev.push({ id: id(), kind: 'assistant', ts: start + ms - 2000, text: reply, streaming: false });
    ev.push({ id: id(), kind: 'result', ts: start + ms, ok: true, durationMs: ms, costUsd: null, turns: 9, error: null });
    return ev;
  }

  subscribe(fn: (e: HubEvent) => void): () => void { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  private emit(e: HubEvent): void { for (const fn of this.listeners) fn(e); }

  private patch(id: string, p: Partial<Session>): void {
    this.snapshot = { ...this.snapshot, sessions: this.snapshot.sessions.map(s => (s.id === id ? { ...s, ...p } : s)) };
    this.emit({ t: 'change' });
  }

  events(sessionId: string): HnEvent[] | null { return this.eventsBy.get(sessionId) ?? null; }
  async loadEvents(sessionId: string): Promise<HnEvent[]> { return this.eventsBy.get(sessionId) ?? []; }
  /** Like the Mac's: none until asked for. */
  agents(sessionId: string): Agent[] | null { return this.agentsAsked.has(sessionId) ? this.agentsBy.get(sessionId) ?? [] : null; }
  async loadAgents(sessionId: string): Promise<Agent[]> {
    this.agentsAsked.add(sessionId);
    if (sessionId === 's-hypernova') this.play();
    return this.agentsBy.get(sessionId) ?? [];
  }

  /** Work for a while, then finish with `reply` (and its facts). */
  private work(id: string, prompt: string, activity: string, reply: string, ms = 5000): void {
    // A new turn: its prompt first, so the session's live view starts from it.
    const asked: HnEvent = { id: `e${this.seq++}`, kind: 'user', ts: Date.now(), text: prompt };
    this.eventsBy.set(id, [asked]);
    this.emit({ t: 'event', sessionId: id, event: asked });
    this.patch(id, { state: 'working', activity, lastActivityAt: Date.now() });
    setTimeout(() => {
      const now = Date.now();
      this.eventsBy.set(id, this.turn(prompt, reply, now - ms, ms, [['Edit', 'src/pairing/PairScreen.kt']], { command: 'npm test', output: '# pass 12\n# fail 0', exitCode: 0 }));
      this.patch(id, { state: 'idle', activity: null, lastActivityAt: now, lastText: reply.slice(0, 200) });
      this.emit({ t: 'finished', sessionId: id });
    }, ms);
  }

  async answer(approvalId: string, a: ApprovalAnswer): Promise<void> {
    const ap = this.snapshot.approvals.find(x => x.id === approvalId);
    if (!ap) throw new ApiError('server', 'This was already answered.', 409, 'resolved');
    this.snapshot = { ...this.snapshot, approvals: this.snapshot.approvals.filter(x => x.id !== approvalId) };
    this.patch(ap.sessionId, { pendingApprovalIds: [] });
    if (a.decision === 'deny') { this.patch(ap.sessionId, { state: 'idle', activity: null }); return; }
    this.work(ap.sessionId, 'push the release', 'Bash: git push origin main', 'Pushed main to origin; the release workflow started.', 4000);
  }

  async prompt(sessionId: string, text: string): Promise<{ queued: boolean }> {
    const s = this.snapshot.sessions.find(x => x.id === sessionId);
    if (!s) throw new ApiError('server', 'That session no longer exists.', 404, 'no_session');
    if (s.state === 'needs_you') throw new ApiError('server', 'Claude is waiting for your answer above. Answer it first.', 409, 'needs_answer');
    const queued = s.state === 'working';
    if (!queued) this.work(sessionId, text, 'Thinking', 'Done: the pairing test was racing the camera; it waits for the preview now and passes. 12 tests pass.', 6000);
    return { queued };
  }

  /** The provider and model each new session was started with (tests and scenes read them). */
  readonly started: Array<{ projectPath: string; choice: NewSessionChoice | null }> = [];
  async newSession(projectPath: string, prompt: string, choice: NewSessionChoice | null = null): Promise<Session | null> {
    this.started.push({ projectPath, choice });
    const name = projectPath.split('/').pop() ?? 'project';
    const now = Date.now();
    const s: Session = { id: `s-new-${now}`, title: prompt.slice(0, 60), cwd: projectPath, projectName: name, origin: 'hypernova', state: 'starting', model: 'opus', permissionMode: 'default', createdAt: now, lastActivityAt: now, lastText: null, activity: null, pendingApprovalIds: [], stoppedForAway: false };
    this.snapshot = { ...this.snapshot, sessions: [s, ...this.snapshot.sessions] };
    this.emit({ t: 'change' });
    this.work(s.id, prompt, 'Thinking', 'Started: I read the project and drafted a plan in PLAN.md.', 7000);
    return s;
  }

  /** A new session in a folder of its own in Nebula, named from its prompt and made unique. */
  async newNebulaSession(name: string, prompt: string, choice: NewSessionChoice | null = null): Promise<Session | null> {
    const taken = new Set(this.snapshot.sessions.map(x => x.projectName));
    let unique = name;
    for (let i = 2; taken.has(unique); i++) unique = `${name}-${i}`;
    const s = await this.newSession(`/Users/you/Programming/Nebula/${unique}`, prompt, choice);
    this.emit({ t: 'change' });
    return s;
  }

  /** Stay awake, as the Mac would take it. */
  async setAwake(mode: MacAwake['mode']): Promise<MacAwake | null> {
    const ready = this.snapshot.mac.awake?.backpackReady ?? true;
    const awake: MacAwake = { mode, holding: mode !== 'off', lidClosedOk: mode === 'backpack' && ready, backpackReady: ready, until: mode === 'backpack' ? Date.now() + 8 * 3600_000 : null, reason: null };
    this.snapshot = { ...this.snapshot, mac: { ...this.snapshot.mac, awake } };
    this.emit({ t: 'change' });
    return awake;
  }

  /** Backpack's "+2 h", as the Mac would take it. */
  async extendAwake(): Promise<MacAwake | null> {
    const cur = this.snapshot.mac.awake;
    if (!cur || cur.mode !== 'backpack' || cur.until == null) return cur ?? null;
    const awake: MacAwake = { ...cur, until: cur.until + 2 * 3600_000, extendable: false };
    this.snapshot = { ...this.snapshot, mac: { ...this.snapshot.mac, awake } };
    this.emit({ t: 'change' });
    return awake;
  }

  /** Simulator scenes: change the Mac as the daemon would say it (limits, the battery, Stay awake), then tell the lens. */
  set(p: { usage?: Partial<Snapshot['usage']>; account?: Snapshot['account']; battery?: Snapshot['mac']['battery']; awake?: Snapshot['mac']['awake']; session?: [string, Partial<Session>];
    providers?: Snapshot['providers']; status?: { status: HubStatus; problem?: string | null; since?: number | null } }): void {
    const snap = this.snapshot;
    if (p.status) { this.status = p.status.status; this.problem = p.status.problem ?? null; this.offlineSince = p.status.status === 'offline' ? p.status.since ?? Date.now() : null; }
    this.snapshot = {
      ...snap,
      usage: p.usage ? { ...snap.usage, ...p.usage } : snap.usage,
      account: p.account ?? snap.account,
      providers: p.providers ?? snap.providers,
      mac: { ...snap.mac, ...(p.battery !== undefined ? { battery: p.battery } : {}), ...(p.awake !== undefined ? { awake: p.awake } : {}) },
      sessions: p.session ? snap.sessions.map(x => (x.id === p.session![0] ? { ...x, ...p.session![1] } : x)) : snap.sessions,
    };
    this.emit({ t: 'change' });
  }

  /**
   * Simulator scenes comparing the looks (lens/look.ts): the sample Mac's words in `lang`, the
   * hyperscribe reply long enough to scroll, and 0stems' push with a reason and a longer command.
   */
  localise(lang: 'en' | 'ru'): void {
    const ru = lang === 'ru';
    const titles: Record<string, string> = ru
      ? { 's-0stems': 'тексты для лендинга', 's-hypernova': 'рендер космоса', 's-hyperscribe': 'экспорт расшифровок', 's-site': 'вёрстка блога' }
      : { 's-0stems': 'release landing copy', 's-hypernova': 'cosmos renderer', 's-hyperscribe': 'transcript export', 's-site': 'blog layout' };
    const reply = LONG_REPLY[lang];
    const command = ru ? 'git commit -am "Экспорт запоминает последнюю папку" && git push origin main' : 'git commit -am "Export remembers the last folder" && git push origin main';
    const reason = ru ? 'Отправить исправление в main, чтобы его забрала ночная сборка' : 'Ship the fix to main so the nightly build picks it up';
    this.snapshot = {
      ...this.snapshot,
      sessions: this.snapshot.sessions.map(x => ({ ...x, title: titles[x.id] ?? x.title, ...(x.id === 's-hyperscribe' ? { lastText: reply.slice(0, 200) } : {}) })),
      approvals: this.snapshot.approvals.map(a => (a.id === 'ap-push' ? { ...a, summary: `Bash: ${command}`, input: { command }, reason } : a)),
    };
    const turn = this.eventsBy.get('s-hyperscribe') ?? [];
    this.eventsBy.set('s-hyperscribe', turn.map(e => (e.kind === 'assistant' ? { ...e, text: reply } : e)));
    this.emit({ t: 'change' });
  }

  /**
   * Simulator scenes: a run waits at an `ask` step, as the note brings it (PROTOCOL.md, "Answering
   * an ask"). `minutes`: until its default is taken. Answers made here are kept in `answered`.
   */
  automationAsk(question = 'Post this review to the pull request?', name = 'Review before merge', answers = ['Approve', 'Skip'], minutes = 10): void {
    const id = Date.now().toString(36);
    // The hub turns a note with `ask` into this card (LiveHub.emitNote); the sample Mac does it here.
    const note: AutomationNote = { id: `no_${id}`, automationId: 'au_a1b2c3d4e5', runId: `ru_${id}`, name, text: question, to: 'glasses', at: Date.now(),
      ask: { runId: `ru_${id}`, stepId: 'st_ask', question, answers, default: answers[answers.length - 1], until: Date.now() + minutes * 60_000 } };
    const card = askOfNote(note, Date.now());
    if (card) this.emit({ t: 'ask', ask: card });
  }

  readonly answered: Array<{ runId: string; stepId: string; answer: string }> = [];
  async answerAsk(runId: string, stepId: string, answer: string): Promise<void> {
    this.answered.push({ runId, stepId, answer });
    this.emit({ t: 'run_ask', run: { runId, open: null, ended: [{ stepId, text: answer, by: 'glasses' }] } });
  }

  /** Simulator scenes: an automation tells the glasses something, as the socket brings it. */
  automationNote(text: string, name = 'Morning brief'): void {
    this.emit({ t: 'note', note: { id: `no_${Date.now().toString(36)}`, automationId: 'au_a1b2c3d4e5', runId: 'ru_0123456789', name, text, to: 'glasses', at: Date.now() } });
  }

  /** Simulator scenes: the Mac about to sleep (Stay awake's guards). */
  sleepSoon(reason: 'battery' | 'hot' | 'time' = 'battery'): void { this.emit({ t: 'sleep_soon', inSeconds: 120, reason }); }

  /** Simulator scenes: an approval for `sessionId`, as the socket brings one. */
  approve(sessionId: string, command: string): void {
    const now = Date.now();
    const a: Approval = { id: `ap-${now}`, sessionId, createdAt: now, expiresAt: now + 10 * MIN, kind: 'tool', toolName: 'Bash', summary: `Bash: ${command}`, input: { command }, reason: null, canSession: false, questions: null, plan: null };
    this.snapshot = { ...this.snapshot, approvals: [...this.snapshot.approvals, a], sessions: this.snapshot.sessions.map(x => (x.id === sessionId ? { ...x, state: 'needs_you', pendingApprovalIds: [a.id] } : x)) };
    this.emit({ t: 'approval', approval: a });
    this.emit({ t: 'change' });
  }

  async interrupt(sessionId: string): Promise<void> { this.patch(sessionId, { state: 'idle', activity: null, agentsRunning: 0 }); }

  /** "Plan first" / "go ahead". */
  async setMode(sessionId: string, mode: 'plan' | 'default'): Promise<{ applies: string | null }> {
    const s = this.session(sessionId);
    if (!s) throw new ApiError('server', 'That session no longer exists.', 404, 'no_session');
    this.patch(sessionId, { permissionMode: mode });
    return { applies: s.state === 'working' ? 'next_turn' : 'now' };
  }

  /** "Continue": as the Mac's /continue, a prompt to carry on. */
  async continueSession(sessionId: string): Promise<void> {
    await this.prompt(sessionId, 'I\'m back. Please continue where you left off.');
  }

  /** Notes said to the glasses, kept here (sample data has no Nebula). */
  readonly notes: Array<{ text: string; project: string | null; at: number }> = [];
  async note(text: string, project: string | null): Promise<{ path: string }> {
    this.notes.push({ text, project, at: Date.now() });
    return { path: '~/Programming/Nebula/notes.md' };
  }
  async menu(): Promise<void> {}

  async screenshot(sessionId: string): Promise<Shot> {
    await new Promise(r => setTimeout(r, 700));
    const { url, width, height } = demoScreenshot();
    return { sessionId, url, width, height, title: 'Hyperscribe — Export', source: 'demo', pageUrl: 'http://localhost:5173/export', at: Date.now() };
  }

  /** Telescope: each sample project shows a source of its own (data/demo-telescope.ts). */
  async telescope(sessionId: string, ask: ScopeAsk): Promise<Shot> {
    await new Promise(r => setTimeout(r, 500));
    return demoTelescope(sessionId, this.session(sessionId)?.projectName ?? '', ask);
  }

  async preview(): Promise<string> { return 'about:blank'; }
  async speak(): Promise<ArrayBuffer> { throw new ApiError('missing', 'Sample data has no Mac voice.', 404, 'not_found'); }
  async playOnPhone(text: string): Promise<PlayStart | null> { return this.phoneSpeech.play(text); }
  async stopOnPhone(id: string): Promise<void> { this.phoneSpeech.stop(id); }
  async voices(): Promise<MacVoice[]> { return []; }

  /** Put a question or a plan on the lens (simulator scenes). */
  ask(kind: 'question' | 'plan'): void {
    const now = Date.now();
    const a: Approval = kind === 'question'
      ? { id: 'ap-q', sessionId: 's-hypernova', createdAt: now, expiresAt: now + 10 * MIN, kind: 'question', toolName: 'AskUserQuestion', summary: 'A question for you', input: {}, reason: null, canSession: false, plan: null,
          questions: [{ question: 'Where should the lens look for the Mac?', header: 'Discovery', multiSelect: false, options: [{ label: 'Tailscale name from pairing' }, { label: 'Ask the daemon for its address' }, { label: 'Both, Tailscale first' }] }] }
      : { id: 'ap-plan', sessionId: 's-hypernova', createdAt: now, expiresAt: now + 10 * MIN, kind: 'plan', toolName: 'ExitPlanMode', summary: 'Plan ready for review', input: {}, reason: null, canSession: false, questions: null,
          plan: '## Plan\n1. Add CORS to the phone API, with a preflight answer.\n2. Serve speech from `say` and screenshots from headless Chrome.\n3. Document both in PROTOCOL.md and test them against the lens.' };
    this.snapshot = { ...this.snapshot, approvals: [...this.snapshot.approvals, a], sessions: this.snapshot.sessions.map(s => (s.id === a.sessionId ? { ...s, state: 'needs_you', pendingApprovalIds: [a.id] } : s)) };
    this.emit({ t: 'approval', approval: a });
    this.emit({ t: 'change' });
  }

  /** Finish a session now (simulator scenes). */
  finish(sessionId: string): void { this.emit({ t: 'finished', sessionId }); }
}

type DemoDiff = { added: number; removed: number; hunks: Array<{ oldStart: number; oldLines: number; newStart: number; newLines: number; lines: string[] }> };

/** What hyperscribe's sample turn changed, as Claude Code's patches have it ("what changed?", "show diff"). */
const DEMO_DIFFS: Record<string, DemoDiff> = {
  'ExportSheet.kt': { added: 9, removed: 3, hunks: [
    { oldStart: 41, oldLines: 9, newStart: 41, newLines: 15, lines: [
      '     @Composable',
      '     fun ExportSheet(store: SettingsStore, onExport: (Uri) -> Unit) {',
      '-        val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->',
      '-            uri?.let(onExport)',
      '-        }',
      '+        val last by store.lastExportFolder.collectAsState(initial = null)',
      '+        val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->',
      '+            if (uri != null) {',
      '+                store.rememberExportFolder(uri)',
      '+                onExport(uri)',
      '+            }',
      '+        }',
      '+        // Opens where the last export went, so a second export is one tap.',
      '+        Button(onClick = { picker.launch(last) }) { Text(stringResource(R.string.export)) }',
      '     }',
    ] },
  ] },
  'SettingsStore.kt': { added: 6, removed: 1, hunks: [
    { oldStart: 18, oldLines: 5, newStart: 18, newLines: 10, lines: [
      '     private val folderKey = stringPreferencesKey("export_folder")',
      '-    private val oldKey = stringPreferencesKey("last_dir")',
      '+    val lastExportFolder: Flow<Uri?> = data.map { p ->',
      '+        (p[folderKey] ?: p[LEGACY_KEY])?.let(Uri::parse)',
      '+    }',
      '+',
      '+    suspend fun rememberExportFolder(uri: Uri) = edit { it[folderKey] = uri.toString() }',
      '+    private companion object { val LEGACY_KEY = stringPreferencesKey("last_dir") }',
    ] },
  ] },
  'ExportFolderTest.kt': { added: 11, removed: 0, hunks: [
    { oldStart: 0, oldLines: 0, newStart: 1, newLines: 11, lines: [
      '+class ExportFolderTest {',
      '+    @Test fun remembersTheLastFolder() = runTest {',
      '+        val store = SettingsStore(testDataStore())',
      '+        store.rememberExportFolder(Uri.parse("content://notes/Meetings"))',
      '+        assertEquals("content://notes/Meetings", store.lastExportFolder.first().toString())',
      '+    }',
      '+    @Test fun readsTheOldKey() = runTest {',
      '+        val store = SettingsStore(testDataStore("last_dir" to "content://old"))',
      '+        assertEquals("content://old", store.lastExportFolder.first().toString())',
      '+    }',
      '+}',
    ] },
  ] },
};

/** A light web app, drawn here so sample data needs no files: what "show me" would capture. */
export function demoScreenshot(): { url: string; width: number; height: number } {
  const w = 1280, h = 800;
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const x = c.getContext('2d')!;
  x.fillStyle = '#f6f5f2'; x.fillRect(0, 0, w, h);
  x.fillStyle = '#1b1814'; x.fillRect(0, 0, w, 72);
  x.fillStyle = '#7fffa0'; x.font = '600 28px system-ui, sans-serif'; x.fillText('Hyperscribe', 40, 46);
  x.fillStyle = '#b5b0a6'; x.font = '500 18px system-ui, sans-serif'; x.fillText('Recordings     Export     Settings', 860, 44);
  x.fillStyle = '#1b1814'; x.font = '600 44px system-ui, sans-serif'; x.fillText('Export transcripts', 80, 170);
  x.fillStyle = '#6b665e'; x.font = '500 22px system-ui, sans-serif'; x.fillText('Choose a folder once; Hyperscribe remembers it.', 80, 214);
  const card = (y: number, title: string, sub: string) => {
    x.fillStyle = '#ffffff'; x.strokeStyle = '#dedad2'; x.lineWidth = 2;
    x.beginPath(); x.roundRect(80, y, 720, 110, 18); x.fill(); x.stroke();
    x.fillStyle = '#1b1814'; x.font = '600 26px system-ui, sans-serif'; x.fillText(title, 112, y + 48);
    x.fillStyle = '#6b665e'; x.font = '500 19px system-ui, sans-serif'; x.fillText(sub, 112, y + 82);
  };
  card(270, 'Standup — 14 Oct', '12 min · 1,840 words');
  card(400, 'Design review', '38 min · 5,212 words');
  card(530, 'Call with Sam', '9 min · 1,102 words');
  x.fillStyle = '#1b1814'; x.beginPath(); x.roundRect(880, 270, 320, 64, 32); x.fill();
  x.fillStyle = '#7fffa0'; x.font = '600 24px system-ui, sans-serif'; x.fillText('Export to ~/Notes', 930, 311);
  x.fillStyle = '#6b665e'; x.font = '500 18px system-ui, sans-serif'; x.fillText('Last folder: ~/Notes/Meetings', 884, 370);
  return { url: c.toDataURL('image/png'), width: w, height: h };
}
