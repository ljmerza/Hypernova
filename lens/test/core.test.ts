// Pure logic: voice words and routing, the glance, a turn's facts, markdown to lens lines, pairing links.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseUtterance, matchProject, type VoiceContext } from '../src/core/voice.ts';
import { gapFor, parseTelescope, quarters, scopeLabel, sideBySide, sourceWord } from '../src/core/telescope.ts';
import { glanceRows, type GlanceMemory } from '../src/core/glance.ts';
import { turnFacts, testCounts, firstSentence, cardFacts, doneLine, shortFacts } from '../src/core/result.ts';
import { plainText, summaryLines, speakable, speechChunks, textLines, wrapAll } from '../src/core/text.ts';
import { hasMore, lastTop, MORE_W, scrollTo, settle, startScroll, swipe, windowLines } from '../src/core/scroll.ts';
import { parsePairLink } from '../src/core/pairing.ts';
import { ago, activityLabel, clockless, clockTime, duration, glassesBatteryOf, powerSpoken, wantsLine } from '../src/core/format.ts';
import { powerRows, powerWords } from '../src/core/power.ts';
import { clean, hasGlyph, nativeWidth, spread } from '../src/core/font.ts';
import { feedItems, feedLines, nowLines, toolsLine } from '../src/core/live.ts';
import { Store, type KeyValue } from '../src/data/store.ts';
import { leading, sessionName, type Agent, type Approval, type HnEvent, type MacBattery, type Session, type Snapshot } from '../src/core/types.ts';
import { DeviceInfo, DeviceStatus } from '@evenrealities/even_hub_sdk';

const NOW = 1_800_000_000_000;
const MIN = 60_000;

function session(id: string, projectName: string, state: Session['state'], extra: Partial<Session> = {}): Session {
  return { id, title: `${projectName} work`, cwd: `/p/${projectName}`, projectName, origin: 'terminal', state, model: null, permissionMode: 'default',
    createdAt: NOW - 60 * MIN, lastActivityAt: NOW - MIN, lastText: null, activity: null, pendingApprovalIds: [], stoppedForAway: false, ...extra };
}
const approval = (id: string, sessionId: string, command: string, createdAt = NOW - MIN): Approval => ({
  id, sessionId, createdAt, expiresAt: createdAt + 10 * MIN, kind: 'tool', toolName: 'Bash', summary: `Bash: ${command}`, input: { command },
  reason: null, canSession: false, questions: null, plan: null,
});
const snap = (sessions: Session[], approvals: Approval[] = []): Snapshot => ({
  mac: { id: 'm', name: 'Mac', daemonVersion: '0' }, usage: { fiveHour: { pct: 42, resetsAt: NOW + 2 * 3600_000 }, sevenDay: null, source: 'official', observedAt: NOW },
  sessions, approvals, projects: [...new Set(sessions.map(s => s.projectName))].map(name => ({ path: `/p/${name}`, name, lastActivityAt: NOW, sessionCount: 1 })),
});
const mem = (): GlanceMemory => ({ workingSince: new Map(), unseen: new Map() });

// ------------------------------------------------------------------ voice

const ctx = (): VoiceContext => {
  const s = snap([session('a', 'hypernova', 'working'), session('b', 'hyperscribe', 'idle'), session('c', '0stems', 'needs_you'), session('d', 'test_nova', 'idle')]);
  return { sessions: s.sessions, projects: [...s.projects, { path: '/p/prudnikov-site', name: 'prudnikov-site', lastActivityAt: null, sessionCount: 0 }], focusId: 'a' };
};

test('voice words are whole utterances, with filler and politeness ignored', () => {
  const c = ctx();
  assert.deepEqual(parseUtterance('Status.', c), { kind: 'word', word: 'status', rest: '' });
  assert.deepEqual(parseUtterance('Okay, approve!', c), { kind: 'word', word: 'approve', rest: '' });
  assert.deepEqual(parseUtterance('Read it to me please', c), { kind: 'word', word: 'read', rest: '' });
  assert.deepEqual(parseUtterance('Telescope.', c), { kind: 'word', word: 'telescope', rest: '', ask: {} });
  assert.deepEqual(parseUtterance('send it', c), { kind: 'word', word: 'send', rest: '' });
  assert.deepEqual(parseUtterance('Stop.', c), { kind: 'word', word: 'stop', rest: '' });
  assert.deepEqual(parseUtterance('never mind', c), { kind: 'word', word: 'discard', rest: '' });
});

test('"mute" and "unmute" (also "quiet", "un mute") turn reading aloud off and on', () => {
  const c = ctx();
  for (const said of ['Mute.', 'quiet', 'Be quiet please', 'okay mute it']) assert.deepEqual(parseUtterance(said, c), { kind: 'word', word: 'mute', rest: '' }, said);
  for (const said of ['Unmute', 'un mute', 'Sound on.']) assert.deepEqual(parseUtterance(said, c), { kind: 'word', word: 'unmute', rest: '' }, said);
  assert.equal(parseUtterance('mute the notification sound in settings', c).kind, 'prompt');
});

test('deny can carry a reason; a sentence that starts with "no" stays a prompt', () => {
  const c = ctx();
  assert.deepEqual(parseUtterance('Deny, push to a feature branch instead.', c), { kind: 'word', word: 'deny', rest: 'push to a feature branch instead.' });
  const p = parseUtterance('no tests ran, find out why', c);
  assert.equal(p.kind, 'prompt');
});

test('"No, …" answers a card that asks; anywhere else it corrects Claude', () => {
  const c = ctx();
  assert.deepEqual(parseUtterance('No, use the other file instead.', c), { kind: 'prompt', text: 'No, use the other file instead.', route: null, sendNow: false });
  assert.deepEqual(parseUtterance('No, that is wrong. Fix the header.', c).kind, 'prompt');
  assert.deepEqual(parseUtterance('No, use the other file instead.', { ...c, needs: true }), { kind: 'word', word: 'deny', rest: 'use the other file instead.' });
});

test('telescope: it picks, or a source, a page on the Mac, a PDF page; "show me" alone is the same; "show me …" is a prompt', () => {
  const c = ctx();
  const scope = (said: string) => { const u = parseUtterance(said, c); return u.kind === 'word' && u.word === 'telescope' ? { rest: u.rest, ask: u.ask } : u.kind; };
  for (const said of ['Telescope', 'telescope.', 'Tele scope', 'Okay, telescope please', 'Show me', 'show me.', 'telescope it', 'telescope what you built'])
    assert.deepEqual(scope(said), { rest: '', ask: {} }, said);
  const named: Array<[string, string]> = [
    ['telescope android', 'emulator'],
    ['telescope web', 'web'], ['Telescope the web page', 'web'], ['telescope browser', 'web'],
    ['telescope terminal', 'terminal'], ['telescope on the console', 'terminal'],
    ['telescope simulator', 'simulator'], ['telescope iPhone', 'simulator'], ['Telescope the iOS simulator.', 'simulator'],
    ['telescope image', 'image'], ['telescope the chart', 'image'], ['telescope PDF', 'image'],
    ['telescope window', 'window'], ['telescope the Mac app', 'window'],
  ];
  for (const [said, source] of named) assert.deepEqual(scope(said), { rest: '', ask: { source } }, said);
  // The glasses can't say a device id: "phone" and "emulator" say which kind of Android device.
  for (const said of ['Telescope emulator.', 'telescope the android emulator', 'telescope my emulator'])
    assert.deepEqual(scope(said), { rest: '', ask: { source: 'emulator', prefer: 'emulator' } }, said);
  for (const said of ['telescope phone', 'telescope my phone', 'telescope the android phone', 'telescope device'])
    assert.deepEqual(scope(said), { rest: '', ask: { source: 'emulator', prefer: 'phone' } }, said);
  // "my …", "the last …".
  assert.deepEqual(scope('telescope the last pdf'), { rest: '', ask: { source: 'image' } });
  assert.deepEqual(scope('telescope my latest chart'), { rest: '', ask: { source: 'image' } });
  assert.deepEqual(scope('telescope pdf page 3'), { rest: '', ask: { source: 'image', page: 3 } });
  assert.deepEqual(scope('Telescope page two.'), { rest: '', ask: { source: 'image', page: 2 } });
  // A page on the Mac: its address as said, path and all.
  assert.deepEqual(scope('telescope localhost 3000'), { rest: 'localhost 3000', ask: { source: 'web' } });
  assert.deepEqual(scope('Telescope localhost:3000/settings.'), { rest: 'localhost:3000/settings', ask: { source: 'web' } });
  assert.deepEqual(scope('telescope the app on port 5173'), { rest: 'port 5173', ask: { source: 'web' } });
  // "Show me …" starts ordinary prompts, so only "show me" alone is the telescope.
  for (const said of ['Show me the app.', 'Show me the diff', 'show me the failing tests', 'Show me how the auth flow works', 'show me localhost 3000'])
    assert.equal(scope(said), 'prompt', said);
  // Said to the glasses, "telescope …" is never a prompt: words it doesn't know let the Mac pick.
  for (const said of ['telescope the auth bug', 'telescope my app', 'Telescope that thing over there.'])
    assert.deepEqual(scope(said), { rest: '', ask: {} }, said);
  // "Page" alone is the web page; with a number, a PDF's.
  assert.deepEqual(scope('telescope page'), { rest: '', ask: { source: 'web' } });
  // "Watch": the telescope's picture, again and again for a minute.
  for (const said of ['Watch.', 'watch it', 'keep watching', 'telescope watch']) assert.deepEqual(parseUtterance(said, c), { kind: 'word', word: 'watch', rest: '' }, said);
  assert.equal(parseUtterance('watch the build and tell me when it fails', c).kind, 'prompt');
  assert.deepEqual(parseTelescope('telescopes window'), { source: 'window' });
  assert.deepEqual(parseTelescope('telescope the meaning of life'), {});
  assert.equal(parseTelescope('telescopic lens'), null);
  assert.equal(parseTelescope('show the telescope'), null);
});

test('"telescope" misheard: alone or before a source it is still the telescope; before anything else it stays a prompt', () => {
  const c = ctx();
  const scope = (said: string) => { const u = parseUtterance(said, c); return u.kind === 'word' && u.word === 'telescope' ? { rest: u.rest, ask: u.ask } : u.kind; };
  // What Soniox wrote for "Telescope terminal" on the owner's glasses.
  assert.deepEqual(scope('To the scope terminal.'), { rest: '', ask: { source: 'terminal' } });
  for (const said of ['To the scope.', 'tele scope', 'Tele-scope!', 'Telescopes.', 'The telescope.', 'Tell a scope.', 'to a scope', 'okay, to the scope'])
    assert.deepEqual(scope(said), { rest: '', ask: {} }, said);
  assert.deepEqual(scope('Tell a scope emulator.'), { rest: '', ask: { source: 'emulator', prefer: 'emulator' } });
  assert.deepEqual(scope('The telescope on the simulator'), { rest: '', ask: { source: 'simulator' } });
  assert.deepEqual(scope('tele scope my phone'), { rest: '', ask: { source: 'emulator', prefer: 'phone' } });
  assert.deepEqual(scope('telescopes window'), { rest: '', ask: { source: 'window' } });
  // These are ordinary words too: followed by anything but a source, a prompt as it was said.
  for (const said of ['To the scope of this change, add the tests.', 'Tell a scope story to the user in the README.', 'The telescope icon is too dim, make it brighter.',
    'to the scope terminal output add colours', 'tele scope creep is the problem here', 'Telescopes are a good name for the zoom.'])
    assert.equal(scope(said), 'prompt', said);
  const u = parseUtterance('To the scope of this change, add the tests.', c);
  assert.equal(u.kind === 'prompt' && u.text, 'To the scope of this change, add the tests.');
  // "telescope" itself is unchanged: never a prompt.
  assert.deepEqual(scope('telescope the auth bug'), { rest: '', ask: {} });
});

test('telescope zooms into the quarter closest in shape to the lens: corners, bands down a phone, columns', () => {
  // A page 1152 × 512: four corners, each exactly the lens' picture.
  assert.deepEqual(quarters(1152, 512), [{ x: 0, y: 0, w: 576, h: 256 }, { x: 576, y: 0, w: 576, h: 256 }, { x: 0, y: 256, w: 576, h: 256 }, { x: 576, y: 256, w: 576, h: 256 }]);
  // A phone, tall: four bands down it.
  assert.deepEqual(quarters(1080, 2400).map(r => [r.x, r.y, r.w, r.h]), [[0, 0, 1080, 600], [0, 600, 1080, 600], [0, 1200, 1080, 600], [0, 1800, 1080, 600]]);
  // A very wide strip: four columns.
  assert.deepEqual(quarters(4000, 200).map(r => r.x), [0, 1000, 2000, 3000]);
  // Odd sizes still cover every pixel once.
  const q = quarters(1281, 801);
  assert.equal(q.reduce((n, r) => n + r.w * r.h, 0), 1281 * 801);
});

test('a phone’s whole screen goes on the lens as two halves side by side; a page as it is', () => {
  assert.equal(sideBySide(1260, 2800), true);
  assert.equal(sideBySide(1080, 2400), true);
  assert.equal(sideBySide(1152, 512), false);
  assert.equal(sideBySide(1000, 1000), false);
  assert.equal(sideBySide(2560, 1600), false);
  // Twice as large, near enough: 256 / 1400 against 256 / 2800.
  const whole = Math.min(576 / 1260, 256 / 2800), halves = Math.min(576 / (2 * 1260 + gapFor(1260)), 256 / 1400);
  assert.ok(halves / whole > 1.9, String(halves / whole));
});

test('telescope’s label: the project, the source, how fresh', () => {
  const T = 1_800_000_000_000;
  // The label line in parts; how fresh on its right.
  assert.deepEqual(scopeLabel('hypernova', 'emulator', T - 2000, T), { head: ['TELESCOPE', 'hypernova', 'emulator'], age: 'just now' });
  assert.deepEqual(scopeLabel('hypernova', 'web', T - 40_000, T), { head: ['TELESCOPE', 'hypernova', 'web'], age: '40s ago' });
  assert.deepEqual(scopeLabel('hypernova', '', null, T), { head: ['TELESCOPE', 'hypernova'], age: '' });
  assert.equal(sourceWord('url'), 'web');
  assert.equal(sourceWord('terminal'), 'terminal');
});

test('a session that switched agents is never a prompt’s target: the one it went on in is', () => {
  const s = snap([session('old', 'hyperscribe', 'idle', { switchedTo: 'new', lastActivityAt: NOW }), session('new', 'hyperscribe', 'idle', { agent: 'codex', lastActivityAt: NOW - 5 * MIN })]);
  const p = parseUtterance('in hyperscribe run the tests', { sessions: s.sessions, projects: s.projects, focusId: null });
  assert.deepEqual(p.kind === 'prompt' && p.route, { kind: 'session', sessionId: 'new', project: 'hyperscribe' });
  assert.equal(sessionName(s.sessions[1]!), 'hyperscribe · Codex');
  assert.equal(sessionName(s.sessions[0]!), 'hyperscribe');
});

test('the sun follows the agent that leads: Claude Code, or Codex on a Mac without it', () => {
  const base = snap([session('a', 'hypernova', 'idle')]);
  assert.equal(leading(base).codex, false);
  assert.equal(leading({ ...base, claude: { available: true, version: '2.1' }, codex: { available: true, enabled: true, usage: null } }).codex, false);
  const solo = leading({ ...base, claude: { available: false, version: null }, codex: { available: true, enabled: true, usage: { fiveHour: null, sevenDay: { pct: 61, resetsAt: NOW + 3600_000 }, limited: false, observedAt: NOW } } });
  assert.equal(solo.codex, true);
  assert.equal(solo.usage?.sevenDay?.pct, 61);
  assert.equal(solo.account, undefined, 'Claude Code’s account doesn’t eclipse Codex’s sun');
});

test('naming a project routes the prompt to its session', () => {
  const p = parseUtterance('In hyperscribe, fix the export button.', ctx());
  assert.equal(p.kind, 'prompt');
  if (p.kind !== 'prompt') return;
  assert.equal(p.text, 'fix the export button.');
  assert.deepEqual(p.route, { kind: 'session', sessionId: 'b', project: 'hyperscribe' });
});

test('project names are matched as said: digits as words, separators as spaces, small mistakes', () => {
  const c = ctx();
  assert.equal(matchProject('zero stems: release notes', c)?.project.name, '0stems');
  assert.equal(matchProject('test nova, run it', c)?.project.name, 'test_nova');
  assert.equal(matchProject('hyper scribe, go', c)?.project.name, 'hyperscribe');
  assert.equal(matchProject('hyperskribe, go', c)?.project.name, 'hyperscribe');
  assert.equal(matchProject('the build, go', c), null);
});

test('"new session in <project>: …" starts one there', () => {
  const p = parseUtterance('New session in zero stems: write the release notes', ctx());
  assert.equal(p.kind, 'prompt');
  if (p.kind !== 'prompt') return;
  assert.equal(p.text, 'write the release notes');
  assert.deepEqual(p.route, { kind: 'new', projectPath: '/p/0stems', project: '0stems' });
});

test('a project with no session gets a new one', () => {
  const p = parseUtterance('in prudnikov-site, update the blog layout', ctx());
  assert.equal(p.kind === 'prompt' && p.route?.kind, 'new');
});

test('"… send it" at the end sends at once', () => {
  const p = parseUtterance('Fix the failing pairing test, send it.', ctx());
  assert.equal(p.kind, 'prompt');
  if (p.kind !== 'prompt') return;
  assert.equal(p.sendNow, true);
  assert.equal(p.text, 'Fix the failing pairing test');
  assert.equal(p.route, null);
});

test('"… and send it" leaves the "and" out of the prompt', () => {
  for (const said of ['Fix the bug and send it', 'Fix the bug, then send it.', 'Fix the bug and then send it']) {
    const p = parseUtterance(said, ctx());
    assert.equal(p.kind === 'prompt' && p.text, 'Fix the bug', said);
    assert.equal(p.kind === 'prompt' && p.sendNow, true, said);
  }
});

test('an ordinary sentence is a prompt for whoever is in front of you', () => {
  const p = parseUtterance('make the planet turn slower', ctx());
  assert.deepEqual(p, { kind: 'prompt', text: 'make the planet turn slower', route: null, sendNow: false });
});

// ------------------------------------------------------------------ glance

test('the glance puts what needs you first, then work, at most four lines', () => {
  const s = snap([
    session('w', 'hypernova', 'working', { activity: 'Bash: ./gradlew build', lastActivityAt: NOW - 2 * MIN }),
    session('i', 'site', 'idle', { lastActivityAt: NOW - 5 * MIN }),
    session('n', '0stems', 'needs_you', { pendingApprovalIds: ['ap'] }),
    session('old', 'old', 'idle', { lastActivityAt: NOW - 3 * 3600_000 }),
    session('e', 'ended', 'ended'),
    session('w2', 'hyperscribe', 'working', { activity: 'Thinking' }),
    session('i2', 'notes', 'idle', { lastActivityAt: NOW - 10 * MIN }),
  ], [approval('ap', 'n', 'git push origin main')]);
  const rows = glanceRows(s, { projects: null }, mem(), NOW);
  assert.deepEqual(rows.map(r => r.sessionId), ['n', 'w2', 'w', 'i']);
  // The phone's phrase, and the short one for a row without room for it (the page picks).
  assert.equal(rows[0].detail, 'wants to run git push origin main');
  assert.equal(rows[0].detailShort, 'wants to push to main');
  assert.equal(rows[2].detail, 'Bash: ./gradlew build');
  assert.equal(rows[1].detail, 'thinking');
});

test('a session whose agents work on after its turn ended reads as working', () => {
  const s = snap([
    session('a', 'hypernova', 'idle', { agentsRunning: 2, lastActivityAt: NOW - 3 * 3600_000 }),
    session('b', 'site', 'idle', { lastActivityAt: NOW - MIN }),
    session('c', 'notes', 'idle', { agentsRunning: 0, lastActivityAt: NOW - 3 * 3600_000 }),
  ]);
  assert.deepEqual(glanceRows(s, { projects: null }, mem(), NOW).map(r => [r.sessionId, r.state, r.detail]), [['a', 'working', '2 agents running'], ['b', 'idle', 'site work']]);
});

test('a working line says how many agents work alongside Claude; a job whose last agent just ended stays working', () => {
  const s = snap([
    session('a', 'hypernova', 'working', { activity: 'Bash: ./gradlew build', agentsRunning: 3 }),
    session('b', 'site', 'idle', { agentsRunning: 1, lastActivityAt: NOW - 2 * MIN }),
    session('c', 'notes', 'idle', { agentsRunning: 0, lastActivityAt: NOW - 3 * 3600_000 }),
  ]);
  const m = { ...mem(), held: new Set(['c']) };
  assert.deepEqual(glanceRows(s, { projects: null }, m, NOW).map(r => [r.sessionId, r.state, r.detail, r.tag]), [
    ['a', 'working', 'Bash: ./gradlew build', ['3 agents running']],
    ['b', 'working', '1 agent running', []],
    ['c', 'working', 'thinking', []],
  ]);
  assert.deepEqual(glanceRows(s, { projects: null }, mem(), NOW).map(r => r.sessionId), ['a', 'b'], 'without the hold it is idle and long gone');
  // No tool of its own to show: its agents are the line.
  const waits = snap([session('a', 'hypernova', 'working', { activity: null, agentsRunning: 9 })]);
  assert.deepEqual(glanceRows(waits, { projects: null }, mem(), NOW).map(r => [r.detail, r.tag]), [['9 agents running', []]]);
  // Prompts waiting stay whole too, apart from the agents.
  const queued = snap([session('a', 'hypernova', 'working', { activity: 'Bash: ./gradlew build', agentsRunning: 2, queued: 2 })]);
  assert.deepEqual(glanceRows(queued, { projects: null }, mem(), NOW)[0].tag, ['2 agents running', '2 queued']);
});

test('the glance can show only chosen projects', () => {
  const s = snap([session('a', 'hypernova', 'working'), session('b', 'site', 'working')]);
  assert.deepEqual(glanceRows(s, { projects: ['site'] }, mem(), NOW).map(r => r.sessionId), ['b']);
});

test('a finished turn not looked at yet shows its facts', () => {
  const m = mem();
  m.unseen.set('a', { at: NOW - MIN, facts: 'finished: 3 files, tests pass' });
  const rows = glanceRows(snap([session('a', 'hypernova', 'idle')]), { projects: null }, m, NOW);
  assert.equal(rows[0].state, 'done');
  assert.equal(rows[0].detail, 'finished: 3 files, tests pass');
});

test('words for time, usage and asks', () => {
  assert.equal(ago(5000), 'now');
  assert.equal(ago(2 * MIN + 5000), '2m');
  assert.equal(ago(72 * MIN), '1h 12m');
  // The clock's words, a pair being one: an hour going by is no news.
  for (const t of [ago(5000), ago(59 * MIN), ago(60 * MIN), ago(61 * MIN), ago(119 * MIN), ago(120 * MIN), ago(10 * 60 * MIN), ago(3 * 24 * 60 * MIN), duration(252_000)]) {
    assert.equal(clockless(`hypernova ${t} · resets ${t}`), 'hypernova · · resets ·', t);
  }
  assert.deepEqual([ago(59 * MIN), ago(60 * MIN), ago(61 * MIN), ago(119 * MIN), ago(120 * MIN), ago(10 * 60 * MIN), duration(252_000)], ['59m', '1h', '1h 1m', '1h 59m', '2h', '10h', '4m 12s']);
  assert.equal(clockless('wants to run make 2h3m'), 'wants to run make 2h3m');
  // As the phone and the Mac say it.
  assert.equal(activityLabel('Bash: ./gradlew build'), 'Bash: ./gradlew build');
  assert.equal(activityLabel('mcp__github__create_pr: x'), 'github create_pr: x');
  assert.equal(activityLabel('Thinking'), 'thinking');
  assert.equal(wantsLine(approval('x', 's', 'git push -u origin feature/lens')), 'wants to push to feature/lens');
  assert.equal(wantsLine(approval('x', 's', 'rm -rf build')), 'wants to delete build');
  assert.equal(wantsLine(approval('x', 's', 'npm publish')), 'wants to run npm publish');
  // What can't be undone reads as such on the glance and in the band too, not only on the card.
  assert.equal(wantsLine(approval('x', 's', 'git push origin --delete feature')), 'wants to delete branch feature');
  assert.equal(wantsLine(approval('x', 's', 'git push -d origin feature')), 'wants to delete branch feature');
  assert.equal(wantsLine(approval('x', 's', 'git push origin :feature')), 'wants to delete branch feature');
  assert.equal(wantsLine(approval('x', 's', 'git push --force origin main')), 'wants to force-push to main');
  assert.equal(wantsLine(approval('x', 's', 'git push -f origin main')), 'wants to force-push to main');
  assert.equal(wantsLine(approval('x', 's', 'git push --force-with-lease origin main')), 'wants to force-push to main');
  assert.equal(wantsLine(approval('x', 's', 'git push origin +main')), 'wants to force-push to main');
  assert.equal(wantsLine(approval('x', 's', 'git push origin HEAD:release')), 'wants to push to release');
});

test('the time of day as the phone\'s locale writes it: 24 hours or two halves', () => {
  const at = Date.UTC(2026, 9, 15, 14, 5, 30);
  assert.equal(clockTime(at, { locale: 'en-GB', timeZone: 'UTC' }), '14:05');
  assert.equal(clockTime(at, { locale: 'ru-RU', timeZone: 'Europe/Moscow' }), '17:05');
  assert.match(clockTime(at, { locale: 'en-US', timeZone: 'UTC' }), /^2:05\s?PM$/);
  // Before ten in the morning, 24-hour locales keep the leading zero.
  assert.equal(clockTime(Date.UTC(2026, 9, 15, 7, 0), { locale: 'en-GB', timeZone: 'UTC' }), '07:00');
  // Locales that write the time in other scripts keep their 12 or 24 hours in glyphs the lens has.
  for (const locale of ['ar-EG', 'ko-KR', 'hi-IN']) assert.match(clockTime(at, { locale, timeZone: 'UTC' }), /^2:05\s?PM$/, locale);
  for (const locale of ['ja-JP', 'zh-CN', 'de-DE']) assert.equal(clockTime(at, { locale, timeZone: 'UTC' }), '14:05', locale);
  for (const locale of ['en-US', 'ar-EG']) assert.ok([...clean(clockTime(at, { locale, timeZone: 'UTC' }))].every(hasGlyph), locale);
});

test('the batteries: each named, the Mac\'s only when it has one, brighter when one runs low', () => {
  const mac = (percent: number, extra: Partial<MacBattery> = {}): MacBattery => ({ percent, charging: false, onAC: false, minutesLeft: 200, ...extra });
  const rows = (g: Parameters<typeof powerRows>[0], m: Parameters<typeof powerRows>[1]) => powerRows(g, m).map(r => [r.label, ...powerWords(r)]);
  assert.deepEqual(rows({ percent: 84, charging: false }, mac(62)), [['GLASSES', '84%'], ['MAC', '62%']]);
  assert.deepEqual(rows({ percent: 84, charging: true }, mac(61.6, { charging: true, onAC: true })), [['GLASSES', '84%', 'charging'], ['MAC', '62%', 'charging']]);
  // A Mac without a battery (null), or from before batteries (missing): the glasses alone, or nothing.
  assert.deepEqual(rows({ percent: 84, charging: false }, null), [['GLASSES', '84%']]);
  assert.deepEqual(rows(null, mac(62)), [['MAC', '62%']]);
  assert.deepEqual(rows(null, null), []);
  assert.deepEqual(rows(undefined, undefined), []);
  // Low and not charging (nor on power) says so, and a word in "status".
  const low = (g: number, m: MacBattery) => powerRows({ percent: g, charging: false }, m)[0].low;
  assert.equal(low(15, mac(80)), true);
  assert.equal(low(20, mac(80)), true, 'the one rule: 20% or less');
  assert.equal(low(21, mac(80)), false);
  assert.equal(powerRows({ percent: 15, charging: true }, mac(12, { onAC: true })).some(r => r.low), false);
  assert.equal(powerSpoken({ percent: 15, charging: false }, mac(12)), "The glasses' battery is at 15 percent. Your Mac's battery is at 12 percent.");
  assert.equal(powerSpoken({ percent: 84, charging: false }, mac(62)), '');
  // What the Even App's device status says, or nothing for nonsense.
  assert.deepEqual(glassesBatteryOf({ batteryLevel: 84, isCharging: false }), { percent: 84, charging: false });
  assert.deepEqual(glassesBatteryOf({ batteryLevel: '57', isCharging: true }), { percent: 57, charging: true });
  for (const s of [null, {}, { batteryLevel: -1 }, { batteryLevel: 140 }, { batteryLevel: Number.NaN }, { batteryLevel: '' }]) assert.equal(glassesBatteryOf(s), null);
});

test('the glasses\' battery: the 0 and the "none" the SDK fills in for what the Even App left out are no reading', () => {
  // A push that says the glasses connected but not their level, and one only about being worn.
  assert.equal(glassesBatteryOf(DeviceStatus.fromJson({ sn: 'G2', connectType: 'connected' })), null);
  assert.equal(glassesBatteryOf(DeviceStatus.fromJson({ sn: 'G2', isWearing: true })), null);
  // A device the Even App knows with no status yet, and the SDK's default status.
  assert.equal(glassesBatteryOf(DeviceInfo.fromJson({ sn: 'G2', model: 'g2' }).status), null);
  assert.equal(glassesBatteryOf(DeviceStatus.createDefault('G2')), null);
  // Real levels, 0 on the charger among them.
  assert.deepEqual(glassesBatteryOf(DeviceStatus.fromJson({ sn: 'G2', connectType: 'connected', batteryLevel: 84 })), { percent: 84, charging: false });
  assert.deepEqual(glassesBatteryOf(DeviceStatus.fromJson({ sn: 'G2', connectType: 'connected', batteryLevel: 0, isCharging: true })), { percent: 0, charging: true });
  assert.deepEqual(powerRows(glassesBatteryOf(DeviceStatus.fromJson({ sn: 'G2', connectType: 'connected' })), null), []);
});

// ------------------------------------------------------------------ results

function turn(): HnEvent[] {
  let n = 0;
  const id = () => `e${n++}`;
  const tool = (name: string, input: Record<string, unknown>, extra: Partial<Extract<HnEvent, { kind: 'tool' }>> = {}): HnEvent => ({
    id: id(), kind: 'tool', ts: NOW, toolUseId: id(), name, summary: '', input, status: 'done', output: null, exitCode: 0, durationMs: 10, path: (input.file_path as string) ?? null, ...extra,
  });
  return [
    { id: id(), kind: 'user', ts: NOW - 10 * MIN, text: 'old prompt' },
    tool('Edit', { file_path: '/p/old.ts' }),
    { id: id(), kind: 'user', ts: NOW - 4 * MIN, text: 'fix the export' },
    tool('Read', { file_path: '/p/a.ts' }),
    tool('Edit', { file_path: '/p/a.ts' }),
    tool('Edit', { file_path: '/p/a.ts' }),
    tool('Write', { file_path: '/p/b.test.ts' }),
    tool('Edit', { file_path: '/p/c.ts' }, { status: 'error' }),
    tool('Bash', { command: 'npm test' }, { status: 'error', exitCode: 1, output: '# pass 11\n# fail 1' }),
    tool('Bash', { command: 'npm test' }, { output: '# pass 12\n# fail 0' }),
    tool('Bash', { command: 'npm run build' }, { output: 'built in 1.2s' }),
    { id: id(), kind: 'assistant', ts: NOW - 30_000, text: 'Fixed the export. It **remembers** the folder now.\n\n- a.ts\n- b.test.ts', streaming: false },
    { id: id(), kind: 'result', ts: NOW, ok: true, durationMs: 252_000, costUsd: null, turns: 5, error: null },
  ];
}

test('a turn\'s facts come from its own events', () => {
  const f = turnFacts(turn());
  assert.equal(f.prompt, 'fix the export');
  assert.deepEqual(f.files, ['/p/a.ts', '/p/b.test.ts']);
  assert.deepEqual(f.tests && { ok: f.tests.ok, passed: f.tests.passed, failed: f.tests.failed }, { ok: true, passed: 12, failed: 0 });
  assert.equal(f.build?.ok, true);
  assert.equal(f.durationMs, 252_000);
  // Phrases: the card's a sentence of them, the row's shorter.
  assert.deepEqual(cardFacts(f), ['2 files changed', '12 tests passed', 'build passed', 'took 4m 12s']);
  assert.equal(shortFacts(f), '2 files, 12 tests pass');
  assert.equal(doneLine(f), 'finished: 2 files, 12 tests pass');
  assert.equal(doneLine({ ...f, ok: false, error: 'boom' }), 'stopped: 2 files, 12 tests pass');
  assert.equal(firstSentence(f.reply), 'Fixed the export.');
});

test('a turn that ran agents says so on its card', () => {
  const ev = turn();
  const agentCall = (id: string): HnEvent => ({ id, kind: 'tool', ts: NOW - MIN, toolUseId: id, name: 'Agent', summary: 'survey', input: {}, status: 'done', output: null, exitCode: null, durationMs: null, path: null });
  const f = turnFacts([...ev.slice(0, -2), agentCall('x1'), agentCall('x2'), ...ev.slice(-2)]);
  assert.equal(f.agents, 2);
  assert.deepEqual(cardFacts(f), ['2 files changed', '12 tests passed', 'build passed', '2 agents', 'took 4m 12s']);
});

// ------------------------------------------------------------------ a session at work

const W = 520;
const agent = (id: string, label: string, status: Agent['status'], extra: Partial<Agent> = {}): Agent => ({
  id, sessionId: 's', parentAgentId: null, workflowId: null, label, type: 'general-purpose', status, activity: null,
  startedAt: NOW - 5 * MIN, endedAt: null, lastActivityAt: NOW, result: null, ...extra,
});
const toolEv = (id: string, ts: number, name: string, summary: string): HnEvent =>
  ({ id, kind: 'tool', ts, toolUseId: id, name, summary, input: {}, status: 'done', output: null, exitCode: 0, durationMs: 1, path: null });

test('the top box: the main agent\'s tool, then its agents at work, newest first, and how many didn\'t fit', () => {
  const agents = [
    agent('a1', 'survey:parser', 'running', { startedAt: NOW - 3 * MIN, activity: 'Read: core/Solar.kt' }),
    agent('a2', 'survey:widget', 'running', { startedAt: NOW - 2 * MIN, activity: 'Grep: “x” in widget' }),
    agent('a3', 'survey:types', 'done', { endedAt: NOW - MIN, result: 'All fine.' }),
    agent('a4', 'survey:old', 'running', { startedAt: NOW - MIN, superseded: true }),
  ];
  const working = session('s', 'hypernova', 'working', { activity: 'Bash: ./gradlew build', agentsRunning: 2 });
  // What runs in the phone's words; an agent and what it runs, a gap apart.
  assert.deepEqual(nowLines(working, agents, 2), { lines: ['› Bash: ./gradlew build', '• survey:widget   Grep: “x” in widget'], more: 1 });
  assert.deepEqual(nowLines(working, agents, 3).more, 0);
  // Claude waits on its agents: they are the box.
  const waiting = session('s', 'hypernova', 'idle', { agentsRunning: 2 });
  assert.deepEqual(nowLines(waiting, agents, 2).lines, ['• survey:widget   Grep: “x” in widget', '• survey:parser   Read: core/Solar.kt']);
  // Before the list comes (or from a Mac without it), the session's own count.
  assert.deepEqual(nowLines(waiting, null, 2).lines, ['• 2 agents running']);
  assert.deepEqual(nowLines(session('s', 'hypernova', 'working', { activity: null }), [], 2).lines, ['› thinking']);
  // Thinking with agents at work: the agents fill the box.
  assert.deepEqual(nowLines(session('s', 'hypernova', 'working', { activity: null, agentsRunning: 2 }), agents, 2).lines, ['• survey:widget   Grep: “x” in widget', '• survey:parser   Read: core/Solar.kt']);
});

test('a run of tools is one line, the newest last, with how many came before it', () => {
  const tools = ['core/Solar.kt', 'core/Comet.kt', 'ui/Cosmos.kt', 'test/CometTest.kt'].map((f, i) => toolEv(`t${i}`, NOW, i % 2 ? 'Edit' : 'Read', f)) as Array<Extract<HnEvent, { kind: 'tool' }>>;
  assert.equal(toolsLine(tools.slice(0, 1), W), '› Read: core/Solar.kt');
  assert.equal(toolsLine(tools.slice(0, 2), W), '› Read: core/Solar.kt   Edit: core/Comet.kt');
  const narrow = toolsLine(tools, 330);
  assert.match(narrow, /^› \+\d {3}.*Edit: test\/CometTest\.kt$/);
  assert.ok(nativeWidth(narrow) <= 330);
});

test('the output box: this turn only, Claude\'s words, runs of tools, and agents\' reports where they landed', () => {
  const T = NOW - 10 * MIN;
  const events: HnEvent[] = [
    { id: 'u0', kind: 'user', ts: T - 60 * MIN, text: 'the turn before' },
    { id: 'x0', kind: 'assistant', ts: T - 59 * MIN, text: 'Old reply, never shown live.', streaming: false },
    { id: 'u1', kind: 'user', ts: T, text: 'draw comets' },
    { id: 'x1', kind: 'assistant', ts: T + 1000, text: 'I’ll survey first.', streaming: false },
    { id: 'th', kind: 'thinking', ts: T + 1500, chars: 300 },
    toolEv('t1', T + 2000, 'Edit', 'core/Comet.kt'),
    toolEv('t2', T + 3000, 'Edit', 'ui/Cosmos.kt'),
    toolEv('t3', T + 9000, 'Bash', './gradlew test'),
    { id: 'x2', kind: 'assistant', ts: T + 10_000, text: 'Tests pass.', streaming: false },
  ];
  const agents = [
    agent('a1', 'survey:parser', 'done', { endedAt: T + 5000, result: 'Three readers left.' }),
    agent('a2', 'survey:widget', 'running'),
    agent('a3', 'survey:before', 'done', { endedAt: T - 30 * MIN, result: 'From the turn before.' }),
  ];
  assert.deepEqual(feedLines(events, agents, W), [
    '» draw comets',
    'I’ll survey first.',
    '› Edit: core/Comet.kt   Edit: ui/Cosmos.kt',
    '• survey:parser — Three readers left.',
    '› Bash: ./gradlew test',
    'Tests pass.',
  ]);
  assert.equal(feedItems(events, agents, W).length, 6);
  // A report that lands after everything comes last; a long one wraps.
  const late = feedLines(events, [agent('a9', 'review', 'done', { endedAt: NOW, result: 'word '.repeat(60) })], W);
  assert.ok(late.at(-1)!.startsWith('word') && late.length > 8 && late.every(l => nativeWidth(l) <= W));
  assert.deepEqual(feedLines([], null, W), []);
});

test('reading aloud turned off with the old "Off" voice comes back as the switch', async () => {
  const kv = (saved: object): KeyValue => ({ get: async k => (k === 'lens.settings' ? JSON.stringify(saved) : ''), set: async () => true });
  const st = new Store();
  await st.load(kv({ voice: 'off', rate: 1.2 }));
  assert.deepEqual([st.settings.readAloud, st.settings.voice, st.settings.rate], [false, 'auto', 1.2]);
  await st.load(kv({ voice: 'mac:Samantha' }));
  assert.deepEqual([st.settings.readAloud, st.settings.voice], [true, 'mac:Samantha']);
  await st.load(kv({ voice: 'off', readAloud: true }));
  assert.equal(st.settings.readAloud, true);
});

test('test counts from the common runners', () => {
  assert.deepEqual(testCounts('Tests:       2 failed, 40 passed, 42 total'), { passed: 40, failed: 2 });
  assert.deepEqual(testCounts('==== 48 passed, 2 skipped in 3.21s ===='), { passed: 48, failed: null });
  assert.deepEqual(testCounts('test result: ok. 12 passed; 0 failed; 0 ignored'), { passed: 12, failed: 0 });
  assert.deepEqual(testCounts('Executed 12 tests, with 1 failure (0 unexpected)'), { passed: 11, failed: 1 });
  assert.deepEqual(testCounts('48 tests completed, 3 failed'), { passed: 45, failed: 3 });
  assert.deepEqual(testCounts('  14 passing (2s)\n  1 failing'), { passed: 14, failed: 1 });
});

// ------------------------------------------------------------------ text

test('markdown becomes plain lens lines', () => {
  const md = '## Plan\nFirst this,\nthen that.\n\n- **one** thing\n- `two`\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```ts\nconst x = 1\n```\n[link](http://x) and _it_';
  // A table's cells a gap apart, which wrapping keeps (three spaces; clean() alone would make them one).
  assert.equal(plainText(md), 'Plan\nFirst this, then that.\n• one thing\n• two\na   b\n1   2\nconst x = 1\nlink and it');
  assert.deepEqual(wrapAll('name   size   date', 500), ['name   size   date']);
  assert.deepEqual(wrapAll('a  b', 500), ['a b'], 'two spaces are only a space');
  assert.equal(speakable('Done.\n\n```\ncode\n```\n- item one'), 'Done.\n(code)\nitem one.');
});

test('read-aloud text goes to the Mac in pieces of at most 8000 characters, cut between paragraphs', () => {
  const para = (n: number) => `${'word '.repeat(Math.floor(n / 5) - 1)}end.`;
  const text = [para(3000), para(3000), para(3000), para(20_000)].join('\n');
  const chunks = speechChunks(text);
  assert.ok(chunks.every(c => c.length <= 8000), chunks.map(c => c.length).join(','));
  assert.equal(chunks[0], [para(3000), para(3000)].join('\n'));
  assert.equal(chunks.join(' ').replace(/\s+/g, ' '), text.replace(/\s+/g, ' '));
  assert.deepEqual(speechChunks('Short.'), ['Short.']);
  assert.ok(speechChunks('x'.repeat(9000)).every(c => c.length <= 8000));
});

test('long words break; a text is one run of lines, and a part of it wraps as the whole does', () => {
  const lines = wrapAll('see /Users/you/Programming/hypernova/android/app/src/main/java/com/prudnikweb/hypernova/data/LiveHub.kt now', 300);
  assert.ok(lines.every(l => nativeWidth(l) <= 300));
  const text = `${'word '.repeat(60).trim()}\nA second paragraph, on a line of its own.`;
  const all = textLines(text, 400);
  assert.ok(all.length > 5 && all.every(l => nativeWidth(l) <= 400));
  assert.equal(all.at(-1), 'A second paragraph, on a line of its own.');
  // Words arriving like speech: the lines so far are the whole text's, but the last, still growing.
  const part = textLines(text.slice(0, 200), 400);
  assert.deepEqual(part.slice(0, -1), all.slice(0, part.length - 1));
  assert.ok(all[part.length - 1].startsWith(part.at(-1)!));
});

test('a text that scrolls: a window less a line a swipe; back stops following, the end follows again', () => {
  const rows = 5;
  let s = startScroll(12);
  assert.deepEqual([s.top, s.follow], [0, true]);
  // Read on: four lines a swipe, so the line you were on stays in view; never past the end.
  let m = swipe(s, 1, rows);
  assert.deepEqual([m.s.top, m.moved], [4, true]);
  m = swipe(m.s, 1, rows);
  assert.deepEqual([m.s.top, m.moved, m.s.follow], [lastTop(12, rows), true, true]);
  assert.ok(!hasMore(m.s, rows));
  assert.equal(swipe(m.s, 1, rows).moved, false, 'a swipe on at the end moves nothing');
  // Back: up a window, and the window no longer follows what arrives.
  m = swipe(m.s, -1, rows);
  assert.deepEqual([m.s.top, m.s.follow], [3, false]);
  s = settle(m.s, 16, rows, true);
  assert.equal(s.top, 3, 'scrolled back, new lines don\'t move it');
  // Swiped to the end again: following, and the next new line brings the window down.
  s = swipe(swipe(s, 1, rows).s, 1, rows).s;
  assert.deepEqual([s.top, s.follow], [lastTop(16, rows), true]);
  s = settle(s, 17, rows, true);
  assert.equal(s.top, lastTop(17, rows));
  // Not arriving (All at once, a plan, a diff): growth leaves it where it is.
  assert.equal(settle({ top: 0, lines: 3, follow: true }, 30, rows, false).top, 0);
  // A text that shrank (a voice starting again from the top) pulls the window up with it.
  assert.equal(settle({ top: 20, lines: 30, follow: true }, 2, rows, true).top, 0);
  assert.equal(scrollTo(startScroll(30), 12, rows).top, 12);
  assert.equal(scrollTo(startScroll(14), 12, rows).top, lastTop(14, rows));
});

test('the window keeps its size, and marks more to read at the right of its last line', () => {
  const width = 520;
  const lines = textLines('word '.repeat(200).trim(), width - MORE_W);
  const w = windowLines(lines, 0, 5, width);
  assert.equal(w.length, 5);
  assert.ok(w[4].endsWith('↓') && w[4].startsWith(lines[4]), w[4]);
  assert.ok(w.every(l => nativeWidth(l) <= width), 'the mark never pushes a line past its box');
  assert.equal(windowLines(lines, lines.length - 5, 5, width)[4], lines.at(-1), 'nothing more: no mark');
  assert.deepEqual(windowLines(['one'], 0, 3, width), ['one', '', '']);
  assert.ok(!windowLines([...lines.slice(0, 6), '', ''], 1, 5, width, 6)[4].endsWith('↓'), 'blank lines after the text aren\'t more');
});

test('a plan in three lines', () => {
  const s = summaryLines('## Plan\n1. Add CORS.\n2. Speech from say.\n3. Screenshots.\n4. Docs.', 500, 3);
  assert.deepEqual(s, { lines: ['1. Add CORS.', '2. Speech from say.', '3. Screenshots.'], steps: 4 });
});

test('spread puts the right part at the right edge, within half a space', () => {
  for (const right of ['2m', '22m', 'now', '1h 12m']) {
    const line = spread('hypernova · BASH ./gradlew build', right, 520);
    assert.ok(Math.abs(nativeWidth(line) - 520) <= 2.5, `${right}: ${nativeWidth(line)}`);
  }
});

// ------------------------------------------------------------------ pairing

test('pairing links in every shape the Mac makes', () => {
  assert.deepEqual(parsePairLink('hypernova://pair?v=1&u=https%3A%2F%2Fmac.tail.ts.net%3A47321&c=abc&n=MacBook'), { baseUrl: 'https://mac.tail.ts.net:47321', code: 'abc', macName: 'MacBook' });
  assert.deepEqual(parsePairLink('https://mac.tail.ts.net:47321/pair?c=xyz&n=Air'), { baseUrl: 'https://mac.tail.ts.net:47321', code: 'xyz', macName: 'Air' });
  assert.deepEqual(parsePairLink('scan: intent://pair?v=1&u=https%3A%2F%2Fm.ts.net%3A47321&c=q#Intent;scheme=hypernova;end'), { baseUrl: 'https://m.ts.net:47321', code: 'q', macName: 'm.ts.net' });
  assert.equal(parsePairLink('hypernova://pair?v=2&u=https%3A%2F%2Fm&c=q'), null);
  assert.equal(parsePairLink('https://example.com/'), null);
});
