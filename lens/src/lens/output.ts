// Controller → glasses: the view becomes a page (text boxes), the planet column becomes frames,
// Telescope's picture becomes four dithered tiles (only those whose pixels changed are sent). Frames only flow while something on the lens moves, at
// most ~3 a second, never ahead of text, and they stop after a minute and a half without news: one
// last frame puts everything at rest (the needs-you bracket closed, the sun steady, planets still),
// so a lens left on the glance costs nothing. News is the lens showing something new, from the Mac
// or a gesture. Being asked to redraw isn't: while the Mac is away the hub retries and polls, and
// the same page again must not keep the planets moving. Nor is the clock turning "2m" into "3m".
import { buildPage, layoutKey, ORRERY, type LensPage, type LensView, type OrreryView } from './pages.ts';
import { DRAWN_ORRERY, drawnLensPage, drawView, specimen, type DrawnPage, type TextMode } from './drawn.ts';
import { specimenOf } from './look.ts';
import { loadLookFonts, lookFontsReady, Painter, type FrameCost } from './painter.ts';
import { clockless } from '../core/format.ts';
import { Orrery, animating } from './orrery.ts';
import { ditherScope, tile, type ScopeFrame } from './gray.ts';
import { gapFor, quarters, SCOPE, sideBySide } from '../core/telescope.ts';
import type { LensSender } from './sender.ts';
import { POWER_IMAGE_ID } from './overlay.ts';
import { powerFrame } from './power-art.ts';

const FRAME_MS = 340;
/** The planet column's image slot (pages.ts). */
const ORRERY_ID = 3;
/** Telescope's four image slots (pages.ts). */
const SHOT_IDS = [4, 5, 6, 7];
const STILL_AFTER_MS = 90_000;
/** Work out what each drawn page weighs on its way to the glasses, and log it (development, or `?lookstats`). */
const MEASURE = !!(import.meta as { env?: { DEV?: boolean } }).env?.DEV || /[?&]lookstats\b/.test(globalThis.location?.search ?? '');

export class LensOutput {
  view: LensView = { kind: 'blank' };
  page: LensPage = { boxes: [], images: [] };
  /** Telescope's picture as the lens shows it now, whole or a quarter (the phone mirrors it). */
  shotCanvas: HTMLCanvasElement | null = null;
  readonly orrery: FrameSource;
  private sender: Pick<LensSender, 'show' | 'picture' | 'idle'> & Partial<Pick<LensSender, 'forget'>>;
  private source: () => LensView;
  private frameTimer: ReturnType<typeof setTimeout> | null = null;
  private lastNews = Date.now();
  /** The last planet frame sent was the still one: nothing more to send until there's news. */
  private settled = false;
  private shotKey = '';
  /** The picture as it came, full size: a zoomed quarter is cut from this, never enlarged from the lens' copy. */
  private shotImage: ImageBitmap | HTMLImageElement | null = null;
  /** Which link shotImage was decoded from (shotKey is the one asked for, maybe still loading). */
  private shotImageKey = '';
  /** What shotCanvas shows: the picture, the quarter, crisp or not. */
  private canvasKey = '';
  private tiles = new Map<number, { key: string; png: Uint8Array; hash: number }>();
  /** The last frame drawn, to compare the next picture of the same view with. */
  private frame: (ScopeFrame & { view: string }) | null = null;
  private orreryKey = '';
  /** What the lens said last, without the clock's words, and its planets: news is when this changes. */
  private shownKey = '';
  /** The drawn look's page while the lens shows one (look.ts 'hypernova'); null: native text boxes. */
  drawn: DrawnPage | null = null;
  private painter: Painter | null = null;
  private fontWait: Promise<void> | null = null;
  /** The drawn look's planet column (its rows sit where the drawn look's lines are). */
  private lookOrrery: Orrery | null = null;
  /** What each drawn page cost, newest last (development: the simulator's log, the harness). */
  readonly lookCosts: Array<FrameCost & { at: number; kind: string }> = [];

  constructor(sender: Pick<LensSender, 'show' | 'picture' | 'idle'> & Partial<Pick<LensSender, 'forget'>>, source: () => LensView, orrery: FrameSource = new Orrery()) {
    this.sender = sender;
    this.source = source;
    this.orrery = orrery;
  }

  /** Something may have changed: rebuild the view and send what differs. The planets are redrawn
   * only when what they show changed (a state, the focus, the usage); their motion comes from the
   * frame timer alone, so a stream of events from a busy session never turns into a stream of image
   * writes. It's news only if what the screen says (apart from the clock's words) or what the
   * planets show changed. Read from the view, not the page: on the page a longer time ("10m") also
   * moves the spaces before it and shortens the line it ends. `news: false` for the clock: whatever
   * it changes (a line that has aged off the glance) is no reason to start moving again. */
  invalidate(o: { news?: boolean } = {}): void {
    this.view = this.source();
    this.drawn = this.drawnPage(this.view);
    const page = this.drawn ? drawnLensPage(this.drawn) : buildPage(this.view);
    const relaid = layoutKey(page) !== layoutKey(this.page);
    this.page = page;
    this.sender.show(page);
    const key = 'orrery' in this.view ? orreryKey(this.view.orrery) : '';
    // The time of day and the batteries change by themselves too: their text goes out, but they
    // never set the planets moving again.
    // Telescope's picture link isn't words (and in sample data it's a whole picture as a data: URL).
    // Nor does the speaking wave, which moves with the voice's loudness while it reads.
    const { orrery: _planets, clock: _time, power: _batteries, key: _picture, wave: _wave, ...words } = this.view as LensView & { orrery?: OrreryView; clock?: string; power?: unknown; key?: string };
    const shown = `${clockless(JSON.stringify(words))}|${key}`;
    if (o.news !== false && shown !== this.shownKey) this.lastNews = Date.now();
    this.shownKey = shown;
    this.drawImages(relaid || key !== this.orreryKey);
    this.orreryKey = key;
    this.schedule();
  }

  /**
   * A new picture for Telescope: decoded once and kept full size; the view says which part the lens
   * shows. The same link again (a watched picture that hasn't changed) costs nothing.
   */
  async setShot(url: string): Promise<void> {
    if (url === this.shotKey && this.shotImage) return;
    this.shotKey = url;
    const img = await loadImage(url);
    if (this.shotKey !== url) return;
    this.shotImage = img;
    this.shotImageKey = url;
    this.canvasKey = '';
    this.invalidate();
  }

  /** The lens' copy of the picture for this view (whole, or the quarter it zooms into), dithered once. */
  private scopeCanvas(v: Extract<LensView, { kind: 'telescope' }>): HTMLCanvasElement | null {
    // Only its own picture: until a new one is decoded the glasses keep what they show (watching),
    // or show nothing yet (pointed somewhere new).
    const img = this.shotImage;
    if (!img || v.key !== this.shotImageKey) return null;
    const key = `${v.key}|${v.zoom ?? '-'}|${v.crisp}`;
    if (key !== this.canvasKey || !this.shotCanvas) {
      const crop = v.zoom === null ? null : quarters(img.width, img.height)[v.zoom] ?? null;
      // A new picture of the same view (watching): tiles in which nearly nothing moved stay as sent.
      const view = `${v.zoom ?? '-'}|${v.crisp}|${img.width}x${img.height}`;
      // A phone's whole screen: its two halves side by side, twice as large as the sliver it would be.
      const src = v.zoom === null && sideBySide(img.width, img.height) ? halves(img) : img;
      const frame = ditherScope(src, crop, SCOPE.w, SCOPE.h, v.crisp, this.frame?.view === view ? this.frame : null);
      this.frame = { ...frame, view };
      this.shotCanvas = frame.canvas;
      this.canvasKey = key;
    }
    return this.shotCanvas;
  }

  private still(): boolean { return Date.now() - this.lastNews > STILL_AFTER_MS; }

  /**
   * The view as the drawn look's page, when it's drawn (the look is 'hypernova' and the screen is one
   * it draws; else null, the native page). Until the fonts are loaded the native page stands in.
   */
  private drawnPage(v: LensView): DrawnPage | null {
    const spec = specimenOf();
    if (spec === null && v.look !== 'hypernova') return null;
    if (!lookFontsReady()) {
      // One redraw once they're in, however often the lens asks meanwhile.
      this.fontWait ??= loadLookFonts().then(ok => { this.fontWait = null; if (ok) this.invalidate({ news: false }); });
      return null;
    }
    if (spec !== null) return specimen(spec, (new URLSearchParams(globalThis.location?.search ?? '').get('textmode') ?? 'crisp') as TextMode);
    return drawView(v);
  }

  /** The drawn page's tiles that changed, and on the column plan the planets, as their own image. */
  private drawLook(planets: boolean): void {
    const d = this.drawn!, now = Date.now();
    this.painter ??= new Painter();
    this.lookOrrery ??= new Orrery(DRAWN_ORRERY);
    const { tiles, cost } = this.painter.frame(d, this.lookOrrery, now, MEASURE);
    for (const t of tiles) this.sender.picture(t.slot.id, t.png, t.hash);
    if (d.plan === 'column' && d.orrery && planets) {
      const still = this.still();
      const f = this.lookOrrery.frame(d.orrery, now, still);
      this.settled = still;
      this.sender.picture(ORRERY_ID, f.png, f.hash);
    }
    if (MEASURE && tiles.length) {
      this.lookCosts.push({ ...cost, at: now, kind: this.view.kind });
      if (this.lookCosts.length > 400) this.lookCosts.shift();
      console.info(`[look] ${this.view.kind} tiles=${cost.tiles} png=${cost.png} gray4=${cost.gray4} lz4=${cost.lz4} paint=${cost.paintMs.toFixed(1)}ms encode=${cost.encodeMs.toFixed(1)}ms`);
    }
  }

  /** The drawn look's canvas (lens levels as greys), for the phone's mirror. */
  get lookCanvas(): HTMLCanvasElement | null { return this.drawn ? this.painter?.canvas ?? null : null; }

  private drawImages(planets = true): void {
    if (this.drawn) { this.drawLook(planets); return; }
    const v = this.view;
    if (planets && 'orrery' in v && this.page.images.some(i => i.kind === 'orrery')) {
      const still = this.still();
      const f = this.orrery.frame(v.orrery, Date.now(), still);
      this.settled = still;
      this.sender.picture(3, f.png, f.hash);
    }
    // Telescope's four tiles: the sender skips any whose pixels are what the glasses already have,
    // so a watched picture costs only the parts that changed.
    if (v.kind === 'telescope' && !v.picture) {
      this.sender.forget?.(SHOT_IDS);
      this.frame = null;
    }
    const c = v.kind === 'telescope' && v.picture ? this.scopeCanvas(v) : null;
    if (c) for (const slot of this.page.images) {
      if (!slot.kind.startsWith('shot')) continue;
      // Cut and encoded once per picture and zoom: the view is asked for far more often.
      const k = `${this.canvasKey}#${slot.id}`;
      let t = this.tiles.get(slot.id);
      if (t?.key !== k) this.tiles.set(slot.id, t = { key: k, ...tile(c, slot.x - SCOPE.x, slot.y - SCOPE.y, slot.w, slot.h) });
      this.sender.picture(slot.id, t.png, t.hash);
    }
    // The battery card's icons and bars, drawn again only when a row changes.
    if (v.kind === 'power' && this.page.images.some(i => i.kind === 'power')) {
      const k = JSON.stringify(v.rows);
      if (k !== this.powerKey || !this.powerArt) { this.powerArt = powerFrame(v.rows, this.powerCanvas ??= document.createElement('canvas')); this.powerKey = k; }
      this.sender.picture(POWER_IMAGE_ID, this.powerArt.png, this.powerArt.hash);
    }
  }

  /** The battery card's art as the lens has it (the phone mirrors it). */
  powerCanvas: HTMLCanvasElement | null = null;
  private powerArt: { png: Uint8Array; hash: number } | null = null;
  private powerKey = '';

  private schedule(): void {
    if (this.frameTimer) return;
    const v = this.view;
    if (!('orrery' in v) || !animating(v.orrery, Date.now())) return;
    // A drawn page on the full plan keeps its planets at rest (tiles.ts).
    if (this.drawn && this.drawn.plan !== 'column') return;
    // Past the quiet time only the still frame is left to send, and once it's out, nothing.
    if (this.still() && this.settled) return;
    this.frameTimer = setTimeout(() => {
      this.frameTimer = null;
      // Text first: a frame waits while captions or an answer are on their way.
      if (this.sender.idle && this.drawn) {
        // The drawn look: the same plan draws again (only changed tiles go, and the planets); a
        // different one is a new page.
        this.view = this.source();
        const d = this.drawnPage(this.view);
        if (d?.plan !== this.drawn.plan) { this.invalidate({ news: false }); return; }
        this.drawn = d;
        this.drawImages();
      } else if (this.sender.idle) {
        this.view = this.source();
        const page = buildPage(this.view);
        if (page.boxes.some((b, i) => b.content !== this.page.boxes[i]?.content)) { this.page = page; this.sender.show(page); }
        this.drawImages();
      }
      this.schedule();
    }, FRAME_MS);
  }

  /** The orrery as last drawn, for the phone's mirror. */
  get orreryCanvas(): HTMLCanvasElement { return this.drawn && this.lookOrrery ? this.lookOrrery.canvas : this.orrery.canvas; }
  get orreryRect() { return ORRERY; }
}

/**
 * The clock's redraws, every 20 s on the wall clock's :00, :20 and :40: the time of day on the
 * glance turns with the minute rather than up to 20 s late, and "2m" or "resets 2h" are never more
 * than 20 s behind. Each timeout aims at the next mark, so a late timer (the Even App's often are)
 * doesn't drift. Returns a stop function.
 */
export function clockTicks(fn: () => void, every = 20_000): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const next = () => { timer = setTimeout(() => { fn(); next(); }, every - (Date.now() % every) + 150); };
  next();
  return () => { if (timer) clearTimeout(timer); timer = null; };
}

/** What draws the planet column (the Orrery; a stand-in in tests). `still`: everything at rest. */
export interface FrameSource {
  readonly canvas: HTMLCanvasElement;
  frame(v: OrreryView, now: number, still?: boolean): { png: Uint8Array; hash: number };
}

/** What the planet column shows, without its motion. */
function orreryKey(v: OrreryView): string {
  const p = (x: { state: string; focused?: boolean; blipAt?: number } | null | undefined) => (x ? `${x.state}${x.focused ? '*' : ''}${x.blipAt ?? ''}` : '-');
  const sun = v.sun ? `${Math.round(v.sun.pct)}${v.sun.limit ? '!' : ''}${v.sun.mode ?? ''}${v.sun.flashAt ?? ''}` : '-';
  return `${sun}|${v.planets.map(p).join(',')}|${p(v.seam)}`;
}

/**
 * The screenshot as decoded pixels. fetch + createImageBitmap rather than an <img>: WebKit can hand
 * over an <img> that is "complete" but draws nothing into a canvas (seen in the simulator when the
 * phone shows the same picture). The daemon serves screenshots with CORS (DAEMON.md), which both
 * fetch and reading the canvas back need.
 */
async function loadImage(src: string): Promise<ImageBitmap | HTMLImageElement> {
  try {
    const res = await fetch(src, { mode: src.startsWith('data:') ? 'same-origin' : 'cors', cache: 'force-cache' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await createImageBitmap(await res.blob());
  } catch (err) {
    console.warn('[lens] screenshot via fetch failed, trying an image', (err as Error).message);
    return new Promise((resolve, reject) => {
      const img = new Image();
      if (!src.startsWith('data:')) img.crossOrigin = 'anonymous';
      img.onload = () => { img.decode().then(() => resolve(img), () => resolve(img)); };
      img.onerror = () => reject(new Error('Could not load the screenshot.'));
      img.src = src;
    });
  }
}

/** A tall picture's top half, then (after a dark gap) its bottom half, side by side. */
function halves(img: CanvasImageSource & { width: number; height: number }): HTMLCanvasElement {
  const w = img.width, top = Math.ceil(img.height / 2), gap = gapFor(w);
  const c = document.createElement('canvas');
  c.width = 2 * w + gap; c.height = top;
  const ctx = c.getContext('2d')!;
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, c.width, c.height);
  ctx.drawImage(img, 0, 0, w, top, 0, 0, w, top);
  ctx.drawImage(img, 0, top, w, img.height - top, w + gap, 0, w, img.height - top);
  return c;
}
