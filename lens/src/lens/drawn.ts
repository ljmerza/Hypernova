// The drawn look (look.ts 'hypernova'): a screen as a page description, a list of things to draw
// (text in the app's own type, hairlines, frames, glyphs) plus the planet column, which painter.ts
// draws on a canvas, quantises to the lens' 16 levels and sends as image tiles (tiles.ts). Pure:
// runs under node, so the layout is tested like the native pages are.
//
// In the Hypernova look (look.ts, a hybrid) it draws the glance (its spending as a meter, a pop-up
// over it) and approvals; in the development look 'all', replies too (arriving and scrolling). Every
// other screen stays the glasses' own text boxes (pages.ts), and so does moving mode. The
// design is the app's (DESIGN.md) for a green lens: Manrope for words, JetBrains Mono for commands,
// ids and numbers, tracked caps for a seam's label, hairline glyphs for the gestures, a drawn ring
// round the line in focus, the needs-you label as the one filled block. Sizes are the smallest the
// simulator showed legible at 4 bits (dev/type-metrics.mjs, `?specimen=`): words 15 px (13 at the
// least), mono 13 px (12), labels 11 px caps.
import { faceLines, fitFace, measure, wrapFace, type Face } from '../core/type.ts';
import { clean, partsOf, type Parts } from '../core/font.ts';
import { ago } from '../core/format.ts';
import type { SpendMeter } from '../core/meter.ts';
import { drawsCards, drawsReplies, type Look } from './look.ts';
import { phraseLines, type GlanceLine, type ImageSlot, type LensPage, type LensView, type OrreryView } from './pages.ts';
import { BAND_HINT, type Band } from './overlay.ts';
import { PLANS, type Plan } from './tiles.ts';

/** Hairline glyphs (DESIGN.md: neither font carries them at the right weight, so they're drawn). */
export type Icon = 'tap' | 'double' | 'swipe' | 'hold' | 'say' | 'plus' | 'down' | 'up' | 'warn' | 'dot' | 'ring' | 'reset';

/** `tone`: perceived brightness 0-1 (gray.ts maps it to a lens level). Text `y` is its baseline. */
export type Op =
  | { op: 'text'; x: number; y: number; s: string; face: Face; tone: number; align?: 'left' | 'right' }
  | { op: 'rect'; x: number; y: number; w: number; h: number; tone: number }
  | { op: 'frame'; x: number; y: number; w: number; h: number; r: number; tone: number; fill?: number }
  | { op: 'icon'; icon: Icon; x: number; y: number; size: number; tone: number };

/**
 * How drawn text meets the lens' levels (gray.ts): 'hard', anti-aliased edges to the nearest level
 * and the faintest fringe off (thin, open letters: the default); 'crisp', that fringe at the lowest
 * level (heavier); 'dither', below the lowest level dithered (a speckled halo).
 */
export type TextMode = 'crisp' | 'dither' | 'hard';

export interface DrawnPage {
  plan: Plan;
  ops: Op[];
  /** The planet column: on 'column' its own image, free to move; on 'full' drawn at rest into the first tile. */
  orrery: OrreryView | null;
  mode?: TextMode;
}

export const W = 576, H = 288;

// ---------------------------------------------------------------- type, tone, geometry

/** Perceived brightness by role (DESIGN.md's ink, muted, dim, ghost and hairlines, for a lens that has no faint light). */
export const TONE = { ink: 1, bright: 0.9, muted: 0.74, dim: 0.6, ghost: 0.5, line: 0.42 } as const;

export const FACE = {
  /** A reply's words, a glance line's project. */
  body: { family: 'sans', size: 15, weight: 500 },
  title: { family: 'sans', size: 15, weight: 600 },
  /** Leads, details, a hint, the band's line. */
  small: { family: 'sans', size: 13, weight: 500 },
  /** A seam's label: tracked caps. */
  label: { family: 'sans', size: 11, weight: 700, track: 1.5 },
  /** The wordmark, light. */
  mark: { family: 'sans', size: 15, weight: 300 },
  /** Commands. */
  mono: { family: 'mono', size: 13, weight: 500 },
  /** Times, counts, facts. */
  monoSmall: { family: 'mono', size: 12, weight: 500 },
} as const satisfies Record<string, Face>;

/** Text starts right of the planet column and stops this far from the lens' right edge. */
export const X0 = 38;
const RIGHT = 570;
/** Every line's room. */
export const TEXT_W = RIGHT - X0;
const HEAD_BASE = 19;
/** The hint's line at the foot. */
const FOOT_BASE = 280;
const FOOT_TOP = 266;

/** The glance's rows: the header line, then one line per session. */
export const DRAWN_ROW = { headerMid: 15, top: 30, pitch: 22 } as const;
/** A row's middle (row 0 is the header), where its planet sits. */
export const drawnPlanetY = (row: number) => (row === 0 ? DRAWN_ROW.headerMid : DRAWN_ROW.top + (row - 1) * DRAWN_ROW.pitch + DRAWN_ROW.pitch / 2);
/** The drawn look's planet column (orrery.ts geometry): 32 x 144 like the native one, its own rows. */
export const DRAWN_ORRERY = { h: 144, planetY: drawnPlanetY } as const;
const ORRERY_SLOT: ImageSlot = { id: 3, name: 'orrery', x: 0, y: 0, w: 32, h: 144, kind: 'orrery' };

/** A card's body: its first line's top, line pitch; a text that scrolls keeps a few px at the right for the scroll mark. */
export const CARD = { top: 31, pitch: 20, factsPitch: 16, scrollW: 8 } as const;
/** What a reply's line may take (the scroll mark beside it). */
export const READ_W = TEXT_W - CARD.scrollW;
/** An approval's command, in its frame. */
const CMD = { pad: 8, prompt: 14 } as const;
export const CMD_W = TEXT_W - 2 * CMD.pad - CMD.prompt;
const CMD_PITCH = 17;

/**
 * How many body lines a reply card holds over `facts` lines of facts (the drawn look's cardRoom):
 * from under the header to over the facts and the hint.
 */
export function drawnCardRoom(facts: number): number {
  const factsH = facts ? facts * CARD.factsPitch + 9 : 0;
  return Math.max(1, Math.floor((FOOT_TOP - 4 - CARD.top - factsH) / CARD.pitch));
}

/** How many command lines an approval holds under its lead, over `facts` lines of reason. */
export function drawnCommandRoom(lead: boolean, facts: number): number {
  const top = CARD.top + (lead ? 20 : 0) + CMD.pad;
  const factsH = facts ? facts * 18 + 8 : 0;
  return Math.max(1, Math.floor((FOOT_TOP - 6 - top - CMD.pad - factsH) / CMD_PITCH));
}

/** Facts as a sentence ("3 files changed, 48 tests passed, took 4m 12s") on up to `rows` lines, broken between facts (pages.ts phraseLines). */
export function drawnFactLines(facts: readonly string[], rows: number, face: Face = FACE.monoSmall, width = TEXT_W): string[] {
  return phraseLines(facts, rows, width, t => measure(t, face), (t, w) => fitFace(t, w, face));
}

/** A reply's text as the drawn look's lines (controller: the scroll's lines). */
export const drawnReadLines = (plain: string) => faceLines(plain, READ_W, FACE.body);
/** An approval's command as lines in its frame. */
export const drawnCommandLines = (detail: string) => wrapFace(detail, CMD_W, FACE.mono);
/** An approval's reason on up to two lines (one when a warning takes the other). */
export const drawnReasonLines = (facts: readonly string[], warn = '') => {
  const rows = warn ? 1 : 2;
  const lines = wrapFace(facts.map(clean).filter(Boolean).join(', '), TEXT_W - 18, FACE.small);
  return lines.length > rows ? [...lines.slice(0, rows - 1), fitFace(lines.slice(rows - 1).join(' '), TEXT_W - 18, FACE.small)] : lines;
};

// ---------------------------------------------------------------- the page

/** The glasses' page for a drawn page: no text but the gesture box (g2.ts), and the plan's images. */
export function drawnLensPage(d: DrawnPage): LensPage {
  return { boxes: [], images: d.plan === 'column' ? [ORRERY_SLOT, ...PLANS.column] : [...PLANS.full] };
}

/** Whether the look draws this view (the glance, an approval; replies only in the development look), or leaves it to the glasses' own font. */
export function drawsView(v: LensView, look: Look | undefined): boolean {
  if (!drawsCards(look)) return false;
  return v.kind === 'glance' || (v.kind === 'card' && (v.role === 'approval' || (v.role === 'reply' && drawsReplies(look))));
}

/** The drawn look's page for a view, or null for a screen it doesn't draw (it stays native). */
export function drawView(v: LensView): DrawnPage | null {
  if (!drawsCards(v.look)) return null;
  const right = (r: Parts): Parts => (v.wave ? [v.wave, ...partsOf(r)] : r);
  let page: DrawnPage | null = null;
  if (v.kind === 'glance') page = glance({ ...v, right: right(v.right) }, !!v.band);
  else if (v.kind === 'card' && v.role === 'reply' && drawsReplies(v.look)) page = reply({ ...v, right: right(v.right) });
  else if (v.kind === 'card' && v.role === 'approval') page = approval({ ...v, right: right(v.right) });
  if (!page) return null;
  if (v.band) page.ops.push(...bandOps(v.band));
  return { ...page, mode: 'hard' };
}

const text = (x: number, y: number, s: string, face: Face, tone: number, align?: 'right'): Op => ({ op: 'text', x, y, s, face, tone, ...(align ? { align } : {}) });

/** The drawn look's gap between a line's parts (its type is narrower than the firmware's). */
const DRAWN_GAP = '   ';
const drawnJoin = (p: Parts) => partsOf(p).map(clean).filter(Boolean).join(DRAWN_GAP);

/**
 * The seam along the top: the head's label (tracked caps, or the filled needs-you block), the rest of
 * it (the project), a hairline, and the trail on the right in mono.
 */
function seam(ops: Op[], head: Parts, trail: Parts, urgent = false): void {
  const parts = partsOf(head).map(clean).filter(Boolean);
  const label = (parts[0] ?? '').toUpperCase(), rest = parts.slice(1).join(DRAWN_GAP);
  const t = drawnJoin(trail);
  const trailW = t ? measure(t, FACE.monoSmall) : 0;
  let x = X0;
  if (urgent) {
    // The one filled block on the lens: what needs you (DESIGN.md: Hypernavigate's urgent state).
    const w = Math.ceil(measure(label, FACE.label) - (FACE.label.track ?? 0)) + 12;
    ops.push({ op: 'frame', x: x - 1, y: 6, w, h: 17, r: 8, tone: 0, fill: TONE.bright });
    ops.push(text(x + 5, HEAD_BASE - 1, label, FACE.label, 0));
    x += w + 8;
  } else {
    ops.push(text(x, HEAD_BASE - 1, label, FACE.label, TONE.bright));
    x += Math.ceil(measure(label, FACE.label)) + 6;
  }
  if (rest) {
    const room = RIGHT - x - (trailW ? trailW + 24 : 0);
    const s = fitFace(rest, room, FACE.title);
    ops.push(text(x, HEAD_BASE, s, FACE.title, TONE.ink));
    x += Math.ceil(measure(s, FACE.title)) + 10;
  }
  hairline(ops, x, t ? RIGHT - trailW - 10 : RIGHT, HEAD_BASE - 5);
  if (t) ops.push(text(RIGHT, HEAD_BASE, t, FACE.monoSmall, TONE.dim, 'right'));
}

function hairline(ops: Op[], x0: number, x1: number, y: number): void {
  if (x1 - x0 >= 12) ops.push({ op: 'rect', x: Math.round(x0), y, w: Math.round(x1 - x0), h: 1, tone: TONE.line });
}

const GESTURES: Array<[RegExp, Icon]> = [[/^double tap /, 'double'], [/^tap /, 'tap'], [/^swipe /, 'swipe'], [/^hold /, 'hold'], [/^say /, 'say']];

/** A hint's parts (["tap allow", "double tap deny"]) as [glyph, words] pairs: each part's gesture is its glyph (a part with none, a toast, keeps its words). */
export function hintParts(hint: Parts): Array<{ icon: Icon | null; words: string }> {
  return partsOf(hint).map(clean).filter(Boolean).map(part => {
    // "swipe to read" reads "↕ read", "hold to talk" "◎ talk".
    for (const [re, icon] of GESTURES) if (re.test(part)) return { icon, words: part.replace(re, '').replace(/^to /, '') };
    return { icon: null, words: part };
  });
}

/** The hint at the foot: a glyph for each gesture and what it does; what doesn't fit `width` is left out. */
function footer(ops: Op[], hint: Parts, width = TEXT_W): void {
  const parts = hintParts(hint);
  const ICON = 11, GAP = 16;
  const partW = (p: { icon: Icon | null; words: string }) => (p.icon ? ICON + 5 : 0) + measure(p.words, FACE.small);
  while (parts.length > 1 && parts.reduce((w, p) => w + partW(p), 0) + GAP * (parts.length - 1) > width) parts.pop();
  let x = X0;
  for (const p of parts) {
    if (p.icon) { ops.push({ op: 'icon', icon: p.icon, x, y: FOOT_BASE - 10, size: ICON, tone: TONE.dim }); x += ICON + 5; }
    const words = fitFace(p.words, X0 + width - x, FACE.small);
    ops.push(text(x, FOOT_BASE, words, FACE.small, TONE.dim));
    x += measure(words, FACE.small) + GAP;
  }
}

// ---------------------------------------------------------------- the glance

/**
 * The glance: the wordmark and the time, a hairline, the usage on the right; a line per session
 * (project in semibold, what it does, a tag as a hairline chip, how long on the right), the line in
 * focus held in a drawn ring; "+ New session"; the hint at the foot. Its planets move, so it is on
 * the column plan: everything it says fits the top half and the bottom left (tiles.ts). With a
 * pop-up band it needs the whole lens: the full plan, its planets at rest while the band shows.
 */
function glance(v: Extract<LensView, { kind: 'glance' }>, band: boolean): DrawnPage {
  const ops: Op[] = [];
  const plan: Plan = band ? 'full' : 'column';
  // hypernova/ in light lowercase with the slash lit, as the app's wordmark.
  const word = clean(v.left).replace(/\/$/, '');
  ops.push(text(X0, HEAD_BASE, word, FACE.mark, TONE.muted));
  let x = X0 + measure(word, FACE.mark);
  if (v.left.endsWith('/')) { ops.push(text(x, HEAD_BASE, '/', FACE.title, TONE.ink)); x += measure('/', FACE.title); }
  if (v.clock) { x += 12; ops.push(text(x, HEAD_BASE, clean(v.clock), FACE.monoSmall, TONE.dim)); x += measure(clean(v.clock), FACE.monoSmall); }
  // Spending as a meter (core/meter.ts); without one (offline, signed out…) the corner's words.
  if (v.meter && !v.wave) {
    const left = meter(ops, v.meter, RIGHT, HEAD_BASE, !!v.eclipse);
    hairline(ops, x + 10, left - 10, HEAD_BASE - 5);
  } else {
    const right = drawnJoin(v.right), rw = right ? measure(right, FACE.monoSmall) : 0;
    hairline(ops, x + 10, right ? RIGHT - rw - 10 : RIGHT, HEAD_BASE - 5);
    if (right) ops.push(text(RIGHT, HEAD_BASE, fitFace(right, RIGHT - x - 30, FACE.monoSmall), FACE.monoSmall, TONE.muted, 'right'));
  }
  const rowTop = (i: number) => DRAWN_ROW.top + i * DRAWN_ROW.pitch;
  const base = (i: number) => rowTop(i) + 15;
  if (!v.rows.length) ops.push(text(X0, base(0), fitFace(clean(v.empty), TEXT_W, FACE.body), FACE.body, TONE.muted));
  v.rows.forEach((r, i) => row(ops, r, rowTop(i), base(i)));
  if (v.newRow) {
    const i = Math.max(1, v.rows.length), on = v.newRow.focused;
    if (on) focusRing(ops, rowTop(i));
    ops.push({ op: 'icon', icon: 'plus', x: X0, y: base(i) - 10, size: 11, tone: on ? TONE.ink : TONE.dim });
    ops.push(text(X0 + 17, base(i), clean(v.newRow.label), FACE.small, on ? TONE.ink : TONE.dim));
  }
  // On the column plan only the bottom-left tile is there: the hint keeps to it.
  if (partsOf(v.hint).length) footer(ops, v.hint, plan === 'column' ? 288 - X0 - 4 : TEXT_W);
  return { plan, ops, orrery: v.orrery };
}

/** The meter's bar, px. */
export const METER = { w: 88, h: 7 } as const;

/**
 * Spending as a projection meter, right-aligned to `right` on the line whose baseline is `base`
 * (DESIGN.md, the owner's projection meters): the window's label in tracked caps and its percentage
 * in mono, a bar (the track a hairline, used solid, the pace a faint fill to where it leads by the
 * reset), and one tag: how soon the limit comes, bright beside a notch at the bar's end, when it
 * comes before the reset; else the reset, dim, after a drawn reset glyph. Returns where it starts.
 */
function meter(ops: Op[], m: SpendMeter, right: number, base: number, eclipse: boolean): number {
  const soon = m.limitInMs !== null, hot = soon || m.spent;
  const tag = soon ? ago(m.limitInMs!) : m.resetInMs !== null ? ago(m.resetInMs) : '';
  let x = right;
  if (tag) {
    ops.push(text(x, base, tag, FACE.monoSmall, hot ? TONE.ink : TONE.dim, 'right'));
    x -= measure(tag, FACE.monoSmall);
    if (!soon) { x -= 13; ops.push({ op: 'icon', icon: 'reset', x, y: base - 10, size: 11, tone: hot ? TONE.bright : TONE.dim }); }
    x -= 8;
  }
  const bx = x - METER.w, by = base - 9, inner = METER.w - 2;
  ops.push({ op: 'frame', x: bx, y: by, w: METER.w, h: METER.h, r: 3, tone: TONE.line });
  const used = Math.round((inner * Math.max(0, Math.min(100, m.pct))) / 100);
  if (m.pace !== null) {
    const to = Math.round((inner * Math.max(m.pct, Math.min(100, m.pace))) / 100);
    if (to > used) ops.push({ op: 'rect', x: bx + 1 + used, y: by + 1, w: to - used, h: METER.h - 2, tone: TONE.ghost });
  }
  if (used > 0) ops.push({ op: 'rect', x: bx + 1, y: by + 1, w: used, h: METER.h - 2, tone: TONE.bright });
  // The limit before the reset (or now): a notch at the end, standing out of the bar.
  if (hot) ops.push({ op: 'rect', x: bx + METER.w - 2, y: by - 3, w: 2, h: METER.h + 6, tone: TONE.ink });
  x = bx - 7;
  const pct = `${Math.round(m.pct)}%`;
  ops.push(text(x, base, pct, FACE.monoSmall, hot ? TONE.ink : TONE.muted, 'right'));
  x -= measure(pct, FACE.monoSmall) + 6;
  const label: Face = { family: 'sans', size: 10, weight: 700, track: 1 };
  const name = eclipse ? `ECLIPSE  ${m.label}` : m.label;
  ops.push(text(x, base - 1, name, label, TONE.dim, 'right'));
  return x - measure(name, label);
}

/** The ring round the line in focus (the selection a native list would draw). */
function focusRing(ops: Op[], top: number): void {
  ops.push({ op: 'frame', x: X0 - 6, y: top, w: RIGHT + 4 - (X0 - 6), h: DRAWN_ROW.pitch, r: 7, tone: TONE.ghost });
}

function row(ops: Op[], r: GlanceLine, top: number, base: number): void {
  if (r.focused) focusRing(ops, top);
  const lit = r.focused ? TONE.ink : r.state === 'needs' ? TONE.bright : TONE.muted;
  const right = clean(r.right), rw = right ? measure(right, FACE.monoSmall) : 0;
  const end = RIGHT - 4 - (rw ? rw + 12 : 0);
  if (right) ops.push(text(RIGHT - 4, base, right, FACE.monoSmall, r.focused ? TONE.muted : TONE.dim, 'right'));
  // The tag ("2 agents running", "2 queued") stays whole: a chip after the detail; the detail gives way first.
  const tag = drawnJoin(r.tag ?? []).toUpperCase();
  const chipFace: Face = { family: 'sans', size: 10, weight: 700, track: 1 };
  const chipW = tag ? Math.ceil(measure(tag, chipFace)) + 12 : 0;
  const project = fitFace(clean(r.project), Math.min(220, end - X0), FACE.title);
  ops.push(text(X0, base, project, FACE.title, lit));
  let x = X0 + measure(project, FACE.title) + 8;
  // The phone's phrase where it fits, else the short one.
  const room = end - x - (chipW ? chipW + 8 : 0);
  const full = clean(r.detail);
  const detail = measure(full, FACE.small) <= room ? full : fitFace(clean(r.detailShort ?? r.detail), room, FACE.small);
  if (detail) { ops.push(text(x, base, detail, FACE.small, r.focused ? TONE.bright : TONE.dim)); x += measure(detail, FACE.small) + 8; }
  if (tag && x + chipW <= end) {
    ops.push({ op: 'frame', x, y: top + 4, w: chipW, h: 14, r: 7, tone: TONE.line });
    ops.push(text(x + 6, top + 15, tag, chipFace, TONE.muted));
  }
}

// ---------------------------------------------------------------- a reply

/**
 * A reply (or a finished turn's words): the seam, the text as one that scrolls (its lines laid out
 * by the controller in this look's type, typeset.ts), a scroll mark at the right while there's more
 * than shows, its facts in mono under a hairline, the hint. Its planet is at rest: the whole lens
 * is text here, so it is on the full plan, and words arriving cost only the tiles they land in.
 */
function reply(v: Extract<LensView, { kind: 'card' }>): DrawnPage {
  const ops: Op[] = [];
  seam(ops, v.head, v.right);
  const rows = Math.max(1, v.body.length);
  v.body.forEach((l, i) => { if (l) ops.push(text(X0, CARD.top + i * CARD.pitch + 15, l, FACE.body, TONE.ink)); });
  const s = v.scroll;
  if (s && s.lines > s.rows) {
    // The track along the window's right and a thumb where the window is: how much there is, and where you are.
    const top = CARD.top + 3, h = rows * CARD.pitch - 6, x = RIGHT - 2;
    ops.push({ op: 'rect', x, y: top, w: 1, h, tone: TONE.line });
    const th = Math.max(12, Math.round((h * s.rows) / s.lines));
    const ty = top + Math.round(((h - th) * s.top) / Math.max(1, s.lines - s.rows));
    ops.push({ op: 'rect', x: x - 1, y: ty, w: 3, h: th, tone: TONE.bright });
  }
  const facts = v.facts.length ? drawnFactLines(v.facts, v.factsRows ?? 1) : [];
  if (facts.length) {
    const y = CARD.top + rows * CARD.pitch + 5;
    hairline(ops, X0, RIGHT, y);
    facts.forEach((f, i) => ops.push(text(X0, y + 14 + i * CARD.factsPitch, f, FACE.monoSmall, TONE.dim)));
  }
  if (partsOf(v.hint).length) footer(ops, v.hint);
  return { plan: 'full', ops, orrery: v.orrery };
}

// ---------------------------------------------------------------- an approval

/**
 * An approval: the needs-you block and the project, what it wants (the lead), the command in mono in
 * a terminal's frame with its prompt, Claude's reason and a warning for what can't be undone, the
 * hint. On the full plan: the planet holds still in its bracket (the block says it needs you).
 */
function approval(v: Extract<LensView, { kind: 'card' }>): DrawnPage {
  const ops: Op[] = [];
  seam(ops, v.head, v.right, true);
  let y = CARD.top;
  if (v.lead) { ops.push(text(X0, y + 14, fitFace(clean(v.lead), TEXT_W, FACE.small), FACE.small, TONE.muted)); y += 20; }
  const lines = v.body.length ? v.body : [''];
  const h = lines.length * CMD_PITCH + 2 * CMD.pad - 4;
  ops.push({ op: 'frame', x: X0 - 4, y, w: TEXT_W + 6, h, r: 8, tone: TONE.line });
  // The prompt in a heavier weight: at 13 px a regular $ loses its thin stroke on the lens and reads as S.
  ops.push(text(X0 + CMD.pad - 4, y + CMD.pad + 10, '$', { ...FACE.mono, weight: 800 }, TONE.muted));
  lines.forEach((l, i) => ops.push(text(X0 + CMD.pad - 4 + CMD.prompt, y + CMD.pad + 10 + i * CMD_PITCH, l, FACE.mono, TONE.ink)));
  y += h + 8;
  // Claude's reason in grey; what can't be undone on its own line, with the warning glyph, brighter.
  const reason = drawnReasonLines(v.facts, v.warn);
  reason.forEach((l, i) => ops.push(text(X0, y + 13 + i * 18, l, FACE.small, TONE.muted)));
  if (v.warn) {
    const wy = y + reason.length * 18;
    ops.push({ op: 'icon', icon: 'warn', x: X0, y: wy + 3, size: 12, tone: TONE.bright });
    ops.push(text(X0 + 18, wy + 13, fitFace(clean(v.warn), TEXT_W - 18, FACE.small), FACE.small, TONE.bright));
  }
  if (partsOf(v.hint).length) footer(ops, v.hint);
  return { plan: 'full', ops, orrery: v.orrery };
}

// ---------------------------------------------------------------- the pop-up band

/**
 * A pop-up over the bottom of the lens: a glass card (solid, so nothing reads through) with the
 * head in tracked caps, what's new in words, and what a tap and a swipe do as glyphs.
 */
function bandOps(b: Band): Op[] {
  const ops: Op[] = [];
  const y = 218, h = 66;
  ops.push({ op: 'rect', x: 0, y: y - 4, w: W, h: H - y + 4, tone: 0 });
  ops.push({ op: 'frame', x: X0 - 6, y, w: RIGHT + 4 - (X0 - 6), h, r: 10, tone: TONE.muted, fill: 0 });
  const right = [clean(b.right), drawnJoin(b.hint ?? BAND_HINT)].filter(Boolean).join(DRAWN_GAP);
  const rw = right ? measure(right, FACE.small) : 0;
  ops.push(text(X0 + 4, y + 22, fitFace(drawnJoin(b.head).toUpperCase(), RIGHT - X0 - rw - 30, FACE.label), FACE.label, TONE.bright));
  if (right) ops.push(text(RIGHT - 6, y + 22, right, FACE.small, TONE.dim, 'right'));
  const soon = b.detailRight ? clean(b.detailRight) : '', sw = soon ? measure(soon, FACE.monoSmall) : 0;
  ops.push(text(X0 + 4, y + 47, fitFace(clean(b.detail), TEXT_W - 12 - (sw ? sw + 16 : 0), FACE.body), FACE.body, TONE.ink));
  if (soon) ops.push(text(RIGHT - 6, y + 47, soon, FACE.monoSmall, TONE.bright, 'right'));
  return ops;
}

// ---------------------------------------------------------------- the type specimen (development)

const SAMPLE_EN = 'Fixed the export; it remembers the last folder. 48 tests pass.';
const SAMPLE_RU = 'Исправил экспорт: он помнит последнюю папку. 48 тестов прошли.';
const MONO_EN = 'git push origin main · rm -rf build/ && ./gradlew test';
const MONO_RU = 'Тесты: 48 из 48 · a0f3c9e · 12:04 · ./сборка.sh';

/**
 * `?specimen=n` (look.ts): 1 Manrope sizes, 2 JetBrains Mono, tracked labels and glyphs, 3 one
 * size per line in English and Russian for comparing text modes (`&textmode=`). For choosing the
 * smallest sizes that stay legible on the lens.
 */
export function specimen(n: number, mode: TextMode = 'crisp'): DrawnPage {
  const ops: Op[] = [];
  const tag = (y: number, s: string) => ops.push({ op: 'text', x: 4, y, s, face: { family: 'mono', size: 10, weight: 500 }, tone: 0.6 });
  const line = (y: number, s: string, face: Face, tone = 1) => ops.push({ op: 'text', x: 52, y, s, face, tone });
  if (n === 1) {
    let y = 2;
    for (const [size, weight] of [[11, 500], [12, 500], [13, 500], [13, 600], [14, 500], [15, 500], [16, 500], [18, 500]] as const) {
      y += Math.round(size * 1.05) + 4;
      tag(y, `${size}/${weight}`);
      line(y, size >= 16 ? SAMPLE_EN.slice(0, 50) : SAMPLE_EN, { family: 'sans', size, weight });
      y += Math.round(size * 1.05) + 3;
      line(y, size >= 16 ? SAMPLE_RU.slice(0, 48) : SAMPLE_RU, { family: 'sans', size, weight });
    }
  } else if (n === 2) {
    let y = 4;
    for (const size of [10, 11, 12, 13, 14]) {
      y += size + 6;
      tag(y, `m${size}`);
      line(y, MONO_EN, { family: 'mono', size, weight: 500 });
      y += size + 3;
      line(y, MONO_RU, { family: 'mono', size, weight: 500 });
    }
    for (const size of [9, 10, 11, 12]) {
      y += size + 9;
      tag(y, `L${size}`);
      line(y, 'NEEDS YOU   0STEMS   ДОБАВИТЬ   2 AGENTS RUNNING', { family: 'sans', size, weight: 700, track: Math.round(size * 0.14 * 10) / 10 }, 0.94);
    }
    y += 10;
    const icons: Icon[] = ['tap', 'double', 'swipe', 'hold', 'say', 'plus', 'down', 'up', 'warn', 'dot', 'ring', 'reset'];
    for (const [k, size] of [10, 12, 14].entries()) {
      icons.forEach((icon, i) => ops.push({ op: 'icon', icon, x: 60 + i * 26, y: y + k * 18, size, tone: 0.9 }));
      tag(y + k * 18 + size - 1, `i${size}`);
    }
  } else {
    let y = 6;
    for (const [size, weight] of [[12, 500], [13, 500], [14, 500], [15, 500], [16, 500]] as const) {
      y += size + 8;
      tag(y, `${size}`);
      line(y, SAMPLE_EN, { family: 'sans', size, weight });
      y += size + 6;
      line(y, SAMPLE_RU, { family: 'sans', size, weight }, 0.72);
    }
    tag(y + 22, mode);
  }
  return { plan: 'full', ops, orrery: null, mode };
}
