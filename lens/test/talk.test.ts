// Talking to the glasses, from the release on: the last words come in after the temple is let go
// (data/speech.ts) and settle where the captions were; then the whole prompt shows, scrolling if it's
// long, and goes by itself after a short count unless a gesture comes first: tap sends at once,
// double tap cancels and keeps nothing, a swipe or a hold stops the count. A prompt whose last words
// never came back says so and waits for a tap.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { partsOf } from '../src/core/font.ts';
import { buildPage, H, TALK_ROWS, TALK_TEXT_W, W, type LensPage, type LensView } from '../src/lens/pages.ts';
import { LINE, nativeWidth } from '../src/core/font.ts';
import { MORE_W } from '../src/core/scroll.ts';
import { wrapAll } from '../src/core/text.ts';
import { LensController, sendDelay, type Services } from '../src/lens/controller.ts';
import type { HubEvent, HubLike } from '../src/data/hub.ts';
import type { Caption, Heard, SpeechEngine } from '../src/data/speech.ts';
import { DEFAULT_SETTINGS, type Settings } from '../src/data/store.ts';
import type { VoiceOut } from '../src/data/voice.ts';
import type { Session, Snapshot } from '../src/core/types.ts';
import { briefOf } from '../src/lens/moving.ts';

const NOW = Date.now();

function makeHub() {
  const s = (id: string, projectName: string, state: Session['state']): Session => ({
    id, title: `${projectName} work`, cwd: `/p/${projectName}`, projectName, origin: 'terminal', state, model: null, permissionMode: 'default',
    createdAt: NOW - 3600_000, lastActivityAt: NOW - 60_000, lastText: 'Done.', activity: null, pendingApprovalIds: [], stoppedForAway: false,
  });
  const calls: string[] = [];
  const listeners = new Set<(e: HubEvent) => void>();
  const hub: HubLike & { calls: string[]; snapshot: Snapshot } = {
    calls,
    snapshot: {
      mac: { id: 'm', name: 'Mac', daemonVersion: '0' }, usage: { fiveHour: { pct: 42, resetsAt: NOW + 7200_000 }, sevenDay: null, source: 'official', observedAt: NOW },
      sessions: [s('w', 'hypernova', 'working'), s('i', 'hyperscribe', 'idle')],
      approvals: [],
      projects: [{ path: '/p/hyperscribe', name: 'hyperscribe', lastActivityAt: NOW, sessionCount: 1 }, { path: '/p/hypernova', name: 'hypernova', lastActivityAt: NOW - 1, sessionCount: 1 }],
    },
    status: 'live', problem: null, macName: 'Mac',
    events: () => [], loadEvents: async () => [], agents: () => [], loadAgents: async () => [],
    answer: async () => {},
    prompt: async (id: string, text: string) => { calls.push(`prompt ${id} ${text}`); return { queued: false }; },
    newSession: async (path: string, text: string) => { calls.push(`new ${path} ${text}`); return null; },
    newNebulaSession: async (name: string, text: string) => { calls.push(`nebula ${name} ${text}`); return null; },
    interrupt: async (id: string) => { calls.push(`interrupt ${id}`); },
    menu: async () => {},
    screenshot: async () => { throw new Error('no'); },
    telescope: async () => { throw new Error('no'); },
    preview: async () => '',
    speak: async () => new ArrayBuffer(0),
    voices: async () => [],
   
    subscribe: (fn: (e: HubEvent) => void) => { listeners.add(fn); return () => listeners.delete(fn); },
  };
  return hub;
}

/**
 * Speech as the Mac gives it: captions while held; on release, `tail` (more words, as Soniox settles
 * the end once the audio is over) arrives `tailMs` later with the whole text, unless `cut` (it never
 * came back: what was heard, incomplete).
 */
class Scripted implements SpeechEngine {
  readonly kind = 'mac' as const;
  listening = false;
  cancelled = 0;
  private caption: Caption = () => {};
  private said = '';
  next: { said: string; tail?: string; tailMs?: number; cut?: boolean }[] = [];
  async start(onCaption: Caption): Promise<void> {
    this.listening = true;
    this.caption = onCaption;
    const n = this.next[0];
    this.said = n?.said ?? '';
    if (this.said) setTimeout(() => this.caption('', this.said), 1);
  }
  async stop(): Promise<Heard> {
    this.listening = false;
    const n = this.next.shift() ?? { said: this.said };
    await new Promise(r => setTimeout(r, n.tailMs ?? 0));
    if (n.cut) return { text: n.said, complete: false };
    const text = [n.said, n.tail].filter(Boolean).join(' ');
    this.caption(text, '');
    return { text, complete: true };
  }
  cancel(): void { this.listening = false; this.cancelled++; this.next.shift(); }
}

function setup() {
  const hub = makeHub();
  const speech = new Scripted();
  const voice = { speaking: false, locked: false, onChange() {}, unlock() {}, stop() {}, blip() {}, speak: async () => 'mac' } as unknown as VoiceOut;
  let settings: Settings = { ...DEFAULT_SETTINGS };
  const services: Services = {
    hub: () => hub, speech: () => speech, voice, settings: () => settings, updateSettings: p => { settings = { ...settings, ...p }; },
    paired: () => true, showOnPhone: () => {}, shotReady: () => {},
  };
  const lens = new LensController(services);
  hub.subscribe(e => lens.hubEvent(e));
  return { hub, lens, speech };
}

const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
const talk = (v: LensView) => { assert.equal(v.kind, 'talk', `the talk screen, not ${v.kind}`); return v as Extract<LensView, { kind: 'talk' }>; };
/** The talk box's lines as the glasses get them. */
const body = (v: LensView) => buildPage(v).boxes.find(b => b.name === 'body')!.content.split('\n');

/** Every box within the lens and within its own size (else the firmware adds a scrollbar). */
function fits(p: LensPage, label: string): void {
  for (const b of p.boxes) {
    assert.ok(b.x >= 0 && b.y >= 0 && b.x + b.w <= W && b.y + b.h <= H, `${label}: ${b.name} off the lens`);
    const lines = b.content.split('\n');
    assert.ok(lines.length <= Math.max(1, Math.floor((b.h - 2 * b.border - 2 * b.pad) / LINE)), `${label}: ${b.name} has ${lines.length} lines`);
    for (const l of lines) assert.ok(nativeWidth(l) <= b.w - 2 * b.border - 2 * b.pad, `${label}: ${b.name} line too wide: "${l}"`);
  }
}

test('let go: the last words arrive after the release and settle where the captions were; then the whole prompt shows and goes by itself', async () => {
  const { hub, lens, speech } = setup();
  speech.next.push({ said: 'in hyperscribe, make the export button', tail: 'remember the last folder', tailMs: 300 });
  await lens.hold();
  await wait(10);
  assert.deepEqual(talk(lens.view()).head, ['LISTENING']);
  const releasing = lens.release();
  // Let go, the end still on its way: the captions stay, and a tap now doesn't send half of it.
  let v = talk(lens.view());
  assert.deepEqual(v.head, ['LISTENING']);
  assert.deepEqual(v.hint, ['getting the last words…']);
  assert.match(v.text, /make the export button$/);
  lens.tap();
  await wait(100);
  assert.deepEqual(hub.calls, []);
  await releasing;
  v = talk(lens.view());
  assert.deepEqual(v.head, ['TO', 'hyperscribe']);
  assert.equal(v.text, 'make the export button remember the last folder', 'the words that came after the release are in');
  assert.equal(v.right, 'sending in 2');
  assert.equal(v.sendIn, 2);
  assert.deepEqual(v.hint, ['tap send now', 'double tap cancel']);
  assert.deepEqual(body(v).filter(Boolean).join(' '), v.text, 'shown whole');
  fits(buildPage(v), 'countdown');
  // Moving mode says it's going too.
  assert.deepEqual((briefOf(v) as { lines: string[] }).lines[0], ['TO', 'hyperscribe', 'sending in 2']);
  await wait(1100);
  assert.equal(talk(lens.view()).right, 'sending in 1');
  assert.deepEqual(hub.calls, []);
  await wait(1000);
  assert.deepEqual(hub.calls, ['prompt i make the export button remember the last folder']);
  assert.deepEqual(talk(lens.view()).head, ['SENT', 'hyperscribe']);
  lens.dispose();
});

test('a tap while it shows sends at once; double tap cancels and keeps nothing, even after the count would have ended', async () => {
  const { hub, lens, speech } = setup();
  speech.next.push({ said: 'in hyperscribe, run the tests' });
  await lens.hold();
  await lens.release();
  assert.equal(talk(lens.view()).right, 'sending in 2');
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['prompt i run the tests']);
  await wait(2200);
  assert.deepEqual(hub.calls, ['prompt i run the tests'], 'sent once');

  speech.next.push({ said: 'in hyperscribe, delete the build folder' });
  await lens.hold();
  await lens.release();
  lens.doubleTap();
  assert.equal(lens.view().kind, 'glance');
  await wait(2300);
  assert.deepEqual(hub.calls, ['prompt i run the tests'], 'cancelled: never sent');
  // Nothing kept: the next hold starts afresh.
  speech.next.push({ said: 'in hyperscribe, status of the build' });
  await lens.hold();
  await lens.release();
  assert.equal(talk(lens.view()).text, 'status of the build');
  lens.doubleTap();
  // Double tap while the last words are still coming cancels the hold too.
  speech.next.push({ said: 'in hyperscribe, push it', tailMs: 300 });
  await lens.hold();
  const releasing = lens.release();
  lens.doubleTap();
  await releasing;
  assert.equal(lens.view().kind, 'glance');
  assert.equal(speech.cancelled, 1);
  await wait(2200);
  assert.deepEqual(hub.calls, ['prompt i run the tests']);
  lens.dispose();
});

test('a swipe stops the count (here, choosing where it goes); a hold adds to it and the count starts again on the next release', async () => {
  const { hub, lens, speech } = setup();
  speech.next.push({ said: 'in hyperscribe, fix the header' });
  await lens.hold();
  await lens.release();
  lens.down();                                     // somewhere else, and no longer going by itself
  let v = talk(lens.view());
  assert.notEqual(v.head, 'TO hyperscribe');
  assert.equal(v.sendIn, undefined);
  assert.ok(!partsOf(v.hint).some(p => /send now/.test(p)));
  lens.up();                                       // back to hyperscribe
  await wait(2300);
  assert.deepEqual(hub.calls, []);
  // Hold to add: the count is gone while listening, and back once the whole draft shows.
  speech.next.push({ said: 'and the footer' });
  await lens.hold();
  assert.deepEqual(talk(lens.view()).head, ['LISTENING']);
  await lens.release();
  v = talk(lens.view());
  assert.equal(v.text, 'fix the header and the footer');
  assert.equal(v.right, 'sending in 2');
  await wait(2150);
  assert.deepEqual(hub.calls, ['prompt i fix the header and the footer']);
  lens.dispose();
});

test('a long prompt shows whole, from its end: a swipe stops the count and scrolls back to its start; nothing is cut', async () => {
  const { hub, lens, speech } = setup();
  const said = 'make the export button remember the last folder it used, then run every test in the project, tell me which ones were slow and why, and if any of them failed, fix them one at a time and explain each fix in a sentence before you move on to the next one; when that is done, update the changelog with a short line for each fix, bump the version, and build the release so I can try it on the phone tonight';
  speech.next.push({ said: `in hyperscribe, ${said}` });
  await lens.hold();
  await lens.release();
  let v = talk(lens.view());
  const all = wrapAll(said, TALK_TEXT_W - MORE_W);
  assert.ok(all.length > TALK_ROWS + 2, `${all.length} lines`);
  // Long: more time to read it before it goes, at most eight seconds.
  assert.equal(v.right, `sending in ${sendDelay(all.length) / 1000}`);
  assert.deepEqual(v.hint, ['tap send now', 'double tap cancel', 'swipe read']);
  // The end first: the words just said, under the captions you saw; more above is marked.
  assert.deepEqual(body(v).map(l => l.replace(/\s+↑$/, '')), all.slice(-TALK_ROWS));
  assert.ok(body(v)[0].endsWith('↑'));
  fits(buildPage(v), 'long countdown');
  const seen = new Set(body(v).map(l => l.replace(/\s+[↑↓]$/, '')));
  lens.up();
  v = talk(lens.view());
  assert.equal(v.sendIn, undefined, 'reading: the count stopped');
  assert.equal(v.right, 'tap to send', 'paused: it says what sends it');
  assert.deepEqual(v.hint, ['tap send', 'double tap cancel', 'swipe read']);
  for (const l of body(v)) seen.add(l.replace(/\s+[↑↓]$/, ''));
  for (let i = 0; i < 10; i++) { lens.up(); for (const l of body(lens.view())) seen.add(l.replace(/\s+[↑↓]$/, '')); }
  v = talk(lens.view());
  assert.equal(body(v)[0], all[0], 'back at its start');
  assert.ok(body(v).at(-1)!.endsWith('↓'), 'more below is marked');
  for (const l of all) assert.ok(seen.has(l), `line seen: ${l}`);
  assert.ok(!body(v).some(l => l.includes('…')), 'never cut short');
  fits(buildPage(v), 'long scrolled');
  await wait(2300);
  assert.deepEqual(hub.calls, []);
  assert.equal(talk(lens.view()).right, 'tap to send', 'still paused');
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, [`prompt i ${said}`]);
  // Gone, it stays as it was on the lens a moment: not cut short, as if only part of it went.
  v = talk(lens.view());
  assert.deepEqual(v.head, ['SENT', 'hyperscribe']);
  assert.equal(body(v)[0].replace(/\s+↓$/, ''), all[0]);
  assert.ok(!body(v).some(l => l.includes('…')));
  lens.dispose();
});

test('the last words never came back: what was heard stays, the lens says so, and it waits for a tap; a voice word in it isn’t acted on', async () => {
  const { hub, lens, speech } = setup();
  speech.next.push({ said: 'in hyperscribe, rename the export', cut: true });
  await lens.hold();
  await lens.release();
  let v = talk(lens.view());
  assert.deepEqual(v.head, ['TO', 'hyperscribe']);
  assert.equal(v.text, 'rename the export');
  assert.equal(v.right, 'last words missing?');
  assert.equal(v.sendIn, undefined);
  assert.deepEqual(v.hint, ['tap send', 'double tap discard', 'hold add']);
  await wait(2300);
  assert.deepEqual(hub.calls, [], 'not sent by itself');
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['prompt i rename the export']);
  // "stop", cut short, might have been "stop the server and…": not an interrupt, a draft to look at.
  lens.open('w');
  speech.next.push({ said: 'stop', cut: true });
  await lens.hold();
  await lens.release();
  v = talk(lens.view());
  assert.equal(v.text, 'stop');
  assert.equal(v.right, 'last words missing?');
  assert.doesNotMatch(hub.calls.join('\n'), /interrupt/);
  lens.dispose();
});

test('the count grows with the prompt: two seconds up to two lines, a second more for each line after, eight at most', () => {
  assert.equal(sendDelay(1), 2000);
  assert.equal(sendDelay(2), 2000);
  assert.equal(sendDelay(3), 3000);
  assert.equal(sendDelay(5), 5000);
  assert.equal(sendDelay(8), 8000);
  assert.equal(sendDelay(40), 8000);
});

test('a prompt of four lines goes after four seconds; a swipe while it counts pauses it until a tap, and a double tap then cancels', async () => {
  const { hub, lens, speech } = setup();
  const said = 'make the export button remember the last folder it used, then run every test in the project and tell me which ones were slow and why they were slow, and then fix them one by one';
  const lines = wrapAll(said, TALK_TEXT_W - MORE_W).length;
  assert.equal(lines, 4, `${lines} lines`);
  speech.next.push({ said: `in hyperscribe, ${said}` });
  await lens.hold();
  await lens.release();
  assert.equal(talk(lens.view()).right, 'sending in 4');
  await wait(2300);
  assert.deepEqual(hub.calls, [], 'not at two seconds: there are four lines to read');
  assert.equal(talk(lens.view()).right, 'sending in 2');
  await wait(1900);
  assert.deepEqual(hub.calls, [`prompt i ${said}`]);

  // Again, and a swipe partway: paused, it waits however long it takes; a double tap cancels.
  speech.next.push({ said: `in hyperscribe, ${said}` });
  await lens.hold();
  await lens.release();
  await wait(1000);
  lens.down();
  let v = talk(lens.view());
  assert.equal(v.right, 'tap to send');
  assert.equal(v.sendIn, undefined);
  await wait(4200);
  assert.equal(hub.calls.length, 1, 'paused: never sent by itself');
  lens.down();                                     // still paused after another swipe
  assert.equal(talk(lens.view()).right, 'tap to send');
  lens.doubleTap();
  assert.notEqual(lens.view().kind, 'talk');
  assert.equal(hub.calls.length, 1, 'cancelled');
  lens.dispose();
});
