// QR codes for `hypernova pair` on a server's terminal: the pairing link, scanned by the phone's
// camera, as the Mac app shows it in its popover. A small encoder (ISO/IEC 18004, model 2): byte
// mode, versions 1 to 40, error correction L, M, Q or H, the mask with the lowest penalty, drawn
// with half blocks so two rows of modules fit one line of text. No dependency: the install fetches
// nothing it doesn't need.

export type Ecc = 'L' | 'M' | 'Q' | 'H'

const ECC_INDEX: Record<Ecc, number> = { L: 0, M: 1, Q: 2, H: 3 }
/** The two format bits each level is written with. */
const ECC_FORMAT: Record<Ecc, number> = { L: 1, M: 0, Q: 3, H: 2 }

// Per version 1..40 (index 0 unused): error correction codewords in each block, and the blocks.
const ECC_PER_BLOCK = [
  [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
  [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28],
  [-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
  [-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
]
const BLOCKS = [
  [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25],
  [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49],
  [-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68],
  [-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81],
]

/** Modules a version holds for data and error correction (everything but the fixed patterns). */
function rawModules(ver: number): number {
  let n = (16 * ver + 128) * ver + 64
  if (ver >= 2) {
    const align = Math.floor(ver / 7) + 2
    n -= (25 * align - 10) * align - 55
    if (ver >= 7) n -= 36
  }
  return n
}

/** Data codewords a version holds at a level. */
export function dataCodewords(ver: number, ecc: Ecc): number {
  const e = ECC_INDEX[ecc]
  return Math.floor(rawModules(ver) / 8) - ECC_PER_BLOCK[e]![ver]! * BLOCKS[e]![ver]!
}

/** The smallest version that holds [bytes] bytes in byte mode at [ecc]; null when none does. */
export function versionFor(bytes: number, ecc: Ecc): number | null {
  for (let v = 1; v <= 40; v++) {
    const bits = 4 + (v < 10 ? 8 : 16) + bytes * 8
    if (bits <= dataCodewords(v, ecc) * 8) return v
  }
  return null
}

// ── Reed-Solomon over GF(256), polynomial 0x11D ───────────────────────────
function gfMul(x: number, y: number): number {
  let z = 0
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d)
    z ^= ((y >>> i) & 1) * x
  }
  return z & 0xff
}

function rsDivisor(degree: number): number[] {
  const r = new Array<number>(degree).fill(0)
  r[degree - 1] = 1
  let root = 1
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < r.length; j++) {
      r[j] = gfMul(r[j]!, root)
      if (j + 1 < r.length) r[j] ^= r[j + 1]!
    }
    root = gfMul(root, 0x02)
  }
  return r
}

function rsRemainder(data: number[], divisor: number[]): number[] {
  const r = new Array<number>(divisor.length).fill(0)
  for (const b of data) {
    const factor = b ^ r.shift()!
    r.push(0)
    divisor.forEach((c, i) => (r[i] ^= gfMul(c, factor)))
  }
  return r
}

/** The data with its error correction, split in blocks and interleaved as the standard lays them out. */
function codewords(data: number[], ver: number, ecc: Ecc): number[] {
  const e = ECC_INDEX[ecc]
  const blocks = BLOCKS[e]![ver]!, eccLen = ECC_PER_BLOCK[e]![ver]!
  const raw = Math.floor(rawModules(ver) / 8)
  const shortBlocks = blocks - (raw % blocks)
  const shortLen = Math.floor(raw / blocks)
  const div = rsDivisor(eccLen)
  const all: number[][] = []
  for (let i = 0, k = 0; i < blocks; i++) {
    const dat = data.slice(k, k + shortLen - eccLen + (i < shortBlocks ? 0 : 1))
    k += dat.length
    const block = [...dat]
    // A short block gets a placeholder where the long ones have one more data codeword.
    if (i < shortBlocks) block.push(-1)
    all.push([...block, ...rsRemainder(dat, div)])
  }
  const out: number[] = []
  for (let i = 0; i < all[0]!.length; i++) for (const b of all) if (b[i]! >= 0) out.push(b[i]!)
  return out
}

// ── The symbol ────────────────────────────────────────────────────────────
export type Qr = { version: number; size: number; ecc: Ecc; mask: number; modules: boolean[][] }

function alignmentPositions(ver: number): number[] {
  if (ver === 1) return []
  const n = Math.floor(ver / 7) + 2
  const step = ver === 32 ? 26 : Math.ceil((ver * 4 + 4) / (n * 2 - 2)) * 2
  const out = [6]
  for (let pos = ver * 4 + 17 - 7; out.length < n; pos -= step) out.splice(1, 0, pos)
  return out
}

const MASKS: Array<(x: number, y: number) => boolean> = [
  (x, y) => (x + y) % 2 === 0,
  (_x, y) => y % 2 === 0,
  (x) => x % 3 === 0,
  (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
  (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
]

/** [text] (UTF-8) as a QR code: the smallest version that holds it at [ecc]. Throws when it's too long for any. */
export function encodeQr(text: string, ecc: Ecc = 'M'): Qr {
  const bytes = [...Buffer.from(text, 'utf8')]
  const ver = versionFor(bytes.length, ecc)
  if (ver === null) throw new Error('too long for a QR code')
  const capacity = dataCodewords(ver, ecc) * 8
  // Byte mode (0100), the length, the bytes, then a terminator and padding up to the capacity.
  const bits: number[] = []
  const put = (v: number, n: number) => {
    for (let i = n - 1; i >= 0; i--) bits.push((v >>> i) & 1)
  }
  put(4, 4)
  put(bytes.length, ver < 10 ? 8 : 16)
  for (const b of bytes) put(b, 8)
  put(0, Math.min(4, capacity - bits.length))
  put(0, (8 - (bits.length % 8)) % 8)
  for (let pad = 0xec; bits.length < capacity; pad ^= 0xec ^ 0x11) put(pad, 8)
  const data: number[] = []
  for (let i = 0; i < bits.length; i += 8) data.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0))
  const all = codewords(data, ver, ecc)

  const size = ver * 4 + 17
  const m: boolean[][] = Array.from({ length: size }, () => new Array<boolean>(size).fill(false))
  const fixed: boolean[][] = Array.from({ length: size }, () => new Array<boolean>(size).fill(false))
  const set = (x: number, y: number, dark: boolean) => {
    m[y]![x] = dark
    fixed[y]![x] = true
  }
  // Timing patterns, then finders (with their separators), alignment patterns, the format and
  // version areas (written for real once the mask is chosen).
  for (let i = 0; i < size; i++) {
    set(6, i, i % 2 === 0)
    set(i, 6, i % 2 === 0)
  }
  for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]] as const) {
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const x = cx + dx, y = cy + dy
      if (x < 0 || y < 0 || x >= size || y >= size) continue
      const d = Math.max(Math.abs(dx), Math.abs(dy))
      set(x, y, d !== 2 && d !== 4)
    }
  }
  const al = alignmentPositions(ver)
  for (let i = 0; i < al.length; i++) for (let j = 0; j < al.length; j++) {
    if ((i === 0 && j === 0) || (i === 0 && j === al.length - 1) || (i === al.length - 1 && j === 0)) continue
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(al[i]! + dx, al[j]! + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1)
  }
  writeFormat(m, fixed, size, ecc, 0)
  if (ver >= 7) writeVersion(set, size, ver)

  // The codewords in the standard's zigzag, two columns at a time from the right.
  let i = 0
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j
        const up = ((right + 1) & 2) === 0
        const y = up ? size - 1 - vert : vert
        if (!fixed[y]![x] && i < all.length * 8) {
          m[y]![x] = ((all[i >>> 3]! >>> (7 - (i & 7))) & 1) === 1
          i++
        }
      }
    }
  }

  // The mask that leaves the fewest patterns a scanner could trip on.
  let best = 0, bestScore = Infinity
  for (let k = 0; k < 8; k++) {
    const t = applyMask(m, fixed, k)
    writeFormat(t, fixed, size, ecc, k)
    const score = penalty(t, size)
    if (score < bestScore) {
      bestScore = score
      best = k
    }
  }
  const modules = applyMask(m, fixed, best)
  writeFormat(modules, fixed, size, ecc, best)
  return { version: ver, size, ecc, mask: best, modules }
}

function applyMask(m: boolean[][], fixed: boolean[][], k: number): boolean[][] {
  const f = MASKS[k]!
  return m.map((row, y) => row.map((v, x) => (fixed[y]![x] ? v : v !== f(x, y))))
}

/** The 15 format bits: the level and the mask, with their BCH code, XORed with 0x5412. */
export function formatBits(ecc: Ecc, mask: number): number {
  const data = (ECC_FORMAT[ecc] << 3) | mask
  let rem = data
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537)
  return ((data << 10) | rem) ^ 0x5412
}

function writeFormat(m: boolean[][], fixed: boolean[][], size: number, ecc: Ecc, mask: number): void {
  const bits = formatBits(ecc, mask)
  const bit = (i: number) => ((bits >>> i) & 1) === 1
  const set = (x: number, y: number, v: boolean) => {
    m[y]![x] = v
    fixed[y]![x] = true
  }
  for (let i = 0; i <= 5; i++) set(8, i, bit(i))
  set(8, 7, bit(6))
  set(8, 8, bit(7))
  set(7, 8, bit(8))
  for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i))
  for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i))
  for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i))
  // The module that is always dark.
  set(8, size - 8, true)
}

function writeVersion(set: (x: number, y: number, v: boolean) => void, size: number, ver: number): void {
  let rem = ver
  for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25)
  const bits = (ver << 12) | rem
  for (let i = 0; i < 18; i++) {
    const v = ((bits >>> i) & 1) === 1
    const a = size - 11 + (i % 3), b = Math.floor(i / 3)
    set(a, b, v)
    set(b, a, v)
  }
}

/** The standard's penalty: runs of one colour, 2×2 blocks, finder look-alikes, and the dark share. */
function penalty(m: boolean[][], size: number): number {
  let score = 0
  const at = (x: number, y: number, horizontal: boolean) => (horizontal ? m[y]![x]! : m[x]![y]!)
  for (const horizontal of [true, false]) {
    for (let a = 0; a < size; a++) {
      let run = 1
      for (let b = 1; b <= size; b++) {
        if (b < size && at(b, a, horizontal) === at(b - 1, a, horizontal)) run++
        else {
          if (run >= 5) score += 3 + run - 5
          run = 1
        }
      }
      // 1:1:3:1:1 with four light modules on one side (outside the symbol counts as light).
      const lit = (b: number) => b >= 0 && b < size && at(b, a, horizontal)
      for (let b = -4; b < size; b++) {
        const core = lit(b) && !lit(b + 1) && lit(b + 2) && lit(b + 3) && lit(b + 4) && !lit(b + 5) && lit(b + 6)
        if (!core) continue
        const before = !lit(b - 1) && !lit(b - 2) && !lit(b - 3) && !lit(b - 4)
        const after = !lit(b + 7) && !lit(b + 8) && !lit(b + 9) && !lit(b + 10)
        if (before || after) score += 40
      }
    }
  }
  for (let y = 0; y + 1 < size; y++) for (let x = 0; x + 1 < size; x++) {
    const c = m[y]![x]
    if (c === m[y]![x + 1] && c === m[y + 1]![x] && c === m[y + 1]![x + 1]) score += 3
  }
  let dark = 0
  for (const row of m) for (const v of row) if (v) dark++
  const total = size * size
  score += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10
  return score
}

/**
 * The code as lines of text, two module rows a line (▀ ▄ █ and spaces), with a quiet zone of
 * [quiet] modules. `ansi`: dark on white in the terminal's own colours, so it scans on a dark
 * terminal too; without it (a log, a pipe) the characters alone, dark modules drawn.
 */
export function qrLines(qr: Qr, o: { quiet?: number; ansi?: boolean } = {}): string[] {
  const q = o.quiet ?? 2
  const n = qr.size + 2 * q
  const dark = (x: number, y: number) => {
    const mx = x - q, my = y - q
    return mx >= 0 && my >= 0 && mx < qr.size && my < qr.size && qr.modules[my]![mx]!
  }
  const lines: string[] = []
  for (let y = 0; y < n; y += 2) {
    let s = ''
    for (let x = 0; x < n; x++) {
      const top = dark(x, y), bottom = y + 1 < n && dark(x, y + 1)
      s += top && bottom ? '█' : top ? '▀' : bottom ? '▄' : ' '
    }
    // 256-colour black on bright white: terminals draw plain "white" (47) as light grey.
    lines.push(o.ansi ? `\x1b[38;5;16;48;5;231m${s}\x1b[0m` : s)
  }
  return lines
}
