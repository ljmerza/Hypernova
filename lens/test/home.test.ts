// The lens with new sessions from the glasses (Nebula), pop-ups, the battery card, Stay awake, limits
// and Transmission: the gestures and words do what the README says.
import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { buildPage, type LensView } from '../src/lens/pages.ts';
import { gapJoin, partsOf } from '../src/core/font.ts';
import { LensController, type Services } from '../src/lens/controller.ts';
import type { HubEvent, HubLike } from '../src/data/hub.ts';
import { DemoSpeech } from '../src/data/speech.ts';
import { DEFAULT_SETTINGS, type Settings } from '../src/data/store.ts';
import type { VoiceOut } from '../src/data/voice.ts';
import { ApiError } from '../src/data/api.ts';
import { WORDS, type NewSessionChoice } from '../src/data/bridge.ts';
import type { Account, Agent, Approval, ApprovalAnswer, GlassesBattery, HnEvent, MacAwake, Provider, Session, Snapshot } from '../src/core/types.ts';

const MIN = 60_000, HOUR = 60 * MIN;

function makeHub(now = Date.now()) {
  const s = (id: string, projectName: string, state: Session['state'], extra: Partial<Session> = {}): Session => ({
    id, title: `${projectName} work`, cwd: `/p/${projectName}`, projectName, origin: 'terminal', state, model: null, permissionMode: 'default',
    createdAt: now - HOUR, lastActivityAt: now - MIN, lastText: 'Done.', activity: null, pendingApprovalIds: [], stoppedForAway: false, ...extra,
  });
  const ap: Approval = { id: 'ap1', sessionId: 'n', createdAt: now - MIN, expiresAt: now + 10 * MIN, kind: 'tool', toolName: 'Bash', summary: 'Bash: git push origin main',
    input: { command: 'git push origin main' }, reason: null, canSession: false, questions: null, plan: null };
  const calls: string[] = [];
  const listeners = new Set<(e: HubEvent) => void>();
  const turn: HnEvent[] = [
    { id: 'u', kind: 'user', ts: now - 300_000, text: 'fix it' },
    { id: 'a', kind: 'assistant', ts: now - 1000, text: 'Fixed the pairing test, and it waits for the camera now. Twelve tests pass.', streaming: false },
    { id: 'r', kind: 'result', ts: now, ok: true, durationMs: 300_000, costUsd: null, turns: 3, error: null },
  ];
  const hub: HubLike & {
    calls: string[]; snapshot: Snapshot; emit(e: HubEvent): void; eventsBy: Map<string, HnEvent[]>; status: HubLike['status'];
    promptError: ApiError | null; awakeOk: boolean;
  } = {
    calls, eventsBy: new Map(), promptError: null, awakeOk: true,
    snapshot: {
      mac: { id: 'm', name: 'Mac', daemonVersion: '0', battery: { percent: 62, charging: false, onAC: false, minutesLeft: 200 },
        awake: { mode: 'working', holding: true, lidClosedOk: false, backpackReady: true, until: null, reason: null } },
      usage: { fiveHour: { pct: 42, resetsAt: now + 2 * HOUR }, sevenDay: { pct: 20, resetsAt: now + 50 * HOUR }, source: 'official', observedAt: now },
      account: { state: 'ok', limit: null, since: null },
      sessions: [s('n', '0stems', 'needs_you', { pendingApprovalIds: ['ap1'], term: { id: 't1' } }), s('w', 'hypernova', 'working', { activity: 'Bash: ./gradlew build' }), s('i', 'hyperscribe', 'idle')],
      approvals: [ap],
      projects: [
        { path: '/p/hyperscribe', name: 'hyperscribe', lastActivityAt: now, sessionCount: 1 },
        { path: '/p/0stems', name: '0stems', lastActivityAt: now - 1, sessionCount: 1 },
        { path: '/p/Nebula', name: 'Nebula', lastActivityAt: now - 2, sessionCount: 0 },
      ],
    },
    status: 'live', problem: null, macName: 'Mac',
    events: (id: string) => hub.eventsBy.get(id) ?? turn,
    loadEvents: async (id: string) => hub.eventsBy.get(id) ?? turn,
    agents: () => [] as Agent[],
    loadAgents: async () => [],
    answer: async (id: string, a: ApprovalAnswer) => {
      calls.push(`answer ${id} ${JSON.stringify(a)}`);
      hub.snapshot = { ...hub.snapshot, approvals: hub.snapshot.approvals.filter(x => x.id !== id), sessions: hub.snapshot.sessions.map(x => (x.id === 'n' ? { ...x, state: 'idle', pendingApprovalIds: [] } : x)) };
      hub.emit({ t: 'change' });
    },
    prompt: async (id: string, text: string) => {
      calls.push(`prompt ${id} ${text}`);
      if (hub.promptError) throw hub.promptError;
      return { queued: false };
    },
    newSession: async (path: string, text: string, choice?: NewSessionChoice | null) => { calls.push(`new ${path} ${text}${choice ? ` ${JSON.stringify(choice)}` : ''}`); return null; },
    newNebulaSession: async (name: string, text: string, choice?: NewSessionChoice | null) => {
      calls.push(`nebula ${name} ${text}${choice ? ` ${JSON.stringify(choice)}` : ''}`);
      const made = s(`s-${name}`, name, 'starting', { cwd: `/p/Nebula/${name}` });
      hub.snapshot = { ...hub.snapshot, sessions: [...hub.snapshot.sessions, made] };
      return made;
    },
    setAwake: async (mode: MacAwake['mode']) => {
      calls.push(`awake ${mode}`);
      if (!hub.awakeOk) throw new ApiError('missing', 'no', 404, 'not_found');
      const awake: MacAwake = { mode, holding: mode !== 'off', lidClosedOk: mode === 'backpack', backpackReady: true, until: null, reason: null };
      hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, awake } };
      return awake;
    },
    extendAwake: async () => {
      calls.push('extend');
      const cur = hub.snapshot.mac.awake!;
      const awake: MacAwake = { ...cur, until: (cur.until ?? 0) + 7200_000, extendable: false };
      hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, awake } };
      return awake;
    },
    interrupt: async (id: string) => { calls.push(`interrupt ${id}`); },
    continueSession: async (id: string) => { calls.push(`continue ${id}`); },
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

function setup(lines: string[] = [], patch: Partial<Settings> = {}) {
  const hub = makeHub();
  const speech = new DemoSpeech(lines);
  const spoken: string[] = [];
  /** The voice: `progress` is how far it has read (Transmission keeps the card in step). */
  const voice = { speaking: false, locked: false, progress: () => voice.at, at: 0, onChange() {}, unlock() {}, stop() { voice.speaking = false; }, blip() {},
    speak: async (t: string) => { spoken.push(t); voice.speaking = true; await new Promise(r => { voice.done = r; }); voice.speaking = false; return 'mac'; }, done: (_: unknown) => {} };
  let settings: Settings = { ...DEFAULT_SETTINGS, ...patch };
  const glasses: { battery: GlassesBattery | null } = { battery: { percent: 84, charging: false } };
  const services: Services = {
    hub: () => hub, speech: () => speech, voice: voice as unknown as VoiceOut, settings: () => settings, updateSettings: p => { settings = { ...settings, ...p }; },
    paired: () => true, showOnPhone: () => {}, shotReady: () => {}, glassesBattery: () => glasses.battery,
  };
  const lens = new LensController(services);
  hub.subscribe(e => lens.hubEvent(e));
  hub.emit({ t: 'change' });
  return { hub, lens, spoken, speech, voice, glasses, set: (p: Partial<Settings>) => { settings = { ...settings, ...p }; } };
}

const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
const head = (v: LensView) => ('head' in v ? v.head : v.kind);
const talk = (v: LensView) => { assert.equal(v.kind, 'talk'); return v as Extract<LensView, { kind: 'talk' }>; };
function change(hub: ReturnType<typeof makeHub>, p: Partial<Omit<Snapshot, 'mac'>> & { mac?: Partial<Snapshot['mac']> }): void {
  hub.snapshot = { ...hub.snapshot, ...p, mac: { ...hub.snapshot.mac, ...(p.mac ?? {}) } };
  hub.emit({ t: 'change' });
}
const NO_NEEDS = (hub: ReturnType<typeof makeHub>) => change(hub, { approvals: [], sessions: hub.snapshot.sessions.map(x => (x.id === 'n' ? { ...x, state: 'idle', pendingApprovalIds: [] } : x)) });

// ------------------------------------------------------------------ new sessions from the glasses

test('a hold on the main screen talks to what needs you; a swipe makes it a new session in Nebula, named from what was said', async () => {
  const { hub, lens } = setup(['build a landing page for my bakery']);
  const g = lens.view();
  assert.deepEqual(g.kind === 'glance' && g.hint, ['tap answer', 'hold talk to 0stems']);
  await lens.hold();
  assert.deepEqual(talk(lens.view()).right, ['to 0stems', 'needs you']);
  await lens.release();
  assert.deepEqual(talk(lens.view()).head, ['TO', '0stems', 'NEEDS YOU']);
  lens.down();
  const v = talk(lens.view());
  assert.deepEqual(v.head, ['NEW', 'Nebula/landing-page-bakery']);
  assert.equal(partsOf(v.hint)[0], 'tap make its folder & send');
  lens.up();
  assert.deepEqual(talk(lens.view()).head, ['TO', '0stems', 'NEEDS YOU']);
  lens.down();
  // Nothing is made on the Mac before the tap.
  assert.deepEqual(hub.calls, []);
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['nebula landing-page-bakery build a landing page for my bakery']);
  assert.deepEqual(talk(lens.view()).head, ['NEW PROJECT', 'landing-page-bakery']);
  lens.dispose();
});

test('with nothing that needs you, a hold starts a new session in Nebula; naming a project still wins', async () => {
  const { hub, lens } = setup(['a weather bot for my telegram', 'in hyperscribe, run the tests']);
  NO_NEEDS(hub);
  const g = lens.view();
  assert.equal(g.kind === 'glance' && partsOf(g.hint).at(-1), 'hold new session');
  await lens.hold();
  assert.deepEqual(talk(lens.view()).right, ['new session']);
  await lens.release();
  assert.deepEqual(talk(lens.view()).head, ['NEW', 'Nebula/weather-bot-telegram']);
  lens.doubleTap();
  await lens.hold();
  await lens.release();
  assert.deepEqual(talk(lens.view()).head, ['TO', 'hyperscribe']);
  lens.dispose();
});

test('inside a session a hold talks to it', async () => {
  const { lens } = setup(['and the widget too']);
  lens.down();                                     // hypernova, working
  lens.tap();
  assert.equal(lens.view().kind, 'live');
  await lens.hold();
  await lens.release();
  assert.deepEqual(talk(lens.view()).head, ['TO', 'hypernova']);
  lens.dispose();
});

test('said to a session that asks first: its card comes up, with what was said as the reason a double tap sends', async () => {
  const { hub, lens } = setup(['use a feature branch instead']);
  hub.promptError = new ApiError('server', 'Claude is waiting for your answer above.', 409, 'needs_answer');
  await lens.hold();
  await lens.release();
  lens.tap();
  await wait(5);
  const card = lens.view();
  assert.deepEqual(head(card), ['NEEDS YOU', '0stems']);
  assert.deepEqual(card.kind === 'card' && card.hint, ['It asks first: double tap denies with what you said']);
  await wait(750);
  lens.doubleTap();
  await wait(5);
  assert.deepEqual(hub.calls.at(-1), 'answer ap1 {"decision":"deny","message":"use a feature branch instead"}');
  lens.dispose();
});

test('"+ New session" on the glance opens the project picker; a pick listens, hands-free, for a new session there', async () => {
  const { hub, lens } = setup(['add dark mode']);
  for (let i = 0; i < 4; i++) lens.down();
  const g = lens.view();
  assert.equal(g.kind === 'glance' && g.newRow?.focused, true);
  assert.match(buildPage(g).boxes.find(b => b.name === 'new')!.content, /^\+ New session$/);
  lens.tap();
  const p = lens.view();
  assert.equal(p.kind, 'choice');
  // A new project in Nebula first, then the projects, newest first; Nebula itself isn't one.
  assert.deepEqual(p.kind === 'choice' && p.options.map(o => o.label), [['+ New project', 'its own folder in Nebula'], ['hyperscribe'], ['0stems']]);
  lens.down();
  lens.tap();
  await wait(5);
  assert.deepEqual(talk(lens.view()).head, ['LISTENING']);
  assert.deepEqual(talk(lens.view()).right, ['new session in hyperscribe']);
  await lens.release();
  assert.deepEqual(talk(lens.view()).head, ['NEW SESSION', 'hyperscribe']);
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['new /p/hyperscribe add dark mode']);
  lens.dispose();
});

test('"+ New project": its folder is named from what you say, and made only by the tap that sends it', async () => {
  const { hub, lens } = setup(['a recipe app with shopping lists']);
  lens.showPicker();
  lens.tap();
  await wait(5);
  assert.deepEqual(talk(lens.view()).right, ['new session']);
  await lens.release();
  assert.deepEqual(talk(lens.view()).head, ['NEW', 'Nebula/recipe-app-shopping-lists']);
  assert.deepEqual(hub.calls, []);
  lens.doubleTap();                                // discard: back to the picker, nothing made
  assert.equal(lens.view().kind, 'choice');
  assert.deepEqual(hub.calls, []);
  lens.dispose();
});

// ------------------------------------------------------------------ pop-ups

test('what needs you pops up over another screen; taps are guarded a moment; tap opens it', async () => {
  const { hub, lens } = setup();
  lens.down();
  lens.tap();                                      // hypernova's live view
  const a: Approval = { ...hub.snapshot.approvals[0], id: 'ap2', sessionId: 'i', input: { command: 'rm -rf build' }, summary: 'Bash: rm -rf build' };
  hub.snapshot = { ...hub.snapshot, approvals: [...hub.snapshot.approvals, a] };
  hub.emit({ t: 'approval', approval: a });
  const v = lens.view();
  assert.equal(v.kind, 'live');
  // How soon it's denied by itself, at the end of its line, as the phone's band says it.
  const { detailRight, ...band } = v.band!;
  assert.deepEqual(band, { head: ['NEEDS YOU', 'hyperscribe'], right: '', detail: 'wants to delete build', line: 'hyperscribe needs you: wants to delete build' });
  assert.match(detailRight ?? '', /^auto-deny in (9|10)m$/);
  const page = buildPage(v);
  assert.match(page.boxes.find(b => b.name === 'band')!.content, /^NEEDS YOU {3}hyperscribe +tap open {5}swipe away\nwants to delete build +auto-deny in (9|10)m$/);
  lens.tap();                                      // too soon: meant for what was there
  assert.equal(lens.view().kind, 'live');
  await wait(750);
  lens.tap();
  assert.deepEqual(head(lens.view()), ['NEEDS YOU', 'hyperscribe']);
  assert.equal(lens.view().band ?? null, null);
  lens.dispose();
});

test('a swipe puts the band away; pop-ups wait while you talk, and show after', async () => {
  const { hub, lens } = setup(['hmm']);
  lens.down();
  lens.tap();
  const a: Approval = { ...hub.snapshot.approvals[0], id: 'ap2', sessionId: 'i' };
  hub.snapshot = { ...hub.snapshot, approvals: [...hub.snapshot.approvals, a] };
  hub.emit({ t: 'approval', approval: a });
  assert.ok(lens.view().band);
  lens.down();
  assert.equal(lens.view().band ?? null, null);
  assert.equal(lens.view().kind, 'live', 'the swipe moved nothing else');
  // Talking: a new one waits.
  await lens.hold();
  const b: Approval = { ...a, id: 'ap3' };
  hub.snapshot = { ...hub.snapshot, approvals: [...hub.snapshot.approvals, b] };
  hub.emit({ t: 'approval', approval: b });
  assert.equal(lens.view().band ?? null, null);
  lens.doubleTap();                                // back from talking
  assert.deepEqual(lens.view().band?.head, ['NEEDS YOU', 'hyperscribe']);
  lens.dispose();
});

test('a finished turn elsewhere pops up when that switch is on, and fades about six seconds after it shows', async () => {
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1_800_000_000_000 });
  // Its facts load first (a promise), to tell done from stopped.
  const settle = () => new Promise(r => setImmediate(r));
  try {
    const { hub, lens, set } = setup();
    lens.open('n');                                // 0stems' card
    hub.emit({ t: 'finished', sessionId: 'i' });
    await settle();
    assert.equal(lens.view().band ?? null, null, 'off by default');
    set({ popups: { ...DEFAULT_SETTINGS.popups, done: true } });
    hub.emit({ t: 'finished', sessionId: 'i' });
    await settle();
    assert.deepEqual(lens.view().band, { head: ['DONE', 'hyperscribe'], right: '', detail: 'Fixed the pairing test, and it waits for the camera now.', line: 'hyperscribe finished: Fixed the pairing test, and it waits for the camera now.' });
    mock.timers.tick(5000);
    assert.ok(lens.view().band);
    mock.timers.tick(1100);
    assert.equal(lens.view().band ?? null, null);
    lens.dispose();
  } finally { mock.timers.reset(); }
});

test('several pop-ups merge into one band; the Mac’s battery at 20% pops up once per discharge', async () => {
  const { hub, lens } = setup();
  lens.down();
  lens.tap();
  change(hub, { mac: { battery: { percent: 20, charging: false, onAC: false, minutesLeft: 30 } } });
  const a: Approval = { ...hub.snapshot.approvals[0], id: 'ap2', sessionId: 'i' };
  hub.snapshot = { ...hub.snapshot, approvals: [...hub.snapshot.approvals, a] };
  hub.emit({ t: 'approval', approval: a });
  const { detailRight: _soon, ...merged } = lens.view().band!;
  assert.deepEqual(merged, { head: ['NEEDS YOU', 'hyperscribe'], right: '+1', detail: 'wants to push to main, and Mac at 20%', line: 'hyperscribe needs you: wants to push to main, and Mac at 20%' });
  lens.up();
  change(hub, { mac: { battery: { percent: 19, charging: false, onAC: false, minutesLeft: 28 } } });
  assert.equal(lens.view().band ?? null, null, 'once per discharge');
  change(hub, { mac: { battery: { percent: 30, charging: true, onAC: true, minutesLeft: 60 } } });
  change(hub, { mac: { battery: { percent: 20, charging: false, onAC: false, minutesLeft: 30 } } });
  assert.deepEqual(lens.view().band?.head, ['MAC BATTERY', '20%']);
  await wait(750);
  lens.tap();                                      // opens the battery card
  assert.equal(lens.view().kind, 'power');
  lens.dispose();
});

test('the Mac about to sleep pops up; its band names why', () => {
  const { hub, lens } = setup();
  hub.emit({ t: 'sleep_soon', inSeconds: 120, reason: 'battery' });
  assert.deepEqual(lens.view().band, { head: ['MAC SLEEPS IN 2 MIN'], right: '', detail: 'Its battery is low: plug it in to keep it awake', line: 'Mac sleeps soon: Its battery is low: plug it in to keep it awake' });
  lens.dispose();
});

test('Backpack ending: a pop-up offers +2 h; a tap extends it, and so does "extend"; Eclipse lets it through', async () => {
  const { hub, lens, set } = setup(['extend']);
  const end = Date.now() + 10 * 60_000;
  const backpack = (until: number): MacAwake => ({ mode: 'backpack', holding: true, lidClosedOk: true, backpackReady: true, until, reason: null, extendable: true });
  set({ eclipse: true });
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, awake: backpack(end) } };
  hub.emit({ t: 'backpack_ending', inSeconds: 600, until: end, extendBy: 7200 });
  assert.deepEqual(lens.view().band, { head: ['BACKPACK ENDS IN 10 MIN'], right: '', detail: 'Tap or say “extend” for 2 more hours', line: 'Backpack ends soon: Tap or say “extend” for 2 more hours' });
  await wait(750);
  lens.tap();
  await settle();
  assert.ok(hub.calls.includes('extend'), 'a tap on the band extends');
  assert.equal(lens.view().band ?? null, null, 'and the offer goes');
  // By voice, with the offer still up.
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, awake: backpack(end) } };
  hub.emit({ t: 'backpack_ending', inSeconds: 600, until: end, extendBy: 7200 });
  assert.deepEqual(lens.view().band?.head, ['BACKPACK ENDS IN 10 MIN']);
  await lens.hold();
  await lens.release();
  await settle();
  assert.equal(hub.calls.filter(c => c === 'extend').length, 2, 'saying “extend” does it too');
  assert.equal(lens.view().band ?? null, null);
  lens.dispose();
});

test('Backpack extended elsewhere (the Mac’s own button): the glasses’ offer goes by itself', () => {
  const { hub, lens } = setup();
  const end = Date.now() + 10 * 60_000;
  const awake = (until: number): MacAwake => ({ mode: 'backpack', holding: true, lidClosedOk: true, backpackReady: true, until, reason: null, extendable: true });
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, awake: awake(end) } };
  hub.emit({ t: 'backpack_ending', inSeconds: 600, until: end, extendBy: 7200 });
  assert.deepEqual(lens.view().band?.head, ['BACKPACK ENDS IN 10 MIN']);
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, awake: { ...awake(end + 7200_000), extendable: false } } };
  hub.emit({ t: 'change' });
  assert.equal(lens.view().band ?? null, null);
  lens.dispose();
});

test('when another app keeps the Mac awake, the band says so instead of promising sleep', () => {
  const { hub, lens } = setup();
  hub.emit({ t: 'sleep_soon', inSeconds: 120, reason: 'battery', percent: 15, stillAwake: true, otherApp: 'Amphetamine' });
  assert.deepEqual(lens.view().band, { head: ['HYPERNOVA LETS GO IN 2 MIN'], right: '', detail: 'Battery 15%: Amphetamine still keeps the Mac awake', line: 'Amphetamine keeps the Mac awake: Battery 15%: Amphetamine still keeps the Mac awake' });
  lens.dispose();
});

test('a limit hit and lifted pops up once each (when on), and the sun lights up again', () => {
  const { hub, lens, set } = setup();
  set({ popups: { ...DEFAULT_SETTINGS.popups, limit: true } });
  NO_NEEDS(hub);
  // Half a minute past the hour, so the millisecond the test takes doesn't turn "1h" into "59m".
  const now = Date.now(), back = now + HOUR + 30_000;
  const limited: Account = { state: 'limited', since: now, limit: { window: 'five_hour', label: 'session limit', resetsAt: back, autoResumeAt: null, usingCredits: false } };
  change(hub, { account: limited, usage: { ...hub.snapshot.usage, fiveHour: { pct: 100, resetsAt: back } } });
  let v = lens.view();
  assert.deepEqual(v.band?.head, ['SESSION LIMIT REACHED']);
  assert.match(v.band?.detail ?? '', /^Claude is back at \d/);
  // Spent: until when it lifts, at the time of day.
  assert.match(v.kind === 'glance' ? partsOf(v.right)[0] : '', /^limit until \d{1,2}:\d\d/);
  assert.equal(v.kind === 'glance' && v.orrery.sun?.mode, 'quiet');
  change(hub, { account: { state: 'ok', limit: null, since: null }, usage: { ...hub.snapshot.usage, fiveHour: { pct: 0, resetsAt: now + 5 * HOUR } } });
  v = lens.view();
  assert.deepEqual(v.band?.head, ['LIMIT LIFTED']);
  assert.equal(v.kind === 'glance' && v.orrery.sun?.mode, 'disk');
  assert.ok(v.kind === 'glance' && v.orrery.sun?.flashAt, 'the flash');
  lens.dispose();
});

// ------------------------------------------------------------------ the battery card and Stay awake

test('"stay awake" keeps the Mac awake with the lid closed (Backpack); "let it sleep" undoes it, then turns it off', async () => {
  const { hub, lens } = setup(['stay awake', 'let it sleep', 'let it sleep']);
  for (let i = 0; i < 3; i++) { await lens.hold(); await lens.release(); await wait(5); }
  assert.deepEqual(hub.calls, ['awake backpack', 'awake working', 'awake off']);
  lens.showPower();
  const v = lens.view();
  assert.equal(v.kind === 'power' && v.awake, 'Sleeps as usual');
  lens.dispose();
});

test('the battery card shows Stay awake under the Mac; a Mac from before Stay awake says so', async () => {
  const { hub, lens } = setup(['stay awake']);
  lens.showPower();
  let v = lens.view();
  assert.equal(v.kind === 'power' && v.awake, 'Awake while working');
  const page = buildPage(v);
  assert.match(page.boxes.find(b => b.name === 'awake')!.content, /^Awake while working$/);
  change(hub, { mac: { awake: undefined } });
  v = lens.view();
  assert.equal(v.kind === 'power' && v.awake, null);
  lens.doubleTap();
  await lens.hold();
  await lens.release();
  await wait(5);
  assert.deepEqual(lens.view().kind === 'glance' && (lens.view() as Extract<LensView, { kind: 'glance' }>).hint, ['This Mac’s Hypernova can’t keep it awake yet']);
  lens.dispose();
});

// ------------------------------------------------------------------ limits, as on the phone

test('a paused session stays on the glance with its limit; queued prompts show; its card says PAUSED', () => {
  const { hub, lens } = setup();
  const now = Date.now();
  change(hub, {
    account: { state: 'limited', since: now, limit: { window: 'five_hour', label: 'session limit', resetsAt: now + HOUR, autoResumeAt: now + HOUR, usingCredits: false } },
    sessions: hub.snapshot.sessions.map(x => (x.id === 'i' ? { ...x, lastActivityAt: now - 3 * HOUR, blocked: { kind: 'limit' as const, text: '', resetsAt: now + HOUR, autoResumeAt: now + HOUR } }
      : x.id === 'w' ? { ...x, queued: 2 } : x)),
  });
  const g = lens.view() as Extract<LensView, { kind: 'glance' }>;
  const row = g.rows.find(r => r.project === 'hyperscribe')!;
  assert.match(row.detail, /^session limit until \d{1,2}:\d\d( [AP]M)?, then carries on$/);
  assert.deepEqual(g.rows.find(r => r.project === 'hypernova')!.tag, ['2 queued']);
  lens.open('i');
  const card = lens.view();
  assert.deepEqual(head(card), ['PAUSED', 'hyperscribe']);
  assert.match(card.kind === 'card' ? card.facts[0] : '', /^session limit until \d{1,2}:\d\d( [AP]M)?, then carries on$/);
  assert.match(lens.statusText(), /hyperscribe is paused by the session limit, and carries on by itself in (1 hour|59 minutes)\./);
  lens.dispose();
});

test('signed out, the sun is eclipsed and the corner says why; the week spent, it’s the low state', () => {
  const { hub, lens } = setup();
  change(hub, { account: { state: 'logged_out', limit: null, since: Date.now() }, usage: { ...hub.snapshot.usage, source: 'stale' } });
  let g = lens.view() as Extract<LensView, { kind: 'glance' }>;
  assert.deepEqual(g.right, ['signed out']);
  assert.equal(g.orrery.sun?.mode, 'eclipse');
  assert.match(lens.statusText(), /Claude Code is signed out on your Mac/);
  change(hub, { account: { state: 'ok', limit: null, since: null }, usage: { ...hub.snapshot.usage, source: 'official', sevenDay: { pct: 100, resetsAt: Date.now() + 30 * HOUR } } });
  g = lens.view() as Extract<LensView, { kind: 'glance' }>;
  assert.equal(g.orrery.sun?.mode, 'low');
  // A day and more away: its weekday, as the phone says it.
  assert.match(partsOf(g.right)[0], /^week limit until (Mon|Tue|Wed|Thu|Fri|Sat|Sun) \d{1,2}:\d\d/);
  lens.dispose();
});

test('the week’s low state outranks the 5-hour limit, and its reset lights the disk with the outburst', () => {
  const { hub, lens } = setup();
  const now = Date.now();
  change(hub, { account: { state: 'ok', limit: null, since: null }, usage: { ...hub.snapshot.usage, source: 'official', fiveHour: { pct: 100, resetsAt: now + HOUR }, sevenDay: { pct: 100, resetsAt: now + 30 * HOUR } } });
  let g = lens.view() as Extract<LensView, { kind: 'glance' }>;
  assert.equal(g.orrery.sun?.mode, 'low', 'both limits hold: the week’s state');
  assert.equal(g.orrery.sun?.flashAt, undefined, 'nothing flares going into the low state');
  change(hub, { usage: { ...hub.snapshot.usage, fiveHour: { pct: 2, resetsAt: now + 5 * HOUR }, sevenDay: { pct: 1, resetsAt: now + 7 * 24 * HOUR } } });
  g = lens.view() as Extract<LensView, { kind: 'glance' }>;
  assert.equal(g.orrery.sun?.mode, 'disk');
  assert.ok(g.orrery.sun?.flashAt, 'the outburst');
  lens.dispose();
});

// ------------------------------------------------------------------ Transmission

test('text that arrives like speech: a reply a few words at a time from its start; reading aloud off, a tap shows the rest', () => {
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1_800_000_000_000 });
  try {
    const { lens } = setup([], { textArrives: 'speech', pace: 180, readAloud: false });
    lens.open('i');
    const body = () => { const v = lens.view(); assert.equal(v.kind, 'card'); return (v as Extract<LensView, { kind: 'card' }>).body.join(' ').trim(); };
    assert.equal(body(), 'Fixed the pairing');
    const first = buildPage(lens.view());
    mock.timers.tick(1300);
    assert.equal(body(), 'Fixed the pairing test,');
    // The card keeps its layout while words arrive: each step is a text update.
    assert.equal(JSON.stringify(buildPage(lens.view()).boxes.map(b => [b.name, b.y, b.h])), JSON.stringify(first.boxes.map(b => [b.name, b.y, b.h])));
    const v = lens.view();
    assert.deepEqual(v.kind === 'card' && v.hint, ['tap show all', 'swipe scroll', 'double tap back']);
    lens.tap();
    assert.equal(body(), 'Fixed the pairing test, and it waits for the camera now. Twelve tests pass.');
    // Seen whole once, it stays whole.
    lens.doubleTap();
    lens.open('i');
    assert.equal(body(), 'Fixed the pairing test, and it waits for the camera now. Twelve tests pass.');
    lens.dispose();
  } finally { mock.timers.reset(); }
});

test('in the live view, what was there shows at once; what Claude writes next arrives like speech', () => {
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1_800_000_000_000 });
  try {
    const { hub, lens } = setup([], { textArrives: 'speech', pace: 180 });
    const now = Date.now();
    hub.eventsBy.set('w', [
      { id: 'u1', kind: 'user', ts: now - 9000, text: 'draw comets' },
      { id: 'a1', kind: 'assistant', ts: now - 5000, text: 'Starting on the comets.', streaming: false },
    ]);
    lens.open('w');
    const out = () => { const v = lens.view(); assert.equal(v.kind, 'live'); return (v as Extract<LensView, { kind: 'live' }>).output.join(' '); };
    assert.match(out(), /Starting on the comets\.$/);
    const e: HnEvent = { id: 'a2', kind: 'assistant', ts: now + 100, text: 'The build passes. Next, a test for the fade.', streaming: false };
    hub.eventsBy.set('w', [...hub.eventsBy.get('w')!, e]);
    hub.emit({ t: 'event', sessionId: 'w', event: e });
    assert.match(out(), /Starting on the comets\. The build passes\.$/);
    mock.timers.tick(1600);
    assert.match(out(), /The build passes\. Next,$/);
    mock.timers.tick(3400);
    assert.match(out(), /Next, a test for the fade\.$/);
    lens.dispose();
  } finally { mock.timers.reset(); }
});

test('read aloud while text arrives like speech: one tap reads it from its start, the card follows the voice; a tap stops it', async () => {
  const { lens, voice, spoken } = setup([], { textArrives: 'speech' });
  lens.open('i');
  const first = lens.view();
  // Still arriving: the first tap reads aloud, with no wait for the whole text to show.
  assert.deepEqual(first.kind === 'card' && first.hint, ['tap read aloud', 'swipe scroll', 'double tap back']);
  lens.tap();
  await wait(5);
  assert.equal(spoken.length, 1);
  assert.match(spoken[0], /^Fixed the pairing test/);
  voice.at = 0.35;
  let v = lens.view();
  // Reading aloud says so, steadily, in the glasses' own font; a tap stops it, a swipe still scrolls.
  assert.equal(v.wave, 'reading aloud');
  assert.deepEqual(v.kind === 'card' && v.hint, ['tap stop reading', 'swipe scroll', 'double tap back']);
  assert.match(buildPage(v).boxes.find(b => b.name === 'header')!.content, /^DONE {3}hyperscribe +reading aloud$/);
  // About a third read: the words up to there, cut at a word.
  assert.equal(v.kind === 'card' && v.body.join(' ').trim(), 'Fixed the pairing test,');
  voice.at = 1;
  v = lens.view();
  assert.match(v.kind === 'card' ? v.body.join(' ').trim() : '', /Twelve tests pass\.$/);
  lens.tap();
  assert.equal(voice.speaking, false);
  voice.done(null);
  lens.dispose();
});

test('a long reply arriving like speech flows through one text that scrolls: the window follows its newest line, a swipe back stops it, the end follows again', () => {
  mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1_800_000_000_000 });
  try {
    const { hub, lens } = setup([], { textArrives: 'speech', pace: 180, readAloud: false });
    const now = Date.now();
    const sentences = Array.from({ length: 24 }, (_, i) => `Sentence ${i + 1} says a little more about the pairing test and the camera.`);
    const reply = `${sentences.slice(0, 12).join(' ')}\n\n${sentences.slice(12).join(' ')}`;
    hub.eventsBy.set('i', [
      { id: 'u', kind: 'user', ts: now - 300_000, text: 'fix it' },
      { id: 'a', kind: 'assistant', ts: now - 1000, text: reply, streaming: false },
      { id: 'r', kind: 'result', ts: now, ok: true, durationMs: 300_000, costUsd: null, turns: 3, error: null },
    ]);
    lens.open('i');
    const card = () => { const v = lens.view(); assert.equal(v.kind, 'card'); return v as Extract<LensView, { kind: 'card' }>; };
    const layout = () => JSON.stringify(buildPage(lens.view()).boxes.map(b => [b.name, b.y, b.h]));
    // Time passes as on the glasses: the lens draws again as each step is due.
    const play = (ms: number) => { for (let t = 0; t < ms; t += 250) { mock.timers.tick(250); lens.view(); } };
    const first = card();
    const boxes = layout();
    assert.match(first.body[0], /^Sentence 1/);
    assert.equal(first.right, '', 'no page counter');
    // Words keep arriving past the first window, and the window follows the newest line down.
    play(40_000);
    let v = card();
    assert.doesNotMatch(v.body[0], /^Sentence 1 /, 'scrolled on with the words');
    assert.ok(v.body.at(-1)!.trim() && !v.body.at(-1)!.endsWith('↓'), 'the newest line is the last in view');
    assert.deepEqual(v.hint, ['tap show all', 'swipe scroll', 'double tap back']);
    assert.equal(layout(), boxes, 'every step is a text update: the boxes never move');
    // Back: the window stays where you put it while the words go on under it.
    lens.up();
    const back = card().body[0];
    play(5000);
    v = card();
    assert.equal(v.body[0], back);
    assert.ok(v.body.at(-1)!.endsWith('↓'), 'more has arrived under it');
    // Swiped on to the end: following again.
    for (let i = 0; i < 4; i++) lens.down();
    const caught = card().body.at(-1);
    play(5000);
    v = card();
    assert.ok(!v.body.at(-1)!.endsWith('↓') && v.body.at(-1) !== caught, 'following the newest line');
    // A tap shows the rest at once, under the window, which stays put to read on from there.
    const before = v.body[0];
    lens.tap();
    v = card();
    assert.equal(v.body[0], before);
    assert.ok(v.body.at(-1)!.endsWith('↓'), 'the rest is under it');
    for (let i = 0; i < 30; i++) lens.down();
    assert.match(card().body.join(' ').replace(/\s+/g, ' ').trim(), /Sentence 24 says a little more about the pairing test and the camera\.$/);
    assert.deepEqual(card().hint, ['double tap back', 'hold talk'], 'reading aloud is off here');
    assert.equal(layout(), boxes);
    lens.dispose();
  } finally { mock.timers.reset(); }
});

/** Lets the controller's promises (a send, a load) settle while the clock is mocked. */
const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

/**
 * A new session started from the glasses (Nebula), with Like speech: sent, its live view open, and
 * the session as the Mac then has it, at work with `said` so far (null: not yet).
 */
async function newFromGlasses(prompt: string) {
  const { hub, lens, speech } = setup([], { textArrives: 'speech', pace: 180 });
  NO_NEEDS(hub);
  speech.next(prompt);
  await lens.hold();
  await lens.release();
  lens.tap();
  await settle();
  const id = hub.snapshot.sessions.at(-1)!.id;
  assert.match(hub.calls.join('\n'), /^nebula /m);
  assert.equal(partsOf(talk(lens.view()).head)[0], 'NEW PROJECT');
  mock.timers.tick(1400);
  assert.equal(lens.view().kind, 'live', 'after a new session is sent, its live view opens (owner, 2026-10-16), not the glance');
  return { hub, lens, id };
}

test('after a new session from the glasses its live view is open, and double tap goes back to the glance', async () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: 1_800_000_000_000 });
  try {
    const { lens, id } = await newFromGlasses('build a landing page for my bakery');
    const live = lens.view() as Extract<LensView, { kind: 'live' }>;
    assert.equal(live.kind, 'live');
    assert.ok(id);
    lens.doubleTap();
    assert.equal(lens.view().kind, 'glance');
    lens.dispose();
  } finally { mock.timers.reset(); }
});

const LONG_REPLY = Array.from({ length: 14 }, (_, i) => `Step ${i + 1} of the bakery page is done, with its menu and opening hours.`).join(' ');

test('a new session from the glasses: its reply lands on the result card and arrives like speech from its start, the window following it', async () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: 1_800_000_000_000 });
  try {
    const { hub, lens, id } = await newFromGlasses('build a landing page for my bakery');
    const now = Date.now();
    hub.eventsBy.set(id, [
      { id: 'nu', kind: 'user', ts: now - 60_000, text: 'build a landing page for my bakery' },
      { id: 'na', kind: 'assistant', ts: now - 1000, text: LONG_REPLY, streaming: false },
      { id: 'nr', kind: 'result', ts: now, ok: true, durationMs: 60_000, costUsd: null, turns: 3, error: null },
    ]);
    change(hub, { sessions: hub.snapshot.sessions.map(x => (x.id === id ? { ...x, state: 'idle', lastText: LONG_REPLY } : x)) });
    hub.emit({ t: 'finished', sessionId: id });
    await settle();
    const card = () => { const v = lens.view(); assert.equal(v.kind, 'card'); return v as Extract<LensView, { kind: 'card' }>; };
    assert.deepEqual(card().head, ['DONE', 'landing-page-bakery']);
    assert.equal(card().body.join(' ').trim(), 'Step 1 of', 'only the first words, not the whole reply at once');
    for (let t = 0; t < 30_000; t += 250) { mock.timers.tick(250); lens.view(); }
    const v = card();
    assert.doesNotMatch(v.body[0], /^Step 1 of/, 'words came past the first window, and the window followed them');
    assert.ok(!v.body.at(-1)!.endsWith('↓'), 'the newest line is the last in view');
    // The rest arrives in its own time (a tap would read it aloud).
    for (let t = 0; t < 120_000; t += 250) { mock.timers.tick(250); lens.view(); }
    for (let i = 0; i < 20; i++) lens.down();
    assert.match(card().body.join(' ').replace(/\s+/g, ' '), /Step 14 of the bakery page is done, with its menu and opening hours\.\s*$/);
    lens.dispose();
  } finally { mock.timers.reset(); }
});

test('a new session from the glasses, its live view opened after Claude’s first words landed: they arrive like speech, not all at once', async () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: 1_800_000_000_000 });
  try {
    const { hub, lens, id } = await newFromGlasses('build a landing page for my bakery');
    const now = Date.now();
    const words = 'Starting on the bakery page. First the menu, then the opening hours, then a map.';
    hub.eventsBy.set(id, [
      { id: 'nu', kind: 'user', ts: now - 3000, text: 'build a landing page for my bakery' },
      { id: 'na', kind: 'assistant', ts: now - 500, text: words, streaming: false },
    ]);
    change(hub, { sessions: hub.snapshot.sessions.map(x => (x.id === id ? { ...x, state: 'working' } : x)) });
    lens.open(id);
    const out = () => { const v = lens.view(); assert.equal(v.kind, 'live'); return (v as Extract<LensView, { kind: 'live' }>).output.join(' ').replace(/\s+/g, ' '); };
    assert.doesNotMatch(out(), /then a map\.$/, 'not shown whole at first: you haven’t seen them');
    assert.match(out(), /Starting on/);
    for (let t = 0; t < 8000; t += 250) { mock.timers.tick(250); lens.view(); }
    assert.match(out(), /then a map\.$/);
    // A session's old backlog, opened the same way, still shows at once.
    lens.doubleTap();
    hub.eventsBy.set('w', [
      { id: 'u1', kind: 'user', ts: now - 9000, text: 'draw comets' },
      { id: 'a1', kind: 'assistant', ts: now - 5000, text: 'Starting on the comets, then the tails.', streaming: false },
    ]);
    lens.open('w');
    assert.match(lens.view().kind === 'live' ? (lens.view() as Extract<LensView, { kind: 'live' }>).output.join(' ') : '', /then the tails\.$/);
    lens.dispose();
  } finally { mock.timers.reset(); }
});

test('a reply watched arriving in the live view carries on in the result card where it was, never from its first word again', async () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: 1_800_000_000_000 });
  try {
    const { hub, lens } = setup([], { textArrives: 'speech', pace: 180 });
    NO_NEEDS(hub);
    const now = Date.now();
    hub.eventsBy.set('w', [{ id: 'u1', kind: 'user', ts: now - 9000, text: 'draw comets' }]);
    lens.open('w');
    assert.equal(lens.view().kind, 'live');
    const e: HnEvent = { id: 'a2', kind: 'assistant', ts: now + 100, text: 'The comets are drawn and the build passes. Their tails fade as they leave the sun, and a test checks the fade at three distances.', streaming: false };
    hub.eventsBy.set('w', [...hub.eventsBy.get('w')!, e]);
    hub.emit({ t: 'event', sessionId: 'w', event: e });
    for (let t = 0; t < 3000; t += 250) { mock.timers.tick(250); lens.view(); }
    const live = lens.view() as Extract<LensView, { kind: 'live' }>;
    const seen = live.output.join(' ').replace(/\s+/g, ' ').trim();
    assert.match(seen, /The comets are drawn and the build passes\./);
    assert.doesNotMatch(seen, /three distances\.$/, 'still arriving');
    // The turn ends: the card comes up with what was already shown, and goes on from there.
    hub.eventsBy.set('w', [...hub.eventsBy.get('w')!, { id: 'r2', kind: 'result', ts: now + 3200, ok: true, durationMs: 9000, costUsd: null, turns: 1, error: null }]);
    change(hub, { sessions: hub.snapshot.sessions.map(x => (x.id === 'w' ? { ...x, state: 'idle', activity: null, lastText: e.text } : x)) });
    hub.emit({ t: 'finished', sessionId: 'w' });
    await settle();
    const v = lens.view();
    assert.equal(v.kind, 'card');
    const body = (v as Extract<LensView, { kind: 'card' }>).body.join(' ').replace(/\s+/g, ' ').trim();
    assert.ok(body.length >= 'The comets are drawn and the build passes.'.length && body.startsWith('The comets are drawn and the build passes.'), `carried on: "${body}"`);
    for (let t = 0; t < 8000; t += 250) { mock.timers.tick(250); lens.view(); }
    assert.match((lens.view() as Extract<LensView, { kind: 'card' }>).body.join(' ').replace(/\s+/g, ' '), /three distances\.\s*$/);
    lens.dispose();
  } finally { mock.timers.reset(); }
});

test('All at once (the default): nothing is paced', () => {
  const { lens } = setup();
  lens.open('i');
  const v = lens.view();
  assert.equal(v.kind === 'card' && v.body.join(' ').trim(), 'Fixed the pairing test, and it waits for the camera now. Twelve tests pass.');
  lens.dispose();
});

// ------------------------------------------------------------------ what the phone says, on the glasses too

test('the corner warns of the limit before the reset (the phone’s notch, the Mac’s arrow), and "status" says it', () => {
  const { hub, lens } = setup();
  change(hub, { usage: { ...hub.snapshot.usage, fiveHour: { pct: 62, resetsAt: Date.now() + 2 * HOUR }, rate: { fiveHour: 0.8, sevenDay: null, fiveHourEtaMs: 47 * MIN, sevenDayEtaMs: null, working: true } } });
  const g = lens.view() as Extract<LensView, { kind: 'glance' }>;
  assert.deepEqual(g.right, ['62%', 'limit in 47m']);
  assert.match(lens.statusText(), /At this pace you reach the limit in 47 minutes, before it resets\./);
  lens.dispose();
});

test('another provider’s trouble: the row says it in the phone’s words, and it pops up once per episode', () => {
  const { hub, lens, set } = setup();
  set({ popups: { ...DEFAULT_SETTINGS.popups, limit: true } });
  NO_NEEDS(hub);
  const or = (status: Provider['status']): Provider => ({ id: 'or', name: 'OpenRouter', builtIn: false, status });
  const on = (blocked: Session['blocked']) => hub.snapshot.sessions.map(x => (x.id === 'i' ? { ...x, providerId: 'or', blocked } : x));
  change(hub, { providers: [or(null)], sessions: on(null) });
  assert.equal(lens.view().band ?? null, null);
  const since = Date.now();
  change(hub, { providers: [or({ state: 'no_credit', since, resetsAt: null })], sessions: on({ kind: 'provider', text: 'API Error: 402', resetsAt: null, autoResumeAt: null }) });
  const v = lens.view() as Extract<LensView, { kind: 'glance' }>;
  assert.equal(v.rows.find(r => r.project === 'hyperscribe')!.detail, 'OpenRouter is out of credits');
  assert.deepEqual(v.band?.head, ['OUT OF CREDITS', 'OpenRouter']);
  assert.equal(v.band?.detail, '1 session paused. Add credits or continue on another provider.');
  lens.down();                                     // put away
  change(hub, { providers: [or({ state: 'no_credit', since, resetsAt: null })] });
  assert.equal(lens.view().band ?? null, null, 'the same episode: once');
  // Its card: why it can't reply; "status" says it too.
  lens.open('i');
  const card = lens.view();
  assert.deepEqual(head(card), ['CAN’T REPLY', 'hyperscribe']);
  assert.match(lens.statusText(), /hyperscribe can’t reply: OpenRouter is out of credits\./);
  // Credit again, with a session waiting on it: said once.
  change(hub, { providers: [or(null)] });
  assert.deepEqual(lens.view().band?.head, ['CREDIT AGAIN', 'OpenRouter']);
  lens.dispose();
});

test('stopped while you were away: the row and the card say so in the phone’s words, a band says PAUSED, a tap carries it on', async () => {
  const { hub, lens, set } = setup();
  set({ popups: { ...DEFAULT_SETTINGS.popups, stopped: true } });
  // Three hours old, and still on the glance: it waits for "continue", as a paused one does.
  change(hub, { sessions: hub.snapshot.sessions.map(x => (x.id === 'i' ? { ...x, stoppedForAway: true, lastActivityAt: Date.now() - 3 * HOUR } : x)) });
  const g = lens.view() as Extract<LensView, { kind: 'glance' }>;
  assert.equal(g.rows.find(r => r.project === 'hyperscribe')!.detail, 'paused after 10 minutes without an answer');
  assert.equal(g.rows.find(r => r.project === 'hyperscribe')!.detailShort, 'paused while you were away', 'for a row with no room');
  assert.match(lens.statusText(), /hyperscribe paused after 10 minutes without an answer\./);
  // Its turn ending while the lens shows something else: a band says PAUSED, not DONE.
  lens.open('w');
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  assert.deepEqual(lens.view().band?.head, ['PAUSED', 'hyperscribe']);
  assert.equal(lens.view().band?.detail, 'paused after 10 minutes without an answer');
  lens.open('i');
  const card = lens.view() as Extract<LensView, { kind: 'card' }>;
  assert.deepEqual(card.head, ['PAUSED', 'hyperscribe']);
  assert.equal(card.facts[0], 'paused after 10 minutes without an answer');
  assert.equal(partsOf(card.hint)[0], 'tap continue');
  lens.tap();
  await wait(5);
  assert.ok(hub.calls.includes('continue i'), hub.calls.join());
  lens.dispose();
});

test('a needs card says how soon it is denied by itself, and a mode that isn’t the usual one', () => {
  const { hub, lens } = setup();
  lens.open('n');
  let v = lens.view() as Extract<LensView, { kind: 'card' }>;
  assert.match(gapJoin(v.right), /^auto-deny in (9|10)m$/);
  change(hub, { sessions: hub.snapshot.sessions.map(x => (x.id === 'n' ? { ...x, permissionMode: 'acceptEdits' } : x.id === 'w' ? { ...x, permissionMode: 'plan' } : x)) });
  v = lens.view() as Extract<LensView, { kind: 'card' }>;
  assert.match(partsOf(v.right)[0], /^auto-deny in (9|10)m$/);
  assert.equal(partsOf(v.right)[1], 'accept edits');
  lens.doubleTap();                                // denied: the glance
  lens.open('w');
  const live = lens.view() as Extract<LensView, { kind: 'live' }>;
  assert.equal(partsOf(live.right).at(-1), 'plan');
  lens.dispose();
});

test('the Mac away: rows at rest as it last said them, the corner says since when; Hypernova stopped on the phone is a band', () => {
  const { hub, lens } = setup();
  const now = Date.now();
  change(hub, { sessions: hub.snapshot.sessions.map(x => (x.id === 'i' ? { ...x, lastActivityAt: now - 3 * MIN } : x)) });
  hub.status = 'offline';
  (hub as { offlineSince?: number }).offlineSince = now - 2 * MIN;
  hub.emit({ t: 'change' });
  const g = lens.view() as Extract<LensView, { kind: 'glance' }>;
  assert.match(partsOf(g.right)[0], /^offline since \d{1,2}:\d\d/);
  // Its age as it was when the Mac went away (1m), not counting on as if it were news.
  assert.equal(g.rows.find(r => r.project === 'hyperscribe')!.right, '1m');
  assert.ok(g.orrery.planets.every(p => p?.state === 'idle'), 'at rest');
  assert.equal(g.band ?? null, null, 'the Mac away: no band');
  // The app on the phone stopped answering: a band says so, and that it's to be opened.
  (hub as { problem: string | null }).problem = WORDS.no_app;
  hub.emit({ t: 'change' });
  const v = lens.view();
  assert.deepEqual(v.band?.head, ['HYPERNOVA STOPPED']);
  assert.equal(v.band?.detail, 'Open Hypernova on your phone');
  assert.match(buildPage(v).boxes.find(b => b.name === 'band')!.content, /^HYPERNOVA STOPPED\nOpen Hypernova on your phone$/);
  lens.dispose();
});

test('a new session from the glasses starts on the phone’s last provider and model, while the Mac has it; never a mode', async () => {
  const { hub, lens } = setup(['build a landing page for my bakery', 'write the release notes'], { newSession: { providerId: 'or', model: 'x-ai/grok-4' } });
  NO_NEEDS(hub);
  change(hub, { providers: [{ id: 'anthropic', name: 'Claude', builtIn: true, status: null }, { id: 'or', name: 'OpenRouter', builtIn: false, status: null }] });
  await lens.hold();
  await lens.release();
  lens.tap();
  await wait(5);
  assert.match(hub.calls.at(-1)!, /^nebula landing-page-bakery build a landing page for my bakery {"providerId":"or","model":"x-ai\/grok-4"}$/);
  // A provider this Mac doesn't have (any more): the Mac's defaults.
  change(hub, { providers: [{ id: 'anthropic', name: 'Claude', builtIn: true, status: null }] });
  lens.doubleTap();
  lens.scene('glance');
  await lens.hold();
  await lens.release();
  lens.tap();
  await wait(5);
  assert.match(hub.calls.at(-1)!, /^nebula release-notes write the release notes$/);
  lens.dispose();
});
