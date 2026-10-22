// The drawn look's image tiles: where they sit on the lens (the slot plans), which of them changed
// since they were last sent, and what one costs on its way to the glasses. The firmware takes at most
// four image containers a page, each at most 288 x 144, so four tiles are exactly the whole lens:
//   full    four tiles, the whole 576 x 288 lens. The planet column is drawn into the first, at rest:
//           a planet that moved would send a whole 288 x 144 tile again, so on this plan none moves.
//   column  the planet column as its own 32 x 144 image (as on the native pages: a frame costs what
//           it costs there), two tiles beside it over the top half, and one 288 x 144 tile at the
//           bottom left. The bottom right isn't covered: a screen on this plan leaves it dark.
// Pure: runs under node.
import { lz4Compress } from '../core/lz4.ts';
import type { ImageSlot } from './pages.ts';

export type Plan = 'full' | 'column';

export interface Rect { x: number; y: number; w: number; h: number }

/** The tiles' container ids and names (apart from the planet column's 3, Telescope's 4-7, the batteries' 9). */
export const TILE_ID = 40;
const tile = (i: number, x: number, y: number, w: number, h: number): ImageSlot => ({ id: TILE_ID + i, name: `look${i}`, x, y, w, h, kind: 'look' });

export const PLANS: Record<Plan, ImageSlot[]> = {
  full: [tile(0, 0, 0, 288, 144), tile(1, 288, 0, 288, 144), tile(2, 0, 144, 288, 144), tile(3, 288, 144, 288, 144)],
  column: [tile(0, 32, 0, 272, 144), tile(1, 304, 0, 272, 144), tile(2, 0, 144, 288, 144)],
};

/** What a plan leaves uncovered (drawing there never reaches the lens). */
export const DARK: Record<Plan, Rect[]> = {
  full: [],
  column: [{ x: 288, y: 144, w: 288, h: 144 }],
};

/** A hash of the lens levels (0-15, one byte a pixel, `stride` wide) inside `r`. */
export function regionHash(levels: Uint8Array, stride: number, r: Rect): number {
  let h = 0x811c9dc5;
  for (let y = r.y; y < r.y + r.h; y++) {
    for (let i = y * stride + r.x, end = i + r.w; i < end; i++) { h ^= levels[i]; h = Math.imul(h, 0x01000193); }
  }
  return h >>> 0;
}

/**
 * Which tiles show something new: each tile's levels are hashed and compared with what was sent for
 * it last; `sent` is updated for the tiles returned. A tile whose pixels are what the glasses have
 * costs nothing (not even a PNG).
 */
export function changedTiles(slots: ImageSlot[], levels: Uint8Array, stride: number, sent: Map<number, number>): Array<{ slot: ImageSlot; hash: number }> {
  const out: Array<{ slot: ImageSlot; hash: number }> = [];
  for (const slot of slots) {
    const hash = regionHash(levels, stride, slot);
    if (sent.get(slot.id) === hash) continue;
    sent.set(slot.id, hash);
    out.push({ slot, hash });
  }
  return out;
}

/** `r` of the levels as packed 4-bit greys, two pixels a byte (the first in the high half). */
export function packGray4(levels: Uint8Array, stride: number, r: Rect): Uint8Array {
  const out = new Uint8Array(Math.ceil((r.w * r.h) / 2));
  let k = 0;
  for (let y = r.y; y < r.y + r.h; y++) {
    for (let x = r.x; x < r.x + r.w; x++, k++) {
      const l = levels[y * stride + x] & 15;
      out[k >> 1] |= k & 1 ? l : l << 4;
    }
  }
  return out;
}

/** What a tile weighs on its way to the glasses: as 4-bit greys, and those compressed with LZ4. */
export function tileCost(levels: Uint8Array, stride: number, r: Rect): { gray4: number; lz4: number } {
  const g = packGray4(levels, stride, r);
  return { gray4: g.length, lz4: lz4Compress(g).length };
}
