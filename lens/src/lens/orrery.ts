// The planet column: Hypernova's living sky, shrunk to 32 px of lens. The sun at the top is the
// accretion disk of the phone's sky (docs/DESIGN.md, "The sun is an accretion disk"): a tilted
// ellipse 2 px tall whose bright span, growing from the centre, is the 5-hour budget left, the white
// dwarf a point at its centre; at the limit it is quiescent (a dim ellipse, the point and one bright
// pixel on its rim, the bright spot), at the weekly limit the low state (the donor's stream stopped,
// the disk drained: the white dwarf a faint point alone), and while usage can't be followed (signed
// out, no plan) an eclipse; when a limit lifts (the week's too), the outburst: the span lighting from
// the rim in over three frames. Nothing pulses; a knot or two circles in only while a session
// works. Each session is a planet lit from the upper left, its meridians turning while Claude
// works, held in a contracting focus bracket when it needs you, ringed when a reply is waiting.
// Same rules as the app's activity meter (DESIGN.md, "Seam"), drawn as perceived brightness and
// quantised for the lens (gray.ts).
import { ORRERY, PLANET_Y, type OrreryView, type PlanetState } from './pages.ts';
import { quantise, pngBytes } from './gray.ts';

const TAU = Math.PI * 2;
const lerp = (a: number, b: number, k: number) => a + (b - a) * k;
const clamp = (x: number, a: number, b: number) => Math.max(a, Math.min(b, x));
const expo = (x: number) => (x >= 1 ? 1 : 1 - 2 ** (-10 * x));

/** The disk's half-width in lens pixels (the sky's rim, 13 dp, and its inner edge, 2.6 dp, in the same ratio). */
export const DISK_RIM = 12;
export const DISK_IN = DISK_RIM * 2.6 / 13;
/** The disk's tilt (rise per pixel of x) and how far its plane is seen from above (SIN_E). */
const TILT = -0.12;
const SIN_E = 0.342;

/**
 * How far the bright span reaches from the centre (px) with `left` (0..1) of the budget left: by
 * area, as the sky's `DiskModel.hotRadius`, since a disk's mass grows with its area (so the last
 * tenth is a thin inner ring, not a tenth of the width). 0 when nothing is left.
 */
export function diskSpan(left: number): number {
  const k = clamp(left, 0, 1);
  return k <= 0 ? 0 : Math.sqrt(DISK_IN * DISK_IN + (DISK_RIM * DISK_RIM - DISK_IN * DISK_IN) * k);
}

/** The outburst, three frames: the part of the span lit from the rim inward (1/3, 2/3, all) `k` (0..1) into it. */
export function outburstFront(k: number): number { return (Math.min(2, Math.floor(clamp(k, 0, 1) * 3)) + 1) / 3; }

/** One turn of a working planet, as in the app's meter. */
const TURN_S = 4;
/** The needs-you bracket contracts from 9 to 7 px every 2.6 s (StarPortrait's rhythm). */
const BRACKET_S = 2.6;
const BLIP_S = 1.4;
/** A limit lifting: the outburst, the span lighting rim-inward over three frames. */
const FLASH_S = 1.4;

const grey = (v: number, a = 1) => `rgba(${Math.round(clamp(v, 0, 1) * 255)},${Math.round(clamp(v, 0, 1) * 255)},${Math.round(clamp(v, 0, 1) * 255)},${a})`;

/** Anything moving at time t (s)? Frames are only sent while it is. */
export function animating(v: OrreryView, now: number): boolean {
  if (v.sun?.flashAt !== undefined && now - v.sun.flashAt < FLASH_S * 1000) return true;
  return [...v.planets, v.seam ?? null].some(p => p && (p.state === 'working' || p.state === 'needs' || (p.blipAt !== undefined && now - p.blipAt < BLIP_S * 1000)));
}

/** Where the column's planets sit: the native pages' rows, or the drawn look's (drawn.ts). */
export interface OrreryGeometry { h: number; planetY(row: number): number }

export class Orrery {
  readonly canvas = document.createElement('canvas');
  private ctx: CanvasRenderingContext2D;
  private still = false;
  private planetY: (row: number) => number;

  constructor(geo: OrreryGeometry = { h: ORRERY.h, planetY: PLANET_Y }) {
    this.canvas.width = ORRERY.w;
    this.canvas.height = geo.h;
    this.planetY = geo.planetY;
    this.ctx = this.canvas.getContext('2d', { willReadFrequently: true })!;
  }

  /** Draw the column at `now` (ms); returns PNG bytes and a hash of the lens levels. `still`: the
   * resting pose (bracket closed, sun steady, meridians at ±45°), the same pixels every time, for
   * when frames stop. */
  frame(v: OrreryView, now: number, still = false): { png: Uint8Array; hash: number } {
    const c = this.ctx, t = now / 1000;
    this.still = still;
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.globalCompositeOperation = 'source-over';
    c.fillStyle = '#000';
    c.fillRect(0, 0, this.canvas.width, this.canvas.height);
    if (v.sun) this.sun(v.sun, t, now, v.planets.some(p => p?.state === 'working') || v.seam?.state === 'working');
    if (v.seam) this.planet(0, { ...v.seam, focused: false }, now, t);
    v.planets.forEach((p, i) => { if (p) this.planet(i + 1, p, now, t); });
    const { hash } = quantise(this.canvas, true);
    return { png: pngBytes(this.canvas), hash };
  }

  // The lens has no faint light: its first level already shows at ~37% (gray.ts). So nothing here
  // glows or fades below that; shapes are solid, and shading lives between ~40% and full.

  private sun(s: NonNullable<OrreryView['sun']>, t: number, now: number, works: boolean): void {
    const c = this.ctx, cx = 16, cy = Math.round(this.planetY(0));
    const mode = s.mode ?? (s.limit ? 'quiet' : 'disk');
    if (mode === 'disk' || mode === 'quiet') {
      const quiet = mode === 'quiet';
      const span = quiet ? 0 : diskSpan(1 - clamp(s.pct / 100, 0, 1));
      // The outburst of a limit lifting: lit from the rim in, over three frames.
      const burst = s.flashAt !== undefined && !this.still && now - s.flashAt < FLASH_S * 1000
        ? outburstFront((now - s.flashAt) / (FLASH_S * 1000)) : 1;
      const full = Math.max(span, burst < 1 ? DISK_RIM : 0);
      for (let i = -DISK_RIM; i <= DISK_RIM; i++) {
        const r = Math.abs(i);
        if (r < Math.ceil(DISK_IN)) continue;
        // The ellipse's tilt steps up a pixel at a time; its ends taper to one pixel.
        const y = cy + Math.round(i * TILT) - (r >= DISK_RIM ? 0 : 1);
        const lit = burst < 1 ? r >= full * (1 - burst) : r <= span;
        // Hot gas is white-gold inside and fainter toward the rim; the spent disk is the lens's first level.
        c.fillStyle = lit ? grey(lerp(1, 0.78, (r - DISK_IN) / (DISK_RIM - DISK_IN))) : grey(0.42);
        c.fillRect(cx + i, y, 1, r >= DISK_RIM ? 1 : 2);
      }
      if (quiet) {
        // The bright spot where the stream strikes the rim, ahead of the donor (the sky's STREAM_LEAD).
        const a = -Math.PI / 2 + 0.9, x = Math.round(DISK_RIM * Math.cos(a)), y = Math.round(DISK_RIM * Math.sin(a) * SIN_E + x * TILT);
        c.fillStyle = grey(1);
        c.fillRect(cx + x, cy + y - 1, 1, 1);
      } else if (works && !this.still && burst === 1 && span > DISK_IN + 2) {
        // A knot or two of hotter gas circling in at Kepler speeds while a session works.
        for (let j = 0; j < 2; j++) {
          const r = lerp(span, DISK_IN + 1.2, (t * 0.05 + j * 0.5) % 1);
          const th = t * TAU / 9 * (DISK_IN / r) ** 1.5 + j * Math.PI;
          const x = Math.round(r * Math.cos(th)), y = Math.round(r * Math.sin(th) * SIN_E + x * TILT);
          c.fillStyle = grey(1);
          c.fillRect(cx + x, cy + y - 1, 1, 1);
        }
      }
      // The white dwarf, a point.
      c.fillStyle = grey(1);
      c.fillRect(cx, cy, 1, 1);
      return;
    }
    if (mode === 'low') {
      // The week spent, the low state: no stream, no disk; the white dwarf a faint point, the lens's first level.
      c.fillStyle = grey(0.45);
      c.fillRect(cx, cy, 1, 1);
      return;
    }
    if (mode === 'eclipse') {
      // A dark disc over the sun: its corona and a thin bright edge.
      c.fillStyle = grey(0.5);
      c.fillRect(cx - 12, Math.round(cy) - 0.5, 24, 1);
      c.fillRect(Math.round(cx) - 0.5, cy - 8, 1, 16);
      c.fillStyle = '#000';
      c.beginPath(); c.arc(cx, cy, 6, 0, TAU); c.fill();
      c.strokeStyle = grey(0.95); c.lineWidth = 1;
      c.beginPath(); c.arc(cx, cy, 5.5, 0, TAU); c.stroke();
      c.fillStyle = '#000';
      c.beginPath(); c.arc(cx + 0.8, cy + 0.4, 5, 0, TAU); c.fill();
      return;
    }
  }

  private planet(row: number, p: { state: PlanetState; focused: boolean; blipAt?: number }, now: number, t: number): void {
    const c = this.ctx, cx = 18, cy = this.planetY(row), r = 5.5;
    if (p.state === 'quiet') return;
    // How lit the sphere is: full while it works or needs you, stepped back when idle.
    const lit = p.state === 'idle' ? 0.72 : p.state === 'error' ? 0.66 : p.state === 'needs' && !this.still ? lerp(0.86, 1, 0.5 + 0.5 * Math.sin(t * TAU / 1.4)) : 1;
    // A sphere lit from the upper left.
    const body = c.createRadialGradient(cx - r * 0.4, cy - r * 0.4, 0, cx, cy, r);
    body.addColorStop(0, grey(lit)); body.addColorStop(0.55, grey(lit * 0.72)); body.addColorStop(1, grey(Math.max(0.42, lit * 0.5)));
    c.fillStyle = body; c.beginPath(); c.arc(cx, cy, r, 0, TAU); c.fill();
    // Meridians slide across the face while it works; at rest they sit evenly (±45°), like the logo.
    const phase = p.state === 'working' && !this.still ? (t / TURN_S) * TAU : Math.PI / 4;
    c.save();
    c.beginPath(); c.arc(cx, cy, r - 0.6, 0, TAU); c.clip();
    c.strokeStyle = '#000'; c.lineWidth = 1;
    for (let k = 0; k < 2; k++) {
      const th = phase + k * Math.PI / 2;
      // Each meridian is drawn on the near side only, as a curve whose bulge follows its angle.
      const front = Math.cos(th);
      const off = Math.sin(th) * r;
      if (front <= 0.05) continue;
      c.beginPath();
      for (let dy = -r; dy <= r; dy += 0.5) {
        const x = cx + off * Math.sqrt(Math.max(0, 1 - (dy / r) ** 2)), y = cy + dy;
        if (dy === -r) c.moveTo(x, y); else c.lineTo(x, y);
      }
      c.stroke();
    }
    c.restore();

    if (p.state === 'needs') {
      // The contracting focus bracket.
      const k = this.still ? 1 : expo(((t % BRACKET_S) / BRACKET_S) * 1.6);
      const s = Math.round(lerp(10, 8, k)), arm = 3;
      c.fillStyle = grey(1);
      for (const [sx, sy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]] as const) {
        const x = cx + sx * s, y = Math.round(cy) + sy * s;
        c.fillRect(Math.min(x, x - sx * arm), y, arm + 1, 1);
        c.fillRect(x, Math.min(y, y - sy * arm), 1, arm + 1);
      }
    }
    if (p.state === 'done') {
      // A reply waits: a thin ring, like a moon's orbit.
      c.strokeStyle = grey(0.55); c.lineWidth = 1;
      c.beginPath(); c.ellipse(cx, cy, r + 4, r + 2.2, -0.35, 0, TAU); c.stroke();
    }
    if (p.state === 'listening') {
      c.strokeStyle = grey(0.8); c.lineWidth = 1;
      for (const rr of [r + 3.5, r + 7]) { c.beginPath(); c.arc(cx, cy, rr, -0.75, 0.75); c.stroke(); }
    }
    if (p.state === 'error') {
      c.strokeStyle = '#000'; c.lineWidth = 1.4;
      c.beginPath(); c.moveTo(cx - 3, cy + 3); c.lineTo(cx + 3, cy - 3); c.stroke();
    }
    if (p.blipAt !== undefined && now - p.blipAt < BLIP_S * 1000 && !this.still) {
      // Finished: one ring goes out and fades (in steps the lens can show).
      const k = (now - p.blipAt) / (BLIP_S * 1000);
      c.strokeStyle = grey(lerp(1, 0.45, k)); c.lineWidth = 1;
      c.beginPath(); c.arc(cx, cy, r + 2 + 8 * expo(k), 0, TAU); c.stroke();
    }
    if (p.focused) {
      // A star-chart tick: this is the one you're pointing at.
      c.fillStyle = grey(0.9);
      c.fillRect(1, Math.round(cy), 4, 1);
    }
  }
}

