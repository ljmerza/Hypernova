// The drawn look (lens/look.ts 'hypernova'): the switch, the app's type measured and wrapped (English
// and Russian), the page descriptions, the tiles and which of them changed, and the LZ4 estimate.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { advance, faceLines, fitFace, hasFaceGlyph, measure, wrapFace, type Face } from '../src/core/type.ts';
import { lz4Compress, lz4Decompress } from '../src/core/lz4.ts';
import { drawsCards, drawsReplies, lookOf, specimenOf } from '../src/lens/look.ts';
import { spendMeter } from '../src/core/meter.ts';
import { changedTiles, DARK, packGray4, PLANS, regionHash, tileCost, type Rect } from '../src/lens/tiles.ts';
import { CARD, CMD_W, drawnCardRoom, drawnLensPage, drawView, FACE, hintParts, READ_W, TEXT_W, X0, type DrawnPage, type Op } from '../src/lens/drawn.ts';
import { approvalTypeset, DRAWN_TYPE, NATIVE_TYPE, replyTypeset } from '../src/lens/typeset.ts';
import { drawnLevel, SHOWN_OF } from '../src/lens/gray.ts';
import { CARD_TEXT_W, cardRoom, factRows, type LensView } from '../src/lens/pages.ts';
import { textLines, wrapAll } from '../src/core/text.ts';
import { fitRows, MORE_W, windowLines } from '../src/core/scroll.ts';
import { LensController, type Services } from '../src/lens/controller.ts';
import { DemoSpeech } from '../src/data/speech.ts';
import { DEFAULT_SETTINGS, type Settings } from '../src/data/store.ts';
import type { HubEvent, HubLike } from '../src/data/hub.ts';
import type { VoiceOut } from '../src/data/voice.ts';
import type { Approval, HnEvent, Session, Snapshot, Usage } from '../src/core/types.ts';

const EN = 'Fixed the export so it remembers the last folder; all 48 tests pass now. ExportSheet.kt keeps the folder in the app’s settings and opens the picker there next time, and a test covers the round trip.';
const RU = 'Исправил экспорт: теперь он запоминает последнюю папку, и все 48 тестов проходят. ExportSheet.kt хранит папку в настройках приложения и в следующий раз открывает выбор файла сразу там.';

// ------------------------------------------------------------------ the switch

test('the look: built-in (the firmware font) unless the setting says Hypernova; the address wins; replies drawn only with ?look=all', () => {
  // The owner, 2026-10-18, on real glasses: "let's use firmware text on glasses, because responsiveness really sucks with images".
  assert.equal(lookOf({}, ''), 'native');
  assert.equal(lookOf(null, ''), 'native');
  assert.equal(lookOf(DEFAULT_SETTINGS, ''), 'native');
  assert.equal(DEFAULT_SETTINGS.look, 'native');
  assert.equal(lookOf({ look: 'hypernova' }, ''), 'hypernova');
  assert.equal(lookOf({ look: 'native' }, ''), 'native');
  assert.equal(lookOf({ look: 'native' }, '?look=hypernova'), 'hypernova');
  assert.equal(lookOf({ look: 'hypernova' }, '?demo=1&look=native'), 'native');
  assert.equal(lookOf({ look: 'hypernova' }, '?look=all'), 'all');
  assert.equal(lookOf({ look: 'fancy' }, '?look=sparkly'), 'native');
  // The hybrid draws the glance and approvals, never replies; 'all' draws replies too.
  assert.ok(drawsCards('hypernova') && drawsCards('all') && !drawsCards('native') && !drawsCards(undefined));
  assert.ok(drawsReplies('all') && !drawsReplies('hypernova') && !drawsReplies('native'));
  assert.equal(replyTypeset('hypernova'), NATIVE_TYPE);
  assert.equal(replyTypeset('all'), DRAWN_TYPE);
  assert.equal(approvalTypeset('hypernova'), DRAWN_TYPE);
  assert.equal(approvalTypeset('native'), NATIVE_TYPE);
  assert.equal(specimenOf('?specimen=2'), 2);
  assert.equal(specimenOf('?demo=1'), null);
});

// ------------------------------------------------------------------ the app's type

test('the type: advances from the font files, Cyrillic included; mono is monospaced; tracking adds up', () => {
  for (const ch of 'aяЖё€№—…') assert.ok(hasFaceGlyph(ch, FACE.body), `Manrope has ${ch}`);
  for (const ch of 'aяЖё') assert.ok(hasFaceGlyph(ch, FACE.mono), `JetBrains Mono has ${ch}`);
  // JetBrains Mono: 0.6 em a character.
  assert.equal(measure('abcЖ', { family: 'mono', size: 10, weight: 500 }), 24);
  // Bolder Manrope is wider; tracking adds its px after each character.
  assert.ok(measure('remember', { family: 'sans', size: 15, weight: 700 }) > measure('remember', { family: 'sans', size: 15, weight: 500 }));
  const f: Face = { family: 'sans', size: 11, weight: 700, track: 1.5 };
  assert.equal(measure('AB', f), advance('A', f) + advance('B', f));
  assert.equal(measure('AB', f), measure('AB', { ...f, track: 0 }) + 3);
  // A glyph neither font has counts as wide (it would be drawn by a fallback).
  assert.ok(advance('㐀', FACE.body) >= 0.8 * 15);
});

test('wrapping in the app\'s type: every line fits, nothing is lost, English and Russian; long words break; fit adds an ellipsis', () => {
  for (const text of [EN, RU]) {
    for (const [width, face] of [[READ_W, FACE.body], [CMD_W, FACE.mono], [200, FACE.small]] as const) {
      const lines = wrapFace(text, width, face);
      assert.ok(lines.length > 1);
      for (const l of lines) assert.ok(measure(l, face) <= width, `"${l}" ${measure(l, face)} > ${width}`);
      assert.equal(lines.join(' '), text.replace(/\s+/g, ' '));
    }
  }
  // Russian takes more room than English of the same length in the firmware font and in Manrope alike.
  assert.ok(measure('экспорт', FACE.body) > measure('exports', FACE.body));
  const path = '/Users/you/Programming/hyperscribe/app/src/main/java/com/prudnikweb/hyperscribe/ExportSheet.kt';
  const broken = wrapFace(path, 160, FACE.mono);
  assert.equal(broken.join(''), path);
  for (const l of broken) assert.ok(measure(l, FACE.mono) <= 160);
  assert.deepEqual(faceLines('one\ntwo three', 400, FACE.body), ['one', 'two three']);
  const cut = fitFace(RU, 120, FACE.body);
  assert.ok(cut.endsWith('…') && measure(cut, FACE.body) <= 120);
  assert.equal(fitFace('short', 120, FACE.body), 'short');
});

test('the drawn reply shows more than the native one: more words a line and more lines a card', () => {
  const native = textLines(EN + ' ' + EN, CARD_TEXT_W - MORE_W), drawn = DRAWN_TYPE.readLines(EN + ' ' + EN);
  assert.ok(drawn.length < native.length, `${drawn.length} drawn lines vs ${native.length} native`);
  assert.ok(drawnCardRoom(2) > cardRoom(false, 2), `${drawnCardRoom(2)} drawn rows vs ${cardRoom(false, 2)} native`);
});

// ------------------------------------------------------------------ the typeset (what the controller lays out with)

test('the native typeset is the firmware font\'s layout, unchanged', () => {
  const facts = ['3 files changed', '48 tests passed', 'took 4m 12s'];
  const lines = NATIVE_TYPE.readLines(EN + '\n' + RU);
  assert.deepEqual(lines, textLines(EN + '\n' + RU, CARD_TEXT_W - MORE_W));
  const rows = NATIVE_TYPE.replyRows(lines, facts);
  assert.equal(rows, fitRows(lines, Math.min(cardRoom(false, factRows(facts, 2)), lines.length), CARD_TEXT_W));
  assert.deepEqual(NATIVE_TYPE.window(lines, 1, rows), windowLines(lines, 1, rows, CARD_TEXT_W));
  const cmd = 'rm -rf build/ && ./gradlew clean && ./gradlew assembleRelease --stacktrace --info --no-daemon && ls -la app/build/outputs/apk/release';
  assert.deepEqual(NATIVE_TYPE.command(cmd, []), wrapAll(cmd, CARD_TEXT_W).slice(0, cardRoom(true, false)));
  // A reason and a warning take a line each, so the command keeps one less.
  assert.equal(NATIVE_TYPE.command(`${cmd} ${cmd} ${cmd}`, ['Clean build first'], 'careful: deleting files can’t be undone').length, cardRoom(true, 2));
  assert.equal(NATIVE_TYPE.command(`${cmd} ${cmd} ${cmd}`, []).length, cardRoom(true, false));
});

test('the drawn typeset: windows keep their size and carry no marks; a long command is cut with an ellipsis', () => {
  const lines = DRAWN_TYPE.readLines([EN, RU, EN, RU, EN].join('\n'));
  const rows = DRAWN_TYPE.replyRows(lines, ['3 files changed', '48 tests passed']);
  assert.equal(rows, drawnCardRoom(1));
  for (const top of [0, 3, lines.length - rows]) {
    const w = DRAWN_TYPE.window(lines, top, rows);
    assert.equal(w.length, rows);
    assert.ok(w.every(l => !l.includes('↓')));
  }
  assert.deepEqual(DRAWN_TYPE.window(['a', 'b'], 0, 4), ['a', 'b', '', '']);
  const cmd = Array.from({ length: 120 }, (_, i) => `step${i}`).join(' && ');
  const shown = DRAWN_TYPE.command(cmd, ['Clean build first']);
  assert.ok(shown.length < wrapFace(cmd, CMD_W, FACE.mono).length);
  assert.ok(shown[shown.length - 1].endsWith('…'));
});

// ------------------------------------------------------------------ the pages

const orrery = { sun: { pct: 42, limit: false }, planets: [{ state: 'needs' as const, focused: true }, { state: 'working' as const, focused: false }] };
const glanceView = (extra: Partial<Extract<LensView, { kind: 'glance' }>> = {}): LensView => ({
  kind: 'glance', left: 'hypernova/', clock: '14:05', right: ['42%', 'resets 2h 11m'], empty: 'All quiet on Mac.', hint: ['tap answer', 'hold talk to 0stems'], orrery,
  rows: [
    { project: '0stems', detail: 'wants to push to main', right: '1m', state: 'needs', focused: true },
    { project: 'hypernova', detail: 'Bash: ./gradlew build', right: '2m', state: 'working', focused: false, tag: ['2 agents running'] },
    { project: 'hyperscribe', detail: 'экспорт расшифровок, который запоминает последнюю папку и всё такое', right: '3m', state: 'done', focused: false },
    { project: 'prudnikov-site', detail: 'blog layout', right: '22m', state: 'idle', focused: false },
  ],
  newRow: { label: 'New session', focused: false }, look: 'hypernova', ...extra,
} as LensView);
const replyView = (body: string[], scroll = { top: 0, rows: body.length, lines: body.length }): LensView => ({
  kind: 'card', head: ['DONE', 'hyperscribe'], right: '', lead: '', body, facts: ['3 files changed', '48 tests passed', 'took 4m 12s'], factsRows: 2,
  hint: ['swipe read', 'tap read aloud'], orrery: { sun: null, planets: [], seam: { state: 'done' } }, role: 'reply', scroll, look: 'all',
});
const approvalView = (command: string, facts: string[] = [], warn?: string): LensView => ({
  kind: 'card', head: ['NEEDS YOU', '0stems'], right: 'auto-deny in 9m', lead: 'wants to run', body: DRAWN_TYPE.command(command, facts, warn), facts, ...(warn ? { warn } : {}),
  hint: ['tap allow', 'double tap deny', 'swipe back'], orrery: { sun: null, planets: [], seam: { state: 'needs' } }, role: 'approval', look: 'hypernova',
});

/** Where an op draws (text: from its baseline's ascent to its descent). */
function box(op: Op): Rect {
  switch (op.op) {
    case 'text': {
      const w = measure(op.s, op.face);
      return { x: op.align === 'right' ? op.x - w : op.x, y: op.y - op.face.size, w, h: Math.round(op.face.size * 1.3) };
    }
    case 'icon': return { x: op.x, y: op.y, w: op.size, h: op.size };
    default: return { x: op.x, y: op.y, w: op.w, h: op.h };
  }
}
const inside = (a: Rect, b: Rect) => a.x >= b.x - 0.5 && a.y >= b.y - 0.5 && a.x + a.w <= b.x + b.w + 0.5 && a.y + a.h <= b.y + b.h + 0.5;
const overlaps = (a: Rect, b: Rect) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

/** A drawn page the glasses can take: within the lens, nothing in a part no tile covers, text clear of the planet column. */
function checkDrawn(d: DrawnPage, label: string): void {
  const page = drawnLensPage(d);
  assert.ok(page.images.length <= 4, `${label}: ${page.images.length} images`);
  assert.equal(page.boxes.length, 0, `${label}: only the gesture box is text`);
  for (const i of page.images) assert.ok(i.w >= 20 && i.h >= 20 && i.w <= 288 && i.h <= 144 && i.x >= 0 && i.y >= 0 && i.x + i.w <= 576 && i.y + i.h <= 288, `${label}: image ${i.name}`);
  for (const op of d.ops) {
    const r = box(op);
    assert.ok(inside(r, { x: 0, y: 0, w: 576, h: 288 }), `${label}: ${JSON.stringify(op)} off the lens`);
    for (const dark of DARK[d.plan]) assert.ok(!overlaps(r, dark) || (op.op === 'text' && !op.s.trim()), `${label}: ${JSON.stringify(op)} where no tile goes`);
    if (op.op === 'text') {
      assert.ok(r.x >= X0 - 0.5, `${label}: "${op.s}" over the planet column`);
      assert.ok(r.x + r.w <= 572, `${label}: "${op.s}" past the right edge (${r.x + r.w})`);
    }
  }
}

test('drawn pages: the glance on the column plan (its planets move), cards and a band on the full plan; other screens stay native', () => {
  const g = drawView(glanceView())!;
  assert.equal(g.plan, 'column');
  checkDrawn(g, 'glance');
  // The line in focus is held in a ring; the tag is a chip; the hint's gestures are glyphs.
  assert.ok(g.ops.some(o => o.op === 'frame' && o.w > 400), 'a focus ring');
  assert.ok(g.ops.some(o => o.op === 'text' && o.s === '2 AGENTS RUNNING'), 'the tag chip');
  assert.deepEqual(g.ops.filter(o => o.op === 'icon').map(o => (o as { icon: string }).icon), ['plus', 'tap', 'hold']);
  const banded = drawView({ ...glanceView(), band: { head: ['DONE', 'prudnikov-site'], right: '+1', detail: 'The blog layout uses the new grid now.' } })!;
  assert.equal(banded.plan, 'full');
  checkDrawn(banded, 'glance with a band');
  const lines = DRAWN_TYPE.readLines([EN, RU, EN, RU, EN].join('\n'));
  const rows = drawnCardRoom(1);
  assert.ok(lines.length > rows);
  const r = drawView(replyView(DRAWN_TYPE.window(lines, 0, rows), { top: 0, rows, lines: lines.length }))!;
  assert.equal(r.plan, 'full');
  checkDrawn(r, 'reply');
  // More than shows: the scroll mark (a track and a thumb at the top).
  const thumbs = r.ops.filter(o => o.op === 'rect' && o.w === 3);
  assert.equal(thumbs.length, 1);
  const end = drawView(replyView(DRAWN_TYPE.window(lines, lines.length - rows, rows), { top: lines.length - rows, rows, lines: lines.length }))!;
  const [t0, t1] = [thumbs[0], end.ops.find(o => o.op === 'rect' && o.w === 3)!] as Array<Extract<Op, { op: 'rect' }>>;
  assert.ok(t1.y > t0.y, 'the thumb moves down as it scrolls');
  // Short: no mark.
  assert.ok(!drawView(replyView(['Done.']))!.ops.some(o => o.op === 'rect' && o.w === 3));
  const a = drawView(approvalView('git commit -am "Экспорт запоминает последнюю папку" && git push origin main', ['Ship the fix to main'], 'careful: pushing to main can’t be undone'))!;
  assert.equal(a.plan, 'full');
  checkDrawn(a, 'approval');
  assert.ok(a.ops.some(o => o.op === 'icon' && o.icon === 'warn'), 'what can’t be undone is marked');
  assert.ok(a.ops.some(o => o.op === 'frame' && o.fill !== undefined && o.fill > 0.5), 'the filled needs-you block');
  for (const v of [{ kind: 'blank' }, { kind: 'brief', lines: ['a'] }, { kind: 'message', title: 't', sub: '' }] as LensView[]) assert.equal(drawView({ ...v, look: 'hypernova' } as LensView), null);
  // A card the drawn look doesn't know (a plan, a note, the diff) stays native; in the hybrid a reply does too; built-in draws nothing.
  assert.equal(drawView({ ...replyView(['x']), role: undefined } as LensView), null);
  assert.equal(drawView({ ...replyView(['x']), look: 'hypernova' } as LensView), null);
  assert.equal(drawView({ ...glanceView(), look: undefined } as LensView), null);
  assert.ok(drawView({ ...approvalView('ls'), look: 'all' } as LensView));
});

test('the hint as glyphs: each gesture its glyph, "to" dropped, words without a gesture kept', () => {
  assert.deepEqual(hintParts(['tap allow', 'double tap deny', 'swipe back']), [{ icon: 'tap', words: 'allow' }, { icon: 'double', words: 'deny' }, { icon: 'swipe', words: 'back' }]);
  assert.deepEqual(hintParts(['swipe to read', 'hold to talk', 'say “approve push”']), [{ icon: 'swipe', words: 'read' }, { icon: 'hold', words: 'talk' }, { icon: 'say', words: '“approve push”' }]);
  assert.deepEqual(hintParts('Allowed'), [{ icon: null, words: 'Allowed' }]);
});

test('a drawn reply arriving: the lines already shown stay where they were, so only the tiles new words land in change', () => {
  const words = (EN + ' ' + RU).split(' ');
  let prev: string[] | null = null;
  for (let n = 3; n <= words.length; n += 3) {
    const lines = DRAWN_TYPE.readLines(words.slice(0, n).join(' '));
    // Greedy wrapping: a longer text's lines start with the shorter's (all but its last).
    if (prev) assert.deepEqual(lines.slice(0, prev.length - 1), prev.slice(0, -1));
    prev = lines;
  }
});

test('drawn text meets the lens: lit levels kept; below the lowest, a well-covered stroke of dim text stays, a faint fringe goes', () => {
  const floor = SHOWN_OF[1];                     // the lowest level's perceived brightness, ~96
  assert.equal(drawnLevel(255, 255), 15);
  assert.equal(drawnLevel(floor + 2, 255), 1);
  // Full-bright text: anything under the lowest level is fringe.
  assert.equal(drawnLevel(floor - 10, 255), 0);
  // Dim text (60%): a hyphen half over each of two rows is 0.5 x 153 = 77, under the floor; it stays.
  assert.equal(drawnLevel(77, 153), 1);
  // A faint edge of it (a fifth covered) goes.
  assert.equal(drawnLevel(30, 153), 0);
  // Nothing drawn there: dark stays dark.
  assert.equal(drawnLevel(40, 0), 0);
});

// ------------------------------------------------------------------ spending as a meter

const HOUR = 3600_000, MIN = 60_000;
const usage = (pct: number, resetIn: number, rate?: { fiveHour: number | null; fiveHourEtaMs: number | null }): Usage => ({
  fiveHour: { pct, resetsAt: NOW + resetIn }, sevenDay: { pct: 18, resetsAt: NOW + 60 * HOUR }, source: 'official', observedAt: NOW,
  ...(rate ? { rate: { ...rate, sevenDay: null, sevenDayEtaMs: null, working: true } } : {}),
});

test('the meter: used, the pace to where it leads by the reset, the limit before the reset; limits and accounts as the corner says', () => {
  const ok = { state: 'ok', limit: null, since: null } as const;
  // Idle: no pace, the reset to come.
  assert.deepEqual(spendMeter(usage(42, 2 * HOUR), ok, NOW), { label: '5H', pct: 42, pace: null, limitInMs: null, resetInMs: 2 * HOUR, spent: false });
  // Working at 0.12 %/min with 2 h left: it leads to 42 + 14.4 = 56.4%, short of the limit.
  const pace = spendMeter(usage(42, 2 * HOUR, { fiveHour: 0.12, fiveHourEtaMs: null }), ok, NOW)!;
  assert.ok(Math.abs(pace.pace! - 56.4) < 1e-9 && pace.limitInMs === null && pace.resetInMs === 2 * HOUR);
  // Burning: the Mac's time to the limit, before the reset.
  const burn = spendMeter(usage(62, 2 * HOUR + MIN, { fiveHour: 0.8, fiveHourEtaMs: 47 * MIN }), ok, NOW)!;
  assert.deepEqual([burn.pace, burn.limitInMs], [100, 47 * MIN]);
  // No ETA from the Mac but the projection passes 100%: worked out here.
  const fast = spendMeter(usage(90, HOUR, { fiveHour: 1, fiveHourEtaMs: null }), ok, NOW)!;
  assert.deepEqual([fast.pace, fast.limitInMs], [100, 10 * MIN]);
  // A pace of 0 is no pace.
  assert.equal(spendMeter(usage(42, HOUR, { fiveHour: 0, fiveHourEtaMs: null }), ok, NOW)!.pace, null);
  // At the 5-hour limit: spent, back at its reset.
  const limited = { state: 'limited', since: NOW, limit: { window: 'five_hour', label: 'session limit', resetsAt: NOW + HOUR, autoResumeAt: null, usingCredits: false } } as const;
  assert.deepEqual(spendMeter(usage(100, HOUR), limited, NOW), { label: '5H', pct: 100, pace: null, limitInMs: null, resetInMs: HOUR, spent: true });
  // A model's week while it is the tightest: a plain meter named for it.
  const m = spendMeter({ ...usage(30, HOUR), models: [{ label: 'Fable 5.1', pct: 91, resetsAt: NOW + 50 * HOUR }] }, ok, NOW)!;
  assert.deepEqual([m.label, m.pct, m.pace], ['FABLE', 91, null]);
  // What a bar can't say stays words: signed out, out of credits, stale numbers, nothing known.
  assert.equal(spendMeter(usage(42, HOUR), { state: 'logged_out', limit: null, since: NOW } as never, NOW), null);
  assert.equal(spendMeter({ ...usage(42, HOUR), source: 'stale' }, ok, NOW), null);
  assert.equal(spendMeter(null, ok, NOW), null);
});

test('the glance draws the meter, not a sentence: a bar, the pace as a fainter fill, a notch and one tag when the limit comes first', () => {
  const ok = { state: 'ok', limit: null, since: null } as const;
  const drawn = (u: Usage) => drawView({ ...glanceView(), meter: spendMeter(u, ok, NOW) } as LensView)!;
  const header = (d: DrawnPage) => d.ops.filter(o => (o.op === 'text' || o.op === 'rect' || o.op === 'icon' || o.op === 'frame') && o.y < 26);
  const texts = (d: DrawnPage) => header(d).filter(o => o.op === 'text').map(o => (o as { s: string }).s);
  const idle = drawn(usage(42, 2 * HOUR + 11 * MIN));
  checkDrawn(idle, 'glance with a meter');
  // No "·"-joined spending: the label, the number, the reset after its glyph.
  assert.deepEqual(texts(idle).slice(-3), ['2h 11m', '42%', '5H']);
  assert.ok(!texts(idle).some(t => t.includes('·') || t.includes('resets')));
  assert.ok(header(idle).some(o => o.op === 'icon' && o.icon === 'reset'));
  const fills = (d: DrawnPage) => header(d).filter(o => o.op === 'rect' && o.h === 5) as Array<Extract<Op, { op: 'rect' }>>;
  assert.equal(fills(idle).length, 1, 'used only');
  const pace = drawn(usage(42, 2 * HOUR, { fiveHour: 0.12, fiveHourEtaMs: null }));
  const [ahead, used] = fills(pace);
  assert.ok(ahead.tone < used.tone && ahead.x === used.x + used.w, 'the pace runs on, fainter, from the end of what is used');
  const burn = drawn(usage(62, 2 * HOUR + MIN, { fiveHour: 0.8, fiveHourEtaMs: 47 * MIN }));
  assert.deepEqual(texts(burn).slice(-3), ['47m', '62%', '5H']);
  assert.ok(!header(burn).some(o => o.op === 'icon' && o.icon === 'reset'), 'the limit\'s time, not the reset');
  assert.ok(header(burn).some(o => o.op === 'rect' && o.w === 2 && o.h > 7), 'a notch at the end');
  checkDrawn(burn, 'glance burning');
  // Without a meter (offline, signed out) the corner's words.
  assert.ok(texts(drawView({ ...glanceView(), meter: null, right: ['offline since 14:32'] } as LensView)!).includes('offline since 14:32'));
});

// ------------------------------------------------------------------ tiles

test('the plans: full is the whole lens in four legal tiles; column leaves the planets their image and the bottom right dark', () => {
  const covered = (slots: Rect[], x: number, y: number) => slots.some(s => x >= s.x && x < s.x + s.w && y >= s.y && y < s.y + s.h);
  for (let y = 0; y < 288; y += 4) for (let x = 0; x < 576; x += 4) {
    assert.ok(covered(PLANS.full, x, y), `full misses ${x},${y}`);
    const col = covered(PLANS.column, x, y) || (x < 32 && y < 144) || covered(DARK.column, x, y);
    assert.ok(col, `column misses ${x},${y}`);
  }
  for (const plan of Object.values(PLANS)) for (const s of plan) assert.ok(s.w <= 288 && s.h <= 144);
  assert.equal(drawnLensPage({ plan: 'column', ops: [], orrery: null }).images.length, 4);
});

test('tile diffing: only the tiles whose levels changed, once; a change outside a tile leaves it alone', () => {
  const levels = new Uint8Array(576 * 288);
  const sent = new Map<number, number>();
  assert.equal(changedTiles(PLANS.full, levels, 576, sent).length, 4);
  assert.equal(changedTiles(PLANS.full, levels, 576, sent).length, 0);
  levels[10 * 576 + 300] = 15;                   // in the second tile
  assert.deepEqual(changedTiles(PLANS.full, levels, 576, sent).map(t => t.slot.name), ['look1']);
  levels[200 * 576 + 10] = 7; levels[200 * 576 + 500] = 3;
  assert.deepEqual(changedTiles(PLANS.full, levels, 576, sent).map(t => t.slot.name), ['look2', 'look3']);
  // The same pixels back as they were: changed again (what the glasses have is the last sent).
  levels[10 * 576 + 300] = 0;
  assert.deepEqual(changedTiles(PLANS.full, levels, 576, sent).map(t => t.slot.name), ['look1']);
  const r = { x: 0, y: 0, w: 8, h: 2 };
  const a = regionHash(levels, 576, r);
  levels[2 * 576] = 9;                            // just under the region
  assert.equal(regionHash(levels, 576, r), a);
});

test('a tile\'s weight: 4-bit greys two to a byte; black compresses to almost nothing, text to a fraction', () => {
  const levels = new Uint8Array(576 * 288);
  levels[0] = 15; levels[1] = 1; levels[2] = 8;
  assert.deepEqual([...packGray4(levels, 576, { x: 0, y: 0, w: 4, h: 1 })], [0xf1, 0x80]);
  const black = tileCost(levels, 576, PLANS.full[3]);
  assert.equal(black.gray4, 288 * 144 / 2);
  assert.ok(black.lz4 < 200, `black: ${black.lz4} bytes`);
  // Something like text: a few busy bands.
  let seed = 7;
  for (let y = 10; y < 130; y += 20) for (let k = 0; k < 12; k++) for (let x = 0; x < 288; x++) { seed = (seed * 1103515245 + 12345) >>> 0; levels[(y + k) * 576 + x] = (seed >> 16) % 3 ? 0 : (seed >> 20) & 15; }
  const busy = tileCost(levels, 576, PLANS.full[0]);
  assert.ok(busy.lz4 > black.lz4 && busy.lz4 < busy.gray4);
});

test('LZ4: the encoder writes real LZ4 blocks (they decode back), and repeats shrink', () => {
  let seed = 1;
  const rnd = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed >>> 24; };
  const samples = [new Uint8Array(0), new Uint8Array(5).fill(3), new Uint8Array(20736), Uint8Array.from({ length: 5000 }, rnd), Uint8Array.from({ length: 20736 }, (_, i) => (i % 97 < 60 ? 0 : rnd() & 0x11))];
  for (const s of samples) {
    const c = lz4Compress(s);
    assert.deepEqual(lz4Decompress(c, s.length), s);
  }
  assert.ok(lz4Compress(new Uint8Array(20736)).length < 120);
});

// ------------------------------------------------------------------ the controller, end to end

const NOW = Date.now();
function setup(settings: Omit<Partial<Settings>, 'look'> & { look?: string }) {
  const s = (id: string, projectName: string, state: Session['state'], extra: Partial<Session> = {}): Session => ({
    id, title: `${projectName} work`, cwd: `/p/${projectName}`, projectName, origin: 'terminal', state, model: null, permissionMode: 'default',
    createdAt: NOW - 3600_000, lastActivityAt: NOW - 60_000, lastText: 'Done.', activity: null, pendingApprovalIds: [], stoppedForAway: false, ...extra,
  });
  const ap: Approval = { id: 'ap1', sessionId: 'n', createdAt: NOW - 60_000, expiresAt: NOW + 600_000, kind: 'tool', toolName: 'Bash', summary: 'Bash: x',
    input: { command: 'git commit -am "Экспорт запоминает последнюю папку" && git push origin main' }, reason: 'Ship the fix', canSession: false, questions: null, plan: null };
  const events: HnEvent[] = [
    { id: 'u', kind: 'user', ts: NOW - 300_000, text: 'fix it' },
    { id: 'a', kind: 'assistant', ts: NOW - 1000, text: [EN, RU, EN, RU].join('\n\n'), streaming: false },
    { id: 'r', kind: 'result', ts: NOW, ok: true, durationMs: 300_000, costUsd: null, turns: 3, error: null },
  ];
  const listeners = new Set<(e: HubEvent) => void>();
  const snapshot: Snapshot = {
    mac: { id: 'm', name: 'Mac', daemonVersion: '0' }, usage: { fiveHour: { pct: 42, resetsAt: NOW + 7200_000 }, sevenDay: null, source: 'official', observedAt: NOW },
    sessions: [s('n', '0stems', 'needs_you', { pendingApprovalIds: ['ap1'] }), s('i', 'hyperscribe', 'idle')], approvals: [ap],
    projects: [{ path: '/p/hyperscribe', name: 'hyperscribe', lastActivityAt: NOW, sessionCount: 1 }],
  };
  const hub = {
    snapshot, status: 'live', problem: null, macName: 'Mac',
    events: () => events, loadEvents: async () => events, agents: () => [], loadAgents: async () => [],
    answer: async () => {}, prompt: async () => ({ queued: false }), newSession: async () => null, interrupt: async () => {}, menu: async () => {},
    screenshot: async () => { throw new Error('no'); }, telescope: async () => { throw new Error('no'); }, preview: async () => '', speak: async () => new ArrayBuffer(0),
    voices: async () => [],
    subscribe: (fn: (e: HubEvent) => void) => { listeners.add(fn); return () => listeners.delete(fn); },
  } as unknown as HubLike;
  const voice = { speaking: false, locked: false, onChange() {}, unlock() {}, stop() {}, blip() {}, speak: async () => 'mac' } as unknown as VoiceOut;
  const all = { ...DEFAULT_SETTINGS, ...settings } as Settings;
  const services: Services = {
    hub: () => hub, speech: () => new DemoSpeech([]), voice, settings: () => all, updateSettings: () => {},
    paired: () => true, showOnPhone: () => {}, shotReady: () => {},
  };
  return new LensController(services);
}
const wait = (ms: number) => new Promise(r => setTimeout(r, ms));

test('the switch end to end: the hybrid draws the approval, keeps the reply native; ?look=all draws the reply; built-in is as before', async () => {
  const native = setup({}), drawn = setup({ look: 'all' }), hybrid = setup({ look: 'hypernova' });
  hybrid.open('i');
  await wait(10);
  const h = hybrid.view();
  assert.ok(h.kind === 'card' && h.look === 'hypernova' && h.role === 'reply');
  assert.equal(drawView(h), null, 'the hybrid shows a reply in the glasses\' font');
  assert.ok(h.kind === 'card' && h.body.some(l => l.includes('↓')), 'laid out for the glasses\' font, with its mark');
  for (const lens of [native, drawn]) { lens.open('i'); }
  await wait(10);
  const n = native.view(), d = drawn.view();
  assert.equal(n.look, undefined);
  assert.equal(d.look, 'all');
  assert.ok(n.kind === 'card' && d.kind === 'card');
  if (n.kind !== 'card' || d.kind !== 'card') return;
  assert.equal(d.role, 'reply');
  // Native: the firmware font's window, with its ↓; drawn: Manrope's, longer, unmarked, every line within its width.
  assert.ok(n.body.some(l => l.includes('↓')));
  assert.ok(d.body.every(l => !l.includes('↓')));
  assert.ok(d.body.length > n.body.length, `${d.body.length} drawn lines vs ${n.body.length}`);
  for (const l of d.body) assert.ok(measure(l, FACE.body) <= READ_W);
  assert.ok(d.scroll && d.scroll.lines > d.scroll.rows && d.scroll.top === 0);
  checkDrawn(drawView(d)!, 'reply from the controller');
  // A swipe moves the window by a window less a line, in the drawn look's lines.
  drawn.down();
  const d2 = drawn.view();
  assert.ok(d2.kind === 'card' && d2.scroll!.top === Math.min(d.scroll.rows - 1, d.scroll.lines - d.scroll.rows));
  // The approval: its command in mono lines, drawn with its reason.
  drawn.doubleTap();
  native.doubleTap();
  await wait(10);
  for (const lens of [native, drawn]) lens.open('n');
  const na = native.view(), da = drawn.view();
  assert.ok(na.kind === 'card' && da.kind === 'card' && da.role === 'approval');
  if (da.kind !== 'card' || na.kind !== 'card') return;
  for (const l of da.body) assert.ok(measure(l, FACE.mono) <= CMD_W);
  assert.ok(da.body.length <= na.body.length + 1);
  checkDrawn(drawView(da)!, 'approval from the controller');
  // The hybrid's approval is drawn like the development look's.
  hybrid.doubleTap();
  await wait(10);
  hybrid.open('n');
  const ha = hybrid.view();
  assert.ok(ha.kind === 'card' && ha.role === 'approval' && ha.look === 'hypernova');
  assert.deepEqual(ha.kind === 'card' && ha.body, da.body);
  checkDrawn(drawView(ha)!, 'the hybrid\'s approval');
  // The hybrid's glance: drawn on the column plan, its spending a meter; by default it's the glasses' text.
  const gv = setup({ look: 'hypernova' }).view();
  assert.equal(gv.kind, 'glance');
  assert.equal(drawView(gv)?.plan, 'column');
  assert.equal(drawView(setup({}).view()), null);
  assert.ok(gv.kind === 'glance' && gv.meter && gv.meter.pct === 42 && gv.meter.pace === null);
  void TEXT_W; void CARD;
});
