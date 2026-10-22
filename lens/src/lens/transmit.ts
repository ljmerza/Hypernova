// Transmission on the lens: each piece of text that arrives like speech (a turn's words in the live
// view, an agent's report, a reply on its card, a reply read aloud) is a stream that shows a
// little more at each step (core/transmission.ts). The view asks how much of each shows; the
// controller redraws when the next step is due. A tap shows the rest at once. With "All at once"
// every stream shows whole.
import { advance, commonPrefix, syncEnd, type Atom, type Stream } from '../core/transmission.ts';

/** Streams not asked for in this long are forgotten (the bound on memory, a long session's many messages). */
const FORGET_MS = 15 * 60_000;
const MAX_STREAMS = 300;

export class Transmission {
  private streams = new Map<string, Stream>();
  /** The streams the view on the lens used (asked for since the last begin()). */
  private touched = new Set<string>();
  private now: () => number;
  private opts: () => { on: boolean; wpm: number };

  constructor(now: () => number, opts: () => { on: boolean; wpm: number }) {
    this.now = now;
    this.opts = opts;
  }

  get on(): boolean { return this.opts().on; }

  /** A new view is being built: what it asks for is what's on the lens. */
  begin(): void { this.touched.clear(); }

  /**
   * How much of `text` shows under `key`. `fresh`: new to the lens, so it arrives from its start;
   * otherwise what's there now shows at once (it was there before you looked) and only what's added
   * later arrives like speech. `carry`: the stream this text was shown under until now (a reply
   * on its card before its turn loaded), which a new stream picks up from, as far as their texts
   * agree, rather than starting over.
   */
  shown(key: string, text: string, fresh: boolean, atoms: Atom[] = [], carry?: string): string {
    if (!this.on) return text;
    const now = this.now();
    this.touched.add(key);
    let s = this.streams.get(key);
    if (!s) {
      const from = carry ? this.streams.get(carry) : undefined;
      s = from
        ? { text, atoms, shown: Math.min(from.shown, commonPrefix(from.text, text)), nextAt: from.nextAt, seenAt: now }
        : { text, atoms, shown: fresh ? 0 : text.length, nextAt: now, seenAt: now };
      this.forget(now);
    }
    s = advance(s, text, atoms, now, this.opts().wpm);
    this.streams.set(key, s);
    return text.slice(0, s.shown);
  }

  /** Read aloud: the text shows as far as the voice has got (`fraction` of it), never less than shown. */
  synced(key: string, text: string, fraction: number, atoms: Atom[] = []): string {
    if (!this.on) return text;
    const now = this.now();
    this.touched.add(key);
    const prev = this.streams.get(key);
    const shown = Math.max(prev && text.startsWith(prev.text.slice(0, prev.shown)) ? prev.shown : 0, syncEnd(text, atoms, fraction));
    this.streams.set(key, { text, atoms, shown, nextAt: now, seenAt: now });
    return text.slice(0, shown);
  }

  /** Some text on the lens is still arriving. */
  behind(): boolean {
    if (!this.on) return false;
    for (const k of this.touched) { const s = this.streams.get(k); if (s && s.shown < s.text.length) return true; }
    return false;
  }

  /** A tap: everything on the lens shows now. */
  catchUp(): void {
    const now = this.now();
    for (const k of this.touched) { const s = this.streams.get(k); if (s) this.streams.set(k, { ...s, shown: s.text.length, nextAt: now }); }
  }

  /** Done with `key` for good: shown whole from now on (a reply read, a card seen through). */
  finish(key: string): void {
    const s = this.streams.get(key);
    if (s) this.streams.set(key, { ...s, shown: s.text.length });
  }

  /** When the lens next needs a redraw for text arriving (null: nothing on it is arriving). */
  nextDue(): number | null {
    if (!this.on) return null;
    let due: number | null = null;
    for (const k of this.touched) {
      const s = this.streams.get(k);
      if (s && s.shown < s.text.length) due = due === null ? s.nextAt : Math.min(due, s.nextAt);
    }
    return due;
  }

  private forget(now: number): void {
    for (const [k, s] of this.streams) if (now - s.seenAt > FORGET_MS && !this.touched.has(k)) this.streams.delete(k);
    while (this.streams.size >= MAX_STREAMS) this.streams.delete(this.streams.keys().next().value!);
  }
}
