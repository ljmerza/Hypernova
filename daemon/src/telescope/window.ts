// Telescope's window source: the Mac app a session is building, as its window looks now. macOS
// gives Screen Recording to an app the owner can find and name in System Settings; the daemon is a
// node process under launchd, which would show there as "node" (and lose it with each Node
// update), so the Hypernova Mac app takes the picture (mac/…/Core/WindowCapture.swift). The daemon
// names the app by the processes running a program built in the session's folder, the Mac app
// checks each one's executable again and captures only windows those processes own, and hands the
// picture back on the local port (`POST /local/telescope/<job>`, admin token), where it becomes a
// token link like every other. Never by bundle id: a folder can hold a bundle that claims any app's
// id. Never by the name a process gives itself either: `ps` shows argv[0], which any program can set
// to a path in the session's folder, so each candidate's real executable (`lsof -d txt`) must be
// there too. Either would open another app's windows to the telescope, under the Mac app's Screen
// Recording.
import { readdirSync, realpathSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import { basename, isAbsolute, join, resolve, sep } from 'node:path'
import { HOME } from '../util.ts'
import { lsofNames, pngSize, MAX_PNG } from '../shots.ts'
import { toMacApps } from '../guide.ts'
import { fail, type Capture } from './common.ts'
import { runFile } from './run.ts'
import { has, unavailable } from '../platform.ts'

/** The processes, their apps' names, and the folders their programs must run from (for the Mac app to check again). */
export type AppTargets = { pids: number[]; names: string[]; roots: string[] }

/**
 * `ps -axww -o pid=,comm=`: each process and the program it runs, as it was started: a full path
 * for apps, a relative one for a program started that way from a shell (`.build/release/X`).
 * Bare names (found on the PATH) are left out.
 */
export function parsePs(out: string): Array<{ pid: number; exe: string }> {
  const list: Array<{ pid: number; exe: string }> = []
  for (const line of out.split('\n')) {
    const m = /^\s*(\d+)\s+(\S.*?)\s*$/.exec(line)
    if (m && m[2]!.includes('/')) list.push({ pid: Number(m[1]), exe: m[2]! })
  }
  return list
}

/** The working folders of these processes (`lsof -d cwd`), to resolve a program started by a relative path. */
async function workingFolders(pids: number[]): Promise<Map<number, string>> {
  return lsofFirst(pids, 'cwd')
}

/**
 * The program each of these processes really runs: the first `txt` file lsof lists for it is its
 * executable as the kernel loaded it, whatever argv[0] says.
 */
async function executables(pids: number[]): Promise<Map<number, string>> {
  return lsofFirst(pids, 'txt')
}

async function lsofFirst(pids: number[], fd: 'cwd' | 'txt'): Promise<Map<number, string>> {
  const out = new Map<number, string>()
  if (!pids.length) return out
  const r = await runFile('/usr/sbin/lsof', ['-a', '-p', pids.slice(0, 200).join(','), '-d', fd, '-Fn'], { timeout: 4000, max: 1024 * 1024 })
  for (const [pid, names] of lsofNames(r.out.toString('utf8'))) if (names[0]) out.set(pid, names[0])
  return out
}

/**
 * Folders whose programs are the Mac's or installed apps, never something a session builds: a
 * session there would claim every app in them (a session in /Applications, every installed app).
 */
export function systemFolder(root: string, home: string): boolean {
  const roots = ['/Applications', '/System', '/Library', '/usr', '/bin', '/sbin', '/opt', join(home, 'Applications'), join(home, 'Library')]
  return root === '/' || root === home || home.startsWith(root + sep) || roots.some((r) => root === r || root.startsWith(r + sep))
}

/** Xcode's build products of these projects: `DerivedData/<project>-<hash>/Build/Products`. */
function derivedProducts(home: string, names: string[]): string[] {
  if (!names.length) return []
  const derived = join(home, 'Library/Developer/Xcode/DerivedData')
  let entries: string[] = []
  try {
    entries = readdirSync(derived)
  } catch {
    return []
  }
  const out: string[] = []
  for (const e of entries) {
    if (!names.some((n) => e.startsWith(`${n}-`) && /^[a-z]{20,}$/.test(e.slice(n.length + 1)))) continue
    try {
      out.push(realpathSync(join(derived, e, 'Build/Products')))
    } catch {}
  }
  return out
}

/** The Xcode projects and workspaces in a folder (and one level down): their names. */
function xcodeNames(root: string): string[] {
  const names = new Set<string>()
  const look = (dir: string, deeper: boolean) => {
    let entries: string[] = []
    try {
      entries = readdirSync(dir)
    } catch {
      return
    }
    for (const e of entries) {
      const m = /^(.+)\.(?:xcodeproj|xcworkspace)$/.exec(e)
      if (m) names.add(m[1]!)
      else if (deeper && !e.startsWith('.') && e !== 'node_modules' && e !== 'Pods') look(join(dir, e), false)
    }
  }
  look(root, true)
  return [...names]
}

/** The program a process runs: inside an app bundle, the bundle's name ("Hypernova"), else its own. */
const appName = (exe: string) => /([^/]+)\.app\/Contents\/MacOS\//.exec(exe)?.[1] ?? basename(exe)

/**
 * The apps a session is building: processes running a program from inside its folder (`swift
 * run`, `.build/release/X`, `dist/X.app`), or from Xcode's build products of a project in it
 * (`~/Library/Developer/Xcode/DerivedData/<project>-…/Build/Products/`). `ps` only finds the
 * candidates (it shows argv[0], which a process sets itself); each counts only once its real
 * executable (`lsof -d txt`, then its real path) is in one of those folders too. Never for a session
 * in the home folder or above it, or in a folder of the Mac's or of installed apps, where every app
 * would count. `ps`, `folders` and `exes` stand in for the programs in tests.
 */
export async function appTargets(cwd: string, ps?: string, folders?: Map<number, string>, exes?: Map<number, string>): Promise<AppTargets> {
  const none = { pids: [], names: [], roots: [] }
  let root: string, home = HOME
  try {
    root = realpathSync(cwd)
    home = realpathSync(HOME)
  } catch {
    return none
  }
  if (systemFolder(root, home)) return none
  const roots = [root, ...derivedProducts(home, xcodeNames(root))]
  const ours = (p: string) => roots.some((r) => p.startsWith(r + sep))
  const realOf = (p: string) => {
    try {
      return realpathSync(p)
    } catch {
      return ''
    }
  }
  const listing = ps ?? (await runFile('/bin/ps', ['-axww', '-o', 'pid=,comm='], { timeout: 4000, max: 8 * 1024 * 1024 })).out.toString('utf8')
  const list = parsePs(listing).filter((p) => p.pid !== process.pid)
  const relative = list.filter((p) => !isAbsolute(p.exe)).map((p) => p.pid)
  const wd = folders ?? (await workingFolders(relative))
  // Candidates by what ps says…
  const candidates: number[] = []
  for (const p of list) {
    const exe = isAbsolute(p.exe) ? p.exe : wd.has(p.pid) ? resolve(wd.get(p.pid)!, p.exe) : ''
    const real = exe && realOf(exe)
    if (real && ours(real)) candidates.push(p.pid)
  }
  // …kept only when the program the kernel loaded is there as well.
  const loaded = exes ?? (await executables(candidates))
  const names = new Set<string>()
  const pids: number[] = []
  for (const pid of candidates) {
    const real = realOf(loaded.get(pid) ?? '')
    if (!real || !ours(real)) continue
    pids.push(pid)
    names.add(appName(real))
  }
  return pids.length ? { pids, names: [...names], roots } : none
}

/** What the Mac app sends back. */
export type WindowAnswer = { png?: string; app?: string; title?: string; error?: string; asked?: boolean }

const pending = new Map<string, (a: WindowAnswer) => void>()

/** The Mac app's answer to a job (the local route): false when no capture waits for it. */
export function windowAnswer(job: string, body: Buffer): boolean {
  const done = pending.get(job)
  if (!done) return false
  let a: WindowAnswer = { error: 'failed' }
  try {
    const j = JSON.parse(body.toString('utf8'))
    if (j && typeof j === 'object') a = j
  } catch {}
  done(a)
  return true
}

/** How long the Mac app gets: listing windows and one capture take well under a second. */
const ANSWER_MS = 15_000

/**
 * A window of the app the session builds. `ask`: the owner asked for windows by name, so the Mac
 * app may ask macOS for Screen Recording (once ever); when the telescope picks by itself, it never
 * puts that question on the Mac.
 */
export async function captureWindow(cwd: string, ask: boolean, targets?: AppTargets): Promise<Capture> {
  // Windows come through the Mac app's Screen Recording: a Linux server has neither.
  if (!has('appWindow')) throw unavailable('appWindow')
  const t = targets ?? (await appTargets(cwd))
  if (!t.pids.length) throw fail(404, 'no_app', 'This session isn’t building a Mac app that’s running.')
  const job = randomBytes(12).toString('hex')
  const answer = new Promise<WindowAnswer>((resolve) => {
    const timer = setTimeout(() => done({ error: 'timeout' }), ANSWER_MS)
    const done = (a: WindowAnswer) => {
      clearTimeout(timer)
      pending.delete(job)
      resolve(a)
    }
    pending.set(job, done)
  })
  // The folders go too: the Mac app checks each process's executable itself before it looks at a window.
  if (!toMacApps({ t: 'telescope.window', job, pids: t.pids, roots: t.roots, ask })) {
    pending.get(job)?.({ error: 'no_mac_app' })
    throw fail(503, 'no_mac_app', 'Open Hypernova in your Mac’s menu bar first: it takes the pictures of app windows.')
  }
  const a = await answer
  const app = a.app || t.names[0] || 'the app'
  if (a.error === 'no_permission')
    throw fail(403, 'screen_recording', a.asked
      ? 'Your Mac is asking now: allow Hypernova under Screen Recording, then try again.'
      : 'Allow Screen Recording on your Mac first: System Settings, Privacy & Security, Screen Recording, Hypernova.')
  // The Mac app checked the programs again and none runs from the session's folder (it went, or
  // something only claimed to).
  if (a.error === 'no_app') throw fail(404, 'no_app', 'This session isn’t building a Mac app that’s running.')
  if (a.error === 'no_window') throw fail(404, 'no_window', `No window of ${app} is open on the Mac.`)
  if (a.error === 'timeout') throw fail(504, 'capture_failed', 'The Mac app didn’t answer in time.')
  const png = typeof a.png === 'string' ? Buffer.from(a.png, 'base64') : Buffer.alloc(0)
  if (a.error || !pngSize(png) || png.length > MAX_PNG) throw fail(502, 'capture_failed', `The Mac couldn’t take a picture of ${app}.`)
  const title = String(a.title ?? '').replace(/\s+/g, ' ').trim().slice(0, 120)
  return { png, title: title ? `${app} · ${title}` : app, label: app }
}
