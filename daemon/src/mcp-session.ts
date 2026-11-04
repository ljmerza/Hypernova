// One session's MCP servers as that session has them, for the session sheet's MCP tab (docs/PROTOCOL.md, "A session's MCP
// connections"; docs/DECISIONS.md, "The session sheet's MCP tab"). mcp-probe.ts asks a process of its own, whose connections
// aren't the session's (measured on 2.1.317), so each kind is asked through what reaches it:
//
//   terminal, job  /mcp in its shared terminal (a job through `claude attach`): typed, read off the screen, closed.
//   turn           an SDK session in a turn: control requests on that turn's own process.
//   rest           an SDK session between turns: no process, so no connection. A toggle counts from its next turn.
//   codex          its thread on the shared app-server: `mcpServerStatus/list` with the thread, `config/mcpServer/reload`.
//   watched, ended nothing reaches it: what a new session in that folder would get, and no actions.
//
// Every action reads the state back, and that decides what the phone is told. Screens are never logged or sent on: only what
// mcp-screen.ts picks out of them, since a server's page prints its whole command.

import { agentJobOf, getSession } from './hub.ts'
import { claudeConfigFile, sessionMcp, type SessionMcpServer } from './agent-settings.ts'
import { attachAgentsTerm, attachDrifted, menusQuiet, pressKeys, termOf, termScreen, typeInto, type Typed } from './terms.ts'
import { isSdk, settings } from './store.ts'
import { isCodex } from './codex/threads.ts'
import { MACHINE } from './platform.ts'
import { log } from './util.ts'
import { McpRefused } from './mcp-edit.ts'
import { codexStatus, codexThreadStatus, codexToggle, reloadShared } from './mcp-codex.ts'
import { isDisabled, forgetTools } from './mcp-routes.ts'
import { forgetLive, LIVE_NAME, liveOf, liveServers, settled, toggleServer, type LiveServer, type LiveStatus, type LiveTool } from './mcp-probe.ts'
import { mcpHits, noMcpServers, readMcpList, readMcpPage, readMcpTools, showsMcpDialog, wholeName, type McpHit, type McpScreenRow } from './mcp-screen.ts'
import { claudeList, folderId, heldBack, maskText, readClaudeConfig } from './mcp-view.ts'
import { runningQuery } from './runner.ts'

export type SessionKind = 'terminal' | 'job' | 'turn' | 'rest' | 'codex' | 'watched' | 'ended'
export type McpAction = 'reconnect' | 'enable' | 'disable'
/** What really came of an action, by the state read back. */
export type McpOutcome = 'done' | 'failed' | 'no_change' | 'unconfirmed' | 'next_turn' | 'new_sessions'

export type SessionMcpRow = {
  name: string
  status: LiveStatus
  source: string
  toolCount: number | null
  tools?: LiveTool[]
  error?: string
  /** The terminal was too narrow for its whole name, and no file here has it. */
  cut?: true
  /** On in this session, turned off for new ones (by another session, or Settings). */
  offForNew?: true
  transport?: string
  command?: string
  host?: string
  actions: McpAction[]
  signIn: boolean
}
export type SessionMcpLive = {
  agent: 'claude' | 'codex'
  kind: SessionKind
  /** The rows are this session's own connections, not what a new one would get. */
  live: boolean
  folder: string
  servers: SessionMcpRow[]
  /** Why the rows couldn't be read this time, in words. */
  unread?: { code: string; words: string }
}
export type SessionMcpActed = { ok: true; action: McpAction; name: string; outcome: McpOutcome; said?: string; server: SessionMcpRow | null; reply: SessionMcpLive }

type SessionRec = NonNullable<ReturnType<typeof getSession>>

// ── The terminal ──────────────────────────────────────────────────────────

export type TermOps = {
  type: (tid: string, text: string) => Promise<Typed>
  keys: (tid: string, data: string) => boolean
  screen: (tid: string) => Promise<string | null>
}
const realOps: TermOps = { type: (tid, text) => typeInto(tid, text), keys: pressKeys, screen: async (tid) => (await termScreen(tid))?.text ?? null }
let ops = realOps
/** Tests play a terminal here. */
export const useTermOps = (o: TermOps | null) => {
  ops = o ?? realOps
}

let POLL_MS = 120
let OPEN_MS = 6000
/** Its slowest answer: a reconnect waits for the server, measured 20 s for one that takes 20 s, with nothing on the screen meanwhile. */
let ACT_MS = 45_000
let SETTLE_MS = 12_000
let SETTLE_POLL_MS = 1500
const KEY_MS = 30
const PAGES = 12
/** Tests needn't wait these out. */
export const useMcpTimes = (t: { poll?: number; open?: number; act?: number; settle?: number; settlePoll?: number }) => {
  POLL_MS = t.poll ?? POLL_MS
  OPEN_MS = t.open ?? OPEN_MS
  ACT_MS = t.act ?? ACT_MS
  SETTLE_MS = t.settle ?? SETTLE_MS
  SETTLE_POLL_MS = t.settlePoll ?? SETTLE_POLL_MS
}
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))
const DOWN = '\x1b[B'
const ESC = '\x1b'

/** One look at a terminal at a time, and while it lasts no menu of it goes to the phones as a question (terms.ts, watchMenus). */
const chains = new Map<string, Promise<unknown>>()
function oneAtATime<T>(tid: string, work: () => Promise<T>): Promise<T> {
  const run = (chains.get(tid) ?? Promise.resolve()).catch(() => {}).then(async () => {
    menusQuiet.add(tid)
    try {
      return await work()
    } finally {
      menusQuiet.delete(tid)
    }
  })
  chains.set(tid, run)
  void run.catch(() => {}).finally(() => {
    if (chains.get(tid) === run) chains.delete(tid)
  })
  return run
}

function typedRefusal(r: Typed): McpRefused {
  const why: Record<string, [string, string]> = {
    no_input: ['waiting', 'Claude is showing something in the terminal (a question or a menu). Open the terminal to answer it, then try again.'],
    busy_input: ['typing', `There’s unsent text in this session’s terminal on the ${MACHINE}. Send or clear it there first.`],
    not_started: ['starting', 'The session is still starting. Try again in a moment.'],
    not_seen: ['not_sent', 'The command is in the terminal’s input box but wasn’t sent. Open the terminal to check it.'],
    not_sent: ['not_sent', 'The command is in the terminal’s input box but wasn’t sent. Open the terminal to check it.'],
    unreachable: ['terminal_busy', 'The terminal didn’t answer. Try again in a moment.'],
  }
  const [code, words] = why[r.reason ?? 'unreachable'] ?? why.unreachable!
  return new McpRefused(409, code, words)
}
const UNSEEN = () => new McpRefused(409, 'unseen', 'The terminal didn’t show its MCP screen. Open the terminal to look.')
const noneOn = (text: string | null) => (text?.match(/No MCP servers configured\./g) ?? []).length

/** Closes whatever of /mcp is open, a level at a time. Esc only while its dialog shows: with none, Esc would stop Claude's turn. */
async function closeDialog(tid: string): Promise<void> {
  for (let i = 0; i < 6; i++) {
    const text = await ops.screen(tid)
    if (!text || !showsMcpDialog(text)) return
    ops.keys(tid, ESC)
    const t0 = Date.now()
    while (Date.now() - t0 < 1500) {
      await sleep(POLL_MS)
      const now = await ops.screen(tid)
      if (now !== text) break
    }
  }
}

/** Opens /mcp and hands [work] its list's first screen. Null: this session has no servers at all. It's closed again whatever happens. */
async function withDialog<T>(tid: string, work: (first: string) => Promise<T>): Promise<T | null> {
  const before = noneOn(await ops.screen(tid))
  const typed = await ops.type(tid, '/mcp')
  if (!typed.ok) throw typedRefusal(typed)
  const t0 = Date.now()
  try {
    while (Date.now() - t0 < OPEN_MS) {
      const text = await ops.screen(tid)
      if (text && readMcpList(text)) return await work(text)
      if (noneOn(text) > before) return null
      await sleep(POLL_MS)
    }
    throw UNSEEN()
  } finally {
    await closeDialog(tid)
  }
}

async function press(tid: string, key: string, times: number): Promise<void> {
  for (let i = 0; i < times; i++) {
    ops.keys(tid, key)
    await sleep(KEY_MS)
  }
}

/** Every row of the open list, paging through a list too long for the screen. */
async function allRows(tid: string, first: string): Promise<McpScreenRow[]> {
  const rows = new Map<string, McpScreenRow>()
  let list = readMcpList(first)!
  for (let page = 0; page < PAGES; page++) {
    for (const r of list.rows) if (!rows.has(r.name)) rows.set(r.name, r)
    if (!list.moreBelow) break
    await press(tid, DOWN, Math.max(1, list.rows.length))
    await sleep(POLL_MS)
    const text = await ops.screen(tid)
    const next = text ? readMcpList(text) : null
    if (!next) break
    list = next
  }
  return [...rows.values()]
}

/** The session's rows, off its /mcp. */
const readRows = (tid: string): Promise<McpScreenRow[]> => withDialog(tid, (first) => allRows(tid, first)).then((r) => r ?? [])

/** Moves the cursor down to [fits], one row at a time, checking where it landed. */
async function cursorTo(tid: string, fits: (r: McpScreenRow) => boolean): Promise<boolean> {
  for (let step = 0; step < 400; step++) {
    const text = await ops.screen(tid)
    const list = text ? readMcpList(text) : null
    if (!list) return false
    const at = list.rows.find((r) => r.cursor)
    if (at && fits(at)) return true
    ops.keys(tid, DOWN)
    const t0 = Date.now()
    while (Date.now() - t0 < 800) {
      await sleep(KEY_MS)
      if ((await ops.screen(tid)) !== text) break
    }
  }
  return false
}

/** The tools this session's connection has: its /mcp › the server › View tools. */
async function toolsOnScreen(tid: string, name: string, cut: string | null): Promise<LiveTool[] | null> {
  const r = await withDialog(tid, async () => {
    if (!(await cursorTo(tid, (row) => row.name === (cut ?? name)))) return null
    ops.keys(tid, '\r')
    let page = null
    for (const t0 = Date.now(); Date.now() - t0 < OPEN_MS && !page; ) {
      await sleep(POLL_MS)
      const text = await ops.screen(tid)
      page = text ? readMcpPage(text) : null
    }
    if (!page) return null
    const at = page.options.indexOf('View tools')
    if (at < 0) return []
    await press(tid, DOWN, Math.max(0, at - page.cursor))
    ops.keys(tid, '\r')
    const tools = new Map<string, LiveTool>()
    let screen = null
    for (const t0 = Date.now(); Date.now() - t0 < OPEN_MS && !screen; ) {
      await sleep(POLL_MS)
      const text = await ops.screen(tid)
      screen = text ? readMcpTools(text) : null
    }
    for (let i = 0; screen && i < PAGES * 4; i++) {
      for (const t of screen.tools) {
        const n = maskText(t.name)
        if (!tools.has(n)) tools.set(n, { name: n, ...(t.readOnly ? { readOnly: true } : {}), ...(t.destructive ? { destructive: true } : {}), ...(t.openWorld ? { openWorld: true } : {}) })
      }
      if (!screen.moreBelow) break
      await press(tid, DOWN, Math.max(1, screen.tools.length))
      await sleep(POLL_MS)
      const text = await ops.screen(tid)
      screen = text ? readMcpTools(text) : null
    }
    return [...tools.values()]
  })
  return r
}

/** What's on the screen for this action now, and how many answers there already were. */
async function waitForAnswer(tid: string, action: McpAction, name: string, already: number): Promise<McpHit | null> {
  const t0 = Date.now()
  while (Date.now() - t0 < ACT_MS) {
    const text = await ops.screen(tid)
    if (text === null) return null
    const hits = mcpHits(text, action, name)
    if (hits.length > already) return hits[hits.length - 1]!
    await sleep(POLL_MS)
  }
  return null
}

/** Whether what's read back is what [action] was for. */
export function outcomeOf(action: McpAction, status: LiveStatus | null, hit: McpHit | null): McpOutcome {
  if (hit?.said === 'other_session') return 'no_change'
  if (status === null) return 'failed'
  if (action === 'disable') return status === 'disabled' ? 'done' : 'failed'
  if (action === 'enable') return status === 'connected' || status === 'pending' ? 'done' : 'failed'
  // A reconnect that says nothing: it's connected, but not known to be a new connection.
  if (status !== 'connected') return 'failed'
  return hit?.said === 'ok' ? 'done' : hit ? 'failed' : 'unconfirmed'
}

// ── Rows ──────────────────────────────────────────────────────────────────

/** What this kind can do for a server in this state. A connected Codex server has no reconnect: its reload leaves those alone (measured). */
export function offers(kind: SessionKind, live: boolean, status: LiveStatus, o: { cut?: boolean; offForNew?: boolean } = {}): McpAction[] {
  if (kind === 'watched' || kind === 'ended' || o.cut || status === 'waiting' || status === 'rejected') return []
  if (o.offForNew) return ['enable', 'disable']
  if (status === 'disabled') return ['enable']
  if (!live) return ['disable']
  if (status === 'pending') return ['disable']
  if (kind === 'codex' && status === 'connected') return ['disable']
  return ['reconnect', 'disable']
}

async function details(cwd: string): Promise<Map<string, SessionMcpServer>> {
  try {
    return new Map((await sessionMcp(cwd)).servers.map((s) => [s.name, s]))
  } catch {
    return new Map()
  }
}

function rowOf(kind: SessionKind, live: boolean, s: { name: string; status: LiveStatus; source: string; toolCount: number | null; tools?: LiveTool[]; error?: string; cut?: boolean; offForNew?: boolean }, more: SessionMcpServer | undefined): SessionMcpRow {
  return {
    name: s.name,
    status: s.status,
    source: s.source,
    toolCount: s.toolCount,
    ...(s.tools ? { tools: s.tools } : {}),
    ...(s.error ? { error: s.error } : {}),
    ...(s.cut ? { cut: true as const } : {}),
    ...(s.offForNew ? { offForNew: true as const } : {}),
    ...(more?.transport ? { transport: more.transport } : {}),
    ...(more?.command ? { command: more.command } : {}),
    ...(more?.host ? { host: more.host } : {}),
    actions: offers(kind, live, s.status, s),
    // A connector of a claude.ai account signs in on claude.ai.
    signIn: s.status === 'needs-auth' && kind !== 'watched' && kind !== 'ended' && !s.name.startsWith('claude.ai '),
  }
}

const liveRow = (kind: SessionKind, live: boolean, s: LiveServer, more: SessionMcpServer | undefined) => rowOf(kind, live, { ...s, toolCount: s.tools.length }, more)

/** A terminal's rows: whole names, what the folder's files add, and the project's servers it never started (it doesn't list those). */
async function terminalRows(kind: SessionKind, rec: SessionRec, screen: McpScreenRow[]): Promise<SessionMcpRow[]> {
  const more = await details(rec.cwd)
  const { root } = await readClaudeConfig(claudeConfigFile())
  const names = screen.some((r) => r.cut) ? (await claudeList([rec.cwd])).servers.map((s) => s.name) : []
  const rows: SessionMcpRow[] = []
  for (const r of screen) {
    const name = r.cut ? (wholeName(r.name, names) ?? r.name) : r.name
    const cut = r.cut && name === r.name
    if (!cut && !LIVE_NAME.test(name)) continue
    rows.push(rowOf(kind, true, { name, status: r.status, source: r.source, toolCount: r.toolCount, cut, offForNew: !cut && r.status !== 'disabled' && (await isDisabled(root, rec.cwd, name)) }, more.get(name)))
  }
  const held = await heldBack(rec.cwd)
  for (const name of held.waiting) if (!rows.some((r) => r.name === name)) rows.push(rowOf(kind, true, { name, status: 'waiting', source: 'project', toolCount: null }, more.get(name)))
  for (const name of held.rejected) if (!rows.some((r) => r.name === name)) rows.push(rowOf(kind, true, { name, status: 'rejected', source: 'project', toolCount: null }, more.get(name)))
  return rows
}

/** What a new session in the folder would get, from a process of its own: for what has no connection to ask. */
async function folderRows(kind: SessionKind, rec: SessionRec, fresh: boolean): Promise<SessionMcpRow[]> {
  const held = await heldBack(rec.cwd)
  const more = await details(rec.cwd)
  return (await liveServers(rec.cwd, held.waiting, held.rejected, fresh)).map((s) => liveRow(kind, false, s, more.get(s.name)))
}

// ── Which kind ────────────────────────────────────────────────────────────

/** Which kind a session is, from what the daemon knows of it. */
export function kindFrom(s: { ended: boolean; codex: boolean; sdk: boolean; turn: boolean; term: boolean; job: boolean }): SessionKind {
  if (s.ended) return 'ended'
  if (s.codex) return 'codex'
  if (s.sdk) return s.turn ? 'turn' : 'rest'
  if (s.job) return 'job'
  return s.term ? 'terminal' : 'watched'
}

const sessionOf = (id: string): SessionRec => {
  const rec = getSession(id)
  if (!rec) throw new McpRefused(404, 'no_session', 'That session no longer exists.')
  return rec
}

function kindOf(rec: SessionRec): SessionKind {
  const job = settings.claudeAgents === true && !!agentJobOf(rec.id)
  return kindFrom({ ended: rec.state === 'ended', codex: isCodex(rec.id), sdk: isSdk(rec.id), turn: !!runningQuery(rec.id), term: !!termOf(rec.id), job })
}

/** The terminal to type in: a job's through `claude attach`, one that shows another session replaced or refused. */
async function terminalOf(rec: SessionRec): Promise<string> {
  let tid = termOf(rec.id)
  const job = settings.claudeAgents === true && (!tid || attachDrifted(tid, rec.id)) ? agentJobOf(rec.id) : null
  if (job) {
    tid = await attachAgentsTerm(rec.id, rec.cwd, job)
    if (!tid) throw new McpRefused(409, 'terminal_busy', 'The background session’s terminal didn’t open. Try again in a moment.')
  }
  if (!tid) throw new McpRefused(409, 'no_terminal', 'This session isn’t running in a shared terminal.')
  if (attachDrifted(tid, rec.id)) throw new McpRefused(409, 'terminal_busy', 'The terminal shows another session. Try again in a moment.')
  return tid
}

const TURN_ASK_MS = 15_000
const limited = <T>(p: Promise<T>, ms: number): Promise<T> =>
  new Promise((ok, no) => {
    const timer = setTimeout(() => no(new Error('The turn didn’t answer.')), ms)
    p.then(
      (v) => {
        clearTimeout(timer)
        ok(v)
      },
      (e) => {
        clearTimeout(timer)
        no(e)
      },
    )
  })

async function turnRows(rec: SessionRec, q: NonNullable<ReturnType<typeof runningQuery>>, ms = 3000): Promise<SessionMcpRow[]> {
  const more = await details(rec.cwd)
  const raw = await limited(settled(q, ms), TURN_ASK_MS)
  return raw.flatMap((r) => liveOf(r) ?? []).map((s) => liveRow('turn', true, s, more.get(s.name)))
}

async function codexRows(id: string, fresh: boolean): Promise<{ live: boolean; rows: SessionMcpRow[] }> {
  const t = await codexThreadStatus(id)
  if (t) return { live: true, rows: t.map((s) => liveRow('codex', true, s, undefined)) }
  return { live: false, rows: (await codexStatus(fresh)).servers.map((s) => liveRow('codex', false, s, undefined)) }
}

// ── The routes ────────────────────────────────────────────────────────────

/** GET /v1/sessions/{id}/mcp/live: the session's servers, however that session can be asked. */
export async function sessionMcpLive(id: string, o: { fresh?: boolean } = {}): Promise<SessionMcpLive> {
  const rec = sessionOf(id)
  const kind = kindOf(rec)
  const folder = folderId(rec.cwd)
  const base = { agent: isCodex(id) ? ('codex' as const) : ('claude' as const), kind, folder }
  switch (kind) {
    case 'codex': {
      const { live, rows } = await codexRows(id, !!o.fresh)
      return { ...base, live, servers: rows }
    }
    case 'terminal':
    case 'job':
      try {
        const tid = await terminalOf(rec)
        const rows = await oneAtATime(tid, () => readRows(tid))
        return { ...base, live: true, servers: await terminalRows(kind, rec, rows) }
      } catch (e) {
        if (e instanceof McpRefused && e.status === 409) return { ...base, live: true, servers: [], unread: { code: e.code, words: e.message } }
        throw e
      }
    case 'turn': {
      const q = runningQuery(id)
      if (q) {
        try {
          return { ...base, live: true, servers: await turnRows(rec, q) }
        } catch (e) {
          // The turn ended as it was asked: it's at rest now.
          log('session mcp: a turn didn’t answer', wordsOf(e))
        }
      }
      return { ...base, kind: 'rest', live: false, servers: await folderRows('rest', rec, !!o.fresh) }
    }
    default:
      return { ...base, live: false, servers: await folderRows(kind, rec, !!o.fresh) }
  }
}

/** GET /v1/sessions/{id}/mcp/live/tools: one server's tools, from where its status came. */
export async function sessionMcpTools(id: string, name: string): Promise<{ name: string; live: boolean; tools: LiveTool[] }> {
  if (!LIVE_NAME.test(name)) throw new McpRefused(400, 'bad_name', 'That isn’t a server’s name.')
  const rec = sessionOf(id)
  const kind = kindOf(rec)
  if (kind === 'terminal' || kind === 'job') {
    const tid = await terminalOf(rec)
    return oneAtATime(tid, async () => {
      // The screen may cut the name: it's the row that starts with it.
      const rows = await readRows(tid)
      const row = rows.find((r) => r.name === name) ?? rows.find((r) => r.cut && name.startsWith(r.name.slice(0, -1)))
      if (!row) throw new McpRefused(404, 'no_server', 'That server isn’t in this session.')
      const tools = await toolsOnScreen(tid, name, row.name === name ? null : row.name)
      if (tools === null) throw UNSEEN()
      return { name, live: true, tools }
    })
  }
  const reply = await sessionMcpLive(id)
  const row = reply.servers.find((r) => r.name === name)
  if (!row) throw new McpRefused(404, 'no_server', 'That server isn’t in this session.')
  return { name, live: reply.live, tools: row.tools ?? [] }
}

const actedOn = (reply: SessionMcpLive, action: McpAction, name: string, outcome: McpOutcome, said?: string | null): SessionMcpActed => {
  forgetLive()
  forgetTools()
  return { ok: true, action, name, outcome, ...(said ? { said } : {}), server: reply.servers.find((r) => r.name === name) ?? null, reply }
}

async function actInTerminal(rec: SessionRec, kind: SessionKind, action: McpAction, name: string): Promise<SessionMcpActed> {
  const tid = await terminalOf(rec)
  const base = { agent: 'claude' as const, kind, live: true, folder: folderId(rec.cwd) }
  return oneAtATime(tid, async () => {
    const before = await readRows(tid)
    const rows = await terminalRows(kind, rec, before)
    const row = rows.find((r) => r.name === name)
    if (!row || row.status === 'waiting' || row.status === 'rejected') throw new McpRefused(404, 'no_server', 'That server isn’t in this session. One added or approved since it started needs Restart session.')
    // It's what /mcp takes for every server.
    if (name === 'all') throw new McpRefused(409, 'cut_name', 'A server called “all” can’t be told apart from every server here. Open the terminal to use its screen.')
    if (row.cut) throw new McpRefused(409, 'cut_name', 'The terminal is too narrow for this server’s whole name. Open the terminal to use its screen.')
    let outcome: McpOutcome | null = null
    let hit: McpHit | null = null
    // Another session or Settings turned it off for new sessions: /mcp won't change that from here, and only the file needs to.
    if (action === 'enable' && row.offForNew) {
      const held = await heldBack(rec.cwd)
      await toggleServer(rec.cwd, held.waiting, name, true)
      outcome = 'new_sessions'
    } else {
      const already = mcpHits((await ops.screen(tid)) ?? '', action, name).length
      const typed = await ops.type(tid, `/mcp ${action} ${name}`)
      if (!typed.ok) throw typedRefusal(typed)
      log(`session mcp: ${action} ${name} typed in ${rec.id.slice(0, 8)}`)
      hit = await waitForAnswer(tid, action, name, already)
    }
    // The answer isn't what's told: the state read back is.
    const t0 = Date.now()
    for (;;) {
      let after: McpScreenRow[]
      try {
        after = await readRows(tid)
      } catch (e) {
        if (!(e instanceof McpRefused) || e.status !== 409) throw e
        return actedOn({ ...base, servers: [], unread: { code: e.code, words: e.message } }, action, name, 'unconfirmed', hit?.words)
      }
      const now = after.find((r) => r.name === name)
      if (now?.status === 'pending' && action !== 'disable' && Date.now() - t0 < SETTLE_MS) {
        await sleep(SETTLE_POLL_MS)
        continue
      }
      const reply = { ...base, servers: await terminalRows(kind, rec, after) }
      return actedOn(reply, action, name, outcome ?? outcomeOf(action, now?.status ?? null, hit), hit?.words)
    }
  })
}

const wordsOf = (e: unknown) => maskText(String((e as Error)?.message ?? e)).slice(0, 300)

async function actInTurn(rec: SessionRec, q: NonNullable<ReturnType<typeof runningQuery>>, action: McpAction, name: string): Promise<SessionMcpActed> {
  let said: string | null = null
  try {
    if (action === 'reconnect') await limited(q.reconnectMcpServer(name), ACT_MS)
    else await limited(q.toggleMcpServer(name, action === 'enable'), TURN_ASK_MS)
  } catch (e) {
    said = wordsOf(e)
  }
  const base = { agent: 'claude' as const, kind: 'turn' as const, live: true, folder: folderId(rec.cwd) }
  const servers = await turnRows(rec, q, action === 'disable' ? 500 : SETTLE_MS)
  const now = servers.find((r) => r.name === name)
  const ok = action === 'disable' ? now?.status === 'disabled' : now?.status === 'connected' || (action === 'enable' && now?.status === 'pending')
  log(`session mcp: ${action} ${name} on the turn of ${rec.id.slice(0, 8)}: ${ok ? 'took' : 'didn’t take'}`)
  return actedOn({ ...base, servers }, action, name, ok ? 'done' : 'failed', said)
}

/** POST /v1/sessions/{id}/mcp/live: {action, name}. Answers with what's read back. */
export async function sessionMcpAct(id: string, action: unknown, name: unknown): Promise<SessionMcpActed> {
  if (action !== 'reconnect' && action !== 'enable' && action !== 'disable') throw new McpRefused(400, 'bad_action', 'Say reconnect, enable or disable.')
  if (typeof name !== 'string' || !LIVE_NAME.test(name)) throw new McpRefused(400, 'bad_name', 'That isn’t a server’s name.')
  const rec = sessionOf(id)
  const kind = kindOf(rec)
  switch (kind) {
    case 'watched':
      throw new McpRefused(409, 'not_here', 'This session’s terminal isn’t shared, so nothing here reaches its connections.')
    case 'ended':
      throw new McpRefused(409, 'not_here', 'This session has ended, so it has no connections to change.')
    case 'terminal':
    case 'job':
      return actInTerminal(rec, kind, action, name)
    case 'codex':
      return actOnCodex(rec, action, name)
    case 'turn': {
      const q = runningQuery(id)
      if (q) return actInTurn(rec, q, action, name)
    }
    // The turn just ended.
    // falls through
    default:
      return actAtRest(rec, action, name)
  }
}

/** Between an SDK session's turns nothing is connected: a toggle is the folder's list, read at its next turn. */
async function actAtRest(rec: SessionRec, action: McpAction, name: string): Promise<SessionMcpActed> {
  if (action === 'reconnect') throw new McpRefused(409, 'not_here', 'This session connects again at its next turn: it has no process until then.')
  const held = await heldBack(rec.cwd)
  if (held.waiting.includes(name) || held.rejected.includes(name)) throw new McpRefused(409, 'needs_approval', 'Approve this one in Settings first.')
  await toggleServer(rec.cwd, held.waiting, name, action === 'enable')
  forgetLive(rec.cwd)
  const reply: SessionMcpLive = { agent: 'claude', kind: 'rest', live: false, folder: folderId(rec.cwd), servers: await folderRows('rest', rec, true) }
  return actedOn(reply, action, name, 'next_turn')
}

/** Codex: on and off is everywhere, and its reload is for the whole app-server, so it reconnects what's down and leaves the rest. */
async function actOnCodex(rec: SessionRec, action: McpAction, name: string): Promise<SessionMcpActed> {
  const base = { agent: 'codex' as const, kind: 'codex' as const, folder: folderId(rec.cwd) }
  if (action === 'reconnect') {
    if (!(await reloadShared())) throw new McpRefused(409, 'not_here', 'Hypernova isn’t connected to Codex’s shared app-server, so nothing here reaches this thread.')
  } else await codexToggle(name, action === 'enable')
  const t0 = Date.now()
  for (;;) {
    const { live, rows } = await codexRows(rec.id, true)
    const now = rows.find((r) => r.name === name)
    if (live && now?.status === 'pending' && action !== 'disable' && Date.now() - t0 < SETTLE_MS) {
      await sleep(SETTLE_POLL_MS)
      continue
    }
    const reply = { ...base, live, servers: rows }
    if (!live) return actedOn(reply, action, name, 'next_turn')
    const ok = action === 'disable' ? now?.status === 'disabled' : now?.status === 'connected'
    return actedOn(reply, action, name, ok ? 'done' : 'failed', now?.error)
  }
}
