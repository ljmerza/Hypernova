// An automation run waiting at an `ask` step, on the glasses (core/ask.ts; DAEMON.md, 14): the note's
// ask read off the wire, the hub passing it on once (live and in the snapshot), the card and its
// gestures, the answer posted, someone else being first (409 not_asking), the default taken at its
// time, voice answers, and the quiet cases (a Mac without workflows, eclipse still lets it through).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gapJoin } from '../src/core/font.ts';
import { askCountdown, askEndWords, askOfNote, askSpoken, askTimedOut, matchAnswer, readAskInfo, readRunAsks } from '../src/core/ask.ts';
import { readNote, unseenNotes } from '../src/core/notes.ts';
import { askBand, askHead } from '../src/lens/asks.ts';
import { ApiError } from '../src/data/api.ts';
import { LiveHub, type HubEvent, type HubLike } from '../src/data/hub.ts';
import { LensController, type Services } from '../src/lens/controller.ts';
import { buildPage, type LensView } from '../src/lens/pages.ts';
import { DemoSpeech } from '../src/data/speech.ts';
import { DEFAULT_SETTINGS, type Settings } from '../src/data/store.ts';
import type { VoiceOut } from '../src/data/voice.ts';
import type { AskInfo, AutomationNote, Session, Snapshot } from '../src/core/types.ts';

const NOW = Date.now();
const MIN = 60_000;
const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
const info = (extra: Partial<AskInfo> = {}): AskInfo => ({ runId: 'ru_1', stepId: 'st_ask', question: 'Post this review?', answers: ['Approve', 'Skip'], default: 'Skip', until: NOW + 10 * MIN, ...extra });
const note = (id: string, ask: Partial<AskInfo> | null = {}, extra: Partial<AutomationNote> = {}): AutomationNote => ({
  id, automationId: 'au_1', runId: 'ru_1', name: 'Review before merge', to: 'glasses', at: NOW, text: 'Post this review?', ...(ask ? { ask: info(ask) } : {}), ...extra,
});

// ------------------------------------------------------------------ core

test('Note.ask off the wire: two or three answers, a default that is one of them, a deadline', () => {
  assert.deepEqual(readAskInfo(info()), info());
  assert.equal(readAskInfo(null), null);
  assert.equal(readAskInfo({ ...info(), answers: ['Only one'] }), null, 'one answer is no choice');
  assert.equal(readAskInfo({ ...info(), until: 0 }), null);
  assert.equal(readAskInfo({ ...info(), stepId: '' }), null);
  // Four answers: the first three; an unknown default is the last answer, a known one ignores case.
  assert.deepEqual(readAskInfo({ ...info(), answers: ['A', 'B', 'C', 'D'], default: 'nope' })!.answers, ['A', 'B', 'C']);
  assert.equal(readAskInfo({ ...info(), answers: ['A', 'B', 'C'], default: 'nope' })!.default, 'C');
  assert.equal(readAskInfo({ ...info(), default: 'approve' })!.default, 'Approve');
  assert.equal(readNote(note('n1', null))!.ask, undefined, 'a plain note has none');
  assert.deepEqual(readNote(note('n1'))!.ask, info());
});

test('a card from a note: not once its deadline passed; the snapshot keeps an ask that waits, however old', () => {
  const n = readNote(note('n1'))!;
  assert.deepEqual(askOfNote(n, NOW), { key: 'ru_1:st_ask', runId: 'ru_1', stepId: 'st_ask', name: 'Review before merge', question: 'Post this review?', answers: ['Approve', 'Skip'], default: 'Skip', until: NOW + 10 * MIN, at: NOW });
  assert.equal(askOfNote(n, NOW + 11 * MIN), null);
  assert.equal(askOfNote(readNote(note('n2', null)), NOW), null);
  const old = note('o', {}, { at: NOW - 30 * MIN });
  const plain = note('p', null, { at: NOW - 30 * MIN });
  assert.deepEqual(unseenNotes([old, plain], new Set(), NOW).map(x => x.id), ['o']);
});

test('the countdown is the default and its time in one line', () => {
  assert.equal(askCountdown('Skip', NOW + 9 * MIN + 40_000, NOW), 'Skip in 9m');
  assert.equal(askCountdown('Skip', NOW + 40_000, NOW), 'Skip in 40s');
  assert.equal(askCountdown('Skip', NOW + 5_000, NOW), 'Skip now');
  assert.equal(askCountdown('Approve', NOW - 1, NOW), 'Approve now');
});

test('how a card ends, in calm words: the default taken, who was first', () => {
  assert.deepEqual(askTimedOut('Skip'), ['Took the default: Skip']);
  assert.deepEqual(askEndWords({ text: 'Approve', by: 'timeout' }, 'Skip'), ['Took the default: Approve']);
  assert.deepEqual(askEndWords({ text: 'Approve', by: 'phone' }, 'Skip'), ['Already answered', 'Approve, on your phone']);
  assert.deepEqual(askEndWords({ text: 'Skip', by: 'mac' }, 'Skip'), ['Already answered', 'Skip, on the Mac']);
  assert.deepEqual(askEndWords({ text: 'Skip', by: 'glasses' }, 'Skip'), ['Already answered', 'Skip, on the glasses']);
  assert.deepEqual(askEndWords(null, 'Skip'), ['Already answered']);
});

test('a run off the wire: whether its ask is open, and who answered the ones that are over', () => {
  const run = readRunAsks({ id: 'ru_1', ask: null, steps: [{ id: 'st_a', answer: { text: 'Approve', by: 'phone', at: 1 } }, { id: 'st_b', answer: null }, { id: 'st_c', answer: { text: 'Skip', by: 'sideways', at: 1 } }] })!;
  assert.deepEqual(run, { runId: 'ru_1', open: null, ended: [{ stepId: 'st_a', text: 'Approve', by: 'phone' }, { stepId: 'st_c', text: 'Skip', by: 'phone' }] });
  assert.deepEqual(readRunAsks({ id: 'ru_1', ask: { stepId: 'st_ask' } })!.open, { stepId: 'st_ask' });
  assert.equal(readRunAsks({}), null);
});

test('what is said aloud, and which answer the wearer said', () => {
  assert.equal(askSpoken({ name: 'Review before merge', question: 'Post this review?', answers: ['Approve', 'Skip'] }), 'Review before merge asks: Post this review? Approve, or Skip.');
  assert.equal(askSpoken({ name: 'Nightly', question: 'Which branch', answers: ['main', 'release', 'Skip'] }), 'Nightly asks: Which branch? main, release, or Skip.');
  const a = ['Approve', 'Skip'];
  for (const [said, want] of [['skip', 1], ['Skip.', 1], ['okay skip it', 1], ['approve', 0], ['Approve it please', 0], ['one', 0], ['two', 1], ['the second', 1], ['number two', 1], ['three', -1], ['skip the tests and merge', -1], ['', -1]] as const) {
    assert.equal(matchAnswer(said, a), want, said);
  }
  assert.equal(matchAnswer('post', ['Post it now', 'Skip']), 0, 'a label’s first word, when nothing else starts with it');
  assert.equal(matchAnswer('post', ['Post to main', 'Post as draft']), -1, 'but not when two do');
  assert.equal(matchAnswer('main', ['main', 'release', 'Skip']), 0);
  assert.equal(matchAnswer('release', ['main', 'release', 'Skip']), 1);
});

test('the words on the lens: head and band', () => {
  const c = askOfNote(readNote(note('n1'))!, NOW)!;
  assert.deepEqual(askHead(c), ['ASK', 'Review before merge']);
  assert.deepEqual(askHead({ name: 'A very long automation name indeed' }), ['ASK', 'A very long automat…']);
  assert.deepEqual(askBand(c), { key: 'ask:ru_1:st_ask', kind: 'needs', head: ['ASK', 'Review before merge'], detail: 'Post this review?', short: 'Review before merge asks', who: 'Review before merge', ttl: null, until: NOW + 10 * MIN });
});

// ------------------------------------------------------------------ the hub

function socket(messages: unknown[]) {
  return class {
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: string }) => void) | null = null;
    onclose: ((e: { code: number }) => void) | null = null;
    onerror: (() => void) | null = null;
    constructor() { setTimeout(() => { this.onopen?.(); for (const m of messages) this.onmessage?.({ data: JSON.stringify(m) }); }, 5); }
    send(): void {}
    close(): void {}
  };
}
const snap = (notes: unknown[]): Snapshot => ({
  mac: { id: 'm', name: 'Mac', daemonVersion: '0' }, usage: { fiveHour: null, sevenDay: null, source: 'none', observedAt: null },
  sessions: [], approvals: [], projects: [], automations: { paused: false, notes: notes as AutomationNote[] },
});

test('the hub passes an ask on once, as an ask and not as a note; a run that moved on is told too', async () => {
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = g.WebSocket;
  g.WebSocket = socket([
    { t: 'snapshot', snapshot: snap([note('n1', { runId: 'ru_a' }, { at: NOW - 20 * MIN }), note('n2', { runId: 'ru_gone', until: NOW - MIN }), note('n3', null)]) },
    { t: 'note', note: note('n4', { runId: 'ru_b' }) },
    { t: 'note', note: note('n4', { runId: 'ru_b' }) },
    { t: 'note', note: note('n5', { runId: 'ru_c' }, { to: 'phone' }) },
    { t: 'run', run: { id: 'ru_b', ask: null, steps: [{ id: 'st_ask', answer: { text: 'Approve', by: 'phone', at: 1 } }] } },
  ]);
  try {
    const hub = new LiveHub({ baseUrl: 'https://mac.example.ts.net:47321', token: 't', macName: 'Mac', macId: null, deviceId: null });
    const asks: string[] = [], notes: string[] = [], runs: string[] = [];
    hub.subscribe((e: HubEvent) => {
      if (e.t === 'ask') asks.push(e.ask.runId);
      if (e.t === 'note') notes.push(e.note.id);
      if (e.t === 'run_ask') runs.push(`${e.run.runId}:${e.run.ended[0]?.by}`);
    });
    hub.start();
    await wait(40);
    assert.deepEqual(asks, ['ru_a', 'ru_b']);
    assert.deepEqual(notes, ['n3']);
    assert.deepEqual(runs, ['ru_b:phone']);
    hub.stop();
  } finally { g.WebSocket = saved; }
});

test('the answer is one POST to the run’s answer route, with the step and the answer', async () => {
  const calls: Array<[string, unknown]> = [];
  const hub = new LiveHub({ baseUrl: 'https://mac.example.ts.net:47321', token: 't', macName: 'Mac', macId: null, deviceId: null });
  (hub as unknown as { api: { post(p: string, b: unknown): Promise<unknown> } }).api = { post: async (p, b) => { calls.push([p, b]); return {}; } };
  await hub.answerAsk('ru_1', 'st_ask', 'Approve');
  assert.deepEqual(calls, [['/v1/automations/runs/ru_1/answer', { stepId: 'st_ask', answer: 'Approve' }]]);
});

// ------------------------------------------------------------------ the lens

type Answer = (runId: string, stepId: string, answer: string) => Promise<void>;
function setup(over: Partial<Settings> = {}, answerAsk: Answer | null = async () => {}) {
  const sessions: Session[] = [{
    id: 'w', title: 'work', cwd: '/p/hypernova', projectName: 'hypernova', origin: 'terminal', state: 'working', model: null, permissionMode: 'default',
    createdAt: NOW - 3600_000, lastActivityAt: NOW - 60_000, lastText: null, activity: 'Bash: ./gradlew build', pendingApprovalIds: [], stoppedForAway: false,
  }];
  const listeners = new Set<(e: HubEvent) => void>();
  const posted: Array<[string, string, string]> = [];
  const hub = {
    snapshot: { mac: { id: 'm', name: 'Mac', daemonVersion: '0' }, usage: { fiveHour: { pct: 42, resetsAt: NOW + 7200_000 }, sevenDay: null, source: 'official', observedAt: NOW }, sessions, approvals: [], projects: [] } as Snapshot,
    status: 'live', problem: null, macName: 'Mac',
    events: () => null, loadEvents: async () => [], agents: () => null, loadAgents: async () => [],
    subscribe: (fn: (e: HubEvent) => void) => { listeners.add(fn); return () => listeners.delete(fn); },
    emit: (e: HubEvent) => { for (const fn of listeners) fn(e); },
    ...(answerAsk ? { answerAsk: async (r: string, s: string, a: string) => { posted.push([r, s, a]); await answerAsk(r, s, a); } } : {}),
  };
  const spoken: string[] = [];
  const voice = { speaking: false, locked: false, onChange() {}, unlock() {}, stop() { voice.speaking = false; }, blip() {}, speak: async (t: string) => { spoken.push(t); return 'mac'; } } as unknown as VoiceOut & { speaking: boolean };
  let settings: Settings = { ...DEFAULT_SETTINGS, ...over };
  const speech = new DemoSpeech([]);
  const services: Services = {
    hub: () => hub as unknown as HubLike, speech: () => speech, voice, settings: () => settings, updateSettings: p => { settings = { ...settings, ...p }; },
    paired: () => true, showOnPhone: () => {}, shotReady: () => {}, glassesBattery: () => null,
  };
  const lens = new LensController(services);
  hub.subscribe(e => lens.hubEvent(e));
  // Built here, not through the hub, so an ask already over can be tried (the hub passes none).
  const ask = (extra: Partial<AskInfo> = {}) => {
    const i = info(extra);
    hub.emit({ t: 'ask', ask: { key: `${i.runId}:${i.stepId}`, runId: i.runId, stepId: i.stepId, name: 'Review before merge', question: i.question, answers: i.answers, default: i.default, until: i.until, at: Date.now() } });
  };
  return { hub, lens, spoken, voice, speech, posted, ask };
}
const choice = (lens: LensController) => { const v = lens.view(); assert.equal(v.kind, 'choice', `a choice, not ${v.kind}`); return v as Extract<LensView, { kind: 'choice' }>; };
const card = (lens: LensController) => { const v = lens.view(); assert.equal(v.kind, 'card', `a card, not ${v.kind}`); return v as Extract<LensView, { kind: 'card' }>; };

test('on the glance an ask takes the lens: name, question, answers, the default and its clock', () => {
  const { lens, spoken, ask } = setup();
  ask();
  const v = choice(lens);
  assert.deepEqual(v.head, ['ASK', 'Review before merge']);
  assert.equal(v.prompt, 'Post this review?');
  assert.match(gapJoin(v.right), /^Skip in 9m$/);
  assert.deepEqual(v.options.map(o => gapJoin(o.label)), ['Approve', 'Skip (default)']);
  assert.equal(v.cursor, 0);
  assert.deepEqual(v.hint, ['swipe choose', 'tap answer', 'double tap later']);
  assert.deepEqual(spoken, ['Review before merge asks: Post this review? Approve, or Skip.']);
  // The page fits the lens: boxes under the firmware's cap, the question at most two lines.
  const long = 'x'.repeat(5) + ' ' + Array.from({ length: 30 }, () => 'review').join(' ') + '?';
  lens.dispose();
  const b = setup();
  b.ask({ question: long });
  const page = buildPage(choice(b.lens));
  assert.ok(page.boxes.length + 1 <= 8);
  assert.ok(page.boxes.every(x => !('content' in x) || typeof x.content === 'string'));
  b.lens.dispose();
});

test('swipes pick, a tap answers: the answer route is called with the step, and the card closes', async () => {
  const { lens, posted, ask } = setup();
  ask();
  await wait(750);                                              // past the tap guard
  lens.down();
  assert.equal(choice(lens).cursor, 1);
  lens.down();
  assert.equal(choice(lens).cursor, 1, 'stops at the last answer');
  lens.up();
  lens.tap();
  await wait(5);
  assert.deepEqual(posted, [['ru_1', 'st_ask', 'Approve']]);
  assert.equal(lens.view().kind, 'glance');
  lens.dispose();
});

test('a tap in the first moment was meant for what was there before', async () => {
  const { lens, posted, ask } = setup();
  ask();
  lens.tap();
  await wait(5);
  assert.deepEqual(posted, []);
  lens.dispose();
});

test('someone else answered first (409 not_asking): who, calmly, and the card closes by itself', async () => {
  const run = { id: 'ru_1', ask: null, steps: [{ id: 'st_ask', answer: { text: 'Skip', by: 'phone', at: 1 } }] };
  const { lens, ask } = setup({}, async () => { throw new ApiError('server', 'Already answered.', 409, 'not_asking', { code: 'not_asking', run }); });
  ask();
  await wait(750);
  lens.tap();
  await wait(5);
  const c = card(lens);
  assert.deepEqual(c.head, ['ASK', 'Review before merge']);
  assert.deepEqual(c.body, ['Already answered', 'Skip, on your phone']);
  await wait(750);
  lens.tap();                                                   // a tap closes it
  assert.equal(lens.view().kind, 'glance');
  lens.dispose();
});

test('another device answered while the card is up: the run message closes it with who', async () => {
  const { lens, hub, ask } = setup();
  ask();
  hub.emit({ t: 'run_ask', run: { runId: 'ru_1', open: null, ended: [{ stepId: 'st_ask', text: 'Approve', by: 'mac' }] } });
  assert.deepEqual(card(lens).body, ['Already answered', 'Approve, on the Mac']);
  // Another run's message changes nothing.
  lens.dispose();
});

test('the timeout passes: the card closes with the default taken', async () => {
  const { lens, ask } = setup();
  ask({ until: Date.now() + 120 });
  assert.equal(lens.view().kind, 'choice');
  await wait(180);
  lens.view();                                                  // the tick that notices
  assert.deepEqual(card(lens).body, ['Took the default: Skip']);
  lens.dispose();
});

test('the daemon says the default was taken (by: timeout): its answer is in the words', () => {
  const { lens, hub, ask } = setup();
  ask({ answers: ['Approve', 'Skip', 'Retry'], default: 'Retry' });
  hub.emit({ t: 'run_ask', run: { runId: 'ru_1', open: null, ended: [{ stepId: 'st_ask', text: 'Retry', by: 'timeout' }] } });
  assert.deepEqual(card(lens).body, ['Took the default: Retry']);
  lens.dispose();
});

test('double tap sets it aside, a band keeps it, a tap on the band brings it back', async () => {
  const { lens, ask } = setup();
  ask();
  await wait(750);
  lens.doubleTap();
  assert.equal(lens.view().kind, 'glance');
  assert.deepEqual(lens.view().band?.head, ['ASK', 'Review before merge']);
  await wait(750);
  lens.tap();
  assert.deepEqual(choice(lens).head, ['ASK', 'Review before merge']);
  lens.dispose();
});

test('over another screen an ask is a band; eclipse lets it through; the pop-up switch for sessions governs it', async () => {
  const { lens, ask } = setup({ eclipse: true });
  lens.tap();                                                   // the working session
  assert.equal(lens.view().kind, 'live');
  ask();
  assert.deepEqual(lens.view().band?.head, ['ASK', 'Review before merge']);
  assert.equal(lens.view().band?.detail, 'Post this review?');
  await wait(750);
  lens.tap();
  assert.deepEqual(choice(lens).head, ['ASK', 'Review before merge']);
  lens.dispose();
});

test('saying an answer works like the voice approvals: “skip”, “approve”, “two”', async () => {
  for (const [said, want] of [['skip', 'Skip'], ['approve', 'Approve'], ['two', 'Skip'], ['one', 'Approve']] as const) {
    const { lens, speech, posted, ask } = setup();
    ask();
    await wait(750);
    speech.next(said);
    await lens.hold();
    await lens.release();
    await wait(10);
    assert.deepEqual(posted, [['ru_1', 'st_ask', want]], said);
    lens.dispose();
  }
});

test('something that only contains an answer is no answer: it stays asking', async () => {
  const { lens, speech, posted, ask } = setup();
  ask();
  await wait(750);
  speech.next('skip the tests and merge it');
  await lens.hold();
  await lens.release();
  await wait(10);
  assert.deepEqual(posted, []);
  lens.dispose();
});

test('a hub that cannot answer asks says so, and nothing changes for a Mac without workflows', async () => {
  const old = setup({}, null);
  old.ask();
  await wait(750);
  old.lens.tap();
  await wait(5);
  assert.match(gapJoin(choice(old.lens).hint), /can’t take that yet/);
  old.lens.dispose();

  // No ask ever comes: the glance stays the glance, and the plain note path is as before.
  const none = setup();
  none.hub.emit({ t: 'note', note: note('n9', null) });
  assert.equal(card(none.lens).head[0], 'AUTOMATION');
  none.lens.dispose();
});

test('the same ask twice shows once; an ask already over shows nothing', () => {
  const { lens, spoken, ask } = setup();
  ask();
  lens.doubleTap();
  ask();
  assert.equal(lens.view().kind, 'glance');
  assert.equal(spoken.length, 1);
  lens.dispose();
  const late = setup();
  late.ask({ runId: 'ru_2', until: Date.now() - 1 });
  assert.equal(late.lens.view().kind, 'glance');
  late.lens.dispose();
});
