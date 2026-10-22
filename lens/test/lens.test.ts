// The lens: every screen fits the firmware's limits, and the gestures do what the README says.
import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { buildPage, CARD_TEXT_W, cardRoom, H, TALK_TEXT_W, W, layoutKey, type LensPage, type LensView } from '../src/lens/pages.ts';
import { fitRows, MORE_W, windowLines } from '../src/core/scroll.ts';
import { textLines, wrapAll } from '../src/core/text.ts';
import { BOX_BYTES, LINE, fitBytes, utf8Bytes } from '../src/core/font.ts';
import { ago, clockTime } from '../src/core/format.ts';
import { clean, gapJoin, nativeWidth, partsOf } from '../src/core/font.ts';
import { LensController, MENU, devServerPort, spokenUrl, type Services } from '../src/lens/controller.ts';
import { LensOutput, clockTicks, type FrameSource } from '../src/lens/output.ts';
import { still } from '../src/lens/gray.ts';
import type { HubEvent, HubLike } from '../src/data/hub.ts';
import { DemoSpeech } from '../src/data/speech.ts';
import { DEFAULT_SETTINGS, type Settings } from '../src/data/store.ts';
import type { VoiceOut } from '../src/data/voice.ts';
import { ApiError } from '../src/data/api.ts';
import type { Agent, Approval, ApprovalAnswer, GlassesBattery, HnEvent, Session, Shot, Snapshot } from '../src/core/types.ts';

// ------------------------------------------------------------------ firmware limits

/** What the G2 accepts: ≤ 8 text boxes (ours plus the gesture box), names ≤ 16 bytes and unique, everything on the
 * 576 x 288 lens, image tiles ≤ 288 x 144, and no box with more lines or wider lines than it holds (else a scrollbar). */
function checkPage(p: LensPage, label: string): void {
  assert.ok(p.boxes.length + 1 <= 8, `${label}: ${p.boxes.length + 1} text boxes`);
  assert.ok(p.boxes.length + 1 + p.images.length <= 12, `${label}: too many containers`);
  const names = [...p.boxes.map(b => b.name), ...p.images.map(i => i.name), 'input'];
  assert.equal(new Set(names).size, names.length, `${label}: duplicate names`);
  const ids = [...p.boxes.map(b => b.id), ...p.images.map(i => i.id), 1];
  assert.equal(new Set(ids).size, ids.length, `${label}: duplicate ids`);
  for (const b of p.boxes) {
    assert.ok(Buffer.byteLength(b.name) <= 16, `${label}: name ${b.name}`);
    assert.ok(b.x >= 0 && b.y >= 0 && b.x + b.w <= W && b.y + b.h <= H, `${label}: ${b.name} off the lens (${b.x},${b.y} ${b.w}x${b.h})`);
    const lines = b.content.split('\n');
    const room = Math.floor((b.h - 2 * b.border - 2 * b.pad) / LINE);
    assert.ok(lines.length <= Math.max(1, room), `${label}: ${b.name} has ${lines.length} lines in room for ${room}`);
    for (const l of lines) assert.ok(nativeWidth(l) <= b.w - 2 * b.border - 2 * b.pad, `${label}: ${b.name} line too wide: "${l}" ${nativeWidth(l)} > ${b.w}`);
    // The firmware refuses a box over about 999 bytes (font.ts BOX_BYTES keeps a little under it).
    assert.ok(Buffer.byteLength(b.content) <= BOX_BYTES, `${label}: ${b.name} has ${Buffer.byteLength(b.content)} bytes`);
  }
  for (const i of p.images) assert.ok(i.w <= 288 && i.h <= 144 && i.x + i.w <= W && i.y + i.h <= H, `${label}: image ${i.name}`);
}

const long = 'make the export button remember the last folder it used, and run every test in the project, then tell me which ones were slow and why they were slow';
const orrery = { sun: { pct: 42, limit: false }, planets: [] };

test('every kind of screen fits the lens', () => {
  const views: Array<[string, LensView]> = [
    ['message', { kind: 'message', title: 'Pair on your phone', sub: 'Open Hypernova Lens in the Even App and scan the code on your Mac.' }],
    ['glance', { kind: 'glance', left: 'hypernova/', right: ['42%', 'resets 2h 11m'], empty: '', hint: ['tap to answer', 'hold to talk'], orrery,
      rows: [1, 2, 3, 4].map(i => ({ project: `project-with-a-long-name-${i}`, detail: long, right: '1h 12m', state: 'working' as const, focused: i === 1 })) }],
    ['glance-empty', { kind: 'glance', left: 'hypernova/', right: '', empty: 'All quiet on MacBook Air.', hint: 'hold to start a session', rows: [], orrery }],
    // The longest time there can be, with four long rows and "+ New session": still one box each, and within the lens.
    ['glance-new', { kind: 'glance', left: 'hypernova/', clock: '12:59 PM', right: ['week limit', 'back 4h 59m'], empty: '', hint: `tap to watch · hold to talk to ${long}`, orrery,
      rows: [1, 2, 3, 4].map(i => ({ project: `project-with-a-long-name-${i}`, detail: long, right: '1h 12m', state: 'working' as const, focused: i === 1, tag: '12 agents · 3 queued' })),
      newRow: { label: 'New session', focused: false } }],
    ['card', { kind: 'card', head: ['NEEDS YOU', 'hypernova'], right: '2m', lead: 'wants to run', body: Array(9).fill(long), facts: ['a reason that is also rather long and keeps going'], warn: 'careful: force push can’t be undone', factsRows: 2, hint: ['tap allow', 'double tap deny'], orrery }],
    ['card-nofacts', { kind: 'card', head: ['DIFF', 'Solar.kt'], right: '+2 -1', lead: '', body: Array(9).fill('x'), facts: [], hint: ['swipe scroll', 'double tap back'], orrery }],
    // A reply's window: as many lines as the card holds over its facts, the last marked "more below".
    ['card-scroll', { kind: 'card', head: ['DONE', 'hypernova'], right: '', lead: '', facts: ['3 files changed', '112 tests passed', 'build passed', '1 workflow', 'took 4m 12s'], factsRows: 2,
      body: windowLines(textLines(`${long} ${long} ${long} ${long}`, CARD_TEXT_W - MORE_W), 2, cardRoom(false, 2), CARD_TEXT_W), hint: ['swipe to read', 'tap read aloud'], orrery }],
    ['choice', { kind: 'choice', head: ['QUESTION', 'hypernova'], right: '1/2', prompt: long, options: Array(7).fill(0).map((_, i) => ({ label: `${long} ${i}`, mark: i % 2 ? '●' : '○' })), cursor: 5, hint: ['swipe choose', 'tap pick'], orrery }],
    ['talk', { kind: 'talk', head: 'LISTENING', right: 'to hypernova', text: `${long} ${long} ${long}`, live: true, hint: 'let go when done', orrery }],
    ['talk-review', { kind: 'talk', head: 'TO hypernova', right: 'swipe ↑↓', text: `${long} ${long} ${long}`, live: false, hint: ['tap send', 'double tap discard', 'hold add'], orrery }],
    ['telescope', { kind: 'telescope', head: ['TELESCOPE', 'project-with-a-long-name', `emulator ${long}`], right: ['just now', 'watching'], status: '', hint: '', picture: true, loading: false, key: 'x', zoom: 2, crisp: false }],
    ['telescope-missing', { kind: 'telescope', head: ['TELESCOPE', 'hypernova', 'web'], right: 'tap to zoom', status: `${long} ${long}`, hint: ['tap open on phone', 'double tap back'], picture: false, loading: false, key: '', zoom: null, crisp: false }],
    ['telescope-loading', { kind: 'telescope', head: ['TELESCOPE', 'hypernova'], right: '', status: 'Pointing the telescope…', hint: 'double tap back', picture: false, loading: true, key: '', zoom: null, crisp: false }],
    ['live', { kind: 'live', head: ['WORKING', 'project-with-a-long-name'], right: ['3 queued', '1h 12m'], now: [`› Bash: ${long}`, `• survey:parser-with-a-long-name   Read: ${long}`, '• another'], more: 12,
      output: Array(9).fill(long), hint: ['tap pause', 'swipe scroll', 'hold talk'], orrery }],
    ['live-empty', { kind: 'live', head: ['AGENTS', 'x'], right: 'now', now: [], more: 0, output: [], hint: ['paused', 'tap resume', 'swipe scroll'], orrery }],
  ];
  for (const [label, v] of views) checkPage(buildPage(v), label);
  // The window's lines go out whole: the mark fits beside them, and the card cuts nothing.
  const scrolled = buildPage(views.find(([l]) => l === 'card-scroll')![1]).boxes.find(b => b.name === 'body')!.content.split('\n');
  assert.equal(scrolled.length, cardRoom(false, 2));
  assert.ok(scrolled.at(-1)!.endsWith('↓') && !scrolled.some(l => l.includes('…')), scrolled.join(' | '));
  // A result card's facts take a second line rather than lose the last ones, broken between facts;
  // what can't fit in two still ends in an ellipsis, and the page still fits.
  const facts = ['3 files changed', '112 tests passed', 'build passed', '1 workflow', 'took 4m 12s'];
  const card = (f: string[]) => buildPage({ kind: 'card', head: ['DONE', 'hypernova'], right: '', lead: '', body: ['Comets are in.'], facts: f, factsRows: 2, hint: ['swipe read', 'tap read aloud'], orrery });
  const lines = card(facts).boxes.find(b => b.name === 'facts')!.content.split('\n');
  assert.equal(lines.length, 2);
  // A sentence of phrases, broken between two of them, never inside one.
  assert.equal(lines.join(', '), facts.join(', '));
  assert.ok(lines.every(l => !l.endsWith(',')), lines.join(' | '));
  checkPage(card([...facts, long, long]), 'card-many-facts');
  assert.match(card([...facts, long, long]).boxes.find(b => b.name === 'facts')!.content, /…$/);
  // A needs card: Claude's reason, and what can't be undone on a line of its own under it.
  const needs = buildPage(views.find(([l]) => l === 'card')![1]).boxes.find(b => b.name === 'facts')!.content.split('\n');
  assert.equal(needs.length, 2);
  assert.match(needs[0], /^a reason that is also rather long/);
  assert.equal(needs[1], 'careful: force push can’t be undone');
});

// ------------------------------------------------------------------ the controller

const NOW = Date.now();
function makeHub() {
  const s = (id: string, projectName: string, state: Session['state'], extra: Partial<Session> = {}): Session => ({
    id, title: `${projectName} work`, cwd: `/p/${projectName}`, projectName, origin: 'terminal', state, model: null, permissionMode: 'default',
    createdAt: NOW - 3600_000, lastActivityAt: NOW - 60_000, lastText: 'Done.', activity: null, pendingApprovalIds: [], stoppedForAway: false, ...extra,
  });
  const ap: Approval = { id: 'ap1', sessionId: 'n', createdAt: NOW - 60_000, expiresAt: NOW + 600_000, kind: 'tool', toolName: 'Bash', summary: 'Bash: git push origin main',
    input: { command: 'git push origin main' }, reason: null, canSession: false, questions: null, plan: null };
  const calls: string[] = [];
  const listeners = new Set<(e: HubEvent) => void>();
  const events: HnEvent[] = [
    { id: 'u', kind: 'user', ts: NOW - 300_000, text: 'fix it' },
    { id: 'a', kind: 'assistant', ts: NOW - 1000, text: 'Fixed the pairing test. It waits for the camera now.', streaming: false },
    { id: 'r', kind: 'result', ts: NOW, ok: true, durationMs: 300_000, costUsd: null, turns: 3, error: null },
  ];
  const hub: HubLike & {
    calls: string[]; snapshot: Snapshot; emit(e: HubEvent): void; promptReply: null | { queued: boolean; session?: Session; forked?: boolean };
    /** Each session's turn (else the default one) and agents; what was loaded, and how. */
    eventsBy: Map<string, HnEvent[]>; agentsBy: Map<string, Agent[]>; loads: string[]; status: HubLike['status'];
    /** Sessions whose copy of the turn the hub dropped (a reconnect does), until it's loaded again. */
    dropped: Set<string>;
  } = {
    calls,
    eventsBy: new Map(), agentsBy: new Map(), loads: [], dropped: new Set(),
    snapshot: {
      mac: { id: 'm', name: 'Mac', daemonVersion: '0' }, usage: { fiveHour: { pct: 42, resetsAt: NOW + 7200_000 }, sevenDay: null, source: 'official', observedAt: NOW },
      sessions: [s('n', '0stems', 'needs_you', { pendingApprovalIds: ['ap1'] }), s('w', 'hypernova', 'working', { activity: 'Bash: ./gradlew build' }), s('i', 'hyperscribe', 'idle')],
      approvals: [ap],
      projects: [{ path: '/p/hyperscribe', name: 'hyperscribe', lastActivityAt: NOW, sessionCount: 1 }, { path: '/p/0stems', name: '0stems', lastActivityAt: NOW - 1, sessionCount: 1 }],
    },
    status: 'live', problem: null, macName: 'Mac',
    events: (id: string) => (hub.dropped.has(id) ? null : hub.eventsBy.get(id) ?? events),
    loadEvents: async (id: string) => { hub.loads.push(`events ${id}`); hub.dropped.delete(id); return hub.eventsBy.get(id) ?? events; },
    agents: (id: string) => hub.agentsBy.get(id) ?? null,
    loadAgents: async (id: string) => { hub.loads.push(`agents ${id}`); const list = hub.agentsBy.get(id) ?? []; hub.agentsBy.set(id, list); return list; },
    // Like the daemon: every client hears the approval is resolved before the answer's response.
    answer: async (id: string, a: ApprovalAnswer) => {
      calls.push(`answer ${id} ${JSON.stringify(a)}`);
      hub.snapshot = { ...hub.snapshot, approvals: hub.snapshot.approvals.filter(x => x.id !== id) };
      hub.emit({ t: 'change' });
    },
    prompt: async (id: string, text: string) => { calls.push(`prompt ${id} ${text}`); return hub.promptReply ?? { queued: false }; },
    promptReply: null as null | { queued: boolean; session?: Session; forked?: boolean },
    newSession: async (path: string, text: string) => { calls.push(`new ${path} ${text}`); return null; },
    interrupt: async (id: string) => { calls.push(`interrupt ${id}`); },
    menu: async () => {},
    screenshot: async () => { throw new Error('no'); },
    telescope: async () => { throw new Error('no'); },
    preview: async () => '',
    speak: async () => new ArrayBuffer(0),
    voices: async () => [],
   
    subscribe: (fn: (e: HubEvent) => void) => { listeners.add(fn); return () => listeners.delete(fn); },
    emit: (e: HubEvent) => { for (const fn of listeners) fn(e); },
  };
  return hub;
}

function setup(lines: string[] = []) {
  const hub = makeHub();
  const speech = new DemoSpeech(lines);
  const spoken: string[] = [];
  const voice = { speaking: false, locked: false, onChange() {}, unlock() {}, stop() {}, blip() {}, speak: async (t: string) => { spoken.push(t); return 'mac'; } } as unknown as VoiceOut;
  let settings: Settings = { ...DEFAULT_SETTINGS };
  /** The glasses' battery, as the Even App would report it (none in a browser). */
  const glasses: { battery: GlassesBattery | null } = { battery: null };
  const services: Services = {
    hub: () => hub, speech: () => speech, voice, settings: () => settings, updateSettings: p => { settings = { ...settings, ...p }; },
    paired: () => true, showOnPhone: () => {}, shotReady: () => {}, glassesBattery: () => glasses.battery,
  };
  const lens = new LensController(services);
  hub.subscribe(e => lens.hubEvent(e));
  return { hub, lens, spoken, speech, settings: () => settings, glasses };
}

const view = (lens: LensController) => lens.view();
const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
const live = (lens: LensController) => { const v = lens.view(); assert.equal(v.kind, 'live', `a live view, not ${v.kind} ${'head' in v ? v.head : ''}`); return v as Extract<LensView, { kind: 'live' }>; };
const card = (lens: LensController) => { const v = lens.view(); assert.equal(v.kind, 'card', `a card, not ${v.kind}`); return v as Extract<LensView, { kind: 'card' }>; };
/** What a card's window shows, as one line (without the mark that says more follows). */
const shownText = (v: LensView) => (v.kind === 'card' ? v.body.join(' ').replace(/↓/g, '').replace(/\s+/g, ' ').trim() : '');
const agent = (id: string, label: string, status: Agent['status'], extra: Partial<Agent> = {}): Agent => ({
  id, sessionId: 'w', parentAgentId: null, workflowId: null, label, type: 'Explore', status, activity: null, startedAt: NOW - 120_000, endedAt: null,
  lastActivityAt: NOW, result: null, ...extra,
});
/** The session `id` changes as the Mac would say it. */
function patch(hub: ReturnType<typeof makeHub>, id: string, p: Partial<Session>): void {
  hub.snapshot = { ...hub.snapshot, sessions: hub.snapshot.sessions.map(x => (x.id === id ? { ...x, ...p } : x)) };
  hub.emit({ t: 'change' });
}
function say(hub: ReturnType<typeof makeHub>, id: string, e: HnEvent): void {
  hub.eventsBy.set(id, [...(hub.eventsBy.get(id) ?? []), e]);
  hub.emit({ t: 'event', sessionId: id, event: e });
}

test('the glance focuses what needs you; tap opens it; a tap right away is ignored; then it allows', async () => {
  const { hub, lens } = setup();
  const g = view(lens);
  assert.equal(g.kind, 'glance');
  if (g.kind !== 'glance') return;
  assert.equal(g.rows[0].project, '0stems');
  assert.equal(g.rows[0].focused, true);
  lens.tap();
  const n = view(lens);
  assert.deepEqual(n.kind === 'card' && n.head, ['NEEDS YOU', '0stems']);
  assert.deepEqual(n.kind === 'card' && n.body, ['git push origin main']);
  lens.tap();                                     // too soon: meant for the glance
  assert.deepEqual(hub.calls, []);
  await wait(750);
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"allow"}']);
  assert.equal(view(lens).kind, 'glance');
  lens.dispose();
});

test('double tap on a needs card denies', async () => {
  const { hub, lens } = setup();
  lens.tap();
  await wait(750);
  lens.doubleTap();
  await wait(5);
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"deny"}']);
  lens.dispose();
});

test('a finished turn shows its reply as one text that scrolls over its facts, never pages; double tap goes back', async () => {
  const { hub, lens } = setup();
  const reply = [
    'Fixed the pairing test: it waits for the camera now, instead of sleeping for a second and hoping.',
    'What changed:\n- **PairScreen.kt** asks for the camera first and shows the code once it has a frame.\n- **PairTest.kt** waits for that frame, so it no longer fails on a slow emulator.',
    'Twelve tests pass, on the emulator and on the Pixel, three times in a row, so the old flake looks gone for good.',
    'I left the QR parser alone; it was right all along, and its own tests cover the odd links.',
  ].join('\n\n');
  hub.eventsBy.set('i', [
    { id: 'u', kind: 'user', ts: NOW - 300_000, text: 'fix it' },
    { id: 'a', kind: 'assistant', ts: NOW - 1000, text: reply, streaming: false },
    { id: 'r', kind: 'result', ts: NOW, ok: true, durationMs: 300_000, costUsd: null, turns: 3, error: null },
  ]);
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  const r = card(lens);
  assert.deepEqual(r.head, ['DONE', 'hyperscribe']);
  assert.equal(r.right, '', 'no page counter');
  assert.deepEqual(r.facts, ['took 5m']);
  assert.match(r.body[0], /^Fixed the pairing test: it waits/);
  assert.ok(r.body.at(-1)!.endsWith('↓'), 'more under the window');
  assert.deepEqual(r.hint, ['swipe read', 'tap read aloud']);
  const first = buildPage(r);
  // A swipe reads on by a window less a line: the last line in view is now the first. The card
  // keeps its boxes, so it's one text update.
  lens.down();
  const r2 = card(lens);
  assert.deepEqual(r2.head, ['DONE', 'hyperscribe']);
  assert.equal(r2.body[0], r.body.at(-1)!.replace(/\s*↓$/, ''));
  assert.equal(layoutKey(buildPage(r2)), layoutKey(first));
  // On to the end: nothing more, and the hint says what's left to do.
  for (let i = 0; i < 5; i++) lens.down();
  const end = card(lens);
  assert.match(shownText(end), /its own tests cover the odd links\.$/);
  assert.ok(!end.body.some(l => l.endsWith('↓')));
  assert.deepEqual(end.hint, ['tap read aloud', 'double tap back']);
  lens.up();
  assert.ok(card(lens).body.at(-1)!.endsWith('↓'), 'back up: more under it again');
  lens.doubleTap();
  assert.equal(view(lens).kind, 'glance');
  // Opened again, it starts at its top.
  lens.open('i');
  assert.deepEqual(card(lens).body, r.body);
  lens.dispose();
});

test('hold, speak, let go: a routed draft; tap sends it', async () => {
  const { hub, lens } = setup(['in hyperscribe, fix the export']);
  await lens.hold();
  await wait(400);
  const live = view(lens);
  assert.deepEqual(live.kind === 'talk' && live.head, ['LISTENING']);
  await lens.release();
  const r = view(lens);
  assert.deepEqual(r.kind === 'talk' && r.head, ['TO', 'hyperscribe']);
  assert.equal(r.kind === 'talk' && r.text, 'fix the export');
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['prompt i fix the export']);
  const done = view(lens);
  assert.deepEqual(done.kind === 'talk' && done.head, ['SENT', 'hyperscribe']);
  lens.dispose();
});

test('swiping a draft sends it somewhere else; double tap discards', async () => {
  const { hub, lens } = setup(['run the tests']);
  // On the glance a hold talks to what needs you; a swipe switches to the other target, a new
  // session in Nebula, then on to the other sessions.
  await lens.hold();
  await lens.release();
  const before = view(lens);
  assert.deepEqual(before.kind === 'talk' && before.head, ['TO', '0stems', 'NEEDS YOU']);
  lens.down();
  const after = view(lens);
  assert.deepEqual(after.kind === 'talk' && after.head, ['NEW', 'Nebula/run-tests']);
  lens.down();
  const third = view(lens);
  assert.deepEqual(third.kind === 'talk' && third.head, ['TO', 'hypernova']);
  lens.doubleTap();
  assert.equal(view(lens).kind, 'glance');
  assert.deepEqual(hub.calls, []);
  lens.dispose();
});

test('voice words act without a tap', async () => {
  const { hub, lens, spoken } = setup(['approve', 'approve', 'status', 'stop']);
  // On the glance "approve" brings up what it would allow, to read first.
  await lens.hold();
  await lens.release();
  await wait(5);
  assert.equal(hub.calls.length, 0);
  const card = view(lens);
  assert.deepEqual(card.kind === 'card' && card.head, ['NEEDS YOU', '0stems']);
  // On the card, it answers that card.
  await lens.hold();
  await lens.release();
  await wait(5);
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"allow"}']);
  await lens.hold();
  await lens.release();
  assert.match(spoken[0], /hypernova is running \.\/gradlew build\./);
  assert.match(spoken[0], /Usage is at 42 percent/);
  lens.down();                                     // focus the working session, then "stop"
  await lens.hold();
  await lens.release();
  await wait(5);
  assert.ok(hub.calls.some(c => c.startsWith('interrupt ')));
  lens.dispose();
});

test('"new session in …" starts one', async () => {
  const { hub, lens } = setup(['new session in zero stems: write the release notes send it']);
  await lens.hold();
  await lens.release();
  await wait(5);
  assert.deepEqual(hub.calls, ['new /p/0stems write the release notes']);
  lens.dispose();
});

test('double tap quiets the lens; what needs you wakes it, a finished turn doesn\'t', async () => {
  const { hub, lens } = setup();
  lens.doubleTap();
  assert.equal(view(lens).kind, 'blank');
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  assert.equal(view(lens).kind, 'blank');
  hub.emit({ t: 'approval', approval: hub.snapshot.approvals[0] });
  assert.equal(view(lens).kind, 'glance');
  lens.dispose();
});

test('the live-page fallback only trusts pages a server command printed, never the daemon', () => {
  const tool = (command: string, output: string): HnEvent => ({ id: command, kind: 'tool', ts: 0, toolUseId: 'x', name: 'Bash', summary: '', input: { command }, status: 'done', output, exitCode: 0, durationMs: 1, path: null });
  assert.equal(devServerPort([tool('npm run dev', '  ➜  Local:   http://localhost:5173/')]), 5173);
  assert.equal(devServerPort([tool('curl http://127.0.0.1:47322/v1/sessions', 'http://127.0.0.1:47322/')]), null);
  assert.equal(devServerPort([tool('python3 -m http.server 8000', 'Serving HTTP on :: port 8000 (http://[::1]:8000/)')]), 8000);
  assert.equal(devServerPort([{ id: 'a', kind: 'assistant', ts: 0, text: 'open http://localhost:3000', streaming: false }]), null);
});

// ------------------------------------------------------------------ voice answers only what the lens shows

test('"No, …" on a result card is a prompt for that session, never a deny elsewhere', async () => {
  const { hub, lens } = setup(['No, use the other file instead.']);
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  await lens.hold();
  await lens.release();
  await wait(5);
  const r = view(lens);
  assert.deepEqual(r.kind === 'talk' && r.head, ['TO', 'hyperscribe']);
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['prompt i No, use the other file instead.']);
  lens.dispose();
});

test('"approve" on another session\'s card opens what needs you, guarded, and answers nothing', async () => {
  const { hub, lens } = setup(['approve']);
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  await lens.hold();
  await lens.release();
  await wait(5);
  assert.deepEqual(hub.calls, []);
  const n = view(lens);
  assert.deepEqual(n.kind === 'card' && n.head, ['NEEDS YOU', '0stems']);
  lens.tap();                                     // the tap guard holds for a new card
  await wait(5);
  assert.deepEqual(hub.calls, []);
  lens.dispose();
});

test('"deny, …" away from the card brings it up with the reason, and a double tap sends both', async () => {
  const { hub, lens } = setup(['Deny, use a feature branch.']);
  await lens.hold();
  await lens.release();
  await wait(5);
  assert.deepEqual(hub.calls, []);
  const n = view(lens);
  assert.deepEqual(n.kind === 'card' && n.head, ['NEEDS YOU', '0stems']);
  await wait(3300);                               // past the toast: the hint keeps the reason
  const kept = view(lens);
  assert.match(kept.kind === 'card' ? gapJoin(kept.hint) : '', /double tap deny: “use a feature branch\.”/);
  lens.doubleTap();
  await wait(5);
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"deny","message":"use a feature branch."}']);
  lens.dispose();
});

test('"deny, …" bringing up a question keeps no reason: a question\'s deny skips it, with nothing to tell Claude', async () => {
  const { hub, lens } = setup(['Deny, use a feature branch.']);
  // Only a question needs you.
  hub.snapshot = { ...hub.snapshot, approvals: [], sessions: hub.snapshot.sessions.map(x => (x.id === 'n' ? { ...x, state: 'idle' as const, pendingApprovalIds: [] } : x)) };
  addApproval(hub, { id: 'q1', sessionId: 'w', kind: 'question', toolName: 'AskUserQuestion', summary: 'A question',
    questions: [{ question: 'Which one?', header: 'Pick', multiSelect: false, options: [{ label: 'This' }, { label: 'That' }] }] });
  await wait(750);
  await lens.hold();
  await lens.release();
  await wait(5);
  const q = view(lens);
  assert.deepEqual(q.kind === 'choice' && q.head, ['QUESTION', 'hypernova']);
  assert.deepEqual(q.kind === 'choice' && q.hint, ['Read it, then say “deny” or double tap']);
  await wait(3300);
  const kept = view(lens);
  assert.doesNotMatch(kept.kind === 'choice' ? gapJoin(kept.hint) : '', /feature branch/);
  assert.deepEqual(hub.calls, []);
  lens.dispose();
});

test('"show me the diff" is a prompt, not a screenshot', async () => {
  const { hub, lens } = setup(['Show me the diff']);
  lens.down();                                     // the working session, opened: a hold talks to it
  lens.tap();
  await lens.hold();
  await lens.release();
  const r = view(lens);
  assert.equal(r.kind === 'talk' && r.text, 'Show me the diff');
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['prompt w Show me the diff']);
  lens.dispose();
});

test('spoken addresses become local URLs; without a port the Mac finds the page', () => {
  assert.equal(spokenUrl('localhost 3000'), 'http://localhost:3000/');
  assert.equal(spokenUrl('localhost:3000/settings'), 'http://localhost:3000/settings');
  assert.equal(spokenUrl('port 5173'), 'http://localhost:5173/');
  assert.equal(spokenUrl('127.0.0.1:8000'), 'http://127.0.0.1:8000/');
  assert.equal(spokenUrl('localhost'), undefined);
  assert.equal(spokenUrl(''), undefined);
});

// ------------------------------------------------------------------ needs cards

function addApproval(hub: ReturnType<typeof makeHub>, a: Partial<Approval> & Pick<Approval, 'id' | 'sessionId'>): void {
  const full: Approval = { createdAt: NOW, expiresAt: NOW + 600_000, kind: 'tool', toolName: 'Bash', summary: 'Bash: rm -rf build', input: { command: 'rm -rf build' },
    reason: null, canSession: false, questions: null, plan: null, ...a };
  hub.snapshot = { ...hub.snapshot, approvals: [...hub.snapshot.approvals, full],
    sessions: hub.snapshot.sessions.map(x => (x.id === a.sessionId ? { ...x, state: 'needs_you' as const, pendingApprovalIds: [a.id] } : x)) };
  hub.emit({ t: 'approval', approval: full });
  hub.emit({ t: 'change' });
}

test('after an answer the next card that needs you stays on the lens', async () => {
  const { hub, lens } = setup();
  addApproval(hub, { id: 'ap2', sessionId: 'w' });
  lens.open('n');
  await wait(750);
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"allow"}']);
  const next = view(lens);
  assert.deepEqual(next.kind === 'card' && next.head, ['NEEDS YOU', 'hypernova']);
  lens.dispose();
});

test('a question has a Back row that answers nothing; a command goes back with a swipe', async () => {
  const { hub, lens } = setup();
  addApproval(hub, { id: 'q1', sessionId: 'w', kind: 'question', toolName: 'AskUserQuestion', summary: 'A question',
    questions: [{ question: 'Which one?', header: 'Pick', multiSelect: false, options: [{ label: 'This' }, { label: 'That' }] }] });
  lens.open('w');
  const q = view(lens);
  assert.deepEqual(q.kind === 'choice' && q.options.at(-1)?.label, ['Back', 'answer later']);
  lens.down(); lens.down(); lens.down();           // past the options: the Back row, and no further
  await wait(750);
  lens.tap();
  await wait(5);
  assert.equal(view(lens).kind, 'glance');
  lens.open('n');
  lens.down();                                     // too soon: meant for what was there before
  assert.equal(view(lens).kind, 'card');
  await wait(750);
  lens.down();
  assert.equal(view(lens).kind, 'glance');
  assert.deepEqual(hub.calls, []);
  lens.dispose();
});

test('a plan: three lines first, then the whole plan as one text that scrolls; past its end it is set aside, unanswered', async () => {
  const { hub, lens } = setup();
  const steps = Array.from({ length: 9 }, (_, i) => `${i + 1}. Step ${i + 1}: change one more part of the export, and test it on the phone before the next.`);
  addApproval(hub, { id: 'p1', sessionId: 'i', kind: 'plan', toolName: 'ExitPlanMode', summary: 'A plan', input: {}, plan: `## Plan\n${steps.join('\n')}` });
  lens.open('i');
  let v = card(lens);
  assert.deepEqual(v.head, ['PLAN', 'hyperscribe']);
  assert.equal(v.body.length, 3);
  assert.deepEqual(v.facts, ['9 steps', 'swipe to read it all']);
  await wait(750);
  lens.down();                                     // the whole plan, from its top
  v = card(lens);
  assert.match(v.body[0], /^Plan$/);
  // How soon it's denied by itself (as on the phone), no page counter.
  assert.match(gapJoin(v.right), /^auto-deny in (9|10)m$/);
  assert.ok(v.body.at(-1)!.endsWith('↓'));
  assert.deepEqual(v.facts, ['9 steps', 'swipe to read on']);
  const layout = layoutKey(buildPage(v));
  checkPage(buildPage(v), 'plan');
  lens.up();                                       // back past its start: the three lines
  assert.deepEqual(card(lens).facts, ['9 steps', 'swipe to read it all']);
  lens.down();
  for (let i = 0; i < 12 && card(lens).facts[0] !== 'the end: swipe on to answer later'; i++) lens.down();
  v = card(lens);
  assert.deepEqual(v.facts, ['the end: swipe on to answer later']);
  assert.match(v.body.join(' ').replace(/\s+/g, ' '), /9\. Step 9: .* before the next\.$/);
  assert.equal(layoutKey(buildPage(v)), layout, 'scrolling is a text update');
  lens.down();                                     // past the end: set aside, unanswered
  assert.equal(view(lens).kind, 'glance');
  assert.deepEqual(hub.calls, []);
  lens.open('i');
  assert.deepEqual(card(lens).facts, ['9 steps', 'swipe to read it all'], 'opened again: its three lines');
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, []);                 // too soon after it came up
  await wait(750);
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['answer p1 {"decision":"allow"}']);
  lens.dispose();
});

test('the long-press menu has a Back that leaves a card unanswered', async () => {
  const { hub, lens } = setup();
  lens.open('n');
  lens.menu(MENU.back);
  assert.equal(view(lens).kind, 'glance');
  assert.deepEqual(hub.calls, []);
  lens.dispose();
});

test('an approval arriving while you swipe the glance keeps the focus where you put it', () => {
  const { hub, lens } = setup();
  lens.down();                                     // hypernova
  addApproval(hub, { id: 'ap3', sessionId: 'i' });
  const g = view(lens);
  assert.equal(g.kind === 'glance' && g.rows.find(r => r.focused)?.project, 'hypernova');
  lens.dispose();
});

// ------------------------------------------------------------------ small things

test('a prompt that forks a terminal session follows the new session', async () => {
  const { hub, lens } = setup(['in hyperscribe, fix the export']);
  hub.promptReply = { queued: false, forked: true, session: { ...hub.snapshot.sessions[2], id: 'fork', origin: 'hypernova' } };
  await lens.hold();
  await lens.release();
  lens.tap();
  await wait(5);
  const done = view(lens);
  assert.deepEqual(done.kind === 'talk' && done.head, ['NEW SESSION', 'hyperscribe']);
  assert.equal(lens.contextId(), 'fork');          // holding again talks to the fork
  lens.dispose();
});

test('a reply that fits has nothing to scroll, and its facts sit right under it', async () => {
  const { hub, lens } = setup();
  hub.loadEvents = async () => [
    { id: 'u', kind: 'user', ts: NOW - 60_000, text: 'go' },
    { id: 'a', kind: 'assistant', ts: NOW - 1000, text: 'Fixed the pairing test.', streaming: false },
    { id: 'r', kind: 'result', ts: NOW, ok: true, durationMs: 60_000, costUsd: null, turns: 1, error: null },
  ];
  hub.events = () => null;
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  const r = view(lens);
  assert.equal(r.kind === 'card' && r.right, '');
  assert.deepEqual(r.kind === 'card' && r.body, ['Fixed the pairing test.'], 'a window as tall as the reply');
  const boxes = buildPage(r).boxes;
  assert.equal(boxes.find(b => b.name === 'facts')!.y, boxes.find(b => b.name === 'body')!.y + LINE + 4);
  lens.down();
  const still = view(lens);
  assert.deepEqual(still.kind === 'card' && still.head, ['DONE', 'hyperscribe']);
  assert.deepEqual(still.kind === 'card' && still.body, r.kind === 'card' && r.body);
  lens.dispose();
});

test('the live-page fallback finds a dev server Claude started in the background, from where it read its output', () => {
  const tool = (id: string, name: string, input: Record<string, unknown>, output: string): HnEvent => ({ id, kind: 'tool', ts: 0, toolUseId: id, name, summary: '', input, status: 'done', output, exitCode: 0, durationMs: 1, path: null });
  const file = '/private/tmp/claude-501/-p-site/abc/tasks/b8fkrkbas.output';
  const started = tool('1', 'Bash', { command: 'npm run dev', run_in_background: true }, `Command running in background with ID: b8fkrkbas. Output is being written to: ${file}. You will be notified when it completes.`);
  assert.equal(devServerPort([started]), null);
  assert.equal(devServerPort([started, tool('2', 'Read', { file_path: file }, '     1→  VITE v8.3.3  ready in 300 ms\n     2→  ➜  Local:   http://localhost:5174/')]), 5174);
  assert.equal(devServerPort([started, tool('2', 'Bash', { command: `sleep 2; tail -5 ${file}` }, 'Local: http://127.0.0.1:4321/')]), 4321);
  assert.equal(devServerPort([started, tool('2', 'BashOutput', { bash_id: 'b8fkrkbas' }, 'Local: http://localhost:3000/')]), 3000);
  assert.equal(devServerPort([started, tool('2', 'Read', { file_path: '/p/site/notes.md' }, 'see http://localhost:9999/')]), null);
});

test('telescope: each answer of the daemon\'s telescope route, as DAEMON.md 3 lists them', async () => {
  const { hub, lens } = setup();
  const asked: string[] = [];
  let answer: () => Promise<Shot> = async () => { throw new Error('unset'); };
  hub.telescope = async (id: string, ask: object) => { asked.push(`scope ${id} ${JSON.stringify(ask)}`); return answer(); };
  hub.preview = async (t: { port: number } | { path: string }) => { asked.push(`preview ${JSON.stringify(t)}`); return 'https://mac.example.ts.net:47321/p/tok/'; };
  hub.events = () => [{ id: 't', kind: 'tool', ts: 0, toolUseId: 't', name: 'Bash', summary: '', input: { command: 'npm run dev' }, status: 'done', output: 'Local: http://localhost:5173/', exitCode: 0, durationMs: 1, path: null }];
  const scope = async (ask: object = {}, said = '') => {
    asked.length = 0;
    await lens.telescope('i', ask, said);
    const v = view(lens);
    assert.equal(v.kind, 'telescope');
    return v as Extract<LensView, { kind: 'telescope' }>;
  };
  const daemon = (status: number, code: string, message: string) => async (): Promise<Shot> => { throw new ApiError(status === 404 && code === 'not_found' ? 'missing' : 'server', message, status, code); };

  // A picture, labelled with the project, its source and how fresh it is; the whole lens is the picture.
  answer = async () => ({ url: 'https://mac.example.ts.net:47321/v1/shots/9f2c41d07a5b3e8a1c6f0b2d.png', width: 1080, height: 2400, title: 'Android emulator · Pixel 8', label: 'Pixel 8', source: 'emulator', pageUrl: null, digest: 'a'.repeat(24), at: Date.now(), sessionId: 'i' });
  let v = await scope();
  assert.deepEqual([v.head, v.picture, v.key, v.zoom, v.crisp, asked], [['TELESCOPE', 'hyperscribe', 'emulator'], true, 'https://mac.example.ts.net:47321/v1/shots/9f2c41d07a5b3e8a1c6f0b2d.png', null, false, ['scope i {}']]);
  assert.deepEqual(v.right, ['tap to zoom']);
  // A source named, and the terminal drawn crisp rather than dithered.
  answer = async () => ({ url: 'https://mac.example.ts.net:47321/v1/shots/0a2c41d07a5b3e8a1c6f0b2d.png', width: 1152, height: 512, title: 'Terminal · hyperscribe', label: 'terminal', source: 'terminal', pageUrl: null, at: Date.now(), sessionId: 'i' });
  v = await scope({ source: 'terminal' });
  assert.deepEqual([v.head, v.crisp, asked], [['TELESCOPE', 'hyperscribe', 'terminal'], true, ['scope i {"source":"terminal"}']]);
  // A page named by voice goes as its address.
  await scope({ source: 'web' }, 'localhost 3000');
  assert.deepEqual(asked, ['scope i {"source":"web","url":"http://localhost:3000/"}']);

  // The Mac looked and there's nothing to show: its words, and no live page from an old transcript.
  for (const [status, code] of [[404, 'nothing_to_show'], [404, 'no_emulator'], [501, 'no_simulator'], [403, 'screen_recording'], [409, 'busy'], [502, 'capture_failed'], [404, 'no_session']] as const) {
    answer = daemon(status, code, `daemon says ${code}`);
    v = await scope();
    assert.deepEqual([v.status, v.picture, v.hint, asked], [`daemon says ${code}`, false, ['double tap back'], ['scope i {}']], code);
  }
  // No Chrome on the Mac: its words, and the live page instead (the port said, else the dev server's).
  answer = daemon(501, 'no_browser', 'Install Google Chrome on the Mac to take screenshots.');
  v = await scope();
  assert.equal(v.status, 'Install Google Chrome on the Mac to take screenshots.');
  assert.deepEqual(asked, ['scope i {}', 'preview {"port":5173}']);
  assert.ok(partsOf(v.hint).includes('tap open on phone'));
  await scope({ source: 'web' }, 'localhost 3000');
  assert.deepEqual(asked, ['scope i {"source":"web","url":"http://localhost:3000/"}', 'preview {"port":3000}']);
  // …but not for another source: the emulator has no live page.
  v = await scope({ source: 'emulator' });
  assert.deepEqual(asked, ['scope i {"source":"emulator"}']);
  // A Hypernova from before pictures: the same live page.
  answer = daemon(404, 'not_found', 'Unknown endpoint.');
  v = await scope();
  assert.equal(v.status, 'This Mac can’t take pictures yet.');
  assert.deepEqual(asked, ['scope i {}', 'preview {"port":5173}']);
  lens.dispose();
});

test('telescope: tap zooms into a quarter, swipes pan round them, double tap zooms out, then goes back', async () => {
  const { hub, lens } = setup();
  const phone: string[] = [];
  (lens as unknown as { s: Services }).s.showOnPhone = (shot) => { phone.push(shot?.url ?? '-'); };
  hub.telescope = async () => ({ url: 'u1', width: 1152, height: 512, title: 't', source: 'web', label: 'localhost:5173', pageUrl: 'http://localhost:5173/', at: Date.now(), sessionId: 'w' });
  lens.down();                                     // the working session
  lens.tap();                                      // its live view
  assert.equal(view(lens).kind, 'live');
  await lens.telescope('w');
  const z = () => { const v = view(lens); assert.equal(v.kind, 'telescope'); return (v as Extract<LensView, { kind: 'telescope' }>).zoom; };
  assert.equal(z(), null);
  lens.down();                                     // nothing to pan yet
  assert.equal(z(), null);
  lens.tap();
  assert.equal(z(), 0);
  lens.down(); lens.down();
  assert.equal(z(), 2);
  lens.down(); lens.down();
  assert.equal(z(), 0);
  lens.up();
  assert.equal(z(), 3);
  // Zoomed in, a tap opens the picture full size on the phone.
  lens.tap();
  assert.deepEqual(phone, ['u1']);
  lens.doubleTap();
  assert.equal(z(), null);
  // Back where the telescope was pointed from: the session at work.
  lens.doubleTap();
  assert.equal(view(lens).kind, 'live');
  lens.dispose();
});

test('telescope watch: looks again every few seconds for a minute, sends nothing while nothing changes, stops at any gesture', async () => {
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1_800_000_000_000 });
  try {
    const { hub, lens } = setup(['watch']);
    const asks: string[] = [];
    const ready: string[] = [];
    (lens as unknown as { s: Services }).s.shotReady = (shot) => { ready.push(shot?.url ?? '-'); };
    let url = 'u1';
    hub.telescope = async (_id: string, ask: object) => { asks.push(JSON.stringify(ask)); return { url, width: 1080, height: 2400, title: 't', source: 'emulator', label: 'Pixel 8', pageUrl: null, device: 'emulator-5554', at: Date.now(), sessionId: 'w' }; };
    const settle = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };
    // "Watch" said on the glance: the telescope opens on the session in focus and keeps looking.
    lens.down();
    await lens.hold();
    await lens.release();
    await settle();
    let v = view(lens) as Extract<LensView, { kind: 'telescope' }>;
    assert.equal(v.kind, 'telescope');
    assert.deepEqual([asks, ready], [['{}'], ['u1']]);
    // Every 3 s the same source and device again, as a watching look (the Mac keeps only the
    // newest frames); an unchanged link loads and sends nothing.
    mock.timers.tick(3000); await settle();
    mock.timers.tick(3000); await settle();
    const look = '{"source":"emulator","device":"emulator-5554","watch":true}';
    assert.deepEqual(asks, ['{}', look, look]);
    assert.deepEqual(ready, ['u1']);
    v = view(lens) as Extract<LensView, { kind: 'telescope' }>;
    assert.deepEqual(v.right, ['just now', 'watching']);
    // The label's age is the last change's, not the last look's.
    assert.equal(lens.shot?.at, 1_800_000_000_000);
    // A changed picture comes through, and is new again.
    url = 'u2';
    mock.timers.tick(3000); await settle();
    assert.deepEqual(ready, ['u1', 'u2']);
    assert.equal(lens.shot?.at, Date.now());
    // A minute in all, then it stops by itself.
    for (let i = 0; i < 25; i++) { mock.timers.tick(3000); await settle(); }
    const n = asks.length;
    assert.ok(n <= 22, `${n} looks in a minute`);
    mock.timers.tick(9000); await settle();
    assert.equal(asks.length, n);
    assert.notEqual((view(lens) as Extract<LensView, { kind: 'telescope' }>).right, 'watching');
    // Watching again, and a swipe stops it at once.
    lens.watch('w');
    await settle();
    const m = asks.length;
    lens.down();
    mock.timers.tick(9000); await settle();
    assert.equal(asks.length, m);
    lens.dispose();
  } finally {
    mock.timers.reset();
  }
});

test('telescope: a source the Mac passed over for something fixable says how, over what it showed', async () => {
  const { hub, lens } = setup();
  hub.telescope = async () => ({ url: 'u1', width: 1152, height: 512, title: 't', source: 'terminal', label: 'terminal', pageUrl: null, at: Date.now(), sessionId: 'w',
    skipped: { source: 'window', code: 'screen_recording', message: 'Say “telescope window” to allow Screen Recording.' } });
  await lens.telescope('w');
  const v = view(lens) as Extract<LensView, { kind: 'telescope' }>;
  assert.deepEqual(v.right, ['Say “telescope window” to allow Screen Recording.']);
  lens.dispose();
});

test('watching a dithered picture: scattered noise isn’t sent, a changed digit is', () => {
  const w = 288, h = 128;
  const was = new Float32Array(w * h);
  const moved = (pixels: Array<[number, number]>) => {
    const now = was.slice();
    for (const [x, y] of pixels) now[y * w + x] = 0.5;
    return still(now, was, w, 0, 0, w, h);
  };
  // A few twinkles across the tile.
  assert.equal(moved(Array.from({ length: 20 }, (_, i) => [(i * 37) % w, (i * 53) % h] as [number, number])), true);
  // A caret's blink: a line one pixel wide.
  assert.equal(moved(Array.from({ length: 14 }, (_, i) => [100, 40 + i] as [number, number])), true);
  // "41" → "42": a glyph's worth of pixels together, under the share that counts as a change.
  const digit: Array<[number, number]> = [];
  for (let y = 0; y < 7; y++) for (let x = 0; x < 5; x++) if ((x + y) % 2 === 0 || y === 6) digit.push([200 + x, 60 + y]);
  assert.ok(digit.length < w * h * 0.0025);
  assert.equal(moved(digit), false);
  // Many pixels anywhere: a change.
  assert.equal(moved(Array.from({ length: 120 }, (_, i) => [(i * 31) % w, (i * 17) % h] as [number, number])), false);
  // Nothing: unchanged.
  assert.equal(moved([]), true);
});

// ------------------------------------------------------------------ frames

test('with no news the planet column settles and stops sending frames; the clock is not news', () => {
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1_800_000_000_000 });
  try {
    const frames: Array<{ still: boolean }> = [];
    const orrery: FrameSource = {
      canvas: null as unknown as HTMLCanvasElement,
      frame: (_v, now, still = false) => { frames.push({ still }); return { png: new Uint8Array(1), hash: still ? 1 : now }; },
    };
    const sender = { idle: true, show() {}, picture() {} };
    // Something that moves: a session that needs you, and the sun at 70%.
    let v: LensView = { kind: 'glance', left: 'hypernova/', right: '70%', rows: [{ project: '0stems', detail: 'wants to push', right: '1m', state: 'needs', focused: true }], empty: '', hint: '',
      orrery: { sun: { pct: 70, limit: false }, planets: [{ state: 'needs', focused: true }] } };
    const out = new LensOutput(sender, () => v, orrery);
    // Time passes in 100 ms steps; the 20 s clock refreshes the text as main.ts does.
    const run = (ms: number) => { for (let t = 0; t < ms; t += 100) { mock.timers.tick(100); if (t % 20_000 === 0) out.invalidate({ news: false }); } };
    out.invalidate();
    run(60_000);
    const moving = frames.length;
    assert.ok(moving > 100, `frames while it moves: ${moving}`);
    run(120_000);
    const settledAt = frames.length;
    assert.ok(settledAt < moving + 100, `frames stop after 90 s: ${settledAt}`);
    assert.equal(frames.at(-1)?.still, true, 'the last frame is the resting one');
    run(600_000);
    assert.equal(frames.length, settledAt, 'ten more minutes of clock ticks send nothing');
    out.invalidate();                              // the same lens again: nothing to tell
    run(2000);
    assert.equal(frames.length, settledAt, 'a redraw that changes nothing is not news');
    v = { ...v, right: '71%', orrery: { ...v.orrery, sun: { pct: 71, limit: false } } };
    out.invalidate();                              // news: it moves again
    run(2000);
    assert.ok(frames.length > settledAt + 3 && frames.at(-1)?.still === false);
  } finally {
    mock.timers.reset();
  }
});

test('a hub that keeps asking for a redraw while the Mac is away lets the planets settle', () => {
  const T0 = 1_800_000_000_000;
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: T0 });
  try {
    const frames: Array<{ at: number; still: boolean }> = [];
    const orrery: FrameSource = {
      canvas: null as unknown as HTMLCanvasElement,
      frame: (_v, now, still = false) => { frames.push({ at: now - T0, still }); return { png: new Uint8Array(1), hash: still ? 1 : now }; },
    };
    // A session at work and the sun at 70%, as the last snapshot left them; its time and the
    // reset time go on with the clock ("2m", "resets 1h 12m"), as on the real glance.
    const view = (): LensView => {
      const now = Date.now();
      return { kind: 'glance', left: 'hypernova/', right: `70% · resets ${ago(T0 + 4_000_000 - now)}`, empty: '', hint: 'hold to talk to hypernova',
        rows: [{ project: 'hypernova', detail: 'BASH ./gradlew build', right: ago(now - T0 + 50_000), state: 'working', focused: false }],
        orrery: { sun: { pct: 70, limit: false }, planets: [{ state: 'working', focused: false }] } };
    };
    const out = new LensOutput({ idle: true, show() {}, picture() {} }, view, orrery);
    out.invalidate();
    // The hub's retries (1, 3, 7 … 121 s) and a poll every 4 s, each one a redraw; the clock every 20 s.
    const retries = new Set([1, 3, 7, 15, 31, 61, 91, 121].map(s => s * 1000));
    for (let t = 100; t <= 200_000; t += 100) {
      mock.timers.tick(100);
      if (retries.has(t) || t % 4000 === 0) out.invalidate();
      if (t % 20_000 === 0) out.invalidate({ news: false });
    }
    const late = frames.filter(f => f.at > 95_000);
    assert.ok(frames.length > 100, `frames while it moves: ${frames.length}`);
    assert.ok(late.length <= 1, `frames after 95 s: ${late.length}`);
    assert.equal(frames.at(-1)?.still, true, 'the last frame is the resting one');
  } finally {
    mock.timers.reset();
  }
});

test('times that cross an hour ("59m", "1h", "1h 1m") are the clock, not news', () => {
  const T0 = 1_800_000_000_000;
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: T0 });
  try {
    const frames: Array<{ at: number; still: boolean }> = [];
    const orrery: FrameSource = {
      canvas: null as unknown as HTMLCanvasElement,
      frame: (_v, now, still = false) => { frames.push({ at: now - T0, still }); return { png: new Uint8Array(1), hash: still ? 1 : now }; },
    };
    // A session at work for 58 minutes and a reset 61 minutes away: in the next 200 s one turns
    // "59m", "1h", "1h 1m", the other "1h 1m", "1h", "59m".
    const view = (): LensView => {
      const now = Date.now();
      return { kind: 'glance', left: 'hypernova/', right: `70% · resets ${ago(T0 + 61 * 60_000 - now)}`, empty: '', hint: 'hold to talk to hypernova',
        rows: [{ project: 'hypernova', detail: 'BASH ./gradlew build', right: ago(now - T0 + 58 * 60_000), state: 'working', focused: false }],
        orrery: { sun: { pct: 70, limit: false }, planets: [{ state: 'working', focused: false }] } };
    };
    const out = new LensOutput({ idle: true, show() {}, picture() {} }, view, orrery);
    out.invalidate();
    // A poll every 4 s redraws, as a hub does; the clock every 20 s.
    for (let t = 100; t <= 200_000; t += 100) {
      mock.timers.tick(100);
      if (t % 4000 === 0) out.invalidate();
      if (t % 20_000 === 0) out.invalidate({ news: false });
    }
    const last = view();
    assert.ok(last.kind === 'glance' && last.rows[0].right === '1h 1m' && last.right === '70% · resets 57m');
    const late = frames.filter(f => f.at > 95_000);
    assert.ok(late.length <= 1, `frames after 95 s: ${late.length}`);
    assert.equal(frames.at(-1)?.still, true, 'the last frame is the resting one');
  } finally {
    mock.timers.reset();
  }
});

// ------------------------------------------------------------------ a session at work

test('a session that works again leaves its reply for the live view, and never shows the old reply', async () => {
  const { hub, lens, spoken } = setup();
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  lens.down();                                     // reading the reply
  const reply = view(lens);
  assert.deepEqual(reply.kind === 'card' && reply.head, ['DONE', 'hyperscribe']);
  // A prompt typed on the Mac: the session works on a new turn.
  hub.eventsBy.set('i', [...hub.events('i')!, { id: 'u2', kind: 'user', ts: NOW, text: 'now the docs' }]);
  patch(hub, 'i', { state: 'working', activity: 'Edit: README.md' });
  const v = live(lens);
  assert.deepEqual(v.head, ['WORKING', 'hyperscribe']);
  assert.deepEqual(v.now, ['› Edit: README.md']);
  assert.deepEqual(v.output, ['» now the docs']);
  assert.ok(!JSON.stringify(v).includes('Fixed the pairing test'), 'no false DONE or old reply');
  lens.tap();                                      // pauses the scroll; reads nothing
  await wait(5);
  assert.deepEqual(spoken, []);
  // Its turn ends: the result.
  patch(hub, 'i', { state: 'idle', activity: null });
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  const done = view(lens);
  assert.deepEqual(done.kind === 'card' && done.head, ['DONE', 'hyperscribe']);
  lens.dispose();
});

test('the live view: what runs now over the turn\'s output, a line at a time; tap pauses, swipes scroll', () => {
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: NOW });
  try {
    const { hub, lens } = setup();
    lens.onChange = () => void lens.view();       // the lens draws what changed, as main.ts has it
    hub.eventsBy.set('w', [{ id: 'u', kind: 'user', ts: NOW - 60_000, text: 'build it' }]);
    hub.agentsBy.set('w', [agent('a1', 'survey:parser', 'running', { activity: 'Read: core/Solar.kt' })]);
    patch(hub, 'w', { agentsRunning: 1 });
    lens.open('w');
    let v = live(lens);
    assert.deepEqual(v.head, ['WORKING', 'hypernova']);
    assert.deepEqual([v.now, v.more, v.output], [['› Bash: ./gradlew build', '• survey:parser   Read: core/Solar.kt'], 0, ['» build it']]);
    // Claude writes nine lines at once: the box shows the first ones and moves on a line at a time.
    say(hub, 'w', { id: 'x1', kind: 'assistant', ts: NOW, text: Array.from({ length: 9 }, (_, i) => `Line ${i + 1}.`).join('\n\n'), streaming: false });
    v = live(lens);
    assert.deepEqual(v.output, ['» build it', 'Line 1.', 'Line 2.', 'Line 3.', 'Line 4.']);
    mock.timers.tick(1500);
    assert.equal(live(lens).output[0], 'Line 1.');
    mock.timers.tick(1500);
    assert.equal(live(lens).output[0], 'Line 2.');
    // A tap pauses: nothing moves until the next one.
    lens.tap();
    v = live(lens);
    assert.equal(partsOf(v.hint)[0], 'paused');
    mock.timers.tick(10_000);
    assert.equal(live(lens).output[0], 'Line 2.');
    lens.tap();
    mock.timers.tick(1500);
    assert.equal(live(lens).output[0], 'Line 3.');
    // Back through the turn, then forward to its end: following again.
    lens.up();
    v = live(lens);
    assert.deepEqual([v.output[0], partsOf(v.hint)[0] === 'paused'], ['» build it', true]);
    lens.down();
    lens.down();
    v = live(lens);
    assert.deepEqual([v.output.at(-1), partsOf(v.hint)[0] === 'paused'], ['Line 9.', false]);
    // The agent reports: its words come in at the end, and it leaves the top box.
    const done = agent('a1', 'survey:parser', 'done', { endedAt: NOW + 20_000, result: 'Three readers still parse the old snapshot.' });
    hub.agentsBy.set('w', [done]);
    patch(hub, 'w', { agentsRunning: 0 });
    hub.emit({ t: 'agent', sessionId: 'w', agent: done });
    mock.timers.tick(1500);
    mock.timers.tick(1500);
    v = live(lens);
    assert.deepEqual(v.now, ['› Bash: ./gradlew build']);
    assert.equal(v.output.slice(-2).join(' '), '• survey:parser — Three readers still parse the old snapshot.');
    lens.dispose();
  } finally {
    mock.timers.reset();
  }
});

test('a session whose agents work after its turn is never done: a working line, a live view, the result once', async () => {
  const { hub, lens, spoken } = setup();
  hub.agentsBy.set('w', [agent('a1', 'review', 'running', { activity: 'Grep: TODO' }), agent('a2', 'tests', 'running', { startedAt: NOW - 1000, activity: 'Bash: npm test' })]);
  patch(hub, 'w', { state: 'idle', activity: null, agentsRunning: 2 });
  const g = view(lens);
  assert.deepEqual(g.kind === 'glance' && g.rows.map(r => [r.project, r.state, r.detail]).find(r => r[0] === 'hypernova'), ['hypernova', 'working', '2 agents running']);
  lens.open('w');
  let v = live(lens);
  assert.deepEqual(v.head, ['AGENTS', 'hypernova']);
  assert.deepEqual(v.now, ['• tests   Bash: npm test', '• review   Grep: TODO']);
  // "status" says so too.
  await lens.hold();
  lens.screenKind;
  (lens as unknown as { s: { speech(): DemoSpeech } }).s.speech().next('status');
  await lens.release();
  assert.match(spoken.at(-1) ?? '', /hypernova has 2 agents running\./);
  lens.open('w');
  v = live(lens);
  // The last report's turn ends: then it's done.
  patch(hub, 'w', { agentsRunning: 0 });
  hub.emit({ t: 'finished', sessionId: 'w' });
  await wait(5);
  const done = view(lens);
  assert.deepEqual(done.kind === 'card' && done.head, ['DONE', 'hypernova']);
  lens.dispose();
});

test('what a working session asks comes up on its live view, and the live view comes back once it\'s answered', async () => {
  const { hub, lens } = setup();
  hub.eventsBy.set('w', [{ id: 'u', kind: 'user', ts: NOW - 60_000, text: 'build it' }]);
  lens.open('w');
  live(lens);
  addApproval(hub, { id: 'ap9', sessionId: 'w', agentId: 'a1', agentLabel: 'survey:parser' });
  const card = view(lens);
  assert.deepEqual(card.kind === 'card' && card.head, ['NEEDS YOU', 'hypernova']);
  assert.equal(card.kind === 'card' && card.lead, 'survey:parser wants to run');
  lens.tap();                                      // too soon: meant for the live view
  await wait(750);
  hub.answer = async (id: string, a: ApprovalAnswer) => {
    hub.calls.push(`answer ${id} ${JSON.stringify(a)}`);
    hub.snapshot = { ...hub.snapshot, approvals: hub.snapshot.approvals.filter(x => x.id !== id) };
    patch(hub, 'w', { state: 'working', pendingApprovalIds: [] });
  };
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['answer ap9 {"decision":"allow"}']);
  assert.deepEqual(live(lens).head, ['WORKING', 'hypernova']);
  lens.dispose();
});

test('reading aloud off: taps and "status" stay silent; "mute" and "unmute" turn it off and on', async () => {
  const { hub, lens, spoken, settings } = setup(['mute', 'status', 'read the reply', 'unmute', 'status']);
  await lens.hold();
  await lens.release();
  assert.equal(settings().readAloud, false);
  assert.deepEqual(view(lens).kind === 'glance' && (view(lens) as Extract<LensView, { kind: 'glance' }>).hint, ['Read aloud off']);
  await wait(1900);                                // past the toast
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  const r = view(lens);
  assert.deepEqual(r.kind === 'card' && r.hint, ['double tap back', 'hold talk']);
  lens.tap();
  await wait(5);
  assert.deepEqual((view(lens) as Extract<LensView, { kind: 'card' }>).hint, ['Read aloud is off: say “unmute”']);
  for (let i = 0; i < 2; i++) { await lens.hold(); await lens.release(); await wait(5); }
  assert.deepEqual(spoken, [], 'nothing read while muted');
  await lens.hold();
  await lens.release();
  assert.equal(settings().readAloud, true);
  await lens.hold();
  await lens.release();
  await wait(5);
  assert.equal(spoken.length, 1, '"status" speaks again');
  lens.dispose();
});

test('the live view asks the Mac only for the session on screen: once on the socket, every 4 s without it, never while it\'s away', async () => {
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: NOW });
  const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };
  try {
    const { hub, lens } = setup();
    hub.eventsBy.set('w', [{ id: 'u', kind: 'user', ts: NOW - 60_000, text: 'build it' }]);
    // On the socket: its agents once, then what the socket brings.
    lens.open('w');
    live(lens);
    await flush();
    live(lens);
    mock.timers.tick(20_000);
    live(lens);
    assert.deepEqual(hub.loads, ['agents w']);
    // Agents starting on the socket: their own messages keep the list current, so their count
    // changing asks for nothing.
    for (const n of [1, 2]) { patch(hub, 'w', { agentsRunning: n }); live(lens); await flush(); }
    assert.deepEqual(hub.loads, ['agents w']);
    // Without it (the hub polls the snapshot): every 4 s, while it's on screen.
    hub.loads.length = 0;
    hub.status = 'polling';
    live(lens);
    for (let i = 0; i < 3; i++) { mock.timers.tick(4000); await flush(); live(lens); }
    assert.deepEqual(hub.loads, ['events w', 'agents w', 'events w', 'agents w', 'events w', 'agents w']);
    hub.loads.length = 0;
    lens.doubleTap();                              // back to the glance
    view(lens);
    mock.timers.tick(20_000);
    await flush();
    assert.deepEqual(hub.loads, []);
    // The Mac away: nothing at all.
    hub.status = 'offline';
    hub.agentsBy.delete('i');
    patch(hub, 'i', { state: 'working' });
    lens.open('i');
    view(lens);
    mock.timers.tick(20_000);
    await flush();
    assert.deepEqual(hub.loads, []);
    // What it shows is only what the Mac last said, and its corner says so.
    assert.match(gapJoin(live(lens).right), /^offline since \d{1,2}:\d\d/);
    lens.dispose();
  } finally {
    mock.timers.reset();
  }
});

// ------------------------------------------------------------------ the time and the batteries

test('the glance tells the time; the batteries are a card on request: "battery" or a swipe up from the top', async () => {
  const { hub, lens, glasses } = setup(['battery']);
  const glance = () => { const v = view(lens); assert.equal(v.kind, 'glance'); return v as Extract<LensView, { kind: 'glance' }>; };
  const box = (name: string) => buildPage(view(lens)).boxes.find(b => b.name === name);
  // The time sits by the label, before the usage; no battery line on the glance any more.
  const clock = glance().clock!;
  assert.match(clock, /^\d{1,2}:\d{2}/);
  assert.match(box('header')!.content, new RegExp(`^hypernova/ +${clock.replace(/\s/g, '\\s')} +42% {3}resets in`));
  assert.equal(box('power'), undefined);
  // A swipe up from the first line: the card, each device on its row, the glasses first.
  glasses.battery = { percent: 84, charging: false };
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, battery: { percent: 62, charging: true, onAC: true, minutesLeft: 40 } } };
  lens.up();
  const card = () => { const v = view(lens); assert.equal(v.kind, 'power'); return v as Extract<LensView, { kind: 'power' }>; };
  assert.deepEqual(card().rows.map(r => [r.label, r.percent, r.segments, r.charging]), [['GLASSES', 84, 5, false], ['MAC', 62, 4, true]]);
  assert.match(box('row0')!.content, /^GLASSES +84%$/);
  assert.match(box('row1')!.content, /^MAC +62% {3}charging$/);
  assert.deepEqual(buildPage(view(lens)).images.map(i => i.kind), ['power']);
  // A Mac without a battery isn't listed; one that's away neither (its level would be an old one).
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, battery: null } };
  assert.deepEqual(card().rows.map(r => r.label), ['GLASSES']);
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, battery: { percent: 12, charging: false, onAC: false, minutesLeft: 20 } } };
  assert.deepEqual(card().rows.map(r => [r.label, r.segments, r.low]), [['GLASSES', 5, false], ['MAC', 1, true]]);
  assert.match(box('row1')!.content, /12% {3}low$/);
  hub.status = 'offline';
  assert.deepEqual(card().rows.map(r => r.label), ['GLASSES']);
  hub.status = 'live';
  // Double tap: back where you were. "battery" said anywhere brings it up too.
  lens.doubleTap();
  assert.equal(view(lens).kind, 'glance');
  await lens.hold();
  await lens.release();
  assert.equal(view(lens).kind, 'power');
  lens.dispose();
});

test('"status" names a battery only when it runs low', async () => {
  const { hub, lens, spoken, glasses } = setup(['status', 'status']);
  glasses.battery = { percent: 84, charging: false };
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, battery: { percent: 62, charging: false, onAC: false, minutesLeft: 180 } } };
  await lens.hold();
  await lens.release();
  assert.doesNotMatch(spoken.at(-1) ?? '', /battery/);
  glasses.battery = { percent: 9, charging: false };
  await lens.hold();
  await lens.release();
  assert.match(spoken.at(-1) ?? '', /The glasses' battery is at 9 percent\.$/);
  lens.dispose();
});

test('the time is text, not news: the page stays, one box changes, and the planets rest', () => {
  const T0 = 1_800_000_000_000;
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: T0 });
  try {
    const frames: Array<{ at: number; still: boolean }> = [];
    const orrery: FrameSource = {
      canvas: null as unknown as HTMLCanvasElement,
      frame: (_v, now, still = false) => { frames.push({ at: now - T0, still }); return { png: new Uint8Array(1), hash: still ? 1 : now }; },
    };
    const shown: LensPage[] = [];
    // What needs you (the bracket moves) under the time of day.
    const view = (): LensView => {
      const now = Date.now();
      return { kind: 'glance', left: 'hypernova/', clock: clockTime(now, { locale: 'en-GB', timeZone: 'UTC' }), right: ['42%', 'resets 2h'], empty: '', hint: ['tap to answer', 'hold to talk'],
        rows: [{ project: '0stems', detail: 'wants to push to main', right: '1m', state: 'needs', focused: false }],
        orrery: { sun: { pct: 42, limit: false }, planets: [{ state: 'needs', focused: false }] } };
    };
    const out = new LensOutput({ idle: true, show: p => shown.push(p), picture() {} }, view, orrery);
    out.invalidate();
    // The clock on its 20 s marks (news: false); a redraw now and then (main.ts sends one for a battery's change).
    const stop = clockTicks(() => out.invalidate({ news: false }));
    for (let t = 100; t <= 200_000; t += 100) {
      mock.timers.tick(100);
      if (t % 30_000 === 0 || t === 60_000) out.invalidate();
    }
    stop();
    const clocks = new Set(shown.map(p => p.boxes.find(b => b.name === 'header')!.content));
    assert.ok(clocks.size >= 4, `the time turned with the minute: ${[...clocks].join(' | ')}`);
    assert.equal(new Set(shown.map(layoutKey)).size, 1, 'one layout throughout: the time is a text update');
    assert.ok(frames.filter(f => f.at > 95_000).length <= 1, `frames after 95 s: ${frames.filter(f => f.at > 95_000).length}`);
    assert.equal(frames.at(-1)?.still, true, 'the last frame is the resting one');
  } finally {
    mock.timers.reset();
  }
});

test('a result card shows its turn as it is now, never the reply it had when last opened', async () => {
  const { hub, lens } = setup();
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  assert.equal(shownText(view(lens)), 'Fixed the pairing test. It waits for the camera now.');
  lens.doubleTap();
  // Another turn ran while the lens looked elsewhere and its finish never came (the socket was down
  // at the time); the hub's copy of the turn has it.
  hub.eventsBy.set('i', [
    { id: 'u2', kind: 'user', ts: NOW, text: 'now the docs' },
    { id: 'a2', kind: 'assistant', ts: NOW + 1000, text: 'Wrote the docs for pairing.', streaming: false },
    { id: 'r2', kind: 'result', ts: NOW + 2000, ok: true, durationMs: 2000, costUsd: null, turns: 1, error: null },
  ]);
  lens.open('i');
  const r = card(lens);
  assert.deepEqual([r.head, shownText(r)], [['DONE', 'hyperscribe'], 'Wrote the docs for pairing.']);
  lens.dispose();
});

test('a DONE card on screen while a whole turn ran during a socket outage shows that turn once the Mac is back', async () => {
  const { hub, lens } = setup();
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  assert.equal(shownText(card(lens)), 'Fixed the pairing test. It waits for the camera now.');
  hub.loads.length = 0;
  // The socket drops and comes back with nothing new: the hub drops its copy of the turn, and
  // nothing is asked for (the session hasn't moved on).
  hub.status = 'offline';
  hub.emit({ t: 'change' });
  hub.dropped.add('i');
  hub.status = 'live';
  hub.emit({ t: 'change' });
  await wait(5);
  assert.deepEqual(hub.loads, []);
  // Down again, and a whole turn runs meanwhile: its finish never comes, nor do its events. Back
  // on the socket, the session's activity is newer than the card's: the turn is loaded again.
  hub.status = 'offline';
  hub.emit({ t: 'change' });
  hub.eventsBy.set('i', [
    { id: 'u2', kind: 'user', ts: NOW, text: 'now the docs' },
    { id: 'a2', kind: 'assistant', ts: NOW + 1000, text: 'Wrote the docs for pairing.', streaming: false },
    { id: 'r2', kind: 'result', ts: NOW + 2000, ok: true, durationMs: 2000, costUsd: null, turns: 1, error: null },
  ]);
  hub.dropped.add('i');
  hub.status = 'live';
  patch(hub, 'i', { lastText: 'Wrote the docs for pairing.', lastActivityAt: NOW + 2000 });
  await wait(5);
  assert.deepEqual(hub.loads, ['events i']);
  assert.deepEqual([card(lens).head, shownText(card(lens))], [['DONE', 'hyperscribe'], 'Wrote the docs for pairing.']);
  lens.dispose();
});

test('while the hub polls, a result card is loaded again only when its session moved on', async () => {
  const { hub, lens } = setup();
  hub.status = 'polling';
  lens.open('i');
  await wait(5);
  assert.deepEqual(hub.loads, ['events i']);       // nothing keeps the hub's copy current now
  hub.loads.length = 0;
  patch(hub, 'w', { activity: 'Bash: npm test' }); // a poll that finds another session at work
  await wait(5);
  assert.deepEqual(hub.loads, []);
  hub.eventsBy.set('i', [
    { id: 'u2', kind: 'user', ts: NOW, text: 'now the docs' },
    { id: 'a2', kind: 'assistant', ts: NOW + 1000, text: 'Wrote the docs for pairing.', streaming: false },
    { id: 'r2', kind: 'result', ts: NOW + 2000, ok: true, durationMs: 2000, costUsd: null, turns: 1, error: null },
  ]);
  patch(hub, 'i', { lastText: 'Wrote the docs for pairing.', lastActivityAt: NOW + 2000 });
  await wait(5);
  assert.deepEqual(hub.loads, ['events i']);
  assert.equal(shownText(view(lens)), 'Wrote the docs for pairing.');
  lens.dispose();
});

test('a reply said on a session\'s DONE card goes back to that session, which shows it at work', async () => {
  const { hub, lens } = setup(['now run the tests']);
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  await lens.hold();
  await lens.release();
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['prompt i now run the tests']);
  patch(hub, 'i', { state: 'working', activity: 'Bash: npm test' });
  await wait(1400);
  assert.deepEqual(live(lens).head, ['WORKING', 'hyperscribe']);
  lens.dispose();
});

test('"status" doesn\'t speak the Mac\'s battery while the Mac is away', async () => {
  const { hub, lens, spoken } = setup(['status', 'status']);
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, battery: { percent: 9, charging: false, onAC: false, minutesLeft: 20 } } };
  await lens.hold();
  await lens.release();
  assert.match(spoken.at(-1) ?? '', /Your Mac's battery is at 9 percent\.$/);
  hub.status = 'offline';
  await lens.hold();
  await lens.release();
  assert.doesNotMatch(spoken.at(-1) ?? '', /battery/);
  lens.dispose();
});

// ------------------------------------------------------------------ bytes: Russian, CJK, and worse

/** Russian: 2 bytes a letter. */
const RU = 'Исправил экспорт: теперь он запоминает последнюю папку, а все сорок восемь тестов проходят на эмуляторе и на телефоне, трижды подряд.';
/** CJK: 3 bytes a character, every one as wide as the font's unknown glyph. */
const CJK = '修复了导出功能现在它会记住上次使用的文件夹并且所有四十八个测试都在模拟器和手机上连续三次通过了这是很长的一段话';
/** The worst there is for its width: 3-byte quotes and 2-byte dots, 5 px each. */
const DENSE = '’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·’·';

test('UTF-8 is counted as the glasses count it, and a box over the limit loses lines from its end (or its start), then characters', () => {
  assert.equal(utf8Bytes('abc'), 3);
  assert.equal(utf8Bytes('Ёж'), 4);
  assert.equal(utf8Bytes('中'), 3);
  assert.equal(utf8Bytes('↓…'), 6);
  assert.equal(utf8Bytes('𝐀'), 4);
  const text = Array.from({ length: 12 }, (_, i) => `${i} ${RU}`).join('\n');
  assert.ok(utf8Bytes(text) > BOX_BYTES);
  const head = fitBytes(text), tail = fitBytes(text, BOX_BYTES, true);
  assert.ok(utf8Bytes(head) <= BOX_BYTES && utf8Bytes(tail) <= BOX_BYTES);
  assert.ok(head.startsWith('0 ') && text.startsWith(head), 'whole lines, from the start');
  assert.ok(text.endsWith(tail) && tail.split('\n').length > 1, 'whole lines, from the end');
  const one = fitBytes(CJK.repeat(20));
  assert.ok(utf8Bytes(one) <= BOX_BYTES && one.endsWith('…'));
  assert.equal(fitBytes('short'), 'short');
});

test('every text box fits the firmware’s bytes: a full window of long Russian lines, of CJK, and of the densest text there is', async () => {
  const { hub, lens } = setup();
  const READ_W = CARD_TEXT_W - MORE_W;
  for (const [label, line] of [['russian', RU], ['cjk', CJK], ['dense', DENSE.repeat(3)]] as const) {
    const reply = Array.from({ length: 10 }, (_, i) => `${i + 1}. ${line}`).join('\n\n');
    hub.eventsBy.set('i', [
      { id: 'u', kind: 'user', ts: NOW - 300_000, text: 'fix it' },
      { id: `a-${label}`, kind: 'assistant', ts: NOW - 1000, text: reply, streaming: false },
      { id: 'r', kind: 'result', ts: NOW, ok: true, durationMs: 300_000, costUsd: null, turns: 3, error: null },
    ]);
    lens.open('i');
    await wait(5);
    const all = textLines(Array.from({ length: 10 }, (_, i) => `${i + 1}. ${line}`).join('\n'), READ_W);
    const seen = new Set<string>();
    const room = cardRoom(false, 1);
    let rows = 0;
    for (let i = 0; i < 40; i++) {
      const v = card(lens);
      checkPage(buildPage(v), `${label} ${i}`);
      const body = buildPage(v).boxes.find(b => b.name === 'body')!.content.split('\n').filter(Boolean);
      rows = Math.max(rows, body.length);
      for (const l of body) seen.add(l.replace(/\s+↓$/, ''));
      lens.down();
    }
    // Swiped through, every line of it was on the lens, none cut.
    for (const l of all) assert.ok(seen.has(l), `${label}: line seen: ${l}`);
    // Russian and CJK fill the window; only the densest text takes fewer lines.
    if (label === 'dense') assert.ok(rows < room && rows === fitRows(all, room, CARD_TEXT_W), `${label}: ${rows} of ${room} rows`);
    else assert.equal(rows, room, `${label}: a full window`);
    lens.doubleTap();
  }
  lens.dispose();
});

test('the live view, captions, the talk draft, a choice and a pop-up stay within the bytes too', async () => {
  const views: Array<[string, LensView]> = [];
  for (const [label, line] of [['russian', RU], ['cjk', CJK], ['dense', DENSE]] as const) {
    const many = Array(9).fill(line);
    views.push(
      [`${label} card`, { kind: 'card', head: ['NEEDS YOU', line], right: '2m', lead: line, body: many, facts: [line], factsRows: 2, hint: line, orrery }],
      [`${label} live`, { kind: 'live', head: `WORKING · ${line}`, right: '1m', now: [line, line], more: 3, output: many, hint: line, orrery }],
      [`${label} captions`, { kind: 'talk', head: 'LISTENING', right: 'to x', text: many.join(' '), live: true, hint: line, orrery }],
      [`${label} draft`, { kind: 'talk', head: `TO ${line}`, right: 'sending in 8', text: many.join(' '), live: false, hint: line, orrery }],
      [`${label} choice`, { kind: 'choice', head: `QUESTION · ${line}`, right: '1/2', prompt: line, options: many.map(l => ({ label: l, mark: '○' as const })), cursor: 2, hint: line, orrery }],
      [`${label} pop-up`, { kind: 'card', head: ['DONE', 'x'], right: '', lead: '', body: many, facts: [], hint: line, orrery, band: { head: ['NEEDS YOU', line], right: '+2', detail: line, detailRight: 'auto-deny in 9m' } }],
    );
  }
  for (const [label, v] of views) checkPage(buildPage(v), label);
  // Captions too dense for the box keep their newest words.
  const words = Array.from({ length: 40 }, (_, i) => `${DENSE.slice(0, 20)}${i}`).join(' ');
  const caption = buildPage({ kind: 'talk', head: 'LISTENING', right: '', text: words, live: true, hint: '', orrery }).boxes.find(b => b.name === 'body')!.content;
  assert.match(caption, /39$/);

  // The controller: a turn of long Russian lines in the live view, and a long Russian draft whose
  // window shows fewer lines only when it must, every line still read by swiping.
  const { hub, lens, speech } = setup();
  hub.eventsBy.set('w', [
    { id: 'u', kind: 'user', ts: NOW - 9000, text: RU },
    ...Array.from({ length: 6 }, (_, i): HnEvent => ({ id: `a${i}`, kind: 'assistant', ts: NOW - 5000 + i, text: `${RU} ${DENSE}`, streaming: false })),
  ]);
  lens.open('w');
  for (let i = 0; i < 12; i++) { checkPage(buildPage(live(lens)), `live ${i}`); lens.up(); }
  lens.doubleTap();
  const draft = Array(6).fill(DENSE).join(' ');
  speech.next(`in hyperscribe, ${draft}`);
  await lens.hold();
  await lens.release();
  const all = wrapAll(clean(draft), TALK_TEXT_W - MORE_W);
  const seen = new Set<string>();
  for (let i = 0; i < 12; i++) {
    const v = view(lens);
    assert.equal(v.kind, 'talk');
    checkPage(buildPage(v), `draft ${i}`);
    for (const l of buildPage(v).boxes.find(b => b.name === 'body')!.content.split('\n')) seen.add(l.replace(/\s+[↑↓]$/, ''));
    lens.up();
  }
  for (const l of all) assert.ok(seen.has(l), `draft line seen: ${l}`);
  lens.doubleTap();
  lens.dispose();
});

test('the lens is blank only when asked to be quiet: every other screen, its moment between screens too, shows something', async () => {
  const { hub, lens } = setup();
  const check = (what: string) => {
    const v = lens.view();
    assert.equal(v.kind === 'blank', lens.screenKind === 'quiet', `${what}: ${v.kind} on ${lens.screenKind}`);
    if (v.kind !== 'blank') assert.ok(buildPage(v).boxes.length > 0, `${what}: an empty page`);
  };
  check('glance');
  for (const id of ['n', 'w', 'i']) { lens.open(id); check(`open ${id}`); lens.doubleTap(); check(`back from ${id}`); }
  // A finished reply read aloud, then a swipe down and back (the tour's frames 06 and 07).
  lens.open('i');
  lens.tap();
  check('reading aloud');
  await new Promise(r => setTimeout(r, 5));
  lens.down();
  check('scrolled while reading');
  lens.doubleTap();
  check('back while reading');
  // A session whose card goes away under it (ended on the Mac) shows the glance meanwhile.
  lens.open('w');
  hub.snapshot = { ...hub.snapshot, sessions: hub.snapshot.sessions.filter(x => x.id !== 'w') };
  check('its session gone');
  lens.showPower(); check('battery');
  lens.doubleTap();
  lens.doubleTap(); check('quiet');
  lens.tap(); check('woken');
  lens.dispose();
});
