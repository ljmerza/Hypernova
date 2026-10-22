// The lens is 16 levels of green, and it doesn't light them linearly: in the Even Hub simulator
// (which matches the glasses' conversion) level 1 of 15 already shows at ~37% and mid-greys look
// almost white. Pictures are drawn as perceived brightness and mapped through the inverse curve.
// Numbers from Hypertasks (src/lens/pipeline.ts).

export const LENS_GAMMA = 0.36;
const FLOOR = (1 / 15) ** LENS_GAMMA;
const FLOOR_V = Math.round(FLOOR * 255);
const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5].map(v => (v + 0.5) / 16);
const LEVEL_OF = new Uint8Array(256);
for (let v = 0; v < 256; v++) LEVEL_OF[v] = Math.max(1, Math.min(15, Math.round(15 * (v / 255) ** (1 / LENS_GAMMA))));

/** Perceived brightness (0-255) of each lens level, for the phone's mirror of the glasses. */
export const SHOWN_OF = Array.from({ length: 16 }, (_, l) => (l ? Math.round(255 * (l / 15) ** LENS_GAMMA) : 0));

/** Lens level (0-15) for a perceived brightness; below the lowest level, an ordered dither. */
export function levelAt(v: number, x: number, y: number): number {
  if (v >= FLOOR_V) return LEVEL_OF[v];
  return v / FLOOR_V > BAYER[(y & 3) * 4 + (x & 3)] ? 1 : 0;
}

/** Synchronous PNG: toBlob() callbacks stall while the WebView is in the background. */
export function pngBytes(c: HTMLCanvasElement): Uint8Array {
  const url = c.toDataURL('image/png'), bin = atob(url.slice(url.indexOf(',') + 1)), out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/**
 * Quantise a perceived-brightness canvas to lens levels in place; returns a hash of the levels.
 * `crisp`: anything below the lowest level becomes off or that level (no dither), so small drawn
 * shapes keep clean edges instead of a speckled halo; 'hard': below the lowest level is off (no faint
 * fringe round drawn text, which then reads thinner).
 */
export function quantise(c: HTMLCanvasElement, crisp: boolean | 'hard' = false): { hash: number; levels: Uint8Array } {
  const ctx = c.getContext('2d', { willReadFrequently: true })!;
  const img = ctx.getImageData(0, 0, c.width, c.height), d = img.data;
  const levels = new Uint8Array(c.width * c.height);
  let h = 0x811c9dc5;
  for (let y = 0, i = 0; y < c.height; y++) {
    for (let x = 0; x < c.width; x++, i++) {
      const p = d[i * 4 + 1];
      const l = crisp && p < FLOOR_V ? (crisp !== 'hard' && p >= FLOOR_V * 0.6 ? 1 : 0) : levelAt(p, x, y);
      levels[i] = l;
      d[i * 4] = d[i * 4 + 1] = d[i * 4 + 2] = l * 17; d[i * 4 + 3] = 255;
      h ^= l; h = Math.imul(h, 0x01000193);
    }
  }
  ctx.putImageData(img, 0, 0);
  return { hash: h >>> 0, levels };
}

/**
 * A pixel of a drawn page (lens/drawn.ts: text and glyphs in the app's type) to its lens level. `p`:
 * its perceived brightness, `tone`: the brightness the text there was drawn at (0: none), both 0-255.
 * At or above the lens' lowest level it keeps its level; below, a pixel the glyph covers well (42% or
 * more of the text's own brightness) is lit at the lowest level, so a thin stroke of dim text (a
 * hyphen across two pixel rows) doesn't vanish, and the faint fringe goes: no halo, no dither.
 */
export function drawnLevel(p: number, tone: number): number {
  if (p >= FLOOR_V) return LEVEL_OF[p];
  return tone > 0 && p >= 0.42 * tone ? 1 : 0;
}

/** A part of a picture, in its own pixels. */
export interface Crop { x: number; y: number; w: number; h: number }

/**
 * Telescope's picture for the lens: `crop` of the picture (all of it when null; a zoomed quarter is
 * cut from the full-size picture, never enlarged from the small one) fitted into w x h. Light
 * pictures are turned dark (a white page would be a glaring green block on the lens, and text reads
 * better bright on black), contrast stretched, then error-diffused to the 16 levels so gradients
 * and photos survive. The diffusion runs tile by tile (w/2 x h/2, as the glasses get them) and
 * never crosses a tile's edge: a change in one part of the picture (a clock, a progress bar)
 * changes only its own tile, and only that tile goes to the glasses again. `crisp`, for a picture
 * drawn for the lens (the terminal): each pixel to its own level, no dither and no inversion, so
 * text keeps clean edges. `prev`: the frame before at this zoom (watching): a tile of a dithered
 * picture in which almost nothing moved (a twinkle, a caret's blink) keeps its old pixels, so it
 * isn't sent again; a small change in one place (a digit) still goes, and one that builds up slowly
 * gets through, as it's measured against what the lens shows. Returns
 * a canvas of lens levels (as greys) ready to cut into tiles, with the brightness it was made from.
 */
export function ditherScope(img: CanvasImageSource & { width: number; height: number }, crop: Crop | null, w: number, h: number, crisp = false, prev: ScopeFrame | null = null): ScopeFrame {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const ctx = c.getContext('2d', { willReadFrequently: true })!;
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, w, h);
  const r = crop ?? { x: 0, y: 0, w: img.width, h: img.height };
  const s = Math.min(w / r.w, h / r.h);
  const dw = Math.max(1, Math.round(r.w * s)), dh = Math.max(1, Math.round(r.h * s));
  const dx = Math.round((w - dw) / 2), dy = Math.round((h - dh) / 2);
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, r.x, r.y, r.w, r.h, dx, dy, dw, dh);
  const data = ctx.getImageData(0, 0, w, h), d = data.data;
  const lum = new Float32Array(w * h);
  let sum = 0, n = 0, lo = 1, hi = 0;
  for (let y = dy; y < dy + dh; y++) for (let x = dx; x < dx + dw; x++) {
    const i = y * w + x, j = i * 4;
    const v = (0.2126 * d[j] + 0.7152 * d[j + 1] + 0.0722 * d[j + 2]) / 255;
    lum[i] = v; sum += v; n++;
    lo = Math.min(lo, v); hi = Math.max(hi, v);
  }
  const invert = !crisp && n > 0 && sum / n > 0.55;
  const span = Math.max(0.2, hi - lo);
  const inside = (x: number, y: number) => x >= dx && x < dx + dw && y >= dy && y < dy + dh;
  const tw = Math.ceil(w / 2), th = Math.ceil(h / 2);
  const err = new Float32Array(w * h);
  const before = prev && prev.lum.length === lum.length && prev.canvas.width === w ? prev : null;
  const old = before?.canvas.getContext('2d', { willReadFrequently: true })!.getImageData(0, 0, w, h).data;
  for (let ty = 0; ty < h; ty += th) for (let tx = 0; tx < w; tx += tw) {
    const x1 = Math.min(w, tx + tw), y1 = Math.min(h, ty + th);
    // Drawn for the lens (the terminal) has no noise to ignore: any change is a real one, and the
    // Mac already answers an unchanged screen with the same link.
    if (!crisp && before && old && still(lum, before.lum, w, tx, ty, x1, y1)) {
      for (let y = ty; y < y1; y++) for (let x = tx; x < x1; x++) {
        const i = y * w + x, j = i * 4;
        d[j] = d[j + 1] = d[j + 2] = old[j]; d[j + 3] = 255;
        lum[i] = before.lum[i];
      }
      continue;
    }
    for (let y = ty; y < y1; y++) {
      for (let x = tx; x < x1; x++) {
        const i = y * w + x, j = i * 4;
        let level = 0;
        if (crisp) {
          // Drawn for the lens: its own brightness steps (a hairline at the lowest level that shows).
          const p = inside(x, y) ? Math.round(220 * lum[i]) : 0;
          level = p >= FLOOR_V ? LEVEL_OF[p] : p >= FLOOR_V / 2 ? 1 : 0;
        } else {
          let v = inside(x, y) ? (lum[i] - lo) / span : 0;
          if (inside(x, y) && invert) v = 1 - v;
          // Keep the brightest parts well below full glare: the lens is a light in front of the eye,
          // and a dark bar or button on the page becomes a bright one once inverted.
          const target = inside(x, y) ? 0.82 * Math.max(0, Math.min(1, v)) ** 1.2 : 0;
          // Linear light the lens emits for that perceived value, in levels 0..15.
          const want = 15 * target ** (1 / LENS_GAMMA) + err[i];
          level = Math.max(0, Math.min(15, Math.round(want)));
          const e = want - level;
          if (x + 1 < x1) err[i + 1] += e * 7 / 16;
          if (y + 1 < y1) {
            if (x > tx) err[i + w - 1] += e * 3 / 16;
            err[i + w] += e * 5 / 16;
            if (x + 1 < x1) err[i + w + 1] += e / 16;
          }
        }
        d[j] = d[j + 1] = d[j + 2] = level * 17; d[j + 3] = 255;
      }
    }
  }
  ctx.putImageData(data, 0, 0);
  return { canvas: c, lum };
}

export interface ScopeFrame { canvas: HTMLCanvasElement; lum: Float32Array }

/** Under this share of a tile's pixels moved visibly, it counts as unchanged… */
const MOVED = 0.0025;
/**
 * …unless they moved together: this many or more, in a block at least 3 pixels each way and
 * filled this densely, are a changed digit or word ("41 passed" → "42 passed"), not noise. A
 * twinkle or two is a few scattered pixels; a caret is a line 1–2 pixels wide.
 */
const CLUSTER = 12;
const DENSE = 0.15;

/**
 * Whether a tile of a dithered picture is unchanged for the lens: almost nothing moved, and what did
 * is scattered noise rather than one small real change (which would otherwise never be sent, as each
 * look is measured against what the lens shows).
 */
export function still(now: Float32Array, was: Float32Array, w: number, x0: number, y0: number, x1: number, y1: number): boolean {
  let moved = 0, left = x1, right = x0 - 1, top = y1, bottom = y0 - 1;
  const allowed = (x1 - x0) * (y1 - y0) * MOVED;
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
    if (Math.abs(now[y * w + x] - was[y * w + x]) <= 0.08) continue;
    if (++moved > allowed) return false;
    if (x < left) left = x;
    if (x > right) right = x;
    if (y < top) top = y;
    if (y > bottom) bottom = y;
  }
  if (moved < CLUSTER) return true;
  const bw = right - left + 1, bh = bottom - top + 1;
  return bw < 3 || bh < 3 || moved / (bw * bh) < DENSE;
}

/** One tile of a level canvas, as PNG bytes, with a hash of its pixels. */
export function tile(src: HTMLCanvasElement, x: number, y: number, w: number, h: number): { png: Uint8Array; hash: number } {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const ctx = c.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(src, x, y, w, h, 0, 0, w, h);
  const d = ctx.getImageData(0, 0, w, h).data;
  let hash = 0x811c9dc5;
  for (let i = 0; i < d.length; i += 4) { hash ^= d[i]; hash = Math.imul(hash, 0x01000193); }
  return { png: pngBytes(c), hash: hash >>> 0 };
}
