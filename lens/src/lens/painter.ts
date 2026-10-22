// The drawn look on the phone: a page description (drawn.ts) painted on a 576 x 288 canvas in the
// app's own type, quantised to the lens' 16 levels (gray.ts: perceived brightness through the
// lens' curve; text edges kept crisp, not dithered), then cut into the plan's tiles (tiles.ts). Only
// tiles whose levels changed are encoded and handed on; the rest cost nothing. The fonts are the
// phone page's own files (phone/fonts), loaded once before the first page is drawn.
import { FAMILY, advance, cssFont, measure } from '../core/type.ts';
import { drawnLevel, pngBytes, quantise } from './gray.ts';
import { changedTiles, PLANS, tileCost } from './tiles.ts';
import type { DrawnPage, Icon, Op } from './drawn.ts';
import type { ImageSlot } from './pages.ts';

let loading: Promise<boolean> | null = null;
let ready = false;

/** Load Manrope and JetBrains Mono for the canvas (once); true when they're there. */
export function loadLookFonts(): Promise<boolean> {
  return (loading ??= (async () => {
    try {
      // Vite turns these into the bundled files' addresses (the phone page's own fonts).
      const sans = new URL('../phone/fonts/Manrope.ttf', import.meta.url).href;
      const mono = new URL('../phone/fonts/JetBrainsMono.ttf', import.meta.url).href;
      const faces = [new FontFace(FAMILY.sans, `url(${sans})`, { weight: '200 800' }), new FontFace(FAMILY.mono, `url(${mono})`, { weight: '100 800' })];
      await Promise.all(faces.map(f => f.load()));
      for (const f of faces) document.fonts.add(f);
      ready = true;
      return true;
    } catch (err) {
      console.warn('[look] fonts', (err as Error).message);
      loading = null;
      return false;
    }
  })());
}

export const lookFontsReady = () => ready;

const grey = (v: number) => { const c = Math.round(Math.max(0, Math.min(1, v)) * 255); return `rgb(${c},${c},${c})`; };

/** What one page cost to make and send (painter.frame's measure). */
export interface FrameCost {
  /** Painting, quantising and hashing the tiles, ms. */
  paintMs: number;
  /** Encoding the changed tiles as PNG, ms. */
  encodeMs: number;
  tiles: number;
  /** Bytes handed to the Even App (PNG), as 4-bit greys, and those as LZ4 (what the radio carries, about). */
  png: number;
  gray4: number;
  lz4: number;
}

export interface LookTile { slot: ImageSlot; png: Uint8Array; hash: number }

export interface PlanetColumn {
  readonly canvas: HTMLCanvasElement;
  frame(v: NonNullable<DrawnPage['orrery']>, now: number, still?: boolean): { png: Uint8Array; hash: number };
}

export class Painter {
  /** The lens as it is drawn now, in lens levels as greys (the phone mirrors it). */
  readonly canvas = document.createElement('canvas');
  private ctx: CanvasRenderingContext2D;
  /** Where text and glyphs are, at the brightness they were drawn at (painted alongside: drawnLevel). */
  private tones = document.createElement('canvas');
  private tctx: CanvasRenderingContext2D;
  /** Each tile's levels as last handed on, by slot id: unchanged tiles aren't encoded again. */
  private sent = new Map<number, number>();
  private plan = '';

  constructor() {
    this.canvas.width = this.tones.width = 576;
    this.canvas.height = this.tones.height = 288;
    this.ctx = this.canvas.getContext('2d', { willReadFrequently: true })!;
    this.tctx = this.tones.getContext('2d', { willReadFrequently: true })!;
  }

  /**
   * Draw `page` and return the tiles that changed. `planets`: the column, drawn at rest into the
   * page on the full plan (on the column plan it's an image of its own, output.ts). `cost`: also work
   * out the bytes each tile weighs as 4-bit greys and as LZ4 (development).
   */
  frame(page: DrawnPage, planets: PlanetColumn | null, now: number, cost = false): { tiles: LookTile[]; cost: FrameCost } {
    const t0 = performance.now();
    const c = this.ctx;
    if (page.plan !== this.plan) { this.sent.clear(); this.plan = page.plan; }
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.globalCompositeOperation = 'source-over';
    c.fillStyle = '#000';
    c.fillRect(0, 0, 576, 288);
    this.tctx.globalCompositeOperation = 'source-over';
    this.tctx.fillStyle = '#000';
    this.tctx.fillRect(0, 0, 576, 288);
    this.tctx.globalCompositeOperation = 'lighten';
    if ('fontKerning' in c) c.fontKerning = 'none';
    c.textBaseline = 'alphabetic';
    c.textAlign = 'left';
    for (const op of page.ops) this.draw(op);
    const mode = page.mode ?? 'crisp';
    const levels = mode === 'hard' ? this.quantiseDrawn() : quantise(this.canvas, mode === 'dither' ? false : true).levels;
    if (page.plan === 'full' && page.orrery && planets) {
      // At rest: on this plan a moving planet would send a whole tile again.
      planets.frame(page.orrery, now, true);
      const o = planets.canvas;
      c.drawImage(o, 0, 0);
      const d = o.getContext('2d', { willReadFrequently: true })!.getImageData(0, 0, o.width, o.height).data;
      for (let y = 0; y < o.height; y++) for (let x = 0; x < o.width; x++) levels[y * 576 + x] = Math.round(d[(y * o.width + x) * 4] / 17);
    }
    const changed = changedTiles(PLANS[page.plan], levels, 576, this.sent);
    const t1 = performance.now();
    const out: LookTile[] = [];
    const sum: FrameCost = { paintMs: t1 - t0, encodeMs: 0, tiles: changed.length, png: 0, gray4: 0, lz4: 0 };
    for (const { slot, hash } of changed) {
      const t = document.createElement('canvas');
      t.width = slot.w; t.height = slot.h;
      t.getContext('2d')!.drawImage(this.canvas, slot.x, slot.y, slot.w, slot.h, 0, 0, slot.w, slot.h);
      const png = pngBytes(t);
      out.push({ slot, png, hash });
      sum.png += png.length;
      if (cost) { const k = tileCost(levels, 576, slot); sum.gray4 += k.gray4; sum.lz4 += k.lz4; }
    }
    sum.encodeMs = performance.now() - t1;
    return { tiles: out, cost: sum };
  }

  /** The glasses lost their pictures (a new page): every tile goes again. */
  reset(): void { this.sent.clear(); }

  /** The canvas to lens levels in place, each pixel knowing the tone of the text drawn there (drawnLevel). */
  private quantiseDrawn(): Uint8Array {
    const img = this.ctx.getImageData(0, 0, 576, 288), d = img.data;
    const t = this.tctx.getImageData(0, 0, 576, 288).data;
    const levels = new Uint8Array(576 * 288);
    for (let i = 0; i < levels.length; i++) {
      const l = drawnLevel(d[i * 4 + 1], t[i * 4 + 1]);
      levels[i] = l;
      d[i * 4] = d[i * 4 + 1] = d[i * 4 + 2] = l * 17; d[i * 4 + 3] = 255;
    }
    this.ctx.putImageData(img, 0, 0);
    return levels;
  }

  /** Mark where text or a glyph is, at its tone (the 'lighten' blend keeps the brightest where they meet). */
  private tone(x: number, y: number, w: number, h: number, tone: number): void {
    this.tctx.fillStyle = grey(tone);
    this.tctx.fillRect(Math.floor(x) - 1, Math.floor(y) - 1, Math.ceil(w) + 2, Math.ceil(h) + 2);
  }

  private draw(op: Op): void {
    const c = this.ctx;
    if (op.op === 'icon') this.tone(op.x, op.y, op.size, op.size, op.tone);
    if (op.op === 'text') {
      const w = measure(op.s, op.face), size = op.face.size;
      this.tone(op.align === 'right' ? op.x - w : op.x, op.y - size, w, size * 1.35, op.tone);
    }
    switch (op.op) {
      case 'rect':
        c.fillStyle = grey(op.tone);
        c.fillRect(op.x, op.y, op.w, op.h);
        return;
      case 'frame':
        c.beginPath();
        c.roundRect(op.x + 0.5, op.y + 0.5, op.w - 1, op.h - 1, op.r);
        if (op.fill !== undefined) { c.fillStyle = grey(op.fill); c.fill(); }
        if (op.tone > 0) { c.strokeStyle = grey(op.tone); c.lineWidth = 1; c.stroke(); }
        return;
      case 'icon':
        icon(c, op.icon, op.x, op.y, op.size, op.tone);
        return;
      case 'text': {
        c.font = cssFont(op.face);
        c.fillStyle = grey(op.tone);
        let x = op.align === 'right' ? op.x - measure(op.s, op.face) + (op.face.track ?? 0) : op.x;
        if (!op.face.track) { c.fillText(op.s, Math.round(x), op.y); return; }
        // Tracked caps: a character at a time (canvas letter spacing isn't everywhere yet).
        x = Math.round(x);
        for (const ch of op.s) { c.fillText(ch, x, op.y); x += advance(ch, op.face); }
      }
    }
  }
}

/**
 * The hairline glyphs in a `size` px box at (x, y): the gestures as the guide draws them (tap one
 * ring, double tap two dots, swipe a double arrow, hold a dot in two rings, say a voice's bars), and
 * a few marks. Lines sit on pixel centres so they stay one pixel wide.
 */
function icon(c: CanvasRenderingContext2D, name: Icon, x: number, y: number, size: number, tone: number): void {
  const s = size, cx = x + s / 2, cy = y + s / 2, px = (v: number) => Math.round(v) + 0.5;
  c.strokeStyle = grey(tone);
  c.fillStyle = grey(tone);
  c.lineWidth = 1;
  c.lineCap = 'round';
  c.lineJoin = 'round';
  const ring = (r: number) => { c.beginPath(); c.arc(cx, cy, r, 0, Math.PI * 2); c.stroke(); };
  const dot = (dx: number, dy: number, r: number) => { c.beginPath(); c.arc(cx + dx, cy + dy, r, 0, Math.PI * 2); c.fill(); };
  const path = (pts: Array<[number, number]>) => { c.beginPath(); pts.forEach(([a, b], i) => (i ? c.lineTo(px(a), px(b)) : c.moveTo(px(a), px(b)))); c.stroke(); };
  switch (name) {
    case 'tap': ring(s * 0.36); return;
    case 'double': dot(-s * 0.22, 0, Math.max(1.3, s * 0.15)); dot(s * 0.22, 0, Math.max(1.3, s * 0.15)); return;
    case 'hold': dot(0, 0, Math.max(1, s * 0.1)); ring(s * 0.25); ring(s * 0.45); return;
    case 'swipe': {
      const a = s * 0.2;
      path([[cx, y + 1], [cx, y + s - 1]]);
      path([[cx - a, y + 1 + a], [cx, y + 1], [cx + a, y + 1 + a]]);
      path([[cx - a, y + s - 1 - a], [cx, y + s - 1], [cx + a, y + s - 1 - a]]);
      return;
    }
    case 'say': {
      const hs = [0.35, 0.75, 0.5];
      hs.forEach((h, i) => { const bx = Math.round(cx + (i - 1) * s * 0.28); c.fillRect(bx, Math.round(cy - (s * h) / 2), 1, Math.max(2, Math.round(s * h))); });
      return;
    }
    case 'plus': path([[cx, y + s * 0.15], [cx, y + s * 0.85]]); path([[x + s * 0.15, cy], [x + s * 0.85, cy]]); return;
    case 'down': path([[x + s * 0.2, cy - s * 0.15], [cx, cy + s * 0.15], [x + s * 0.8, cy - s * 0.15]]); return;
    case 'up': path([[x + s * 0.2, cy + s * 0.15], [cx, cy - s * 0.15], [x + s * 0.8, cy + s * 0.15]]); return;
    case 'warn':
      path([[cx, y + 1], [x + s - 1, y + s - 1], [x + 1, y + s - 1], [cx, y + 1]]);
      c.fillRect(Math.round(cx), Math.round(y + s * 0.4), 1, Math.max(1, Math.round(s * 0.28)));
      c.fillRect(Math.round(cx), Math.round(y + s * 0.78), 1, 1);
      return;
    case 'dot': dot(0, 0, Math.max(1.2, s * 0.14)); return;
    case 'ring': ring(s * 0.3); return;
    case 'reset': {
      // A circle turning back on itself (clockwise), open at the top right, its arrowhead at the top.
      const r = s * 0.36, a0 = -Math.PI * 0.22, a1 = Math.PI * 1.45;
      c.beginPath(); c.arc(cx, cy, r, a0, a1); c.stroke();
      const ex = cx + r * Math.cos(a1), ey = cy + r * Math.sin(a1);
      const dx = -Math.sin(a1), dy = Math.cos(a1), nx = Math.cos(a1), ny = Math.sin(a1), k = Math.max(2, s * 0.22);
      c.beginPath(); c.moveTo(ex + dx * k * 1.3, ey + dy * k * 1.3); c.lineTo(ex + nx * k, ey + ny * k); c.lineTo(ex - nx * k, ey - ny * k); c.closePath(); c.fill();
      return;
    }
  }
}
