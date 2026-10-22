// Pure logic behind new sessions from the glasses, pop-ups, the battery card, limits and Transmission.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { folderName, uniqueName } from '../src/core/nebula.ts';
import { blockedLine, limitKey, limitSpoken, sunMode } from '../src/core/limits.ts';
import { limitCorner } from '../src/core/meter.ts';
import { awakeLine, awakeModeFor, powerRows, powerWords, segmentsOf } from '../src/core/power.ts';
import { advance, codeAtoms, MIN_STEP_MS, nextStep, syncEnd, type Stream } from '../src/core/transmission.ts';
import { bandWords, DEFAULT_POPS, DONE_MS, PopQueue, STALE_MS, type Pop } from '../src/core/popups.ts';
import { parseUtterance, type VoiceContext } from '../src/core/voice.ts';
import { plainText } from '../src/core/text.ts';
import { buildPage, type LensView } from '../src/lens/pages.ts';
import { nativeWidth, LINE } from '../src/core/font.ts';
import type { Account, MacAwake, Provider, Session, Usage } from '../src/core/types.ts';
import { clockTime, dayClock } from '../src/core/format.ts';

const NOW = 1_800_000_000_000;
const MIN = 60_000, HOUR = 60 * MIN;

// ------------------------------------------------------------------ Nebula

test('a new session’s folder is named from its first prompt: short kebab-case, filler skipped', () => {
  assert.equal(folderName('Build me a landing page for my bakery, with a menu and opening hours'), 'landing-page-bakery-menu');
  assert.equal(folderName('fix the login page'), 'fix-login-page');
  assert.equal(folderName('Can you please write a Telegram bot that posts the weather?'), 'telegram-bot-posts-weather');
  assert.equal(folderName('Café crème tracker'), 'cafe-creme-tracker');
  assert.equal(folderName('Сделай бота для погоды'), 'sdelay-bota-dlya-pogody');
  assert.equal(folderName('please, could you'), 'session');
  assert.ok(folderName('supercalifragilisticexpialidocious antidisestablishmentarianism words').length <= 32);
  assert.equal(uniqueName('landing-page', ['blog', 'landing-page']), 'landing-page-2');
  assert.equal(uniqueName('landing-page', ['Landing-Page', 'landing-page-2']), 'landing-page-3');
  assert.equal(uniqueName('blog', []), 'blog');
});

test('"new session: …" and "new project, …" go to Nebula; "new session in 0stems: …" to that project', () => {
  const ctx: VoiceContext = { sessions: [], projects: [{ path: '/p/0stems', name: '0stems', lastActivityAt: NOW, sessionCount: 1 }], focusId: null };
  assert.deepEqual(parseUtterance('New session: a landing page for my bakery', ctx), { kind: 'prompt', text: 'a landing page for my bakery', route: { kind: 'nebula', project: 'Nebula' }, sendNow: false });
  assert.deepEqual(parseUtterance('start a new project, weather bot, send it', ctx), { kind: 'prompt', text: 'weather bot', route: { kind: 'nebula', project: 'Nebula' }, sendNow: true });
  assert.deepEqual(parseUtterance('new session in zero stems: write notes', ctx).kind === 'prompt' && parseUtterance('new session in zero stems: write notes', ctx), { kind: 'prompt', text: 'write notes', route: { kind: 'new', projectPath: '/p/0stems', project: '0stems' }, sendNow: false });
  // Without a break after it, "new session" is just words in a prompt.
  assert.equal((parseUtterance('new session handling is broken', ctx) as { route: unknown }).route, null);
});

test('"battery", "stay awake" and "let it sleep" are words; longer sentences stay prompts', () => {
  const ctx: VoiceContext = { sessions: [], projects: [], focusId: null };
  for (const s of ['Battery.', 'batteries', "how's the battery"]) assert.deepEqual(parseUtterance(s, ctx), { kind: 'word', word: 'battery', rest: '' }, s);
  for (const s of ['Stay awake', 'keep the Mac awake', 'backpack mode']) assert.deepEqual(parseUtterance(s, ctx), { kind: 'word', word: 'awake', rest: '' }, s);
  for (const s of ['Let it sleep.', 'you can sleep now']) assert.deepEqual(parseUtterance(s, ctx), { kind: 'word', word: 'sleep', rest: '' }, s);
  assert.equal(parseUtterance('the battery icon is wrong in the widget', ctx).kind, 'prompt');
  assert.equal(parseUtterance('let it sleep for a second before retrying', ctx).kind, 'prompt');
});

// ------------------------------------------------------------------ limits

const usage = (five: number, week = 20, extra: Partial<Usage> = {}): Usage => ({ fiveHour: { pct: five, resetsAt: NOW + 2 * HOUR }, sevenDay: { pct: week, resetsAt: NOW + 50 * HOUR }, source: 'official', observedAt: NOW, ...extra });
const account = (state: Account['state'], limit: Account['limit'] = null): Account => ({ state, limit, since: NOW - 10 * MIN });
const session = (blocked: Session['blocked']): Session => ({ id: 's', title: 't', cwd: '/p/x', projectName: 'x', origin: 'terminal', state: 'idle', model: null, permissionMode: 'default',
  createdAt: NOW - HOUR, lastActivityAt: NOW - MIN, lastText: null, activity: null, pendingApprovalIds: [], stoppedForAway: false, blocked });

test('the sun: the disk, quiet at the 5-hour limit, the low state at the weekly one (outranking it), eclipsed while usage can’t be followed', () => {
  assert.equal(sunMode(usage(42), account('ok'), NOW), 'disk');
  assert.equal(sunMode(usage(100), account('ok'), NOW), 'quiet');
  assert.equal(sunMode(usage(60), account('limited', { window: 'five_hour', label: 'session limit', resetsAt: NOW + HOUR, autoResumeAt: null, usingCredits: false }), NOW), 'quiet');
  assert.equal(sunMode(usage(100, 100), account('ok'), NOW), 'low');
  assert.equal(sunMode(usage(10), account('limited', { window: 'seven_day', label: 'weekly limit', resetsAt: NOW + 30 * HOUR, autoResumeAt: null, usingCredits: false }), NOW), 'low');
  assert.equal(sunMode(usage(42, 20, { source: 'stale' }), account('logged_out'), NOW), 'eclipse');
  assert.equal(sunMode(usage(42), account('on_hold'), NOW), 'eclipse');
  // Past a limit on usage credits Claude carries on: a star, "on usage credits" in the corner.
  const credits = account('limited', { window: 'five_hour', label: 'session limit', resetsAt: NOW + HOUR, autoResumeAt: null, usingCredits: true });
  assert.equal(sunMode(usage(60), credits, NOW), 'disk');
  assert.deepEqual(limitCorner(usage(60), credits, NOW), ['on usage credits']);
  // A limit whose reset has passed is over.
  assert.equal(sunMode({ ...usage(100), fiveHour: { pct: 100, resetsAt: NOW - 1 } }, account('ok'), NOW), 'disk');
});

test('the glance’s corner and "status" say the limits as the phone does', () => {
  // Its parts (the page puts a gap between them): the spending, and when it resets.
  assert.deepEqual(limitCorner(usage(42), account('ok'), NOW), ['42%', 'resets in 2h']);
  // Spent: until when, at the time it lifts (with its weekday past 20 hours).
  assert.deepEqual(limitCorner(usage(100), account('ok'), NOW), [`limit until ${clockTime(NOW + 2 * HOUR)}`]);
  assert.deepEqual(limitCorner(usage(100, 100), account('ok'), NOW), [`week limit until ${dayClock(NOW + 50 * HOUR, NOW)}`]);
  assert.match(dayClock(NOW + 50 * HOUR, NOW), /^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) \d/);
  assert.deepEqual(limitCorner(usage(42), account('logged_out'), NOW), ['signed out']);
  assert.deepEqual(limitCorner(usage(42), account('no_credit'), NOW), ['out of credits']);
  assert.match(limitSpoken(usage(100), account('limited', { window: 'five_hour', label: 'session limit', resetsAt: NOW + 2 * HOUR, autoResumeAt: NOW + 2 * HOUR, usingCredits: false }), NOW),
    /^You’re at the session limit\. Waiting sessions carry on by themselves in 2 hours\.$/);
  assert.equal(limitSpoken(usage(100, 100), account('ok'), NOW), 'The weekly limit is used up. Claude is back in 50 hours.');
  assert.equal(limitSpoken(usage(42, 85), account('ok'), NOW), 'Usage is at 42 percent, resetting in 2 hours. This week is at 85 percent.');
  // One pop-up per episode: the key changes with the episode, not with each session that hits it.
  const lim = account('limited', { window: 'five_hour', label: 'session limit', resetsAt: NOW + HOUR, autoResumeAt: null, usingCredits: false });
  assert.equal(limitKey(usage(100), lim, NOW), limitKey(usage(100), lim, NOW + MIN));
  assert.equal(limitKey(usage(42), account('ok'), NOW), null);
});

test('the pace first: “limit in 47m” when it reaches the limit before the reset, as the phone’s notch and the Mac’s arrow warn', () => {
  const rate = (fiveHour: number, fiveHourEtaMs: number | null = null) => ({ rate: { fiveHour, sevenDay: null, fiveHourEtaMs, sevenDayEtaMs: null, working: true } });
  // The Mac's own time to the limit, before the reset (2 h away): the corner says the limit.
  assert.deepEqual(limitCorner(usage(62, 20, rate(0.8, 47 * MIN)), account('ok'), NOW), ['62%', 'limit in 47m']);
  // Worked out from the pace when the Mac gives no time: (100 - 62) / 0.8 %/min = 47.5 minutes.
  assert.deepEqual(limitCorner(usage(62, 20, rate(0.8)), account('ok'), NOW), ['62%', 'limit in 47m']);
  // A pace that ends before the limit: the reset, as without one.
  assert.deepEqual(limitCorner(usage(42, 20, rate(0.12)), account('ok'), NOW), ['42%', 'resets in 2h']);
  // "status" says it too.
  assert.equal(limitSpoken(usage(62), account('ok'), NOW, 47 * MIN), 'Usage is at 62 percent. At this pace you reach the limit in 47 minutes, before it resets.');
});

test('a model’s own week takes the corner only while it is the tightest limit', () => {
  const fable = (pct: number, resetsAt: number | null = NOW + 50 * HOUR) => ({ models: [{ label: 'Sonnet', pct: 3, resetsAt: NOW + 70 * HOUR }, { label: 'Fable', pct, resetsAt }] });
  const at50 = dayClock(NOW + 50 * HOUR, NOW);
  // Fuller than the 5-hour window and the week: Fable's numbers, its reset with its weekday (it's days away).
  assert.deepEqual(limitCorner(usage(42, 60, fable(91)), account('ok'), NOW), ['Fable 91%', `resets ${at50}`]);
  // Not the tightest (the week is fuller, or a tie with the 5-hour window): the 5-hour window, as before.
  assert.deepEqual(limitCorner(usage(42, 95, fable(91)), account('ok'), NOW), ['42%', 'resets in 2h']);
  assert.deepEqual(limitCorner(usage(91, 20, fable(91)), account('ok'), NOW), ['91%', 'resets in 2h']);
  // Used up (Claude Code stopped it: a model limit, which leaves the sun a star).
  const modelLimit = account('limited', { window: 'model', label: 'Fable limit', resetsAt: NOW + 50 * HOUR, autoResumeAt: null, usingCredits: false });
  assert.deepEqual(limitCorner(usage(42, 60, fable(100)), modelLimit, NOW), [`Fable limit until ${at50}`]);
  assert.equal(sunMode(usage(42, 60, fable(100)), modelLimit, NOW), 'disk');
  // Stopped before the Mac's next look at /usage: the limit fills Fable's week now, at the limit's
  // reset, even when /usage names the week after the full model name; a limit on another model doesn't.
  const lastLook = { models: [{ label: 'Fable 5.1', pct: 97, resetsAt: NOW + 70 * HOUR }] };
  assert.deepEqual(limitCorner(usage(42, 60, lastLook), modelLimit, NOW), [`Fable 5.1 limit until ${at50}`]);
  assert.deepEqual(limitCorner(usage(42, 60, fable(30)), modelLimit, NOW), [`Fable limit until ${at50}`]);
  const opusLimit = account('limited', { window: 'model', label: 'Opus limit', resetsAt: NOW + 50 * HOUR, autoResumeAt: null, usingCredits: false });
  assert.deepEqual(limitCorner(usage(42, 60, lastLook), opusLimit, NOW), ['Fable 5.1 97%', `resets ${dayClock(NOW + 70 * HOUR, NOW)}`]);
  // The 5-hour limit still comes first; a week past its reset is empty; no reset said, none shown.
  assert.deepEqual(limitCorner(usage(100, 60, fable(100)), account('ok'), NOW), [`limit until ${clockTime(NOW + 2 * HOUR)}`]);
  assert.deepEqual(limitCorner(usage(42, 60, fable(99, NOW - MIN)), account('ok'), NOW), ['42%', 'resets in 2h']);
  assert.deepEqual(limitCorner(usage(42, 60, fable(91, null)), account('ok'), NOW), ['Fable 91%']);
  // A Mac that sends no models: as before.
  assert.deepEqual(limitCorner(usage(42, 60), account('ok'), NOW), ['42%', 'resets in 2h']);
});

test('a paused session’s line, in the phone’s words: until when, then carries on, paused at, ready to continue, or what stops it', () => {
  const lim = account('limited', { window: 'five_hour', label: 'session limit', resetsAt: NOW + HOUR, autoResumeAt: null, usingCredits: false });
  const at = clockTime(NOW + HOUR);
  assert.equal(blockedLine(session({ kind: 'limit', text: '', resetsAt: NOW + HOUR, autoResumeAt: NOW + HOUR }), lim, NOW), `session limit until ${at}, then carries on`);
  assert.equal(blockedLine(session({ kind: 'limit', text: '', resetsAt: NOW + HOUR, autoResumeAt: null }), lim, NOW), `session limit until ${at}`);
  assert.equal(blockedLine(session({ kind: 'limit', text: '', resetsAt: null, autoResumeAt: null }), lim, NOW), 'paused at the session limit');
  // Over, and it didn't carry on by itself: it waits for "continue".
  assert.equal(blockedLine(session({ kind: 'limit', text: '', resetsAt: NOW - MIN, autoResumeAt: null, resume: { state: 'ready', at: NOW - MIN } }), lim, NOW), 'session limit over, ready to continue');
  // Claude Code's own words name a limit the account doesn't know about; days away, the weekday.
  assert.equal(blockedLine(session({ kind: 'model', text: 'You’ve hit your Opus limit · resets 4pm', resetsAt: null, autoResumeAt: null }), account('ok'), NOW), 'this model isn’t available');
  assert.equal(blockedLine(session({ kind: 'limit', text: 'You’ve hit your weekly limit · resets Mon', resetsAt: NOW + 30 * HOUR, autoResumeAt: null }), account('ok'), NOW), `weekly limit until ${dayClock(NOW + 30 * HOUR, NOW)}`);
  assert.equal(blockedLine(session({ kind: 'logged_out', text: '', resetsAt: null, autoResumeAt: null }), account('logged_out'), NOW), 'signed out on the Mac');
  assert.equal(blockedLine(session({ kind: 'no_subscription', text: '', resetsAt: null, autoResumeAt: null }), account('no_subscription'), NOW), 'can’t reply without a Claude plan');
  assert.equal(blockedLine(session(null), account('ok'), NOW), null);
});

test('another provider’s trouble in the phone’s words, not “can’t reply right now”', () => {
  const on = (blocked: Session['blocked']) => ({ ...session(blocked), providerId: 'or' });
  const provider = (status: Provider['status']): Provider => ({ id: 'or', name: 'OpenRouter', builtIn: false, status });
  const b = { kind: 'provider' as const, text: 'API Error: 402', resetsAt: null, autoResumeAt: null };
  const line = (st: Provider['status'], blocked: Session['blocked'] = b) => blockedLine(on(blocked), account('ok'), NOW, [provider(st)]);
  assert.equal(line({ state: 'no_credit', since: NOW, resetsAt: null }), 'OpenRouter is out of credits');
  assert.equal(line({ state: 'no_credit', since: NOW, resetsAt: null, budget: true }), 'OpenRouter reached its budget');
  assert.equal(line({ state: 'bad_key', since: NOW, resetsAt: null }), 'OpenRouter refused its key');
  assert.equal(line({ state: 'limited', since: NOW, resetsAt: NOW + HOUR }), `OpenRouter is rate limited until ${clockTime(NOW + HOUR)}`);
  assert.equal(line({ state: 'limited', since: NOW, resetsAt: null }), 'OpenRouter is rate limited');
  assert.equal(line({ state: 'down', since: NOW, resetsAt: null }), 'OpenRouter isn’t answering');
  assert.equal(line({ state: 'teapot', since: NOW, resetsAt: null }), 'OpenRouter can’t reply');
  // It carries on by itself, or it's over and waits for "continue".
  assert.equal(line({ state: 'limited', since: NOW, resetsAt: NOW + HOUR }, { ...b, resume: { state: 'armed', at: NOW + HOUR } }), `OpenRouter carries on by itself at ${clockTime(NOW + HOUR)}`);
  assert.equal(line(null, { ...b, resume: { state: 'ready', at: NOW } }), 'OpenRouter is ready to continue');
  // A Mac that doesn't say which provider: as before.
  assert.equal(blockedLine(session(b), account('ok'), NOW), 'can’t reply right now');
});

// ------------------------------------------------------------------ the battery card

test('the battery card: the glasses first, then the Mac; a Mac without a battery isn’t listed; five segments', () => {
  assert.deepEqual([0, 1, 20, 21, 59, 80, 81, 100].map(segmentsOf), [0, 1, 1, 2, 3, 4, 5, 5]);
  const rows = powerRows({ percent: 84, charging: false }, { percent: 12, charging: false, onAC: false, minutesLeft: 30 });
  assert.deepEqual(rows.map(r => [r.device, r.label, r.percent, r.segments, r.low]), [['glasses', 'GLASSES', 84, 5, false], ['mac', 'MAC', 12, 1, true]]);
  assert.deepEqual(rows.map(powerWords), [['84%'], ['12%', 'low']]);
  assert.deepEqual(powerRows(null, null), []);
  assert.deepEqual(powerRows({ percent: 50, charging: true }, null).map(powerWords), [['50%', 'charging']]);
  // On the charger without charging (optimized charging) isn't low.
  assert.equal(powerRows(null, { percent: 10, charging: false, onAC: true, minutesLeft: null })[0].low, false);
});

test('Stay awake in a few words, and what "stay awake" and "let it sleep" ask for', () => {
  const a = (p: Partial<MacAwake>): MacAwake => ({ mode: 'working', holding: false, lidClosedOk: false, backpackReady: false, until: null, reason: null, ...p });
  assert.equal(awakeLine(null, NOW), null);
  assert.equal(awakeLine(a({ mode: 'off' }), NOW), 'Sleeps as usual');
  assert.equal(awakeLine(a({ holding: true }), NOW), 'Awake while working');
  // Phrases, as the phone's Mac card says them.
  assert.match(awakeLine(a({ mode: 'backpack', holding: true, lidClosedOk: true, until: NOW + HOUR }), NOW)!, /^Awake with the lid closed until \d/);
  // The daemon's codes in words; a guard counting down says when.
  assert.equal(awakeLine(a({ reason: 'battery', minBattery: 15 }), NOW), 'Can sleep: battery at 15% or less');
  assert.equal(awakeLine(a({ mode: 'backpack', reason: 'setup' }), NOW), 'Can sleep: Backpack needs its setup on the Mac');
  assert.match(awakeLine(a({ mode: 'backpack', holding: true, reason: 'hot', sleepAt: NOW + 2 * MIN }), NOW)!, /^Sleeps at \d[^·]*: running hot$/);
  assert.equal(awakeModeFor('awake', a({ backpackReady: true })), 'backpack');
  assert.equal(awakeModeFor('awake', a({ backpackReady: false })), 'working');
  assert.equal(awakeModeFor('sleep', a({ mode: 'backpack' })), 'working');
  assert.equal(awakeModeFor('sleep', a({ mode: 'working' })), 'off');
});

test('the battery card fits the lens: two rows beside their art, Stay awake under the Mac', () => {
  const views: LensView[] = [
    { kind: 'power', head: 'BATTERIES', right: '12:59 PM', rows: powerRows({ percent: 100, charging: true }, { percent: 100, charging: true, onAC: true, minutesLeft: null }), awake: 'Awake with the lid closed until 12:59 PM', empty: '', hint: ['double tap back', 'say “stay awake” or “let it sleep”'] },
    { kind: 'power', head: 'BATTERIES', right: '9:41', rows: powerRows({ percent: 7, charging: false }, null), awake: 'Keeps awake while working', empty: '', hint: 'double tap back' },
    { kind: 'power', head: 'BATTERIES', right: '9:41', rows: [], awake: null, empty: 'No battery to show: the glasses haven’t said yet', hint: 'double tap back' },
  ];
  for (const v of views) {
    const p = buildPage(v);
    assert.ok(p.boxes.length + 1 <= 8);
    for (const b of p.boxes) for (const l of b.content.split('\n')) assert.ok(nativeWidth(l) <= b.w - 2 * (b.border + b.pad), `${b.name}: ${l}`);
    assert.equal(p.images.length, v.kind === 'power' && v.rows.length ? 1 : 0);
  }
  const p = buildPage(views[1]);
  assert.match(p.boxes.find(b => b.name === 'awake')!.content, /^Mac: Keeps awake/);
});

// ------------------------------------------------------------------ Transmission

test('text arrives a few words at a time, with pauses at commas and longer ones at full stops and paragraphs', () => {
  const text = 'Comets are in, and they fade. 112 tests pass.\nThe surveys found three readers';
  const steps: Array<{ shown: string; ms: number }> = [];
  for (let at = 0; at < text.length;) { const s = nextStep(text, at, [], 180); steps.push({ shown: text.slice(at, s.end).trim(), ms: s.ms }); at = s.end; }
  assert.deepEqual(steps.map(s => s.shown), ['Comets are in,', 'and they fade.', '112 tests pass.', 'The surveys found', 'three readers']);
  const word = 60_000 / 180;
  assert.equal(steps[0].ms, Math.round((3 + 0.6) * word));      // a comma
  assert.equal(steps[1].ms, Math.round((3 + 1.4) * word));      // a full stop
  assert.equal(steps[2].ms, Math.round((3 + 2.2) * word));      // a paragraph
  assert.ok(steps.every(s => s.ms >= MIN_STEP_MS));
});

test('code arrives whole: a fenced block and an inline command are never cut', () => {
  const md = 'Run `./gradlew test --info` to check.\n\n```\nnpm ci\nnpm test\n```\nDone.';
  const plain = plainText(md);
  const atoms = codeAtoms(md, plain);
  assert.deepEqual(atoms.map(([a, b]) => plain.slice(a, b)), ['./gradlew test --info', 'npm ci\nnpm test']);
  const ends: number[] = [];
  for (let at = 0; at < plain.length;) { const s = nextStep(plain, at, atoms, 170); ends.push(s.end); at = s.end; }
  // No step ends inside code: each piece of it shows in one step.
  for (const [a, b] of atoms) assert.ok(!ends.some(e => e > a && e < b), `a step ends inside "${plain.slice(a, b)}"`);
  assert.ok(ends.includes(atoms[1][1]), 'the block shows whole, in one step');
  // In step with the voice: never inside code, always at a word's end.
  assert.equal(plain.slice(0, syncEnd(plain, atoms, 0.12)), 'Run ');
  assert.ok(!/gradlew$/.test(plain.slice(0, syncEnd(plain, atoms, 0.3))));
});

test('a stream shows what’s due; new text after a pause starts from now, not in one burst', () => {
  const s0: Stream = { text: '', atoms: [], shown: 0, nextAt: NOW, seenAt: NOW };
  let s = advance(s0, 'One two three four five six.', [], NOW, 180);
  assert.equal(s.text.slice(0, s.shown), 'One two three');
  s = advance(s, s.text, [], NOW + 10_000, 180);
  assert.equal(s.shown, s.text.length);
  // Ten seconds later, more of a streaming message: it starts arriving now.
  const longer = `${s.text} Seven eight nine ten eleven.`;
  s = advance(s, longer, [], NOW + 20_000, 180);
  assert.equal(longer.slice(0, s.shown), 'One two three four five six. Seven eight nine');
});

// ------------------------------------------------------------------ pop-ups

const pop = (key: string, kind: Pop['kind'], at = NOW, ttl: number | null = kind === 'done' ? DONE_MS : null): Pop =>
  ({ key, kind, head: [kind.toUpperCase(), 'x'], detail: `${kind} detail`, short: `x ${kind}`, sessionId: 'x', at, ttl });

test('pop-ups: needs-you leads and stays, done fades ~6 s after it shows; several merge into one band', () => {
  assert.deepEqual(DEFAULT_POPS, { needs: true, done: false, stopped: false, limit: false, battery: true, sleep: true, note: true });
  const q = new PopQueue();
  q.add(pop('done:a', 'done'));
  q.add(pop('battery', 'battery'));
  q.add(pop('needs:1', 'needs', NOW + 100));
  const shown = q.visible(NOW + 200, false);
  assert.deepEqual(shown.map(p => p.kind), ['needs', 'battery', 'done']);
  // Merged as a phrase: what leads, and the rest by name.
  assert.deepEqual(bandWords(shown), { head: ['NEEDS', 'x'], right: '+2', detail: 'needs detail, and x battery and x done', line: 'x needs: needs detail, and x battery and x done' });
  // Several that need you: who.
  const two = [{ ...pop('needs:1', 'needs'), who: '0stems' }, { ...pop('needs:2', 'needs'), who: 'hyperscribe' }];
  assert.equal(bandWords(two)!.detail, '0stems and hyperscribe need you');
  // A sentence leads: the rest is another.
  assert.equal(bandWords([{ ...pop('battery', 'battery'), detail: 'Your Mac is at 18%. Plug it in or its sessions stop.' }, pop('done:a', 'done')])!.detail, 'Your Mac is at 18%. Plug it in or its sessions stop. Also x done.');
  assert.equal(q.nextFade(), NOW + 200 + DONE_MS);
  assert.deepEqual(q.visible(NOW + 200 + DONE_MS + 1, false).map(p => p.kind), ['needs', 'battery']);
  // The same key replaces; dropping by subject.
  q.add(pop('needs:1', 'needs', NOW + 300));
  assert.equal(q.size, 2);
  q.drop(p => p.kind === 'needs');
  assert.deepEqual(q.visible(NOW + 400, false).map(p => p.kind), ['battery']);
});

test('pop-ups wait while you talk or are read to; a done one that waited too long is dropped unseen', () => {
  const q = new PopQueue();
  q.add(pop('done:a', 'done'));
  q.add(pop('needs:1', 'needs'));
  assert.deepEqual(q.visible(NOW + 1000, true), []);
  // Held, nothing starts fading: shown after, with its full time.
  const after = q.visible(NOW + 5000, false);
  assert.deepEqual(after.map(p => p.kind), ['needs', 'done']);
  assert.equal(q.nextFade(), NOW + 5000 + DONE_MS);
  const q2 = new PopQueue();
  q2.add(pop('done:b', 'done'));
  q2.visible(NOW + 1000, true);
  assert.deepEqual(q2.visible(NOW + STALE_MS + 1, false), []);
});

test('the band fits over every screen: the bottom two lines, or beside a picture; a full-lens picture keeps its tiles', () => {
  const band = { head: ['NEEDS YOU', 'prudnikov-site-with-a-long-name'], right: '+3', detail: 'wants to run git push --force origin main and then a lot more words that will not fit' };
  const orrery = { sun: { pct: 42, limit: false }, planets: [] };
  const views: LensView[] = [
    { kind: 'glance', left: 'hypernova/', right: '42%', empty: '', hint: 'x', orrery, rows: [1, 2, 3, 4].map(i => ({ project: `p${i}`, detail: 'd', right: '1m', state: 'working' as const, focused: false })), newRow: { label: 'New session', focused: false }, band },
    { kind: 'live', head: ['WORKING', 'x'], right: 'now', now: ['a', 'b'], more: 0, output: ['1', '2', '3', '4', '5'], hint: 'x', orrery, band },
    { kind: 'card', head: ['DONE', 'x'], right: '', lead: '', body: ['a', 'b', 'c'], facts: ['f', 'g'], factsRows: 2, hint: 'x', orrery, band },
    { kind: 'telescope', head: ['TELESCOPE', 'x'], right: '', status: '', hint: '', picture: true, loading: false, key: 'k', zoom: null, crisp: false, band },
  ];
  for (const v of views) {
    const p = buildPage(v);
    assert.ok(p.boxes.length + 1 <= 8, `${v.kind}: ${p.boxes.length} boxes`);
    for (const b of p.boxes) {
      const lines = b.content.split('\n');
      assert.ok(lines.length <= Math.max(1, Math.floor((b.h - 2 * b.border - 2 * b.pad) / LINE)), `${v.kind}: ${b.name} lines`);
      for (const l of lines) assert.ok(nativeWidth(l) <= b.w - 2 * (b.border + b.pad), `${v.kind}: ${b.name} "${l}"`);
    }
    if (v.kind === 'telescope') {
      assert.equal(p.boxes.find(b => b.name === 'band'), undefined);
      assert.match(p.boxes.find(b => b.name === 'header')!.content, /^NEEDS YOU {3}prudnikov-site.* \+3 {3}tap open$/);
      assert.equal(p.images.length, buildPage({ ...v, band: null }).images.length, 'the picture keeps every tile');
    } else {
      const b = p.boxes.find(x => x.name === 'band')!;
      assert.match(b.content, /^NEEDS YOU {3}prudnikov-site.* \+3 {5}tap open {5}swipe away\nwants to run git push/);
      for (const o of p.boxes) if (o !== b) assert.ok(o.y + o.h <= b.y || o.x + o.w <= b.x, `${v.kind}: ${o.name} under the band`);
    }
  }
});
