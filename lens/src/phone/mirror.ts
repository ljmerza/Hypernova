// The phone's picture of the lens. The firmware font isn't available here, so glyphs come from the
// system font at the same cap height, placed at the firmware's measured advances: lines break and
// line up where they do on the glasses (after Hypertasks' native-mirror.ts). The planet column and
// a screenshot are drawn from the same pixels the glasses get.
import { CAP_H, CAP_TOP, LINE, charWidth } from '../core/font.ts';
import { H, W, type LensPage } from '../lens/pages.ts';
import { SHOWN_OF } from '../lens/gray.ts';

/** The G2's green. */
const TINT: [number, number, number] = [60, 250, 68];
/** Measured in the simulator: text brightness per textColor. */
const TEXT_V: Record<number, number> = { 0: 131, 1: 179, 2: 215, 3: 240, 4: 255 };
const borderV = (c: number) => Math.round(255 * Math.min(1, Math.max(0, c) / 13) ** 0.36);
const FONT_FAMILY = '-apple-system, BlinkMacSystemFont, "Helvetica Neue", Arial, sans-serif';
const FONT = `300 22px ${FONT_FAMILY}`;

const tint = (v: number) => `rgb(${Math.round(TINT[0] * v / 255)},${Math.round(TINT[1] * v / 255)},${Math.round(TINT[2] * v / 255)})`;
const widths = new Map<string, number>();

function roundRect(c: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  r = Math.max(0, Math.min(r, w / 2, h / 2));
  c.beginPath();
  c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r);
  c.closePath();
}

/** Lens-level greys (as the glasses get them) drawn in the lens' green at perceived brightness; `crop`: a part of `src` only. */
function drawLevels(c: CanvasRenderingContext2D, src: HTMLCanvasElement, x: number, y: number, crop = { x: 0, y: 0, w: src.width, h: src.height }): void {
  const s = src.getContext('2d', { willReadFrequently: true })!.getImageData(crop.x, crop.y, crop.w, crop.h);
  const out = c.createImageData(crop.w, crop.h);
  for (let i = 0; i < s.data.length; i += 4) {
    const level = Math.round(s.data[i] / 17), v = SHOWN_OF[level] / 255;
    out.data[i] = TINT[0] * v; out.data[i + 1] = TINT[1] * v; out.data[i + 2] = TINT[2] * v; out.data[i + 3] = 255;
  }
  c.putImageData(out, x, y);
}

export function drawLens(c: CanvasRenderingContext2D, page: LensPage | null, images: { orrery?: HTMLCanvasElement | null; shot?: HTMLCanvasElement | null; power?: HTMLCanvasElement | null; look?: HTMLCanvasElement | null }): void {
  c.save();
  c.setTransform(1, 0, 0, 1, 0, 0);
  c.fillStyle = '#000';
  c.fillRect(0, 0, W, H);
  for (const slot of page?.images ?? []) {
    if (slot.kind === 'orrery' && images.orrery) drawLevels(c, images.orrery, slot.x, slot.y);
    if (slot.kind === 'shot0' && images.shot) drawLevels(c, images.shot, slot.x, slot.y);
    if (slot.kind === 'power' && images.power) drawLevels(c, images.power, slot.x, slot.y);
    // The drawn look: each tile is its part of the whole drawn lens.
    if (slot.kind === 'look' && images.look) drawLevels(c, images.look, slot.x, slot.y, slot);
  }
  for (const b of page?.boxes ?? []) {
    if (b.border > 0) {
      roundRect(c, b.x + b.border / 2, b.y + b.border / 2, b.w - b.border, b.h - b.border, b.radius);
      c.strokeStyle = tint(borderV(b.borderColor));
      c.lineWidth = b.border;
      c.stroke();
    }
    if (!b.content) continue;
    c.fillStyle = tint(TEXT_V[b.textColor ?? 4] ?? 255);
    c.font = FONT;
    c.textBaseline = 'alphabetic';
    c.textAlign = 'left';
    c.save();
    c.beginPath(); c.rect(b.x, b.y, b.w, b.h); c.clip();
    const top = b.y + b.border + b.pad + CAP_TOP - 1;
    b.content.split('\n').forEach((line, i) => {
      let x = b.x + b.border + b.pad + 1;
      const y = top + CAP_H + i * LINE;
      for (const ch of line) {
        const adv = charWidth(ch);
        if (ch === '─' || ch === '━') {
          const t = ch === '━' ? 2 : 1;
          c.fillRect(x, Math.round(y - CAP_H / 2) - t / 2, adv, t);
        } else if (ch !== ' ') {
          // The firmware font is narrow and light: squeeze any wider system glyph into its advance.
          let gw = widths.get(ch);
          if (gw === undefined) { gw = c.measureText(ch).width; widths.set(ch, gw); }
          const sx = gw > adv - 1 ? (adv - 1) / gw : 1;
          if (sx < 1) { c.save(); c.translate(x, y); c.scale(sx, 1); c.fillText(ch, 0, 0); c.restore(); } else c.fillText(ch, x, y);
        }
        x += adv;
      }
    });
    c.restore();
  }
  c.restore();
}
