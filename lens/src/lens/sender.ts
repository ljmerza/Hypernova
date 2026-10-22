// Phone -> glasses. Whatever the controller wants on the lens arrives here as a whole page; this
// sends the cheapest change that gets the glasses there: new text in one or two boxes is a text
// update each (no flicker), anything that moves or resizes a box rebuilds the page in one go, and
// image columns follow only when no text is waiting (captions and approvals never queue behind a
// turning planet). One write at a time; wishes that arrive meanwhile collapse into the newest.
// After Hypertasks' native lens (src/lens/native-lens.ts there).
import { boxChanged, layoutKey, samePage, type ImageSlot, type LensPage, type NativeBox } from './pages.ts';

export interface LensSink {
  readonly name: string;
  rebuild(page: LensPage): Promise<boolean>;
  upgrade(box: NativeBox): Promise<boolean>;
  image(slot: ImageSlot, png: Uint8Array): Promise<boolean>;
}

/** Past this many changed boxes one rebuild is cheaper than separate text updates. */
const MAX_UPGRADES = 2;
/** Development (`&sendlog`): each write to the glasses in the console, what it carried and how long it took. */
const LOG = !!(import.meta as { env?: { DEV?: boolean } }).env?.DEV && /[?&]sendlog\b/.test(globalThis.location?.search ?? '');
const utf8 = (s: string) => new TextEncoder().encode(s).length;

const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms));
/** Lets changes made in the same moment go out as one write. Not setTimeout: the Even App runs
 * page timers on its own, sometimes late, clock. */
const yieldTask = () => new Promise<void>(r => { const ch = new MessageChannel(); ch.port1.onmessage = () => { ch.port1.close(); r(); }; ch.port2.postMessage(0); });

interface Picture { png: Uint8Array; hash: number }

export class LensSender {
  /** What the glasses show right now. */
  shown: LensPage | null = null;
  stats = { rebuilds: 0, upgrades: 0, images: 0, failures: 0, ms: 0 };
  /** After every successful write (the phone's mirror follows the glasses). */
  onShown: () => void = () => {};
  private sink: LensSink;
  private want: LensPage | null = null;
  private pictures = new Map<number, Picture>();
  private sentPictures = new Map<number, number>();
  private running = false;
  private paused = true;
  private failures = 0;

  constructor(sink: LensSink) { this.sink = sink; }

  get sinkName(): string { return this.sink.name; }
  get latest(): LensPage | null { return this.want; }
  /** Nothing waiting to go out: a good moment for the next animation frame. */
  get idle(): boolean { return !this.running || (!!this.want && samePage(this.want, this.shown) && this.picturesSent()); }

  setSink(sink: LensSink): void { this.sink = sink; this.reset(); }

  show(page: LensPage): void { this.want = page; this.kick(); }

  /** New pixels for an image slot (by id). Unchanged pixels cost nothing. */
  picture(id: number, png: Uint8Array, hash: number): void {
    const cur = this.pictures.get(id);
    if (cur?.hash === hash) return;
    this.pictures.set(id, { png, hash });
    this.kick();
  }

  /**
   * Pictures for these slots are out of date (Telescope pointed somewhere new): drop them, so a
   * rebuilt page doesn't show the old ones while the new are made.
   */
  forget(ids: number[]): void { for (const id of ids) this.pictures.delete(id); }

  /** The glasses lost the page (app resumed, a dialogue was shown): send it whole. */
  reset(): void { this.shown = null; this.sentPictures.clear(); this.kick(); }

  pause(): void { this.paused = true; }
  resume(): void { this.paused = false; this.reset(); }

  private picturesSent(): boolean {
    for (const slot of this.shown?.images ?? []) {
      const p = this.pictures.get(slot.id);
      if (p && this.sentPictures.get(slot.id) !== p.hash) return false;
    }
    return true;
  }

  private kick(): void {
    if (this.running || this.paused) return;
    this.running = true;
    this.loop().catch(err => console.warn('[lens] sender failed', err)).finally(() => {
      this.running = false;
      if (!this.paused && this.want && (!samePage(this.want, this.shown) || !this.picturesSent())) this.kick();
    });
  }

  private async loop(): Promise<void> {
    await yieldTask();
    while (!this.paused && this.want) {
      const target = this.want, cur = this.shown, t0 = performance.now();
      let ok = true, wrote = false;
      if (!samePage(target, cur)) {
        const changed = cur && layoutKey(cur) === layoutKey(target) ? target.boxes.filter((b, i) => boxChanged(b, cur.boxes[i])) : null;
        if (!changed || changed.length > MAX_UPGRADES) {
          ok = await this.sink.rebuild(target).catch(() => false);
          if (ok) { this.shown = target; this.sentPictures.clear(); this.stats.rebuilds++; }
          if (LOG) console.info(`[send] rebuild boxes=${target.boxes.length} images=${target.images.length} text=${target.boxes.reduce((n, b) => n + utf8(b.content), 0)}B ${(performance.now() - t0).toFixed(0)}ms`);
        } else {
          // One box at a time, then look at the newest wish again.
          const b = changed[0];
          ok = await this.sink.upgrade(b).catch(() => false);
          if (LOG) console.info(`[send] text ${b.name} ${utf8(b.content)}B ${(performance.now() - t0).toFixed(0)}ms`);
          if (ok && this.shown === cur) { this.shown = { ...cur!, boxes: cur!.boxes.map(x => (x.id === b.id ? { ...x, content: b.content, textColor: b.textColor } : x)) }; this.stats.upgrades++; }
        }
        wrote = true;
      } else {
        const slot = (cur?.images ?? []).find(s => { const p = this.pictures.get(s.id); return p && this.sentPictures.get(s.id) !== p.hash; });
        if (!slot) break;
        const p = this.pictures.get(slot.id)!;
        ok = await this.sink.image(slot, p.png).catch(() => false);
        if (LOG) console.info(`[send] image ${slot.name} ${p.png.length}B ${(performance.now() - t0).toFixed(0)}ms`);
        if (ok) { this.sentPictures.set(slot.id, p.hash); this.stats.images++; }
        wrote = true;
      }
      this.stats.ms = performance.now() - t0;
      if (ok) { this.failures = 0; if (wrote) this.onShown(); continue; }
      this.failures++;
      this.stats.failures++;
      this.shown = null;
      this.sentPictures.clear();
      await sleep(Math.min(5000, 300 * this.failures));
    }
  }
}

/** Stand-in for the glasses in a plain browser, timed like the hardware (rebuild ~165 ms, text ~83 ms). */
export class PreviewSink implements LensSink {
  readonly name = 'Preview (no glasses)';
  async rebuild(): Promise<boolean> { await sleep(165); return true; }
  async upgrade(): Promise<boolean> { await sleep(83); return true; }
  async image(): Promise<boolean> { await sleep(60); return true; }
}
