// The usage probe: the weeks some models have of their own (Fable 5.1, Sonnet), which Claude
// Code's status line doesn't report (2.1.302: only `five_hour` and `seven_day`) but its /usage
// screen shows. docs/PROTOCOL.md, "Per-model weeks". The same screen's 5-hour and all-models rows
// are official readings too: they keep the spend pace (usage-rate.ts) fed while no status line
// refreshes (agents at work in the background, a main session waiting on them).
//
// It starts Claude Code in a hidden shared terminal, in a folder of its own, opens /usage, reads
// every row off the screen (usage-screen.ts) and closes it. Nothing is ever sent to the model:
// the only thing typed is "/usage", a local command. Claude Code's login is its own business
// (the probe reads no token and passes none). Its hooks are off (no approvals, no status line,
// nothing on the phone) and so are MCP servers (a plugin's server, such as a Telegram bot,
// would otherwise start a second copy of itself). The terminal and its session stay out of the
// session list and History (term/hidden.ts): its folder is marked, so a transcript Claude Code
// writes there is passed over, and after each look the probe deletes that one transcript
// (<Claude config folder>/projects/<encoded scratch folder>/<session id>.jsonl), and the project
// folder if it made it and it is empty. Nothing else of Claude Code's is touched.
//
// When: while any client is connected, every 2 minutes while a session on the subscription or its
// agents work (what Usage.rate goes by: three looks in four minutes make a pace; sessions on other
// providers don't spend the subscription's windows); idle, every 15 while a phone (or the glasses)
// is connected, else hourly (the Mac app's own feed is always connected, and every look starts
// Claude Code); backing off while it can't read the screen; when a client asks (POST
// /v1/usage/refresh); right after a limit is hit.
//
// Claude Code starts for it as quietly as it can: no auto-update (a download it starts would be
// killed seconds later), no telemetry or error reports, and an English locale (the screen's times
// are read as English; a Russian locale changes them).
import { randomUUID } from 'node:crypto'
import { existsSync, readdirSync, statSync, unlinkSync } from 'node:fs'
import { join } from 'node:path'
import { CLAUDE_DIR, SUPPORT, findClaude, log } from './util.ts'
import { anythingWorking, clientCount, getUsage, markModelUsageStale, offerModelUsage, offerScreenUsage, phoneCount, setProbeHealth, tapBroadcast, type Usage } from './hub.ts'
import { getAccount, usageStale } from './account.ts'
import { endTermAndWait, ensureTerm, pressKeys, termAlive, termScreen, typeInto } from './terms.ts'
import { boxLines, menuOf, newTermId, sessionEnv, spawnHolder } from './term/shared.ts'
import { deleteHiddenTranscript, hiddenDir, transcriptDirs } from './term/hidden.ts'
import { modelWeeks, planWindows, readUsageScreen, type UsageScreen } from './usage-screen.ts'

export const PROBE_DIR = join(SUPPORT, 'usage-probe')
/**
 * How Claude Code starts for it (checked against `claude --help` of 2.1.302): hooks and any
 * custom status line off, and only the MCP servers --mcp-config names, which is none.
 */
export const PROBE_ARGS = ['--settings', '{"disableAllHooks":true}', '--strict-mcp-config']

const EVERY_MS = 15 * 60_000
/** Idle with no phone or glasses connected (only the Mac app's feed, which is always there). */
const UNWATCHED_MS = 60 * 60_000
/** Looks in a row that couldn't read the screen before the per-model weeks are said to be stale. */
const FAILS_STALE = 3
/** A session's status-line file (`<support>/statusline/<id>.json`) older than this is gone for good. */
const STATUS_KEEP_MS = 7 * 24 * 3600_000
/**
 * What Claude Code is told for the probe's start: no auto-updater (it would download and be killed
 * after 5 s), no telemetry or error reports, and English (the screen's times are read as English).
 */
export const PROBE_ENV: Record<string, string> = { DISABLE_AUTOUPDATER: '1', DISABLE_TELEMETRY: '1', DISABLE_ERROR_REPORTING: '1', LANG: 'en_US.UTF-8', LC_ALL: 'en_US.UTF-8' }
/** While a session or its agents work: often enough for a pace (usage-rate.ts: 3 readings over 4 minutes). */
const WORKING_MS = 2 * 60_000
/** How often the timer asks whether a look is due. */
const TICK_MS = 15_000
/** After a screen that said the per-model breakdown is unavailable: look again this soon. */
const SOON_MS = 2 * 60_000
/** While the screen can't be read (signed out of a plan, an API key, a dialog in the way): at most this rarely. */
const BACKOFF_MAX_MS = 4 * 3600_000
/** A probe started this recently answers a refresh as it is. */
const FRESH_MS = 30_000
/** Claude Code started, past its folder-trust prompt, its input box showing. */
const START_MS = 30_000
/** /usage fetched and drawn. */
const SCREEN_MS = 20_000
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/**
 * [at]: when the screen was read with its rows freshly fetched (the 5-hour and weekly readings are
 * true as of then); null when it wasn't, or showed only Claude Code's last numbers under a
 * "Refreshing…" that never ended.
 */
export type ProbeResult = { ok: boolean; why: string; screen: UsageScreen | null; text: string | null; at?: number | null }

/** One look at /usage: start, read, close. Never throws. */
export async function runProbe(o: { file?: string | null; cols?: number; rows?: number; configDir?: string } = {}): Promise<ProbeResult> {
  const file = o.file === undefined ? findClaude() : o.file
  if (!file) return { ok: false, why: 'no_claude', screen: null, text: null }
  hiddenDir(PROBE_DIR)
  const id = newTermId()
  const sessionId = randomUUID()
  const configDir = o.configDir ?? CLAUDE_DIR
  const dirExisted = Object.fromEntries(transcriptDirs(configDir, PROBE_DIR).map((d) => [d, existsSync(d)]))
  let pid: number | null = null
  let text: string | null = null
  let screen: UsageScreen | null = null
  try {
    pid = spawnHolder({
      id,
      cwd: PROBE_DIR,
      file,
      args: [...PROBE_ARGS, '--session-id', sessionId],
      // The owner's own environment, as any session gets it; no provider's (the probe asks Claude
      // Code about the plan it is signed in with, and sends nothing anywhere).
      env: sessionEnv(process.env, id, PROBE_ENV),
      // Wide enough for the three-line rows, tall enough for every row with room to spare.
      cols: o.cols ?? 100,
      rows: o.rows ?? 50,
      origin: 'hypernova',
      sessionId,
      terminal: { app: null },
      waitForTty: false,
      // A folder of its own, made for it: say yes to Claude Code's "trust this folder?".
      autoTrust: true,
    })
    // Claude Code up, its input box empty. A dialog other than the trust prompt (a notice, a
    // sign-in) means it can't be done without someone: give up rather than press anything.
    let blocked: string | null = null
    const ready = await poll(START_MS, 300, async () => {
      ensureTerm(id)
      const s = await termScreen(id)
      if (!s) return termAlive(id, pid) ? null : 'ended'
      text = s.text
      if (/trust this folder/i.test(s.text)) return null
      const lines = s.text.split('\n')
      const box = boxLines(lines)
      if (box) return 'ready'
      const menu = menuOf(lines)
      if (menu) {
        blocked = menu.title || menu.options[0]?.label || 'a dialog'
        return 'blocked'
      }
      return null
    })
    if (ready !== 'ready') return { ok: false, why: ready === 'blocked' ? `blocked: ${blocked}` : ready === 'ended' ? 'ended' : 'no_input', screen, text }
    // Claude Code draws its box a moment before it takes keys.
    await sleep(700)
    const typed = await typeInto(id, '/usage')
    if (!typed.ok) return { ok: false, why: `not_typed: ${typed.reason}`, screen, text }
    // Rows drawn and fetched: not while it says it's loading (a moment more for "Refreshing…"
    // over the last numbers it had, then those do).
    let refreshingSince = 0
    let at: number | null = null
    const read = await poll(SCREEN_MS, 400, async () => {
      const s = await termScreen(id)
      if (!s) return termAlive(id, pid) ? null : 'ended'
      text = s.text
      const seen = Date.now()
      screen = readUsageScreen(s.text, seen)
      if (screen.error && !screen.ok) return 'error'
      if (!screen.ok) return null
      if (!screen.loading) {
        at = seen
        return 'read'
      }
      refreshingSince ||= seen
      return seen - refreshingSince > 8000 ? 'read' : null
    })
    if (read !== 'read') return { ok: false, why: read === 'error' ? `error: ${(screen as UsageScreen | null)?.error}` : read ?? 'no_screen', screen, text }
    return { ok: true, why: 'read', screen, text, at }
  } catch (e) {
    log('usage probe failed', e)
    return { ok: false, why: 'failed', screen, text }
  } finally {
    // Esc closes the pane; ending the holder hangs Claude Code up (it exits and leaves the registry).
    pressKeys(id, '\x1b')
    if (!(await endTermAndWait(id)) && pid) {
      try {
        process.kill(pid, 'SIGTERM')
      } catch {}
    }
    // Its own transcript, and only that file.
    deleteHiddenTranscript(configDir, PROBE_DIR, sessionId, dirExisted)
  }
}

/** [f] every [every] ms until it answers (not null) or [ms] pass (null). */
async function poll<T>(ms: number, every: number, f: () => Promise<T | null>): Promise<T | null> {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const v = await f()
    if (v !== null) return v
    await sleep(every)
  }
  return null
}

// ── When ─────────────────────────────────────────────────────────────────
let running: Promise<boolean> | null = null
let lastStart = 0
/** Probes in a row that couldn't read the screen: each doubles the wait before the next timed one. */
let fails = 0
/** When the first of them was. */
let failingSince: number | null = null
/** The last look read /usage but not the per-model breakdown: the next timed one comes after SOON_MS. */
let soon = false

/** Look now (joining one under way). True when the screen was read. */
export function probeUsage(why: string): Promise<boolean> {
  if (running) return running
  lastStart = Date.now()
  running = (async () => {
    const r = await runProbe()
    // The 5-hour window and the week, as of the moment the screen was read: for the meters and the pace.
    const plan = r.ok && r.screen && r.at ? planWindows(r.screen.rows) : null
    if (plan) offerScreenUsage(plan.fiveHour, plan.sevenDay, r.at!)
    const said = plan ? [plan.fiveHour && `5-hour ${plan.fiveHour.pct}%`, plan.sevenDay && `week ${plan.sevenDay.pct}%`].filter(Boolean).join(', ') : ''
    if (r.ok) {
      failingSince = null
      setProbeHealth(true, null)
    }
    if (r.ok && r.screen?.partial) {
      // The breakdown is unavailable (rate limited, partial data): keep the last good rows, stale.
      fails = 0
      soon = true
      markModelUsageStale()
      log(`usage probe (${why}):${said ? ` ${said};` : ''} per-model weeks unavailable (${r.screen.partial}); keeping the last ones, again in 2 minutes`)
    } else if (r.ok && r.screen) {
      fails = 0
      soon = false
      const models = modelWeeks(r.screen.rows)
      offerModelUsage(models)
      log(`usage probe (${why}):`, [said, models.map((m) => `${m.label} ${m.pct}%`).join(', ') || 'no per-model weeks'].filter(Boolean).join('; '))
    } else {
      fails++
      soon = false
      failingSince ??= lastStart
      // Three in a row: the per-model weeks are the last ones read, and clients say since when.
      if (fails >= FAILS_STALE) setProbeHealth(false, failingSince)
      // What the screen ended on, for the log: its last lines that say anything.
      const said = (r.text ?? '').split('\n').map((l) => l.trim()).filter(Boolean).slice(-4).join(' / ').slice(0, 300)
      log(`usage probe (${why}) couldn't read /usage: ${r.why}${said ? ` [${said}]` : ''}`)
    }
    return r.ok
  })().finally(() => {
    running = null
  })
  return running
}

/** POST /v1/usage/refresh: a fresh look (one started in the last 30 s counts), then the usage. */
export async function refreshUsage(): Promise<Usage> {
  if (running) await running
  else if (Date.now() - lastStart > FRESH_MS && !usageStale()) await probeUsage('asked')
  return getUsage()
}

/**
 * How long after a look the next timed one waits: 2 minutes while something works ([working]) or
 * after an unavailable breakdown, else 15 while a phone is connected ([watched]), else 60; after
 * looks that couldn't read the screen, that doubled per failure (up to 4 hours).
 */
export const waitMs = (working = false, watched = true): number => {
  const every = working ? WORKING_MS : watched ? EVERY_MS : UNWATCHED_MS
  if (fails) return Math.min(every * 2 ** fails, BACKOFF_MAX_MS)
  return soon ? Math.min(every, SOON_MS) : every
}

/**
 * Something works, so the pace wants readings: a session working or its agents running, and no
 * 5-hour or weekly limit in the way (at one, the meter stands full and has no pace).
 */
function workingForPace(): boolean {
  const a = getAccount()
  if (a.state === 'limited' && (a.limit?.window === 'five_hour' || a.limit?.window === 'seven_day')) return false
  return anythingWorking()
}

/**
 * Whether a timed look is due at [now], and why ("while working", "every 15 minutes"); null when
 * not: nobody connected, one under way, too soon, or signed out, without a plan or on hold
 * (/usage has nothing to say then, and Claude Code would only show its sign-in).
 */
export function timedLookDue(now = Date.now()): string | null {
  if (clientCount() === 0 || running || usageStale()) return null
  const working = workingForPace()
  const watched = phoneCount() > 0
  if (now - lastStart < waitMs(working, watched)) return null
  return working ? 'while working' : soon ? 'again after an unavailable breakdown' : watched ? 'every 15 minutes' : 'hourly'
}

/**
 * Status-line files of sessions not seen for a week (one per session ever, all read every few
 * seconds): deleted. [dir]: `<support>/statusline`. Returns how many went.
 */
export function sweepStatusLines(dir = join(SUPPORT, 'statusline'), now = Date.now()): number {
  let gone = 0
  let files: string[]
  try {
    files = readdirSync(dir)
  } catch {
    return 0
  }
  for (const f of files) {
    if (!f.endsWith('.json')) continue
    const p = join(dir, f)
    try {
      if (now - statSync(p).mtimeMs > STATUS_KEEP_MS) {
        unlinkSync(p)
        gone++
      }
    } catch {}
  }
  return gone
}

/** The timed look, the look after a limit. Called once at start. */
export function startUsageProbe(): void {
  setInterval(() => {
    const why = timedLookDue()
    if (why) void probeUsage(why)
  }, TICK_MS).unref()
  sweepStatusLines()
  setInterval(() => sweepStatusLines(), 6 * 3600_000).unref()
  // A limit hit (a new one: another window, reset or episode): the weeks are worth a look now.
  let lastLimit: string | null = null
  tapBroadcast((m: any) => {
    if (m?.t !== 'account') return
    const a = m.account
    const key = a?.state === 'limited' ? `${a.limit?.window}:${a.limit?.resetsAt}:${a.since}` : null
    if (key && key !== lastLimit) setTimeout(() => void probeUsage('a limit was hit'), 5000).unref()
    lastLimit = key
  })
}
