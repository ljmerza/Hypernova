// The app's side of the seal (data/seal.ts; the app's BridgeSeal.kt), worked out with node:crypto
// rather than Lens's own code, so the two can't agree by sharing a mistake. For bridge.test.ts and
// stt.test.ts.
import nc from 'node:crypto';

export const FROM_LENS = 1;
export const FROM_APP = 2;
export const TEXT = 1;
export const BINARY = 2;
export const END = 3;

export const nmac = (key: Uint8Array, ...parts: Array<string | Uint8Array>) => { const h = nc.createHmac('sha256', key); for (const p of parts) h.update(p); return h.digest(); };
export const ncallKey = (key: Uint8Array, listener: string, nonce: string) => nmac(key, `hnb3-key\n${listener}\n${nonce}`);
export const niv = (dir: number, ctr: number) => { const b = Buffer.alloc(12); b[0] = dir; b.writeBigUInt64BE(BigInt(ctr), 4); return b; };

export function nseal(ck: Uint8Array, dir: number, ctr: number, aad: string, pt: Uint8Array): Buffer {
  const c = nc.createCipheriv('aes-256-gcm', ck, niv(dir, ctr));
  c.setAAD(Buffer.from(aad));
  return Buffer.concat([c.update(pt), c.final(), c.getAuthTag()]);
}

export function nopen(ck: Uint8Array, dir: number, ctr: number, aad: string, ct: Uint8Array): Buffer {
  const d = nc.createDecipheriv('aes-256-gcm', ck, niv(dir, ctr));
  d.setAAD(Buffer.from(aad));
  d.setAuthTag(ct.subarray(ct.length - 16));
  return Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]);
}

export function nrecord(ck: Uint8Array, dir: number, ctr: number, last: boolean, pt: Uint8Array): Buffer {
  const ct = nseal(ck, dir, ctr, 'hnb3-body\n' + (last ? 'last' : 'more'), pt);
  const h = Buffer.alloc(5);
  h[0] = last ? 1 : 0;
  h.writeUInt32BE(ct.length, 1);
  return Buffer.concat([h, ct]);
}

/** A whole stream's records opened, in order; throws when it isn't one. */
export function nopenStream(ck: Uint8Array, dir: number, bytes: Uint8Array): Buffer[] {
  const out: Buffer[] = [];
  let at = 0, ctr = 0;
  for (;;) {
    if (bytes.length - at < 5) throw new Error('cut off');
    const flag = bytes[at];
    const len = Buffer.from(bytes.subarray(at + 1, at + 5)).readUInt32BE(0);
    if (bytes.length - at - 5 < len) throw new Error('cut off');
    out.push(nopen(ck, dir, ctr++, 'hnb3-body\n' + (flag ? 'last' : 'more'), bytes.subarray(at + 5, at + 5 + len)));
    at += 5 + len;
    if (flag) break;
  }
  if (at !== bytes.length) throw new Error('bytes after the end');
  return out;
}

/** An answer as the app seals it: the head, the body in `chunk`-sized pieces, an empty last record. `cut`: without the end (cut off). */
export function nanswer(ck: Uint8Array, status: number, headers: Array<[string, string]>, body: Uint8Array, chunk = 64 * 1024, cut = false): Buffer {
  const parts = [nrecord(ck, FROM_APP, 0, false, Buffer.from(JSON.stringify({ status, headers })))];
  let ctr = 1;
  for (let at = 0; at < body.length; at += chunk) parts.push(nrecord(ck, FROM_APP, ctr++, false, body.subarray(at, Math.min(body.length, at + chunk))));
  if (!cut) parts.push(nrecord(ck, FROM_APP, ctr++, true, Buffer.alloc(0)));
  return Buffer.concat(parts);
}

export const nframe = (ck: Uint8Array, dir: number, ctr: number, kind: number, payload: Uint8Array) => nseal(ck, dir, ctr, 'hnb3-frame', Buffer.concat([Buffer.from([kind]), payload]));
export function nopenFrame(ck: Uint8Array, dir: number, ctr: number, sealed: Uint8Array): { kind: number; payload: Buffer } {
  const p = nopen(ck, dir, ctr, 'hnb3-frame', sealed);
  return { kind: p[0], payload: p.subarray(1) };
}
export const nend = (code: number, reason = '') => Buffer.concat([Buffer.from([code >> 8, code & 255]), Buffer.from(reason)]);

/** A Buffer's bytes as their own ArrayBuffer (what a socket or `fetch` hands a page). */
export const ab = (b: Uint8Array): ArrayBuffer => b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer;

/** A sealed answer as `fetch` hands it to Lens: the outer 200 that says only `sealed`. */
export function sealedResponse(bytes: Buffer): Response {
  return new Response(ab(bytes), { status: 200, headers: { 'content-type': 'application/x-hnb3', 'X-Hypernova-Bridge': 'sealed', 'cache-control': 'no-store' } });
}

/** A request body as `fetch` was given it (an ArrayBuffer through the app, a string straight to a Mac). */
export function bodyBytes(body: BodyInit | null | undefined): Buffer {
  if (body === undefined || body === null) return Buffer.alloc(0);
  if (typeof body === 'string') return Buffer.from(body);
  if (body instanceof ArrayBuffer) return Buffer.from(body);
  if (ArrayBuffer.isView(body)) return Buffer.from(body.buffer, body.byteOffset, body.byteLength);
  throw new Error('a body of a kind Lens never sends');
}
