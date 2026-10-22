// PNG in plain TypeScript, for a Linux server: macOS draws and shrinks Telescope's pictures with
// AppKit and `sips` (draw.ts, index.ts), and a Linux machine has neither, nor a library the daemon
// could count on. Node's zlib does the compression; this file does the rest of the format: write an
// 8-bit RGB or RGBA picture, read the PNGs that adb, Chrome and charting tools make (8 or 16 bits,
// any colour type, not interlaced), and shrink one by averaging.
import { deflateSync, inflateSync } from 'node:zlib'

/** Pixels, top row first: 4 bytes a pixel (RGBA, straight alpha). */
export type Rgba = { width: number; height: number; data: Uint8Array }

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

function crc32(bufs: Uint8Array[]): number {
  let c = 0xffffffff
  for (const b of bufs) for (let i = 0; i < b.length; i++) c = CRC_TABLE[(c ^ b[i]!) & 0xff]! ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function chunk(type: string, body: Uint8Array): Buffer {
  const head = Buffer.alloc(8)
  head.writeUInt32BE(body.length, 0)
  head.write(type, 4, 'latin1')
  const tail = Buffer.alloc(4)
  tail.writeUInt32BE(crc32([head.subarray(4), body]), 0)
  return Buffer.concat([head, body, tail])
}

const paeth = (a: number, b: number, c: number) => {
  const p = a + b - c
  const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c)
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c
}

/**
 * The picture as a PNG: RGB when every pixel is opaque (a third smaller), else RGBA. Each row gets
 * the filter that leaves the smallest numbers (the usual heuristic), which is what makes a terminal's
 * flat background and a screenshot's large areas compress well.
 */
export function encodePng(img: Rgba): Buffer {
  const { width, height, data } = img
  let opaque = true
  for (let i = 3; i < data.length; i += 4) if (data[i] !== 255) {
    opaque = false
    break
  }
  const bpp = opaque ? 3 : 4
  const stride = width * bpp
  const raw = new Uint8Array(stride * height)
  for (let y = 0, o = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4
      raw[o++] = data[i]!
      raw[o++] = data[i + 1]!
      raw[o++] = data[i + 2]!
      if (!opaque) raw[o++] = data[i + 3]!
    }
  }
  const out = new Uint8Array((stride + 1) * height)
  const cand = [0, 1, 2, 3, 4].map(() => new Uint8Array(stride))
  for (let y = 0; y < height; y++) {
    const row = raw.subarray(y * stride, (y + 1) * stride)
    const up = y ? raw.subarray((y - 1) * stride, y * stride) : null
    let best = 0, bestSum = Infinity
    for (let f = 0; f < 5; f++) {
      const c = cand[f]!
      let sum = 0
      for (let i = 0; i < stride; i++) {
        const a = i >= bpp ? row[i - bpp]! : 0
        const b = up ? up[i]! : 0
        const d = i >= bpp && up ? up[i - bpp]! : 0
        const v = f === 0 ? row[i]! : f === 1 ? row[i]! - a : f === 2 ? row[i]! - b : f === 3 ? row[i]! - ((a + b) >> 1) : row[i]! - paeth(a, b, d)
        c[i] = v & 0xff
        sum += c[i]! < 128 ? c[i]! : 256 - c[i]!
      }
      if (sum < bestSum) {
        bestSum = sum
        best = f
      }
    }
    out[y * (stride + 1)] = best
    out.set(cand[best]!, y * (stride + 1) + 1)
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8
  ihdr[9] = opaque ? 2 : 6
  return Buffer.concat([SIGNATURE, chunk('IHDR', ihdr), chunk('IDAT', deflateSync(out, { level: 6 })), chunk('IEND', new Uint8Array(0))])
}

/** The most pixels a picture may have to be read here: a 100-megapixel PNG would take 400 MB. */
export const MAX_PIXELS = 40_000_000

/**
 * A PNG's pixels as RGBA, or null when it can't be read here (not a PNG, interlaced, damaged, too
 * large). Colour types 0, 2, 3, 4 and 6 at 8 bits, and 16-bit ones (their high byte); a palette's
 * transparency (tRNS) and a grey or RGB picture's transparent colour are honoured.
 */
export function decodePng(png: Buffer): Rgba | null {
  if (png.length < 33 || !png.subarray(0, 8).equals(SIGNATURE)) return null
  let width = 0, height = 0, depth = 0, type = 0, interlace = 0
  let palette: Buffer | null = null
  let trns: Buffer | null = null
  const idat: Buffer[] = []
  for (let at = 8; at + 12 <= png.length; ) {
    const len = png.readUInt32BE(at)
    const kind = png.toString('latin1', at + 4, at + 8)
    const body = png.subarray(at + 8, at + 8 + len)
    if (body.length !== len) return null
    if (kind === 'IHDR') {
      width = body.readUInt32BE(0)
      height = body.readUInt32BE(4)
      depth = body[8]!
      type = body[9]!
      interlace = body[12]!
    } else if (kind === 'PLTE') palette = body
    else if (kind === 'tRNS') trns = body
    else if (kind === 'IDAT') idat.push(body)
    else if (kind === 'IEND') break
    at += 12 + len
  }
  const channels = ({ 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 } as Record<number, number>)[type]
  if (!channels || !width || !height || width * height > MAX_PIXELS || interlace !== 0) return null
  if (!(depth === 8 || (depth === 16 && type !== 3) || (type === 3 && [1, 2, 4].includes(depth)))) return null
  if (type === 3 && !palette) return null
  let raw: Buffer
  try {
    raw = inflateSync(Buffer.concat(idat))
  } catch {
    return null
  }
  const bitsPerPixel = channels * depth
  const stride = Math.ceil((width * bitsPerPixel) / 8)
  const bpp = Math.max(1, bitsPerPixel >> 3)
  if (raw.length < (stride + 1) * height) return null
  // Undo each row's filter in place.
  const rows = new Uint8Array(stride * height)
  for (let y = 0; y < height; y++) {
    const f = raw[y * (stride + 1)]!
    const src = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1))
    const row = rows.subarray(y * stride, (y + 1) * stride)
    const up = y ? rows.subarray((y - 1) * stride, y * stride) : null
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? row[i - bpp]! : 0
      const b = up ? up[i]! : 0
      const c = i >= bpp && up ? up[i - bpp]! : 0
      const v = src[i]!
      row[i] = (f === 0 ? v : f === 1 ? v + a : f === 2 ? v + b : f === 3 ? v + ((a + b) >> 1) : f === 4 ? v + paeth(a, b, c) : v) & 0xff
    }
  }
  const data = new Uint8Array(width * height * 4)
  // A sample's 8 bits: the high byte of a 16-bit one.
  const step = depth === 16 ? 2 : 1
  const key = trns && (type === 0 || type === 2) ? Array.from({ length: trns.length / 2 }, (_, i) => trns!.readUInt16BE(i * 2)) : null
  for (let y = 0; y < height; y++) {
    const row = rows.subarray(y * stride, (y + 1) * stride)
    for (let x = 0; x < width; x++) {
      const o = (y * width + x) * 4
      if (type === 3) {
        const bit = x * depth
        const idx = depth === 8 ? row[x]! : (row[bit >> 3]! >> (8 - depth - (bit & 7))) & ((1 << depth) - 1)
        data[o] = palette![idx * 3] ?? 0
        data[o + 1] = palette![idx * 3 + 1] ?? 0
        data[o + 2] = palette![idx * 3 + 2] ?? 0
        data[o + 3] = trns && idx < trns.length ? trns[idx]! : 255
        continue
      }
      const s = x * channels * step
      const at = (k: number) => row[s + k * step]!
      const full = (k: number) => (step === 2 ? (row[s + k * 2]! << 8) | row[s + k * 2 + 1]! : row[s + k]!)
      if (type === 0 || type === 4) {
        data[o] = data[o + 1] = data[o + 2] = at(0)
        data[o + 3] = type === 4 ? at(1) : key && full(0) === key[0] ? 0 : 255
      } else {
        data[o] = at(0)
        data[o + 1] = at(1)
        data[o + 2] = at(2)
        data[o + 3] = type === 6 ? at(3) : key && full(0) === key[0] && full(1) === key[1] && full(2) === key[2] ? 0 : 255
      }
    }
  }
  return { width, height, data }
}

/** The picture on a solid colour (transparency flattened): a chart drawn on nothing reads on white. */
export function flatten(img: Rgba, bg: [number, number, number] = [255, 255, 255]): Rgba {
  const data = new Uint8Array(img.data.length)
  for (let i = 0; i < data.length; i += 4) {
    const a = img.data[i + 3]! / 255
    for (let k = 0; k < 3; k++) data[i + k] = Math.round(img.data[i + k]! * a + bg[k]! * (1 - a))
    data[i + 3] = 255
  }
  return { width: img.width, height: img.height, data }
}

/**
 * The picture at most [max] pixels on its longer side, each new pixel the average of the area it
 * covers (with alpha weighting, so transparent pixels don't darken edges). As it was when it fits.
 */
export function shrinkRgba(img: Rgba, max: number): Rgba {
  const k = max / Math.max(img.width, img.height)
  if (k >= 1) return img
  const w = Math.max(1, Math.round(img.width * k)), h = Math.max(1, Math.round(img.height * k))
  const sx = img.width / w, sy = img.height / h
  const data = new Uint8Array(w * h * 4)
  const acc = new Float64Array(5)
  for (let y = 0; y < h; y++) {
    const y0 = y * sy, y1 = y0 + sy
    for (let x = 0; x < w; x++) {
      const x0 = x * sx, x1 = x0 + sx
      acc.fill(0)
      for (let yy = Math.floor(y0); yy < Math.min(img.height, Math.ceil(y1)); yy++) {
        const wy = Math.min(yy + 1, y1) - Math.max(yy, y0)
        for (let xx = Math.floor(x0); xx < Math.min(img.width, Math.ceil(x1)); xx++) {
          const wgt = wy * (Math.min(xx + 1, x1) - Math.max(xx, x0))
          const i = (yy * img.width + xx) * 4
          const a = img.data[i + 3]! * wgt
          acc[0] += img.data[i]! * a
          acc[1] += img.data[i + 1]! * a
          acc[2] += img.data[i + 2]! * a
          acc[3] += a
          acc[4] += wgt
        }
      }
      const o = (y * w + x) * 4
      const alpha = acc[3]!
      data[o] = alpha ? Math.round(acc[0]! / alpha) : 0
      data[o + 1] = alpha ? Math.round(acc[1]! / alpha) : 0
      data[o + 2] = alpha ? Math.round(acc[2]! / alpha) : 0
      data[o + 3] = acc[4] ? Math.round(alpha / acc[4]!) : 0
    }
  }
  return { width: w, height: h, data }
}

/** A PNG at most [max] on its longer side; null when it can't be read here (the caller keeps it as it was). */
export function shrinkPng(png: Buffer, max: number): Buffer | null {
  const img = decodePng(png)
  if (!img) return null
  if (Math.max(img.width, img.height) <= max) return png
  return encodePng(shrinkRgba(img, max))
}
