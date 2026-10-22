// Speech to text, on the Mac: the glasses' microphone (16 kHz s16le PCM through the Even App)
// streams to the daemon, which relays it to Soniox (data/stt.ts) and sends captions back.
// While the socket is still opening, audio is held here and sent the moment the Mac says
// "started", so the first word of a quick "hold and talk" isn't lost.
import { startMacStream, joinText, type MacEndpoint, type MacStreamOptions, type SttClient } from './mac-stream.ts';

export class SpeechError extends Error {
  readonly reason: 'no_mac_speech' | 'mic' | 'unreachable' | 'lost';
  constructor(reason: SpeechError['reason'], message: string) {
    super(message);
    this.name = 'SpeechError';
    this.reason = reason;
  }
}

export type Caption = (finalText: string, interimText: string) => void;

/**
 * What a hold heard, once its last words are in. `complete` is false when they never came back
 * within FINAL_WAIT_MS (or the link failed on the way): `text` is then what had been heard before,
 * and the lens says the end may be missing rather than sending it by itself.
 */
export interface Heard { text: string; complete: boolean }

export interface SpeechEngine {
  readonly kind: 'demo' | 'mac';
  readonly listening: boolean;
  /** Start listening; captions arrive until stop() or cancel(). Rejects with a SpeechError. */
  start(onCaption: Caption): Promise<void>;
  /** Stop listening; resolves with everything that was said, its last words included. Captions may still come meanwhile. */
  stop(): Promise<Heard>;
  cancel(): void;
}

/** The glasses' microphone, as the Even App hands it out. */
export interface MicSource {
  start(): Promise<boolean>;
  stop(): Promise<void>;
  onPcm(fn: ((pcm: Uint8Array) => void) | null): void;
}

/** 30 s of 16 kHz 16-bit audio held while the stream opens. */
const MAX_HELD = 16_000 * 2 * 30;

/**
 * After the temple is let go the microphone stays on this long. The last word is often still being
 * said as the finger lifts, and the glasses' audio reaches the phone a little after it was spoken
 * (Bluetooth, then the Even App): switched off at once, those last frames were dropped and the
 * prompt lost its last word.
 */
export const RELEASE_TAIL_MS = 400;

/**
 * Then the end of the audio goes to the Mac, which tells Soniox; Soniox works out the words it
 * hadn't settled yet and sends them, usually well under a second later. Waited for this long at most:
 * past it, what was heard before is kept and the lens says the end may be missing.
 */
export const FINAL_WAIT_MS = 2500;

const pause = (ms: number) => new Promise<void>(r => setTimeout(r, ms));

/** How to stream speech to the Mac: where, how, and what to say when it can't be opened. */
export interface SpeechStream {
  readonly kind: 'mac';
  /**
   * Where to stream now, and the socket to open it with (through the Hypernova app on the phone,
   * one that seals every message: data/seal.ts); throws a SpeechError when there's nowhere yet
   * (nothing paired).
   */
  open(): Promise<{ ep: MacEndpoint; socket?: typeof WebSocket }>;
  /**
   * Done before each stream opens, when there is one: through the Hypernova app on the phone, its
   * listener proves itself (data/bridge.ts), so the glasses' audio never goes to anything else on
   * its port. Throws a SpeechError when it can't.
   */
  ready?(): Promise<void>;
  options(language: string): MacStreamOptions;
  /** The words for a stream that didn't open. */
  words(err: unknown): string;
}

/**
 * A hold through the Mac's speech stream: held audio, captions as words settle, and everything said
 * on release; a generation number keeps a cancelled hold's late answers out of the next one.
 */
export class StreamSpeech implements SpeechEngine {
  readonly kind: 'mac';
  listening = false;
  /** How long the microphone stays on after the release (RELEASE_TAIL_MS; the tests shorten it). */
  tailMs = RELEASE_TAIL_MS;
  private mic: MicSource;
  private way: SpeechStream;
  private language: () => string;
  private client: SttClient | null = null;
  private held: Uint8Array[] = [];
  private heldBytes = 0;
  private starting: Promise<void> | null = null;
  private text = { final: '', interim: '' };
  private gen = 0;
  private lostError: SpeechError | null = null;

  constructor(mic: MicSource, way: SpeechStream, language: () => string) {
    this.mic = mic;
    this.way = way;
    this.kind = way.kind;
    this.language = language;
  }

  async start(onCaption: Caption): Promise<void> {
    await this.way.ready?.();
    const { ep, socket } = await this.way.open();
    const gen = ++this.gen;
    this.text = { final: '', interim: '' };
    this.held = []; this.heldBytes = 0; this.client = null; this.lostError = null;
    this.listening = true;
    this.mic.onPcm(pcm => {
      if (gen !== this.gen) return;
      if (this.client) { this.client.sendPcm(pcm); return; }
      if (this.heldBytes + pcm.byteLength > MAX_HELD) return;
      this.held.push(pcm.slice()); this.heldBytes += pcm.byteLength;
    });
    this.starting = (async () => {
      const micOk = await this.mic.start().catch(() => false);
      if (!micOk) throw new SpeechError('mic', 'The glasses microphone didn’t start. Are the glasses connected?');
      try {
        const client = await startMacStream(ep, this.way.options(this.language() || 'en'), snap => {
          if (gen !== this.gen) return;
          this.text = { final: snap.finalText, interim: snap.interimText };
          onCaption(snap.finalText, snap.interimText);
        }, err => {
          if (gen !== this.gen) return;
          this.lostError = new SpeechError('lost', err.message);
        }, socket ?? WebSocket);
        if (gen !== this.gen) { void client.close().catch(() => {}); return; }
        for (const pcm of this.held) client.sendPcm(pcm);
        this.held = []; this.heldBytes = 0;
        this.client = client;
      } catch (err) {
        throw new SpeechError('unreachable', this.way.words(err));
      }
    })();
    try {
      await this.starting;
    } catch (err) {
      this.listening = false;
      this.mic.onPcm(null);
      void this.mic.stop().catch(() => {});
      throw err;
    }
  }

  async stop(): Promise<Heard> {
    const gen = this.gen;
    this.listening = false;
    // The last word and the audio still on its way from the glasses (RELEASE_TAIL_MS).
    if (this.tailMs) await pause(this.tailMs);
    if (gen !== this.gen) return { text: '', complete: false };   // cancelled meanwhile
    await this.mic.stop().catch(() => {});
    try { await this.starting; } catch { /* reported by start() */ }
    this.mic.onPcm(null);
    const client = this.client;
    this.client = null;
    // `stop`, then the Mac's last snapshot (finished), or FINAL_WAIT_MS: then what we have is kept.
    let complete = true;
    if (client && gen === this.gen) await client.close().catch(() => { complete = false; });
    if (this.lostError && !this.text.final && !this.text.interim) throw this.lostError;
    return { text: joinText(this.text.final, this.text.interim).trim(), complete: complete && !this.lostError };
  }

  cancel(): void {
    this.gen++;
    this.listening = false;
    this.mic.onPcm(null);
    void this.mic.stop().catch(() => {});
    const c = this.client;
    this.client = null;
    if (c) void c.close().catch(() => {});
  }
}

/**
 * Sample data's speech: types a scripted sentence word by word while "listening", as Soniox
 * would. Used by ?demo=1 and the simulator screenshots; never touches a microphone.
 */
export class DemoSpeech implements SpeechEngine {
  readonly kind = 'demo' as const;
  listening = false;
  private timer: ReturnType<typeof setInterval> | null = null;
  private said: string[] = [];
  private script: string[] = [];
  private queue: string[];
  private caption: Caption = () => {};
  /** How the next hold ends (`next`'s options), then how this one does. */
  private ending: { tailMs?: number; cut?: boolean } = {};
  private ends: { tailMs?: number; cut?: boolean } = {};

  constructor(lines: string[]) { this.queue = [...lines]; }

  /**
   * What the next "hold" will say (tests and the phone's demo buttons). `tailMs`: its last words
   * come that long after the release, as Soniox's do; `cut`: they never come (the simulator scenes).
   */
  next(line: string, ending: { tailMs?: number; cut?: boolean } = {}): void { this.queue.unshift(line); this.ending = ending; }

  async start(onCaption: Caption): Promise<void> {
    const line = this.queue.shift() ?? 'status';
    this.queue.push(line);
    this.script = line.split(' ');
    this.said = [];
    this.caption = onCaption;
    this.ends = this.ending;
    this.ending = {};
    this.listening = true;
    // A word every 170 ms of wall time: a late timer (WebViews throttle them) catches up rather
    // than slowing the speaker down.
    const t0 = Date.now();
    this.timer = setInterval(() => {
      const due = Math.floor((Date.now() - t0) / 170);
      if (!this.script.length || this.said.length >= due) return;
      while (this.script.length && this.said.length < due) this.said.push(this.script.shift()!);
      onCaption(this.said.slice(0, -1).join(' '), this.said.at(-1) ?? '');
    }, 170);
  }

  async stop(): Promise<Heard> {
    this.listening = false;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    const { tailMs = 0, cut = false } = this.ends;
    if (cut) return { text: this.said.join(' '), complete: false };
    if (tailMs) await new Promise(r => setTimeout(r, tailMs));
    // Whatever was being said when the temple was released finishes, as a real phrase would.
    const text = [...this.said, ...this.script].join(' ');
    if (tailMs) this.caption(text, '');
    return { text, complete: true };
  }

  cancel(): void {
    this.listening = false;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }
}
