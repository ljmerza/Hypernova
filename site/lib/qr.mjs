// A small QR code encoder (ISO/IEC 18004): byte mode, versions 1–10, error correction L/M/Q/H,
// all eight masks scored by the standard's penalty rules. Enough for a URL; written for this site.

// Error correction codewords per block and the block layout, per version (1–10) and level.
// Each entry: [ecPerBlock, blocksInGroup1, dataPerBlock1, blocksInGroup2, dataPerBlock2].
const TABLE = {
  L: [[7, 1, 19, 0, 0], [10, 1, 34, 0, 0], [15, 1, 55, 0, 0], [20, 1, 80, 0, 0], [26, 1, 108, 0, 0], [18, 2, 68, 0, 0], [20, 2, 78, 0, 0], [24, 2, 97, 0, 0], [30, 2, 116, 0, 0], [18, 2, 68, 2, 69]],
  M: [[10, 1, 16, 0, 0], [16, 1, 28, 0, 0], [26, 1, 44, 0, 0], [18, 2, 32, 0, 0], [24, 2, 43, 0, 0], [16, 4, 27, 0, 0], [18, 4, 31, 0, 0], [22, 2, 38, 2, 39], [22, 3, 36, 2, 37], [26, 4, 43, 1, 44]],
  Q: [[13, 1, 13, 0, 0], [22, 1, 22, 0, 0], [18, 2, 17, 0, 0], [26, 2, 24, 0, 0], [18, 2, 15, 2, 16], [24, 4, 19, 0, 0], [18, 2, 14, 4, 15], [22, 4, 18, 2, 19], [20, 4, 16, 4, 17], [24, 6, 19, 2, 20]],
  H: [[17, 1, 9, 0, 0], [28, 1, 16, 0, 0], [22, 2, 13, 0, 0], [16, 4, 9, 0, 0], [22, 2, 11, 2, 12], [28, 4, 15, 0, 0], [26, 4, 13, 1, 14], [26, 4, 14, 2, 15], [24, 4, 12, 4, 13], [28, 6, 15, 2, 16]],
};
const LEVEL_BITS = { L: 1, M: 0, Q: 3, H: 2 };
const ALIGN = [[], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50]];

// GF(256) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1.
const EXP = new Uint8Array(512), LOG = new Uint8Array(256);
for (let i = 0, x = 1; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11d; }
for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
const mul = (a, b) => (a && b ? EXP[LOG[a] + LOG[b]] : 0);

function generator(n) {
  let g = [1];
  for (let i = 0; i < n; i++) {
    const next = new Array(g.length + 1).fill(0);
    for (let j = 0; j < g.length; j++) { next[j] ^= g[j]; next[j + 1] ^= mul(g[j], EXP[i]); }
    g = next;
  }
  return g;
}

function ecc(data, n) {
  const g = generator(n);
  const r = data.concat(new Array(n).fill(0));
  for (let i = 0; i < data.length; i++) {
    const c = r[i];
    if (c) for (let j = 0; j < g.length; j++) r[i + j] ^= mul(g[j], c);
  }
  return r.slice(data.length);
}

/** Returns { size, modules } where modules[y][x] is true for a dark module. */
export function encodeQR(text, level = 'M') {
  const bytes = [...new TextEncoder().encode(text)];
  let version = 0, spec;
  for (let v = 1; v <= 10; v++) {
    const s = TABLE[level][v - 1];
    const capacity = s[1] * s[2] + s[3] * s[4];
    const countBits = v < 10 ? 8 : 16;
    if (4 + countBits + 8 * bytes.length <= capacity * 8) { version = v; spec = s; break; }
  }
  if (!version) throw new Error('QR: text too long for version 10');
  const [ecLen, b1, d1, b2, d2] = spec;
  const dataLen = b1 * d1 + b2 * d2;

  // The bit stream: mode, count, bytes, terminator, padding.
  const bits = [];
  const put = (v, n) => { for (let i = n - 1; i >= 0; i--) bits.push((v >>> i) & 1); };
  put(0b0100, 4);
  put(bytes.length, version < 10 ? 8 : 16);
  for (const b of bytes) put(b, 8);
  put(0, Math.min(4, dataLen * 8 - bits.length));
  while (bits.length % 8) bits.push(0);
  const data = [];
  for (let i = 0; i < bits.length; i += 8) data.push(parseInt(bits.slice(i, i + 8).join(''), 2));
  for (let pad = 0; data.length < dataLen; pad++) data.push(pad % 2 ? 0x11 : 0xec);

  // Blocks, their error correction, interleaved.
  const blocks = [];
  let k = 0;
  for (let i = 0; i < b1 + b2; i++) {
    const len = i < b1 ? d1 : d2;
    const d = data.slice(k, k + len); k += len;
    blocks.push({ d, e: ecc(d, ecLen) });
  }
  const code = [];
  for (let i = 0; i < Math.max(d1, d2); i++) for (const b of blocks) if (i < b.d.length) code.push(b.d[i]);
  for (let i = 0; i < ecLen; i++) for (const b of blocks) code.push(b.e[i]);

  const size = 17 + 4 * version;
  const m = Array.from({ length: size }, () => new Array(size).fill(false));
  const fn = Array.from({ length: size }, () => new Array(size).fill(false));
  const set = (x, y, dark) => { m[y][x] = dark; fn[y][x] = true; };

  // Finder patterns and separators.
  for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]]) {
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const x = cx + dx, y = cy + dy;
      if (x < 0 || y < 0 || x >= size || y >= size) continue;
      const d = Math.max(Math.abs(dx), Math.abs(dy));
      set(x, y, d !== 2 && d !== 4);
    }
  }
  // Timing patterns.
  for (let i = 8; i < size - 8; i++) { set(i, 6, i % 2 === 0); set(6, i, i % 2 === 0); }
  // Alignment patterns.
  const al = ALIGN[version - 1];
  for (const ay of al) for (const ax of al) {
    if ((ax === 6 && ay === 6) || (ax === 6 && ay === al[al.length - 1]) || (ay === 6 && ax === al[al.length - 1])) continue;
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(ax + dx, ay + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  }
  // Reserve the format areas and the dark module; version info from version 7.
  for (let i = 0; i < 9; i++) { if (!fn[8][i]) set(i, 8, false); if (!fn[i][8]) set(8, i, false); }
  for (let i = 0; i < 8; i++) { set(size - 1 - i, 8, false); set(8, size - 1 - i, false); }
  set(8, size - 8, true);
  if (version >= 7) {
    let rem = version;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    const vbits = (version << 12) | rem;
    for (let i = 0; i < 18; i++) {
      const dark = ((vbits >>> i) & 1) === 1;
      const a = size - 11 + (i % 3), b = Math.floor(i / 3);
      set(a, b, dark); set(b, a, dark);
    }
  }

  // Data in the zigzag, right to left in column pairs, skipping the timing column.
  let bit = 0;
  const total = code.length * 8;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        const upward = ((right + 1) & 2) === 0;
        const y = upward ? size - 1 - vert : vert;
        if (fn[y][x]) continue;
        m[y][x] = bit < total ? ((code[bit >>> 3] >>> (7 - (bit & 7))) & 1) === 1 : false;
        bit++;
      }
    }
  }

  const MASKS = [
    (x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x) => x % 3 === 0, (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0, (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
    (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0, (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
  ];

  function withMask(mask) {
    const g = m.map((row) => row.slice());
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (!fn[y][x] && MASKS[mask](x, y)) g[y][x] = !g[y][x];
    // Format bits: level and mask, BCH(15,5), XOR 0x5412.
    const dataBits = (LEVEL_BITS[level] << 3) | mask;
    let rem = dataBits;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const f = ((dataBits << 10) | rem) ^ 0x5412;
    const fb = (i) => ((f >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) g[i][8] = fb(i);
    g[7][8] = fb(6); g[8][8] = fb(7); g[8][7] = fb(8);
    for (let i = 9; i < 15; i++) g[8][14 - i] = fb(i);
    for (let i = 0; i < 8; i++) g[8][size - 1 - i] = fb(i);
    for (let i = 8; i < 15; i++) g[size - 15 + i][8] = fb(i);
    g[size - 8][8] = true;
    return g;
  }

  function penalty(g) {
    let p = 0;
    // Runs of five or more in a row or column.
    for (let pass = 0; pass < 2; pass++) {
      for (let a = 0; a < size; a++) {
        let run = 1;
        for (let b = 1; b < size; b++) {
          const cur = pass ? g[b][a] : g[a][b], prev = pass ? g[b - 1][a] : g[a][b - 1];
          if (cur === prev) { run++; if (run === 5) p += 3; else if (run > 5) p++; } else run = 1;
        }
      }
    }
    // 2×2 blocks.
    for (let y = 0; y < size - 1; y++) for (let x = 0; x < size - 1; x++) {
      const c = g[y][x];
      if (c === g[y][x + 1] && c === g[y + 1][x] && c === g[y + 1][x + 1]) p += 3;
    }
    // Finder-like patterns 1:1:3:1:1 with four light modules on a side.
    const pat1 = [true, false, true, true, true, false, true, false, false, false, false];
    const pat2 = [false, false, false, false, true, false, true, true, true, false, true];
    for (let y = 0; y < size; y++) for (let x = 0; x <= size - 11; x++) {
      let h1 = true, h2 = true, v1 = true, v2 = true;
      for (let k = 0; k < 11; k++) {
        if (g[y][x + k] !== pat1[k]) h1 = false;
        if (g[y][x + k] !== pat2[k]) h2 = false;
        if (g[x + k][y] !== pat1[k]) v1 = false;
        if (g[x + k][y] !== pat2[k]) v2 = false;
      }
      p += 40 * (h1 + h2 + v1 + v2);
    }
    // Balance of dark and light.
    let dark = 0;
    for (const row of g) for (const c of row) if (c) dark++;
    p += Math.floor(Math.abs(dark * 20 - size * size * 10) / (size * size)) * 10;
    return p;
  }

  let best = null, bestP = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    const g = withMask(mask);
    const p = penalty(g);
    if (p < bestP) { best = g; bestP = p; }
  }
  return { size, modules: best, version };
}

/** The code as an inline SVG: one path, a quiet zone of [margin] modules. */
export function qrSvg(text, { level = 'M', margin = 3, dark = '#14110E', light = '#F5F2EB', label = '' } = {}) {
  const { size, modules } = encodeQR(text, level);
  const n = size + margin * 2;
  let d = '';
  for (let y = 0; y < size; y++) {
    let x = 0;
    while (x < size) {
      if (!modules[y][x]) { x++; continue; }
      let w = 1;
      while (x + w < size && modules[y][x + w]) w++;
      d += `M${x + margin} ${y + margin}h${w}v1h-${w}z`;
      x += w;
    }
  }
  const title = label ? `<title>${label.replace(/[<&]/g, (c) => (c === '<' ? '&lt;' : '&amp;'))}</title>` : '';
  return `<svg class="qr" viewBox="0 0 ${n} ${n}" role="img"${label ? ' aria-label="' + label.replace(/"/g, '&quot;') + '"' : ''} shape-rendering="crispEdges" xmlns="http://www.w3.org/2000/svg">${title}<rect width="${n}" height="${n}" rx="2" fill="${light}"/><path fill="${dark}" d="${d}"/></svg>`;
}
