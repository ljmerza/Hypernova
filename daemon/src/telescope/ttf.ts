// TrueType outlines drawn in plain TypeScript, so a Linux server can draw the terminal for Telescope
// with the same JetBrains Mono the Mac uses (paint.ts), with no system library. Reads the tables a
// glyph needs (cmap, loca, glyf, hmtx) and turns its quadratic outline into an antialiased coverage
// mask: each edge adds the area it covers to an accumulation buffer, and a running sum across each
// row gives every pixel's coverage. Overlapping contours (variable fonts have them) add up and are
// clamped, which is the non-zero rule. A variable font is drawn at its default instance (JetBrains
// Mono's is Regular), which is the one its glyf table holds.
import { readFileSync } from 'node:fs'

export type Font = {
  unitsPerEm: number
  ascender: number
  descender: number
  /** Glyph index for a code point; 0 (the missing glyph) when the font hasn't it. */
  glyphOf(cp: number): number
  advanceOf(gid: number): number
  outline(gid: number): Contour[]
}

/** A closed contour of points in font units, y up; `on`: on the curve, else a quadratic control point. */
export type Contour = Array<{ x: number; y: number; on: boolean }>

/** The font at [path], or null when it isn't a TrueType font this can read (CFF outlines, damage). */
export function loadFont(path: string): Font | null {
  try {
    return parseFont(readFileSync(path))
  } catch {
    return null
  }
}

export function parseFont(b: Buffer): Font | null {
  const tag = b.readUInt32BE(0)
  // TrueType outlines only ('true' or 1.0); 'OTTO' is CFF.
  if (tag !== 0x00010000 && tag !== 0x74727565) return null
  const tables = new Map<string, { off: number; len: number }>()
  const n = b.readUInt16BE(4)
  for (let i = 0; i < n; i++) {
    const r = 12 + 16 * i
    tables.set(b.toString('latin1', r, r + 4), { off: b.readUInt32BE(r + 8), len: b.readUInt32BE(r + 12) })
  }
  const t = (name: string) => {
    const x = tables.get(name)
    if (!x || x.off + x.len > b.length) throw new Error(`no ${name} table`)
    return x.off
  }
  const head = t('head'), maxp = t('maxp'), hhea = t('hhea'), hmtx = t('hmtx'), loca = t('loca'), glyf = t('glyf'), cmap = t('cmap')
  const unitsPerEm = b.readUInt16BE(head + 18)
  const longLoca = b.readInt16BE(head + 50) === 1
  const numGlyphs = b.readUInt16BE(maxp + 4)
  const ascender = b.readInt16BE(hhea + 4)
  const descender = b.readInt16BE(hhea + 6)
  const numHMetrics = b.readUInt16BE(hhea + 34)

  // cmap: format 12 (all of Unicode) when there is one, else format 4 (the BMP).
  let f4 = -1, f12 = -1
  const subtables = b.readUInt16BE(cmap + 2)
  for (let i = 0; i < subtables; i++) {
    const platform = b.readUInt16BE(cmap + 4 + i * 8), enc = b.readUInt16BE(cmap + 6 + i * 8)
    const off = cmap + b.readUInt32BE(cmap + 8 + i * 8)
    const format = b.readUInt16BE(off)
    const unicode = platform === 0 || (platform === 3 && (enc === 1 || enc === 10))
    if (!unicode) continue
    if (format === 12 && f12 < 0) f12 = off
    if (format === 4 && f4 < 0) f4 = off
  }
  const cache = new Map<number, number>()
  function glyphOf(cp: number): number {
    const hit = cache.get(cp)
    if (hit !== undefined) return hit
    let g = 0
    if (f12 >= 0) {
      const groups = b.readUInt32BE(f12 + 12)
      for (let i = 0; i < groups; i++) {
        const r = f12 + 16 + i * 12
        const start = b.readUInt32BE(r), end = b.readUInt32BE(r + 4)
        if (cp >= start && cp <= end) {
          g = b.readUInt32BE(r + 8) + cp - start
          break
        }
      }
    } else if (f4 >= 0 && cp <= 0xffff) {
      const segX2 = b.readUInt16BE(f4 + 6)
      const ends = f4 + 14, starts = ends + segX2 + 2, deltas = starts + segX2, ranges = deltas + segX2
      for (let i = 0; i < segX2; i += 2) {
        if (cp > b.readUInt16BE(ends + i)) continue
        const start = b.readUInt16BE(starts + i)
        if (cp < start) break
        const delta = b.readInt16BE(deltas + i)
        const ro = b.readUInt16BE(ranges + i)
        if (!ro) g = (cp + delta) & 0xffff
        else {
          const at = ranges + i + ro + (cp - start) * 2
          const v = at + 2 <= b.length ? b.readUInt16BE(at) : 0
          g = v ? (v + delta) & 0xffff : 0
        }
        break
      }
    }
    if (g >= numGlyphs) g = 0
    cache.set(cp, g)
    return g
  }
  const advanceOf = (gid: number) => b.readUInt16BE(hmtx + 4 * Math.min(gid, numHMetrics - 1))
  const glyphRange = (gid: number): [number, number] => {
    if (gid < 0 || gid >= numGlyphs) return [0, 0]
    return longLoca ? [b.readUInt32BE(loca + gid * 4), b.readUInt32BE(loca + gid * 4 + 4)] : [b.readUInt16BE(loca + gid * 2) * 2, b.readUInt16BE(loca + gid * 2 + 2) * 2]
  }

  function outline(gid: number, depth = 0): Contour[] {
    const [start, end] = glyphRange(gid)
    if (end <= start || depth > 8) return []
    const g = glyf + start
    const contours = b.readInt16BE(g)
    if (contours >= 0) return simple(g, contours)
    // A composite: other glyphs placed (and maybe scaled) inside this one.
    const out: Contour[] = []
    let p = g + 10
    for (;;) {
      const flags = b.readUInt16BE(p), comp = b.readUInt16BE(p + 2)
      p += 4
      let dx: number, dy: number
      if (flags & 1) {
        dx = b.readInt16BE(p)
        dy = b.readInt16BE(p + 2)
        p += 4
      } else {
        dx = b.readInt8(p)
        dy = b.readInt8(p + 1)
        p += 2
      }
      let a = 1, bb = 0, c = 0, d = 1
      const f2 = (o: number) => b.readInt16BE(o) / 16384
      if (flags & 0x8) {
        a = d = f2(p)
        p += 2
      } else if (flags & 0x40) {
        a = f2(p)
        d = f2(p + 2)
        p += 4
      } else if (flags & 0x80) {
        a = f2(p)
        bb = f2(p + 2)
        c = f2(p + 4)
        d = f2(p + 6)
        p += 8
      }
      // Bit 1 (ARGS_ARE_XY_VALUES) unset means point matching, which monospace fonts don't use.
      if (flags & 2) for (const ct of outline(comp, depth + 1)) out.push(ct.map((q) => ({ x: q.x * a + q.y * c + dx, y: q.x * bb + q.y * d + dy, on: q.on })))
      if (!(flags & 0x20)) break
    }
    return out
  }

  function simple(g: number, contours: number): Contour[] {
    const ends: number[] = []
    for (let i = 0; i < contours; i++) ends.push(b.readUInt16BE(g + 10 + i * 2))
    const count = contours ? ends[contours - 1]! + 1 : 0
    let p = g + 10 + contours * 2
    p += 2 + b.readUInt16BE(p) // instructions
    const flags: number[] = []
    while (flags.length < count) {
      const f = b[p++]!
      flags.push(f)
      if (f & 8) {
        let r = b[p++]!
        while (r-- > 0 && flags.length < count) flags.push(f)
      }
    }
    const xs: number[] = [], ys: number[] = []
    let v = 0
    for (const f of flags) {
      if (f & 2) {
        const d = b[p++]!
        v += f & 16 ? d : -d
      } else if (!(f & 16)) {
        v += b.readInt16BE(p)
        p += 2
      }
      xs.push(v)
    }
    v = 0
    for (const f of flags) {
      if (f & 4) {
        const d = b[p++]!
        v += f & 32 ? d : -d
      } else if (!(f & 32)) {
        v += b.readInt16BE(p)
        p += 2
      }
      ys.push(v)
    }
    const out: Contour[] = []
    let from = 0
    for (const e of ends) {
      const ct: Contour = []
      for (let i = from; i <= e; i++) ct.push({ x: xs[i]!, y: ys[i]!, on: !!(flags[i]! & 1) })
      out.push(ct)
      from = e + 1
    }
    return out
  }

  return { unitsPerEm, ascender, descender, glyphOf, advanceOf, outline: (gid) => outline(gid) }
}

/** A glyph's coverage: alpha 0..255 over w × h, its top-left at (left, top) from the pen at the baseline. */
export type Mask = { left: number; top: number; w: number; h: number; a: Uint8Array }

/**
 * The glyph [gid] at [size] pixels to the em, its pen [frac] of a pixel right of a whole pixel (so
 * a monospace column that falls between pixels still draws true). Null for an empty glyph (a space).
 */
export function rasterize(font: Font, gid: number, size: number, frac = 0): Mask | null {
  const contours = font.outline(gid)
  if (!contours.length) return null
  const k = size / font.unitsPerEm
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
  for (const ct of contours) for (const q of ct) {
    minX = Math.min(minX, q.x)
    maxX = Math.max(maxX, q.x)
    minY = Math.min(minY, q.y)
    maxY = Math.max(maxY, q.y)
  }
  if (!Number.isFinite(minX)) return null
  const left = Math.floor(minX * k + frac), top = Math.floor(-maxY * k)
  const w = Math.ceil(maxX * k + frac) - left + 2, h = Math.ceil(-minY * k) - top + 1
  if (w <= 0 || h <= 0 || w * h > 4_000_000) return null
  const acc = new Float32Array(w * h + 1)
  const px = (x: number) => x * k + frac - left
  const py = (y: number) => -y * k - top
  for (const ct of contours) {
    for (const [a, c, b2] of quads(ct)) {
      const x0 = px(a.x), y0 = py(a.y), x2 = px(b2.x), y2 = py(b2.y)
      if (!c) {
        line(acc, w, h, x0, y0, x2, y2)
        continue
      }
      const x1 = px(c.x), y1 = py(c.y)
      // Enough straight pieces that none strays from the curve by more than a fraction of a pixel.
      const dev = (x0 - 2 * x1 + x2) ** 2 + (y0 - 2 * y1 + y2) ** 2
      const steps = dev < 0.333 ? 1 : 1 + Math.floor(Math.sqrt(Math.sqrt(3 * dev)))
      let lx = x0, ly = y0
      for (let i = 1; i <= steps; i++) {
        const t = i / steps, u = 1 - t
        const nx = u * u * x0 + 2 * u * t * x1 + t * t * x2, ny = u * u * y0 + 2 * u * t * y1 + t * t * y2
        line(acc, w, h, lx, ly, nx, ny)
        lx = nx
        ly = ny
      }
    }
  }
  const a = new Uint8Array(w * h)
  let sum = 0
  for (let i = 0; i < w * h; i++) {
    sum += acc[i]!
    a[i] = Math.round(Math.min(1, Math.abs(sum)) * 255)
  }
  return { left, top, w, h, a }
}

type Pt = { x: number; y: number }
/**
 * A TrueType contour as its pieces: [start, control, end] for a curve, [start, null, end] for a
 * line. Two control points in a row have an on-curve point halfway between them, implied.
 */
function quads(ct: Contour): Array<[Pt, Pt | null, Pt]> {
  const n = ct.length
  if (n < 2) return []
  // Start on a point on the curve (or halfway between two that aren't).
  let s = ct.findIndex((q) => q.on)
  let first: Pt
  if (s < 0) {
    first = { x: (ct[0]!.x + ct[1]!.x) / 2, y: (ct[0]!.y + ct[1]!.y) / 2 }
    s = 0
  } else first = ct[s]!
  const out: Array<[Pt, Pt | null, Pt]> = []
  let cur = first
  let ctrl: Pt | null = null
  // Round the contour back to where it started; a contour with no point on the curve closes on the
  // implied point it started from, after the loop.
  for (let i = 1; i <= n; i++) {
    const target = ct[(s + i) % n]!
    if (target.on) {
      out.push([cur, ctrl, target])
      cur = target
      ctrl = null
    } else if (ctrl) {
      const mid = { x: (ctrl.x + target.x) / 2, y: (ctrl.y + target.y) / 2 }
      out.push([cur, ctrl, mid])
      cur = mid
      ctrl = target
    } else ctrl = target
  }
  // Close back to the start.
  if (ctrl) out.push([cur, ctrl, first])
  else if (cur.x !== first.x || cur.y !== first.y) out.push([cur, null, first])
  return out
}

/** One edge into the accumulation buffer: the signed area it covers in each pixel of each row it crosses. */
function line(acc: Float32Array, w: number, h: number, ax: number, ay: number, bx: number, by: number): void {
  if (ay === by) return
  const dir = ay < by ? 1 : -1
  let x0 = ax, y0 = ay, x1 = bx, y1 = by
  if (dir < 0) {
    ;[x0, y0, x1, y1] = [bx, by, ax, ay]
  }
  const dxdy = (x1 - x0) / (y1 - y0)
  let x = x0
  if (y0 < 0) {
    x -= y0 * dxdy
    y0 = 0
  }
  const yEnd = Math.min(h, Math.ceil(y1))
  const put = (i: number, v: number) => {
    if (i >= 0 && i < acc.length) acc[i] += v
  }
  for (let y = Math.max(0, Math.floor(y0)); y < yEnd; y++) {
    const row = y * w
    const dy = Math.min(y + 1, y1) - Math.max(y, y0)
    const xnext = x + dxdy * dy
    const d = dy * dir
    const xa = Math.max(0, Math.min(x, xnext)), xb = Math.min(w - 1, Math.max(x, xnext))
    const fl = Math.floor(xa), x0i = fl
    const ce = Math.ceil(xb), x1i = ce
    if (x1i <= x0i + 1) {
      const mid = 0.5 * (xa + xb) - fl
      put(row + x0i, d - d * mid)
      put(row + x0i + 1, d * mid)
    } else {
      const s = 1 / (xb - xa)
      const f0 = xa - fl
      const a0 = 0.5 * s * (1 - f0) * (1 - f0)
      const f1 = xb - ce + 1
      const am = 0.5 * s * f1 * f1
      put(row + x0i, d * a0)
      if (x1i === x0i + 2) put(row + x0i + 1, d * (1 - a0 - am))
      else {
        const a1 = s * (1.5 - f0)
        put(row + x0i + 1, d * (a1 - a0))
        for (let xi = x0i + 2; xi < x1i - 1; xi++) put(row + xi, d * s)
        const a2 = a1 + (x1i - x0i - 3) * s
        put(row + x1i - 1, d * (1 - a2 - am))
      }
      put(row + x1i, d * am)
    }
    x = xnext
  }
}
