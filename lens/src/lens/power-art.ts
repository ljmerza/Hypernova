// The battery card's drawn part: for each device a small icon (the glasses, a laptop) and a bar of
// five segments, lit one per started fifth, with a bolt after it while it charges. Drawn as
// perceived brightness and quantised crisp for the lens (gray.ts), like the planet column.
import type { PowerRow } from '../core/power.ts';
import { POWER_ART } from './overlay.ts';
import { pngBytes, quantise } from './gray.ts';

const grey = (v: number) => { const c = Math.round(Math.max(0, Math.min(1, v)) * 255); return `rgb(${c},${c},${c})`; };

const SEG = { x: 44, w: 11, h: 16, gap: 3 } as const;

/** The art for `rows` (the same rows give the same pixels and hash). */
export function powerFrame(rows: PowerRow[], canvas: HTMLCanvasElement = document.createElement('canvas')): { canvas: HTMLCanvasElement; png: Uint8Array; hash: number } {
  canvas.width = POWER_ART.w;
  canvas.height = POWER_ART.h;
  const c = canvas.getContext('2d', { willReadFrequently: true })!;
  c.setTransform(1, 0, 0, 1, 0, 0);
  c.fillStyle = '#000';
  c.fillRect(0, 0, POWER_ART.w, POWER_ART.h);
  rows.slice(0, 2).forEach((r, i) => {
    const top = i * POWER_ART.row, mid = top + 18;
    c.strokeStyle = grey(0.95);
    c.fillStyle = grey(0.95);
    c.lineWidth = 1.5;
    if (r.device === 'glasses') glasses(c, 2, mid); else laptop(c, 2, mid);
    for (let k = 0; k < 5; k++) {
      const x = SEG.x + k * (SEG.w + SEG.gap), y = mid - SEG.h / 2;
      if (k < r.segments) { c.fillStyle = grey(r.low ? 1 : 0.92); c.fillRect(x, y, SEG.w, SEG.h); }
      else { c.strokeStyle = grey(0.5); c.lineWidth = 1; c.strokeRect(x + 0.5, y + 0.5, SEG.w - 1, SEG.h - 1); }
    }
    if (r.charging) bolt(c, SEG.x + 5 * (SEG.w + SEG.gap) + 2, mid);
  });
  const { hash } = quantise(canvas, true);
  return { canvas, png: pngBytes(canvas), hash };
}

/** Two lenses on a bridge, with the temples going back. */
function glasses(c: CanvasRenderingContext2D, x: number, mid: number): void {
  const lens = (lx: number) => { c.beginPath(); c.roundRect(lx + 0.5, mid - 5.5, 13, 11, 4); c.stroke(); };
  lens(x + 2);
  lens(x + 19);
  c.beginPath(); c.moveTo(x + 15.5, mid - 2); c.quadraticCurveTo(x + 17, mid - 4.5, x + 18.5, mid - 2); c.stroke();
  c.beginPath(); c.moveTo(x + 2.5, mid - 3); c.lineTo(x, mid - 5); c.moveTo(x + 32.5, mid - 3); c.lineTo(x + 35, mid - 5); c.stroke();
}

/** A laptop: the screen's outline (dark inside: a lit block would glare on the lens), on a base wider than it. */
function laptop(c: CanvasRenderingContext2D, x: number, mid: number): void {
  c.beginPath(); c.roundRect(x + 4.5, mid - 10.5, 26, 17, 2); c.stroke();
  c.fillStyle = grey(0.95);
  c.beginPath(); c.moveTo(x, mid + 8); c.lineTo(x + 35, mid + 8); c.lineTo(x + 33, mid + 11); c.lineTo(x + 2, mid + 11); c.closePath(); c.fill();
}

/** Charging. */
function bolt(c: CanvasRenderingContext2D, x: number, mid: number): void {
  c.fillStyle = grey(1);
  c.beginPath();
  c.moveTo(x + 6, mid - 9); c.lineTo(x, mid + 1); c.lineTo(x + 4, mid + 1); c.lineTo(x + 2, mid + 9); c.lineTo(x + 9, mid - 2); c.lineTo(x + 5, mid - 2);
  c.closePath(); c.fill();
}
