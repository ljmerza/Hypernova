// The `hypernova` commands on a Linux server (docs/LINUX.md): setup, pair, status, doctor, logs,
// lan, uninstall, run. Each returns its exit code; what it prints is for a person reading a
// terminal over SSH: one line a step, what's wrong in plain words, and the command that fixes it.
import { closeSync, createReadStream, existsSync, openSync, readFileSync, readSync, rmSync, statSync, watchFile } from 'node:fs'
import { join, resolve } from 'node:path'
import { userInfo } from 'node:os'
import { RELEASES, shellRc } from '../platform.ts'
import { createInterface } from 'node:readline'
import { ApiError, health, local, localText, waitHealthy } from './api.ts'
import { bold, line, tilde, type Cli, UNIT } from './context.ts'
import { CODEX_LOGIN, codexChecks, doctorChecks, type CodexState, type DoctorOptions } from './doctor.ts'
import { claudeSignedIn, claudeVersionOf, defaultClaudeAgents, findClaudeBin, installClaude, installDaemon, setIntegration } from './install.ts'
import { ensureNode, recordedNode } from './node.ts'
import { encodeQr, qrLines } from './qr.ts'
import { WSL_GUIDE, behindNat, systemdFix, wslNetworkingOf, wslPairNote } from './wsl.ts'
import { installService, lingering, ownLauncher, removeService, serviceState, userManager, writeLauncher } from './service.ts'

export type Flags = Record<string, string | boolean>

/** `--name value`, `--name=value` and `--flag`, plus the words left over. */
export function parseArgs(argv: string[]): { cmd: string; flags: Flags; rest: string[] } {
  const flags: Flags = {}
  const rest: string[] = []
  const valued = new Set(['from', 'n', 'lines', 'data', 'data-file', 'token'])
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!
    const m = /^--?([\w-]+)(?:=(.*))?$/.exec(a)
    if (!m) rest.push(a)
    else if (m[2] !== undefined) flags[m[1]!] = m[2]
    else if (valued.has(m[1]!) && i + 1 < argv.length) flags[m[1]!] = argv[++i]!
    else flags[m[1]!] = true
  }
  return { cmd: rest.shift() ?? 'help', flags, rest }
}

const notOnMac = (cli: Cli): number => {
  cli.sys.say('On a Mac, Hypernova sets itself up from its menu bar app: open Hypernova, then Setup.')
  return 1
}

// ── setup ─────────────────────────────────────────────────────────────────

/**
 * Checks what's there and puts in what's missing: Node.js 24, the daemon (with its terminal module
 * built), the `hypernova` command, Claude Code (Anthropic's installer), the service (systemd, with
 * lingering) and the integration. Never signs in to Claude: that's the owner's, with `claude`.
 */
export async function setup(cli: Cli, flags: Flags): Promise<number> {
  if (cli.platform === 'darwin') return notOnMac(cli)
  cli.sys.say(bold(cli, `Hypernova setup on ${cli.user}@${hostName()}`))
  cli.sys.say()
  let failed = false
  const doing = (s: string) => line(cli, 'doing', s)

  // 1. Node.js
  let node: string
  try {
    const n = await ensureNode(cli, doing)
    node = n.node
    line(cli, 'ok', `Node.js ${n.version}${n.downloaded ? ' (downloaded from nodejs.org, checksum verified)' : ''} · ${tilde(cli, node)}`)
  } catch (e: any) {
    line(cli, 'fail', 'Node.js 24 isn’t available.', e.message)
    return 1
  }

  // 2. The daemon's files
  const from = typeof flags.from === 'string' ? resolve(flags.from) : cli.selfDaemon
  try {
    const d = await installDaemon(cli, node, from, doing)
    line(cli, 'ok', `The daemon${d.copied ? ' installed' : ''} in ${tilde(cli, cli.daemonDir)} · shared terminals ready${d.built ? ' (terminal module built)' : ''}`)
  } catch (e: any) {
    line(cli, 'fail', 'The daemon couldn’t be installed.', e.message)
    return 1
  }
  writeLauncher(cli, node)
  const onPath = (cli.env.PATH ?? '').split(':').includes(join(cli.home, '.local/bin'))
  line(cli, onPath ? 'ok' : 'warn', `The hypernova command · ${tilde(cli, cli.launcher)}`, onPath ? undefined : 'Not on your PATH yet: log in again (Ubuntu adds ~/.local/bin at login), or run it by its full path.')

  // 3. Claude Code
  let claude = findClaudeBin(cli)
  if (!claude && flags['no-claude']) line(cli, 'warn', 'Claude Code isn’t installed (skipped).', 'curl -fsSL https://claude.ai/install.sh | bash')
  else if (!claude) {
    doing('Installing Claude Code with Anthropic’s installer (claude.ai/install.sh)…')
    try {
      claude = await installClaude(cli)
    } catch (e: any) {
      line(cli, 'fail', 'Claude Code couldn’t be installed.', e.message)
      failed = true
    }
  }
  if (claude) line(cli, 'ok', `Claude Code ${(await claudeVersionOf(cli, claude)) ?? ''} · ${tilde(cli, claude)}`.replace('  ', ' '))
  // Sessions in `claude agents`: on unless the owner turned it off, read by the service as it starts.
  let agentsSet = false
  try {
    agentsSet = defaultClaudeAgents(cli) === 'set'
    if (agentsSet) line(cli, 'ok', 'Sessions run in claude agents (Claude Code’s background sessions). To turn it off: Settings → Agents & providers in the app.')
  } catch (e: any) {
    line(cli, 'warn', 'Couldn’t turn on sessions in claude agents.', e.message)
  }

  // 4. The service
  let running = false
  if (flags['no-service']) line(cli, 'info', 'The service: skipped (--no-service).')
  else {
    // Lingering first: it also starts the user's systemd manager when this shell has no login
    // session of its own (su, sudo -u, a script), which systemctl --user needs.
    // WSL runs systemd only when /etc/wsl.conf says so (cli/wsl.ts): no lingering to try without it.
    const wslNoSystemd = !!cli.wsl && !cli.wsl.container && cli.wsl.version === 2 && !cli.wsl.systemd
    const sd = wslNoSystemd ? { ok: false, why: '', linger: 'no_loginctl' as const } : await userManager(cli)
    if (wslNoSystemd) {
      line(cli, 'warn', 'WSL doesn’t run systemd here, so there’s no service yet: Hypernova would stop with the terminal it runs in.', `${systemdFix(cli.wsl!)}\nUntil then: hypernova run, in a terminal you keep open.`)
    } else if (!sd.ok && !cli.sys.which('systemctl')) {
      line(cli, 'warn', `No systemd user service here: ${sd.why}`, 'Start the daemon with: hypernova run   (a container’s command does this)')
    } else if (!sd.ok) {
      line(cli, 'warn', `systemd’s user manager isn’t running for ${cli.user}: ${sd.why}`, `Turn on lingering, which starts it: sudo loginctl enable-linger ${cli.user}\nor log in as ${cli.user} over ssh; then run hypernova setup again.`)
    } else {
      const linger = sd.linger
      if (linger === 'needs_sudo') line(cli, 'warn', 'Lingering is off: the service would stop when you log out.', `Turn it on: sudo loginctl enable-linger ${cli.user}`)
      try {
        await installService(cli, node)
        running = await waitHealthy(cli, 30_000)
        if (running) line(cli, 'ok', `The service: ${UNIT}, running, starts at boot${linger === 'already' || linger === 'done' ? ' with nobody logged in' : ''}`)
        else {
          line(cli, 'fail', `${UNIT} started but doesn’t answer.`, 'hypernova logs shows why.')
          failed = true
        }
      } catch (e: any) {
        line(cli, 'fail', 'The service couldn’t be installed.', e.message)
        failed = true
      }
    }
  }
  if (!running) running = !!(await health(cli.localPort, 1500))
  // A daemon that was already running and wasn't restarted (no service here) takes it at once too.
  if (agentsSet && running) await local(cli, 'POST', '/local/settings', { claudeAgents: true }).catch(() => {})

  // 5. The integration: hooks, the status line, the claude function. Not in a container image
  // (--no-integration): the hooks carry the daemon's secret, which each volume makes for itself at
  // its first start (the image's entrypoint runs `hypernova integrate`).
  if (flags['no-integration']) line(cli, 'info', 'The integration: left for the first start (--no-integration).')
  else try {
    const i = await setIntegration(cli, true)
    const rc = rcName(cli)
    line(cli, i.hooks.installed ? 'ok' : 'warn', `Hooks and the status line in Claude Code’s settings${i.shell.supported ? `; the claude function in ${rc}` : ''}`, i.shell.supported ? undefined : 'Your login shell isn’t bash or zsh, so `claude` stays plain; sessions from the phone are shared all the same.')
  } catch (e: any) {
    line(cli, 'fail', 'The integration couldn’t be installed.', e.message)
    failed = true
  }

  // 6. Codex (optional, or the agent here without Claude Code): what the daemon found. Never installed from here.
  const cx: CodexState | null | undefined = running ? await local(cli, 'GET', '/local/status', undefined, 15_000).then((s) => s.codex, () => undefined) : undefined
  if (cx === null) line(cli, claude ? 'info' : 'warn', claude ? 'Codex isn’t installed (optional).' : 'Neither Claude Code nor Codex is installed.', `For Codex: ${codexChecks(null, false)[0].fix}`)
  else if (cx) for (const c of codexChecks(cx, !claude)) line(cli, c.mark, c.text, c.fix)

  // 7. What's left for the owner.
  cli.sys.say()
  const auth = claude ? await claudeSignedIn(cli, claude) : null
  const next: string[] = []
  if (auth && !auth.loggedIn) next.push('Sign in to Claude: run `claude` once on this server and follow its steps.')
  if (cx?.available && !cx.enabled) next.push('Use Codex too: hypernova codex on')
  if (cli.wsl && !cli.wsl.container) next.push(`Keep WSL running for the phone: instanceIdleTimeout=-1 under [general] in %UserProfile%\\.wslconfig on Windows (${WSL_GUIDE}).`)
  next.push(cli.wsl ? 'Pair your phone: hypernova pair (WSL keeps this PC off the Wi‑Fi unless its networking is mirrored)' : 'Pair your phone: hypernova pair')
  next.push('Check everything: hypernova doctor')
  cli.sys.say(bold(cli, failed ? 'Setup finished with problems (above). Then:' : 'Done. Next:'))
  next.forEach((n, i) => cli.sys.say(`  ${i + 1}. ${n}`))
  return failed ? 1 : 0
}

/** `hypernova integrate`: hooks, status line and the `claude` function, put in (again). A container's entrypoint runs it at each start, so every volume has its own secrets. */
export async function integrate(cli: Cli): Promise<number> {
  try {
    await setIntegration(cli, true)
    return 0
  } catch (e: any) {
    cli.sys.say(`hypernova: ${e.message}`)
    return 1
  }
}

/** The shell file the daemon puts the `claude` function in: the login shell's, as hooks.ts picks it. */
const rcName = (cli: Cli) => {
  let shell = cli.env.SHELL
  try {
    shell = userInfo().shell ?? shell
  } catch {}
  return shellRc(cli.platform, shell, cli.env, cli.home)?.name ?? '~/.bashrc'
}

function hostName(): string {
  try {
    return readFileSync('/etc/hostname', 'utf8').trim() || 'this server'
  } catch {
    return 'this server'
  }
}

// ── pair ──────────────────────────────────────────────────────────────────

/**
 * The pairing QR code in the terminal: the same link the Mac app shows (the daemon makes it), for
 * the local network (the LAN listener, reached on the Wi-Fi or over the owner's own VPN). Then
 * waits for the phone (10 minutes, the code's life) unless --no-wait. `--local` is still accepted
 * (scripts pass it) but changes nothing: every code is local now.
 */
export async function pair(cli: Cli, flags: Flags): Promise<number> {
  if (cli.platform === 'darwin') return notOnMac(cli)
  if (!(await health(cli.localPort, 2000))) {
    line(cli, 'fail', 'The daemon isn’t running.', 'systemctl --user start hypernova   (or hypernova run), then hypernova pair')
    return 1
  }
  let p: { code?: string; link: string; appLink: string; webLink?: string; download?: string; expiresAt: number; url: string }
  let before: string[] = []
  try {
    before = ((await local(cli, 'GET', '/local/devices')).devices ?? []).map((d: any) => d.id)
    p = await local(cli, 'POST', '/local/pair/start', {})
  } catch (e: any) {
    line(cli, 'fail', 'The daemon couldn’t make a pairing code.', e.message)
    return 1
  }
  // The daemon's link for the QR code: this server's own pairing page on the local network,
  // https://<its address>:47323/pair?…, which hands over to the app (docs/PROTOCOL.md, "Pairing").
  // With the listener off, the app's own hypernova://pair link.
  const link = p.link
  const lan = /[?&]lan=([^&]+)/.exec(p.appLink)?.[1]
  cli.sys.say()
  // WSL on a Windows PC (cli/wsl.ts): behind its NAT the Wi-Fi is no way in.
  const wslNote = cli.wsl ? wslPairNote(cli, cli.wsl, await wslNetworkingOf(cli)) : null
  if (wslNote?.mark === 'warn' || !lan) {
    if (wslNote?.mark === 'warn') line(cli, wslNote.mark, wslNote.text, wslNote.fix)
    else line(cli, 'warn', 'The local network route is off, so a phone has no way in yet.', 'hypernova lan on   (only on a network you trust)')
    cli.sys.say()
  }
  if (wslNote && wslNote.mark !== 'warn') {
    line(cli, wslNote.mark, wslNote.text, wslNote.fix)
    cli.sys.say()
  }
  // The app's page (on the local network's listener). The page the code opens without the app sends the owner here for it ("hypernova pair says where").
  // No download line when the daemon could only guess its address (the emulator's host alias), and
  // the release's when this computer has no app to hand out (a Linux package carries none).
  if (p.download) {
    // `apk: null` is the daemon saying it has none; a daemon that doesn't say is taken at its link.
    const noApk = await local(cli, 'GET', '/local/status', undefined, 15_000).then((st) => !!st && 'apk' in st && st.apk == null, () => false)
    if (noApk) cli.sys.say(`The app, if the phone doesn’t have it yet: hypernova-<tag>.apk from ${RELEASES}\n`)
    else if (!/\/\/10\.0\.2\.2[:/]/.test(p.download)) cli.sys.say(`The app, if the phone doesn’t have it yet: ${p.download}\n`)
  }
  if (!flags['no-qr']) {
    const qr = encodeQr(link, 'M')
    const need = qr.size + 4 + 2
    const cols = process.stdout.columns
    if (cli.tty && cols && cols < need) cli.sys.say(`This window is ${cols} columns wide; the code needs ${need}. Widen it (or zoom out) to scan it, or paste the link below.\n`)
    else {
      for (const l of qrLines(qr, { quiet: 2, ansi: cli.tty })) cli.sys.say('  ' + l)
      cli.sys.say()
    }
  }
  if (link.startsWith('https://')) {
    cli.sys.say('Scan it with the phone’s camera, on this network or over your VPN into it. The browser warns once about this server’s own certificate: go on (Advanced, then Proceed), and the page opens Hypernova. Or paste the link in Hypernova (Paste pairing link):')
  } else cli.sys.say('Paste the link in Hypernova (Paste pairing link):')
  cli.sys.say(`  ${link}`)
  if (p.code) cli.sys.say(`Code: ${p.code}`)
  const until = new Date(p.expiresAt)
  cli.sys.say(`It works once, for ${Math.max(1, Math.round((p.expiresAt - Date.now()) / 60_000))} minutes (until ${String(until.getHours()).padStart(2, '0')}:${String(until.getMinutes()).padStart(2, '0')}).`)
  if (flags['no-wait'] || flags['no-wait-phone']) return 0
  cli.sys.say()
  cli.sys.say('Waiting for the phone… (Ctrl+C to stop; the code stays good until it runs out)')
  while (Date.now() < p.expiresAt) {
    await new Promise((r) => setTimeout(r, 1500))
    try {
      const now = (await local(cli, 'GET', '/local/devices')).devices ?? []
      const fresh = now.find((d: any) => !before.includes(d.id))
      if (fresh) {
        line(cli, 'ok', `Paired with ${fresh.name}${fresh.model ? ` (${fresh.model})` : ''}.`)
        return 0
      }
    } catch {}
  }
  line(cli, 'warn', 'The code ran out before a phone used it.', 'hypernova pair makes a new one.')
  return 1
}

// ── automation ────────────────────────────────────────────────────────────

/**
 * `hypernova automation fire <id> [--data <text> | --data-file <file> | --data-file -] [--token <t>]`:
 * an automation's webhook trigger, fired on this computer (git hooks, cron, CI). Through the local
 * API with the admin token, or the automation's own token (--token, or HYPERNOVA_FIRE_TOKEN).
 * `--data` is sent as it is, whatever it starts with: a script passes outside words there (a commit
 * message, a PR title), and "@~/.ssh/id_ed25519" must never make it read a file. Only
 * `--data-file` reads one (`-`: standard input).
 * Exits 0 when a run was made, 3 when nothing happened (paused, switched off, fired too often), 1
 * on an error.
 */
export async function automation(cli: Cli, flags: Flags, rest: string[]): Promise<number> {
  const [verb, id] = rest
  const usage = () => {
    cli.sys.say('Usage: hypernova automation fire <id> [--data <text> | --data-file <file> | --data-file -] [--token <token>]')
    return 2
  }
  if (verb !== 'fire' || !id || !/^[\w-]{1,64}$/.test(id)) return usage()
  const data = flags.data
  const file = flags['data-file']
  if (data !== undefined && file !== undefined) return usage()
  if (data === true || file === true) return usage()
  let text = typeof data === 'string' ? data : ''
  if (typeof file === 'string') {
    try {
      text = file === '-' ? readFileSync(0, 'utf8') : readFileSync(file, 'utf8')
    } catch (e) {
      cli.sys.say(`hypernova: couldn’t read ${file === '-' ? 'standard input' : file}: ${(e as Error).message}`)
      return 1
    }
  }
  // An empty token (an unset variable in a script) is no token: the admin token is used.
  const given = typeof flags.token === 'string' ? flags.token : cli.env.HYPERNOVA_FIRE_TOKEN
  const token = given?.trim() ? given.trim() : null
  try {
    const r = await localText(cli, `/local/automations/${id}/fire`, text, token)
    if (r.status === 200 && r.json?.run) {
      const run = r.json.run
      cli.sys.say(`Fired ${run.name}: ${run.state}${run.reason ? ` (${String(run.reason).replace(/_/g, ' ')})` : ''}, run ${run.id}.`)
      return 0
    }
    const code = r.json?.error?.code
    cli.sys.say(`hypernova: ${r.json?.error?.message ?? `the daemon answered ${r.status}.`}`)
    return code === 'paused' || code === 'off' || code === 'too_often' ? 3 : 1
  } catch (e) {
    cli.sys.say(`hypernova: ${(e as Error).message}`)
    return 1
  }
}

// ── status ────────────────────────────────────────────────────────────────

export async function status(cli: Cli, flags: Flags): Promise<number> {
  const svc = cli.platform === 'linux' ? await serviceState(cli) : null
  const up = await health(cli.localPort, 2000)
  let st: any = null
  if (up) {
    try {
      st = await local(cli, 'GET', '/local/status', undefined, 15_000)
    } catch {}
  }
  const claude = findClaudeBin(cli)
  const auth = claude ? await claudeSignedIn(cli, claude) : null
  if (flags.json) {
    cli.sys.say(JSON.stringify({ running: !!up, name: up?.name ?? null, service: svc, status: st, claude: claude ? { path: claude, signedIn: auth?.loggedIn ?? null, plan: auth?.plan ?? null } : null, logs: join(cli.logs, 'daemon.log') }, null, 2))
    return up ? 0 : 1
  }
  const row = (k: string, v: string) => cli.sys.say(`  ${k.padEnd(12)} ${v}`)
  cli.sys.say(bold(cli, `Hypernova${st?.daemonVersion ? ` ${st.daemonVersion}` : ''} on ${up?.name ?? hostName()}`))
  const svcText = !svc ? '' : !svc.installed ? ', no service installed' : `, ${UNIT} ${svc.active ? 'active' : 'stopped'}${svc.enabled ? ', starts at boot' : ''}`
  row('Daemon', up ? `running${svc?.pid ? ` (pid ${svc.pid})` : ''}${svcText}` : `not running${svcText}`)
  if (!up) {
    cli.sys.say()
    cli.sys.say(`  Start it: systemctl --user start hypernova (or hypernova run). Why it stopped: hypernova logs`)
    return 1
  }
  // Loopback only: phones come in through the local route below.
  row('Phone API', `127.0.0.1:${cli.apiPort}`)
  row('Local route', st?.lan ? `port ${st.lan.port}${st.lan.addresses?.length ? ` on ${st.lan.addresses.join(', ')}` : ''}` : 'off')
  row('Claude Code', claude ? `${st?.claude?.version ?? (await claudeVersionOf(cli, claude)) ?? '?'}${auth ? (auth.loggedIn ? `, signed in${auth.plan ? ` (${auth.plan})` : ''}` : ', not signed in: run claude once') : ''}` : 'not installed (hypernova setup)')
  if (st) {
    // Codex (optional): found or not, on or off, signed in (hypernova codex says more).
    const c = st.codex
    row('Codex', !c ? 'not installed (optional: hypernova codex)' : !c.available ? `${c.version ?? '?'}, too old: needs ${c.min} (codex update)` : !c.enabled ? `${c.version}, off for Hypernova (hypernova codex on)` : `${c.version}, on${c.account?.signedIn === false ? `, not signed in: ${CODEX_LOGIN}` : c.account?.signedIn ? ', signed in' : ''}${c.problem ? `; ${c.problem}` : ''}`)
  }
  if (st) {
    const bits = [st.hooks?.installed ? 'hooks' : null, st.statusLine?.installed ? 'status line' : null, st.shell?.installed ? 'claude function' : null].filter(Boolean)
    row('Integration', bits.length ? bits.join(', ') : 'not installed (hypernova setup)')
    const devices = st.devices ?? []
    row('Devices', devices.length ? `${devices.length} paired (${devices.map((d: any) => d.name).join(', ')})` : 'none yet (hypernova pair)')
    const sessions: any[] = st.sessions ?? []
    const by = (s: string) => sessions.filter((x) => x.state === s).length
    const terms = sessions.filter((s) => s.term).length
    row('Sessions', sessions.length ? `${[['working', by('working')], ['need you', by('needs_you')], ['idle', by('idle')]].filter(([, n]) => n).map(([k, n]) => `${n} ${k}`).join(', ') || `${sessions.length} open`}; ${terms} in shared terminals` : 'none open')
    if (st.account && st.account.state !== 'ok') row('Account', `${st.account.state}${st.account.text ? `: ${st.account.text}` : ''}`)
  }
  row('Log', tilde(cli, join(cli.logs, 'daemon.log')))
  return 0
}

// ── doctor ────────────────────────────────────────────────────────────────

export async function doctor(cli: Cli, flags: Flags, o: DoctorOptions = {}): Promise<number> {
  const checks = await doctorChecks(cli, o)
  if (flags.json) cli.sys.say(JSON.stringify(checks, null, 2))
  else {
    cli.sys.say(bold(cli, 'Hypernova doctor'))
    for (const c of checks) line(cli, c.mark, c.text, c.fix)
    const bad = checks.filter((c) => c.mark === 'fail').length, warn = checks.filter((c) => c.mark === 'warn').length
    cli.sys.say()
    cli.sys.say(bad ? `${bad} problem${bad > 1 ? 's' : ''} to fix${warn ? `, ${warn} worth a look` : ''}.` : warn ? `Working; ${warn} thing${warn > 1 ? 's' : ''} worth a look.` : 'All good.')
  }
  return checks.some((c) => c.mark === 'fail') ? 1 : 0
}

// ── logs ──────────────────────────────────────────────────────────────────

/** The daemon's log: its last lines (-n, 100 by default), and with -f what comes after. */
export async function logs(cli: Cli, flags: Flags): Promise<number> {
  const file = join(cli.logs, flags.terminals ? 'terminals.log' : 'daemon.log')
  if (!existsSync(file)) {
    cli.sys.say(`No log yet at ${tilde(cli, file)}: the daemon hasn’t run here.`)
    return 1
  }
  const n = Math.max(1, Number(flags.n ?? flags.lines ?? 100) || 100)
  const text = readTail(file, 4 * 1024 * 1024)
  for (const l of text.split('\n').slice(-n - 1).filter((l, i, a) => i < a.length - 1 || l)) cli.sys.say(l)
  if (!(flags.f || flags.follow)) return 0
  let at = statSync(file).size
  await new Promise<void>(() => {
    watchFile(file, { interval: 500 }, (cur) => {
      if (cur.size < at) at = 0 // rotated or cut
      if (cur.size === at) return
      const s = createReadStream(file, { start: at, end: cur.size - 1, encoding: 'utf8' })
      at = cur.size
      createInterface({ input: s }).on('line', (l) => cli.sys.say(l))
    })
  })
  return 0
}

/** The last [max] bytes of a file (a log can grow large), as text. */
function readTail(file: string, max: number): string {
  const size = statSync(file).size
  const len = Math.min(size, max)
  const buf = Buffer.alloc(len)
  const fd = openSync(file, 'r')
  try {
    readSync(fd, buf, 0, len, size - len)
  } finally {
    closeSync(fd)
  }
  return buf.toString('utf8')
}

// ── lan ───────────────────────────────────────────────────────────────────

/** `hypernova lan on|off`: the local network route (docs/PROTOCOL.md, "Local network"). */
export async function lan(cli: Cli, rest: string[]): Promise<number> {
  const want = rest[0]
  if (want !== 'on' && want !== 'off') {
    cli.sys.say('hypernova lan on|off: the phone’s direct route on the same network (device certificates, private addresses only).')
    return 2
  }
  try {
    await local(cli, 'POST', '/local/settings', { lanAccess: want === 'on' })
    const st = await local(cli, 'GET', '/local/status')
    line(cli, 'ok', st.lan ? `Local route on, port ${st.lan.port}${st.lan.addresses?.length ? ` on ${st.lan.addresses.join(', ')}` : ''}.` : 'Local route off.')
    // Behind WSL's NAT the port is on, but on a network no phone is on (cli/wsl.ts).
    if (st.lan && cli.wsl && behindNat(cli.wsl, await wslNetworkingOf(cli))) line(cli, 'warn', `Behind ${cli.wsl.container ? 'the container’s' : 'WSL’s NAT'} network no phone can reach this port, and pairing links leave its addresses out.`, cli.wsl.container ? 'hypernova lan off turns the route off again.' : `Mirrored networking makes it reachable (${WSL_GUIDE}); hypernova lan off turns it off again.`)
    return 0
  } catch (e: any) {
    line(cli, 'fail', e instanceof ApiError && e.status === 0 ? 'The daemon isn’t running.' : e.message)
    return 1
  }
}

// ── auto-update ───────────────────────────────────────────────────────────

/**
 * `hypernova auto-update [on|off]`: the server updating itself to newer releases (settings.autoUpdate,
 * on unless turned off; daemon/src/server-update.ts), as the phone's switch does. Without a word, how
 * it stands: on or off, whether this install can, and the last update it installed.
 */
export async function autoUpdate(cli: Cli, rest: string[]): Promise<number> {
  const want = rest[0]
  if (want !== undefined && want !== 'on' && want !== 'off') {
    cli.sys.say('hypernova auto-update [on|off]: this server installs newer Hypernova releases by itself, when no session is working.')
    return 2
  }
  try {
    if (want) await local(cli, 'POST', '/local/settings', { autoUpdate: want === 'on' })
    const st = await local(cli, 'GET', '/local/status')
    const u = st.update as { auto: boolean; available: boolean; reason: string | null; state: string; target: string | null; last: { from: string; to: string; at: number } | null } | undefined
    if (!u) {
      line(cli, 'fail', 'This daemon is too old to update itself.')
      return 1
    }
    line(cli, u.auto ? 'ok' : 'info', u.auto ? 'Auto-update is on: newer releases install themselves when no session is working.' : 'Auto-update is off.', u.auto ? undefined : 'hypernova auto-update on turns it on.')
    if (!u.available) line(cli, u.auto ? 'warn' : 'info', `Auto-update isn’t available here: ${u.reason}`)
    if (u.state === 'waiting' && u.target) line(cli, 'info', `${u.target} is downloaded and checked, waiting for a quiet moment.`)
    if (u.state === 'installing' && u.target) line(cli, 'doing', `Installing ${u.target}…`)
    if (u.last) line(cli, 'info', `Last updated itself from ${u.last.from} to ${u.last.to}, ${new Date(u.last.at).toLocaleString()}.`)
    return 0
  } catch (e: any) {
    line(cli, 'fail', e instanceof ApiError && e.status === 0 ? 'The daemon isn’t running.' : e.message)
    return 1
  }
}

// ── codex ─────────────────────────────────────────────────────────────────

/**
 * `hypernova codex [on|off]`: OpenAI's Codex for Hypernova (docs/PROTOCOL.md, "Codex"): what is found
 * here, or turning it on or off (`POST /local/settings {codex}`), as Setup's switch on a Mac. On, the
 * daemon connects to Codex's own app server and `codex` joins `claude` in the shell's block. It
 * never installs or signs in Codex: it says the lines to run.
 */
export async function codex(cli: Cli, rest: string[]): Promise<number> {
  const want = rest[0]
  if (want !== undefined && want !== 'on' && want !== 'off') {
    cli.sys.say('hypernova codex [on|off]: OpenAI’s Codex for Hypernova: what’s found here, or turn it on or off.')
    return 2
  }
  try {
    let st = await local(cli, 'GET', '/local/status', undefined, 15_000)
    const alone = !st.claude?.path
    if (want === 'on' && !st.codex?.available) {
      // Not installed, or too old: what to run first, and nothing changed.
      for (const c of codexChecks(st.codex, alone)) line(cli, 'fail', c.text, c.fix)
      return 1
    }
    if (want) {
      await local(cli, 'POST', '/local/settings', { codex: want === 'on' }, 15_000)
      // Its account and limits read now, so the line below says whether it's signed in.
      if (want === 'on') await local(cli, 'POST', '/v1/codex/refresh', undefined, 20_000).catch(() => null)
      st = await local(cli, 'GET', '/local/status', undefined, 15_000)
    }
    for (const c of codexChecks(st.codex, alone)) line(cli, c.mark, c.text, c.fix)
    if (want === 'on' && st.shell?.installed) line(cli, 'info', `\`codex\` in a new terminal runs in a shared terminal your phone can see (${rcName(cli)}).`)
    return 0
  } catch (e: any) {
    line(cli, 'fail', e instanceof ApiError && e.status === 0 ? 'The daemon isn’t running.' : e.message, e instanceof ApiError && e.status === 0 ? 'systemctl --user start hypernova   (or: hypernova run)' : undefined)
    return 1
  }
}

// ── uninstall ─────────────────────────────────────────────────────────────

/**
 * Takes out what setup put in: the integration first (only the daemon knows exactly what it
 * added), the service, the command, the daemon and its Node. The state
 * (paired phones, settings) stays unless --purge. Claude Code and its login stay: they're yours.
 */
export async function uninstall(cli: Cli, flags: Flags): Promise<number> {
  if (cli.platform === 'darwin') {
    cli.sys.say('On a Mac, uninstall from Hypernova’s menu bar app (its menu, Uninstall…).')
    return 1
  }
  const taken = flags.purge ? 'Hypernova, its paired phones and settings' : 'Hypernova (its paired phones and settings stay)'
  if (!flags.yes) {
    // Without a terminal nobody can answer: refuse rather than remove everything unasked.
    if (!process.stdin.isTTY) {
      cli.sys.say('Uninstall asks first, and there is no terminal to ask in. Run it with --yes to remove Hypernova without asking (add --purge for the paired phones and settings too).')
      return 1
    }
    const ok = await ask(`Remove ${taken} from this server? [y/N] `)
    if (!/^y(es)?$/i.test(ok.trim())) {
      cli.sys.say('Nothing removed.')
      return 1
    }
  }
  const notes: string[] = []
  try {
    await setIntegration(cli, false)
    line(cli, 'ok', 'Hooks, status line and the claude function removed')
  } catch (e: any) {
    notes.push(`The integration couldn’t be removed (${e.message}). Its hooks point at Hypernova’s local port, so they do nothing once it’s gone.`)
  }
  notes.push(...(await removeService(cli)))
  line(cli, 'ok', `${UNIT} stopped and removed`)
  if (ownLauncher(cli.launcher)) rmSync(cli.launcher, { force: true })
  // Only the Node Hypernova downloaded: one it was given (the system's) isn't its to remove.
  const node = recordedNode(cli)
  rmSync(cli.appDir, { recursive: true, force: true })
  rmSync(cli.nodeRoot, { recursive: true, force: true })
  rmSync(cli.nodePathFile, { force: true })
  rmSync(join(cli.support, 'bin'), { recursive: true, force: true })
  line(cli, 'ok', `The command, the daemon${node?.startsWith(cli.nodeRoot) ? ' and its Node.js' : ''} removed`)
  if (flags.purge) {
    rmSync(cli.support, { recursive: true, force: true })
    rmSync(cli.logs, { recursive: true, force: true })
    line(cli, 'ok', 'Paired phones, settings and logs removed')
  } else {
    line(cli, 'info', `Kept: paired phones and settings in ${tilde(cli, cli.support)} (hypernova uninstall --purge removes them; reinstalling picks them up).`)
  }
  line(cli, 'info', 'Claude Code and its sign-in stay, and so do Codex and its own. Shared sessions still open keep running until you close them.')
  // Setup turned lingering on for the service, but other services of this user may count on it
  // too, so it isn't turned off behind the owner's back: he's told how.
  if (await lingering(cli)) line(cli, 'info', `Lingering stays on for ${cli.user} (other services of yours may use it). To turn it off: sudo loginctl disable-linger ${cli.user}`)
  for (const n of notes) line(cli, 'warn', n)
  return 0
}

function ask(q: string): Promise<string> {
  return new Promise((r) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout })
    rl.question(q, (a) => {
      rl.close()
      r(a)
    })
  })
}

// ── help ──────────────────────────────────────────────────────────────────

export const HELP = `hypernova: a control room for Claude Code and Codex, on this server

  hypernova setup       check and install what's missing: Node.js 24, Claude Code, the
                        hooks, the shell integration and the service (--no-claude, --from <dir>)
  hypernova pair        show the pairing QR code for the Hypernova app (--no-wait)
  hypernova status      what runs, how phones reach it, who's paired (--json)
  hypernova doctor      check everything, each problem with its fix (--json)
  hypernova logs        the daemon's log (-n <lines>, -f to follow, --terminals: the shared terminals')
  hypernova codex [on|off]
                        OpenAI's Codex for Hypernova: what's found here, or turn it on or off
  hypernova lan on|off  the phone's direct route on the same network
  hypernova auto-update [on|off]
                        this server installs newer releases by itself (on unless turned off)
  hypernova uninstall   remove it (--purge: paired phones and settings too; --yes)
  hypernova run         run the daemon in the foreground (containers, debugging)
  hypernova automation fire <id>
                        fire an automation's webhook trigger (--data <text> or --data-file <file|->: its payload)

Shared terminals: \`claude\` (and \`codex\`, while it's on) in your shell runs in one your phone can see; hn ls, hn attach.
Docs: docs/LINUX.md in https://github.com/ljmerza/Hypernova
`
