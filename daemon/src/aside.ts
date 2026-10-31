// Side questions from the phone (docs/PROTOCOL.md, "Side questions"): Claude Code's own
// `/btw <question>` ("Ask a quick side question without interrupting the main conversation"),
// typed into the session's shared terminal (a `claude agents` job through `claude attach`), while
// Claude works too: Claude Code runs /btw at once, beside the turn, never queued behind it.
//
// Its answer never reaches the transcript: Claude Code draws it in a panel in place of the input
// box (its own `btwHistory` keeps it in memory only). So the daemon reads it off the terminal's
// screen as it reads menus (terms.ts, watchMenus), keeps the last one per session for the phone to
// ask for, and once it has it, closes the panel with Space (never Esc: with the panel gone, Esc
// would stop Claude's turn) so the box is back for the next prompt.
//
// The panel as Claude Code 2.1.314 draws it (read from its code, then seen on the owner's phone
// 2026-10-30, test/fixtures/btw-screens):
//
//   /btw <an earlier question>                    up to five, dim, "(+N earlier /btw)" above them
//   /btw <the question>
//
//     ⠋ Answering…                                then the answer (Markdown), scrolling past
//                                                 max(5, rows − 11 − earlier lines) lines
//   ↑/↓ to browse · c to copy · f to fork · x to clear history · Esc to       ← 4
//   close ·                                                                agents
//
// The footer wraps on a narrow terminal, and the status line's right side shares its rows.
import { randomUUID } from 'node:crypto'
import { agentJobOf, getSession, pendingApprovals } from './hub.ts'
import { attachAgentsTerm, attachDrifted, pressKeys, termOf, termScreen, typeInto } from './terms.ts'
import { isSdk, settings } from './store.ts'
import { isCodex } from './codex/threads.ts'
import { log } from './util.ts'

/**
 * A side question and how it stands: `asking` until its answer is on the screen; `answered`;
 * `failed` (Claude Code's own words in [error]); `unseen`: no answer could be read off the screen
 * (the panel never showed, the terminal went or showed another session, or it took too long), so
 * the phone says to open the terminal. [clipped]: the answer filled the panel, so it may go on
 * past what the screen shows.
 */
export type Aside = {
  id: string
  question: string
  state: 'asking' | 'answered' | 'failed' | 'unseen'
  answer: string | null
  error: string | null
  clipped: boolean
  askedAt: number
  at: number
}

export class AsideError extends Error {
  status: number
  code: string
  extra?: Record<string, unknown>
  constructor(status: number, code: string, message: string, extra?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.code = code
    this.extra = extra
  }
}

/** The longest question taken (a side question is short; Claude Code puts it on one line). */
export const ASIDE_MAX = 2000
const POLL_MS = 500
/** How long an answer is waited for (Claude Code itself gives a side question minutes). */
const ANSWER_MS = 4 * 60_000

/** The last side question per session. */
const asides = new Map<string, Aside>()

/** Session [id]'s last side question, or null. */
export function asideOf(id: string): Aside | null {
  return asides.get(id) ?? null
}

/**
 * Ask session [id] `/btw <question>`: typed into its shared terminal at once, whether Claude works
 * or not (never held, never queued: that's the point of it), never a fork or a resume. Resolves
 * once it's typed, with the aside `asking`; its answer is read off the screen after that.
 * Throws [AsideError]: 400 `empty`, `too_long`; 404 `no_session`; 409 `unsupported_for_agent`
 * (Codex), `asking` (the last one is still being answered), `needs_answer`, `no_terminal`,
 * `terminal_busy` (no `claude attach`, or a holder that shows another session), and the prompt
 * route's `waiting`, `typing` (with `pending`), `starting`, `not_sent`.
 */
export async function askAside(id: string, question: string, o: { answerMs?: number; pollMs?: number } = {}): Promise<Aside> {
  const q = question.replace(/\s+/g, ' ').trim()
  if (!q) throw new AsideError(400, 'empty', 'Write a question first.')
  if (q.length > ASIDE_MAX) throw new AsideError(400, 'too_long', 'That’s too long for a side question.')
  const rec = getSession(id)
  if (!rec) throw new AsideError(404, 'no_session', 'That session no longer exists.')
  if (isCodex(id)) throw new AsideError(409, 'unsupported_for_agent', 'Codex sessions can’t do this.')
  const last = asides.get(id)
  if (last?.state === 'asking') throw new AsideError(409, 'asking', 'Claude is still answering your last side question.')
  if (pendingApprovals().some((a) => a.sessionId === id)) throw new AsideError(409, 'needs_answer', 'Claude is waiting for your answer above. Answer it first.')
  let tid = termOf(id)
  // A `claude agents` job: through `claude attach`, as a prompt goes (a holder that drifted is replaced).
  const job = settings.claudeAgents === true && (!tid || attachDrifted(tid, id)) ? agentJobOf(id) : null
  if (job) {
    tid = await attachAgentsTerm(id, rec.cwd, job)
    if (!tid) throw new AsideError(409, 'terminal_busy', 'The background session didn’t open. Try again in a moment.')
  }
  if (!tid) throw new AsideError(409, 'no_terminal', isSdk(id) ? 'Side questions need a session in a terminal.' : 'This session isn’t running in a shared terminal.')
  // Still another session's view (`claude attach` that opened on the agent list): typing there would ask another job.
  if (attachDrifted(tid, id)) throw new AsideError(409, 'terminal_busy', 'The terminal shows another session. Try again in a moment.')
  const r = await typeInto(tid, `/btw ${q}`)
  if (!r.ok) {
    const why: Record<string, [string, string]> = {
      no_input: ['waiting', 'Claude is showing something in the terminal (a question or a menu). Open the terminal to answer it.'],
      busy_input: ['typing', 'There’s unsent text in this session’s terminal on the Mac. Send or clear it there first.'],
      not_started: ['starting', 'The session is still starting. Try again in a moment.'],
      not_seen: ['not_sent', 'Your question is in the terminal’s input box but wasn’t sent. Open the terminal to check it.'],
      not_sent: ['not_sent', 'Your question is in the terminal’s input box but wasn’t sent. Open the terminal to send it.'],
      unreachable: ['terminal_busy', 'The terminal didn’t answer. Try again in a moment.'],
    }
    const [code, message] = why[r.reason ?? 'unreachable'] ?? why.unreachable!
    throw new AsideError(409, code, message, r.reason === 'busy_input' && r.text ? { pending: r.text } : undefined)
  }
  const now = Date.now()
  const a: Aside = { id: randomUUID(), question: q, state: 'asking', answer: null, error: null, clipped: false, askedAt: now, at: now }
  asides.set(id, a)
  void watchAside(id, tid, a, o.answerMs ?? ANSWER_MS, o.pollMs ?? POLL_MS)
  return a
}

/** Read [a]'s answer off holder [tid]'s screen until it's there, then close the panel. */
async function watchAside(id: string, tid: string, a: Aside, answerMs: number, pollMs: number): Promise<void> {
  const mine = () => asides.get(id)?.id === a.id
  const settle = (p: Partial<Aside>) => {
    if (mine()) asides.set(id, { ...a, ...p, at: Date.now() })
  }
  const t0 = Date.now()
  try {
    while (mine() && Date.now() - t0 < answerMs) {
      await new Promise((ok) => setTimeout(ok, pollMs))
      // The terminal went, or shows another session now: nothing more of it is this session's.
      if (termOf(id) !== tid || attachDrifted(tid, id)) break
      const s = await termScreen(tid)
      const p = s && readAside(s.text, a.question)
      if (!p || p.state === 'asking') continue
      // The answer wraps a few columns short of its indent's room: 75 to 77 of 84 on the owner's phone.
      settle(p.state === 'answered' ? { state: 'answered', answer: reflow(p.text, s.cols - 7), clipped: p.clipped } : { state: 'failed', error: p.text.replace(/\s+/g, ' ').trim() })
      // Space closes the panel (so does Esc, which with the panel gone would stop Claude's turn),
      // only while it's still on the screen and still this session's.
      const again = await termScreen(tid)
      if (again && readAside(again.text, a.question) && termOf(id) === tid && !attachDrifted(tid, id)) pressKeys(tid, ' ')
      return
    }
  } catch (e) {
    log('side question: reading the answer failed', e)
  }
  if (mine() && asides.get(id)?.state === 'asking') settle({ state: 'unseen' })
}

/** What the /btw panel on a screen says for [question], or null when it isn't showing it. */
export type AsideScreen = { state: 'asking' | 'answered' | 'failed'; text: string; clipped: boolean }

const squash = (s: string) => s.replace(/\s+/g, '')
const FOOTER = /\besc to close\b/i
const ANSWERED = /\bc to copy\b|copied to clipboard/i
const PENDING = /Answering…|retrying in \d+s/
// The panel's own lines sit two columns in; its answer four.
const QUESTION = /^ {0,3}\/btw\s+(.*)$/
const EARLIER = /^ {0,3}\(\+\d+ earlier \/btw\)\s*$/
// A row of the footer's hints ("↑/↓ to browse · c to copy · … · Esc to"), two columns in.
const HINTS = /^ {0,3}\S/
const HINT = /(?:^|·)\s*\S+ to(?:\s|$)/
/** A row without what Claude Code's status line draws at its right ("← 4 agents"), trimmed. */
const left = (l: string) => l.trim().split(/\s{3,}/)[0] ?? ''

/**
 * The footer under the panel: its first row and its words, the rows it wrapped onto joined (on a
 * narrow terminal "Esc to" / "close ·", beside the status line's own words). Null without one.
 */
function footerOf(lines: string[]): { at: number; rows: number; text: string } | null {
  const hints = (i: number) => HINTS.test(lines[i]!) && !QUESTION.test(lines[i]!) && !EARLIER.test(lines[i]!) && HINT.test(left(lines[i]!))
  for (let i = lines.length - 1; i >= 0; i--) {
    if (!hints(i)) continue
    let at = i
    while (at > 0 && hints(at - 1)) at--
    // The rows it wrapped onto, up to two, until a blank one.
    let end = i
    while (end + 1 < lines.length && end < i + 2 && lines[end + 1]!.trim()) end++
    const text = lines.slice(at, end + 1).map(left).join(' ').replace(/\s+/g, ' ')
    if (FOOTER.test(text)) return { at, rows: end - at + 1, text }
  }
  return null
}

/**
 * The /btw panel on [screen] (plain lines, as a holder sends them) for [question]: the lowest
 * "/btw …" line above the footer must be that question (its line may be cut short with "…"),
 * else it's another question's panel. Its answer once the footer offers to copy it; Claude Code's
 * error words when it has neither an answer nor "Answering…"; `asking` before.
 */
export function readAside(screen: string, question: string): AsideScreen | null {
  const lines = screen.split('\n')
  const footer = footerOf(lines)
  if (!footer) return null
  const foot = footer.at
  let at = -1
  for (let i = foot - 1; i >= 0 && at < 0; i--) if (QUESTION.test(lines[i]!)) at = i
  if (at < 0) return null
  const shown = squash(QUESTION.exec(lines[at]!)![1]!).replace(/…$/, '')
  if (!shown || !squash(question).startsWith(shown)) return null
  let body = lines.slice(at + 1, foot).map((l) => l.trimEnd())
  while (body.length && !body[0]) body.shift()
  while (body.length && !body[body.length - 1]) body.pop()
  const indent = Math.min(...body.filter(Boolean).map((l) => /^ */.exec(l)![0].length))
  body = body.map((l) => l.slice(Number.isFinite(indent) ? indent : 0))
  const text = body.join('\n')
  if (ANSWERED.test(footer.text)) {
    let earlier = 0
    for (let i = at - 1; i >= 0 && (QUESTION.test(lines[i]!) || EARLIER.test(lines[i]!)); i--) earlier++
    // A footer that wrapped takes its extra rows from the answer's (a guess: Claude Code sizes the
    // answer by rows alone), so it counts as clipped sooner rather than later.
    return { state: 'answered', text, clipped: body.length >= Math.max(5, lines.length - 11 - earlier - (footer.rows - 1)) }
  }
  if (!text.trim() || PENDING.test(text)) return { state: 'asking', text: '', clipped: false }
  return { state: 'failed', text, clipped: false }
}

/**
 * [text] as written rather than as drawn [width] columns wide: a line the terminal broke because
 * the next word didn't fit is joined back to the one before. Lists, headings, quotes, code fences
 * and blank lines start lines of their own.
 */
export function reflow(text: string, width: number): string {
  const own = /^\s*([-*•+]\s|\d+[.)]\s|#|>|```|\|)/
  const out: string[] = []
  // The length of the last line as drawn, which the next word didn't fit after.
  let drawn = 0
  for (const line of text.split('\n')) {
    const word = line.trim().split(/\s/)[0] ?? ''
    const last = out.length ? out[out.length - 1]! : ''
    if (last.trim() && line.trim() && !own.test(line) && drawn + 1 + word.length > width - 2) out[out.length - 1] = `${last.trimEnd()} ${line.trim()}`
    else out.push(line.trimEnd())
    drawn = line.trimEnd().length
  }
  return out.join('\n')
}
