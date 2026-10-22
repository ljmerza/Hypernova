// The little cryptography the phone bridge needs (data/bridge.ts; docs/PROTOCOL.md, "The phone
// bridge"): SHA-256, HMAC-SHA-256 (RFC 2104) and X25519 (RFC 7748), written out here (synchronous,
// and there in a page that isn't a secure context, such as a dev server opened by a LAN address),
// and AES-256-GCM from WebCrypto, which seals every call (data/seal.ts): the platform's, never
// written out, so the bridge works only in a secure context (the Even App's `http://127.0.0.1` is
// one; `aesGcm()` says whether this page has it). Randomness comes from the platform.

const ROUND = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

const ror = (x: number, n: number) => (x >>> n) | (x << (32 - n));

export function sha256(data: Uint8Array): Uint8Array {
  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const len = data.length;
  const total = Math.ceil((len + 9) / 64) * 64;
  const m = new Uint8Array(total);
  m.set(data);
  m[len] = 0x80;
  const dv = new DataView(m.buffer);
  dv.setUint32(total - 8, Math.floor(len / 0x20000000));
  dv.setUint32(total - 4, (len << 3) >>> 0);
  const w = new Uint32Array(64);
  for (let off = 0; off < total; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const x = w[i - 15], y = w[i - 2];
      w[i] = (w[i - 16] + (ror(x, 7) ^ ror(x, 18) ^ (x >>> 3)) + w[i - 7] + (ror(y, 17) ^ ror(y, 19) ^ (y >>> 10))) | 0;
    }
    let a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7];
    for (let i = 0; i < 64; i++) {
      const t1 = (k + (ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)) + ((e & f) ^ (~e & g)) + ROUND[i] + w[i]) | 0;
      const t2 = ((ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      k = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += k;
  }
  const out = new Uint8Array(32);
  const ov = new DataView(out.buffer);
  for (let i = 0; i < 8; i++) ov.setUint32(i * 4, h[i]);
  return out;
}

export function hmacSha256(key: Uint8Array, msg: Uint8Array): Uint8Array {
  const k = new Uint8Array(64);
  k.set(key.length > 64 ? sha256(key) : key);
  const inner = new Uint8Array(64 + msg.length);
  const outer = new Uint8Array(64 + 32);
  for (let i = 0; i < 64; i++) { inner[i] = k[i] ^ 0x36; outer[i] = k[i] ^ 0x5c; }
  inner.set(msg, 64);
  outer.set(sha256(inner), 64);
  return sha256(outer);
}

// ------------------------------------------------------------------ X25519 (RFC 7748, section 5)

const P = (1n << 255n) - 19n;
const A24 = 121665n;
const md = (a: bigint) => { const r = a % P; return r < 0n ? r + P : r; };

function powMod(b: bigint, e: bigint): bigint {
  let r = 1n;
  b = md(b);
  while (e > 0n) {
    if (e & 1n) r = (r * b) % P;
    b = (b * b) % P;
    e >>= 1n;
  }
  return r;
}

function littleEndian(b: Uint8Array): bigint {
  let n = 0n;
  for (let i = b.length - 1; i >= 0; i--) n = (n << 8n) | BigInt(b[i]);
  return n;
}

/** X25519(k, u): the scalar `k` (32 bytes, clamped here) times the point `u` (32 bytes). */
export function x25519(k: Uint8Array, u: Uint8Array): Uint8Array {
  if (k.length !== 32 || u.length !== 32) throw new Error('x25519: 32 bytes each');
  const s = k.slice();
  s[0] &= 248; s[31] &= 127; s[31] |= 64;
  const scalar = littleEndian(s);
  const uu = u.slice();
  uu[31] &= 127;
  const x1 = md(littleEndian(uu));
  let x2 = 1n, z2 = 0n, x3 = x1, z3 = 1n, swap = 0n;
  for (let t = 254n; t >= 0n; t--) {
    const bit = (scalar >> t) & 1n;
    swap ^= bit;
    if (swap) { [x2, x3] = [x3, x2]; [z2, z3] = [z3, z2]; }
    swap = bit;
    const a = md(x2 + z2), aa = (a * a) % P, b = md(x2 - z2), bb = (b * b) % P, e = md(aa - bb);
    const c = md(x3 + z3), d = md(x3 - z3), da = (d * a) % P, cb = (c * b) % P;
    const sum = md(da + cb), diff = md(da - cb);
    x3 = (sum * sum) % P;
    z3 = (x1 * ((diff * diff) % P)) % P;
    x2 = (aa * bb) % P;
    z2 = (e * md(aa + A24 * e)) % P;
  }
  if (swap) { [x2, x3] = [x3, x2]; [z2, z3] = [z3, z2]; }
  let r = (x2 * powMod(z2, P - 2n)) % P;
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i++) { out[i] = Number(r & 255n); r >>= 8n; }
  return out;
}

const BASE = new Uint8Array(32);
BASE[0] = 9;

/** A fresh X25519 key pair (the private half never leaves the page). */
export function x25519Pair(random: (n: number) => Uint8Array = randomBytes): { priv: Uint8Array; pub: Uint8Array } {
  const priv = random(32);
  return { priv, pub: x25519(priv, BASE) };
}

// ------------------------------------------------------------------ bytes

export function utf8(s: string): Uint8Array { return new TextEncoder().encode(s); }

export function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let off = 0;
  for (const p of parts) { out.set(p, off); off += p.length; }
  return out;
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

/** Base64url without padding (RFC 4648, section 5). */
export function b64url(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i += 3) {
    const n = (b[i] << 16) | ((b[i + 1] ?? 0) << 8) | (b[i + 2] ?? 0);
    s += B64[(n >> 18) & 63] + B64[(n >> 12) & 63];
    if (i + 1 < b.length) s += B64[(n >> 6) & 63];
    if (i + 2 < b.length) s += B64[n & 63];
  }
  return s;
}

/** The bytes of a base64url string (no padding); null for anything else. */
export function fromB64url(s: string): Uint8Array | null {
  if (typeof s !== 'string' || !/^[A-Za-z0-9_-]*$/.test(s) || s.length % 4 === 1) return null;
  const out = new Uint8Array(Math.floor((s.length * 3) / 4));
  let bits = 0, acc = 0, j = 0;
  for (const ch of s) {
    acc = (acc << 6) | B64.indexOf(ch);
    bits += 6;
    if (bits >= 8) { bits -= 8; out[j++] = (acc >> bits) & 255; }
  }
  return out;
}

/** Equal bytes, compared in full whatever differs first. */
export function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a[i] ^ b[i];
  return d === 0;
}

export function randomBytes(n: number): Uint8Array {
  const b = new Uint8Array(n);
  globalThis.crypto.getRandomValues(b);
  return b;
}

// ------------------------------------------------------------------ AES-256-GCM (WebCrypto)

/** WebCrypto, when this page has it (a secure context: `127.0.0.1`, `https:`); null on a plain LAN page. */
export function aesGcm(): SubtleCrypto | null {
  try { return globalThis.crypto?.subtle ?? null; } catch { return null; }
}

export async function aesKey(raw: Uint8Array): Promise<CryptoKey> {
  const subtle = aesGcm();
  if (!subtle) throw new Error('no WebCrypto here');
  return subtle.importKey('raw', new Uint8Array(raw).buffer as ArrayBuffer, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
}

/** Ciphertext with its 16-byte tag after it (as WebCrypto and javax.crypto both give it). */
export async function aesSeal(key: CryptoKey, iv: Uint8Array, aad: Uint8Array, plain: Uint8Array): Promise<Uint8Array> {
  const out = await aesGcm()!.encrypt({ name: 'AES-GCM', iv: new Uint8Array(iv), additionalData: new Uint8Array(aad), tagLength: 128 }, key, new Uint8Array(plain));
  return new Uint8Array(out);
}

/** The plaintext; throws when the ciphertext, the tag, the IV or the associated data isn't the one sealed. */
export async function aesOpen(key: CryptoKey, iv: Uint8Array, aad: Uint8Array, sealed: Uint8Array): Promise<Uint8Array> {
  const out = await aesGcm()!.decrypt({ name: 'AES-GCM', iv: new Uint8Array(iv), additionalData: new Uint8Array(aad), tagLength: 128 }, key, new Uint8Array(sealed));
  return new Uint8Array(out);
}
