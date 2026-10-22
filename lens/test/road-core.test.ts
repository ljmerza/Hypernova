// On a bike or a walk, the pure parts: the phrases, risky commands, what changed and its diff, the
// debrief, the guide's steps and moving mode's two lines.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseUtterance, type VoiceContext } from '../src/core/voice.ts';
import { optionNumber } from '../src/core/phrases.ts';
import { approvalRisk, commandRisk, verbMatches } from '../src/core/risk.ts';
import { changesSpoken, codeLines, diffText, turnChanges } from '../src/core/changes.ts';
import { debrief } from '../src/core/debrief.ts';
import { GUIDE_STEPS, guideDots, guideGesture, type GuideState } from '../src/core/lens-guide.ts';
import { nativeWidth } from '../src/core/font.ts';
import { briefOf } from '../src/lens/moving.ts';
import { buildPage, CARD_TEXT_W, type LensView } from '../src/lens/pages.ts';
import type { Approval, HnEvent, Session, Snapshot } from '../src/core/types.ts';

const c: VoiceContext = { sessions: [], projects: [], focusId: null };
const word = (said: string) => { const u = parseUtterance(said, c); return u.kind === 'word' ? { word: u.word, rest: u.rest, ...(u.n ? { n: u.n } : {}) } : 'prompt'; };

test('the cycling and walking phrases are words; sentences that start with them stay prompts', () => {
  assert.deepEqual(word('Debrief.'), { word: 'debrief', rest: '' });
  assert.deepEqual(word('catch me up'), { word: 'debrief', rest: '' });
  assert.deepEqual(word('Next'), { word: 'next', rest: '' });
  assert.deepEqual(word('Back.'), { word: 'back', rest: '' });
  assert.deepEqual(word('previous page'), { word: 'previous', rest: '' });
  assert.deepEqual(word('Plan first.'), { word: 'plan', rest: '' });
  assert.deepEqual(word('Go ahead'), { word: 'goahead', rest: '' });
  assert.deepEqual(word('Continue.'), { word: 'continue', rest: '' });
  assert.deepEqual(word('Eclipse'), { word: 'eclipse', rest: '' });
  assert.deepEqual(word('Eclipse off.'), { word: 'eclipseoff', rest: '' });
  assert.deepEqual(word("I'm moving"), { word: 'moving', rest: '' });
  assert.deepEqual(word('Still.'), { word: 'still', rest: '' });
  assert.deepEqual(word('What changed?'), { word: 'changes', rest: '' });
  assert.deepEqual(word('Show diff'), { word: 'diff', rest: '' });
  assert.deepEqual(word('guide'), { word: 'guide', rest: '' });
  // As before: "show me the diff" is a prompt to Claude.
  assert.equal(word('show me the diff'), 'prompt');
  assert.equal(word('continue with the parser and add tests'), 'prompt');
  assert.equal(word('go ahead and rename the module'), 'prompt');
  assert.equal(word('next, fix the header'), 'prompt');
});

test('Auto and Bypass are never chosen by voice: those words are refused', () => {
  for (const said of ['Auto mode', 'bypass', 'Bypass permissions.', 'yolo']) assert.deepEqual(word(said), { word: 'refused', rest: '' }, said);
});

test('"one", "two", "three" pick an option; misheard numbers only on their own', () => {
  assert.deepEqual(word('One.'), { word: 'option', rest: '', n: 1 });
  assert.deepEqual(word('option two'), { word: 'option', rest: '', n: 2 });
  assert.deepEqual(word('The third one'), { word: 'option', rest: '', n: 3 });
  assert.deepEqual(word('3'), { word: 'option', rest: '', n: 3 });
  assert.equal(optionNumber('to'), 2);
  assert.equal(optionNumber('pick to'), null);
  assert.equal(word('one more thing: rename it'), 'prompt');
});

test('"Note: …" keeps the idea as said; "note that …" is a prompt', () => {
  assert.deepEqual(word('Note: comets should fade faster at night.'), { word: 'note', rest: 'comets should fade faster at night.' });
  assert.deepEqual(word('Okay, note, buy a bell for the bike'), { word: 'note', rest: 'buy a bell for the bike' });
  assert.deepEqual(word('Take a note ask Sam about the ring'), { word: 'note', rest: 'ask Sam about the ring' });
  assert.deepEqual(word('note'), { word: 'note', rest: '' });
  assert.equal(word('note that the parser is slow and fix it'), 'prompt');
  // A project called "notes" is still a project.
  const ctx: VoiceContext = { sessions: [], projects: [{ path: '/p/notes', name: 'notes', lastActivityAt: 1, sessionCount: 1 }], focusId: null };
  assert.equal(parseUtterance('notes, fix the header', ctx).kind, 'prompt');
});

test('"approve push" carries the risky verb; plain "approve" stays as it was', () => {
  assert.deepEqual(word('Approve push.'), { word: 'approve', rest: 'push' });
  assert.deepEqual(word('approve the deploy'), { word: 'approve', rest: 'deploy' });
  assert.deepEqual(word('Allow delete'), { word: 'approve', rest: 'delete' });
  assert.deepEqual(word('approve'), { word: 'approve', rest: '' });
  assert.equal(word('approve the pull request on GitHub'), 'prompt');
});

test('risky commands: force pushes, rm -rf, deploys and publishes, dropped data, hard resets', () => {
  const r = (cmd: string) => commandRisk(cmd)?.verb ?? null;
  assert.equal(r('git push --force origin main'), 'push');
  assert.equal(r('git push -f'), 'push');
  assert.equal(r('git push origin +main'), 'push');
  assert.equal(r('git push --force-with-lease'), 'push');
  assert.equal(r('git push origin --delete old-branch'), 'push');
  assert.equal(r('git push origin main'), null);
  assert.equal(r('git push -u origin feature'), null);
  assert.equal(r('rm -rf build/'), 'delete');
  assert.equal(r('sudo rm -fr /tmp/x'), 'delete');
  assert.equal(r('rm -r -f node_modules'), 'delete');
  assert.equal(r('rm build/out.txt'), null);
  assert.equal(r('cd app && rm -rf dist && npm run build'), 'delete');
  assert.equal(r('find . -name "*.orig" -delete'), 'delete');
  assert.equal(r('git clean -fdx'), 'delete');
  assert.equal(r('git branch -D feature'), 'delete');
  assert.equal(r('git reset --hard HEAD~3'), 'reset');
  assert.equal(r('git checkout -- .'), 'reset');
  assert.equal(r('git restore --staged file.txt'), null);
  assert.equal(r('npm publish'), 'publish');
  assert.equal(r('npm run deploy'), 'deploy');
  assert.equal(r('npm run dev'), null);
  assert.equal(r('vercel --prod'), 'deploy');
  assert.equal(r('vercel'), null);
  assert.equal(r('fly deploy'), 'deploy');
  assert.equal(r('firebase deploy --only hosting'), 'deploy');
  assert.equal(r('terraform apply -auto-approve'), 'deploy');
  assert.equal(r('kubectl apply -f k8s/'), 'deploy');
  assert.equal(r('kubectl get pods'), null);
  assert.equal(r('./scripts/deploy.sh production'), 'deploy');
  assert.equal(r('docker push me/app:1.2'), 'publish');
  assert.equal(r('psql -c "DROP TABLE users;"'), 'drop');
  assert.equal(r('sqlite3 app.db "DELETE FROM sessions;"'), 'drop');
  assert.equal(r('sqlite3 app.db "DELETE FROM sessions WHERE id = 3;"'), null);
  assert.equal(r('redis-cli FLUSHALL'), 'drop');
  assert.equal(r('npx prisma migrate reset --force'), 'drop');
  assert.equal(r('npx vercel --prod'), 'deploy');
  assert.equal(r('prisma migrate reset'), 'drop');
  assert.equal(r('./gradlew build'), null);
  assert.equal(r('ls -la'), null);
  const ap = (toolName: string, input: Record<string, unknown>): Approval => ({ id: 'a', sessionId: 's', createdAt: 0, expiresAt: 0, kind: 'tool', toolName, summary: '', input, reason: null, canSession: false, questions: null, plan: null });
  assert.equal(approvalRisk(ap('Bash', { command: 'git push --force' }))?.what, 'force push');
  assert.equal(approvalRisk(ap('mcp__vercel__deploy_project', {}))?.verb, 'deploy');
  assert.equal(approvalRisk(ap('Edit', { file_path: '/p/x.ts' })), null);
  assert.equal(approvalRisk({ ...ap('Bash', { command: 'rm -rf x' }), kind: 'plan' }), null);
});

test('only a risk\'s own verb (or its usual words) approves it', () => {
  const push = commandRisk('git push --force')!, del = commandRisk('rm -rf build')!;
  assert.ok(verbMatches(push, 'push'));
  assert.ok(verbMatches(push, 'force push'));
  assert.ok(!verbMatches(push, 'delete'));
  assert.ok(verbMatches(del, 'remove'));
  assert.ok(!verbMatches(del, 'push'));
});

// ------------------------------------------------------------------ what changed

const tool = (id: string, name: string, path: string, diff: Extract<HnEvent, { kind: 'tool' }>['diff'], status: 'done' | 'error' = 'done'): HnEvent =>
  ({ id, kind: 'tool', ts: 1, toolUseId: id, name, summary: path, input: { file_path: path }, status, output: null, exitCode: null, durationMs: 1, path, diff });
const hunk = (newStart: number, lines: string[]) => ({ oldStart: newStart, oldLines: 3, newStart, newLines: 4, lines });

const TURN: HnEvent[] = [
  { id: 'old', kind: 'user', ts: 0, text: 'earlier' },
  tool('e0', 'Edit', '/p/app/Old.kt', { path: '/p/app/Old.kt', added: 50, removed: 0, hunks: [] }),
  { id: 'u', kind: 'user', ts: 1, text: 'fade the comets' },
  tool('e1', 'Edit', '/p/app/core/Solar.kt', { path: '/p/app/core/Solar.kt', added: 2, removed: 1, hunks: [hunk(40, ['     val age = now - born', '-    val alpha = 1f', '+    val alpha = fade(age)', '+    // An hour, then gone.'])] }),
  tool('e2', 'Edit', '/p/app/core/Solar.kt', { path: '/p/app/core/Solar.kt', added: 1, removed: 0, hunks: [hunk(90, ['+fun fade(age: Long) = 1f - age / HOUR'])] }),
  tool('e3', 'Write', '/p/app/test/SolarTest.kt', { path: '/p/app/test/SolarTest.kt', added: 3, removed: 0, hunks: [{ oldStart: 0, oldLines: 0, newStart: 1, newLines: 3, lines: ['+class SolarTest {', '+    @Test fun fades() = assertEquals(0f, fade(HOUR))', '+}'] }] }),
  tool('e4', 'Edit', '/p/app/Broken.kt', null, 'error'),
  { id: 'a', kind: 'assistant', ts: 2, text: 'Comets fade over an hour now. The test covers it.', streaming: false },
];

test('what changed: the last turn\'s files, each once, with counts and hunks in order', () => {
  const ch = turnChanges(TURN);
  assert.deepEqual(ch.map(f => [f.name, f.added, f.removed, f.hunks.length, f.created]), [['Solar.kt', 3, 1, 2, false], ['SolarTest.kt', 3, 0, 1, true]]);
  assert.equal(changesSpoken('hypernova', ch, 'Comets fade over an hour now. The test covers it.'),
    'hypernova changed Solar.kt and SolarTest.kt: 6 lines added, 1 removed. Comets fade over an hour now.');
  assert.equal(changesSpoken('hypernova', []), "hypernova's last turn didn't change any files.");
  const many = ['a', 'b', 'c', 'd'].map((n, i) => ({ path: `/p/${n}.ts`, name: `${n}.ts`, added: i * 10, removed: 1, hunks: [], created: false }));
  assert.equal(changesSpoken('0stems', many), '0stems changed 4 files, 60 lines added, 4 removed, the most in d.ts.');
  // An older Mac sends no diff: the file still counts, without numbers.
  const bare = turnChanges([{ id: 'u', kind: 'user', ts: 1, text: 'x' }, tool('e', 'Edit', '/p/A.kt', null)]);
  assert.equal(changesSpoken('it', bare), 'it changed A.kt.');
});

test('show diff: one text through every file, + and - in a column of their own, indentation halved, long lines wrapped under the code', () => {
  const ch = turnChanges(TURN);
  const t = diffText(ch, CARD_TEXT_W);
  assert.deepEqual(t.lines.slice(0, 5), ['… line 40', '     val age = now - born', '-   val alpha = 1f', '+   val alpha = fade(age)', '+   // An hour, then gone.']);
  // The next file begins on a line of its own, with its name and counts; each line knows its file.
  const second = t.starts[1];
  assert.deepEqual(t.starts, [0, second]);
  assert.equal(t.lines[second], '── SolarTest.kt   +3 -0');
  assert.equal(t.lines[second + 1], 'new file');
  assert.deepEqual([t.file[0], t.file[second - 1], t.file[second], t.file.at(-1)], [0, 0, 1, 1]);
  assert.equal(t.file.length, t.lines.length);
  // + and - are as wide as the two spaces of an unchanged line, so the code starts in one column.
  assert.equal(nativeWidth('+'), nativeWidth('  '));
  assert.equal(nativeWidth('-'), nativeWidth('  '));
  const long = codeLines(`+${'    '}const message = "${'word '.repeat(40)}"`, CARD_TEXT_W);
  assert.ok(long.length > 1);
  assert.ok(long.every(l => nativeWidth(l) <= CARD_TEXT_W), 'every line fits');
  assert.ok(long[1].startsWith('     '), 'a wrapped line sits under the code');
  // A name too long for a line breaks after a bracket or a dot, not inside the name.
  const call = codeLines('+        val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->', CARD_TEXT_W);
  assert.ok(call.some(l => l.trim().startsWith('OpenDocumentTree())')), call.join(' | '));
  // A diff of hundreds of lines stops at 80 lines a file (about a dozen lensfuls), and says the rest is on the phone.
  const big = [{ path: '/p/big.ts', name: 'big.ts', added: 300, removed: 0, created: false, hunks: [hunk(1, Array.from({ length: 300 }, (_, i) => `+line ${i}`))] }];
  const bt = diffText(big, CARD_TEXT_W);
  assert.equal(bt.lines.length, 80);
  assert.equal(bt.lines.at(-1), '… more on the phone');
});

// ------------------------------------------------------------------ debrief

const NOW = Date.UTC(2026, 9, 16, 12, 0);
const session = (id: string, projectName: string, state: Session['state'], extra: Partial<Session> = {}): Session => ({
  id, title: projectName, cwd: `/p/${projectName}`, projectName, origin: 'terminal', state, model: null, permissionMode: 'default',
  createdAt: NOW - 3600_000, lastActivityAt: NOW - 60_000, lastText: null, activity: null, pendingApprovalIds: [], stoppedForAway: false, ...extra,
});

test('debrief: what finished since, then what needs you, then what runs; one sentence each', () => {
  const snap: Snapshot = {
    mac: { id: 'm', name: 'Mac', daemonVersion: '0' }, usage: { fiveHour: null, sevenDay: null, source: 'x', observedAt: null },
    sessions: [
      session('d', 'hyperscribe', 'idle', { lastText: 'Fixed the export so it remembers the last folder. All tests pass.', lastActivityAt: NOW - 10 * 60_000 }),
      session('old', 'prudnikov-site', 'idle', { lastText: 'Done long ago.', lastActivityAt: NOW - 3 * 3600_000 }),
      session('n', '0stems', 'needs_you', { pendingApprovalIds: ['ap'] }),
      session('w', 'hypernova', 'working', { activity: 'Bash: ./gradlew build', agentsRunning: 2 }),
      session('e', 'notes', 'error', { lastActivityAt: NOW - 5 * 60_000 }),
    ],
    approvals: [{ id: 'ap', sessionId: 'n', createdAt: NOW - 60_000, expiresAt: NOW, kind: 'tool', toolName: 'Bash', summary: '', input: { command: 'git push origin main' }, reason: null, canSession: false, questions: null, plan: null }],
    projects: [],
  };
  const d = debrief({ snap, facts: new Map(), since: NOW - 3600_000, now: NOW });
  assert.deepEqual(d.items.map(i => [i.sessionId, i.kind]), [['e', 'stopped'], ['d', 'done'], ['n', 'needs'], ['w', 'working']]);
  assert.equal(d.counts, '1 done, 1 stopped, 1 needs you, 1 at work');
  // Each the project and what it did, apart, in the glance's words.
  assert.deepEqual(d.items[1].line, ['hyperscribe', 'finished: Fixed the export so it remembers the last folder.']);
  assert.deepEqual(d.items[2].line, ['0stems', 'wants to push to main']);
  assert.deepEqual(d.items[3].line, ['hypernova', 'Bash: ./gradlew build', '2 agents running']);
  assert.match(d.spoken, /^Since \d\d?:\d\d( [AP]M)?\. notes stopped\. hyperscribe finished: Fixed the export so it remembers the last folder\. zero stems wants to push to main\. hypernova is running \.\/gradlew build, with 2 agents running\.$/);
  // Nothing since: says so, and still what runs.
  const later = debrief({ snap: { ...snap, sessions: snap.sessions.filter(s => s.id === 'w'), approvals: [] }, facts: new Map(), since: NOW, now: NOW + 1000 });
  assert.match(later.spoken, /^Nothing new since .*\. hypernova is running/);
  const none = debrief({ snap: { ...snap, sessions: [], approvals: [] }, facts: new Map(), since: NOW, now: NOW });
  assert.match(none.spoken, /^Nothing new since .*\. All quiet\.$/);
  assert.equal(none.counts, 'nothing new');
});

// ------------------------------------------------------------------ the guide

test('the guide: each step is checked by its own gesture; a wrong one is named; double tap leaves once learned, and skips the spoken step', () => {
  let s: GuideState = { step: -1, checked: false, note: '' };
  assert.equal(guideGesture(s, 'double').kind, 'leave');                     // the offer: not now
  let o = guideGesture(s, 'tap');
  assert.equal(o.kind, 'next');
  s = (o as { state: GuideState }).state;
  assert.equal(s.step, 0);
  o = guideGesture(s, 'swipe');
  assert.equal(o.kind, 'again');
  assert.equal((o as { state: GuideState }).state.note, 'That was a swipe. Just one tap.');
  // Before double tap is learned, a double tap is named, not a way out.
  assert.equal(guideGesture(s, 'double').kind, 'again');
  o = guideGesture(s, 'tap');
  assert.equal(o.kind, 'checked');
  s = (o as { state: GuideState }).state;
  assert.equal(s.note, GUIDE_STEPS[0].done);
  assert.equal(guideDots(s), '●○○○○');
  // The next step's own gesture right away counts for it (glasses may send a tap before a double tap).
  o = guideGesture(s, 'double');
  assert.equal(o.kind, 'checked');
  s = { step: 2, checked: false, note: '' };
  assert.equal(guideGesture(s, 'double').kind, 'leave');
  assert.equal(guideGesture({ step: 3, checked: false, note: '' }, 'hold').kind, 'checked');
  const voice: GuideState = { step: 4, checked: false, note: '' };
  // The spoken step is practice: any words heard tick it (and are shown; nothing is sent).
  assert.equal(guideGesture(voice, 'speak').kind, 'checked');
  assert.equal(guideGesture({ step: 0, checked: false, note: '' }, 'speak').kind, 'ignore');
  const skip = guideGesture(voice, 'double');
  assert.equal(skip.kind, 'next');
  assert.equal((skip as { state: GuideState }).state.step, GUIDE_STEPS.length);
  assert.equal(guideGesture({ step: GUIDE_STEPS.length, checked: false, note: '' }, 'tap').kind, 'leave');
});

// ------------------------------------------------------------------ moving

test('moving: every screen is two short lines at most (a pop-up takes the first); Telescope stays whole', () => {
  const orrery = { sun: null, planets: [] };
  const views: LensView[] = [
    { kind: 'glance', left: 'hypernova/', clock: '14:05', right: ['42%', 'resets 2h'], rows: [
      { project: '0stems', detail: 'wants to run git push origin main', detailShort: 'wants to push to main', right: '1m', state: 'needs', focused: true },
      { project: 'hypernova', detail: 'Bash: ./gradlew build', right: '2m', state: 'working', focused: false },
    ], empty: 'All quiet', hint: 'tap answer', orrery },
    { kind: 'card', head: ['DONE', 'hyperscribe'], right: '', lead: '', body: ['Fixed the export.', 'More.'], facts: ['3 files changed'], hint: 'tap', orrery },
    { kind: 'live', head: ['WORKING', 'hypernova'], right: '2m', now: ['› Bash: ./gradlew build', '• survey'], more: 0, output: ['a', 'b'], hint: 'x', orrery },
    { kind: 'choice', head: ['QUESTION', 'hypernova'], right: '', prompt: 'Where?', options: [{ label: 'A', mark: '' }], cursor: 0, hint: '', orrery },
    { kind: 'talk', head: 'LISTENING', right: '', text: 'fix the header and', live: true, hint: '', orrery },
  ];
  const briefs = views.map(v => briefOf(v));
  for (const b of briefs) {
    assert.equal(b.kind, 'brief');
    const page = buildPage(b);
    assert.ok(page.boxes.length <= 2, 'two boxes');
    assert.equal(page.images.length, 0, 'nothing that moves');
  }
  // Each line in parts (the page puts a gap between them), never "·"-joined; the row's short phrase.
  assert.deepEqual((briefs[0] as { lines: string[] }).lines, [['0stems', 'wants to push to main'], ['1 of 2', '14:05', '42%', 'resets 2h']]);
  assert.deepEqual((briefs[1] as { lines: string[] }).lines, [['DONE', 'hyperscribe'], 'Fixed the export.']);
  assert.deepEqual((briefs[2] as { lines: string[] }).lines, [['WORKING', 'hypernova'], '› Bash: ./gradlew build']);
  assert.deepEqual((briefOf(views[3], '', 'say “one”') as { lines: string[] }).lines, [['QUESTION', 'hypernova'], 'say “one”']);
  assert.deepEqual((briefOf(views[1], 'Allowed') as { lines: string[] }).lines, [['DONE', 'hyperscribe'], 'Allowed']);
  assert.equal(buildPage(briefs[0]).boxes[1].content, '1 of 2   14:05   42%   resets 2h');
  // A pop-up: its one line, as a sentence: "0stems needs you: wants to push to main".
  const banded = briefOf({ ...views[2], band: { head: ['NEEDS YOU', '0stems'], right: '', detail: 'wants to push to main', line: '0stems needs you: wants to push to main' } } as LensView);
  assert.deepEqual((banded as { lines: string[] }).lines, ['0stems needs you: wants to push to main', ['WORKING', 'hypernova']]);
  const scope: LensView = { kind: 'telescope', head: ['TELESCOPE'], right: '', status: '', hint: '', picture: true, loading: false, key: 'k', zoom: null, crisp: false };
  assert.equal(briefOf(scope), scope);
});
