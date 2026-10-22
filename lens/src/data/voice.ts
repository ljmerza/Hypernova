// Sound into the earbuds: replies read aloud, and a soft blip when a session finishes or needs you.
//
// First choice, the Hypernova app on the phone (PROTOCOL.md, "Read aloud"): the Even App's WebView
// only makes sound after a touch on the phone's screen, so with the phone in a pocket the glasses
// stayed silent. When the app is connected and plays the glasses' speech, the Mac makes the voice
// (Soniox's, or its own), the app plays it natively through the phone's headphones, screen off or
// not, and says how far it got; that keeps text on the lens in step, and a reading counts as heard
// whole only when the app played it to the end.
//
// Else, as before, here: the Mac's voice (POST /v1/speech: Soniox's or `say`'s, as the Mac is set),
// then the phone's own (iOS's WebView has voices, Android's has none). Both play through one Web
// Audio context, which a WebView only lets make sound after a touch on the phone screen, so the
// first touch unlocks it and it stays unlocked. Long text is read in pieces (8000 characters for
// the Mac's voice, 1200 with Soniox, so each piece is made in moments), the next made while the
// last plays.
import type { HubLike } from './hub.ts';
import type { Settings } from './store.ts';
import { ApiError } from './api.ts';
import { speechChunks } from '../core/text.ts';
import { playOver, type PlayProgress } from '../core/speech-out.ts';

/**
 * Who read it. 'app': the Hypernova app on the phone. 'locked': nobody could, because the phone
 * hasn't been touched since Lens opened (and no app plays for it).
 */
export type VoiceRoute = 'app' | 'mac' | 'phone' | 'locked';

const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms));

/** The phone app silent this long mid-play (its link gone): the reading is over. */
const APP_SILENT_MS = 20_000;
/**
 * A play that has made no sound by now never will (Soniox hung, the phone can't play): the glasses
 * read it themselves. Longer than the daemon's own switch from Soniox to the Mac's voice, so the
 * daemon's word comes first.
 */
const APP_START_MS = 30_000;
/** A relayed loudness is current for this long; the phone sends a few a second. */
const LEVEL_FRESH_MS = 700;

export class VoiceOut {
  speaking = false;
  /** The WebView still wants a touch on the phone before it plays anything. */
  locked = true;
  onChange: () => void = () => {};
  private ctx: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private samples: Float32Array<ArrayBuffer> | null = null;
  private source: AudioBufferSourceNode | null = null;
  private utter: SpeechSynthesisUtterance | null = null;
  private macMissing = false;
  private gen = 0;
  private hub: () => HubLike | null;
  private settings: () => Settings;
  /** The piece being read now, for text shown in step with the voice (Transmission). */
  private piece: { startedAt: number; ms: number; from: number; len: number; total: number; chars: number | null } | null = null;
  /** The play the phone app reads now, as its progress says. */
  private app: { id: string; of: number; chars: number; level: number; heardAt: number; played: boolean; end: () => void } | null = null;
  /** The reading (by gen) the phone app played to the end. */
  private appEnded = -1;

  constructor(hub: () => HubLike | null, settings: () => Settings) {
    this.hub = hub;
    this.settings = settings;
    const unlock = () => { this.unlock(); };
    for (const ev of ['pointerdown', 'touchend', 'keydown'] as const) document.addEventListener(ev, unlock, { capture: true, passive: true });
  }

  /** Call from a touch: makes (or wakes) the audio context. */
  unlock(): void {
    try {
      if (!this.ctx) {
        const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
        if (!AC) return;
        this.ctx = new AC();
      }
      if (this.ctx.state === 'suspended') void this.ctx.resume().then(() => this.setLocked(false)).catch(() => {});
      else this.setLocked(false);
      // A silent sample: some WebViews only count a context as started once it has played.
      const b = this.ctx.createBuffer(1, 1, 22050), s = this.ctx.createBufferSource();
      s.buffer = b; s.connect(this.ctx.destination); s.start();
    } catch { /* no audio here */ }
  }

  private setLocked(v: boolean): void { if (this.locked !== v) { this.locked = v; this.onChange(); } }

  /** The phone's own voices (iOS WebView, desktop; not Android's WebView). */
  static phoneVoices(): SpeechSynthesisVoice[] {
    try { return window.speechSynthesis?.getVoices() ?? []; } catch { return []; }
  }

  /**
   * Read `text`; resolves when it's done (or stopped). Returns who spoke, or null if nobody could.
   * `sessionId`: whose reply it is, so the phone and the Mac show the wave on that session.
   */
  async speak(text: string, opts: { sessionId?: string | null } = {}): Promise<VoiceRoute | null> {
    this.stop();
    const choice = this.settings().voice;
    if (choice === 'off' || !text.trim()) return null;
    const gen = ++this.gen;
    this.speaking = true; this.onChange();
    try {
      // The Hypernova app on the phone first: it needs no touch, and plays with the screen off.
      if (choice !== 'phone' && (await this.speakApp(text, opts.sessionId ?? null, gen))) return 'app';
      if (gen !== this.gen) return null;
      // A suspended context plays nothing and never ends: say so rather than "reading" in silence.
      if (!(await this.running())) return 'locked';
      const soniox = this.hub()?.snapshot?.speech?.using === 'soniox';
      const chunks = speechChunks(text, soniox ? 1200 : 8000);
      this.offsets = [];
      let at = 0;
      for (const c of chunks) { this.offsets.push(at); at += c.length + 1; }
      this.total = Math.max(1, at - 1);
      if (choice !== 'phone' && !this.macMissing && this.hub()) {
        const ok = await this.speakMac(chunks, choice.startsWith('mac:') ? choice.slice(4) : null, gen);
        if (ok) return 'mac';
      }
      if (gen !== this.gen) return null;
      if (choice === 'auto' || choice === 'phone') return (await this.speakPhone(chunks, gen)) ? 'phone' : null;
      return null;
    } finally {
      if (gen === this.gen) { this.speaking = false; this.piece = null; this.app = null; this.ended = gen; this.onChange(); }
    }
  }

  /** The reading that last ended by itself: neither stopped nor cut off by another. */
  private ended = -1;

  /**
   * As speak(), and whether it was read to the end: a risky command counts as heard only then
   * (DECISIONS, "Glasses for cycling and walking"). Read by the phone app, only when it says it
   * played the last of it.
   */
  async speakWhole(text: string, opts: { sessionId?: string | null } = {}): Promise<{ who: VoiceRoute | null; whole: boolean }> {
    const who = await this.speak(text, opts);
    // Nothing else has started reading since (that would have moved gen on).
    const whole = who === 'app' ? this.appEnded === this.gen : (who === 'mac' || who === 'phone') && this.ended === this.gen;
    return { who, whole };
  }

  /**
   * The phone app reads it: false when none plays for the glasses (or it never started), and the
   * glasses read it themselves. Resolves once the play is over.
   */
  private async speakApp(text: string, sessionId: string | null, gen: number): Promise<boolean> {
    const hub = this.hub();
    if (!hub?.playOnPhone || hub.status !== 'live' || hub.snapshot?.speech?.phone !== true) return false;
    let start: Awaited<ReturnType<NonNullable<HubLike['playOnPhone']>>>;
    try { start = await hub.playOnPhone(text, sessionId); } catch (err) { console.warn('[voice] phone app:', (err as Error).message); return false; }
    if (!start) return false;
    if (gen !== this.gen) { void hub.stopOnPhone?.(start.id); return true; }
    const id = start.id;
    const outcome = await new Promise<PlayProgress['state']>(resolve => {
      let done = false;
      const finish = (s: PlayProgress['state']) => {
        if (done) return;
        done = true;
        off();
        clearInterval(watch);
        resolve(s);
      };
      this.app = { id, of: Math.max(1, start!.of || text.length), chars: 0, level: 0, heardAt: Date.now(), played: false, end: () => finish('stopped') };
      const off = hub.subscribe(e => {
        if (e.t !== 'speech_progress' || e.progress.id !== id || !this.app || this.app.id !== id) return;
        const p = e.progress;
        const first = !this.app.played && p.state === 'playing';
        this.app.chars = p.chars;
        this.app.level = p.level;
        this.app.heardAt = Date.now();
        if (p.state === 'playing') this.app.played = true;
        // The lens redraws by itself a few times a second while it speaks; only news wakes it now.
        if (first) this.onChange();
        if (playOver(p.state)) finish(p.state);
      });
      // The phone's link gone quiet mid-play: no word will come, the reading is over.
      const watch = setInterval(() => {
        const a = this.app;
        if (a?.id !== id) return;
        const quiet = Date.now() - a.heardAt;
        if (a.played ? quiet <= APP_SILENT_MS : quiet <= APP_START_MS) return;
        // Tell the phone to let go, and a play that never sounded is a failed one: the glasses take over.
        void hub.stopOnPhone?.(id);
        finish(a.played ? 'stopped' : 'failed');
      }, 1000);
    });
    const played = this.app?.id === id && this.app.played;
    if (gen === this.gen && outcome === 'ended') this.appEnded = gen;
    // It never played (the app couldn't, or wouldn't): the glasses read it themselves, as before.
    if (outcome === 'failed' && !played && gen === this.gen) { this.app = null; return false; }
    return true;
  }

  private offsets: number[] = [];
  private total = 1;

  /**
   * How far the reading has got, as a fraction of the text given to speak(): from the phone app's
   * timing, the clip's length for the Mac's voice, the words spoken for the phone's. null while
   * nothing is read.
   */
  progress(): number | null {
    if (!this.speaking) return null;
    if (this.app) return Math.min(1, this.app.chars / this.app.of);
    const p = this.piece;
    if (!p) return null;
    const into = p.chars !== null ? p.chars : p.ms > 0 ? Math.min(1, (Date.now() - p.startedAt) / p.ms) * p.len : 0;
    return Math.min(1, (p.from + into) / p.total);
  }

  /**
   * How loud the voice is now, 0..1, for the wave on the lens: as the phone app heard what it plays,
   * or from the audio playing here. 0 while nothing speaks.
   */
  level(): number {
    if (!this.speaking) return 0;
    if (this.app) return Date.now() - this.app.heardAt < LEVEL_FRESH_MS ? this.app.level : 0;
    const a = this.analyser;
    if (!a || !this.source) return 0;
    this.samples ??= new Float32Array(a.fftSize);
    a.getFloatTimeDomainData(this.samples);
    let sum = 0;
    for (const v of this.samples) sum += v * v;
    const db = 10 * Math.log10(sum / this.samples.length + 1e-12);
    return Math.min(1, Math.max(0, (db + 60) / 50));
  }

  /** Is the audio context allowed to play? Waking it outside a touch only works once one has happened. */
  private async running(): Promise<boolean> {
    this.unlock();
    const ctx = this.ctx;
    if (!ctx) return false;
    if (ctx.state !== 'running') await Promise.race([ctx.resume().catch(() => {}), sleep(400)]);
    const ok = (ctx.state as string) === 'running';
    this.setLocked(!ok);
    return ok;
  }

  /** The Mac's voice, piece by piece; false if it couldn't start (then the phone's voice may try). */
  private async speakMac(chunks: string[], voice: string | null, gen: number): Promise<boolean> {
    const render = (text: string) => this.hub()!.speak(text, voice, this.settings().rate).then(bytes => this.ctx!.decodeAudioData(bytes));
    let next: Promise<AudioBuffer> | null = render(chunks[0]);
    for (let i = 0; i < chunks.length && next; i++) {
      let buf: AudioBuffer;
      try { buf = await next; } catch (err) {
        // A Hypernova from before read-aloud, or a Mac without `say`: stop asking it.
        if (err instanceof ApiError && (err.problem === 'missing' || err.problem === 'update_mac' || err.code === 'no_voice')) this.macMissing = true;
        console.warn('[voice] Mac voice failed:', (err as Error).message);
        return i > 0;                           // halfway through: what was read stays read
      }
      if (gen !== this.gen) return true;
      // The Mac renders one clip at a time, so the next piece is asked for only now, while this one plays.
      next = i + 1 < chunks.length ? render(chunks[i + 1]) : null;
      next?.catch(() => {});
      this.piece = { startedAt: Date.now(), ms: buf.duration * 1000, from: this.offsets[i] ?? 0, len: chunks[i].length, total: this.total, chars: null };
      await this.play(buf);
      if (gen !== this.gen) return true;
    }
    return true;
  }

  private play(buf: AudioBuffer): Promise<void> {
    return new Promise<void>(resolve => {
      const ctx = this.ctx!;
      const s = ctx.createBufferSource();
      s.buffer = buf;
      // Through an analyser, so the wave on the lens follows what plays.
      if (!this.analyser) {
        try { this.analyser = ctx.createAnalyser(); this.analyser.fftSize = 1024; this.analyser.connect(ctx.destination); } catch { this.analyser = null; }
      }
      s.connect(this.analyser ?? ctx.destination);
      s.onended = () => resolve();
      this.source = s;
      s.start();
    });
  }

  private async speakPhone(chunks: string[], gen: number): Promise<boolean> {
    for (const [i, text] of chunks.entries()) {
      if (gen !== this.gen) return true;
      this.piece = { startedAt: Date.now(), ms: 0, from: this.offsets[i] ?? 0, len: text.length, total: this.total, chars: 0 };
      if (!(await this.speakPhoneOnce(text, gen))) return false;
    }
    return true;
  }

  private speakPhoneOnce(text: string, gen: number): Promise<boolean> {
    const synth = window.speechSynthesis;
    if (!synth || !VoiceOut.phoneVoices().length) return Promise.resolve(false);
    return new Promise(resolve => {
      const u = new SpeechSynthesisUtterance(text);
      const lang = this.settings().language;
      const v = VoiceOut.phoneVoices().find(x => lang !== 'auto' && x.lang.toLowerCase().startsWith(lang)) ?? null;
      if (v) u.voice = v;
      u.rate = this.settings().rate;
      // Where the phone's voice is: the text on the lens keeps in step with it.
      u.onboundary = e => { if (this.piece && gen === this.gen) this.piece.chars = e.charIndex; };
      u.onend = u.onerror = () => resolve(gen === this.gen);
      this.utter = u;
      synth.speak(u);
    });
  }

  stop(): void {
    this.gen++;
    try { this.source?.stop(); } catch { /* ended */ }
    this.source = null;
    if (this.utter) { try { window.speechSynthesis.cancel(); } catch { /* none */ } this.utter = null; }
    // The phone app stops too: a tap on the glasses stops the voice wherever it plays.
    const app = this.app;
    if (app) { this.app = null; void this.hub()?.stopOnPhone?.(app.id); app.end(); }
    if (this.speaking) { this.speaking = false; this.onChange(); }
  }

  /** A soft tone: two rising notes when a session finishes, one low note when it needs you. */
  blip(kind: 'done' | 'needs'): void {
    if (!this.settings().blip || !this.ctx || this.ctx.state !== 'running') return;
    const ctx = this.ctx, t0 = ctx.currentTime + 0.02;
    const notes = kind === 'done' ? [[660, 0], [990, 0.11]] : [[523, 0], [523, 0.16]];
    for (const [f, at] of notes) {
      const o = ctx.createOscillator(), g = ctx.createGain();
      o.type = 'sine'; o.frequency.value = f;
      g.gain.setValueAtTime(0, t0 + at);
      g.gain.linearRampToValueAtTime(0.05, t0 + at + 0.012);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + at + 0.16);
      o.connect(g); g.connect(ctx.destination);
      o.start(t0 + at); o.stop(t0 + at + 0.18);
    }
  }
}
