// Seeing, checking and updating Claude Code and Codex from a paired phone (docs/PROTOCOL.md, "Updating an agent";
// docs/DECISIONS.md, "Updating Claude Code and Codex"). Only the program's own updater runs: `claude update` or
// `codex update`, a fixed command with nothing from the phone in it. Everything the phone sends is which agent, and the claim.
//
// Reading the version costs one `--version`. A check asks the program how it was installed (`claude doctor`, `codex doctor
// --json`), of which only named lines are kept, and where the newest is: Codex's doctor says, and Claude Code has no way to
// ask without installing, so it's the file `claude update` itself reads. A check runs when someone taps it.
//
// The update's output goes to a file, not a pipe: the daemon can restart while it runs (the service's KillMode=process leaves
// it alone), and a closed pipe could end the updater halfway. What's on that file only reaches the phone scrubbed, and it's
// deleted at the end. Whatever the updater says, the version is read back: `claude update` says "Successfully updated" and
// exits 0 when npm did nothing, and exits 0 when Homebrew manages it or another Claude holds its lock (all measured).
import { execFile, spawn } from 'node:child_process'
import { accessSync, closeSync, constants, existsSync, mkdirSync, openSync, readFileSync, realpathSync, rmSync, statfsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { CODEX_CHECKED, CODEX_MIN, atLeast, findCodex } from './codex/binary.ts'
import { dbgProc } from './debug-log.ts'
import { scrubText } from './debug-scrub.ts'
import { McpRefused, runCli } from './mcp-edit.ts'
import { childEnv } from './runner.ts'
import { HOME, SUPPORT, findClaude, forgetClaudeVersion, log, readJson, writeJson } from './util.ts'
import { MACHINE } from './platform.ts'

type Agent = 'claude' | 'codex'

/** What a check found: how it's installed, what can be done about it, and the newest there is. */
export type Plan = { how: string; words: string; can: boolean; why: string | null; command: string | null }
type Check = Plan & { at: number; latest: string | null; problem: string | null; channel: string | null; autoUpdates: boolean | null }
export type UpdateState = 'running' | 'updated' | 'unchanged' | 'failed'
type Job = { state: UpdateState; from: string | null; to: string | null; startedAt: number; endedAt: number | null; message: string | null; output: string[]; pid?: number }
type Saved = { check?: Check | null; job?: Job | null }

/** Where `claude update` itself reads the newest version: `<channel>` is a file holding it (in its error text and `--debug`, 2.1.316). A test points it elsewhere. */
export const CLAUDE_RELEASES = () => process.env.HYPERNOVA_CLAUDE_RELEASES ?? 'https://downloads.claude.ai/claude-code-releases'
const LOOKUP_MS = 8_000
const DOCTOR_MS = 45_000
/** Claude Code gives its own download 10 minutes; npm's or an installer's can be slower, and this is the outer limit. */
const UPDATE_MS = () => Number(process.env.HYPERNOVA_AGENT_UPDATE_MS) || 15 * 60_000
const KILL_AFTER_MS = 5_000
/** A second tap of Check within this answers from the last one. */
const CHECK_FRESH_MS = 5 * 60_000
/** An update whose daemon has gone and whose process can't be seen waits for it this often. */
const WATCH_MS = 2_000
const OUTPUT_LINES = 40
const LINE_MAX = 200
const OUTPUT_READ = 64 * 1024

const NAMES: Record<Agent, string> = { claude: 'Claude Code', codex: 'Codex' }
const savedFile = () => join(SUPPORT, 'agent-updates.json')
const outputFile = (agent: Agent) => join(SUPPORT, 'agent-updates', `${agent}.log`)

let saved: Partial<Record<Agent, Saved>> | null = null
const state = (agent: Agent): Saved => {
  saved ??= readJson<Partial<Record<Agent, Saved>>>(savedFile(), {})
  return (saved[agent] ??= {})
}
const save = () => {
  try {
    writeJson(savedFile(), saved ?? {})
  } catch (e) {
    log('agent update: couldn’t save its state:', e instanceof Error ? e.message : String(e))
  }
}

// ── Reading the version ──────────────────────────────────────────────────

const binOf = (agent: Agent): string | null => (agent === 'claude' ? findClaude() : findCodex())

/** What the program says it is now, asked fresh: `codex-cli 0.164.0`, `2.1.316 (Claude Code)`. */
export const versionIn = (out: string): string | null => /(\d+\.\d+\.\d+[\w.-]*)/.exec(out)?.[1] ?? null
function readVersion(bin: string): Promise<string | null> {
  return new Promise((resolve) => execFile(bin, ['--version'], { timeout: 15_000, encoding: 'utf8', env: childEnv({}) }, (e, out) => resolve(e ? null : versionIn(out))))
}

/** Part by part, so 2.1.99 is older than 2.1.316; a pre-release's words count as nothing. */
export const isNewer = (a: string, b: string): boolean => !atLeast(b, a)

// ── How it was installed ─────────────────────────────────────────────────

/** The program's real file, a link followed, or [bin] when it can't be. */
const realOf = (bin: string) => {
  try {
    return realpathSync(bin)
  } catch {
    return bin
  }
}

/** npm's global folder over [real], for asking whether this user can write to it. Null when it isn't under one. */
export function npmFolderOf(real: string): string | null {
  const at = real.indexOf('/lib/node_modules/')
  return at < 0 ? null : real.slice(0, at + '/lib/node_modules'.length)
}
const writable = (dir: string) => {
  try {
    accessSync(dir, constants.W_OK)
    return true
  } catch {
    return false
  }
}

/** `claude doctor`'s lines, the only ones kept (2.1.316's words; a later wording falls back to the path). */
export function parseClaudeDoctor(text: string): { running: string | null; manager: string | null; channel: string | null; updates: string | null } {
  const line = (name: string) => new RegExp(`^${name}:\\s*(.+)$`, 'm').exec(text)?.[1]?.trim() ?? null
  return {
    running: line('Running')?.split(/\s/)[0] ?? null,
    manager: line('Package manager'),
    channel: /^(latest|stable)\b/.exec(line('Auto-update channel') ?? '')?.[1] ?? null,
    updates: line('Auto-updates'),
  }
}

/** The same question by where the file is, for a doctor that doesn't answer: the marks Claude Code's own detection uses. */
export function claudeKindOf(real: string): { running: string; manager: string | null } {
  if (real.includes('/Caskroom/')) return { running: 'package-manager', manager: 'homebrew' }
  if (/[/\\]mise[/\\]installs[/\\]/i.test(real)) return { running: 'package-manager', manager: 'mise' }
  if (/[/\\]\.?asdf[/\\]installs[/\\]/i.test(real)) return { running: 'package-manager', manager: 'asdf' }
  if (real.includes('/.claude/local/')) return { running: 'npm-local', manager: null }
  if (real.includes('/node_modules/@anthropic-ai/')) return { running: 'npm-global', manager: null }
  if (real.includes('/.local/share/claude/versions/')) return { running: 'native', manager: null }
  return { running: 'unknown', manager: null }
}

/** Which Homebrew cask [real] is in: it's the one to upgrade, and it decides the channel. Only the two Anthropic names; never a word from elsewhere. */
export const caskOf = (real: string): 'claude-code' | 'claude-code@latest' => (real.includes('/Caskroom/claude-code@latest/') ? 'claude-code@latest' : 'claude-code')

const REFUSED_BY_MANAGER: Record<string, { words: string; command: string | null; root: boolean }> = {
  deb: { words: 'apt', command: 'sudo apt update && sudo apt upgrade claude-code', root: true },
  rpm: { words: 'dnf', command: 'sudo dnf upgrade claude-code', root: true },
  apk: { words: 'apk', command: 'apk update && apk upgrade claude-code', root: true },
  pacman: { words: 'pacman', command: null, root: true },
  mise: { words: 'mise', command: null, root: false },
  asdf: { words: 'asdf', command: null, root: false },
}

/** What can be done with this Claude Code. Only `claude update` ever runs here; a command in the answer is for a person, at a terminal. */
export function planClaude(running: string | null, manager: string | null, updates: string | null, real: string, canWrite: (dir: string) => boolean = writable): Plan {
  const off = /DISABLE_UPDATES/.test(updates ?? '')
  const plan = (how: string, words: string, can: boolean, why: string | null = null, command: string | null = null): Plan => ({ how, words, can: can && !off, why: off ? 'Updates are turned off on this server (DISABLE_UPDATES), so Claude Code won’t update itself.' : why, command: off ? null : command })
  switch (running) {
    case 'native':
      return plan('native', 'the native installer', true)
    case 'npm-local':
      return plan('npm', 'a local npm install', true)
    case 'npm-global': {
      const dir = npmFolderOf(real)
      if (dir && !canWrite(dir)) return plan('npm', 'npm', false, 'npm’s global folder isn’t writable by this user, so this can’t update from here. Run this as whoever owns that folder.', 'npm install -g @anthropic-ai/claude-code@latest')
      return plan('npm', 'npm', true)
    }
    case 'package-manager': {
      if (manager === 'homebrew') return plan('homebrew', 'Homebrew', false, 'Homebrew manages this one, and Claude Code’s own update only prints this command. Run it at a terminal.', `brew upgrade ${caskOf(real)}`)
      const m = REFUSED_BY_MANAGER[manager ?? '']
      if (m) return plan(manager!, m.words, false, m.root ? `A system package from ${m.words}: updating it needs root, so it can’t update from here.` : `${m.words} manages this one, so update it there.`, m.command)
      return plan('other', 'a package manager', false, 'A package manager handles this one, so update it there.')
    }
    case 'development':
      return plan('other', 'a development build', false, 'This is a development build, which doesn’t update itself.')
    default:
      return plan('other', 'a way this can’t tell', false, 'Couldn’t tell how this was installed, so it won’t be updated from here. Run this at a terminal.', 'claude update')
  }
}

/** `codex doctor --json`'s `updates.status`: only these fields. */
export function parseCodexDoctor(out: string): { action: string | null; latest: string | null } | null {
  let j: any
  try {
    j = JSON.parse(out.slice(Math.max(0, out.indexOf('{'))))
  } catch {
    return null
  }
  const d = j?.checks?.['updates.status']?.details
  if (!d || typeof d !== 'object') return null
  const word = (v: unknown) => (typeof v === 'string' ? v : null)
  const latest = word(d['latest version'])
  return { action: word(d['update action']), latest: latest && versionIn(latest) === latest ? latest : null }
}

/** `update action`, as 0.164.0 words it, to what's said here. Anything else is a kind this doesn't know: not offered. */
const CODEX_ACTIONS: Record<string, { how: string; words: string; npm?: true }> = {
  'npm install -g @openai/codex': { how: 'npm', words: 'npm', npm: true },
  'bun install -g @openai/codex': { how: 'bun', words: 'bun' },
  'pnpm add -g @openai/codex': { how: 'pnpm', words: 'pnpm' },
  'vp install -g @openai/codex': { how: 'vp', words: 'Vite+' },
  'brew upgrade --cask codex': { how: 'homebrew', words: 'Homebrew' },
  'standalone installer': { how: 'standalone', words: 'Codex’s own installer' },
}
export function planCodex(action: string | null, real: string, canWrite: (dir: string) => boolean = writable): Plan {
  const known = action ? CODEX_ACTIONS[action] : undefined
  if (!known) return { how: 'other', words: 'a way Codex can’t tell', can: false, why: 'Codex can’t tell how this one was installed, so it can’t update itself. Its guide: developers.openai.com/codex/cli', command: null }
  const dir = known.npm ? npmFolderOf(real) : null
  if (dir && !canWrite(dir)) return { how: known.how, words: known.words, can: false, why: 'npm’s global folder isn’t writable by this user, so this can’t update from here. It needs a person, at a terminal.', command: 'sudo npm install -g @openai/codex@latest' }
  return { how: known.how, words: known.words, can: true, why: null, command: null }
}
/** One older than Hypernova's floor may have no `doctor` or `update` at all (0.39.0 takes either for a prompt), so neither is run. */
export function planOldCodex(real: string): Plan {
  const npm = npmFolderOf(real) !== null
  return { how: npm ? 'npm' : 'other', words: npm ? 'npm' : 'a way this can’t tell', can: false, why: `This Codex is older than the ${CODEX_MIN} Hypernova needs and may not have its own update, so it isn’t run from here.${npm ? ' Run this at a terminal.' : ' Its guide: developers.openai.com/codex/cli'}`, command: npm ? 'npm install -g @openai/codex@latest' : null }
}

// ── A check ──────────────────────────────────────────────────────────────

async function claudeLatest(channel: string): Promise<string> {
  const res = await fetch(`${CLAUDE_RELEASES()}/${channel}`, { signal: AbortSignal.timeout(LOOKUP_MS) })
  if (!res.ok) throw new Error(`status ${res.status}`)
  const v = (await res.text()).trim()
  if (versionIn(v) !== v) throw new Error('not a version')
  return v
}

async function checkClaude(bin: string): Promise<Omit<Check, 'at'>> {
  const real = realOf(bin)
  const doctor = parseClaudeDoctor((await runCli(bin, ['doctor'], HOME, {}, undefined, DOCTOR_MS)).stdout)
  const byPath = claudeKindOf(real)
  const running = doctor.running ?? byPath.running
  const manager = doctor.manager ?? byPath.manager
  const plan = planClaude(running, manager, doctor.updates, real)
  // Homebrew goes by the cask's name, whatever the setting says (its docs and its update's code); everything else by the doctor's line.
  const channel = plan.how === 'homebrew' ? (caskOf(real) === 'claude-code@latest' ? 'latest' : 'stable') : (doctor.channel ?? 'latest')
  const autoUpdates = doctor.updates === null ? null : doctor.updates.startsWith('enabled')
  try {
    return { ...plan, channel, autoUpdates, latest: await claudeLatest(channel), problem: null }
  } catch (e) {
    log('agent update: claude lookup failed:', e instanceof Error ? e.message : String(e))
    return { ...plan, channel, autoUpdates, latest: null, problem: 'Couldn’t reach Claude Code’s release list from this server.' }
  }
}

async function checkCodex(bin: string): Promise<Omit<Check, 'at'>> {
  const real = realOf(bin)
  const version = await readVersion(bin)
  if (!version || !atLeast(version, CODEX_MIN)) return { ...planOldCodex(real), channel: null, autoUpdates: null, latest: null, problem: null }
  const r = await runCli(bin, ['doctor', '--json'], HOME, {}, undefined, DOCTOR_MS)
  const d = parseCodexDoctor(r.stdout)
  const plan = planCodex(d?.action ?? null, real)
  return { ...plan, channel: null, autoUpdates: null, latest: d?.latest ?? null, problem: d?.latest ? null : 'Codex couldn’t say what its newest version is.' }
}

// ── What the phone sees ──────────────────────────────────────────────────

/** What an update does to what's running, in words for the confirm. Each was measured: docs/DECISIONS.md, "Updating Claude Code and Codex". */
export function effectOf(agent: Agent, how: string): string {
  if (agent === 'codex') return 'Codex sessions already running keep the version they started with, and so does Codex’s app server, which moves to the new one when it next restarts. Hypernova doesn’t restart it.'
  if (how === 'native') return 'Sessions already running stay on the version they started with, and new ones start on the new one. Claude Code’s background service restarts itself and keeps its jobs; Home marks a job on an older version.'
  return 'npm replaces the program’s files in place. Sessions already running keep the version they started with, and new ones start on the new one.'
}

/** A network disk's type, from statfs: a running session reads its file as it works, and Claude Code's own docs warn of this one. */
const NETWORK_FS = new Set([0x6969 /* nfs */, 0x517b /* smb */, 0xff534d42 /* cifs */, 0xfe534d42 /* smb2 */, 0x65735546 /* fuse */, 0x01021997 /* 9p */, 0xc36400 /* ceph */, 0x5346414f /* afs */, 0x0bd00bd0 /* lustre */])
export const onNetworkDisk = (type: number) => NETWORK_FS.has(type)
function networkWarning(real: string): string | null {
  try {
    return onNetworkDisk(Number(statfsSync(dirname(real)).type)) ? 'This program is on a network disk. A session reads its file as it works, so replacing it can crash sessions running from it, here or on another machine.' : null
  } catch {
    return null
  }
}

/** Codex past the newest version this daemon was checked against: not refused, as later ones are taken, but said. */
export function codexWarning(latest: string | null): string | null {
  if (!latest || !isNewer(latest, CODEX_CHECKED)) return null
  return `This Hypernova was last checked against Codex ${CODEX_CHECKED}, and ${latest} is newer. Later versions aren’t refused, but they’re unproven here. If its app server stops working with Hypernova, Codex’s card says so.`
}

function tailOutput(agent: Agent): string[] {
  try {
    const raw = readFileSync(outputFile(agent))
    return cleanLines(raw.subarray(Math.max(0, raw.length - OUTPUT_READ)).toString('utf8'))
  } catch {
    return []
  }
}

/** The updater's words as a phone may see them: colours gone, each line scrubbed and cut, the last few. */
export function cleanLines(text: string): string[] {
  // eslint-disable-next-line no-control-regex
  const plain = text.replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, '').replace(/\u001b\][^\u0007]*\u0007/g, '')
  const lines = plain.split(/[\r\n]+/).map((l) => l.trim()).filter(Boolean).map((l) => {
    const s = scrubText(l)
    return s.length > LINE_MAX ? `${s.slice(0, LINE_MAX - 1)}…` : s
  })
  return lines.slice(-OUTPUT_LINES)
}

function jobView(agent: Agent, job: Job | null | undefined) {
  if (!job) return null
  const { pid: _pid, ...shown } = job
  return job.state === 'running' ? { ...shown, output: tailOutput(agent) } : shown
}

/** The answer to all three routes. */
export async function versionView(agent: Agent, running: number) {
  const bin = binOf(agent)
  const s = state(agent)
  const job = jobView(agent, s.job)
  if (!bin) return { agent, installed: false, version: null, running: 0, how: null, words: null, channel: null, autoUpdates: null, latest: null, checkedAt: null, problem: null, newer: false, canUpdate: false, why: null, command: null, effect: null, warning: null, job }
  const version = await readVersion(bin)
  const c = s.check
  const newer = !!(c?.latest && version && isNewer(c.latest, version))
  const warnings = [c?.can ? networkWarning(realOf(bin)) : null, agent === 'codex' && newer ? codexWarning(c?.latest ?? null) : null].filter(Boolean)
  return {
    agent,
    installed: true,
    version,
    running,
    how: c?.how ?? null,
    words: c?.words ?? null,
    channel: c?.channel ?? null,
    autoUpdates: c?.autoUpdates ?? null,
    latest: c?.latest ?? null,
    checkedAt: c?.at || null,
    problem: c?.problem ?? null,
    newer,
    canUpdate: !!c?.can && newer,
    why: c?.why ?? null,
    command: c?.command ?? null,
    effect: c?.can ? effectOf(agent, c.how) : null,
    warning: warnings.length ? warnings.join(' ') : null,
    job,
  }
}

const checking = new Map<Agent, Promise<void>>()
async function runCheck(agent: Agent, bin: string): Promise<void> {
  const hit = state(agent).check
  if (hit && Date.now() - hit.at < CHECK_FRESH_MS) return
  let going = checking.get(agent)
  if (!going) {
    going = (agent === 'claude' ? checkClaude(bin) : checkCodex(bin))
      .then((c) => {
        state(agent).check = { ...c, at: Date.now() }
        save()
      })
      .finally(() => checking.delete(agent))
    checking.set(agent, going)
  }
  await going
}

// ── The update ───────────────────────────────────────────────────────────

let announce: (agent: Agent) => void = () => {}

/** The line of what it said that tells most: the last that sounds like trouble, else the last. */
export function tellingLine(lines: readonly string[]): string | null {
  const trouble = /error|fail|denied|permission|another|disabled|unable|not found|couldn|cannot|can.t/i
  return [...lines].reverse().find((l) => trouble.test(l)) ?? lines[lines.length - 1] ?? null
}

/** What came of it. [from] and [to] are what the program said before and after; the exit code alone says nothing (see the top of the file). */
export function outcomeOf(agent: Agent, from: string | null, to: string | null, exit: number | null, timedOut: boolean, lines: readonly string[], appServer: string | null = null): { state: Exclude<UpdateState, 'running'>; message: string } {
  const name = NAMES[agent]
  const said = tellingLine(lines)
  const saying = said ? ` It said: ${said}` : ''
  if (!to) return { state: 'failed', message: `${name} didn’t say its version after the update, so it may need a look at a terminal.${saying}` }
  if (to !== from) {
    const after = agent === 'codex' && appServer && appServer !== to ? ` Its app server is still on ${appServer} until it restarts.` : agent === 'codex' ? '' : ' Sessions already running stay on the old one until they restart.'
    return { state: 'updated', message: `${name} is now ${to}${from ? `, was ${from}` : ''}.${after}${exit === 0 ? '' : ` The updater also reported a problem.${saying}`}` }
  }
  if (timedOut) return { state: 'failed', message: `${name}’s update was stopped after ${Math.round(UPDATE_MS() / 60_000)} minutes. It’s still ${to}.${saying}` }
  if (exit !== 0) return { state: 'failed', message: `${name}’s update didn’t finish. It’s still ${to}.${saying}` }
  return { state: 'unchanged', message: `${name} is still ${to}: the update changed nothing.${saying}` }
}

/** The running app server's version, which a new Codex doesn't move (measured on 0.164.0). Null when there's none, or it can't say. */
function appServerVersion(bin: string): Promise<string | null> {
  return new Promise((resolve) =>
    execFile(bin, ['app-server', 'daemon', 'version'], { timeout: 15_000, encoding: 'utf8', env: childEnv({}) }, (e, out) => {
      if (e) return resolve(null)
      try {
        const j = JSON.parse(out.slice(Math.max(0, out.indexOf('{'))))
        resolve(j?.status === 'running' && typeof j.appServerVersion === 'string' && versionIn(j.appServerVersion) === j.appServerVersion ? j.appServerVersion : null)
      } catch {
        resolve(null)
      }
    }),
  )
}

async function finish(agent: Agent, exit: number | null, timedOut: boolean): Promise<void> {
  const s = state(agent)
  const job = s.job
  if (!job || job.state !== 'running') return
  const lines = tailOutput(agent)
  const bin = binOf(agent)
  if (agent === 'claude') forgetClaudeVersion()
  const to = bin ? await readVersion(bin) : null
  const appServer = agent === 'codex' && bin && to !== job.from ? await appServerVersion(bin) : null
  const out = outcomeOf(agent, job.from, to, exit, timedOut, lines, appServer)
  const { pid: _pid, ...rest } = job
  s.job = { ...rest, ...out, to, endedAt: Date.now(), output: lines }
  // The check said what was newest then, and a fresh look is the honest next answer.
  s.check = s.check ? { ...s.check, at: 0 } : s.check
  save()
  rmSync(outputFile(agent), { force: true })
  log(`agent update: ${agent} ${out.state}, ${job.from ?? '?'} to ${to ?? '?'}, exit ${exit ?? 'unknown'}${timedOut ? ', timed out' : ''}. ${tellingLine(lines) ?? ''}`)
  announce(agent)
}

const alive = (pid: number | undefined) => {
  if (!pid) return false
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

function watch(agent: Agent, pid: number, startedAt: number): void {
  const t = setInterval(() => {
    const over = Date.now() - startedAt > UPDATE_MS() + KILL_AFTER_MS * 2
    if (alive(pid) && !over) return
    clearInterval(t)
    void finish(agent, null, over)
  }, WATCH_MS)
  t.unref()
}

/** At the daemon's start: an update left running by the last one finishes here, by what the version says, and whoever's listening hears. */
export function startAgentUpdates(onChange: (agent: Agent) => void): void {
  announce = onChange
  for (const agent of ['claude', 'codex'] as const) {
    const job = state(agent).job
    if (job?.state !== 'running') continue
    log(`agent update: ${agent} was running when the daemon stopped, watching pid ${job.pid ?? '?'}`)
    watch(agent, job.pid ?? 0, job.startedAt)
  }
}

async function startUpdate(agent: Agent, bin: string): Promise<void> {
  const s = state(agent)
  // Said before anything is awaited, so a second tap can't get in.
  s.job = { state: 'running', from: null, to: null, startedAt: Date.now(), endedAt: null, message: null, output: [] }
  const from = await readVersion(bin)
  mkdirSync(dirname(outputFile(agent)), { recursive: true, mode: 0o700 })
  const fd = openSync(outputFile(agent), 'w', 0o600)
  const args = ['update']
  const ended = dbgProc(bin, args)
  // Its own bin folder first: npm's update runs whichever `npm` is on PATH, and a service's PATH may reach another Node's.
  const child = spawn(bin, args, { cwd: HOME, env: childEnv({ PATH: `${dirname(bin)}:${process.env.PATH ?? ''}` }), stdio: ['ignore', fd, fd], detached: true })
  closeSync(fd)
  s.job = { ...s.job, from, ...(child.pid ? { pid: child.pid } : {}) }
  save()
  log(`agent update: ${agent} started from ${from ?? '?'} (${s.check?.how ?? '?'})`)
  let timedOut = false
  const stopAt = setTimeout(() => {
    timedOut = true
    const group = child.pid ? -child.pid : 0
    if (!group) return
    try {
      process.kill(group, 'SIGTERM')
    } catch {}
    setTimeout(() => {
      try {
        process.kill(group, 'SIGKILL')
      } catch {}
    }, KILL_AFTER_MS).unref()
  }, UPDATE_MS())
  stopAt.unref()
  let done = false
  const end = (code: number | null, signal: string | null) => {
    if (done) return
    done = true
    clearTimeout(stopAt)
    ended(code, signal)
    void finish(agent, code, timedOut)
  }
  child.once('error', () => end(null, null))
  child.once('exit', end)
  child.unref()
  announce(agent)
}

// ── Routes ───────────────────────────────────────────────────────────────

export type VersionDeps = { body: () => Promise<any>; running: (agent: Agent) => number }

const agentOf = (v: unknown): Agent => {
  if (v === 'claude' || v === 'codex') return v
  throw new McpRefused(400, 'bad_agent', 'That isn’t an agent this can update.')
}
const notInstalled = (agent: Agent) => new McpRefused(409, agent === 'claude' ? 'no_claude' : 'no_codex', `${NAMES[agent]} isn’t installed on this ${MACHINE}.`)

/** docs/PROTOCOL.md, "Routes: updating an agent". */
export async function versionRoute(path: string, m: string, url: URL, res: unknown, json: (res: any, status: number, body: unknown) => void, deps: VersionDeps): Promise<boolean> {
  if (path === '/v1/agents/version' && m === 'GET') {
    const agent = agentOf(url.searchParams.get('agent'))
    json(res, 200, await versionView(agent, deps.running(agent)))
    return true
  }
  if (m !== 'POST' || (path !== '/v1/agents/version/check' && path !== '/v1/agents/version/update')) return false
  const b = await deps.body()
  const agent = agentOf(b?.agent)
  if (path === '/v1/agents/version/check') {
    const bin = binOf(agent)
    if (bin) await runCheck(agent, bin)
    json(res, 200, await versionView(agent, deps.running(agent)))
    return true
  }
  // Updating runs code on the server, and this is the one thing the server can hold it to (docs/SECURITY-MODEL.md, 22).
  if (b?.confirmRun !== true) throw new McpRefused(403, 'confirm_run', `Updating ${NAMES[agent]} runs its updater on the server. Confirm it with your fingerprint or screen lock.`)
  const bin = binOf(agent)
  if (!bin || !existsSync(bin)) throw notInstalled(agent)
  if (state(agent).job?.state === 'running') throw new McpRefused(409, 'update_running', `${NAMES[agent]} is already updating here. Its result will be on this page.`)
  const c = state(agent).check
  if (!c) throw new McpRefused(409, 'check_first', 'Check for an update first, so the confirm can say what it does.')
  if (!c.can) throw new McpRefused(409, 'cant_update', c.why ?? `${NAMES[agent]} can’t update from here.`, { command: c.command })
  await startUpdate(agent, bin)
  json(res, 200, await versionView(agent, deps.running(agent)))
  return true
}

/** Tests only: forget what's in memory, so a test can start from the file or from nothing. */
export const forgetAgentUpdates = () => {
  saved = null
  checking.clear()
  announce = () => {}
}
