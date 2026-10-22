// The lens screens as the glasses' own text boxes, plus a narrow image column for the planets and
// four image tiles for Telescope's picture. Text changes cost one small Bluetooth write per box; anything
// that moves or restyles a box rebuilds the page (see sender.ts). Pure: runs under node.
import { BOX_BYTES, CAP_H, GAP, HINT_GAP, LINE, SAFE, boxHeight, centre, clean, fitBytes, gapJoin, nativeFit, nativeWidth, partsOf, spaces, spread, utf8Bytes, type Parts } from '../core/font.ts';
import { headLines, tailLines, wrapAll } from '../core/text.ts';
import { SCOPE } from '../core/telescope.ts';
import type { RowState } from '../core/glance.ts';
import type { SunMode } from '../core/limits.ts';
import { powerPage, withBand, type Band, type PowerView } from './overlay.ts';
import type { Look } from './look.ts';
import type { SpendMeter } from '../core/meter.ts';
import { groupCode } from '../core/format.ts';

export const W = 576, H = 288;

export interface NativeBox {
  id: number;
  /** ≤ 16 characters, unique on the page. */
  name: string;
  x: number;
  y: number;
  w: number;
  h: number;
  content: string;
  border: number;
  /** 0-16, brightness of the outline. */
  borderColor: number;
  radius: number;
  pad: number;
  /** 0-4 (dim to full); undefined = the glasses' default (full). */
  textColor?: number;
}

export type ImageKind = 'orrery' | 'shot0' | 'shot1' | 'shot2' | 'shot3' | 'power' | 'look';
export interface ImageSlot { id: number; name: string; x: number; y: number; w: number; h: number; kind: ImageKind }

export interface LensPage { boxes: NativeBox[]; images: ImageSlot[] }

// ---------------------------------------------------------------- what the screens show

export type PlanetState = RowState | 'listening' | 'quiet';

/** The planet column: the sun (usage) on top, then one planet per row. A card has no sun: its
 * header line is a seam, with the session's planet in front of it. */
export interface OrreryView {
  /** `mode`: the accretion disk, quiet at the 5-hour limit, the low state at the weekly one, an
   * eclipse (core/limits.ts); `flashAt`: a limit just lifted, the disk's outburst. */
  sun: { pct: number; limit: boolean; mode?: SunMode; flashAt?: number } | null;
  planets: Array<{ state: PlanetState; focused: boolean; blipAt?: number } | null>;
  seam?: { state: PlanetState; blipAt?: number };
}

/**
 * A glance row: the project in a column of its own, what it does (`detailShort` where the row has no
 * room for `detail`), its tag ("2 agents running", "2 queued", each kept whole), how long on the right.
 */
export interface GlanceLine { project: string; detail: string; detailShort?: string; right: string; state: RowState; focused: boolean; tag?: Parts }

/** What's on the lens; `band`: a pop-up over it (overlay.ts). */
/** `wave`: a voice speaks: the speaking wave, at the head of the header's right (core/speech-out.ts). */
/** `look`: drawn in the app's own type (look.ts, drawn.ts); its lines were laid out for it. */
export type LensView = Screen & { band?: Band | null; wave?: string | null; look?: Look };

/**
 * Every line of a screen is in parts (font.ts Parts), never fields joined with "·": `head`, its label
 * then what it's about (["NEEDS YOU", "0stems"]); `right`, the head's right side (["auto-deny in 9m",
 * "plan"]); `hint`, each gesture and what it does (["tap allow", "double tap deny"]). The firmware
 * look puts a gap between parts (a wider one between gestures), the drawn look its seams and glyphs.
 */
type Screen =
  | { kind: 'blank' }
  /** `code`: a connection's six digits, on a line of their own under the rest ("Code   482 913"). */
  | { kind: 'message'; title: string; sub: string; code?: string }
  /**
   * `clock`: the time of day, after `left`; `newRow`: "+ New session" under the sessions (tap: pick a project).
   * `meter`: the spending the corner's words say (`right`), for the drawn look to draw as a meter; `eclipse`: eclipse is on.
   */
  | { kind: 'glance'; left: string; clock?: string; right: Parts; rows: GlanceLine[]; empty: string; hint: Parts; orrery: OrreryView; newRow?: { label: string; focused: boolean } | null;
      meter?: SpendMeter | null; eclipse?: boolean }
  /** The battery card, on request ("battery", or a swipe up at the top of the glance). */
  | PowerView
  /**
   * `facts`: short phrases under the body, a sentence of them ("3 files changed, 48 tests passed"),
   * broken between two on up to `factsRows` lines (1 unless set); `warn`: what can't be undone, on a
   * line of its own after them ("careful: force push can’t be undone").
   * `role`: what the card is, for the drawn look (drawn.ts draws a reply and an approval; other cards stay native);
   * `scroll`: where a text that scrolls is (its window's first line, how many show, how many there are).
   */
  | { kind: 'card'; head: Parts; right: Parts; lead: string; body: string[]; facts: string[]; factsRows?: number; warn?: string; hint: Parts; orrery: OrreryView;
      role?: 'reply' | 'approval'; scroll?: { top: number; rows: number; lines: number } }
  /** A session at work: what runs now (`more`: lines that didn't fit), and its output as it scrolls. */
  | { kind: 'live'; head: Parts; right: Parts; now: string[]; more: number; output: string[]; hint: Parts; orrery: OrreryView }
  | { kind: 'choice'; head: Parts; right: Parts; prompt: string; options: Array<{ label: Parts; mark: '' | '○' | '●' }>; cursor: number; hint: Parts; orrery: OrreryView }
  /**
   * Talking: captions while listening (`live`: the newest lines), then what was heard. `lines`: the
   * draft as the window of a text that scrolls (wrapped TALK_TEXT_W less the ↓ mark, core/scroll.ts),
   * never cut short; `sendIn`: seconds until it goes by itself.
   */
  | { kind: 'talk'; head: Parts; right: Parts; text: string; live: boolean; hint: Parts; orrery: OrreryView; lines?: string[]; sendIn?: number }
  /**
   * Telescope: the label line over a full-width picture (`picture`: shot `key`, at quarter `zoom`
   * or whole; `crisp`: drawn for the lens, not dithered), or, while there is none, why.
   */
  | { kind: 'telescope'; head: Parts; right: Parts; status: string; hint: Parts; picture: boolean; loading: boolean; key: string; zoom: number | null; crisp: boolean }
  /** Moving (on a bike, walking): two short lines at the top and nothing else (lens/moving.ts). */
  | { kind: 'brief'; lines: Parts[] };

// ---------------------------------------------------------------- geometry

/** The planet column, left of every line. 32 x 144 keeps it within one legal image size. */
export const ORRERY = { x: 0, y: 0, w: 32, h: 144 } as const;
/** Where each row of the glance sits; row 0 is the header with the sun. */
export const ROW_Y = (i: number) => 2 + i * 28;
/** Planet centre for a row, inside the orrery image (a capital's middle on that line). */
export const PLANET_Y = (i: number) => ROW_Y(i) + 6 + CAP_H / 2 - ORRERY.y;
const X0 = 36;
const TW = W - X0 - 4;            // text column width
const TEXT_W = TW - SAFE;          // what a line may take
const FOOT_Y = H - LINE - 4;

const ID = { header: 10, row: 11, foot: 15, newRow: 16, lead: 20, body: 21, facts: 22, extra: 23 } as const;
const IMG = { orrery: 3, shot0: 4, shot1: 5, shot2: 6, shot3: 7 } as const;

const box = (p: Partial<NativeBox> & Pick<NativeBox, 'id' | 'name' | 'x' | 'y' | 'w' | 'h'>): NativeBox =>
  ({ content: '', border: 0, borderColor: 0, radius: 0, pad: 0, ...p });

const line = (id: number, name: string, y: number, content: string, textColor?: number, x = X0, w = TW): NativeBox =>
  box({ id, name, x, y, w, h: LINE, content, textColor });

/** A box of `n` lines (fixed, so text that grows or shrinks never moves the box). */
const lines = (id: number, name: string, y: number, rows: string[], textColor?: number, x = X0, w = TW, n = rows.length): NativeBox =>
  box({ id, name, x, y, w, h: boxHeight(Math.max(1, n), 0), content: rows.slice(0, Math.max(1, n)).join('\n'), textColor });

const orrery = (): ImageSlot => ({ id: IMG.orrery, name: 'orrery', ...ORRERY, kind: 'orrery' });

/** Brightness by role: what you act on is full, the rest steps back. */
const TONE = { full: 4, focus: 4, ask: 3, rest: 1, dim: 0 } as const;

export function buildPage(v: LensView): LensPage {
  const page = screenPage(v.wave ? withWave(v, v.wave) : v);
  return withinBytes(v.band ? withBand(page, v.band) : page);
}

/**
 * A voice reading: its word ("reading aloud", steady in this look: a wave rewritten three times a
 * second would be as many Bluetooth writes) first on the header's right side; moving, after the first line.
 */
function withWave(v: LensView, wave: string): LensView {
  if (v.kind === 'brief') {
    const [first = '', ...rest] = v.lines;
    return nativeWidth(gapJoin(first)) + nativeWidth(wave) + GAP <= BRIEF_W ? { ...v, lines: [[...partsOf(first), wave], ...rest] } : v;
  }
  if ('right' in v) return { ...v, right: [wave, ...partsOf(v.right as Parts)] } as LensView;
  return v;
}

/**
 * Every box within what the firmware takes (font.ts BOX_BYTES), whatever it was given: a box over it
 * is refused and the card goes blank. The screens fit their texts first (a scrolling window shows
 * fewer lines, core/scroll.ts fitRows; the live feed and captions keep their newest lines), so this
 * only ever cuts a box that would otherwise not show at all: its last lines go, then the end of a line.
 */
function withinBytes(p: LensPage): LensPage {
  if (p.boxes.every(b => utf8Bytes(b.content) <= BOX_BYTES)) return p;
  return { ...p, boxes: p.boxes.map(b => (utf8Bytes(b.content) <= BOX_BYTES ? b : { ...b, content: fitBytes(b.content) })) };
}

function screenPage(v: LensView): LensPage {
  switch (v.kind) {
    case 'power': return powerPage(v);
    case 'blank': return { boxes: [], images: [] };
    case 'message': return messagePage(v);
    case 'glance': return glancePage(v);
    case 'card': return cardPage(v);
    case 'live': return livePage(v);
    case 'choice': return choicePage(v);
    case 'talk': return talkPage(v);
    case 'telescope': return telescopePage(v);
    case 'brief': return briefPage(v);
  }
}

/** A head's label and what it's about, a gap between them; its right side flush with the end (the left gives way). */
function header(left: Parts, right: Parts, tone: number = TONE.dim): NativeBox {
  const l = gapJoin(left), r = gapJoin(right);
  return line(ID.header, 'header', ROW_Y(0), r ? spread(l, r, TEXT_W) : nativeFit(l, TEXT_W), tone);
}

/** A hint's gestures, a wider gap between them: "tap allow     double tap deny". */
export const hintLine = (hint: Parts) => gapJoin(hint, HINT_GAP);

function footer(hint: Parts): NativeBox {
  return line(ID.foot, 'foot', FOOT_Y, nativeFit(hintLine(hint), TEXT_W), TONE.dim);
}

// ---------------------------------------------------------------- glance

function glancePage(v: Extract<LensView, { kind: 'glance' }>): LensPage {
  // The time sits by the label, the way the Even App's own dashboard starts its top line, and goes
  // out with the header's own clock ("resets 2h 11m"): no box of its own. A wider gap than a word's,
  // so "hypernova/" and the time don't read as one path.
  const left = v.clock ? `${clean(v.left)}${spaces(16)}${clean(v.clock)}` : clean(v.left), right = gapJoin(v.right);
  const boxes = [line(ID.header, 'header', ROW_Y(0), right ? spread(left, right, TEXT_W) : nativeFit(left, TEXT_W), TONE.dim)];
  if (!v.rows.length) boxes.push(line(ID.row, 'row0', ROW_Y(1), nativeFit(clean(v.empty), TEXT_W), TONE.ask));
  // The projects in a column of their own (as wide as the widest, PROJECT_COL at most), so what each
  // row does starts at the same place, a gap after it.
  const col = Math.min(PROJECT_COL, Math.max(0, ...v.rows.map(r => nativeWidth(clean(r.project)))));
  v.rows.forEach((r, i) => boxes.push(line(ID.row + i, `row${i}`, ROW_Y(i + 1), glanceRow(r, col), r.focused ? TONE.focus : r.state === 'needs' ? TONE.ask : TONE.rest)));
  // "+ New session" under the sessions (the batteries are a card of their own now, on request).
  if (v.newRow) boxes.push(line(ID.newRow, 'new', ROW_Y(Math.max(1, v.rows.length) + 1), nativeFit(`+ ${clean(v.newRow.label)}`, TEXT_W), v.newRow.focused ? TONE.focus : TONE.rest));
  if (partsOf(v.hint).length) boxes.push(footer(v.hint));
  return { boxes, images: [orrery()] };
}

/** The glance's project column at most: a long name ends in "…" rather than push what it does aside. */
export const PROJECT_COL = 150;

/**
 * One glance row: the project in its column, a gap, what it does (the phone's phrase where it fits,
 * else the short one), a gap and its tag kept whole (the detail gives way first), how long on the right.
 */
export function glanceRow(r: GlanceLine, col: number): string {
  const project = nativeFit(clean(r.project), col);
  const lead = project + spaces(col - nativeWidth(project)) + spaces(GAP);
  const tag = gapJoin(r.tag ?? []);
  const right = clean(r.right);
  const room = TEXT_W - nativeWidth(lead) - (right ? nativeWidth(right) + 10 : 0) - (tag ? nativeWidth(spaces(GAP) + tag) : 0);
  const full = clean(r.detail), short = clean(r.detailShort ?? r.detail);
  const detail = nativeWidth(full) <= room ? full : nativeFit(short, room);
  const left = (lead + detail).trimEnd() + (tag ? spaces(GAP) + tag : '');
  return right ? spread(left, right, TEXT_W) : nativeFit(left, TEXT_W);
}

// ---------------------------------------------------------------- cards: needs you, result, session, plan, diff

export const CARD_TEXT_W = TEXT_W;

function cardPage(v: Extract<LensView, { kind: 'card' }>): LensPage {
  const boxes = [header(v.head, v.right, TONE.ask)];
  let y = ROW_Y(1) + 6;
  if (v.lead) { boxes.push(line(ID.lead, 'lead', y, nativeFit(clean(v.lead), TEXT_W), TONE.rest)); y += LINE; }
  const facts = cardFacts(v.facts, v.factsRows ?? 1, v.warn);
  const room = cardRoom(!!v.lead, facts.length);
  const body = v.body.slice(0, room).map(l => nativeFit(l, TEXT_W));
  const n = facts.length ? Math.max(1, body.length) : room;
  boxes.push(lines(ID.body, 'body', y, body, TONE.full, X0, TW, n));
  y += n * LINE + 4;
  if (facts.length) boxes.push(lines(ID.facts, 'facts', y, facts, TONE.ask));
  if (partsOf(v.hint).length) boxes.push(footer(v.hint));
  return { boxes, images: [orrery()] };
}

/**
 * Facts as a sentence of phrases ("3 files changed, 112 tests passed, took 4m 12s") on up to `rows`
 * lines of `width`, broken between two facts, never inside one; what still doesn't fit ends the
 * last line with an ellipsis. `measure` sizes a line (the firmware font, or the drawn look's type).
 */
export function phraseLines(facts: readonly string[], rows: number, width: number, measure: (s: string) => number, fit: (s: string, w: number) => string): string[] {
  const out: string[] = [];
  for (const part of facts.map(clean).filter(Boolean)) {
    const cur = out[out.length - 1];
    if (cur !== undefined && measure(`${cur}, ${part}`) <= width) out[out.length - 1] = `${cur}, ${part}`;
    else if (out.length < rows) out.push(part);
    else { out[out.length - 1] = `${cur}, ${part}`; break; }
  }
  return out.map(l => fit(l, width));
}

/** A card's facts in lines (`rows` at most), and its warning on a line of its own after them (it keeps its line). */
function cardFacts(facts: readonly string[], rows: number, warn?: string): string[] {
  const w = warn ? clean(warn) : '';
  const room = Math.max(w ? 0 : 1, rows - (w ? 1 : 0));
  const lines = room ? phraseLines(facts, room, TEXT_W, nativeWidth, nativeFit) : [];
  return w ? [...lines, nativeFit(w, TEXT_W)] : lines;
}

/** How many lines `facts` (and a warning) take on a card that gives them up to `rows` (0 for none). */
export const factRows = (facts: readonly string[], rows = 1, warn?: string): number => cardFacts(facts, rows, warn).length;

/**
 * How many body lines a card has room for, under its lead (if any) and over its facts (`facts`:
 * whether there are any, or on how many lines). A text that scrolls takes at most this many, the
 * same number however far it's scrolled, so its box keeps its size.
 */
export function cardRoom(lead: boolean, facts: boolean | number): number {
  let y = ROW_Y(1) + 6;
  if (lead) y += LINE;
  const n = Number(facts);
  return Math.max(1, Math.floor((FOOT_Y - 6 - y - (n ? n * LINE + 4 : 0)) / LINE));
}

// ---------------------------------------------------------------- a session at work

/**
 * Two framed boxes under the header: what runs now, and the turn's output. Each keeps its size
 * whatever it holds, so new text in either is one text update, never a new page. A frame starts
 * right of the planet column; its border and padding put the text where every other line starts.
 */
const FRAME = { x: 32, border: 1, pad: 5, radius: 6 } as const;
const FRAME_W = W - FRAME.x - 2;
export const LIVE_NOW_ROWS = 2;
export const LIVE_OUT_ROWS = 5;
/** What a line of either box may take. */
export const LIVE_TEXT_W = FRAME_W - 2 * (FRAME.border + FRAME.pad) - SAFE;
const NOW_Y = ROW_Y(1) + 3;
const OUT_Y = NOW_Y + boxHeight(LIVE_NOW_ROWS, FRAME.border, FRAME.pad) + 3;

const framed = (id: number, name: string, y: number, rows: string[], n: number, textColor: number, borderColor: number): NativeBox =>
  box({ id, name, x: FRAME.x, y, w: FRAME_W, h: boxHeight(n, FRAME.border, FRAME.pad), border: FRAME.border, borderColor, radius: FRAME.radius, pad: FRAME.pad,
    content: rows.slice(0, n).join('\n'), textColor });

function livePage(v: Extract<LensView, { kind: 'live' }>): LensPage {
  // Already clean (core/live.ts), with gaps clean() would collapse.
  const now = v.now.slice(0, LIVE_NOW_ROWS).map(l => nativeFit(l, LIVE_TEXT_W));
  if (v.more && now.length) now[now.length - 1] = spread(now[now.length - 1], `+${v.more} more`, LIVE_TEXT_W);
  const out = v.output.slice(0, LIVE_OUT_ROWS).map(l => nativeFit(l, LIVE_TEXT_W));
  const boxes = [
    header(v.head, v.right, TONE.ask),
    framed(ID.lead, 'now', NOW_Y, now, LIVE_NOW_ROWS, TONE.ask, 3),
    framed(ID.body, 'output', OUT_Y, out, LIVE_OUT_ROWS, TONE.full, 5),
  ];
  if (partsOf(v.hint).length) boxes.push(footer(v.hint));
  return { boxes, images: [orrery()] };
}

// ---------------------------------------------------------------- questions and menus

export const CHOICE_ROWS = 4;

function choicePage(v: Extract<LensView, { kind: 'choice' }>): LensPage {
  const boxes = [header(v.head, v.right, TONE.ask)];
  const prompt = wrapAll(v.prompt, TEXT_W).slice(0, 2);
  let y = ROW_Y(1) + 6;
  if (prompt.length) {
    if (wrapAll(v.prompt, TEXT_W).length > 2) prompt[1] = nativeFit(prompt[1] + ' …', TEXT_W);
    boxes.push(lines(ID.lead, 'lead', y, prompt, TONE.full));
    y += prompt.length * LINE + 6;
  }
  const rows = Math.min(CHOICE_ROWS, Math.floor((FOOT_Y - 4 - y) / LINE));
  const first = Math.max(0, Math.min(v.cursor - Math.floor(rows / 2), v.options.length - rows));
  const shown = v.options.slice(first, first + rows).map((o, k) => {
    const i = first + k;
    const head = (i === v.cursor ? '▶ ' : spaces(nativeWidth('▶ '))) + (o.mark ? `${o.mark} ` : '');
    let label = head + nativeFit(gapJoin(o.label), TEXT_W - nativeWidth(head));
    if (k === rows - 1 && first + rows < v.options.length) label = spread(label, '↓', TEXT_W);
    return label;
  });
  boxes.push(lines(ID.body, 'body', y, shown, TONE.full, X0, TW, rows));
  if (partsOf(v.hint).length) boxes.push(footer(v.hint));
  return { boxes, images: [orrery()] };
}

// ---------------------------------------------------------------- talk

export const TALK_ROWS = 5;
/** What a line of the talk box may take. */
export const TALK_TEXT_W = TEXT_W;

function talkPage(v: Extract<LensView, { kind: 'talk' }>): LensPage {
  const boxes = [header(v.head, v.right, TONE.ask)];
  const text = clean(v.text);
  let rows = v.lines ?? (v.live ? tailLines(text, TEXT_W, TALK_ROWS) : headLines(text, TEXT_W, TALK_ROWS));
  // Captions too dense for one box (long Russian lines) keep their newest words.
  if (v.live) rows = fitBytes(rows.join('\n'), BOX_BYTES, true).split('\n');
  boxes.push(lines(ID.body, 'body', ROW_Y(1) + 6, rows, v.live ? TONE.ask : TONE.full, X0, TW, TALK_ROWS));
  if (partsOf(v.hint).length) boxes.push(footer(v.hint));
  return { boxes, images: [orrery()] };
}

// ---------------------------------------------------------------- telescope

/** The four tiles of Telescope's picture, under the label line: as large as images may be (288 wide). */
const SCOPE_TILES: ImageSlot[] = (['shot0', 'shot1', 'shot2', 'shot3'] as const).map((kind, i) => ({
  id: IMG[kind], name: kind, kind, x: SCOPE.x + (i % 2) * (SCOPE.w / 2), y: SCOPE.y + Math.floor(i / 2) * (SCOPE.h / 2), w: SCOPE.w / 2, h: SCOPE.h / 2,
}));

function telescopePage(v: Extract<LensView, { kind: 'telescope' }>): LensPage {
  // The whole width: no planet column here, the picture is the screen.
  const x = 4, w = W - 8, tw = w - SAFE;
  const right = gapJoin(v.right);
  const head = right ? spread(nativeFit(gapJoin(v.head), tw - nativeWidth(right) - 12), right, tw) : nativeFit(gapJoin(v.head), tw);
  const boxes = [box({ id: ID.header, name: 'header', x, y: ROW_Y(0), w, h: LINE, content: head, textColor: TONE.ask })];
  if (v.picture) return { boxes, images: SCOPE_TILES };
  // No picture: the picture's place says why (or that it's coming) instead of staying dark.
  const lines = wrapAll(clean(v.status || (v.loading ? 'Pointing the telescope…' : '')), tw - 40).slice(0, 4);
  const fh = SCOPE.h - LINE - 16;
  const pad = Math.max(0, Math.floor((Math.floor((fh - 2) / LINE) - lines.length) / 2));
  boxes.push(box({ id: 24, name: 'frame', x: 8, y: SCOPE.y, w: W - 16, h: fh, border: 1, borderColor: 4, radius: 10,
    content: [...Array(pad).fill(''), ...lines.map(l => centre(l, W - 16 - 2 - SAFE))].join('\n'), textColor: v.loading ? TONE.dim : TONE.ask }));
  if (partsOf(v.hint).length) boxes.push(box({ id: ID.foot, name: 'foot', x, y: FOOT_Y, w, h: LINE, content: nativeFit(hintLine(v.hint), tw), textColor: TONE.dim }));
  return { boxes, images: [] };
}

// ---------------------------------------------------------------- messages

// ---------------------------------------------------------------- moving

/** A brief line is short: it keeps to the left part of the lens, out of the way of the road. */
export const BRIEF_W = 400;

/**
 * Moving mode: two short lines along the top, the first full, the second a step dimmer; no planets
 * (nothing that moves), no hint, no frame. Each line is its own box, so a change is a text update.
 */
function briefPage(v: Extract<LensView, { kind: 'brief' }>): LensPage {
  const x = 12, w = BRIEF_W + SAFE;
  const boxes = v.lines.slice(0, 2).map((l, i) => box({ id: i ? ID.body : ID.header, name: i ? 'brief1' : 'brief0', x, y: ROW_Y(i), w, h: LINE, content: nativeFit(gapJoin(l), BRIEF_W), textColor: i ? TONE.ask : TONE.full }));
  return { boxes, images: [] };
}

function messagePage(v: Extract<LensView, { kind: 'message' }>): LensPage {
  const full = W - SAFE;
  const sub = v.sub ? wrapAll(clean(v.sub), W - 140).slice(0, 3) : [];
  const total = LINE + 6 + LINE + (sub.length ? 8 + sub.length * LINE : 0) + (v.code ? 10 + LINE : 0);
  const y0 = Math.round((H - total) / 2);
  const boxes = [
    box({ id: ID.header, name: 'header', x: 0, y: y0, w: W, h: LINE, content: centre('hypernova/', full), textColor: TONE.dim }),
    box({ id: ID.body, name: 'body', x: 0, y: y0 + LINE + 6, w: W, h: LINE, content: centre(clean(v.title), full) }),
  ];
  let y = y0 + 2 * LINE + 6;
  if (sub.length) { boxes.push(box({ id: ID.lead, name: 'lead', x: 0, y: y + 8, w: W, h: boxHeight(sub.length, 0), content: sub.map(l => centre(l, full)).join('\n'), textColor: TONE.rest })); y += 8 + sub.length * LINE; }
  // The code alone on its line, in two threes as the phone's prompt shows it, a gap after "Code".
  if (v.code) boxes.push(box({ id: ID.facts, name: 'code', x: 0, y: y + 10, w: W, h: LINE, content: centre(`Code${spaces(15)}${groupCode(clean(v.code))}`, full) }));
  return { boxes, images: [] };
}

// ---------------------------------------------------------------- comparing pages

/** Everything but text and its brightness (a text update carries both): when this differs the
 * page has to be rebuilt. */
export const layoutKey = (p: LensPage) =>
  p.boxes.map(b => `${b.id}:${b.name}:${b.x},${b.y},${b.w},${b.h}:${b.border},${b.borderColor},${b.radius},${b.pad}`).join('|')
  + '#' + p.images.map(i => `${i.id}:${i.x},${i.y},${i.w},${i.h}`).join('|');

/** A box shows something different: new text or a new brightness. */
export const boxChanged = (a: NativeBox, b: NativeBox | undefined) => !b || a.content !== b.content || (a.textColor ?? 4) !== (b.textColor ?? 4);

export const samePage = (a: LensPage | null, b: LensPage | null) =>
  !!a && !!b && layoutKey(a) === layoutKey(b) && a.boxes.every((x, i) => !boxChanged(x, b.boxes[i]));
