// The stream to the Mac's speech socket (GET /v1/stt/ws, daemon/src/stt.ts): the glasses'
// microphone goes up as 16 kHz s16le PCM, captions come back as the words settle. Messages:
// hello, start, started, snapshot, stop, error, ping. Every failure surfaces as MacLostError.

export interface SttSnapshot { finalText: string; interimText: string; finished: boolean }

export interface SttClient {
  sendPcm(chunk: Uint8Array): void;
  /** Resolves after the end of the audio has been told to the Mac and its last words came back. */
  close(): Promise<void>;
}

export interface MacEndpoint {
  /** ws(s)://host/v1/stt/ws?token=… (or the app's signed address) */
  wsUrl: string;
  healthUrl: string;
  token: string;
  host: string;
}

export type MacLostReason = 'unreachable' | 'closed' | 'rejected';

// No parameter properties: Node's strip-only TS (used by the tests) rejects them.
export class MacLostError extends Error {
  readonly reason: MacLostReason;
  constructor(message: string, reason: MacLostReason) {
    super(message);
    this.name = 'MacLostError';
    this.reason = reason;
  }
}

export interface MacStreamOptions {
  language: string;
  /** Allow ws:// to a host that isn't this phone (development pairings over http). */
  allowInsecureLan?: boolean;
  /** The Mac's hello must arrive within this. */
  connectTimeoutMs?: number;
  /** Ping this often; the link is dead after deadAfterMs without any message. */
  heartbeatMs?: number;
  deadAfterMs?: number;
  /** The last words must arrive within this after `stop`; a timeout is an incomplete save. */
  finalizeTimeoutMs?: number;
}

const LOCAL = ['localhost', '127.0.0.1', '[::1]'];
const MAX_BUFFERED = 524_288;

/** The caption texts joined with one space between. */
export function joinText(prefix: string, text: string): string {
  return prefix && text ? `${prefix} ${text}` : prefix || text;
}

/**
 * Opens the socket, waits for the Mac's `hello`, sends `start`, and resolves to a client once the Mac
 * says `started`. Rejects with a MacLostError otherwise; after that a dropped link goes to onError.
 */
export function startMacStream(
  ep: MacEndpoint,
  options: MacStreamOptions,
  onSnapshot: (snap: SttSnapshot) => void,
  onError: (err: MacLostError) => void,
  Socket: typeof WebSocket = WebSocket,
): Promise<SttClient> {
  return new Promise<SttClient>((resolve, reject) => {
    const url = new URL(ep.wsUrl);
    if (url.protocol === 'ws:' && !LOCAL.includes(url.hostname.toLowerCase()) && !options.allowInsecureLan) {
      reject(new MacLostError('An unencrypted link would expose the token and your voice.', 'rejected'));
      return;
    }
    let ws: WebSocket;
    try { ws = new Socket(ep.wsUrl); } catch (err) { reject(new MacLostError((err as Error).message, 'unreachable')); return; }
    ws.binaryType = 'arraybuffer';

    let opened = false;      // resolved: the Mac accepted the stream
    let settled = false;     // resolved or rejected
    let helloSeen = false;
    let ending = false;      // close() was called, or the link was reported lost
    let lost = false;
    let finish: { ok: () => void; fail: (e: Error) => void } | null = null;
    let closing: Promise<void> | null = null;
    let beat: ReturnType<typeof setInterval> | null = null;
    let lastSeen = Date.now();
    const deadAfter = options.deadAfterMs ?? 7000;

    const stopBeat = () => { if (beat) clearInterval(beat); beat = null; };
    const giveUp = (err: MacLostError) => {   // before the stream opened
      if (settled) return;
      settled = true;
      clearTimeout(connectTimer);
      try { ws.close(); } catch { /* already closed */ }
      reject(err);
    };
    const lose = (err: MacLostError) => {     // after it opened
      if (lost || ending) return;
      lost = ending = true;
      stopBeat();
      try { ws.close(4001, 'link failed'); } catch { /* already closed */ }
      onError(err);
    };
    const connectTimer = setTimeout(() => giveUp(new MacLostError(`${ep.host} did not answer in time`, 'unreachable')), options.connectTimeoutMs ?? 4000);

    const client: SttClient = {
      sendPcm(chunk) {
        if (ending || ws.readyState !== Socket.OPEN) return;
        if (chunk.byteLength % 2) return lose(new MacLostError('Audio must be whole 16-bit samples.', 'rejected'));
        if (ws.bufferedAmount > MAX_BUFFERED) return lose(new MacLostError('The link to your Mac is too slow for the audio.', 'closed'));
        // A copy: the bridge may hand over a view into a shared buffer.
        try { ws.send(chunk.slice().buffer as ArrayBuffer); } catch (err) { lose(new MacLostError((err as Error).message, 'closed')); }
      },
      close() {
        if (closing) return closing;
        ending = true;
        stopBeat();
        if (ws.readyState !== Socket.OPEN) return closing = Promise.resolve();
        let timer: ReturnType<typeof setTimeout>;
        closing = new Promise<void>((ok, fail) => {
          finish = { ok, fail };
          timer = setTimeout(() => fail(new MacLostError('The last words did not come back in time.', 'closed')), options.finalizeTimeoutMs ?? 4000);
          try { ws.send(JSON.stringify({ type: 'stop' })); } catch (err) { fail(err as Error); }
        }).finally(() => { clearTimeout(timer); finish = null; ws.close(); });
        return closing;
      },
    };

    ws.onmessage = ev => {
      lastSeen = Date.now();
      if (typeof ev.data !== 'string') return;
      let msg: Record<string, unknown>;
      try {
        const m: unknown = JSON.parse(ev.data);
        if (!m || typeof m !== 'object' || Array.isArray(m)) return;
        msg = m as Record<string, unknown>;
      } catch { return; }
      if (msg.type === 'hello') {
        if (settled || helloSeen) return;
        if (!msg.ok) return giveUp(new MacLostError('Your Mac has no speech ready.', 'rejected'));
        helloSeen = true;
        ws.send(JSON.stringify({ type: 'start', language: options.language }));
      } else if (msg.type === 'started') {
        if (settled || !helloSeen) return;
        settled = opened = true;
        clearTimeout(connectTimer);
        beat = setInterval(() => {
          if (ending) return;
          if (Date.now() - lastSeen > deadAfter) return lose(new MacLostError(`No answer from ${ep.host} for ${Math.round(deadAfter / 1000)} s.`, 'closed'));
          if (ws.readyState === Socket.OPEN) { try { ws.send(JSON.stringify({ type: 'ping' })); } catch { /* the next beat notices */ } }
        }, options.heartbeatMs ?? 3000);
        resolve(client);
      } else if (msg.type === 'snapshot') {
        const snap = { finalText: String(msg.finalText ?? ''), interimText: String(msg.interimText ?? ''), finished: Boolean(msg.finished) };
        onSnapshot(snap);
        if (snap.finished) finish?.ok();
      } else if (msg.type === 'error') {
        // A stream that ends in an error still owns the words so far: publish them first.
        if (opened && msg.finished === true && typeof msg.finalText === 'string' && typeof msg.interimText === 'string') {
          onSnapshot({ finalText: msg.finalText, interimText: msg.interimText, finished: true });
        }
        const text = String(msg.message ?? 'The Mac stopped the stream.');
        if (!settled) giveUp(new MacLostError(text, 'rejected'));
        else if (ending && finish) finish.fail(new MacLostError(text, 'closed'));
        else lose(new MacLostError(text, 'closed'));
      }
    };
    ws.onerror = () => giveUp(new MacLostError(`Could not connect to ${ep.host}`, 'unreachable'));
    ws.onclose = ev => {
      stopBeat();
      if (!settled) return giveUp(new MacLostError(`Connection to ${ep.host} closed (${ev.code})`, 'unreachable'));
      finish?.fail(new MacLostError('The connection closed before the last words came.', 'closed'));
      if (opened && !ending) lose(new MacLostError(ev.code === 4000 ? 'Another stream from this device took over.' : `Lost the link to your Mac (${ev.code}).`, 'closed'));
    };
  });
}
