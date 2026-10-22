// Automation notes on the glasses (docs/specs/spec-automations.md §8): which notes the hub passes on
// (only the glasses' own, once, and not old ones), the card, the band, reading aloud, and the quiet
// cases (eclipse, the pop-up switch off, the guide, a held talk).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gapJoin } from '../src/core/font.ts';
import { LiveHub, type HubEvent, type HubLike } from '../src/data/hub.ts';
import { noteIds, readNote, unseenNotes, NOTE_FRESH_MS } from '../src/core/notes.ts';
import { noteBand, noteHead, noteSpoken } from '../src/lens/notes.ts';
import { LensController, type Services } from '../src/lens/controller.ts';
import { buildPage, type LensView } from '../src/lens/pages.ts';
import { DemoSpeech } from '../src/data/speech.ts';
import { DEFAULT_SETTINGS, type Settings } from '../src/data/store.ts';
import type { VoiceOut } from '../src/data/voice.ts';
import type { AutomationNote, Session, Snapshot } from '../src/core/types.ts';

const NOW = Date.now();
const MIN = 60_000;
const note = (id: string, extra: Partial<AutomationNote> = {}): AutomationNote => ({
  id, automationId: 'au_a1b2c3d4e5', runId: 'ru_0123456789', name: 'Morning brief', to: 'glasses', at: NOW - 30_000,
  text: 'Three pull requests need a look, and the build on main is green. Nothing is blocked.', ...extra,
});

// ------------------------------------------------------------------ core

test('a note off the wire: only what has an id and words; the rest is cleaned, never thrown', () => {
  assert.equal(readNote(null), null);
  assert.equal(readNote({ id: 'n1', text: '   ' }), null);
  assert.equal(readNote({ text: 'no id' }), null);
  const n = readNote({ id: 'n1', text: 'Hello', to: 'tablet', name: '  ', futureField: 1 });
  assert.deepEqual(n, { id: 'n1', automationId: null, runId: null, name: 'Automation', text: 'Hello', to: 'phone', at: 0 });
  assert.equal(readNote({ id: 'n2', text: 'x'.repeat(5000) })!.text.length, 1000);
});

test('a snapshot’s notes: the glasses’ own, fresh, unseen, oldest first', () => {
  const seen = new Set(['n3']);
  const list = [
    note('n1', { at: NOW - 2 * MIN }), note('n2', { to: 'phone' }), note('n3'), note('n4', { at: NOW - NOTE_FRESH_MS - MIN }),
    note('n5', { at: NOW - 3 * MIN }), { junk: true },
  ];
  assert.deepEqual(unseenNotes(list, seen, NOW).map(n => n.id), ['n5', 'n1']);
  assert.deepEqual(noteIds(list), ['n1', 'n2', 'n3', 'n4', 'n5']);
  assert.deepEqual(unseenNotes(undefined, seen, NOW), []);
});

test('the words: head, band, and what is said', () => {
  const n = note('n1');
  assert.deepEqual(noteHead(n), ['AUTOMATION', 'Morning brief']);
  assert.deepEqual(noteHead(note('n', { name: 'A very long automation name indeed' })), ['AUTOMATION', 'A very long automatio…']);
  assert.deepEqual(noteBand(n), { key: 'note:n1', kind: 'note', head: ['AUTOMATION', 'Morning brief'], detail: 'Three pull requests need a look, and the build on main is green.', short: 'Morning brief', ttl: 12_000 });
  assert.equal(noteSpoken(n), 'Morning brief. Three pull requests need a look, and the build on main is green. Nothing is blocked.');
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

test('the hub passes on the glasses’ notes once: live, and again in the snapshot, never old ones', async () => {
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = g.WebSocket;
  g.WebSocket = socket([
    { t: 'snapshot', snapshot: snap([note('old', { at: NOW - 3 * 3600_000 }), note('phone', { to: 'phone' }), note('fresh', { at: NOW - MIN })]) },
    { t: 'note', note: note('live') },
    { t: 'note', note: note('live') },                         // the same one again
    { t: 'note', note: note('p2', { to: 'phone' }) },          // not for the glasses
    { t: 'snapshot', snapshot: snap([note('live'), note('fresh', { at: NOW - MIN }), note('later', { at: NOW })]) }, // a reconnect
  ]);
  try {
    const hub = new LiveHub({ baseUrl: 'https://mac.example.ts.net:47321', token: 't', macName: 'Mac', macId: null, deviceId: null });
    const seen: string[] = [];
    hub.subscribe((e: HubEvent) => { if (e.t === 'note') seen.push(e.note.id); });
    hub.start();
    await new Promise(r => setTimeout(r, 40));
    assert.deepEqual(seen, ['fresh', 'live', 'later']);
    hub.stop();
  } finally { g.WebSocket = saved; }
});

// ------------------------------------------------------------------ the lens

function setup(over: Partial<Settings> = {}) {
  const sessions: Session[] = [{
    id: 'w', title: 'work', cwd: '/p/hypernova', projectName: 'hypernova', origin: 'terminal', state: 'working', model: null, permissionMode: 'default',
    createdAt: NOW - 3600_000, lastActivityAt: NOW - 60_000, lastText: null, activity: 'Bash: ./gradlew build', pendingApprovalIds: [], stoppedForAway: false,
  }];
  const listeners = new Set<(e: HubEvent) => void>();
  const hub = {
    snapshot: { mac: { id: 'm', name: 'Mac', daemonVersion: '0' }, usage: { fiveHour: { pct: 42, resetsAt: NOW + 7200_000 }, sevenDay: null, source: 'official', observedAt: NOW }, sessions, approvals: [], projects: [] } as Snapshot,
    status: 'live', problem: null, macName: 'Mac',
    events: () => null, loadEvents: async () => [], agents: () => null, loadAgents: async () => [],
    subscribe: (fn: (e: HubEvent) => void) => { listeners.add(fn); return () => listeners.delete(fn); },
    emit: (e: HubEvent) => { for (const fn of listeners) fn(e); },
  };
  const spoken: string[] = [];
  const voice = { speaking: false, locked: false, onChange() {}, unlock() {}, stop() { voice.speaking = false; }, blip() {}, speak: async (t: string) => { spoken.push(t); return 'mac'; } } as unknown as VoiceOut & { speaking: boolean };
  let settings: Settings = { ...DEFAULT_SETTINGS, ...over };
  const services: Services = {
    hub: () => hub as unknown as HubLike, speech: () => new DemoSpeech([]), voice, settings: () => settings, updateSettings: p => { settings = { ...settings, ...p }; },
    paired: () => true, showOnPhone: () => {}, shotReady: () => {}, glassesBattery: () => null,
  };
  const lens = new LensController(services);
  hub.subscribe(e => lens.hubEvent(e));
  return { hub, lens, spoken, voice };
}
const card = (lens: LensController) => { const v = lens.view(); assert.equal(v.kind, 'card', `a card, not ${v.kind}`); return v as Extract<LensView, { kind: 'card' }>; };

test('on the glance a note takes the lens as a card, is read at once, and double tap goes back', () => {
  const { hub, lens, spoken } = setup();
  assert.equal(lens.view().kind, 'glance');
  hub.emit({ t: 'note', note: note('n1') });
  const c = card(lens);
  assert.deepEqual(c.head, ['AUTOMATION', 'Morning brief']);
  // The note came 30 s before the test's clock read; a slow run can cross into the next second.
  assert.match(gapJoin(c.right), /^3[01]s$/);
  assert.match(c.body.join(' '), /^Three pull requests need a look/);
  assert.deepEqual(c.hint, ['tap read aloud', 'double tap back']);
  assert.deepEqual(spoken, ['Morning brief. Three pull requests need a look, and the build on main is green. Nothing is blocked.']);
  lens.doubleTap();
  assert.equal(lens.view().kind, 'glance');
  lens.dispose();
});

test('a tap reads it aloud again; reading shows on the card, and a tap stops it', async () => {
  const { hub, lens, spoken, voice } = setup();
  hub.emit({ t: 'note', note: note('n1') });
  spoken.length = 0;
  lens.tap();
  await new Promise(r => setTimeout(r, 5));
  assert.equal(spoken.length, 1);
  voice.speaking = true;
  // Reading says so, steadily, in the header; a tap stops it, a swipe still scrolls.
  assert.equal(card(lens).wave, 'reading aloud');
  assert.deepEqual(card(lens).hint, ['tap stop reading', 'swipe scroll', 'double tap back']);
  lens.tap();
  assert.equal(voice.speaking, false);
  lens.dispose();
});

test('a long note scrolls by swipes, as a result does, and the page fits the lens', () => {
  const { hub, lens } = setup();
  const text = Array.from({ length: 14 }, (_, i) => `Pull request ${i + 1} touches the parser and needs a second look before it can be merged.`).join(' ');
  hub.emit({ t: 'note', note: note('n1', { text }) });
  const c = card(lens);
  assert.ok(c.body.at(-1)!.endsWith('↓'), 'more under the window');
  assert.deepEqual(c.hint, ['swipe read', 'tap read aloud']);
  const page = buildPage(c);
  assert.ok(page.boxes.length + 1 <= 8);
  lens.down();
  assert.notDeepEqual(card(lens).body, c.body);
  lens.dispose();
});

test('over another screen a note is a band; a tap opens it; it is read all the same', async () => {
  const { hub, lens, spoken } = setup();
  lens.tap();                                                  // opens the working session
  assert.equal(lens.view().kind, 'live');
  hub.emit({ t: 'note', note: note('n1') });
  assert.deepEqual(lens.view().band?.head, ['AUTOMATION', 'Morning brief']);
  assert.equal(lens.view().band?.detail, 'Three pull requests need a look, and the build on main is green.');
  assert.equal(spoken.length, 1);
  await new Promise(r => setTimeout(r, 750));
  lens.tap();
  assert.deepEqual(card(lens).head, ['AUTOMATION', 'Morning brief']);
  lens.doubleTap();
  lens.dispose();
});

test('quiet cases: eclipse, the switch off, and reading aloud off say nothing, or only less', () => {
  const eclipse = setup({ eclipse: true });
  eclipse.hub.emit({ t: 'note', note: note('n1') });
  assert.equal(eclipse.lens.view().kind, 'glance');
  assert.deepEqual(eclipse.spoken, []);
  eclipse.lens.dispose();

  const off = setup({ popups: { ...DEFAULT_SETTINGS.popups, note: false } });
  off.hub.emit({ t: 'note', note: note('n1') });
  assert.equal(off.lens.view().kind, 'glance');
  assert.deepEqual(off.spoken, []);
  off.lens.dispose();

  // Muted: the card still shows (the words are the point), nothing is said.
  const muted = setup({ readAloud: false });
  muted.hub.emit({ t: 'note', note: note('n1') });
  assert.deepEqual(card(muted.lens).head, ['AUTOMATION', 'Morning brief']);
  assert.deepEqual(card(muted.lens).hint, ['double tap back', 'hold talk']);
  assert.deepEqual(muted.spoken, []);
  muted.lens.dispose();
});

test('the same note twice shows once', () => {
  const { hub, lens, spoken } = setup();
  hub.emit({ t: 'note', note: note('n1') });
  lens.doubleTap();
  hub.emit({ t: 'note', note: note('n1') });
  assert.equal(lens.view().kind, 'glance');
  assert.equal(spoken.length, 1);
  lens.dispose();
});
