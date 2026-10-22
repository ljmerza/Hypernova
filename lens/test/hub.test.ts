// The Mac's hub while the Mac is away (asleep, lid shut, you've left the house): it keeps retrying
// and polling, and a try that ends like the last one is no news. Wired as main.ts wires it (hub →
// controller → lens output, with the clock's 20 s marks), the planets must come to rest and stop costing
// Bluetooth writes.
import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { LiveHub } from '../src/data/hub.ts';
import { LensController } from '../src/lens/controller.ts';
import { LensOutput, clockTicks, type FrameSource } from '../src/lens/output.ts';
import { DEFAULT_SETTINGS } from '../src/data/store.ts';
import type { VoiceOut } from '../src/data/voice.ts';
import type { Agent, Session, Snapshot } from '../src/core/types.ts';

const T0 = 1_800_000_000_000;
const BASE = 'https://mac.example.ts.net:47321';

/** The last thing the Mac said: one session at work, and the usage at 70%. */
function snapshot(activity = 'Bash: ./gradlew build'): Snapshot {
  const s: Session = {
    id: 'w', title: 'hypernova work', cwd: '/p/hypernova', projectName: 'hypernova', origin: 'terminal', state: 'working', model: null,
    permissionMode: 'default', createdAt: T0 - 3600_000, lastActivityAt: T0 - 30_000, lastText: null, activity, pendingApprovalIds: [], stoppedForAway: false,
  };
  return {
    mac: { id: 'm', name: 'Mac', daemonVersion: '0' },
    usage: { fiveHour: { pct: 70, resetsAt: T0 + 4_000_000 }, sevenDay: null, source: 'official', observedAt: T0 },
    sessions: [s], approvals: [], projects: [],
  };
}

/**
 * The daemon's socket. With `firstUpFor`, the first connection brings a snapshot and drops after
 * that long (the Mac went to sleep); every other one fails before it opens.
 */
function fakeSocket(snap: () => Snapshot, firstUpFor: number | null) {
  let made = 0;
  return class FakeSocket {
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: string }) => void) | null = null;
    onclose: ((e: { code: number }) => void) | null = null;
    onerror: (() => void) | null = null;
    constructor() {
      const up = made++ === 0 && firstUpFor !== null;
      setTimeout(() => {
        if (!up) { this.onerror?.(); this.onclose?.({ code: 1006 }); return; }
        this.onopen?.();
        this.onmessage?.({ data: JSON.stringify({ t: 'snapshot', snapshot: snap() }) });
        setTimeout(() => this.onclose?.({ code: 1006 }), firstUpFor!);
      }, 50);
    }
    send(): void {}
    close(): void {}
  };
}

/** main.ts in miniature, on mocked time; returns what reached the glasses and what the hub said. */
async function wire(o: { socket: ReturnType<typeof fakeSocket>; fetch: typeof fetch }) {
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = { WebSocket: g.WebSocket, fetch: g.fetch };
  g.WebSocket = o.socket;
  g.fetch = o.fetch;
  const frames: Array<{ at: number; still: boolean }> = [];
  const changes: number[] = [];
  const orrery: FrameSource = {
    canvas: null as unknown as HTMLCanvasElement,
    frame: (_v, now, still = false) => { frames.push({ at: now - T0, still }); return { png: new Uint8Array(1), hash: still ? 1 : now }; },
  };
  const hub = new LiveHub({ baseUrl: BASE, token: 'token', macName: 'Mac', macId: null, deviceId: null });
  const voice = { speaking: false, locked: false, onChange() {}, unlock() {}, stop() {}, blip() {}, speak: async () => null } as unknown as VoiceOut;
  const lens = new LensController({
    hub: () => hub, speech: () => null, voice, settings: () => DEFAULT_SETTINGS, updateSettings: () => {}, paired: () => true, showOnPhone: () => {}, shotReady: () => {},
  });
  const out = new LensOutput({ idle: true, show() {}, picture() {} }, () => lens.view(), orrery);
  hub.subscribe(e => { if (e.t === 'change') changes.push(Date.now() - T0); lens.hubEvent(e); });
  lens.onChange = () => out.invalidate();
  const stopClock = clockTicks(() => out.invalidate({ news: false }));
  out.invalidate();
  hub.start();
  /** Time passes in 100 ms steps; promises (fetch, its error) settle between them. */
  const run = async (ms: number) => {
    for (let t = 0; t < ms; t += 100) {
      mock.timers.tick(100);
      await new Promise(r => setImmediate(r));
    }
  };
  const done = () => { hub.stop(); stopClock(); lens.dispose(); g.WebSocket = saved.WebSocket; g.fetch = saved.fetch; };
  return { hub, frames, changes, run, done };
}

test('the Mac unreachable: retries say nothing new, and the planets come to rest', async () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: T0 });
  const w = await wire({
    socket: fakeSocket(() => snapshot(), 5000),
    fetch: (async () => { throw new TypeError('Failed to fetch'); }) as typeof fetch,
  });
  try {
    await w.run(200_000);
    assert.equal(w.hub.status, 'offline');
    assert.ok(w.hub.snapshot, 'the last snapshot is kept');
    // Connecting, live, offline, and why: then a dozen retries that end the same way, silently.
    assert.ok(w.changes.length <= 5, `changes: ${w.changes.join(', ')}`);
    assert.ok(w.changes.every(t => t < 20_000), `changes: ${w.changes.join(', ')}`);
    const late = w.frames.filter(f => f.at > 100_000);
    // Only the working planet's turn moves while the Mac is there (the sun no longer pulses).
    assert.ok(w.frames.length > 10, `frames while it moves: ${w.frames.length}`);
    assert.ok(late.length <= 1, `frames after 100 s: ${late.length}`);
    // Nothing moves once the planets are at rest (the sun no longer pulses), so the last frame drawn
    // is already the resting pose; no frame is flagged still unless something was still moving at 90 s.
    assert.ok(w.frames.at(-1)!.at < 100_000, 'no frame after the planets came to rest');
  } finally {
    w.done();
    mock.timers.reset();
  }
});

test('polling the same snapshot every 4 s says nothing; a real change does', async () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: T0 });
  let snap = snapshot();
  let polls = 0;
  const w = await wire({
    // The socket never gets through (a proxy without WebSockets); plain HTTP does.
    socket: fakeSocket(() => snap, null),
    fetch: (async (url: string) => {
      assert.equal(String(url), `${BASE}/v1/snapshot`);
      polls++;
      return new Response(JSON.stringify(snap), { status: 200, headers: { 'content-type': 'application/json' } });
    }) as unknown as typeof fetch,
  });
  try {
    await w.run(200_000);
    assert.equal(w.hub.status, 'polling');
    assert.ok(polls > 40, `polls: ${polls}`);
    assert.ok(w.changes.length <= 3 && w.changes.every(t => t < 20_000), `changes: ${w.changes.join(', ')}`);
    assert.ok(w.frames.filter(f => f.at > 100_000).length <= 1, 'the planets came to rest');
    const before = w.frames.length, seen = w.changes.length;
    snap = snapshot('Bash: npm test');               // Claude moved on
    await w.run(10_000);
    assert.equal(w.changes.length, seen + 1, 'one change for one new snapshot');
    assert.ok(w.frames.length > before + 3 && w.frames.at(-1)?.still === false, 'news: the planets move again');
  } finally {
    w.done();
    mock.timers.reset();
  }
});

test('a result reads the last turn only, from the last prompt even when a slash command came after it', async () => {
  const asked: string[] = [];
  const prompt = { id: 'u2', kind: 'user', ts: T0 - 9000, text: 'fix the export' };
  const reply = { id: 'a2', kind: 'assistant', ts: T0 - 5000, text: 'Fixed.', streaming: false };
  const cost = { id: 'u3', kind: 'user', ts: T0 - 1000, text: '/cost', command: { name: 'cost', args: '', kind: 'local' } };
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = g.fetch;
  g.fetch = async (url: string) => {
    asked.push(String(url).slice(BASE.length));
    // Like the daemon: turn=last starts at the last user event, a slash command too.
    const events = String(url).includes('turn=last') ? [cost] : [prompt, reply, cost];
    return new Response(JSON.stringify({ events }), { status: 200 });
  };
  try {
    const hub = new LiveHub({ baseUrl: BASE, token: 'token', macName: 'Mac', macId: null, deviceId: null });
    const events = await hub.loadEvents('s1');
    assert.deepEqual(asked, ['/v1/sessions/s1/events?limit=120&output=8192&turn=last', '/v1/sessions/s1/events?limit=120&output=8192']);
    assert.deepEqual(events.map(e => e.id), ['u2', 'a2', 'u3']);
  } finally {
    g.fetch = saved;
  }
});

test('a new load drops the turns before it that an earlier load left behind', async () => {
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = g.fetch;
  let turn = 1;
  g.fetch = async () => {
    const events = turn === 1
      ? [{ id: 'u1', kind: 'user', ts: T0 - 60_000, text: 'first' }, { id: 'a1', kind: 'assistant', ts: T0 - 50_000, text: 'One.', streaming: false }]
      : [{ id: 'u2', kind: 'user', ts: T0 - 9000, text: 'second' }, { id: 'a2', kind: 'assistant', ts: T0 - 5000, text: 'Two.', streaming: false }];
    return new Response(JSON.stringify({ events }), { status: 200 });
  };
  try {
    const hub = new LiveHub({ baseUrl: BASE, token: 'token', macName: 'Mac', macId: null, deviceId: null });
    await hub.loadEvents('s1');
    turn = 2;
    assert.deepEqual((await hub.loadEvents('s1')).map(e => e.id), ['u2', 'a2']);
  } finally {
    g.fetch = saved;
  }
});

test('a turn that ends while its agents work isn\'t finished; the job is, once, after the last one ends', () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: T0 });
  const hub = new LiveHub({ baseUrl: BASE, token: 'token', macName: 'Mac', macId: null, deviceId: null });
  let finished = 0;
  hub.subscribe(e => { if (e.t === 'finished') finished++; });
  const send = (m: object) => (hub as unknown as { message(data: string): void }).message(JSON.stringify(m));
  const session = (state: Session['state'], agentsRunning: number): Session => ({ ...snapshot().sessions[0], state, agentsRunning });
  /** The Mac says the session is in [state] with [agents] at work, then [ms] go by. */
  const step = (state: Session['state'], agents: number, ms = 0) => { send({ t: 'session', session: session(state, agents) }); mock.timers.tick(ms); };
  try {
    send({ t: 'snapshot', snapshot: { ...snapshot(), sessions: [session('working', 0)] } });
    step('idle', 2, 2000);                          // the turn ends with two agents at work
    step('idle', 1, 1000);                          // one reports, in a turn of its own
    step('working', 1, 1000);
    step('idle', 1, 10_000);
    assert.equal(finished, 0, 'not while agents work');
    step('idle', 0, 1500);                          // the last one ends; its report starts a turn
    step('working', 0, 3000);
    assert.equal(finished, 0, 'not before that turn ends');
    step('idle', 0, 10_000);
    assert.equal(finished, 1, 'once, when it does');
    // Agents that end with no turn after them (stopped, none reporting): a few seconds later.
    step('working', 0, 1000);
    step('idle', 1, 1000);
    step('idle', 0, 4000);
    assert.equal(finished, 1);
    mock.timers.tick(2000);
    assert.equal(finished, 2);
    mock.timers.tick(10_000);
    assert.equal(finished, 2);
    // A turn with no agents: at once.
    step('working', 0, 1000);
    step('idle', 0);
    assert.equal(finished, 3);
  } finally {
    hub.stop();
    mock.timers.reset();
  }
});

test('a lens that opens after the turn ended, with one agent still at work: held from the first snapshot, finished once', () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: T0 });
  const hub = new LiveHub({ baseUrl: BASE, token: 'token', macName: 'Mac', macId: null, deviceId: null });
  let finished = 0;
  hub.subscribe(e => { if (e.t === 'finished') finished++; });
  const send = (m: object) => (hub as unknown as { message(data: string): void }).message(JSON.stringify(m));
  const session = (state: Session['state'], agentsRunning: number, id = 'w'): Session => ({ ...snapshot().sessions[0], id, state, agentsRunning });
  const step = (state: Session['state'], agents: number, ms = 0) => { send({ t: 'session', session: session(state, agents) }); mock.timers.tick(ms); };
  try {
    send({ t: 'snapshot', snapshot: { ...snapshot(), sessions: [session('idle', 1)] } });
    assert.ok(hub.held.has('w'), 'the job goes on');
    step('idle', 0, 1500);                          // the agent ends; its report starts a turn
    assert.ok(hub.held.has('w'), 'not done while its report is due');
    step('working', 0, 6000);
    assert.equal(finished, 0);
    step('idle', 0);
    assert.equal(finished, 1, 'once, when that turn ends');
    assert.ok(!hub.held.has('w'));
    // A reconnect that brings a session begun while the lens was away, in the same state: held too.
    send({ t: 'snapshot', snapshot: { ...snapshot(), sessions: [session('idle', 0), session('idle', 1, 'n')] } });
    assert.deepEqual([...hub.held], ['n']);
  } finally {
    hub.stop();
    mock.timers.reset();
  }
});

test('agents: asked for once per session on screen, kept up to date by the socket, asked afresh after a reconnect', async () => {
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = g.fetch;
  let asked: string[] = [];
  let answer: () => Response = () => new Response('{}');
  g.fetch = async (url: string) => { asked.push(String(url).slice(BASE.length)); return answer(); };
  const agent = (id: string, status: Agent['status'], extra: Partial<Agent> = {}): Agent => ({
    id, sessionId: 's1', parentAgentId: null, workflowId: null, label: id, type: 'Explore', status, activity: null, startedAt: T0, endedAt: null, lastActivityAt: T0, result: null, ...extra,
  });
  const hubs: LiveHub[] = [];
  const make = () => { const h = new LiveHub({ baseUrl: BASE, token: 'token', macName: 'Mac', macId: null, deviceId: null }); hubs.push(h); return h; };
  try {
    const hub = make();
    const send = (m: object) => (hub as unknown as { message(data: string): void }).message(JSON.stringify(m));
    const heard: string[] = [];
    hub.subscribe(e => { if (e.t === 'agent') heard.push(`${e.agent.id}:${e.agent.status}`); });
    // Another session's agent, never asked for: not kept.
    send({ t: 'agent', agent: agent('a9', 'running', { sessionId: 's2' }) });
    assert.equal(hub.agents('s2'), null);
    // A step's earlier attempt doesn't count.
    answer = () => new Response(JSON.stringify({ agents: [agent('a1', 'running'), agent('a0', 'done', { superseded: true })], workflows: [] }), { status: 200 });
    assert.deepEqual((await hub.loadAgents('s1')).map(a => a.id), ['a1']);
    assert.deepEqual(asked, ['/v1/sessions/s1/agents']);
    send({ t: 'agent', agent: agent('a2', 'running') });
    send({ t: 'agent', agent: agent('a1', 'done', { result: 'Found it.' }) });
    assert.deepEqual(hub.agents('s1')!.map(a => `${a.id}:${a.status}`).sort(), ['a1:done', 'a2:running']);
    assert.deepEqual(heard, ['a2:running', 'a1:done']);
    // A reconnect: what the socket missed is asked for afresh.
    send({ t: 'snapshot', snapshot: snapshot() });
    assert.equal(hub.agents('s1'), null);
    // Agents at work with the main agent idle, seen after the turn ended: the job goes on.
    send({ t: 'session', session: { ...snapshot().sessions[0], state: 'idle', agentsRunning: 1 } });
    assert.ok(hub.held.has('w'));
    // A Mac from before agents: asked once, then the session's own count is all there is.
    asked = [];
    answer = () => new Response(JSON.stringify({ error: { code: 'not_found', message: 'Unknown endpoint.' } }), { status: 404 });
    const old = make();
    assert.deepEqual(await old.loadAgents('s1'), []);
    assert.deepEqual(await old.loadAgents('s2'), []);
    assert.deepEqual(asked, ['/v1/sessions/s1/agents']);
  } finally {
    for (const h of hubs) h.stop();
    g.fetch = saved;
  }
});

test('the Mac\'s battery: in the snapshot, then a change only when it really changes', () => {
  const hub = new LiveHub({ baseUrl: BASE, token: 'token', macName: 'Mac', macId: null, deviceId: null });
  let changes = 0;
  hub.subscribe(e => { if (e.t === 'change') changes++; });
  const send = (m: object) => (hub as unknown as { message(data: string): void }).message(JSON.stringify(m));
  const battery = (percent: number, charging = false) => ({ percent, charging, onAC: charging, minutesLeft: charging ? null : 190 });
  try {
    send({ t: 'snapshot', snapshot: { ...snapshot(), mac: { ...snapshot().mac, battery: battery(62) } } });
    assert.deepEqual(hub.snapshot?.mac.battery, battery(62));
    changes = 0;
    send({ t: 'battery', battery: battery(61) });
    assert.deepEqual([hub.snapshot?.mac.battery?.percent, changes], [61, 1]);
    send({ t: 'battery', battery: battery(61) });   // the same again: nothing to tell
    assert.equal(changes, 1);
    send({ t: 'battery', battery: battery(61, true) });
    assert.deepEqual([hub.snapshot?.mac.battery?.charging, changes], [true, 2]);
    // The rest of the snapshot stays as it was.
    assert.equal(hub.snapshot?.sessions.length, 1);
    assert.equal(hub.snapshot?.mac.name, 'Mac');
  } finally {
    hub.stop();
  }
});
