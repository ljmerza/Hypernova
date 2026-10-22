// "Note: …" from the glasses (PROTOCOL.md, "Nebula"): an idea said on a walk or a ride, kept as a line
// in <Nebula>/notes.md with the time and the project it was said about. No Claude run: a note is for
// later. Newest last, under a heading per day, so the file reads like a notebook and stays plain
// Markdown anyone can edit.
import { appendFileSync, mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { log } from './util.ts'
import { nebulaInfo, nebulaRoot } from './nebula.ts'

export const NOTES_FILE = 'notes.md'
/** A note is a sentence or a few, said aloud: more is a mistake (a stuck microphone). */
export const NOTE_MAX = 4000

const fail = (status: number, code: string, message: string) => Object.assign(new Error(message), { status, code })

const pad = (n: number) => String(n).padStart(2, '0')
const dayOf = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const timeOf = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`

/** One line of plain text: line breaks and runs of spaces become one space. */
const oneLine = (s: string) => s.replace(/\s+/g, ' ').trim()

/**
 * Appends `text` to <Nebula>/notes.md as "- 14:05 · hypernova · the idea", under today's heading
 * (added when the last one in the file is another day's). Nebula and the file are made if they
 * aren't there yet. The time is the Mac's own, like every other time it writes.
 */
export function addNote(text: unknown, project: unknown, now = new Date()): { path: string; at: number; line: string } {
  const raw = typeof text === 'string' ? text : ''
  if (raw.length > NOTE_MAX) throw fail(413, 'too_long', 'That note is too long to keep.')
  const t = oneLine(raw)
  if (!t) throw fail(400, 'empty', 'Say what to note.')
  // The project is a name, nothing more: one line, no separators of ours, 80 characters at most.
  const p = typeof project === 'string' ? oneLine(project).replace(/·/g, '-').slice(0, 80) : ''
  const root = nebulaRoot()
  mkdirSync(root, { recursive: true })
  const file = join(root, NOTES_FILE)
  let existing = ''
  try {
    existing = readFileSync(file, 'utf8')
  } catch {}
  let head = existing ? '' : '# Notes\n\nIdeas said to the glasses, newest last: the time, the project in front of you, the idea.\n'
  const lastDay = [...existing.matchAll(/^## (\d{4}-\d{2}-\d{2})\s*$/gm)].at(-1)?.[1]
  if (lastDay !== dayOf(now)) head += `${existing && !existing.endsWith('\n') ? '\n' : ''}\n## ${dayOf(now)}\n\n`
  else if (existing && !existing.endsWith('\n')) head = '\n'
  const line = `- ${timeOf(now)}${p ? ` · ${p}` : ''} · ${t}`
  appendFileSync(file, `${head}${line}\n`)
  log('note saved', p || '(no project)', `${t.length} chars`)
  return { path: `${nebulaInfo().path}/${NOTES_FILE}`, at: now.getTime(), line }
}

/** `POST /v1/nebula/notes {text, project?}` → `{note: {path, at, line}}`. */
export async function notesApi(method: string, body: () => Promise<any>): Promise<{ note: ReturnType<typeof addNote> }> {
  if (method !== 'POST') throw fail(405, 'method', 'Add a note with POST.')
  const b = await body()
  return { note: addNote(b?.text, b?.project) }
}
