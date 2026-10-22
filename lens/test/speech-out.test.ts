// Read aloud by the Hypernova app on the phone, as the glasses see it (PROTOCOL.md, "Read aloud"):
// the play goes to the app when one plays for the glasses, its progress keeps the text in step and
// the wave moving, a risky reading counts as heard only when the app played it to the end, and with
// no app (or one that never starts) the glasses read it themselves. Plus the wave on the lens.
import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { WaveBeats, playOver, readProgress, waveGlyph, waveText, type PlayProgress } from '../src/core/speech-out.ts';
import { buildPage, type LensView } from '../src/lens/pages.ts';
import type { HubEvent, HubLike } from '../src/data/hub.ts';
import { DEFAULT_SETTINGS } from '../src/data/store.ts';

// VoiceOut listens for the first touch on the page; a test has no page, and no Web Audio.
(globalThis as any).document ??= { addEventListener() {} };
(globalThis as any).window ??= {};
const { VoiceOut } = await import('../src/data/voice.ts');

const tick = () => new Promise(r => setTimeout(r, 0));

/** The Mac as the glasses reach it: one phone app playing their speech (or none). */
function fakeHub(phone = true) {
  const listeners = new Set<(e: HubEvent) => void>();
  const asked: Array<{ text: string; sessionId: string | null }> = [];
  const stopped: string[] = [];
  let n = 0;
  const hub = {
    status: 'live',
    snapshot: { speech: { engine: 'soniox', using: 'soniox', voice: 'Adrian', macVoice: null, rate: 1, soniox: true, note: null, phone } },
    subscribe(fn: (e: HubEvent) => void) { listeners.add(fn); return () => listeners.delete(fn); },
    async playOnPhone(text: string, sessionId: string | null) {
      asked.push({ text, sessionId });
      return phone ? { id: `p${++n}`, engine: 'soniox', of: text.length, to: { id: 'phone', name: 'Pixel' } } : null;
    },
    async stopOnPhone(id: string) { stopped.push(id); },
    async speak() { throw new Error('no WebView audio in a test'); },
  };
  const progress = (p: Partial<PlayProgress> & { id: string; state: PlayProgress['state'] }) => {
    const full = readProgress({ ms: 0, chars: 0, of: 100, level: 0, engine: 'soniox', why: null, ...p })!;
    for (const fn of listeners) fn({ t: 'speech_progress', progress: full });
  };
  return { hub: hub as unknown as HubLike, asked, stopped, progress };
}

test('the wave: dots in silence, a bar for a loud syllable, spreading outward a beat later', () => {
  assert.equal(waveGlyph(0), '.');
  assert.equal(waveGlyph(0.3), ':');
  assert.equal(waveGlyph(0.9), '|');
  assert.equal(waveText([]), '. . . . .');
  assert.equal(waveText([1]), '. . | . .');
  const w = new WaveBeats();
  w.next(1);
  assert.equal(w.next(0.3), '. | : | .');
  // Symmetric, five glyphs, the font's own characters.
  const t = w.next(0.8);
  assert.equal(t.split(' ').length, 5);
  assert.deepEqual(t.split(' '), [...t.split(' ')].reverse());
  w.reset();
  assert.equal(w.next(0), '. . . . .');
});

test('progress from the Mac is read with care', () => {
  assert.equal(readProgress(null), null);
  assert.equal(readProgress({ id: 'x', state: 'dancing' }), null);
  const p = readProgress({ id: 'x', state: 'playing', ms: -3, chars: 12, of: 40, level: 7 })!;
  assert.equal(p.ms, 0);
  assert.equal(p.level, 1);
  assert.equal(p.why, null);
  assert.ok(playOver('ended') && playOver('stopped') && playOver('failed') && !playOver('playing'));
});

test('a voice reading says so first on the header’s right side, and moving, after the first line', () => {
  const orrery = { sun: null, planets: [] };
  // The glasses' own font: a steady "reading aloud" (a wave would be three Bluetooth writes a second).
  const card: LensView = { kind: 'card', head: ['DONE', 'hypernova'], right: 'plan', lead: '', body: ['The build passed.'], facts: [], hint: ['tap stop reading', 'swipe scroll', 'double tap back'], orrery, wave: 'reading aloud' };
  const header = buildPage(card).boxes.find(b => b.name === 'header')!;
  assert.match(header.content, /^DONE {3}hypernova\s+reading aloud {3}plan$/);
  const brief = buildPage({ kind: 'brief', lines: [['hypernova', 'finished'], 'tap read'], wave: 'reading aloud' }).boxes[0]!;
  assert.equal(brief.content, 'hypernova   finished   reading aloud');
  // Without a voice, the header is as it was.
  assert.doesNotMatch(buildPage({ ...card, wave: null }).boxes.find(b => b.name === 'header')!.content, /reading/);
});

test('the phone app reads it: in step with its progress, and heard whole only when it played to the end', async () => {
  const f = fakeHub();
  const voice = new VoiceOut(() => f.hub, () => DEFAULT_SETTINGS);
  const text = 'x'.repeat(100);
  const reading = voice.speakWhole(text, { sessionId: 's1' });
  await tick();
  assert.deepEqual(f.asked, [{ text, sessionId: 's1' }]);
  assert.equal(voice.speaking, true);
  f.progress({ id: 'p1', state: 'playing', chars: 40, level: 0.7 });
  assert.equal(voice.progress(), 0.4);
  assert.equal(voice.level(), 0.7);
  // Another play's news isn't this one's.
  f.progress({ id: 'p9', state: 'ended' });
  assert.equal(voice.speaking, true);
  f.progress({ id: 'p1', state: 'ended', chars: 100 });
  assert.deepEqual(await reading, { who: 'app', whole: true });
  assert.equal(voice.speaking, false);
  assert.equal(voice.level(), 0);
});

test('stopped on the phone (its Stop, a call, headphones out): not heard to the end', async () => {
  const f = fakeHub();
  const voice = new VoiceOut(() => f.hub, () => DEFAULT_SETTINGS);
  const reading = voice.speakWhole('Approve push to main?');
  await tick();
  f.progress({ id: 'p1', state: 'playing', chars: 5 });
  f.progress({ id: 'p1', state: 'stopped', why: 'phone' });
  assert.deepEqual(await reading, { who: 'app', whole: false });
});

test('a tap on the glasses stops the phone too', async () => {
  const f = fakeHub();
  const voice = new VoiceOut(() => f.hub, () => DEFAULT_SETTINGS);
  const reading = voice.speakWhole('A long reply.');
  await tick();
  f.progress({ id: 'p1', state: 'playing', chars: 2 });
  voice.stop();
  assert.deepEqual(f.stopped, ['p1']);
  const r = await reading;
  assert.equal(r.whole, false);
  assert.equal(voice.speaking, false);
});

test('no phone app, or one that never starts: the glasses read it themselves', async () => {
  // None plays for the glasses: not even asked.
  const none = fakeHub(false);
  (none.hub.snapshot as any).speech.phone = false;
  const v1 = new VoiceOut(() => none.hub, () => DEFAULT_SETTINGS);
  assert.equal(await v1.speak('Hello.'), 'locked', 'the WebView path, which needs a touch here');
  assert.equal(none.asked.length, 0);
  // Asked, but it never played.
  const f = fakeHub();
  const v2 = new VoiceOut(() => f.hub, () => DEFAULT_SETTINGS);
  const reading = v2.speak('Hello.');
  await tick();
  f.progress({ id: 'p1', state: 'failed', why: 'no_start' });
  assert.equal(await reading, 'locked');
  assert.equal(f.asked.length, 1);
});

test('a phone that accepts the play but never makes a sound: told to stop, and the glasses take over', async () => {
  mock.timers.enable({ apis: ['setInterval', 'Date'] });
  try {
    const f = fakeHub();
    const voice = new VoiceOut(() => f.hub, () => DEFAULT_SETTINGS);
    const reading = voice.speakWhole('Approve push to main?');
    await tick();
    mock.timers.tick(31_000);
    const r = await reading;
    assert.deepEqual(f.stopped, ['p1']);
    assert.equal(r.whole, false);
    assert.notEqual(r.who, 'app', 'not counted as read by the phone');
  } finally {
    mock.timers.reset();
  }
});
