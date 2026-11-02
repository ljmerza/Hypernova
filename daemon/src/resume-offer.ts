// Whether Claude Code's own /resume would offer a transcript, read as Claude Code 2.1.316 reads one:
// its first and last 64 KB and nothing else unless those hold no message. /resume passes over an agent's
// transcript (`isSidechain`), a teammate's (`teamName`), its background service's own (`sessionKind`
// daemon, daemon-worker), the Agent SDK's and `claude -p`'s (`entrypoint` sdk-*), a /loop session, a file
// with no message in it (only titles and such), and one that went on in another session
// (`continued-in`, when that other one has messages). None of this is documented: a shape not recognised
// counts as offered, as it does there. Nothing else in this file reads the disk but [offeredByResume].
import { closeSync, fstatSync, openSync, readSync } from 'node:fs'
import { dirname, join } from 'node:path'

const LITE = 64 * 1024
/** How far /resume looks for a message when the head and tail have none. */
const MESSAGE_SCAN = 16 * 1024 * 1024
const MESSAGE = '"parentUuid":'
const SDK_ENTRYPOINTS = new Set(['sdk-cli', 'sdk-ts', 'sdk-py'])

/** The first value of string field [key], as a raw look at the text finds it. */
function field(text: string, key: string): string | null {
  const m = new RegExp(`"${key}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"`).exec(text)
  return m ? m[1]! : null
}

/** The last value of string field [key]. */
function lastField(text: string, key: string): string | null {
  let last: string | null = null
  for (const m of text.matchAll(new RegExp(`"${key}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"`, 'g'))) last = m[1]!
  return last
}

/** The first thing the owner typed is /loop. */
function isLoop(head: string): boolean {
  for (const line of head.split('\n')) {
    if (!/"type"\s*:\s*"user"/.test(line) || line.includes('"tool_result"')) continue
    let o: any
    try {
      o = JSON.parse(line)
    } catch {
      return line.includes('<command-name>/loop</command-name>')
    }
    if (o?.type !== 'user' || o.isMeta === true || o.isCompactSummary === true || !o.message) continue
    const c = o.message.content
    const texts = typeof c === 'string' ? [c] : Array.isArray(c) ? c.flatMap((b: any) => (b?.type === 'text' && typeof b.text === 'string' ? [b.text] : [])) : []
    return texts.some((t: string) => t.includes('<command-name>/loop</command-name>'))
  }
  return false
}

/** The session this one went on in, when nothing was said here after it did. */
export function continuedIn(tail: string): string | null {
  if (!tail.includes('"continued-in"')) return null
  const lines = tail.split('\n')
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i]!
    const moved = line.includes('"continued-in"')
    if (!moved && !line.includes('"type":"user"') && !line.includes('"type":"assistant"')) continue
    try {
      const o = JSON.parse(line)
      if (moved && o?.type === 'continued-in' && typeof o.continuedInSessionId === 'string') return o.continuedInSessionId
      if (o?.type === 'assistant' && o.isApiErrorMessage !== true && typeof o.message?.stop_reason === 'string') return null
      if (o?.type === 'user' && o.isMeta !== true && !line.includes('"tool_result"')) return null
    } catch {}
  }
  return null
}

/** What /resume decides from a transcript's head and tail alone; a message is looked for separately. */
export function offeredFromText(head: string, tail: string): boolean {
  if (/"isSidechain"\s*:\s*true/.test(head)) return false
  if (field(head, 'teamName')) return false
  const firstMessage = head.split('\n').find((l) => l.includes(MESSAGE)) ?? head
  const kind = field(firstMessage, 'sessionKind')
  if (kind === 'daemon' || kind === 'daemon-worker') return false
  if (SDK_ENTRYPOINTS.has(field(head, 'entrypoint') ?? lastField(tail, 'entrypoint') ?? '')) return false
  return !isLoop(head)
}

function headAndTail(fd: number, size: number): { head: string; tail: string } {
  const read = (at: number) => {
    const buf = Buffer.alloc(Math.min(LITE, size - at))
    const n = readSync(fd, buf, 0, buf.length, at)
    return buf.subarray(0, n).toString('utf8')
  }
  return { head: read(0), tail: read(Math.max(0, size - LITE)) }
}

function hasMessage(fd: number, size: number, head: string, tail: string): boolean {
  if (head.includes(MESSAGE) || tail.includes(MESSAGE)) return true
  if (size <= LITE) return false
  const buf = Buffer.alloc(1024 * 1024)
  for (let at = 0; at < Math.min(size, MESSAGE_SCAN); at += buf.length - MESSAGE.length) {
    const n = readSync(fd, buf, 0, buf.length, at)
    if (n <= 0) return false
    if (buf.subarray(0, n).includes(MESSAGE)) return true
  }
  return true
}

/** Whether /resume would offer the transcript at [path]. One that can't be read: no. */
export function offeredByResume(path: string): boolean {
  let fd: number | null = null
  try {
    fd = openSync(path, 'r')
    const size = fstatSync(fd).size
    if (!size) return false
    const { head, tail } = headAndTail(fd, size)
    if (!offeredFromText(head, tail) || !hasMessage(fd, size, head, tail)) return false
    const next = continuedIn(tail)
    return !(next && /^[\w-]{1,64}$/.test(next) && transcriptHasMessage(join(dirname(path), `${next}.jsonl`)))
  } catch {
    return false
  } finally {
    if (fd != null) closeSync(fd)
  }
}

function transcriptHasMessage(path: string): boolean {
  let fd: number | null = null
  try {
    fd = openSync(path, 'r')
    const size = fstatSync(fd).size
    if (!size) return false
    const { head, tail } = headAndTail(fd, size)
    return hasMessage(fd, size, head, tail)
  } catch {
    return false
  } finally {
    if (fd != null) closeSync(fd)
  }
}
