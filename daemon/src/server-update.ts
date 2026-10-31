// The server updating itself (owner, 2026-10-31; docs/PROTOCOL.md, "The server's own update";
// docs/LINUX.md, "Updating"). Off unless settings.autoUpdate is true. The release check
// (releases.ts) hands each release it reads here, after the APK's part, so the phones already have
// the new app on offer. When the release is newer than this daemon's VERSION:
//
//   1. only on an install the installer manages ([installKind]: a Linux install in <support>/app,
//      put there from a release's package, run by its systemd user service), never a checkout, a
//      test copy or a container;
//   2. the release's server package (`hypernova-server-<tag>-linux.tar.gz`) is downloaded and its
//      SHA-256 checked against the note's `serverSha256` (a note without one: nothing installed),
//      then unpacked into a new folder and its daemon's version checked against the tag;
//   3. then it waits for a quiet moment: no session working or starting, no approval waiting, for 2
//      minutes on end (24 hours at most, then it goes ahead: the shared terminals outlive the
//      restart, docs/PROTOCOL.md, "Shared terminals");
//   4. and hands the install to a transient systemd unit of its own (`systemd-run --user`,
//      server-update.sh), outside the daemon's service, so the restart can't cut it off: the
//      package's own `scripts/install-linux.sh --from <it>`, which swaps the new daemon in only once
//      its terminal module works and restarts the service; a new daemon that doesn't answer is
//      swapped back out for the old one.
//
// Everything before step 4 leaves the running server as it is; a failure is a line in the log and
// a try again later (1 hour, 6, 24, then not for that release). The new daemon, at start, sees the
// update it was waiting for (<support>/server-update.json) and says so: the log, and `last` in the
// snapshot's `mac.update`.
import { execFile } from 'node:child_process'
import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, realpathSync, renameSync, rmSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { promisify } from 'node:util'
import { downloadTo, type ServerRelease } from './releases.ts'
import { settings, saveSettings } from './store.ts'
import { HOME, LOCAL_PORT, LOGS, SUPPORT, VERSION, log, readJson, writeJson } from './util.ts'
import { PLATFORM, type Platform, inContainer, systemdUserDir } from './platform.ts'
import { CARRIED, userBusEnv } from './cli/service.ts'

const run = promisify(execFile)

/** The package is the daemon's sources and the font: a few MB. */
const TARBALL_MAX = 128 * 1024 * 1024
export const QUIET_FOR = 2 * 60_000
export const MAX_WAIT = 24 * 3600_000
const POLL = 15_000
/** After a failure, the same release again in 1 hour, then 6, then 24; after the fourth, not again. */
const BACKOFF = [3600_000, 6 * 3600_000, 24 * 3600_000]
/** A started install that hasn't come back in this long didn't take. */
const PENDING_FOR = 3600_000
/** The transient unit's name: one at a time (systemd refuses a second while one runs). */
export const UPDATE_UNIT = 'hypernova-update'

export type Managed = { ok: true } | { ok: false; reason: string }
export type Busy = { working: number; approvals: number }
/** What the install needs: the unpacked package and where to write how it went. */
export type ApplyJob = { tag: string; from: string; to: string; unpacked: string; workDir: string; result: string; logFile: string }
export type LastUpdate = { from: string; to: string; at: number }
/** The snapshot's `mac.update`, `{t:"update"}` and the routes' answer (docs/PROTOCOL.md). */
export type UpdateView = {
  /** settings.autoUpdate. */
  auto: boolean
  /** This install can update itself; else [reason] says why not, in a sentence. */
  available: boolean
  reason: string | null
  /** idle; waiting for a quiet moment with [target] downloaded and checked; installing [target]. */
  state: 'idle' | 'waiting' | 'installing'
  target: string | null
  /** The last update this server installed by itself. */
  last: LastUpdate | null
}
export type ServerOutcome =
  | { kind: 'off' }
  | { kind: 'unavailable'; reason: string }
  | { kind: 'not-a-version'; tag: string }
  | { kind: 'not-newer'; tag: string; running: string }
  | { kind: 'no-package'; tag: string }
  | { kind: 'later'; tag: string; until: number | null }
  | { kind: 'busy' }
  | { kind: 'mismatch'; tag: string }
  | { kind: 'error'; tag: string; message: string }
  | { kind: 'cancelled'; tag: string }
  | { kind: 'launched'; tag: string; forced: boolean }

type Saved = {
  /** An install handed to the unit, waiting for the new daemon to start. */
  pending?: { tag: string; from: string; to: string; at: number } | null
  last?: LastUpdate | null
  /** Failures for one release: how many, and not before when (null: not again). */
  fails?: { tag: string; count: number; next: number | null } | null
}

/** `v1.2.3` or `1.2.3` as three numbers; null for anything else (a pre-release, a build, a name). */
export function semver(v: string): [number, number, number] | null {
  const m = /^v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})$/.exec(v.trim())
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null
}
/** [a] is a higher version than [b]. */
export function newer(a: [number, number, number], b: [number, number, number]): boolean {
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i]! > b[i]!
  return false
}

/** Where the installer put the daemon (cli/context.ts: HYPERNOVA_APP, else <support>/app). */
export function appDirOf(env: NodeJS.ProcessEnv = process.env, support = SUPPORT): string {
  const a = env.HYPERNOVA_APP
  return a && a.startsWith('/') ? a : join(support, 'app')
}

/** The marker `hypernova setup` leaves in the daemon it installs (cli/install.ts installDaemon). */
export type InstallMarker = { source: 'package' | 'checkout'; version?: string; at?: number }
export const MARKER = 'install.json'

export type InstallFacts = {
  platform: Platform
  env: NodeJS.ProcessEnv
  /** The running daemon's own folder (daemon/). */
  self: string
  appDir: string
  unitFile: string
  exists?: (p: string) => boolean
  read?: (p: string) => string
  real?: (p: string) => string
  which?: (name: string) => boolean
}

/**
 * Whether this install can update itself, and if not, why (a sentence for the log, the phone and
 * `hypernova auto-update`). It must be the installer's own: on Linux, outside a container, this
 * daemon running from <app>/daemon, which `hypernova setup` put there from a release's package
 * (its install.json says `package`: a checkout installed with `scripts/install-linux.sh` or
 * `hnctl update` says `checkout`, and an install from before the marker has none), started by
 * its systemd user service (INVOCATION_ID, and the unit running this daemon's main.ts), with
 * systemd-run to run the update outside it.
 */
export function installKind(f: InstallFacts): Managed {
  const exists = f.exists ?? existsSync
  const read = f.read ?? ((p: string) => readFileSync(p, 'utf8'))
  const real = f.real ?? ((p: string) => { try { return realpathSync(p) } catch { return resolve(p) } })
  const which = f.which ?? ((n: string) => ['/usr/bin', '/bin', '/usr/local/bin'].some((d) => existsSync(join(d, n))))
  if (f.platform !== 'linux') return { ok: false, reason: 'Servers update themselves on Linux only.' }
  if (inContainer(f.env, exists)) return { ok: false, reason: 'This server runs in a container: build its image again to update it (docs/LINUX.md, "Updating the container").' }
  const installed = join(f.appDir, 'daemon')
  if (real(f.self) !== real(installed)) return { ok: false, reason: 'This daemon doesn’t run from its installed copy (a checkout or a test copy): update it the way it was started.' }
  let marker: InstallMarker | null = null
  try {
    marker = JSON.parse(read(join(installed, MARKER))) as InstallMarker
  } catch {}
  if (!marker) return { ok: false, reason: 'Installed before servers could update themselves: install the latest release once (get.sh), and it can from then on.' }
  if (marker.source !== 'package') return { ok: false, reason: 'Installed from a checkout: update it from there (git pull, then scripts/install-linux.sh).' }
  if (!f.env.INVOCATION_ID) return { ok: false, reason: 'This daemon isn’t run by its systemd service, so nothing would start the new version.' }
  let unit = ''
  try {
    unit = read(f.unitFile)
  } catch {}
  if (!unit.includes(join(installed, 'src', 'main.ts'))) return { ok: false, reason: 'The systemd service doesn’t run this install: run hypernova setup.' }
  if (!which('systemd-run')) return { ok: false, reason: 'No systemd-run here to run the update outside the daemon.' }
  return { ok: true }
}

/** This daemon's own install, as [installKind] judges it. */
export function thisInstall(): Managed {
  return installKind({
    platform: PLATFORM,
    env: process.env,
    self: resolve(import.meta.dirname, '..'),
    appDir: appDirOf(),
    unitFile: join(systemdUserDir(process.env, HOME), 'hypernova.service'),
  })
}

export type UpdaterOptions = {
  version?: string
  /** <support>/updates: the downloads, the unpacked package, the unit's result. */
  dir?: string
  /** <support>/server-update.json */
  stateFile?: string
  logFile?: string
  /** Default: settings.autoUpdate === true. */
  enabled?: () => boolean
  managed?: () => Managed
  busy?: () => Busy
  /** Starts the install outside the daemon (default: [launchUnit]). Throws when it couldn't. */
  apply?: (job: ApplyJob) => Promise<void>
  /** Something in the view changed: the phones hear it (main.ts: {t:"update"}). */
  changed?: () => void
  now?: () => number
  sleep?: (ms: number) => Promise<void>
  quietMs?: number
  maxWaitMs?: number
  pollMs?: number
  /** The package's download (default 10 minutes). */
  timeoutMs?: number
  /** How often to look for the unit's result while an install runs (default 5 s; 0: not at all). */
  watchMs?: number
}

/** One updater (the daemon has one, [startServerUpdate]; tests make their own). */
export function serverUpdater(o: UpdaterOptions = {}) {
  const version = o.version ?? VERSION
  const dir = o.dir ?? join(SUPPORT, 'updates')
  const stateFile = o.stateFile ?? join(SUPPORT, 'server-update.json')
  const logFile = o.logFile ?? join(LOGS, 'update.log')
  const enabled = o.enabled ?? (() => settings.autoUpdate === true)
  const now = o.now ?? Date.now
  const sleep = o.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms).unref()))
  const quietMs = o.quietMs ?? QUIET_FOR
  const maxWaitMs = o.maxWaitMs ?? MAX_WAIT
  const pollMs = o.pollMs ?? POLL
  const watchMs = o.watchMs ?? 5000
  const busy = o.busy ?? (() => ({ working: 0, approvals: 0 }))
  const apply = o.apply ?? launchUnit
  const changed = o.changed ?? (() => {})
  // The install's verdict is looked at again at most every minute (a stat and a read or two).
  let managedAt = 0
  let managedWas: Managed | null = null
  const managed = o.managed ?? (() => {
    if (!managedWas || now() - managedAt > 60_000) {
      managedWas = thisInstall()
      managedAt = now()
    }
    return managedWas
  })

  let saved: Saved = readJson<Saved>(stateFile, {})
  let state: UpdateView['state'] = saved.pending ? 'installing' : 'idle'
  let target: string | null = saved.pending?.tag ?? null
  let attempt: Promise<ServerOutcome> | null = null
  let lastLogged = ''
  let watching: ReturnType<typeof setInterval> | null = null

  const save = () => writeJson(stateFile, saved)
  const set = (s: UpdateView['state'], t: string | null) => {
    if (s === state && t === target) return
    state = s
    target = t
    changed()
  }
  const say = (line: string, again = false) => {
    if (!again && line === lastLogged) return
    lastLogged = line
    log(line)
  }

  function view(): UpdateView {
    const m = managed()
    return { auto: enabled(), available: m.ok, reason: m.ok ? null : m.reason, state, target, last: saved.last ?? null }
  }

  /** A failure for [tag]: the next try waits (1 h, 6 h, 24 h, then never for this release). */
  function failed(tag: string, why: string): void {
    const count = saved.fails?.tag === tag ? saved.fails.count + 1 : 1
    const next = count > BACKOFF.length ? null : now() + BACKOFF[count - 1]!
    saved.fails = { tag, count, next }
    save()
    say(`server update to ${tag} failed: ${why}; ${next == null ? 'not trying this release again (turn the setting off and on to try again)' : `trying again after ${new Date(next).toISOString()}`}`, true)
  }

  /** The unit's verdict, `{tag, code, at}` (server-update.sh), or null while it runs. */
  const resultFile = join(dir, 'result.json')
  function result(tag: string): { code: number; at: number } | null {
    const r = readJson<{ tag?: string; code?: number; at?: number } | null>(resultFile, null)
    return r && r.tag === tag && typeof r.code === 'number' ? { code: r.code, at: r.at ?? 0 } : null
  }
  const RESULT_WORDS: Record<number, string> = {
    3: 'couldn’t keep a copy of the daemon to go back to; nothing changed',
    4: 'the new daemon didn’t answer, so the old one is back',
  }

  /**
   * An install handed out earlier: done (this daemon is its version), failed (the unit said so,
   * or it's been an hour), or still running (looked at again until one of those).
   */
  function settle(): 'updated' | 'failed' | 'running' | 'none' {
    const p = saved.pending
    if (!p) return 'none'
    if (version === p.to) {
      saved.last = { from: p.from, to: p.to, at: now() }
      saved.pending = null
      saved.fails = null
      save()
      say(`this server updated itself from ${p.from} to ${p.to} (${p.tag})`, true)
      set('idle', null)
      return 'updated'
    }
    const r = result(p.tag)
    if ((r && r.code !== 0) || now() - p.at > PENDING_FOR || (r && r.code === 0)) {
      saved.pending = null
      save()
      const why = !r ? 'it didn’t finish within an hour' : r.code === 0 ? 'it installed, but this daemon wasn’t restarted into it' : (RESULT_WORDS[r.code] ?? `the install stopped (exit ${r.code})`)
      failed(p.tag, `${why}; still ${version} (${logFile})`)
      set('idle', null)
      return 'failed'
    }
    return 'running'
  }
  function watch(): void {
    if (watching || !watchMs) return
    watching = setInterval(() => {
      if (settle() !== 'running' && watching) {
        clearInterval(watching)
        watching = null
      }
    }, watchMs)
    watching.unref()
  }

  /** At start: an update this daemon is the result of, or one that didn't take. True when it just updated. */
  function started(): boolean {
    const s = settle()
    if (s === 'running') watch()
    return s === 'updated'
  }

  /** Waits for no session working or starting and no approval waiting, [quietMs] on end; [maxWaitMs] at most. */
  async function quiet(tag: string): Promise<'quiet' | 'forced' | 'cancelled'> {
    const start = now()
    let since: number | null = null
    for (let told = false; ; ) {
      if (!enabled()) return 'cancelled'
      const b = busy()
      if (b.working === 0 && b.approvals === 0) {
        since ??= now()
        if (now() - since >= quietMs) return 'quiet'
      } else {
        since = null
        if (!told) {
          told = true
          say(`server update to ${tag}: waiting for a quiet moment (${b.working} working, ${b.approvals} waiting for an answer)`, true)
        }
      }
      if (now() - start >= maxWaitMs) return 'forced'
      await sleep(pollMs)
    }
  }

  async function attemptFor(r: ServerRelease, wanted: [number, number, number]): Promise<ServerOutcome> {
    const tag = r.tag
    if (!r.tarball || !r.sha256) {
      say(`release ${tag} is newer than this server (${version}), but ${!r.tarball ? 'it has no server package' : 'its note gives no SHA-256 for the server package (a release from before 2026-10-31)'}: not installed`)
      return { kind: 'no-package', tag }
    }
    mkdirSync(dir, { recursive: true, mode: 0o700 })
    // What an attempt before left (none is running: there's no pending install).
    for (const f of readdirSync(dir)) rmSync(join(dir, f), { recursive: true, force: true })
    const work = join(dir, tag)
    const tmp = join(dir, `.server-${tag}.${process.pid}.download`)
    const tarball = join(work, `hypernova-server-${tag}-linux.tar.gz`)
    const unpacked = join(work, 'package')
    set('waiting', tag)
    try {
      let digest: string
      try {
        digest = await downloadTo(r.tarball.url, tmp, { size: r.tarball.size, max: TARBALL_MAX, what: 'the server package', signal: AbortSignal.timeout(o.timeoutMs ?? 10 * 60_000) })
      } catch (e) {
        failed(tag, e instanceof Error ? e.message : String(e))
        return { kind: 'error', tag, message: e instanceof Error ? e.message : String(e) }
      }
      if (digest !== r.sha256) {
        failed(tag, 'the package’s SHA-256 isn’t the one its note gives; thrown away')
        return { kind: 'mismatch', tag }
      }
      mkdirSync(unpacked, { recursive: true, mode: 0o700 })
      renameSync(tmp, tarball)
      // Unpacked into its own new folder, owned by us; the package holds one hypernova-<version>/.
      try {
        await run('tar', ['-xzf', tarball, '-C', unpacked, '--no-same-owner', '--no-same-permissions'], { timeout: 120_000 })
      } catch (e) {
        failed(tag, `the package doesn’t unpack: ${e instanceof Error ? e.message.split('\n')[0] : e}`)
        return { kind: 'error', tag, message: 'unpack' }
      }
      const top = readdirSync(unpacked, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => join(unpacked, d.name))
      const root = top.length === 1 ? top[0]! : null
      const pkg = root ? readJson<{ version?: unknown } | null>(join(root, 'daemon', 'package.json'), null) : null
      const inside = typeof pkg?.version === 'string' ? semver(pkg.version) : null
      if (!root || !existsSync(join(root, 'scripts', 'install-linux.sh')) || !inside || inside.join('.') !== wanted.join('.')) {
        failed(tag, `the package isn’t Hypernova’s server ${tag} (its daemon says ${String(pkg?.version ?? 'nothing')})`)
        return { kind: 'error', tag, message: 'not the package' }
      }
      const q = await quiet(tag)
      if (q === 'cancelled') {
        say(`server update to ${tag}: auto-update was turned off while it waited; nothing installed`, true)
        return { kind: 'cancelled', tag }
      }
      // Looked at again: the install may have changed while it waited.
      const m = managed()
      if (!m.ok) return { kind: 'unavailable', reason: m.reason }
      const to = wanted.join('.')
      saved.pending = { tag, from: version, to, at: now() }
      save()
      rmSync(resultFile, { force: true })
      try {
        await apply({ tag, from: version, to, unpacked: root, workDir: work, result: resultFile, logFile })
      } catch (e) {
        saved.pending = null
        save()
        failed(tag, `the install couldn’t start: ${e instanceof Error ? e.message.split('\n')[0] : e}`)
        return { kind: 'error', tag, message: 'launch' }
      }
      say(`server update: installing ${tag} (from ${version}${q === 'forced' ? ', after waiting 24 hours for a quiet moment' : ''}); the service restarts when it's in (${logFile})`, true)
      set('installing', tag)
      watch()
      return { kind: 'launched', tag, forced: q === 'forced' }
    } finally {
      rmSync(tmp, { force: true })
      if (state === 'waiting') set('idle', null)
    }
  }

  /** A release the check read (releases.ts): installed if it should be. Never throws. */
  async function consider(r: ServerRelease): Promise<ServerOutcome> {
    if (!enabled()) return { kind: 'off' }
    const m = managed()
    if (!m.ok) {
      say(`auto-update isn't available here: ${m.reason}`)
      return { kind: 'unavailable', reason: m.reason }
    }
    const wanted = semver(r.tag)
    const running = semver(version)
    if (!wanted || !running) return { kind: 'not-a-version', tag: r.tag }
    // Never down, nor sideways.
    if (!newer(wanted, running)) return { kind: 'not-newer', tag: r.tag, running: version }
    if (attempt || saved.pending) return { kind: 'busy' }
    const f = saved.fails
    if (f && f.tag === r.tag && (f.next == null || now() < f.next)) return { kind: 'later', tag: r.tag, until: f.next }
    attempt = attemptFor(r, wanted).catch((e: unknown): ServerOutcome => {
      const message = e instanceof Error ? e.message : String(e)
      failed(r.tag, message)
      return { kind: 'error', tag: r.tag, message }
    })
    try {
      return await attempt
    } finally {
      attempt = null
    }
  }

  /**
   * The setting, from the phone, `hypernova auto-update` or POST /local/settings (which tell the
   * phones themselves). On again: failures forgotten.
   */
  function setEnabled(on: boolean): void {
    settings.autoUpdate = on
    saveSettings()
    if (on && saved.fails) {
      saved.fails = null
      save()
    }
  }

  function stop(): void {
    if (watching) clearInterval(watching)
    watching = null
  }

  return { consider, view, started, settle, setEnabled, stop, enabled }
}

/**
 * The install in a transient systemd unit of the user's own (`systemd-run --user`), not a child of
 * the daemon: the service's restart, which ends the daemon, leaves it running, and its output goes
 * to [ApplyJob.logFile]. It gets the environment the daemon's service has (the HYPERNOVA_ folders and
 * ports among it), so setup writes the service again as it was.
 */
export async function launchUnit(job: ApplyJob): Promise<void> {
  const script = join(job.workDir, 'server-update.sh')
  // A copy: the installed one goes with the old daemon's folder halfway through.
  copyFileSync(join(import.meta.dirname, 'server-update.sh'), script)
  chmodSync(script, 0o700)
  const env = userBusEnv({ env: process.env, uid: process.getuid?.() ?? -1 })
  const keep = ['HOME', 'USER', 'LOGNAME', 'PATH', 'LANG', 'XDG_DATA_HOME', 'XDG_STATE_HOME', 'XDG_CONFIG_HOME', 'XDG_RUNTIME_DIR', ...CARRIED]
  const setenv = keep.flatMap((k) => (env[k] ? [`--setenv=${k}=${env[k]}`] : []))
  const pid = String(process.pid)
  await run('systemd-run', ['--user', `--unit=${UPDATE_UNIT}`, `--description=Hypernova updating itself to ${job.tag}`, '--collect', '--quiet', ...setenv, '/bin/bash', script, job.unpacked, job.tag, job.result, appDirOf(), String(LOCAL_PORT), job.logFile, pid], { env, timeout: 30_000 })
}

// ── The daemon's own ──────────────────────────────────────────────────────

let daemonUpdater: ReturnType<typeof serverUpdater> | null = null
const updater = () => (daemonUpdater ??= serverUpdater())

/** The daemon's updater (main.ts), with the hub's counts and the phones to tell. True when this start is an update's. */
export function startServerUpdate(o: Pick<UpdaterOptions, 'busy' | 'changed'>): boolean {
  daemonUpdater?.stop()
  daemonUpdater = serverUpdater(o)
  return daemonUpdater.started()
}
/** For releases.ts: the release check's hand-over. */
export const serverUpdateHook = { consider: (r: ServerRelease) => updater().consider(r), enabled: () => updater().enabled() }
export const updateView = (): UpdateView => updater().view()
export const setAutoUpdate = (on: boolean): void => updater().setEnabled(on)
