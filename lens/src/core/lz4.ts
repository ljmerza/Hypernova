// LZ4 block format, to estimate what an image costs over Bluetooth: the Even App turns a picture into
// 4-bit greys and compresses it with LZ4 before it goes to the glasses (SDK 0.0.12's notes). Only the
// size is used (lens/tiles.ts); the decoder is here so tests can check the encoder is real LZ4. A
// greedy encoder like the reference "fast" one: 4-byte matches found through a hash table, the last
// 5 bytes always literals, no match starting in the last 12. Pure: runs under node.

const MINMATCH = 4, MFLIMIT = 12, LASTLITERALS = 5, HASH_LOG = 14;

const read32 = (b: Uint8Array, i: number) => (b[i] | (b[i + 1] << 8) | (b[i + 2] << 16) | (b[i + 3] << 24)) >>> 0;

/** `src` as one LZ4 block. */
export function lz4Compress(src: Uint8Array): Uint8Array {
  const n = src.length;
  const out = new Uint8Array(n + Math.ceil(n / 255) + 16);
  const table = new Int32Array(1 << HASH_LOG).fill(-1);
  let op = 0, ip = 0, anchor = 0;
  const length = (v: number) => { while (v >= 255) { out[op++] = 255; v -= 255; } out[op++] = v; };
  if (n > MFLIMIT) {
    const limit = n - MFLIMIT, matchEnd = n - LASTLITERALS;
    while (ip < limit) {
      const v = read32(src, ip);
      const h = Math.imul(v, 2654435761) >>> (32 - HASH_LOG);
      const ref = table[h];
      table[h] = ip;
      if (ref < 0 || ip - ref > 0xffff || read32(src, ref) !== v) { ip++; continue; }
      let len = MINMATCH;
      while (ip + len < matchEnd && src[ref + len] === src[ip + len]) len++;
      const lit = ip - anchor;
      const token = op++;
      out[token] = (Math.min(lit, 15) << 4) | Math.min(len - MINMATCH, 15);
      if (lit >= 15) length(lit - 15);
      out.set(src.subarray(anchor, ip), op); op += lit;
      out[op++] = (ip - ref) & 0xff; out[op++] = (ip - ref) >> 8;
      if (len - MINMATCH >= 15) length(len - MINMATCH - 15);
      ip += len;
      anchor = ip;
    }
  }
  const lit = n - anchor;
  out[op++] = Math.min(lit, 15) << 4;
  if (lit >= 15) length(lit - 15);
  out.set(src.subarray(anchor, n), op); op += lit;
  return out.slice(0, op);
}

/** One LZ4 block back to its bytes (tests). */
export function lz4Decompress(src: Uint8Array, size: number): Uint8Array {
  const out = new Uint8Array(size);
  let ip = 0, op = 0;
  const length = (v: number) => { if (v === 15) { let b; do { b = src[ip++]; v += b; } while (b === 255); } return v; };
  while (ip < src.length) {
    const token = src[ip++];
    const lit = length(token >> 4);
    out.set(src.subarray(ip, ip + lit), op); ip += lit; op += lit;
    if (ip >= src.length) break;
    const off = src[ip] | (src[ip + 1] << 8); ip += 2;
    const len = length(token & 15) + MINMATCH;
    for (let k = 0; k < len; k++, op++) out[op] = out[op - off];
  }
  return out;
}
