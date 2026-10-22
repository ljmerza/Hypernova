// Files attached on the phone (docs/PROTOCOL.md, "Attachments"). They're saved on the Mac and
// handed to Claude Code as @"path" mentions before the prompt: Claude Code reads a mentioned
// file itself (images as images, PDFs as documents), with no permission prompt, in a shared
// terminal, an SDK session and a first prompt alike.
import { createWriteStream, existsSync, mkdirSync, readdirSync, realpathSync, rmSync, statSync } from 'node:fs'
import { basename, extname, join, resolve, sep } from 'node:path'
import { randomBytes } from 'node:crypto'
import { once } from 'node:events'
import { SUPPORT, log } from './util.ts'
import { bareMime } from './mime.ts'

export const ATTACH_DIR = join(SUPPORT, 'attachments')
/** As long as Claude Code keeps transcripts by default. */
const KEEP_MS = 30 * 24 * 3600_000
export const MAX_ATTACHMENT = 30 * 1024 * 1024

export type Attachment = { path: string; name: string; mime: string | null; size: number }

/** Its type without parameters ("text/plain"), from the one table (mime.ts). */
const mimeOf = bareMime

/**
 * A file name that's safe on disk and inside @"…": no quotes, slashes or control characters,
 * and no direction overrides (U+202E can make "photo<U+202E>gnp.exe" display as "photoexe.png").
 */
function safeName(name: string): string {
  let n = basename(name.replace(/\\/g, '/')).normalize('NFC')
  n = n.replace(/["\x00-\x1f\x7f-\x9f:\u2028\u2029\u202a-\u202e\u2066-\u2069]/g, '-').replace(/^[.\s]+/, '').trim()
  if (n.length > 120) {
    const ext = extname(n).slice(0, 12)
    n = n.slice(0, 120 - ext.length) + ext
  }
  return n || 'file'
}

const fail = (status: number, code: string, message: string) => Object.assign(new Error(message), { status, code })

/**
 * Save an upload as it arrives (never whole in memory), in a folder of its own: two photos
 * can both be called image.jpg.
 */
export async function saveAttachment(name: string, body: AsyncIterable<Buffer>): Promise<Attachment> {
  const dir = join(ATTACH_DIR, randomBytes(6).toString('hex'))
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const path = join(dir, safeName(name))
  const out = createWriteStream(path, { mode: 0o600 })
  let size = 0
  try {
    try {
      for await (const chunk of body) {
        size += chunk.length
        if (size > MAX_ATTACHMENT) throw fail(413, 'too_large', 'That file is too big to attach (30 MB at most).')
        if (!out.write(chunk)) await once(out, 'drain')
      }
    } catch (e: any) {
      // The phone gave up (left the screen, lost the network): nothing to answer.
      throw e?.status ? e : fail(400, 'aborted', 'The upload stopped.')
    }
    await new Promise<void>((res, rej) => out.end((e?: Error | null) => (e ? rej(e) : res())))
    if (!size) throw fail(400, 'empty_file', 'That file is empty.')
  } catch (e) {
    out.destroy()
    rmSync(dir, { recursive: true, force: true })
    throw e
  }
  return { path, name: basename(path), mime: mimeOf(path), size }
}

/** The real path of [p] if it's an attachment that's still there, else null. */
export function attachmentFile(p: string): string | null {
  try {
    const root = realpathSync(ATTACH_DIR)
    const full = realpathSync(resolve(p))
    return full.startsWith(root + sep) && statSync(full).isFile() ? full : null
  } catch {
    return null
  }
}

/**
 * Claude reading a file the owner attached (a mentioned PDF is read with the Read tool, which
 * would ask first: it's outside the project). The owner sent it, so it needs no approval.
 */
export function readsAttachment(tool: unknown, input: any): boolean {
  return tool === 'Read' && typeof input?.file_path === 'string' && attachmentFile(input.file_path) !== null
}

// pdftoppm (poppler) renders the pages Read's `pages` asks for. Without it Read can't take
// pages of a PDF, but reads a whole one (as a document) just fine.
let popplerSeen = 0
let poppler = false
function hasPoppler(): boolean {
  if (Date.now() - popplerSeen > 60_000) {
    popplerSeen = Date.now()
    const dirs = ['/opt/homebrew/bin', '/usr/local/bin', ...(process.env.PATH ?? '').split(':').filter(Boolean)]
    poppler = dirs.some((d) => existsSync(join(d, 'pdftoppm')))
  }
  return poppler
}
const readWhole = new Set<string>()
/**
 * The input to read an attachment with, if it should change: on a Mac without poppler, the
 * first read of each attached PDF drops `pages` so the whole PDF arrives (Claude asks for
 * pages even of a short one). A second try with pages goes through as asked, so a PDF too
 * long to read whole can't go round in circles.
 */
export function attachmentReadInput(session: string, input: any): Record<string, unknown> | null {
  if (!input?.pages || !/\.pdf$/i.test(String(input.file_path)) || hasPoppler()) return null
  const k = `${session}|${input.file_path}`
  if (readWhole.has(k)) return null
  if (readWhole.size > 2000) readWhole.clear()
  readWhole.add(k)
  const { pages: _pages, ...rest } = input
  return rest
}

/**
 * The prompt Claude Code gets: the files as @-mentions, then the text. Mentions go first, and
 * a mention-only prompt ends in a space, so the input box's file suggestions never open (they
 * would take the Enter that sends it).
 */
export function withAttachments(text: string, files: string[]): string {
  if (!files.length) return text
  const refs = files.map((f) => `@"${f}"`).join(' ')
  return text.trim() ? `${refs}\n\n${text}` : `${refs} `
}

/** Our mentions at the start of a prompt, split back out of it for the phone's chat. */
export function splitAttachments(text: string): { text: string; files: Attachment[] } {
  const files: Attachment[] = []
  let rest = text
  const re = /^@"([^"\n]+)"[ \t]*\n{0,2}/
  for (let m = re.exec(rest); m && m[1]!.startsWith(ATTACH_DIR + sep); m = re.exec(rest)) {
    const path = m[1]!
    let size = 0
    try {
      size = statSync(path).size
    } catch {}
    files.push({ path, name: basename(path), mime: mimeOf(path), size })
    rest = rest.slice(m[0].length)
  }
  return files.length ? { text: rest.trim(), files } : { text, files }
}

/** Remove attachments older than Claude Code's own transcripts. */
export function reapAttachments(): void {
  if (!existsSync(ATTACH_DIR)) return
  let n = 0
  for (const d of readdirSync(ATTACH_DIR)) {
    const dir = join(ATTACH_DIR, d)
    try {
      if (Date.now() - statSync(dir).mtimeMs > KEEP_MS) {
        rmSync(dir, { recursive: true, force: true })
        n++
      }
    } catch {}
  }
  if (n) log(`removed ${n} old attachment${n === 1 ? '' : 's'}`)
}
