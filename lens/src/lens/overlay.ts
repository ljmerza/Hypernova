// Two pages that sit apart from the rest: the pop-up band laid over whatever page is on the lens,
// and the battery card. Built from the same native text boxes (font.ts) as every page; the types
// come from pages.ts, which calls in here (so nothing here may need pages.ts' values at load).
import { GAP, HINT_GAP, LINE, SAFE, boxHeight, clean, gapJoin, nativeFit, nativeWidth, partsOf, spaces, spread, type Parts } from '../core/font.ts';
import { powerWords, type PowerRow } from '../core/power.ts';
import type { ImageSlot, LensPage, NativeBox } from './pages.ts';

const W = 576, H = 288;
const X0 = 36, TW = W - X0 - 4, TEXT_W = TW - SAFE;
const FOOT_Y = H - LINE - 4;
/** The firmware takes 8 text boxes: ours and the one that catches gestures. */
const MAX_BOXES = 7;

/**
 * What a pop-up band says (core/popups.ts bandWords): its head (its label, then what it's about),
 * "+2" when more merged in, and a line; `detailRight` at that line's end ("auto-deny in 9m"); `hint`,
 * what it does instead of BAND_HINT ([] for a band that only says something: Hypernova stopped on
 * the phone); `line`, all of it on one line, for moving ("0stems needs you: wants to push to main").
 */
export interface Band { head: Parts; right: string; detail: string; detailRight?: string; hint?: Parts; line?: string }

const BAND = { border: 1, pad: 4, radius: 6, id: 30 } as const;
const BAND_H = boxHeight(2, BAND.border, BAND.pad);
/** What the band says it does: the screen's own hint is under it. */
export const BAND_HINT: readonly string[] = ['tap open', 'swipe away'];

const box = (p: Partial<NativeBox> & Pick<NativeBox, 'id' | 'name' | 'x' | 'y' | 'w' | 'h'>): NativeBox =>
  ({ content: '', border: 0, borderColor: 0, radius: 0, pad: 0, ...p });

const overlaps = (a: { x: number; y: number; w: number; h: number }, b: { x: number; y: number; w: number; h: number }) =>
  a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

/**
 * `page` with the band along its bottom, right of the planet column (or of a picture that reaches
 * down there, when there's room beside it). What the band covers steps aside: a box under it goes,
 * and one that reaches into it loses the lines that would be hidden. The band is a new box, so it
 * comes and goes as a page rebuild: pop-ups are rare, text updates stay text updates. A picture that
 * fills the lens (Telescope's) is never cut for it (how the glasses stack text over images isn't
 * known): the band takes the page's top line instead, in one line, as a text update.
 */
export function withBand(page: LensPage, band: Band): LensPage {
  const y = H - BAND_H - 2;
  let x = 32;
  const low = page.images.filter(i => i.kind !== 'orrery' && i.y + i.h > y);
  const beside = low.length ? Math.max(...low.map(i => i.x + i.w)) + 8 : 0;
  // A picture down there with room to its right: the band goes beside it, the picture stays.
  if (beside && W - 2 - beside >= 240) x = beside;
  else if (beside) return headerBand(page, band);
  const rect = { x, y: y - 2, w: W - 2 - x, h: BAND_H + 4 };
  const boxes: NativeBox[] = [];
  for (const b of page.boxes) {
    if (!overlaps(b, rect)) { boxes.push(b); continue; }
    if (b.y >= rect.y - LINE / 2) continue;
    const h = rect.y - 2 - b.y;
    const room = Math.floor((h - 2 * b.border - 2 * b.pad) / LINE);
    if (room < 1) continue;
    boxes.push({ ...b, h, content: b.content.split('\n').slice(0, room).join('\n') });
  }
  const images: ImageSlot[] = page.images.filter(i => !overlaps(i, rect));
  // Room for the band among the firmware's boxes: the lowest go first.
  while (boxes.length >= MAX_BOXES) boxes.splice(boxes.reduce((k, b, i) => (b.y > boxes[k].y ? i : k), 0), 1);
  const w = rect.w, tw = w - 2 * (BAND.border + BAND.pad) - SAFE;
  const hint = gapJoin(band.hint ?? BAND_HINT, HINT_GAP);
  const right = [clean(band.right), hint].filter(Boolean).join(spaces(HINT_GAP));
  const head = gapJoin(band.head);
  const top = !right ? nativeFit(head, tw) : nativeWidth(right) + 60 < tw ? spread(head, right, tw) : nativeFit(head, tw);
  const detail = band.detailRight ? spread(clean(band.detail), clean(band.detailRight), tw, GAP) : nativeFit(clean(band.detail), tw);
  boxes.push(box({ id: BAND.id, name: 'band', x, y, w, h: BAND_H, border: BAND.border, borderColor: 13, radius: BAND.radius, pad: BAND.pad,
    content: `${top}\n${detail}`, textColor: 4 }));
  return { boxes, images };
}

/** The band in a page's top line: "NEEDS YOU   0stems   wants to push to main", then "+1   tap open". */
function headerBand(page: LensPage, band: Band): LensPage {
  const top = page.boxes.find(b => b.name === 'header') ?? page.boxes.reduce<NativeBox | null>((a, b) => (!a || b.y < a.y ? b : a), null);
  if (!top) return page;
  const tw = top.w - 2 * (top.border + top.pad) - SAFE;
  const right = gapJoin([band.right, band.hint && !partsOf(band.hint).length ? '' : 'tap open']);
  const content = spread(nativeFit(gapJoin([...partsOf(band.head), band.detail]), tw - nativeWidth(right) - 12), right, tw);
  return { ...page, boxes: page.boxes.map(b => (b === top ? { ...b, content, textColor: 4 } : b)) };
}

// ---------------------------------------------------------------- the battery card

/** The drawn part (icons and bars, src/lens/power-art.ts): one row per device, `row` px apart. */
export const POWER_ART = { x: 40, y: 44, w: 128, h: 132, row: 70 } as const;
/** Its image slot's id: apart from the planet column's and a picture's. */
export const POWER_IMAGE_ID = 9;
const POWER_TEXT_X = POWER_ART.x + POWER_ART.w + 12;
const POWER_TEXT_W = W - POWER_TEXT_X - 4;
/** The name and the percent stay close, a column's width apart, not at the two ends of the lens. */
const POWER_ROW_W = 250;

export interface PowerView { kind: 'power'; head: Parts; right: Parts; rows: PowerRow[]; awake: string | null; empty: string; hint: Parts }

/**
 * The battery card: a row per device (its icon and five-segment bar drawn, the name and percent in
 * text beside them), and under the Mac's whether Hypernova keeps it awake. With only one device it
 * sits on the first row; with none, the card says so.
 */
export function powerPage(v: PowerView): LensPage {
  const right = gapJoin(v.right);
  const boxes: NativeBox[] = [box({ id: 10, name: 'header', x: X0, y: 2, w: TW, h: LINE, content: right ? spread(gapJoin(v.head), right, TEXT_W) : nativeFit(gapJoin(v.head), TEXT_W), textColor: 3 })];
  v.rows.slice(0, 2).forEach((r, i) => {
    const y = POWER_ART.y + i * POWER_ART.row + 4;
    boxes.push(box({ id: 11 + i, name: `row${i}`, x: POWER_TEXT_X, y, w: POWER_TEXT_W, h: LINE, content: nativeFit(`${r.label}${spaces(Math.max(10, POWER_ROW_W - nativeWidth(r.label) - nativeWidth('100%')))}${gapJoin(powerWords(r))}`, POWER_TEXT_W - SAFE), textColor: 4 }));
  });
  if (!v.rows.length) boxes.push(box({ id: 11, name: 'row0', x: X0, y: POWER_ART.y + 4, w: TW, h: LINE, content: nativeFit(clean(v.empty), TEXT_W), textColor: 3 }));
  if (v.awake) {
    // Under the Mac's row; without the Mac's battery, under the last row, named.
    const mac = v.rows.findIndex(r => r.device === 'mac');
    const at = mac >= 0 ? mac : Math.max(0, v.rows.length - 1);
    const y = POWER_ART.y + at * POWER_ART.row + 4 + LINE + 2;
    const x = v.rows.length ? POWER_TEXT_X : X0, w = v.rows.length ? POWER_TEXT_W : TW;
    boxes.push(box({ id: 14, name: 'awake', x, y, w, h: LINE, content: nativeFit(clean(mac >= 0 ? v.awake : `Mac: ${v.awake}`), w - SAFE), textColor: 1 }));
  }
  if (partsOf(v.hint).length) boxes.push(box({ id: 15, name: 'foot', x: X0, y: FOOT_Y, w: TW, h: LINE, content: nativeFit(gapJoin(v.hint, HINT_GAP), TEXT_W), textColor: 0 }));
  const images: ImageSlot[] = v.rows.length ? [{ id: POWER_IMAGE_ID, name: 'power', x: POWER_ART.x, y: POWER_ART.y, w: POWER_ART.w, h: POWER_ART.h, kind: 'power' }] : [];
  return { boxes, images };
}
