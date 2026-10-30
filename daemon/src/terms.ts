// Shared terminals, daemon side (docs/PROTOCOL.md, "Shared terminals"): finds the holders,
// keeps a control connection to each, links them to sessions, starts them for the phone,
// types into them, relays them to phones and opens them in a terminal window on the Mac.
import { connect, type Socket } from 'node:net'
import { execFile, execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { existsSync, readdirSync, unlinkSync, writeFileSync, mkdirSync, statSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { WebSocket } from 'ws'
import { CLAUDE_DIR, HOME, SUPPORT, findClaude, log, randomToken, readJson, trimText, writeText } from './util.ts'
import { getManaged, getProvider, isSdk, isSubscription, providerEnv, saveManaged, settings, saveSettings, useProvider } from './store.ts'
import { ensureParsed, forgetPaste, getSession, isCodexRec, keepTyped, listSessionRecs, notePaste, sessionOfAgentJob, upsertSession, view } from './hub.ts'
import { promptTitle, typedCommand } from './normalize.ts'
import { trialEnded } from './blockers.ts'
import { noteTrialEnded } from './account.ts'
import { fromBeforeBoot } from './reboot.ts'
import { DATA, Frames, MSG, TERMS, boxLines, boxText, codexTuiArgs, frame, isWorking, menuOf, metaPath, modeOf, msg, newTermId, registryBusy, sessionEnv, sockPath, spawnHolder, type TermMeta, type TermState } from './term/shared.ts'
import { CodexError, codexBinary, codexSocketPath, rpc } from './codex/client.ts'
import { getThread } from './codex/threads.ts'
import { isHiddenCwd } from './term/hidden.ts'
import { bypassBlockedByRoot, has, unavailable } from './platform.ts'

export type TermView = { id: string; mac: number; phone: number }
type T = {
  meta: TermMeta
  sock: Socket | null
  connecting: boolean
  state: TermState | null
  /** What the holder can do itself (none for one started before replace and mode existed). */
  caps: string[]
  /** Waiting for a screen this connection asked for, oldest first. */
  screens: ((text: string) => void)[]
}
const terms = new Map<string, T>()

const alive = (pid: number) => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

// ── Discovery ────────────────────────────────────────────────────────────
/** Find holders (new ones, ones that survived a daemon restart) and drop the ones that ended. */
export function scanTerms(): void {
  const seen = new Set<string>()
  let files: string[] = []
  try {
    files = readdirSync(TERMS)
  } catch {}
  for (const f of files) {
    // A spec nobody read (the holder failed to start) still holds a shell environment: remove it.
    if (f.endsWith('.spec')) {
      try {
        if (Date.now() - statSync(join(TERMS, f)).mtimeMs > 60_000) unlinkSync(join(TERMS, f))
      } catch {}
      continue
    }
    if (!f.endsWith('.json')) continue
    const id = f.slice(0, -5)
    const meta = readJson<TermMeta | null>(metaPath(id), null)
    if (!meta?.pid) continue
    // From before the Mac last booted, its pid now some other process's: gone like a crashed one,
    // or its session would look open in a terminal nobody can reach, and never resume.
    if (!alive(meta.pid) || fromBeforeBoot(meta.createdAt)) {
      // A holder that crashed leaves its files behind.
      for (const p of [metaPath(id), sockPath(id)]) {
        try {
          unlinkSync(p)
        } catch {}
      }
      continue
    }
    seen.add(id)
    let t = terms.get(id)
    if (!t) {
      t = { meta, sock: null, connecting: false, state: null, caps: [], screens: [] }
      terms.set(id, t)
      if (meta.origin === 'terminal' && meta.terminal?.app && meta.terminal.app !== settings.terminalApp) {
        settings.terminalApp = meta.terminal.app
        saveSettings()
      }
    } else t.meta = meta
    if (!t.sock && !t.connecting) control(t)
  }
  for (const [id, t] of terms) if (!seen.has(id)) {
    t.sock?.destroy()
    terms.delete(id)
    exited(id, null)
  }
  link()
}

// Who waits for a holder to end (the sign-in, a restart onto another provider), by its id.
type ExitWaiter = (code: number | null, text: string | null) => void
const exitWaiters = new Map<string, ExitWaiter[]>()
/**
 * Call [f] once when holder [id] ends ([code]: claude's exit code; [text]: its screen as it ended;
 * each null when unknown).
 */
export function onTermExit(id: string, f: ExitWaiter): void {
  exitWaiters.set(id, [...(exitWaiters.get(id) ?? []), f])
}
function exited(id: string, code: number | null, text: string | null = null): void {
  const fs = exitWaiters.get(id)
  exitWaiters.delete(id)
  for (const f of fs ?? []) {
    try {
      f(code, text)
    } catch (e) {
      log('after a terminal ended', e)
    }
  }
}

function control(t: T): void {
  t.connecting = true
  const s = connect(sockPath(t.meta.id))
  const frames = new Frames((type, p) => {
    if (type !== MSG) return
    let m: any
    try {
      m = JSON.parse(p.toString('utf8'))
    } catch {
      return
    }
    if (m.t === 'hello' || m.t === 'state') {
      if (m.t === 'hello') t.caps = Array.isArray(m.caps) ? m.caps : []
      t.state = { cols: m.cols, rows: m.rows, title: m.title ?? '', childPid: m.childPid ?? null, tty: m.tty ?? 0, phone: m.phone ?? 0, started: !!m.started }
      // A Codex TUI's thread, as soon as its holder learns it (before its file says so).
      if (t.meta.agent === 'codex' && typeof m.sessionId === 'string') t.meta.sessionId = m.sessionId
      link()
    } else if (m.t === 'screen') {
      t.screens.shift()?.(String(m.text ?? ''))
    } else if (m.t === 'pasted' || m.t === 'moded') {
      waiters.get(m.id)?.({ ok: !!m.ok, reason: m.reason, text: m.text, mode: m.mode, queued: typeof m.queued === 'boolean' ? m.queued : undefined })
    } else if (m.t === 'exit') {
      terms.delete(t.meta.id)
      link()
      exited(t.meta.id, typeof m.code === 'number' ? m.code : null, typeof m.text === 'string' ? m.text : null)
    }
  })
  s.on('connect', () => {
    t.connecting = false
    t.sock = s
    s.write(msg({ t: 'hello', v: 1, role: 'control' }))
  })
  s.on('data', (b: Buffer) => {
    try {
      frames.push(b)
    } catch {
      s.destroy()
    }
  })
  s.on('close', () => {
    t.sock = null
    t.connecting = false
    // The holder died without saying how claude ended.
    if (!alive(t.meta.pid) && terms.get(t.meta.id) === t) {
      terms.delete(t.meta.id)
      link()
      exited(t.meta.id, null)
    }
  })
  s.on('error', () => {})
}

/** The session a holder runs right now (it changes on /clear and /resume; a Codex one's thread on /new). */
function sessionOf(t: T): string | null {
  if (t.meta.agent === 'codex') return t.meta.sessionId
  const pid = t.state?.childPid ?? t.meta.childPid
  if (pid) {
    const reg = readJson<{ sessionId?: string } | null>(join(CLAUDE_DIR, 'sessions', `${pid}.json`), null)
    if (reg?.sessionId) return reg.sessionId
  }
  return t.meta.sessionId
}

/**
 * Can Shift+Tab reach Bypass permissions in this terminal? Only when Claude Code was started with
 * it allowed (a flag, or bypass as the starting mode). A holder that never said how it started
 * gets the benefit of the doubt: the switch then tries and reports.
 */
export const bypassAllowed = (args: string[] | undefined): boolean => {
  if (!Array.isArray(args)) return true
  return args.some((a, i) => /^--(allow-)?dangerously-skip-permissions$/.test(a) || a === '--permission-mode=bypassPermissions' || (a === '--permission-mode' && args[i + 1] === 'bypassPermissions'))
}

const viewOf = (t: T): TermView => ({ id: t.meta.id, mac: t.state?.tty ?? t.meta.tty ?? 0, phone: t.state?.phone ?? t.meta.phone ?? 0 })

/** Put each live holder on its session, and take it off sessions it no longer runs. */
function link(): void {
  const want = new Map<string, TermView>()
  // The daemon's own terminals (the usage probe) run no session anyone should see (term/hidden.ts).
  const shown = [...terms.values()].filter((t) => !isHiddenCwd(t.meta.cwd))
  for (const t of shown) {
    const sid = sessionOf(t)
    if (sid) want.set(sid, viewOf(t))
  }
  const byTerm = new Map<string, T>()
  for (const t of shown) {
    const sid = sessionOf(t)
    if (sid) byTerm.set(sid, t)
  }
  for (const r of listSessionRecs()) {
    const v = want.get(r.id) ?? null
    // In a terminal: whatever its start allowed. Without one: the daemon's own sessions can.
    const t = byTerm.get(r.id)
    const bypass = t ? bypassAllowed(t.meta.args) : isSdk(r.id)
    if (JSON.stringify(r.term) !== JSON.stringify(v) || r.bypassAvailable !== bypass) upsertSession(r.id, { term: v, bypassAvailable: bypass })
  }
  for (const [sid, v] of want) {
    const t = terms.get(v.id)!
    if (t.meta.agent === 'codex') {
      // A thread the TUI started (`codex` in a shell, /new): a Codex session of its own once Codex
      // has written it down; one Hypernova knows is followed again after a restart.
      followSoon(sid, t.meta.cwd, t.meta.origin)
      if (getSession(sid) && JSON.stringify(getSession(sid)?.term ?? null) !== JSON.stringify(v)) upsertSession(sid, { term: v })
    } else if (!getSession(sid)) upsertSession(sid, { cwd: t.meta.cwd, origin: t.meta.origin, state: t.state?.started ? 'idle' : 'starting', term: v, lastActivityAt: Date.now() })
  }
}

/**
 * What Codex's sessions do with a terminal's thread: take it on, and follow it (codex/sessions.ts
 * hands them over; importing it here would close a loop through the hub).
 */
type CodexHolders = { follow: (id: string, cwd: string, origin: 'terminal' | 'hypernova') => Promise<boolean> }
let codexHolders: CodexHolders | null = null
export const useCodexHolders = (h: CodexHolders) => {
  codexHolders = h
}
/** When each terminal's Codex thread was last tried (a thread before its first turn can't be joined yet). */
const followed = new Map<string, number>()
function followSoon(sid: string, cwd: string, origin: 'terminal' | 'hypernova'): void {
  const at = followed.get(sid) ?? 0
  if (Date.now() - at < 5000) return
  followed.set(sid, Date.now())
  void codexHolders?.follow(sid, cwd, origin).catch(() => {})
}

export const termOf = (sessionId: string): string | null => getSession(sessionId)?.term?.id ?? null
/** The claude process holder [id] runs (null when it hasn't said). */
export const termChildPid = (id: string): number | null => {
  const t = terms.get(id)
  return t?.state?.childPid ?? t?.meta.childPid ?? null
}

// ── Talking to a holder ──────────────────────────────────────────────────
function send(id: string, m: object): boolean {
  const t = terms.get(id)
  if (!t?.sock || t.sock.destroyed) return false
  t.sock.write(msg(m))
  return true
}
export type Typed = {
  ok: boolean
  reason?: 'not_started' | 'no_input' | 'busy_input' | 'not_seen' | 'not_sent' | 'unavailable' | 'unreachable' | 'menu_gone' | 'menu_moved'
  /** busy_input: the text already in the terminal's input box. */
  text?: string
  /** A mode switch: the mode the terminal shows now. */
  mode?: string | null
  /** A prompt typed while Claude works: Claude Code queued it rather than starting a turn. */
  queued?: boolean
}
const waiters = new Map<string, (r: Typed) => void>()
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))
// Shell mode shows a typed "!" as the box's prompt, not as text, so compare without it.
const squash = (s: string) => s.replace(/\s+/g, '').replace(/^!/, '')
/** The last prompt typed into each terminal, for holders that can't tell their own leftovers. */
const lastTyped = new Map<string, string>()
/** How long a queued phone send stays claimable after the holder answered. */
const QUEUED_CLAIM_MS = 5000
/** How long a prompt that started a turn stays claimable by its entry, rewritten by a hook (a model call, seconds). */
const REWRITTEN_CLAIM_MS = 20_000

/**
 * Type a prompt into the live session, as if pasted, and press Enter. The holder only does it
 * into Claude Code's empty input box and says why not otherwise; [replace] clears what's there
 * (so does a leftover of the phone's own last prompt). A holder started before it could do
 * that itself gets it done here, from its screen. While Claude works, Claude Code queues the
 * prompt (the box empties all the same): `queued` says so, except for a slash command, which
 * Claude Code runs at once as often as not (/cost, /model and other panels open over the
 * spinner); one it does queue shows in the session's queue, which the phone goes by.
 * [clientId]: the phone send it is, for the queue entry Claude Code logs for it to claim.
 */
export async function typeInto(id: string, text: string, replace = false, clientId?: string): Promise<Typed> {
  const t0 = terms.get(id)
  const old = !(t0?.caps ?? []).includes('replace')
  // Whether Claude works, sampled here too: holders started before they could tell outlive
  // daemon restarts, and keep their old code until their session ends.
  const busy = registryBusy(t0?.state?.childPid ?? t0?.meta.childPid)
  // Noted before it's typed: Claude Code can log the queue entry before the holder answers.
  if (clientId) notePaste(id, clientId, text)
  // Ending on an @-mention opens file suggestions (newer holders add this space themselves).
  if (old && /(^|\s)@\S*$/.test(text)) text += ' '
  let r = await ask(id, { t: 'paste', text, replace })
  if (old && r.reason === 'busy_input') {
    const lines = (await screenOf(id))?.split('\n') ?? []
    const at = boxLines(lines)
    const inBox = at ? boxText(lines, at) : ''
    const t = terms.get(id)
    // Working, unless the screen above the box holds still (Claude shows no spinner while it streams).
    let busy = isWorking(at ?? { above: [], below: [] }) || registryBusy(t?.state?.childPid ?? t?.meta.childPid)
    if (at && !busy) {
      await sleep(700)
      const again = (await screenOf(id))?.split('\n') ?? []
      busy = again.slice(0, at.top).join('\n') !== lines.slice(0, at.top).join('\n')
    }
    if (at && inBox && (replace || squash(inBox) === lastTyped.get(id)) && !busy) {
      pressKeys(id, '\x03') // Ctrl+C clears the box while Claude is idle
      await sleep(700)
      r = await ask(id, { t: 'paste', text })
    } else if (inBox) r = { ...r, text: inBox.slice(0, 500) }
  }
  let prompt = false
  if (r.ok) {
    lastTyped.set(id, squash(text))
    const command = typedCommand(text, () => null)
    r.queued = command && command.kind !== 'bash' ? false : (r.queued ?? busy)
    prompt = !command
  }
  // Not typed, or not queued: no queue entry may claim it, or the same "yes" typed on the Mac a
  // moment later, while Claude works, would take its name. (A command Claude Code did queue then
  // goes unnamed; the phone matches it by its text.) Queued: its entry, logged as Enter went in,
  // is read within a second, so it waits a few for one and no longer.
  // A prompt that started a turn: only its own entry, if a hook of Claude Code's rewrote it, may claim it.
  if (clientId) {
    if (prompt && !r.queued) keepTyped(id, clientId, REWRITTEN_CLAIM_MS)
    else forgetPaste(id, clientId, r.queued ? QUEUED_CLAIM_MS : 0)
  }
  return r
}

/** Switch a shared session's permission mode (Shift+Tab until Claude Code's mode line shows it). */
export async function switchTermMode(id: string, mode: string): Promise<Typed> {
  if ((terms.get(id)?.caps ?? []).includes('mode')) return ask(id, { t: 'mode', mode })
  // A holder started before: read its screen here.
  const read = async (): Promise<string | null | undefined> => {
    const lines = (await screenOf(id))?.split('\n') ?? []
    const at = boxLines(lines)
    return at ? modeOf(at.below) : undefined
  }
  const cycleTo = async (target: string | null): Promise<boolean> => {
    for (let i = 0; i < 6; i++) {
      const now = await read()
      if (now === undefined) return false
      if (now === target) return true
      pressKeys(id, '\x1b[Z')
      await sleep(400)
    }
    return false
  }
  const start = await read()
  if (start === undefined) return { ok: false, reason: 'no_input' }
  if (await cycleTo(mode)) return { ok: true, mode }
  if (start) await cycleTo(start)
  return { ok: false, reason: 'unavailable', mode: (await read()) ?? null }
}

// ── Menus ────────────────────────────────────────────────────────────────
/** The menu each session's terminal showed at the last look: one counts once it holds still. */
const lastMenu = new Map<string, string | null>()
let looking = false
/**
 * Look at each shared session's screen for a menu or dialog (the /model picker, a dialog the
 * hooks never see) and put it on the session, so the phone can show it as buttons. Not while
 * the session has an approval card: that answers the same dialog.
 */
export async function watchMenus(): Promise<void> {
  if (looking) return
  looking = true
  try {
    await Promise.all(
      listSessionRecs().map(async (r) => {
        // Codex's TUI draws its own dialogs; its approvals come over the app-server instead.
        if (!r.term || r.state === 'ended' || isCodexRec(r)) {
          lastMenu.delete(r.id)
          if (r.menu) upsertSession(r.id, { menu: null })
          return
        }
        const text = await screenOf(r.term.id)
        if (text === null) return
        let menu = menuOf(text.split('\n'))
        // Claude Code's "Your Claude Code trial has ended." dialog: the plan is over.
        if (menu && trialEnded(menu.title) && isSubscription(r.providerId)) noteTrialEnded(Date.now(), r.id)
        if (menu && view(r).pendingApprovalIds.length) menu = null
        const before = lastMenu.get(r.id) ?? null
        lastMenu.set(r.id, menu?.id ?? null)
        if (!menu) {
          if (r.menu) upsertSession(r.id, { menu: null })
        } else if (menu.id === before || menu.id === r.menu?.id) upsertSession(r.id, { menu })
      }),
    )
  } finally {
    looking = false
  }
}

/**
 * Answer the menu on a shared terminal's screen the way a person would: arrow keys to the option
 * (checking where the cursor landed), then Enter or the footer's other key, or Esc to close it.
 */
export async function answerMenu(id: string, menuId: string, pick: { choice?: number; alt?: boolean; cancel?: boolean }): Promise<Typed> {
  const read = async () => {
    const text = await screenOf(id)
    return text === null ? null : menuOf(text.split('\n'))
  }
  let menu = await read()
  if (!menu || menu.id !== menuId) return { ok: false, reason: 'menu_gone' }
  let key = '\x1b'
  if (!pick.cancel) {
    const want = pick.choice ?? -1
    const label = menu.options[want]?.label
    if (label === undefined) return { ok: false, reason: 'menu_moved' }
    for (let step = 0; ; step++) {
      // A list that scrolls shifts under the cursor: find the option by its label there.
      const at = menu.more ? menu.options.findIndex((o) => o.label === label) : want
      if (at < 0 || step > 12) return { ok: false, reason: 'menu_moved' }
      if (at === menu.cursor) break
      for (let k = Math.abs(at - menu.cursor); k > 0; k--) {
        pressKeys(id, at > menu.cursor ? '\x1b[B' : '\x1b[A')
        await sleep(35)
      }
      await sleep(150)
      menu = await read()
      if (!menu) return { ok: false, reason: 'menu_gone' }
    }
    key = pick.alt && menu.alt ? menu.alt.key : '\r'
  }
  pressKeys(id, key)
  const sid = listSessionRecs().find((r) => r.term?.id === id)?.id
  if (sid) {
    lastMenu.set(sid, null)
    upsertSession(sid, { menu: null })
  }
  return { ok: true }
}

/** The holder's screen as plain text (null if it doesn't answer). */
function screenOf(id: string): Promise<string | null> {
  const t = terms.get(id)
  const sock = t?.sock
  if (!t || !sock || sock.destroyed) return Promise.resolve(null)
  return new Promise((resolve) => {
    const done = (text: string) => {
      clearTimeout(timer)
      resolve(text)
    }
    const timer = setTimeout(() => {
      t.screens = t.screens.filter((f) => f !== done)
      resolve(null)
    }, 2000)
    t.screens.push(done)
    sock.write(msg({ t: 'screen' }))
  })
}
function ask(id: string, m: Record<string, unknown>): Promise<Typed> {
  const rid = randomToken(8)
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      waiters.delete(rid)
      resolve({ ok: false, reason: 'unreachable' })
    }, 12_000)
    waiters.set(rid, (r) => {
      clearTimeout(timer)
      waiters.delete(rid)
      resolve(r)
    })
    if (!send(id, { ...m, id: rid })) waiters.get(rid)!({ ok: false, reason: 'unreachable' })
  })
}
export const pressKeys = (id: string, data: string) => send(id, { t: 'keys', data })
export const endTerm = (id: string) => send(id, { t: 'kill' })

/** End a holder: through its socket, or (before the daemon reached it) by its pid. */
export function stopTerm(id: string, pid: number | null): void {
  if (endTerm(id)) return
  const p = terms.get(id)?.meta.pid ?? pid
  if (p) {
    try {
      process.kill(p, 'SIGTERM')
    } catch {}
  }
}

/** End a holder and wait (6 s at most) until it has: true once it's gone. */
export function endTermAndWait(id: string, ms = 6000): Promise<boolean> {
  if (!terms.has(id)) return Promise.resolve(true)
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(!terms.has(id)), ms)
    onTermExit(id, () => {
      clearTimeout(timer)
      resolve(true)
    })
    stopTerm(id, null)
  })
}

/** A holder still runs: the daemon has it, or its process is there (it hasn't reached it yet). */
export const termAlive = (id: string, pid: number | null) => terms.has(id) || (!!pid && alive(pid))

/** Reach holder [id] now rather than at the next scan (one just started). */
export function ensureTerm(id: string): void {
  const t = terms.get(id)
  if (!t) scanTerms()
  else if (!t.sock && !t.connecting) control(t)
}

/** A holder's screen as plain text, with its width (null when it doesn't answer). */
export async function termScreen(id: string): Promise<{ text: string; cols: number } | null> {
  const text = await screenOf(id)
  const t = terms.get(id)
  return text === null || !t ? null : { text, cols: t.state?.cols ?? 100 }
}

// ── Signing in ───────────────────────────────────────────────────────────
// `claude auth login --claudeai` in a holder of its own: no session runs there. Wide, so the
// sign-in page's address, often 400 characters, sits on one line of its screen.
const LOGIN_ARGS = ['auth', 'login', '--claudeai']
const isLogin = (args: string[] | undefined) => !!args && args[0] === 'auth' && args[1] === 'login'

/** Start signing in Claude Code in a new holder. */
export function startLoginTerm(): { termId: string; pid: number | null } {
  const file = findClaude()
  if (!file) throw Object.assign(new Error('Claude Code isn’t installed on this Mac.'), { status: 409, code: 'no_claude' })
  const id = newTermId()
  const pid = spawnHolder({
    id,
    cwd: HOME,
    file,
    args: LOGIN_ARGS,
    env: sessionEnv(process.env, id, providerEnv(getProvider(null))),
    cols: 500,
    rows: 40,
    origin: 'hypernova',
    sessionId: null,
    terminal: { app: settings.terminalApp ?? null },
    waitForTty: false,
    autoTrust: false,
  })
  log('started a sign-in terminal', id)
  return { termId: id, pid }
}

/** A sign-in left running from before the daemon restarted: nobody waits on its code box any more. */
export function endLoginTerms(): void {
  let files: string[] = []
  try {
    files = readdirSync(TERMS)
  } catch {}
  for (const f of files) {
    if (!f.endsWith('.json')) continue
    const meta = readJson<TermMeta | null>(metaPath(f.slice(0, -5)), null)
    if (meta?.pid && isLogin(meta.args) && alive(meta.pid)) stopTerm(meta.id, meta.pid)
  }
}

/** The holder each session the daemon started last went into, by session id (switch.ts hands a terminal over to it). */
const started = new Map<string, string>()
export const startedTerm = (sessionId: string): string | null => started.get(sessionId) ?? null

/** Start a session from the phone in a new shared terminal. Returns the holder's id. */
export function startTermSession(o: {
  sessionId: string
  cwd: string
  args: string[]
  env: Record<string, string>
  origin: 'terminal' | 'hypernova'
}): string {
  const file = findClaude()
  if (!file) throw Object.assign(new Error('Claude Code isn’t installed on this Mac.'), { status: 409 })
  const id = newTermId()
  spawnHolder({
    id,
    cwd: o.cwd,
    file,
    args: o.args,
    env: sessionEnv(process.env, id, o.env),
    cols: 100,
    rows: 34,
    origin: o.origin,
    sessionId: o.sessionId,
    terminal: { app: settings.terminalApp ?? null },
    waitForTty: false,
    autoTrust: true,
  })
  log('started shared terminal', id, 'for', o.sessionId.slice(0, 8), 'in', o.cwd)
  return id
}

/**
 * A Codex TUI in a new shared terminal, on Codex's app-server through the holder's own socket
 * ([args]: `resume <thread>` for one Hypernova knows). Returns the holder's id.
 */
export function startCodexTerm(o: { sessionId: string | null; cwd: string; args: string[]; origin: 'terminal' | 'hypernova'; autoTrust: boolean; env?: Record<string, string> }): string {
  const file = codexBinary().path
  const socket = codexSocketPath()
  if (!file || !socket) throw new CodexError(409, 'codex_unavailable', 'Codex isn’t reachable on this computer right now.')
  const id = newTermId()
  spawnHolder({
    id,
    cwd: o.cwd,
    file,
    args: codexTuiArgs(id, o.args, o.cwd),
    env: sessionEnv(process.env, id, o.env),
    cols: 100,
    rows: 34,
    origin: o.origin,
    sessionId: o.sessionId,
    terminal: { app: settings.terminalApp ?? null },
    waitForTty: false,
    autoTrust: o.autoTrust,
    agent: 'codex',
    codexSocket: socket,
  })
  log('started a codex terminal', id, o.sessionId ? `for ${o.sessionId.slice(0, 8)}` : '', 'in', o.cwd)
  return id
}

/** Wait (4 s at most) until holder [id] is up and the daemon has it: true once it has. */
export async function waitForTerm(id: string, ms = 4000): Promise<boolean> {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) {
    if (!terms.has(id) && existsSync(metaPath(id))) scanTerms()
    if (terms.get(id)?.sock) return true
    await sleep(80)
  }
  return terms.has(id)
}

/**
 * Codex session [id] in a shared terminal: the one it runs in, or `codex resume` in a new one.
 * Codex can resume only a thread it has written down (409 `not_started`).
 */
export async function openCodexTerminal(id: string): Promise<string> {
  const have = getSession(id)?.term?.id
  if (have && terms.has(have)) return have
  const t = getThread(id)
  if (!t) throw new CodexError(404, 'no_session', 'That session no longer exists.')
  if (!t.started) throw new CodexError(409, 'not_started', 'Codex hasn’t started this conversation yet. Send a message first.')
  await rpc()
  const tid = startCodexTerm({ sessionId: id, cwd: t.cwd, args: ['resume', id], origin: 'hypernova', autoTrust: true })
  if (!(await waitForTerm(tid))) throw new CodexError(409, 'terminal_busy', 'The terminal didn’t start. Try again in a moment.')
  link()
  return tid
}

/**
 * A `claude agents` session (Claude Code's background daemon, settings.claudeAgents) in a shared
 * terminal: `claude attach <job>` in a holder, so the phone types into that same conversation
 * rather than a fork of it. Closing the holder only detaches; the session runs on in the daemon.
 * Resolves with the holder's id once its screen shows Claude Code's input box, or once it shows
 * anything else (a question, a permission, a menu: Claude waits on the owner, which typing into it
 * then says rather than "didn't open"); null only if `claude attach` ended or never drew a screen.
 */
export async function attachAgentsTerm(sessionId: string, cwd: string, job: string, ms = 15_000): Promise<string | null> {
  const have = termOf(sessionId)
  if (have && terms.has(have)) {
    if (!attachDrifted(have, sessionId)) return have
    // It shows another job (or the agent list) now: typing there would reach the wrong session.
    log('claude attach holder', have, 'drifted off', sessionId.slice(0, 8), '(shows', JSON.stringify(termTitle(have)) + '): attaching afresh')
    await endTermAndWait(have)
  }
  const file = findClaude()
  if (!file) throw Object.assign(new Error('Claude Code isn’t installed on this computer.'), { status: 409 })
  const id = newTermId()
  spawnHolder({
    id,
    cwd,
    file,
    args: ['attach', job],
    env: sessionEnv(process.env, id, {}),
    cols: 100,
    rows: 34,
    origin: 'terminal',
    sessionId,
    terminal: { app: settings.terminalApp ?? null },
    waitForTty: false,
    autoTrust: false,
  })
  log('attached shared terminal', id, 'to claude agents job', job, 'for', sessionId.slice(0, 8))
  if (!(await waitForTerm(id))) return null
  link()
  const t0 = Date.now()
  let drawn = false
  while (Date.now() - t0 < ms) {
    // `claude attach` gave up (a job that's gone): its holder has ended and taken its files.
    if (!existsSync(metaPath(id))) return null
    const screen = await screenOf(id)
    if (screen && boxLines(screen.split('\n'))) return id
    drawn ||= !!screen?.trim()
    // Claude Code's registry says it waits on the owner: no input box is coming.
    if (drawn && registryWaiting(getSession(sessionId)?.pid)) return id
    await sleep(250)
  }
  if (drawn && existsSync(metaPath(id))) return id
  return null
}

/** Holder [id]'s title as the program in it last set it ("" when none). */
const termTitle = (id: string): string => (terms.get(id)?.state?.title ?? terms.get(id)?.meta.title ?? '').trim()

/**
 * Holder [tid] runs `claude attach` and no longer shows session [sessionId]: ← in it goes back to
 * the agent view, and from there into any other job. `claude attach` titles its terminal with the
 * session it shows (its registry name), and the agent view with "… · claude agents", so a title
 * that's neither the session's name is another view. No title or no name known: not drifted.
 */
export function attachDrifted(tid: string, sessionId: string): boolean {
  const t = terms.get(tid)
  if (!t || t.meta.args?.[0] !== 'attach') return false
  const title = termTitle(tid)
  if (!title) return false
  if (/·\s*claude agents$/.test(title)) return true
  const pid = getSession(sessionId)?.pid
  const name = pid ? readJson<{ name?: string } | null>(join(CLAUDE_DIR, 'sessions', `${pid}.json`), null)?.name?.trim() : null
  return !!name && title !== name
}

/** Claude Code's registry says session process [pid] waits on the owner (a question, a permission). */
function registryWaiting(pid: number | null | undefined): boolean {
  if (!pid) return false
  return readJson<{ status?: string } | null>(join(CLAUDE_DIR, 'sessions', `${pid}.json`), null)?.status === 'waiting'
}

/**
 * A new session from the phone in Claude Code's background daemon (settings.claudeAgents):
 * `claude --bg`, so it shows in `claude agents` like one started there. Claude Code picks the
 * session id itself (--bg passes over --session-id), so it's read from the registry by the job id
 * --bg prints. Returns the session id.
 */
export async function createAgentsSession(o: { cwd: string; model?: string | null; permissionMode?: string; prompt?: string }): Promise<string> {
  const file = findClaude()
  if (!file) throw Object.assign(new Error('Claude Code isn’t installed on this computer.'), { status: 409 })
  const args = ['--bg']
  if (o.model) args.push('--model', o.model)
  if (o.permissionMode) args.push('--permission-mode', o.permissionMode)
  if (o.permissionMode !== 'bypassPermissions' && !bypassBlockedByRoot()) args.push('--allow-dangerously-skip-permissions')
  if (o.prompt?.trim()) args.push('--', o.prompt)
  const out = await new Promise<string>((ok) => execFile(file, args, { cwd: o.cwd, timeout: 30_000 }, (_e, so, se) => ok(`${so}\n${se}`)))
  const job = /backgrounded\s*·\s*([0-9a-f]{8})/.exec(out)?.[1]
  if (!job) {
    log('claude --bg started nothing:', trimText(out.trim(), 300))
    throw Object.assign(new Error('Claude Code didn’t start the session in the background.'), { status: 409 })
  }
  let sid: string | null = null
  const t0 = Date.now()
  while (!sid && Date.now() - t0 < 15_000) {
    sid = sessionOfAgentJob(job)
    if (!sid) await sleep(250)
  }
  if (!sid) throw Object.assign(new Error('The background session didn’t start. Look in claude agents.'), { status: 409 })
  const title = trimText(o.prompt ? promptTitle(o.prompt) : null, 80) ?? 'New session'
  const r = upsertSession(sid, { cwd: o.cwd, origin: 'terminal', state: 'starting', activity: null, stoppedForAway: false, lastActivityAt: Date.now(), title, model: o.model || null, permissionMode: o.permissionMode ?? 'default', createdAt: Date.now() })
  ensureParsed(r)
  log('started claude agents job', job, 'for', sid.slice(0, 8), 'in', o.cwd)
  return sid
}

/**
 * The conversation in holder [from] goes on in holder [to] (the agent switched): its windows and
 * phones follow, and [from] ends. [to] null: they read [text] and close.
 */
export const moveTerm = (from: string, to: string | null, text?: string) => send(from, { t: 'move', to, text: text ?? null })

/** What an automation's run adds to a new session (automations.ts). */
export type AutomationStart = {
  /** Its badge, kept with the session's record. */
  automation?: { id: string; name: string; runId: string }
  /** Claude Code allow rules ("Bash(git log:*)"), for "Allowed only". */
  allowedTools?: string[]
  /** Told to Claude: started by an automation, nobody watching, the reply's shape. */
  appendSystemPrompt?: string
  /** The run's own hooks (PermissionDenied), a settings file passed with --settings. */
  settingsFile?: string
}

/**
 * A session from the phone, in a new shared terminal: a new conversation, a fork of one that's
 * open elsewhere (`forkFrom`), or one that ended (`resume`). Returns the session id. An
 * automation's run starts one with its name (`title`, also Claude Code's `--name`) and [AutomationStart].
 */
export function createSharedSession(o: { cwd: string; providerId?: string; model?: string | null; permissionMode?: string; prompt?: string; forkFrom?: string; resume?: string; title?: string; context?: string } & AutomationStart): string {
  const id = o.resume ?? randomUUID()
  const args: string[] = []
  // A conversation Claude Code never wrote down (its claude ended before taking a prompt): it
  // refuses to resume an id it has no transcript for, and the session would stay starting.
  // It starts afresh under its own id instead.
  if (o.resume && !existsSync(getSession(o.resume)?.transcriptPath ?? '')) args.push('--session-id', o.resume)
  else if (o.resume) args.push('--resume', o.resume)
  else if (o.forkFrom) args.push('--resume', o.forkFrom, '--fork-session', '--session-id', id)
  else args.push('--session-id', id)
  if (o.model) args.push('--model', o.model)
  // Passed whenever the phone chose one, even "default" (the owner's settings may default to
  // auto, say). Without one (a resume or a fork), Claude Code's own default applies and the
  // hooks report the real mode.
  const mode = o.permissionMode ?? 'default'
  if (o.permissionMode) args.push('--permission-mode', o.permissionMode)
  // Lets Shift+Tab reach Bypass later (the phone's mode picker); it starts in the chosen mode.
  // (Not as root: Claude Code refuses the flag there and would exit at once; platform.ts.)
  if (o.permissionMode !== 'bypassPermissions' && !bypassBlockedByRoot()) args.push('--allow-dangerously-skip-permissions')
  if (o.automation) {
    // Checked against `claude --help` of 2.1.301: --allowedTools takes its rules as separate
    // arguments (it reads until the next option), --name names the session in /resume and the
    // terminal title, and --settings adds the run's hook file to the owner's settings.
    if (o.allowedTools?.length) args.push('--allowedTools', ...o.allowedTools)
    if (o.title) args.push('--name', o.title)
    if (o.appendSystemPrompt) args.push('--append-system-prompt', o.appendSystemPrompt)
    if (o.settingsFile) args.push('--settings', o.settingsFile)
  }
  // What a switch from another agent carried over (switch.ts): at its start, and every resume.
  const context = o.context ?? (o.resume ? getManaged(o.resume)?.context : undefined)
  if (context) args.push('--append-system-prompt', context)
  if (o.prompt?.trim()) args.push('--', o.prompt)
  // A resume keeps the provider it had, unless it's moving to another one (the phone's
  // "Continue on <provider>"): then its record says so, for its next restart too.
  // A provider deleted since: 404 `no_provider`, never the subscription instead.
  const provider = useProvider(o.providerId ?? (o.resume ? (getManaged(o.resume)?.providerId ?? getSession(o.resume)?.providerId) : undefined))
  const existing = getSession(id)
  const title = trimText(o.title ?? (o.prompt && promptTitle(o.prompt)), 80) ?? 'New session'
  if (!o.resume) {
    saveManaged({ id, cwd: o.cwd, providerId: provider.id, model: o.model || null, permissionMode: mode, createdAt: Date.now(), title, engine: 'term', ...(o.automation ? { automation: o.automation } : {}), ...(o.context ? { context: o.context } : {}) })
  } else if (o.providerId) {
    const m = getManaged(id)
    const moved = { providerAt: Date.now(), providerFrom: m?.providerId ?? existing?.providerId ?? 'anthropic' }
    saveManaged(m ? { ...m, providerId: provider.id, model: null, engine: 'term', ...moved } : { id, cwd: o.cwd, providerId: provider.id, model: null, permissionMode: 'default', createdAt: existing?.createdAt ?? Date.now(), title: existing?.title, engine: 'term', ...moved })
  }
  started.set(id, startTermSession({ sessionId: id, cwd: o.cwd, args, env: providerEnv(provider), origin: existing?.origin ?? 'hypernova' }))
  const r = upsertSession(id, {
    cwd: o.cwd,
    origin: existing?.origin ?? 'hypernova',
    state: 'starting',
    activity: null,
    stoppedForAway: false,
    lastActivityAt: Date.now(),
    // A resume forgets the claude process before it (a restart on another provider): until the
    // new one registers, the old one's exit would read as the session ending.
    ...(o.resume
      ? { pid: null, ...(o.providerId ? { providerId: provider.id, model: null, blocked: null } : {}) }
      : { title, providerId: provider.id, model: o.model || null, permissionMode: mode, createdAt: Date.now(), ...(o.automation ? { automation: o.automation } : {}) }),
  })
  ensureParsed(r)
  return id
}

/** Can this Mac run shared terminals? (node-pty loads, Claude Code is there.) */
let ptyOk: boolean | null = null
export async function sharedTerminalsAvailable(): Promise<boolean> {
  if (ptyOk === null) {
    try {
      await import('node-pty')
      ptyOk = true
    } catch (e) {
      log('shared terminals unavailable: node-pty failed to load', e)
      ptyOk = false
    }
  }
  return ptyOk && !!findClaude()
}

// ── Phones ───────────────────────────────────────────────────────────────
const tickets = new Map<string, { term: string; device: string | null; exp: number }>()
/** A single-use, one-minute ticket the phone's terminal page opens its WebSocket with, tied to that phone. */
export function terminalTicket(termId: string, device: string | null): string {
  for (const [k, v] of tickets) if (v.exp < Date.now()) tickets.delete(k)
  const k = randomToken(24)
  tickets.set(k, { term: termId, device, exp: Date.now() + 60_000 })
  return k
}
export function redeemTicket(k: string): { term: string; device: string | null } | null {
  const v = tickets.get(k)
  tickets.delete(k)
  return v && v.exp > Date.now() && terms.has(v.term) ? { term: v.term, device: v.device } : null
}

/**
 * Relay one phone's WebSocket to a holder: binary frames are terminal bytes, text frames JSON.
 * When the conversation moves to another holder (the agent switched), the relay follows it: the
 * phone gets that holder's snapshot, which redraws its screen.
 */
export function relayPhone(termId: string, ws: WebSocket, cols: number, rows: number): void {
  let s: Socket
  let moving = false
  const open = (id: string) => {
    const sock = connect(sockPath(id))
    s = sock
    // This one closing or failing is the phone's view ending, a move's included.
    moving = false
    const frames = new Frames((type, p) => {
      if (ws.readyState !== ws.OPEN || s !== sock) return
      // A phone on a slow link that fell 8 MB behind starts over with a fresh snapshot.
      if (ws.bufferedAmount > 8 * 1024 * 1024) {
        ws.close(4008, 'behind')
        sock.destroy()
        return
      }
      if (type === DATA) return void ws.send(p, { binary: true })
      const text = p.toString('utf8')
      if (text.includes('"moved"')) {
        try {
          const m = JSON.parse(text)
          if (m?.t === 'moved' && typeof m.to === 'string') {
            moving = true
            sock.destroy()
            void follow(m.to)
            return
          }
        } catch {}
      }
      ws.send(text)
    })
    sock.on('connect', () => sock.write(msg({ t: 'hello', v: 1, role: 'phone', cols, rows, snapshot: true })))
    sock.on('data', (b: Buffer) => {
      try {
        frames.push(b)
      } catch {
        sock.destroy()
      }
    })
    sock.on('close', () => s === sock && !moving && ws.readyState === ws.OPEN && ws.close(1000, 'ended'))
    sock.on('error', () => s === sock && !moving && ws.readyState === ws.OPEN && ws.close(1011, 'unavailable'))
  }
  // The new holder may still be starting: a few seconds to come up.
  const follow = async (id: string) => {
    for (let i = 0; i < 50 && ws.readyState === ws.OPEN; i++) {
      if (existsSync(sockPath(id))) return open(id)
      await sleep(80)
    }
    if (ws.readyState === ws.OPEN) ws.close(1000, 'ended')
  }
  open(termId)
  ws.on('message', (data, isBinary) => {
    if (s.destroyed) return
    const buf = Buffer.isBuffer(data) ? data : Array.isArray(data) ? Buffer.concat(data) : Buffer.from(data as ArrayBuffer)
    if (isBinary) return void s.write(frame(DATA, buf))
    try {
      const m = JSON.parse(buf.toString('utf8'))
      // Keystrokes arrive as binary frames; the messages a phone sends are its size and its scrolling.
      if (m?.t === 'resize') s.write(msg({ t: 'resize', cols: m.cols, rows: m.rows }))
      // Scrolling isn't using the terminal: it goes in as `keys`, so it doesn't take the size.
      else if (m?.t === 'scroll' && isScroll(m.data)) s.write(msg({ t: 'keys', data: m.data }))
    } catch {}
  })
  ws.on('close', () => s.destroy())
  ws.on('error', () => s.destroy())
}

/** What a phone may send as scrolling: mouse-wheel turns (SGR reports), PgUp, PgDn and Ctrl+End. Nothing that types. */
export function isScroll(data: unknown): data is string {
  return typeof data === 'string' && data.length > 0 && data.length <= 4096 && /^(?:\x1b\[<(?:6[4-9]|[78]\d|9[0-5]);\d{1,4};\d{1,4}M|\x1b\[[56]~|\x1b\[1;5F)+$/.test(data)
}

// ── The Mac ──────────────────────────────────────────────────────────────
export const HN_BIN = join(SUPPORT, 'bin', 'hn')
const q = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`

/** Write <support>/bin/hn for this daemon's Node and sources (the `claude` function calls it). */
export function writeHnBin(): void {
  mkdirSync(dirname(HN_BIN), { recursive: true })
  const cli = join(dirname(fileURLToPath(import.meta.url)), 'term', 'cli.ts')
  // A daemon running on its own state folder (development) hands it to hn too.
  const env = ['HYPERNOVA_SUPPORT', 'HYPERNOVA_LOGS'].filter((k) => process.env[k]).map((k) => `export ${k}=${q(process.env[k]!)}\n`).join('')
  // 97 tells the `claude` shell function to run plain claude (say, after Node moved).
  writeText(
    HN_BIN,
    `#!/bin/sh\n# Hypernova's shared terminals (written by the Hypernova daemon; \`hn\` for help).\n${env}N=${q(process.execPath)}\nC=${q(cli)}\n[ -x "$N" ] && [ -f "$C" ] || exit 97\nexec "$N" --disable-warning=ExperimentalWarning "$C" "$@"\n`,
    0o755,
  )
}

/** Claude Code's subcommands, so `claude mcp …` and friends never go through a shared terminal. */
export function refreshClaudeCommands(): void {
  const bin = findClaude()
  if (!bin) return
  execFile(bin, ['--help'], { timeout: 15_000 }, (e, out) => {
    if (e) return
    const tail = out.split(/^Commands:/m)[1] ?? ''
    const names = [...tail.matchAll(/^ {2}([a-z][\w|-]*)/gm)].flatMap((m) => m[1]!.split('|'))
    if (names.length) writeText(join(SUPPORT, 'claude-commands.json'), JSON.stringify(names) + '\n', 0o644)
  })
}

const APPS: Record<string, { name: string; bundle: string[] }> = {
  alacritty: { name: 'Alacritty', bundle: ['/Applications/Alacritty.app'] },
  terminal: { name: 'Terminal', bundle: ['/System/Applications/Utilities/Terminal.app', '/Applications/Utilities/Terminal.app'] },
  iterm: { name: 'iTerm', bundle: ['/Applications/iTerm.app'] },
  ghostty: { name: 'Ghostty', bundle: ['/Applications/Ghostty.app'] },
  wezterm: { name: 'WezTerm', bundle: ['/Applications/WezTerm.app'] },
  kitty: { name: 'kitty', bundle: ['/Applications/kitty.app'] },
}
const installed = (app: string) => APPS[app]?.bundle.find((b) => existsSync(b)) ?? null
const run = (cmd: string, args: string[]) =>
  new Promise<void>((res, rej) => execFile(cmd, args, { timeout: 10_000 }, (e, _o, err) => (e ? rej(new Error(err?.trim() || e.message)) : res())))

/** A terminal app that's open right now (when we don't know which one the owner uses). */
function runningTerminal(): string | null {
  try {
    const ps = execFileSync('/bin/ps', ['-axo', 'comm='], { encoding: 'utf8', timeout: 3000 })
    for (const [app, re] of [['alacritty', /Alacritty\.app/], ['iterm', /iTerm2?\.app/], ['ghostty', /Ghostty\.app/], ['wezterm', /WezTerm\.app/], ['kitty', /kitty\.app/], ['terminal', /Terminal\.app/]] as const) if (re.test(ps)) return app
  } catch {}
  return null
}

/** The IPC socket of a running Alacritty, so a new window opens in it rather than a second copy of the app. */
function alacrittySocket(known: string | null | undefined): string | null {
  if (known && existsSync(known)) return known
  try {
    const tmp = execFileSync('/usr/bin/getconf', ['DARWIN_USER_TEMP_DIR'], { encoding: 'utf8', timeout: 3000 }).trim()
    for (const f of readdirSync(tmp)) {
      const m = /^Alacritty-(\d+)\.sock$/.exec(f)
      if (m && alive(Number(m[1]))) return join(tmp, f)
    }
  } catch {}
  return null
}

/** Open a shared session in a terminal window on the Mac (the app it was started from, if known). */
export async function openOnMac(termId: string): Promise<{ app: string; already: boolean }> {
  // No screen and no terminal apps on a Linux server: `hn attach` over SSH is the way in there.
  if (!has('openWindow')) throw unavailable('openWindow')
  const t = terms.get(termId)
  if (!t) throw Object.assign(new Error('That session isn’t running in a shared terminal any more.'), { status: 404 })
  let app = [t.meta.terminal?.app, settings.terminalApp, runningTerminal(), 'terminal'].find((a): a is string => !!a && !!installed(a))!
  const bundle = installed(app)!
  if ((t.state?.tty ?? t.meta.tty) > 0) {
    // Already open there: bring it forward.
    await run('/usr/bin/open', ['-a', bundle]).catch(() => {})
    return { app: APPS[app]!.name, already: true }
  }
  const argv = [HN_BIN, 'attach', termId]
  try {
    if (app === 'alacritty') {
      const exe = join(bundle, 'Contents/MacOS/alacritty')
      const sock = alacrittySocket(t.meta.terminal?.socket)
      // A busy Alacritty sometimes drops the first message: one more try before falling back.
      const msgArgs = ['msg', '--socket', sock ?? '', 'create-window', '-e', ...argv]
      if (sock) await run(exe, msgArgs).catch(() => new Promise((r) => setTimeout(r, 400)).then(() => run(exe, msgArgs)))
      else await run('/usr/bin/open', ['-na', bundle, '--args', '-e', ...argv])
      await run('/usr/bin/open', ['-a', bundle]).catch(() => {})
    } else if (app === 'ghostty' || app === 'wezterm' || app === 'kitty') {
      const flag = app === 'wezterm' ? ['start', '--'] : ['-e']
      await run('/usr/bin/open', ['-na', bundle, '--args', ...flag, ...argv])
    } else {
      await openCommandFile(bundle, argv)
    }
  } catch (e) {
    log('open on mac failed with', app, e)
    if (app === 'terminal') throw Object.assign(new Error('Couldn’t open a terminal window on the Mac.'), { status: 500 })
    app = 'terminal'
    await openCommandFile(installed('terminal')!, argv)
  }
  log('opened shared terminal', termId, 'in', app)
  return { app: APPS[app]!.name, already: false }
}

/** Terminal.app and iTerm run a .command file they're given; no Automation permission needed. */
async function openCommandFile(bundle: string, argv: string[]): Promise<void> {
  const dir = join(SUPPORT, 'run')
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  for (const f of readdirSync(dir)) {
    try {
      if (Date.now() - statSync(join(dir, f)).mtimeMs > 60_000) unlinkSync(join(dir, f))
    } catch {}
  }
  const file = join(dir, `attach-${argv[2]}.command`)
  writeFileSync(file, `#!/bin/sh\nclear\nexec ${argv.map(q).join(' ')}\n`, { mode: 0o700 })
  await run('/usr/bin/open', ['-a', bundle, file])
}
