// "Rewind to here" (docs/PROTOCOL.md, "Rewind"): the conversation goes back to just before one of
// the owner's messages, and goes on from there; the message comes back to the phone's prompt box
// to change and send again.
//
// The copy replaces the original on every screen (owner, 2026-11-01). The Agent SDK copies the
// transcript up to the message before it into a new session (no claude process runs, nothing is
// typed into a terminal), for every kind of session alike, and that session takes on all the card
// and the session show. Then the original is stopped (its shared terminal, its `claude agents` job,
// its SDK turn) and hidden: on no list, its transcript untouched on disk (rewound.ts). The copy
// comes first, so a stop that fails loses nothing: the rewind stands, and the original stays where
// it is. Claude Code's own /rewind in a terminal can also put files back; this doesn't, and the
// phone says so. Without Claude Code's SDK (Setup fetches it, sdk.ts) a rewind answers 409
// `sdk_missing`, with nothing touched.
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { agentJobOf, ensureParsed, getSession, isLiveTerminal, replaceSession, resolveSession, transcriptPathFor, upsertSession, view, type Session } from './hub.ts'
import { endSessionAgents } from './agents.ts'
import { agentJobCommand } from './claude-agents.ts'
import { parseTranscript, squash, type Ev } from './normalize.ts'
import { interrupt } from './runner.ts'
import { addLink, linkOf } from './switch-links.ts'
import { getManaged, getProvider, isSdk, saveManaged, settings } from './store.ts'
import { endTermAndWait, termChildPid, termOf } from './terms.ts'
import { log } from './util.ts'
import type { Attachment } from './attachments.ts'
import { SdkMissingError, claudeSdk, type ClaudeSdk } from './sdk.ts'

/**
 * How long the original gets to stop. A phone's request gives up after 30 s, and the copy is made
 * by then: past this the rewind stands, and the phone hears why two cards show.
 */
const STOP_MS = 20_000

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

/** What stops an original: the Delete and Stop routes' own ways (http.ts); tests bring their own. */
export type Stops = {
  endTerm: (termId: string) => Promise<boolean>
  stopJob: (job: string) => Promise<{ ok: boolean; said: string }>
  interrupt: (sessionId: string) => Promise<void>
}
const realStops: Stops = { endTerm: (id) => endTermAndWait(id), stopJob: (job) => agentJobCommand('stop', job), interrupt }

/** Stop whatever runs session [rec]: null when nothing does any more, else why it stays, in a sentence's words. */
async function stopOriginal(rec: Rec, stops: Stops): Promise<string | null> {
  const id = rec.id
  const tid = termOf(id)
  const job = settings.claudeAgents === true ? agentJobOf(id) : null
  // Its holder goes first, as on Delete: for a job it only watched, and would hold the job open.
  if (tid && !(await stops.endTerm(tid))) return 'The earlier session’s terminal didn’t close'
  if (job) {
    // A job with no process has nothing to stop (`claude stop` may say so): only one that runs can fail.
    const running = rec.pid != null
    if (running || ['starting', 'working', 'needs_you'].includes(rec.state)) {
      const r = await stops.stopJob(job)
      if (!r.ok && running) {
        log('rewind: the job didn’t stop', job, r.said)
        return 'claude agents didn’t stop the earlier session'
      }
    }
    return null
  }
  if (isSdk(id)) {
    await stops.interrupt(id)
    return null
  }
  // Open in a terminal nobody here holds: there's no clean way to close it from here.
  if (!tid && isLiveTerminal(id)) return 'The earlier session is open in a terminal Hypernova doesn’t hold'
  return null
}

/** Session [to] replaces the stopped session [rec] on every screen: nothing of [rec] waits on anyone. */
function replaceOriginal(rec: Rec, to: string, pid: number | null): void {
  const id = rec.id
  // One that came from another agent: the session that replaced it came from there too.
  const from = linkOf(id).from
  if (from) addLink(from, to)
  resolveSession(id)
  endSessionAgents(id)
  replaceSession(id, to, pid)
}

/**
 * Rewind session [rec] to just before its message [eventId]. [shared]: this Mac runs the phone's
 * sessions in shared terminals, so the new one continues in one too once it gets a prompt.
 * Returns the new session, the message (its text and files) for the prompt box, and
 * [originalStays]: null when the original is gone from every list, else the words for why not.
 */
export async function rewindTo(rec: Rec, eventId: string, shared: boolean, stops: Stops = realStops): Promise<{ session: Session; text: string; files: Attachment[]; originalStays: string | null }> {
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

  // The same session to the owner: its title, exactly (a "(rewound)" an older version added stays too).
  const title = rec.title
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
  // The same session to the owner: started when it started, on what it ran on, with what a switch
  // of agents carried over and an automation's badge and rules. Last active now, as one just used.
  const createdAt = rec.createdAt
  // A record of its own, as for a session the phone started: on a Mac with shared terminals its
  // first prompt starts a terminal resuming it, else the SDK resumes it.
  saveManaged({
    id, cwd: rec.cwd, providerId: rec.providerId, model, permissionMode: rec.permissionMode, createdAt, title,
    ...(shared ? { engine: 'term' as const } : {}),
    ...(m?.context ? { context: m.context } : {}),
    ...(rec.effort ? { effort: rec.effort } : {}),
    ...(m?.automation ? { automation: m.automation } : {}),
    ...(m?.allowedTools ? { allowedTools: m.allowedTools } : {}),
    ...(m?.appendSystemPrompt ? { appendSystemPrompt: m.appendSystemPrompt } : {}),
  })
  // The card shows the model it last ran on, whatever the record asks for next.
  const r = upsertSession(id, { cwd: rec.cwd, transcriptPath, origin: 'hypernova', state: 'idle', title, providerId: rec.providerId, model: rec.model ?? model, effort: rec.effort, permissionMode: rec.permissionMode, createdAt, lastActivityAt: now, automation: rec.automation, costUsd: rec.costUsd })
  ensureParsed(r)
  // By hand: both ids in full, to find the original's transcript (docs/PROTOCOL.md, "Rewind").
  log('rewound', rec.id, 'to before', uuid, 'as', id, 'its transcript stays at', rec.transcriptPath)

  // The copy is safe: now the original goes. Whatever goes wrong from here, nothing is lost.
  const pid = rec.pid ?? (termOf(rec.id) ? termChildPid(termOf(rec.id)!) : null)
  let timer: NodeJS.Timeout | undefined
  const late = new Promise<string>((done) => {
    timer = setTimeout(() => done('The earlier session didn’t stop in time'), STOP_MS)
  })
  const why = await Promise.race([stopOriginal(rec, stops), late]).catch((e) => {
    log('rewind: stopping the original failed', rec.id, e)
    return 'The earlier session couldn’t be stopped'
  })
  clearTimeout(timer)
  if (why === null) replaceOriginal(rec, id, pid)
  else log('rewound, the original stays:', rec.id.slice(0, 8), why)
  return { session: view(r), text: ev.text, files: ev.files ?? [], originalStays: why === null ? null : `Rewound. ${why}, so it stays on Home too. Your message is back in the box.` }
}
