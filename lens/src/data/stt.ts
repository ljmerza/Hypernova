// Speech to text: Soniox through the Mac. The daemon relays the glasses' microphone to Soniox with
// the key the owner gave it (DAEMON.md, "Speech to text"); the stream is data/mac-stream.ts.
// AutoSpeech picks the engine at each hold (Soniox while the Mac says it's ready, else none, and
// then says what the Mac needs); a hold keeps the one it started with until it ends.
import { MacLostError, type MacEndpoint } from './mac-stream.ts';
import { FINAL_WAIT_MS, SpeechError, StreamSpeech, type Caption, type Heard, type MicSource, type SpeechEngine } from './speech.ts';
import type { Pairing } from './store.ts';
import type { Api } from './api.ts';
import type { GuideState, GuideStep, SttState } from '../core/types.ts';
import { sealedSocketClass } from './seal.ts';

/** The daemon's stream for a pairing straight to a Mac: the same host and token as everything else. */
export function macEndpoint(p: Pairing): MacEndpoint {
  const base = new URL(p.baseUrl);
  const secure = base.protocol === 'https:';
  return {
    wsUrl: `${secure ? 'wss' : 'ws'}://${base.host}/v1/stt/ws?token=${encodeURIComponent(p.token)}`,
    healthUrl: `${base.origin}/v1/health`,
    token: p.token,
    host: base.host,
  };
}

/**
 * The stream for `p`, and the socket to open it with. Through the app on this phone, its address is
 * signed (data/bridge.ts) for a listener that proved it's the app (throws when it hasn't) and every
 * message on it is sealed under that socket's key (data/seal.ts): the glasses' audio and the words
 * back are nobody else's to read on the phone.
 */
export async function macStream(p: Pairing, socket: typeof WebSocket): Promise<{ ep: MacEndpoint; socket: typeof WebSocket }> {
  const ep = macEndpoint(p);
  if (!p.signer) return { ep, socket };
  const { url, call } = await p.signer.socket('/v1/stt/ws');
  return { ep: { ...ep, wsUrl: url }, socket: sealedSocketClass(socket, call) };
}

/** Soniox through the Mac: the daemon's stream for this pairing, held audio and all (StreamSpeech). */
export class MacSpeech extends StreamSpeech {
  constructor(mic: MicSource, pairing: () => Pairing | null, language: () => string, socket?: typeof WebSocket) {
    super(mic, {
      kind: 'mac',
      open: async () => {
        const p = pairing();
        if (!p) throw new SpeechError('no_mac_speech', 'Pair Lens with your Mac first.');
        try {
          return await macStream(p, socket ?? WebSocket);
        } catch {
          // Through the app, which hasn't proved itself again since it went away for a moment.
          throw new SpeechError('unreachable', 'Hypernova on this phone isn’t answering. Open it.');
        }
      },
      // Through the app: whoever listens on its port proves it's the app right before the stream is
      // signed for it, so the glasses' audio never goes to anything else there.
      ready: async () => {
        const signer = pairing()?.signer;
        if (!signer) return;
        try {
          await signer.ready(true);
        } catch {
          throw new SpeechError('unreachable', 'Hypernova on this phone isn’t answering. Open it.');
        }
      },
      // An http pairing (development) carries the token over the same link already, so its
      // stream may too; a real one is https through Tailscale. Soniox's last words are waited for
      // FINAL_WAIT_MS at most (the Mac itself gives Soniox 5 s, then sends what it has as final).
      options: language => ({ language, allowInsecureLan: true, connectTimeoutMs: 9000, finalizeTimeoutMs: FINAL_WAIT_MS }),
      words: macWords,
    }, language);
  }
}

/** The Mac's own words when it refused the stream (no key, Soniox away), else ours. */
export function macWords(err: unknown): string {
  if (err instanceof MacLostError && err.reason === 'rejected') return err.message;
  return 'Can’t reach your Mac for speech. Is it awake, with Tailscale on?';
}

export type SpeechSource = 'mac' | 'demo' | 'none';

/**
 * The speech engine the lens holds: chooses at each hold (Soniox while the Mac has it) and keeps
 * that one until the hold ends, so a change on the Mac mid-sentence loses
 * nothing.
 */
export class AutoSpeech implements SpeechEngine {
  private pick: () => SpeechEngine | null;
  private active: SpeechEngine | null = null;

  constructor(pick: () => SpeechEngine | null) { this.pick = pick; }

  get kind(): 'mac' { return 'mac'; }
  get listening(): boolean { return this.active?.listening ?? false; }
  /** What a hold would use now. */
  source(): SpeechSource { const e = this.active ?? this.pick(); return e ? (e.kind === 'demo' ? 'demo' : e.kind) : 'none'; }

  async start(onCaption: Caption): Promise<void> {
    const e = this.pick();
    if (!e) throw new SpeechError('no_mac_speech', 'Voice needs Soniox on your Mac. Connect it on your phone: Settings, Speech.');
    this.active = e;
    try { await e.start(onCaption); } catch (err) { if (this.active === e) this.active = null; throw err; }
  }

  async stop(): Promise<Heard> {
    const e = this.active;
    if (!e) return { text: '', complete: true };
    try { return await e.stop(); } finally { if (this.active === e) this.active = null; }
  }

  cancel(): void {
    const e = this.active;
    this.active = null;
    e?.cancel();
  }
}

// ------------------------------------------------------------------ the Mac's speech settings

/** `GET /v1/stt`: never the key, only its last four characters. */
export interface SttInfo extends SttState {
  last4: string | null; model?: string; keysUrl?: string;
  /** After a save: false when Soniox couldn't be reached to try the key (it's kept, untried). */
  checked?: boolean;
  /** After a save: Soniox's words when it answered but the try didn't pass (busy, its own problem). */
  problem?: string | null;
}

/** Sends a key to the Mac, which tries it with Soniox before keeping it (a few seconds). */
export async function saveSonioxKey(api: Api, apiKey: string): Promise<SttInfo> {
  return api.post<SttInfo>('/v1/stt/key', { provider: 'soniox', apiKey: apiKey.trim() }, { timeoutMs: 25_000 });
}

/**
 * What a saved key comes to, in the Mac's words: ready; kept but untried (Soniox out of reach);
 * kept while Soniox answered something else (busy, a problem on its side); or the account's problem.
 */
export function savedWords(r: SttInfo): string {
  if (r.checked === false) return 'Saved. Soniox couldn’t be reached to check it just now.';
  if (!r.ready) return r.error ?? 'Saved.';
  if (r.problem) return `Saved. ${r.problem}`;
  return 'Soniox connected. Hold the temple and talk.';
}

export async function testStt(api: Api): Promise<{ ok: boolean; message: string }> {
  return api.post<{ ok: boolean; message: string }>('/v1/stt/test', {}, { timeoutMs: 25_000 });
}

export async function moveGuide(api: Api, patch: Partial<GuideState>): Promise<GuideState> {
  return (await api.post<{ guide: GuideState }>('/v1/guide', patch)).guide;
}

/** Brings the Mac's guide window forward at this step; how many Mac apps heard it. */
export async function showGuideOnMac(api: Api, step: GuideStep): Promise<number> {
  return (await api.post<{ macs: number }>('/v1/guide/show', { step })).macs ?? 0;
}
