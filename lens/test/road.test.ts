// The lens on a bike or a walk: the guide on the glasses, "debrief", "note: …", "next" / "back", "one"
// / "two" / "three", "plan first" / "go ahead", "continue", eclipse, moving mode, risky commands by
// their verb, "what changed?" and "show diff". The gestures and words do what the README says.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gapJoin, partsOf, type Parts } from '../src/core/font.ts';
import { buildPage, H, W, layoutKey, type LensPage, type LensView } from '../src/lens/pages.ts';
import { LINE, nativeWidth } from '../src/core/font.ts';
import { LensController, type Services } from '../src/lens/controller.ts';
import type { HubEvent, HubLike } from '../src/data/hub.ts';
import { DemoSpeech } from '../src/data/speech.ts';
import { DEFAULT_SETTINGS, type Settings } from '../src/data/store.ts';
import type { VoiceOut } from '../src/data/voice.ts';
import { ApiError } from '../src/data/api.ts';
import type { Agent, Approval, ApprovalAnswer, HnEvent, Session, Snapshot } from '../src/core/types.ts';

const MIN = 60_000, HOUR = 60 * MIN;
const NOW = Date.now();

function makeHub() {
  const s = (id: string, projectName: string, state: Session['state'], extra: Partial<Session> = {}): Session => ({
    id, title: `${projectName} work`, cwd: `/p/${projectName}`, projectName, origin: 'terminal', state, model: null, permissionMode: 'default',
    createdAt: NOW - HOUR, lastActivityAt: NOW - MIN, lastText: 'Fixed the pairing test. It waits for the camera now.', activity: null, pendingApprovalIds: [], stoppedForAway: false, ...extra,
  });
  const ap: Approval = { id: 'ap1', sessionId: 'n', createdAt: NOW - MIN, expiresAt: NOW + 10 * MIN, kind: 'tool', toolName: 'Bash', summary: 'Bash: git push origin main',
    input: { command: 'git push origin main' }, reason: null, canSession: false, questions: null, plan: null };
  const calls: string[] = [];
  const listeners = new Set<(e: HubEvent) => void>();
  const turn: HnEvent[] = [
    { id: 'u', kind: 'user', ts: NOW - 300_000, text: 'fix it' },
    { id: 'e1', kind: 'tool', ts: NOW - 200_000, toolUseId: 't1', name: 'Edit', summary: 'pair/PairScreen.kt', input: { file_path: '/p/hyperscribe/pair/PairScreen.kt' }, status: 'done', output: null, exitCode: null, durationMs: 40,
      path: '/p/hyperscribe/pair/PairScreen.kt', diff: { path: '/p/hyperscribe/pair/PairScreen.kt', added: 2, removed: 1, hunks: [{ oldStart: 12, oldLines: 3, newStart: 12, newLines: 4, lines: ['     fun start() {', '-        camera.open()', '+        camera.open().await()', '+        preview.show()'] }] } },
    { id: 'e2', kind: 'tool', ts: NOW - 100_000, toolUseId: 't2', name: 'Write', summary: 'test/PairTest.kt', input: { file_path: '/p/hyperscribe/test/PairTest.kt' }, status: 'done', output: null, exitCode: null, durationMs: 40,
      path: '/p/hyperscribe/test/PairTest.kt', diff: { path: '/p/hyperscribe/test/PairTest.kt', added: 3, removed: 0, hunks: [{ oldStart: 0, oldLines: 0, newStart: 1, newLines: 3, lines: ['+class PairTest {', '+    @Test fun waits() {}', '+}'] }] } },
    { id: 'a', kind: 'assistant', ts: NOW - 1000, text: 'Fixed the pairing test. It waits for the camera now.', streaming: false },
    { id: 'r', kind: 'result', ts: NOW, ok: true, durationMs: 300_000, costUsd: null, turns: 3, error: null },
  ];
  const hub: HubLike & { calls: string[]; snapshot: Snapshot; emit(e: HubEvent): void; noteError: ApiError | null } = {
    calls, noteError: null,
    snapshot: {
      mac: { id: 'm', name: 'Mac', daemonVersion: '0', battery: { percent: 62, charging: false, onAC: false, minutesLeft: 200 } },
      usage: { fiveHour: { pct: 42, resetsAt: NOW + 2 * HOUR }, sevenDay: null, source: 'official', observedAt: NOW },
      account: { state: 'ok', limit: null, since: null },
      sessions: [s('n', '0stems', 'needs_you', { pendingApprovalIds: ['ap1'] }), s('w', 'hypernova', 'working', { activity: 'Bash: ./gradlew build', agentsRunning: 2 }), s('i', 'hyperscribe', 'idle', { lastActivityAt: NOW - 10 * MIN })],
      approvals: [ap],
      projects: [],
    },
    status: 'live', problem: null, macName: 'Mac',
    events: (id: string) => (id === 'i' ? turn : null),
    loadEvents: async (id: string) => (id === 'i' ? turn : []),
    agents: () => [] as Agent[],
    loadAgents: async () => [],
    answer: async (id: string, a: ApprovalAnswer) => {
      calls.push(`answer ${id} ${JSON.stringify(a)}`);
      const sid = hub.snapshot.approvals.find(x => x.id === id)?.sessionId;
      hub.snapshot = { ...hub.snapshot, approvals: hub.snapshot.approvals.filter(x => x.id !== id), sessions: hub.snapshot.sessions.map(x => (x.id === sid ? { ...x, state: 'working', pendingApprovalIds: [] } : x)) };
      hub.emit({ t: 'change' });
    },
    prompt: async (id: string, text: string) => { calls.push(`prompt ${id} ${text}`); return { queued: false }; },
    newSession: async () => null,
    setMode: async (id: string, mode: 'plan' | 'default') => { calls.push(`mode ${id} ${mode}`); return { applies: 'now' }; },
    continueSession: async (id: string) => { calls.push(`continue ${id}`); },
    note: async (text: string, project: string | null) => {
      calls.push(`note ${text} @${project}`);
      if (hub.noteError) throw hub.noteError;
      return { path: '~/Programming/Nebula/notes.md' };
    },
    interrupt: async () => {},
    menu: async (id: string, menuId: string, pick: { choice: number } | { cancel: true }) => { calls.push(`menu ${id} ${menuId} ${JSON.stringify(pick)}`); },
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

/**
 * A voice that reads until `finish()` (or until stopped: then it wasn't heard to the end, as
 * VoiceOut.speakWhole says), and counts its chimes.
 */
function makeVoice() {
  const v = {
    speaking: false, locked: false, blips: [] as string[], spoken: [] as string[], gen: 0,
    end: null as null | (() => void),
    onChange() {}, unlock() {}, progress: () => 0,
    stop() { v.gen++; v.speaking = false; v.end?.(); v.end = null; },
    blip(kind: string) { v.blips.push(kind); },
    async speakWhole(t: string) {
      v.stop();
      const gen = ++v.gen;
      v.spoken.push(t);
      v.speaking = true;
      await new Promise<void>(r => { v.end = r; });
      const whole = gen === v.gen;
      if (whole) v.speaking = false;
      return { who: 'mac' as const, whole };
    },
    async speak(t: string) { return (await v.speakWhole(t)).who; },
    /** The reading ends by itself. */
    finish() { const e = v.end; v.end = null; e?.(); },
  };
  return v;
}

function setup(lines: string[] = [], patch: Partial<Settings> = {}) {
  const hub = makeHub();
  const speech = new DemoSpeech(lines);
  const voice = makeVoice();
  let settings: Settings = { ...DEFAULT_SETTINGS, ...patch };
  const services: Services = {
    hub: () => hub, speech: () => speech, voice: voice as unknown as VoiceOut, settings: () => settings, updateSettings: p => { settings = { ...settings, ...p }; },
    paired: () => true, showOnPhone: () => {}, shotReady: () => {}, glassesBattery: () => ({ percent: 84, charging: false }),
  };
  const lens = new LensController(services);
  hub.subscribe(e => lens.hubEvent(e));
  hub.emit({ t: 'change' });
  return { hub, lens, voice, speech, settings: () => settings };
}

const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
const say = async (lens: LensController) => { await lens.hold(); await lens.release(); await wait(5); };
const view = (lens: LensController): LensView => lens.view();
const card = (v: LensView) => { assert.equal(v.kind, 'card', `a card, not ${v.kind}`); return v as Extract<LensView, { kind: 'card' }>; };
const choice = (v: LensView) => { assert.equal(v.kind, 'choice', `a choice, not ${v.kind}`); return v as Extract<LensView, { kind: 'choice' }>; };
/** Moving's two lines as the lens shows them (their parts a gap apart). */
const brief = (v: LensView) => { assert.equal(v.kind, 'brief', `brief, not ${v.kind}`); return (v as Extract<LensView, { kind: 'brief' }>).lines.map(l => gapJoin(l)); };
/** A screen's hint, its parts. */
const hint = (v: LensView): string[] => ('hint' in v ? partsOf(v.hint as Parts) : []);

/** The firmware's limits (as lens.test.ts checks them): boxes, names, the lens' edges, lines that fit. */
function checkPage(p: LensPage, label: string): void {
  assert.ok(p.boxes.length + 1 <= 8, `${label}: ${p.boxes.length + 1} text boxes`);
  const names = [...p.boxes.map(b => b.name), ...p.images.map(i => i.name), 'input'];
  assert.equal(new Set(names).size, names.length, `${label}: duplicate names`);
  for (const b of p.boxes) {
    assert.ok(Buffer.byteLength(b.name) <= 16, `${label}: name ${b.name}`);
    assert.ok(b.x >= 0 && b.y >= 0 && b.x + b.w <= W && b.y + b.h <= H, `${label}: ${b.name} off the lens`);
    const lines = b.content.split('\n');
    const room = Math.floor((b.h - 2 * b.border - 2 * b.pad) / LINE);
    assert.ok(lines.length <= Math.max(1, room), `${label}: ${b.name} has ${lines.length} lines in room for ${room}`);
    for (const l of lines) assert.ok(nativeWidth(l) <= b.w - 2 * b.border - 2 * b.pad, `${label}: ${b.name} line too wide: "${l}"`);
  }
}

const question = (hub: ReturnType<typeof makeHub>, multi = false): void => {
  const a: Approval = { id: 'q1', sessionId: 'w', createdAt: NOW, expiresAt: NOW + 10 * MIN, kind: 'question', toolName: 'AskUserQuestion', summary: '', input: {}, reason: null, canSession: false, plan: null,
    questions: [{ question: 'Which store?', header: 'Store', multiSelect: multi, options: [{ label: 'Room' }, { label: 'DataStore' }, { label: 'Files' }] }] };
  hub.snapshot = { ...hub.snapshot, approvals: [...hub.snapshot.approvals, a], sessions: hub.snapshot.sessions.map(x => (x.id === 'w' ? { ...x, state: 'needs_you', pendingApprovalIds: ['q1'] } : x)) };
  hub.emit({ t: 'approval', approval: a });
  hub.emit({ t: 'change' });
};

const risky = (hub: ReturnType<typeof makeHub>, command = 'rm -rf build/'): void => {
  hub.snapshot = { ...hub.snapshot, approvals: hub.snapshot.approvals.map(a => (a.id === 'ap1' ? { ...a, summary: `Bash: ${command}`, input: { command } } : a)) };
  hub.emit({ t: 'change' });
};

// ------------------------------------------------------------------ the guide

test('the glasses offer their guide once; it checks each gesture as you do it, and the spoken step can be skipped', async () => {
  const { hub, lens, settings } = setup(['telescope'], { guideOffered: false });
  // Not while something needs you: it waits for a calm glance.
  assert.equal(view(lens).kind, 'glance');
  await hub.answer('ap1', { decision: 'deny' });
  assert.deepEqual(card(view(lens)).head, ['LENS GUIDE']);
  assert.equal(settings().guideOffered, true);
  lens.tap();                                                     // start
  let v = card(view(lens));
  assert.deepEqual(v.head, ['GUIDE 1 of 5', 'TAP']);
  assert.equal(v.right, '○○○○○');
  assert.equal(v.body[0], 'Tap the temple once.');
  lens.down();                                                    // not a tap: named, and nothing else moves
  assert.equal(card(view(lens)).body.at(-1), 'That was a swipe. Just one tap.');
  lens.tap();
  v = card(view(lens));
  assert.equal(v.body[0], '● Tap the temple once.');
  assert.equal(v.right, '●○○○○');
  await wait(1700);                                               // what a tap does stays a moment, then the next step
  assert.deepEqual(card(view(lens)).head, ['GUIDE 2 of 5', 'DOUBLE TAP']);
  lens.doubleTap();
  lens.down();                                                    // any gesture moves on; a swipe is the next step's own
  assert.deepEqual(card(view(lens)).head, ['GUIDE 3 of 5', 'SWIPE']);
  assert.equal(card(view(lens)).right, '●●●○○');
  lens.tap();                                                     // on to the hold
  assert.deepEqual(card(view(lens)).head, ['GUIDE 4 of 5', 'HOLD']);
  await lens.hold();
  assert.equal(card(view(lens)).body.at(-1), 'Now let go.');
  await lens.release();
  assert.equal(card(view(lens)).right, '●●●●○');
  lens.tap();
  assert.deepEqual(card(view(lens)).head, ['GUIDE 5 of 5', 'SAY IT']);
  assert.ok(hint(view(lens)).some(p => /double tap skips/.test(p)), hint(view(lens)).join(' | '));
  await lens.hold();                                              // the spoken step listens for real
  await lens.release();
  await wait(5);
  assert.equal(card(view(lens)).right, '●●●●●');
  lens.tap();
  assert.deepEqual(card(view(lens)).head, ['GUIDE', 'DONE']);
  lens.tap();
  assert.equal(view(lens).kind, 'glance');
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"deny"}'], 'nothing the guide did reached the Mac');
  lens.dispose();
});

test('"guide" plays it again; the spoken step shows what it heard and ticks, sending nothing, even a voice word; double tap skips it and leaves once learned', async () => {
  const { hub, lens } = setup(['guide', 'fix the export and push it to main', 'stop']);
  await say(lens);
  assert.deepEqual(card(view(lens)).head, ['GUIDE 1 of 5', 'TAP']);
  lens.startGuide(false, 4);
  assert.equal(card(view(lens)).body[0], 'Hold, say a few words, let go. Nothing is sent.');
  await lens.hold();
  await lens.release();
  await wait(5);
  let v = card(view(lens));
  assert.equal(v.right, '●●●●●');
  assert.equal(v.body[0], '● Hold, say a few words, let go. Nothing is sent.');
  assert.ok(v.body.join(' ').includes('“fix the export and push it to main”'), v.body.join(' | '));
  assert.match(v.body.join(' '), /tap sends at once, double tap cancels\.$/);
  // Said to the guide, even a voice word is only practice.
  lens.startGuide(false, 4);
  await lens.hold();
  await lens.release();
  await wait(5);
  v = card(view(lens));
  assert.ok(v.body.join(' ').includes('“stop”'));
  assert.deepEqual(hub.calls, [], 'nothing the guide heard reached the Mac');
  lens.tap();                                                     // on to the end
  assert.deepEqual(card(view(lens)).head, ['GUIDE', 'DONE']);
  lens.startGuide(false, 4);
  lens.doubleTap();                                               // voice isn't required
  assert.deepEqual(card(view(lens)).head, ['GUIDE', 'DONE']);
  lens.startGuide(false, 2);
  lens.doubleTap();                                               // learned by now: leaves
  assert.equal(view(lens).kind, 'glance');
  lens.dispose();
});

// ------------------------------------------------------------------ debrief

test('"debrief": what finished, what needs you, what runs, said and listed; a tap opens one; the next covers what came since', async () => {
  const { lens, voice } = setup(['debrief', 'debrief']);
  await say(lens);
  const v = choice(view(lens));
  assert.deepEqual(v.head, ['DEBRIEF']);
  assert.match(gapJoin(v.right), /^since /);
  assert.equal(v.prompt, '1 done, 1 needs you, 1 at work');
  // Each the project and what it did, apart: the same phrases as the glance.
  assert.deepEqual(v.options.map(o => o.label), [['hyperscribe', 'finished: Fixed the pairing test.'], ['0stems', 'wants to push to main'], ['hypernova', 'Bash: ./gradlew build', '2 agents running']]);
  assert.equal(v.cursor, 1, 'on what needs you');
  assert.match(voice.spoken.at(-1)!, /^Since .*\. hyperscribe finished: Fixed the pairing test\. zero stems wants to push to main\. hypernova is running \.\/gradlew build, with 2 agents running\.$/);
  voice.finish();
  await wait(750);
  lens.tap();
  assert.deepEqual(card(view(lens)).head, ['NEEDS YOU', '0stems']);
  lens.back();
  await say(lens);
  const again = choice(view(lens));
  assert.equal(again.prompt, '1 needs you, 1 at work', 'hyperscribe finished before the last debrief');
  lens.dispose();
});

// ------------------------------------------------------------------ notes

test('"note: …" keeps the idea in Nebula with the project in front of you, and starts nothing', async () => {
  const { hub, lens } = setup(['Note: comets should fade faster at night', 'note', 'note, another one']);
  lens.open('i');
  await say(lens);
  assert.deepEqual(hub.calls, ['note comets should fade faster at night @hyperscribe']);
  assert.deepEqual(hint(view(lens)), ['Noted: comets should fade faster at night']);
  await say(lens);
  assert.deepEqual(hint(view(lens)), ['Say “note,” then the idea, in one go']);
  hub.noteError = new ApiError('missing', 'no', 404, 'not_found');
  await say(lens);
  assert.deepEqual(hint(view(lens)), ['This Mac’s Hypernova can’t keep notes yet. Update it']);
  lens.dispose();
});

// ------------------------------------------------------------------ next, back, numbers

test('"next" and "back" move without a hand; "one", "two", "three" answer a question; several, then "approve"', async () => {
  const { hub, lens } = setup(['next', 'back', 'two', 'one', 'three', 'five', 'approve']);
  const focused = () => { const v = view(lens) as Extract<LensView, { kind: 'glance' }>; return v.rows.find(r => r.focused)?.project; };
  assert.equal(focused(), '0stems');
  await say(lens);
  assert.equal(focused(), 'hypernova');
  lens.open('n');
  await say(lens);                                                // back: answer later
  assert.equal(view(lens).kind, 'glance');
  question(hub);
  lens.open('w');
  await wait(750);
  await say(lens);                                                // two
  assert.deepEqual(hub.calls.at(-1), 'answer q1 {"decision":"allow","answers":{"Which store?":"DataStore"}}');
  question(hub, true);
  lens.open('w');
  await wait(750);
  await say(lens);                                                // one: ticked
  await say(lens);                                                // three: ticked
  assert.match(hint(view(lens))[0], /^Ticked 3: say more/);
  await say(lens);                                                // five: there are three
  assert.deepEqual(hint(view(lens)), ['There are 3 options']);
  await say(lens);                                                // approve sends the ticks
  assert.deepEqual(hub.calls.at(-1), 'answer q1 {"decision":"allow","answers":{"Which store?":"Room, Files"}}');
  lens.dispose();
});

test('"two" away from the question brings it up to read first, and answers nothing', async () => {
  const { hub, lens } = setup(['two']);
  question(hub);
  lens.open('i');
  await say(lens);
  const v = choice(view(lens));
  assert.deepEqual(v.head, ['QUESTION', 'hypernova']);
  assert.deepEqual(v.hint, ['Read it, then say the number']);
  assert.ok(!hub.calls.some(c => c.startsWith('answer q1')));
  lens.dispose();
});

// ------------------------------------------------------------------ modes, continue

test('"plan first" and "go ahead" set the mode of the session in front of you; on a plan they answer it; Auto and Bypass never', async () => {
  const { hub, lens } = setup(['plan first', 'go ahead', 'bypass permissions', 'go ahead', 'plan first']);
  lens.open('w');
  await say(lens);
  assert.deepEqual(hub.calls, ['mode w plan']);
  // Said in the phone's words for the modes, of the session it changed.
  assert.deepEqual(hint(view(lens)), ['hypernova plans first']);
  await say(lens);
  assert.deepEqual(hub.calls, ['mode w plan', 'mode w default']);
  assert.deepEqual(hint(view(lens)), ['hypernova asks first again']);
  await say(lens);
  assert.equal(hub.calls.length, 2, 'no mode by voice');
  assert.deepEqual(hint(view(lens)), ['Auto and Bypass only from the phone: it asks you to think twice']);
  const plan: Approval = { id: 'p1', sessionId: 'i', createdAt: NOW, expiresAt: NOW + MIN, kind: 'plan', toolName: 'ExitPlanMode', summary: '', input: {}, reason: null, canSession: false, questions: null, plan: '1. Do it.' };
  hub.snapshot = { ...hub.snapshot, approvals: [...hub.snapshot.approvals, plan], sessions: hub.snapshot.sessions.map(x => (x.id === 'i' ? { ...x, state: 'needs_you', pendingApprovalIds: ['p1'] } : x)) };
  hub.emit({ t: 'change' });
  lens.open('i');
  await say(lens);                                                // go ahead: the plan is approved
  assert.equal(hub.calls.at(-1), 'answer p1 {"decision":"allow"}');
  lens.dispose();
});

test('"continue" carries on the session in front of you', async () => {
  const { hub, lens } = setup(['continue']);
  lens.open('i');
  await say(lens);
  assert.deepEqual(hub.calls, ['continue i']);
  assert.deepEqual(hint(view(lens)), ['hyperscribe carries on']);
  lens.dispose();
});

// ------------------------------------------------------------------ eclipse

test('eclipse: only what needs you gets through; a finished turn neither takes the lens nor chimes', async () => {
  const { hub, lens, settings, voice } = setup(['eclipse', 'eclipse off'], { popups: { needs: true, done: true, stopped: true, limit: true, battery: true, sleep: true, note: true } });
  await say(lens);
  assert.equal(settings().eclipse, true);
  await hub.answer('ap1', { decision: 'deny' });
  const g = view(lens) as Extract<LensView, { kind: 'glance' }>;
  assert.deepEqual(partsOf(g.right).slice(0, 2), ['eclipse', '42%']);
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  assert.equal(view(lens).kind, 'glance', 'the result waits on the glance');
  assert.ok(!voice.blips.includes('done'));
  lens.open('w');
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, battery: { percent: 10, charging: false, onAC: false, minutesLeft: 20 } } };
  hub.emit({ t: 'change' });
  assert.deepEqual(view(lens).band?.head, ['MAC BATTERY', '10%'], 'the Mac running low gets through');
  hub.emit({ t: 'sleep_soon', inSeconds: 120, reason: 'battery', percent: 10 });
  assert.deepEqual(view(lens).band?.head, ['MAC SLEEPS IN 2 MIN'], 'so does the 2-minute sleep warning, which leads');
  const a: Approval = { id: 'ap2', sessionId: 'i', createdAt: NOW, expiresAt: NOW + MIN, kind: 'tool', toolName: 'Bash', summary: '', input: { command: 'npm test' }, reason: null, canSession: false, questions: null, plan: null };
  hub.snapshot = { ...hub.snapshot, approvals: [...hub.snapshot.approvals, a] };
  hub.emit({ t: 'approval', approval: a });
  assert.deepEqual(view(lens).band?.head, ['NEEDS YOU', 'hyperscribe'], 'what needs you gets through and leads');
  lens.doubleTap();
  await say(lens);
  assert.equal(settings().eclipse, false);
  lens.dispose();
});

// ------------------------------------------------------------------ moving

test('moving: two short lines; a finished turn said in one sentence; every pop-up chimes and takes one line', async () => {
  const { hub, lens, settings, voice } = setup(['moving', 'still'], { popups: { needs: true, done: true, stopped: true, limit: true, battery: true, sleep: true, note: true } });
  await say(lens);
  assert.equal(settings().moving, true);
  let lines = brief(view(lens));
  assert.deepEqual(lines, ['0stems   wants to push to main', 'Moving: two lines, one sentence']);
  checkPage(buildPage(view(lens)), 'moving glance');
  await wait(2500);                                               // past the word on the second line
  lens.open('w');
  assert.deepEqual(brief(view(lens)), ['WORKING   hypernova', '› Bash: ./gradlew build']);
  hub.snapshot = { ...hub.snapshot, sessions: hub.snapshot.sessions.map(x => (x.id === 'i' ? { ...x, lastText: 'Fixed the export. All 48 tests pass.' } : x)) };
  hub.emit({ t: 'finished', sessionId: 'i' });
  await wait(5);
  assert.equal(voice.spoken.at(-1), 'hyperscribe is done: Fixed the pairing test.');
  voice.finish();
  await wait(5);
  lines = brief(view(lens));
  assert.equal(lines.length, 2);
  assert.match(lines[0], /^hyperscribe finished: /);
  const chimes = voice.blips.length;
  hub.snapshot = { ...hub.snapshot, mac: { ...hub.snapshot.mac, battery: { percent: 12, charging: false, onAC: false, minutesLeft: 20 } } };
  hub.emit({ t: 'change' });
  assert.equal(voice.blips.length, chimes + 1, 'a chime');
  assert.equal(brief(view(lens))[0], 'Your Mac is at 12%. Plug it in or its sessions stop. Also hyperscribe finished.', 'merged with the result, as bands are');
  lens.doubleTap();
  lens.doubleTap();
  await say(lens);
  assert.equal(settings().moving, false);
  assert.notEqual(view(lens).kind, 'brief');
  lens.dispose();
});

// ------------------------------------------------------------------ "always" is on the phone

test('a spoken "always" is Allow once, never Always, and says where Always is; the glasses offer Allow and Deny only', async () => {
  const { hub, lens } = setup(['always allow', 'always']);
  lens.open('n');
  const v = card(view(lens));
  assert.deepEqual(v.hint, ['tap allow', 'double tap deny', 'swipe back']);
  assert.ok(!JSON.stringify(v).toLowerCase().includes('always'));
  await wait(750);
  await say(lens);
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"allow"}']);
  assert.deepEqual(hint(view(lens)), ['Allowed once. Always is on your phone.']);
  lens.dispose();
});

// ------------------------------------------------------------------ risky commands

test('still: a risky command allows by tap, or by voice with its own verb only; "always" only allows once', async () => {
  const { hub, lens } = setup(['approve', 'approve push', 'always allow', 'approve delete']);
  risky(hub);
  lens.open('n');
  let v = card(view(lens));
  // What can't be undone, on a line of its own.
  assert.equal(v.warn, 'careful: rm -rf can’t be undone');
  assert.deepEqual(v.hint, ['tap allow', 'say “approve delete”', 'double tap deny']);
  checkPage(buildPage(v), 'risky card');
  await wait(750);
  await say(lens);
  assert.deepEqual(hint(view(lens)), ['Say “approve delete” for this one']);
  await say(lens);
  assert.deepEqual(hint(view(lens)), ['This one would rm -rf: say “approve delete”']);
  await say(lens);
  assert.deepEqual(hint(view(lens)), ['Risky: allow it once, with “approve delete”']);
  assert.equal(hub.calls.length, 0);
  await say(lens);
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"allow"}']);
  lens.dispose();
});

test('moving: a risky command is read out; a tap won\'t allow it; its verb works only once it was heard to the end', async () => {
  const { hub, lens, voice } = setup(['approve delete', 'approve delete', 'approve delete'], { moving: true });
  risky(hub, 'rm -rf build/');
  lens.open('n');
  view(lens);
  await wait(5);
  assert.equal(voice.spoken.at(-1), 'Careful. zero stems wants to rm -rf: rm -rf build/. To allow it, say approve delete. Or say deny.');
  assert.deepEqual(brief(view(lens)), ['NEEDS YOU   0stems', 'rm -rf: say “approve delete”']);
  await wait(750);
  lens.tap();
  assert.equal(hub.calls.length, 0, 'a tap alone never allows it while moving');
  assert.equal(brief(view(lens))[1], 'Say “approve delete” to allow it');
  // Talking over the reading cuts it off: not heard, so it's read again.
  await say(lens);
  assert.equal(hub.calls.length, 0);
  assert.equal(brief(view(lens))[1], 'Listen first, then say it again');
  await wait(5);
  voice.finish();                                                 // heard to the end this time
  await wait(5);
  await say(lens);
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"allow"}']);
  lens.dispose();
});

test('moving with reading aloud off: a risky command can\'t be heard, so its verb isn\'t enough; "still" and a tap are', async () => {
  const { hub, lens, voice } = setup(['approve push', 'still'], { moving: true, readAloud: false });
  risky(hub, 'git push --force origin main');
  lens.open('n');
  view(lens);
  await wait(5);
  assert.equal(voice.spoken.length, 0);
  assert.equal(brief(view(lens))[1], 'Read aloud is off: say “unmute” to hear it');
  await wait(750);
  await say(lens);
  assert.equal(hub.calls.length, 0);
  assert.equal(brief(view(lens))[1], 'Hear it first: say “unmute”, or “still” and read it');
  await say(lens);                                                // still: the whole card, and a tap allows it
  assert.equal(card(view(lens)).warn, 'careful: force push can’t be undone');
  await wait(750);
  lens.tap();
  await wait(5);
  assert.deepEqual(hub.calls, ['answer ap1 {"decision":"allow"}']);
  lens.dispose();
});

test('moving: a question is read out with its numbered options', async () => {
  const { hub, lens, voice } = setup([], { moving: true });
  question(hub);
  lens.open('w');
  view(lens);
  await wait(5);
  assert.equal(voice.spoken.at(-1), 'hypernova asks: Which store? One: Room. Two: DataStore. Three: Files. Say one, two or three.');
  assert.deepEqual(brief(view(lens)), ['QUESTION   hypernova', 'say “one”, “two” or “three”']);
  lens.dispose();
});

// ------------------------------------------------------------------ what changed, show diff

test('"what changed?" says the last change and lists the files; a tap shows the diff, one text that scrolls through every file; "show diff" goes straight there', async () => {
  const { lens, voice } = setup(['what changed?', 'show diff']);
  lens.open('i');
  await say(lens);
  assert.equal(voice.spoken.at(-1), 'hyperscribe changed PairScreen.kt and PairTest.kt: 5 lines added, 1 removed. Fixed the pairing test.');
  let v = card(view(lens));
  assert.deepEqual(v.head, ['CHANGED', 'hyperscribe']);
  assert.equal(v.right, '2 files');
  assert.deepEqual(v.facts, ['+5 -1 in all']);
  assert.match(v.body[0], /^PairScreen\.kt +\+2 -1$/);
  assert.match(v.body[1], /^PairTest\.kt \(new\) +\+3 -0$/);
  checkPage(buildPage(v), 'changes');
  lens.tap();
  v = card(view(lens));
  assert.deepEqual(v.head, ['DIFF', 'PairScreen.kt']);
  assert.equal(v.right, '+2 -1', 'no page counter');
  assert.deepEqual(v.body.slice(0, 5), ['… line 12', '     fun start() {', '-     camera.open()', '+     camera.open().await()', '+     preview.show()']);
  // The next file follows in the same text, on a line of its own.
  assert.equal(v.body[5], '── PairTest.kt   +3 -0');
  checkPage(buildPage(v), 'diff');
  const layout = layoutKey(buildPage(v));
  lens.tap();                                                     // the next file, at the top
  v = card(view(lens));
  assert.deepEqual(v.head, ['DIFF', 'PairTest.kt']);
  assert.equal(v.right, '+3 -0');
  assert.deepEqual(v.body.slice(0, 2), ['── PairTest.kt   +3 -0', 'new file']);
  assert.equal(layoutKey(buildPage(v)), layout, 'scrolling is a text update');
  lens.up();
  assert.deepEqual(card(view(lens)).head, ['DIFF', 'PairScreen.kt']);
  lens.up();                                                      // back past its start: the list
  assert.deepEqual(card(view(lens)).head, ['CHANGED', 'hyperscribe']);
  lens.down();                                                    // and on into the diff again
  assert.deepEqual(card(view(lens)).head, ['DIFF', 'PairScreen.kt']);
  lens.tap();
  lens.tap();                                                     // after the last file, the list
  assert.deepEqual(card(view(lens)).head, ['CHANGED', 'hyperscribe']);
  lens.doubleTap();
  assert.deepEqual(card(view(lens)).head, ['DONE', 'hyperscribe']);
  await say(lens);
  assert.deepEqual(card(view(lens)).head, ['DIFF', 'PairScreen.kt']);
  lens.dispose();
});

test('every new screen fits the lens, moving or still', async () => {
  const { hub, lens } = setup(['debrief']);
  const shots: Array<[string, LensView]> = [];
  lens.startGuide(true); shots.push(['guide offer', view(lens)]);
  lens.startGuide(false, 3); shots.push(['guide hold', view(lens)]);
  lens.startGuide(false, 5); shots.push(['guide done', view(lens)]);
  lens.doubleTap();
  await say(lens); shots.push(['debrief', view(lens)]);
  risky(hub, 'git push --force origin main');
  lens.open('n'); shots.push(['risky', view(lens)]);
  for (const [label, v] of shots) checkPage(buildPage(v), label);
  lens.dispose();
});
