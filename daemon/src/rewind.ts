// "Rewind to here" (docs/PROTOCOL.md, "Rewind"): the conversation goes back to just before one of
// the owner's messages, and goes on from there in a new session; the message comes back to the
// phone's prompt box to change and send again.
//
// It forks rather than cutting the session in place, for every kind of session alike: the Agent
// SDK copies the transcript up to the message before it into a new one (no claude process runs,
// nothing is typed into a terminal), so a session that's working, or open in a terminal on the
// Mac, is never touched, and nothing is lost: the original stays as it was. Claude Code's own
// /rewind in a terminal can also put files back; this doesn't, and the phone says so. Without
// Claude Code's SDK (Setup fetches it, sdk.ts) a rewind answers 409 `sdk_missing`.
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { ensureParsed, getSession, transcriptPathFor, upsertSession, view, type Session } from './hub.ts'
import { parseTranscript, squash, type Ev } from './normalize.ts'
import { getManaged, getProvider, saveManaged } from './store.ts'
import { log, trimText } from './util.ts'
import type { Attachment } from './attachments.ts'
import { SdkMissingError, claudeSdk, type ClaudeSdk } from './sdk.ts'

export class RewindError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

type Rec = NonNullable<ReturnType<typeof getSession>>
type UserEv = Extract<Ev, { kind: 'user' }>

/**
 * The transcript entry the message [ev] is (its uuid). A session the daemon runs names its prompts
 * itself (`u:…`), so those are found by their text: the same occurrence of the same words.
 */
function entryOf(events: Ev[], ev: UserEv, transcript: string): string | null {
  const logged = parseTranscript(transcript).events.filter((e): e is UserEv => e.kind === 'user')
  if (logged.some((e) => e.id === ev.id)) return ev.id
  const same = (e: UserEv) => squash(e.text) === squash(ev.text)
  const nth = events.slice(0, events.indexOf(ev)).filter((e): e is UserEv => e.kind === 'user' && same(e)).length
  return logged.filter(same)[nth]?.id ?? null
}

/** The entry just before [uuid] in the conversation (its parent), undefined when it isn't there. */
function parentOf(transcript: string, uuid: string): string | null | undefined {
  for (const line of transcript.split('\n')) {
    if (!line.includes(uuid)) continue
    try {
      const o = JSON.parse(line)
      // A prompt typed while Claude worked is logged as an attachment carrying its uuid.
      if (o.uuid === uuid || o.attachment?.source_uuid === uuid) return typeof o.parentUuid === 'string' ? o.parentUuid : null
    } catch {}
  }
  return undefined
}

/**
 * Rewind session [rec] to just before its message [eventId]. [shared]: this Mac runs the phone's
 * sessions in shared terminals, so the new one continues in one too once it gets a prompt.
 * Returns the new session and the message (its text and files) for the prompt box.
 */
export async function rewindTo(rec: Rec, eventId: string, shared: boolean): Promise<{ session: Session; text: string; files: Attachment[] }> {
  const events = ensureParsed(rec).events
  const ev = events.find((e): e is UserEv => e.id === eventId && e.kind === 'user')
  if (!ev) throw new RewindError(404, 'no_message', 'That message isn’t in this session any more.')
  if (!existsSync(rec.transcriptPath)) throw new RewindError(409, 'no_transcript', 'Claude Code hasn’t saved this conversation yet. Try again in a moment.')
  const transcript = readFileSync(rec.transcriptPath, 'utf8')
  const uuid = entryOf(events, ev, transcript)
  if (!uuid) throw new RewindError(409, 'not_found', 'Couldn’t find that message in Claude Code’s copy of the conversation.')
  const parent = parentOf(transcript, uuid)
  if (parent === undefined) throw new RewindError(409, 'not_found', 'Couldn’t find that message in Claude Code’s copy of the conversation.')
  if (parent === null) throw new RewindError(409, 'first_message', 'That’s where the conversation starts. Start a new session instead.')

  const title = trimText(`${rec.title} (rewound)`, 80) ?? 'Rewound session'
  let forkSession: ClaudeSdk['forkSession']
  try {
    forkSession = (await claudeSdk()).forkSession
  } catch (e) {
    if (e instanceof SdkMissingError) throw new RewindError(e.status, e.code, e.message)
    throw e
  }
  let id: string
  try {
    id = (await forkSession(rec.id, { dir: rec.cwd, upToMessageId: parent, title })).sessionId
  } catch (e) {
    log('rewind failed', rec.id, e)
    throw new RewindError(409, 'fork_failed', 'Claude Code couldn’t go back to that point in this conversation.')
  }
  // The SDK writes next to the original (the same project folder).
  const beside = join(dirname(rec.transcriptPath), `${id}.jsonl`)
  const transcriptPath = existsSync(beside) ? beside : transcriptPathFor(rec.cwd, id)
  const m = getManaged(rec.id)
  // The model it ran: a session on the built-in provider keeps the one it used; another
  // provider's names are its own, so only what was asked of it.
  const model = m?.model ?? (getProvider(rec.providerId).builtIn ? rec.model : null) ?? null
  const now = Date.now()
  // A record of its own, as for a session the phone started: on a Mac with shared terminals its
  // first prompt starts a terminal resuming it, else the SDK resumes it.
  saveManaged({ id, cwd: rec.cwd, providerId: rec.providerId, model, permissionMode: rec.permissionMode, createdAt: now, title, ...(shared ? { engine: 'term' as const } : {}) })
  const r = upsertSession(id, { cwd: rec.cwd, transcriptPath, origin: 'hypernova', state: 'idle', title, providerId: rec.providerId, model, permissionMode: rec.permissionMode, createdAt: now, lastActivityAt: now })
  ensureParsed(r)
  log('rewound', rec.id.slice(0, 8), 'to before', uuid.slice(0, 8), 'as', id.slice(0, 8))
  return { session: view(r), text: ev.text, files: ev.files ?? [] }
}
