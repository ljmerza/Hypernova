// How a signed call's bytes are sealed between Lens and the Hypernova app on this phone
// (PROTOCOL.md, "The phone bridge", 5–6), so whatever relays or reads the loopback connection sees
// only ciphertext, can't change a byte of it undetected, and can't play it again:
//
// - One key per call (`CallKey`: HMAC-SHA-256 of the agreed key over "hnb3-key\n" + the listener's
//   id + "\n" + the call's nonce, which the app takes once), AES-256-GCM. The 12-byte IV is the
//   direction (1: from Lens, 2: from the app), three zero bytes and a 64-bit counter: each direction
//   counts its own pieces from 0, so no IV repeats under a key and a piece dropped, repeated or
//   reordered doesn't open.
// - A body (a request's, or the whole answer) is a stream of records, each `flag ‖ length ‖
//   ciphertext` (1 byte: 0 more, 1 last; 4 bytes big-endian; the ciphertext with its tag), with
//   "hnb3-body\n" + "more" | "last" as associated data, so the end marker can't be moved: a stream
//   that ends without a `last` record was cut off. An answer's first record is its head,
//   `{"status": 200, "headers": [["content-type", "…"], …]}`; the records after it are its body.
// - A WebSocket message is one piece, "hnb3-frame" as associated data, its plaintext starting with
//   its kind (1 text, 2 binary, 3 the end: a close code, big-endian, then the reason), the end sent
//   before the socket closes, so a socket closed without it was cut off.
//
// The numbers are shared with the app's tests (BridgeSealTest.kt) through test/bridge.test.ts.
import { aesKey, aesOpen, aesSeal, concat, hmacSha256, utf8 } from '../core/crypto.ts';

export const FROM_LENS = 1;
export const FROM_APP = 2;
export const TEXT = 1;
export const BINARY = 2;
export const END = 3;
/** A record's plaintext at most; one declared longer is refused unread. */
export const MAX_PIECE = 1024 * 1024;
const TAG = 16;
const MORE = utf8('hnb3-body\nmore');
const LAST = utf8('hnb3-body\nlast');
const FRAME = utf8('hnb3-frame');

/** Something that isn't sealed under this call's key, whole and in order: not the app's, changed on the way, or cut. */
export class SealBroken extends Error {
  constructor(message: string) { super(message); this.name = 'SealBroken'; }
}

export function iv(dir: number, counter: number): Uint8Array {
  const b = new Uint8Array(12);
  b[0] = dir;
  new DataView(b.buffer).setBigUint64(4, BigInt(counter));
  return b;
}

/** The key one call is sealed with, on both sides: for `listener` and this call's `nonce`. */
export class CallKey {
  readonly listener: string;
  readonly nonce: string;
  private readonly key: CryptoKey;

  private constructor(listener: string, nonce: string, key: CryptoKey) {
    this.listener = listener;
    this.nonce = nonce;
    this.key = key;
  }

  /** `bridgeKey`: SHA-256 of the token (data/bridge.ts, `bridgeKey`). */
  static async derive(bridgeKey: Uint8Array, listener: string, nonce: string): Promise<CallKey> {
    return new CallKey(listener, nonce, await aesKey(callKeyBytes(bridgeKey, listener, nonce)));
  }

  seal(dir: number, counter: number, aad: Uint8Array, plain: Uint8Array): Promise<Uint8Array> { return aesSeal(this.key, iv(dir, counter), aad, plain); }

  async open(dir: number, counter: number, aad: Uint8Array, sealed: Uint8Array): Promise<Uint8Array> {
    if (sealed.length < TAG) throw new SealBroken('too short to be sealed');
    try { return await aesOpen(this.key, iv(dir, counter), aad, sealed); } catch { throw new SealBroken('doesn’t open'); }
  }
}

/** The raw bytes of a call's key (the tests check them against node:crypto). */
export function callKeyBytes(bridgeKey: Uint8Array, listener: string, nonce: string): Uint8Array {
  return hmacSha256(bridgeKey, utf8(`hnb3-key\n${listener}\n${nonce}`));
}

function record(flag: number, ct: Uint8Array): Uint8Array {
  const out = new Uint8Array(5 + ct.length);
  out[0] = flag;
  new DataView(out.buffer).setUint32(1, ct.length);
  out.set(ct, 5);
  return out;
}

/** A request's body as Lens sends it: one record, the last. */
export async function sealBody(call: CallKey, plain: Uint8Array): Promise<Uint8Array> {
  return record(1, await call.seal(FROM_LENS, 0, LAST, plain));
}

/** A whole sealed stream opened: its records' plaintexts, in order (the app's answer: the head, then the body). */
export async function openStream(call: CallKey, dir: number, bytes: Uint8Array, max = 64 * 1024 * 1024): Promise<Uint8Array[]> {
  const out: Uint8Array[] = [];
  let at = 0, counter = 0, total = 0;
  for (;;) {
    if (bytes.length - at < 5) throw new SealBroken('cut off');
    const flag = bytes[at];
    if (flag !== 0 && flag !== 1) throw new SealBroken('bad flag');
    const len = new DataView(bytes.buffer, bytes.byteOffset + at + 1, 4).getUint32(0);
    if (len > MAX_PIECE + TAG || bytes.length - at - 5 < len) throw new SealBroken('cut off');
    const plain = await call.open(dir, counter++, flag ? LAST : MORE, bytes.subarray(at + 5, at + 5 + len));
    total += plain.length;
    if (total > max) throw new SealBroken('too large');
    out.push(plain);
    at += 5 + len;
    if (flag) break;
  }
  if (at !== bytes.length) throw new SealBroken('bytes after the end');
  return out;
}

export interface OpenedAnswer { status: number; headers: Array<[string, string]>; body: Uint8Array }

/** The app's answer, sealed, opened: its status, its headers and its body; `SealBroken` when it isn't the app's, whole. */
export async function openAnswer(call: CallKey, bytes: Uint8Array): Promise<OpenedAnswer> {
  const [head, ...rest] = await openStream(call, FROM_APP, bytes);
  let h: { status?: unknown; headers?: unknown };
  try { h = JSON.parse(new TextDecoder().decode(head)) as typeof h; } catch { throw new SealBroken('no head'); }
  const status = Number(h.status);
  if (!Number.isInteger(status) || status < 100 || status > 599) throw new SealBroken('no status');
  const headers: Array<[string, string]> = [];
  if (Array.isArray(h.headers)) for (const p of h.headers) if (Array.isArray(p) && typeof p[0] === 'string' && typeof p[1] === 'string') headers.push([p[0], p[1]]);
  return { status, headers, body: concat(...rest) };
}

/** One WebSocket message sealed: its kind (`TEXT`, `BINARY`, `END`) and payload. */
export function sealFrame(call: CallKey, dir: number, counter: number, kind: number, payload: Uint8Array): Promise<Uint8Array> {
  return call.seal(dir, counter, FRAME, concat(new Uint8Array([kind]), payload));
}

export async function openFrame(call: CallKey, dir: number, counter: number, sealed: Uint8Array): Promise<{ kind: number; payload: Uint8Array }> {
  const plain = await call.open(dir, counter, FRAME, sealed);
  if (!plain.length || plain[0] < TEXT || plain[0] > END) throw new SealBroken('not a frame');
  return { kind: plain[0], payload: plain.subarray(1) };
}

/** The end marker's payload: the close code, then the reason. */
export function endPayload(code: number, reason: string): Uint8Array {
  const r = utf8(reason).subarray(0, 120);
  return concat(new Uint8Array([code >> 8, code & 255]), r);
}

export function endCode(payload: Uint8Array): { code: number; reason: string } {
  if (payload.length < 2) return { code: 1005, reason: '' };
  return { code: (payload[0] << 8) | payload[1], reason: new TextDecoder().decode(payload.subarray(2)) };
}

const toBytes = (d: ArrayBuffer | ArrayBufferView): Uint8Array =>
  d instanceof ArrayBuffer ? new Uint8Array(d.slice(0)) : new Uint8Array(d.buffer.slice(d.byteOffset, d.byteOffset + d.byteLength));

type Handler<E> = ((ev: E) => void) | null;

/**
 * A WebSocket to the app whose every message is sealed under `call`: what Lens sends goes sealed
 * (in order: each waits for the one before), what comes back is opened in order and handed on as
 * text or an ArrayBuffer, and the close code is the one the app sealed into its end marker. A
 * message that doesn't open (not the app's, changed, out of order), or an unsealed one, ends the
 * socket at once; a close without the app's end marker is reported as 1006 (cut off). Enough of
 * the WebSocket interface for data/hub.ts and the Mac's speech stream (which takes the class).
 */
export function sealedSocketClass(Impl: typeof WebSocket, call: CallKey): typeof WebSocket {
  class SealedSocket {
    static readonly CONNECTING = 0;
    static readonly OPEN = 1;
    static readonly CLOSING = 2;
    static readonly CLOSED = 3;
    readonly url: string;
    binaryType: BinaryType = 'arraybuffer';
    onopen: Handler<Event> = null;
    onmessage: Handler<{ data: string | ArrayBuffer }> = null;
    onclose: Handler<{ code: number; reason: string; wasClean: boolean }> = null;
    onerror: Handler<Event> = null;
    private inner: WebSocket;
    private sent = 0;
    private got = 0;
    private sending: Promise<void> = Promise.resolve();
    private receiving: Promise<void> = Promise.resolve();
    private ended: { code: number; reason: string } | null = null;
    private closing = false;
    private failed = false;

    constructor(url: string) {
      this.url = url;
      this.inner = new Impl(url);
      this.inner.binaryType = 'arraybuffer';
      this.inner.onopen = ev => this.onopen?.(ev);
      this.inner.onerror = ev => this.onerror?.(ev);
      this.inner.onmessage = ev => this.receive(ev.data as string | ArrayBuffer);
      this.inner.onclose = ev => { void this.receiving.then(() => this.closed(ev.code, ev.reason)); };
    }

    get readyState(): number { return this.inner.readyState; }
    get bufferedAmount(): number { return this.inner.bufferedAmount; }

    send(data: string | ArrayBuffer | ArrayBufferView): void {
      // As a WebSocket: before it's open, an error (nothing counted); after it closed, dropped
      // (nothing counted either, and nothing more will go). Counted only what will go, in order.
      const state = this.inner.readyState;
      if (state === SealedSocket.CONNECTING) throw new DOMException('Still connecting to the app.', 'InvalidStateError');
      if (state !== SealedSocket.OPEN) return;
      const kind = typeof data === 'string' ? TEXT : BINARY;
      const payload = typeof data === 'string' ? utf8(data) : toBytes(data);
      const n = this.sent++;
      this.sending = this.sending
        .then(() => sealFrame(call, FROM_LENS, n, kind, payload))
        .then(ct => { if (this.inner.readyState === SealedSocket.OPEN) this.inner.send(ct); })
        .catch(() => this.fail());
    }

    close(code = 1000, reason = ''): void {
      if (this.closing) return;
      this.closing = true;
      const shut = () => { try { this.inner.close(code, reason); } catch { this.inner.close(); } };
      // Not open: nothing to mark the end of.
      if (this.inner.readyState !== SealedSocket.OPEN) { shut(); return; }
      const n = this.sent++;
      this.sending = this.sending
        .then(() => sealFrame(call, FROM_LENS, n, END, endPayload(code, reason)))
        .then(ct => { if (this.inner.readyState === SealedSocket.OPEN) this.inner.send(ct); shut(); }, shut);
    }

    private receive(data: string | ArrayBuffer): void {
      // Unsealed: not the app's. Over, once what came before it (and opened) has been handed on.
      if (typeof data === 'string') { this.receiving = this.receiving.then(() => this.fail()); return; }
      const n = this.got++;
      this.receiving = this.receiving
        .then(() => openFrame(call, FROM_APP, n, new Uint8Array(data)))
        .then(f => {
          if (this.failed) return;
          if (f.kind === TEXT) this.onmessage?.({ data: new TextDecoder().decode(f.payload) });
          else if (f.kind === BINARY) this.onmessage?.({ data: f.payload.slice().buffer as ArrayBuffer });
          else this.ended = endCode(f.payload);
        })
        .catch(() => this.fail());
    }

    /** Something on the socket isn't the app's: over, as a cut (1006). */
    private fail(): void {
      if (this.failed) return;
      this.failed = true;
      this.ended = null;
      try { this.inner.close(4000, 'not sealed for this call'); } catch { /* closed */ }
    }

    private closed(code: number, reason: string): void {
      // The code the app sealed (or ours, when we closed); a close without the end marker was cut off.
      const end = this.ended;
      const out = end ? end : this.closing && !this.failed ? { code, reason } : { code: 1006, reason: '' };
      this.onclose?.({ code: out.code, reason: out.reason, wasClean: !!end || (this.closing && !this.failed) });
    }
  }
  return SealedSocket as unknown as typeof WebSocket;
}
