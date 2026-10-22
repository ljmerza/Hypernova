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
import { CODEX_LOGIN, codexChecks, doctorChecks, tailscaleBin, type CodexState, type DoctorOptions } from './doctor.ts'
import { claudeSignedIn, claudeVersionOf, findClaudeBin, installClaude, installDaemon, setIntegration } from './install.ts'
import { ensureNode, recordedNode } from './node.ts'
import { encodeQr, qrLines } from './qr.ts'
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
 * lingering), the integration, and phone access through Tailscale when it's set up. Never signs in
 * to Claude: that's the owner's, with `claude`.
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

  // 4. The service
  let running = false
  if (flags['no-service']) line(cli, 'info', 'The service: skipped (--no-service).')
  else {
    // Lingering first: it also starts the user's systemd manager when this shell has no login
    // session of its own (su, sudo -u, a script), which systemctl --user needs.
    const sd = await userManager(cli)
    if (!sd.ok && !cli.sys.which('systemctl')) {
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

  // 6. Tailscale: the phone's way in from outside. Built in (the daemon's own node, nothing to
  // install) unless the Tailscale app on this server already serves the phone API.
  const ts = tailscaleBin(cli)
  const tsnet = running ? await tsnetOf(cli) : null
  if (tsnet?.mode === 'builtIn') {
    const l = tsnetLine(tsnet)!
    line(cli, l.mark, l.text, l.fix)
  } else if (!ts) line(cli, 'warn', tsnet?.available === false ? 'This package has no built-in Tailscale helper, and Tailscale isn’t installed: the phone can reach this server only on the same network.' : 'Tailscale isn’t running yet: the daemon starts its built-in node once it runs.', tsnet?.available === false ? 'curl -fsSL https://tailscale.com/install.sh | sh\nsudo tailscale up\nsudo tailscale set --operator=$USER' : 'hypernova pair')
  else if (running) {
    const r = await serveOn(cli)
    if (r.ok) line(cli, 'ok', `Tailscale serves the phone API at ${r.url}`)
    else line(cli, 'warn', r.message, r.fix)
  }

  // 7. Codex (optional, or the agent here without Claude Code): what the daemon found. Never installed from here.
  const cx: CodexState | null | undefined = running ? await local(cli, 'GET', '/local/status', undefined, 15_000).then((s) => s.codex, () => undefined) : undefined
  if (cx === null) line(cli, claude ? 'info' : 'warn', claude ? 'Codex isn’t installed (optional).' : 'Neither Claude Code nor Codex is installed.', `For Codex: ${codexChecks(null, false)[0].fix}`)
  else if (cx) for (const c of codexChecks(cx, !claude)) line(cli, c.mark, c.text, c.fix)

  // 8. What's left for the owner.
  cli.sys.say()
  const auth = claude ? await claudeSignedIn(cli, claude) : null
  const next: string[] = []
  if (auth && !auth.loggedIn) next.push('Sign in to Claude: run `claude` once on this server and follow its steps.')
  if (cx?.available && !cx.enabled) next.push('Use Codex too: hypernova codex on')
  next.push('Pair your phone: hypernova pair')
  next.push('Check everything: hypernova doctor')
  await nameTermsOnce(cli)
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

/**
 * `tailscale serve` for the phone API, through the daemon (as the Mac app does). Linux allows it
 * to root, or to the user root named as Tailscale's operator: when it's refused, that's the fix.
 */
async function serveOn(cli: Cli): Promise<{ ok: true; url: string } | { ok: false; message: string; fix?: string }> {
  let st: any
  try {
    st = (await local(cli, 'GET', '/local/status?fresh=1', undefined, 20_000)).tailscale
  } catch (e: any) {
    return { ok: false, message: `Couldn’t ask the daemon about Tailscale (${e.message}).` }
  }
  if (!st?.loggedIn) return { ok: false, message: 'Tailscale is installed but not signed in.', fix: 'sudo tailscale up\nsudo tailscale set --operator=$USER' }
  if (!st.httpsEnabled) return { ok: false, message: 'Your tailnet has HTTPS certificates off, so tailscale serve can’t give the phone https.', fix: 'Turn on MagicDNS and HTTPS Certificates at https://login.tailscale.com/admin/dns, then hypernova pair' }
  if (st.serveOn && st.url) return { ok: true, url: st.url }
  try {
    const r = await local(cli, 'POST', '/local/tailscale/serve', {}, 30_000)
    if (r?.url) return { ok: true, url: r.url }
    return { ok: false, message: 'tailscale serve didn’t take the phone API.', fix: `sudo tailscale serve --bg --https=${cli.apiPort} http://127.0.0.1:${cli.apiPort}` }
  } catch (e: any) {
    const denied = /denied|permission|operator|not allowed|root/i.test(e.message)
    return {
      ok: false,
      message: denied ? 'Tailscale lets only root or its operator change serve.' : `tailscale serve failed: ${e.message}`,
      fix: denied ? `Make yourself its operator once: sudo tailscale set --operator=${cli.user}\nthen run hypernova pair again.` : `sudo tailscale serve --bg --https=${cli.apiPort} http://127.0.0.1:${cli.apiPort}`,
    }
  }
}

// ── Tailscale built in (the daemon's tsnet.ts) ────────────────────────────

type Tsnet = { mode: 'system' | 'builtIn' | 'off'; reason: string; available: boolean; helper: { state?: string; loginUrl?: string; qr?: string; dnsName?: string; account?: string; listening?: string; tls?: string; error?: string } | null; url: string | null; controlUrl: string | null }

/** What the daemon's built-in node is doing; null when the daemon can't be asked. */
async function tsnetOf(cli: Cli): Promise<Tsnet | null> {
  try {
    return await local(cli, 'GET', '/local/tsnet')
  } catch {
    return null
  }
}

/** One line about the built-in node for setup, status and doctor, or null when it isn't in play. */
function tsnetLine(t: Tsnet | null): { mark: 'ok' | 'warn' | 'info'; text: string; fix?: string } | null {
  if (!t || t.mode !== 'builtIn') return null
  const h = t.helper
  if (h?.state === 'Running' && h.listening) return { mark: 'ok', text: `Built-in Tailscale serves the phone API at ${h.listening}${h.account ? ` (signed in as ${h.account})` : ''}${h.tls === 'pinned' ? '; the tailnet has no HTTPS certificates, so the phone pins this server’s own' : ''}` }
  if (h?.state === 'NeedsLogin') return { mark: 'warn', text: 'Built-in Tailscale needs your sign-in: it connects your phone from anywhere, with no Tailscale to install.', fix: 'hypernova tailscale   (shows the sign-in code), or hypernova pair' }
  if (h?.error) return { mark: 'warn', text: `Built-in Tailscale: ${h.error}`, fix: 'hypernova logs' }
  return { mark: 'info', text: `Built-in Tailscale is ${h?.state?.toLowerCase() ?? 'starting'}…` }
}

/**
 * Shows the built-in node's sign-in code and waits (up to [waitMs]) for the sign-in to finish in
 * the browser. True once the node runs and serves; false when it didn't (the caller says what next).
 */
async function tsnetSignIn(cli: Cli, flags: Flags, waitMs = 10 * 60_000, skip?: { skipped: boolean }): Promise<boolean> {
  let t = await tsnetOf(cli)
  if (t?.helper?.state === 'Running' && t.helper.listening) return true
  if (!t || t.mode !== 'builtIn') return false
  const t0 = Date.now()
  // The link can take a few seconds to arrive after the node starts.
  while (t && t.helper?.state !== 'NeedsLogin' && t.helper?.state !== 'Running' && Date.now() - t0 < 20_000) {
    await new Promise((r) => setTimeout(r, 1000))
    t = await tsnetOf(cli)
  }
  const url = t?.helper?.loginUrl
  if (!url) return !!(t?.helper?.state === 'Running' && t.helper.listening)
  cli.sys.say()
  cli.sys.say(bold(cli, 'Sign in to connect your devices.'))
  cli.sys.say('Hypernova carries Tailscale, a private network between your own devices. Scan this with your phone, or open the link, and sign in (Google, GitHub, Apple or Microsoft). Use the same account on the phone.')
  cli.sys.say()
  if (!flags['no-qr']) {
    for (const l of qrLines(encodeQr(url, 'M'), { quiet: 2, ansi: cli.tty })) cli.sys.say('  ' + l)
    cli.sys.say()
  }
  cli.sys.say(`  ${url}`)
  if (t?.controlUrl) cli.sys.say(`  (control server: ${t.controlUrl})`)
  cli.sys.say()
  if (waitMs <= 0) return false
  cli.sys.say(skip ? 'Waiting for the sign-in… (press Enter to skip it and pair on this network only)' : 'Waiting for the sign-in…')
  const deadline = Date.now() + waitMs
  while (Date.now() < deadline && !skip?.skipped) {
    await new Promise((r) => setTimeout(r, 2000))
    t = await tsnetOf(cli)
    if (t?.helper?.state === 'Running' && t.helper.listening) {
      line(cli, 'ok', `Signed in${t.helper.account ? ` as ${t.helper.account}` : ''}: ${t.helper.dnsName ?? ''} serves the phone API at ${t.helper.listening}`)
      return true
    }
    if (t?.mode !== 'builtIn') return false
  }
  if (!skip?.skipped) line(cli, 'warn', 'The sign-in didn’t finish.', 'hypernova tailscale   shows the code again')
  return false
}

/** `hypernova tailscale [login|logout]`: the built-in node, its sign-in code, or another account. */
export async function tailscale(cli: Cli, flags: Flags, rest: string[]): Promise<number> {
  if (cli.platform === 'darwin') return notOnMac(cli)
  if (!(await health(cli.localPort, 2000))) {
    line(cli, 'fail', 'The daemon isn’t running.', 'systemctl --user start hypernova   (or hypernova run)')
    return 1
  }
  const sub = rest[0]
  if (sub === 'on' || sub === 'off') {
    // The choice between the Tailscale app on this server and Hypernova's own node (tsnet.ts): off
    // leaves phones to the Tailscale app (or the local network), on lets the built-in node serve
    // whenever the app isn't already serving the phone API.
    await local(cli, 'POST', '/local/settings', { builtInTailscale: sub === 'on' }, 30_000)
    line(cli, 'ok', sub === 'on' ? 'Built-in Tailscale on: it serves the phone whenever the Tailscale app here isn’t.' : 'Built-in Tailscale off: use the Tailscale app on this server (hypernova pair turns its serve on), or the local network.')
  } else if (sub === 'control') {
    const url = (rest[1] ?? '').trim()
    if (rest.length < 2) {
      cli.sys.say('hypernova tailscale control <url>|none: the coordination server of a self-hosted Headscale (https, or http on a private address).')
      return 2
    }
    try {
      await local(cli, 'POST', '/local/settings', { tailscaleControlUrl: url === 'none' ? '' : url }, 30_000)
    } catch (e: any) {
      line(cli, 'fail', e.message)
      return 1
    }
    line(cli, 'ok', url === 'none' ? 'Control server: tailscale.com' : `Control server: ${url}`, 'Sign in again with hypernova tailscale login')
    return 0
  }
  if (sub === 'logout' || sub === 'login') {
    await local(cli, 'POST', `/local/tsnet/${sub}`, {})
    await new Promise((r) => setTimeout(r, 1500))
  }
  const t = await tsnetOf(cli)
  if (!t) return 1
  if (t.mode === 'system') {
    line(cli, 'ok', 'The Tailscale app on this server serves the phone API; the built-in node stays off.', t.reason)
    return 0
  }
  if (t.mode === 'off') {
    line(cli, 'info', 'Built-in Tailscale is off.', t.reason)
    return 0
  }
  const ok = await tsnetSignIn(cli, flags)
  if (!ok) {
    const l = tsnetLine(t)
    if (l) line(cli, l.mark, l.text, l.fix)
  }
  return ok ? 0 : 1
}

// ── pair ──────────────────────────────────────────────────────────────────

/**
 * The pairing QR code in the terminal: the same link the Mac app shows (the daemon makes it), with
 * the tailnet's way in when Tailscale serves the phone API, else for the local network only. Then
 * waits for the phone (10 minutes, the code's life) unless --no-wait.
 */
export async function pair(cli: Cli, flags: Flags): Promise<number> {
  if (cli.platform === 'darwin') return notOnMac(cli)
  if (!(await health(cli.localPort, 2000))) {
    line(cli, 'fail', 'The daemon isn’t running.', 'systemctl --user start hypernova   (or hypernova run), then hypernova pair')
    return 1
  }
  // The built-in node first (the daemon's tsnet.ts): its sign-in when it needs one. Otherwise the
  // Tailscale app's serve, as before.
  // Never blocks for good: --local skips Tailscale, --no-wait (and any run without a terminal) shows
  // the sign-in but doesn't wait for it, and Enter ends the wait. The code always comes.
  let tsnet = await tsnetOf(cli)
  const skip = { skipped: false }
  if (cli.tty && process.stdin.isTTY && !flags['no-wait']) {
    process.stdin.once('data', () => (skip.skipped = true))
    process.stdin.unref?.()
  }
  const waitFor = flags.local ? 0 : flags['no-wait'] || !cli.tty ? 0 : 10 * 60_000
  const builtIn = tsnet?.mode === 'builtIn' && !flags.local ? await tsnetSignIn(cli, flags, waitFor, skip) : false
  tsnet = await tsnetOf(cli)
  const ts = !builtIn && tsnet?.mode !== 'builtIn' && !flags.local && tailscaleBin(cli) ? await serveOn(cli) : null
  let p: { code?: string; link: string; appLink: string; webLink?: string; download?: string; expiresAt: number; url: string }
  let before: string[] = []
  // Before the code (which makes the name the first time): what the private name is, once.
  await nameTermsOnce(cli)
  try {
    before = ((await local(cli, 'GET', '/local/devices')).devices ?? []).map((d: any) => d.id)
    // `name`: this code's download link may use the private name, so it counts for it (names.ts).
    p = await local(cli, 'POST', '/local/pair/start?name=1', {})
  } catch (e: any) {
    line(cli, 'fail', 'The daemon couldn’t make a pairing code.', e.message)
    return 1
  }
  const tailnet = builtIn || !!ts?.ok
  // The daemon's link for the QR code: https://hypernova.observer/pair with the app link's details
  // (the local addresses, the certificate's fingerprint, the tailnet hints) after its `#`, which the
  // phone opens in the app whatever the way in (docs/PROTOCOL.md, "Pairing").
  const link = p.link
  const lan = /[?&]lan=([^&]+)/.exec(p.appLink)?.[1]
  cli.sys.say()
  if (!tailnet) {
    if (ts && !ts.ok) line(cli, 'warn', ts.message, ts.fix)
    else if (tsnet?.mode === 'builtIn') line(cli, 'warn', 'Built-in Tailscale isn’t signed in yet: this code pairs a phone on the same network only.', 'hypernova tailscale')
    else line(cli, 'warn', 'Tailscale isn’t installed: this code pairs a phone on the same network only.', 'curl -fsSL https://tailscale.com/install.sh | sh, sudo tailscale up, then hypernova pair')
    if (!lan) line(cli, 'warn', 'The local network route is off too, so a phone has no way in yet.', 'hypernova lan on   (only on a network you trust), or set up Tailscale')
    cli.sys.say()
  }
  // The app's page (on the local network's name whenever that works: the daemon's names.ts). The
  // page the code opens without the app sends the owner here for it ("hypernova pair says where").
  // No download line when the daemon could only guess its address (the emulator's host alias), and
  // the release's when this computer has no app to hand out (a Linux package carries none).
  if (p.download) {
    // `apk: null` is the daemon saying it has none; a daemon that doesn't say is taken at its link.
    const noApk = await local(cli, 'GET', '/local/status', undefined, 15_000).then((st) => !!st && 'apk' in st && st.apk == null, () => false)
    if (noApk) cli.sys.say(`The app, if the phone doesn’t have it yet: Hypernova.apk from ${RELEASES}\n`)
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
  cli.sys.say('Scan it with the phone’s camera (it opens the Hypernova app), or paste the link there:')
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
  const ts = st?.tailscale
  const tsnet = st?.tsnet as Tsnet | undefined
  const viaBuiltIn = tsnet?.mode === 'builtIn' ? (tsnet.helper?.listening ? ` → ${tsnet.helper.listening} (built-in Tailscale${tsnet.helper.account ? `, ${tsnet.helper.account}` : ''})` : tsnet.helper?.state === 'NeedsLogin' ? ' (built-in Tailscale needs your sign-in: hypernova tailscale)' : ` (built-in Tailscale ${tsnet.helper?.state?.toLowerCase() ?? 'starting'})`) : null
  row('Phone API', `127.0.0.1:${cli.apiPort}${viaBuiltIn ?? (ts?.url ? ` → ${ts.url} (tailscale serve)` : ts?.installed ? ' (Tailscale doesn’t serve it yet: hypernova pair)' : ' (no Tailscale yet)')}`)
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
  const nm = await local(cli, 'GET', '/local/name').catch(() => null)
  if (nm && nm.enabled !== false) row('Name', `${nm.host ?? (nm.state === 'off' ? 'off' : 'none yet')}${nm.reason ? ` (${nm.reason})` : ''}${nm.lastError ? `; last error: ${nm.lastError.message}` : ''}`)
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

// ── name ──────────────────────────────────────────────────────────────────

/**
 * Once per server (on `setup` or the first `pair`, whichever comes first): what the private name
 * is and that Let's Encrypt's terms are accepted for it, with how to turn it off; then the daemon
 * is told it was said (`nameTermsShown`), which it waits for before making anything. Nothing when
 * the name doesn't apply here (turned off, no local network listener, a test daemon, an older
 * daemon) or was already said.
 */
export async function nameTermsOnce(cli: Cli): Promise<void> {
  let st: { enabled?: boolean; reason?: string; terms?: string; termsShown?: boolean } | null = null
  try {
    st = await local(cli, 'GET', '/local/name')
  } catch {
    return
  }
  if (!st?.enabled || st.termsShown !== false || /listener is off/.test(st.reason ?? '')) return
  line(cli, 'info', `A private name: while a code is shown, this server’s Wi‑Fi address gets a name under hypernova.observer with a free Let’s Encrypt certificate, so a phone’s browser opens the download page without a warning. Hypernova accepts Let’s Encrypt’s terms (${st.terms ?? 'https://letsencrypt.org/repository/'}) for you. The name is public; the address is in DNS only while a code is shown.`, 'hypernova name off   (to do without it)')
  try {
    await local(cli, 'POST', '/local/settings', { nameTermsShown: true })
  } catch {}
}

/** `hypernova name on|off`: the private name under hypernova.observer (docs/PROTOCOL.md, "A name on the local network"). */
export async function name(cli: Cli, rest: string[]): Promise<number> {
  const want = rest[0]
  if (want !== 'on' && want !== 'off') {
    cli.sys.say('hypernova name on|off: a private name with a real certificate for the download page, published only while a code is shown.')
    return 2
  }
  try {
    const r = await local<{ nameRelease?: { released?: boolean; name?: string | null } }>(cli, 'POST', '/local/settings', { nameService: want === 'on' }, 30_000)
    const st = await local(cli, 'GET', '/local/name')
    if (want === 'on') {
      line(cli, 'ok', `Private name on${st.host ? ` (${st.host})` : ''}: it’s made or published when a code is shown.`)
      return 0
    }
    const rel = r?.nameRelease
    if (rel?.name && !rel.released) {
      line(cli, 'warn', `Private name off, but ${rel.name} couldn’t be given up just now: its records stay in DNS until the daemon gets through (it keeps trying), or 90 days unused.`)
      return 1
    }
    line(cli, 'ok', rel?.name ? `Private name off; ${rel.name} was given up.` : 'Private name off.')
    return 0
  } catch (e: any) {
    line(cli, 'fail', e instanceof ApiError && e.status === 0 ? 'The daemon isn’t running.' : e.message)
    return 1
  }
}

// ── uninstall ─────────────────────────────────────────────────────────────

/**
 * Takes out what setup put in: the integration first (only the daemon knows exactly what it
 * added), Tailscale's route to it, the service, the command, the daemon and its Node. The state
 * (paired phones, settings) stays unless --purge. Claude Code and its login stay: they're yours.
 */
export async function uninstall(cli: Cli, flags: Flags): Promise<number> {
  if (cli.platform === 'darwin') {
    cli.sys.say('On a Mac, uninstall from Hypernova’s menu bar app (its menu, Uninstall…).')
    return 1
  }
  const taken = flags.purge ? 'Hypernova, its paired phones and settings, its private name and its Tailscale node' : 'Hypernova (its paired phones and settings stay)'
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
  // The built-in Tailscale node leaves the tailnet with --purge (its key goes with the data, so a
  // node left behind would sit there dead, and a reinstall would come back as hypernova-<host>-1).
  const tsn = flags.purge ? await tsnetOf(cli) : null
  if (tsn?.mode === 'builtIn' && tsn.helper?.state === 'Running') {
    try {
      await local(cli, 'POST', '/local/tsnet/logout', {}, 15_000)
      await new Promise((r) => setTimeout(r, 2000))
      line(cli, 'ok', 'The built-in Tailscale node signed out of your tailnet')
    } catch (e: any) {
      notes.push(`The built-in Tailscale node couldn’t sign out (${e.message}). Remove it from https://login.tailscale.com/admin/machines.`)
    }
  }
  try {
    await setIntegration(cli, false)
    line(cli, 'ok', 'Hooks, status line and the claude function removed')
  } catch (e: any) {
    notes.push(`The integration couldn’t be removed (${e.message}). Its hooks point at Hypernova’s local port, so they do nothing once it’s gone.`)
  }
  // The server's name under hypernova.observer (the daemon's names.ts): its record leaves DNS now
  // rather than after 90 days unused. A daemon that's down, or older, has nothing to say.
  try {
    const r = await local<{ released?: boolean; name?: string | null }>(cli, 'POST', '/local/name/release', {}, 30_000)
    if (r?.name && r.released) line(cli, 'ok', `Its name ${r.name} under hypernova.observer given up`)
    else if (r?.name) notes.push(`This server’s name (${r.name}) under hypernova.observer couldn’t be given up just now; its records go by themselves after 90 days unused.`)
  } catch (e) {
    // An older daemon has no such route; otherwise say so when there is a name to give up.
    if (!(e instanceof ApiError && e.status === 404) && existsSync(join(cli.support, 'names', 'name.json'))) notes.push(`This server’s name under hypernova.observer couldn’t be confirmed given up (${(e as Error).message}); its records go by themselves after 90 days unused.`)
  }
  const ts = tailscaleBin(cli)
  if (ts) {
    const r = await cli.sys.run(ts, ['serve', `--https=${cli.apiPort}`, 'off'], { timeout: 20_000 })
    if (r.code === 0) line(cli, 'ok', `Tailscale no longer serves port ${cli.apiPort}`)
    else if (!/not found|no serve|nothing/i.test(r.err + r.out)) notes.push(`Tailscale still routes port ${cli.apiPort} here (${(r.err || r.out).trim().split('\n')[0]}). Remove it with: sudo tailscale serve --https=${cli.apiPort} off`)
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
    if (existsSync(join(cli.support, 'tsnet'))) line(cli, 'info', `Its Tailscale node stays in your tailnet as hypernova-<this server's name>; reinstalling picks it up, or remove it at https://login.tailscale.com/admin/machines.`)
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
  hypernova pair        show the pairing QR code for the Hypernova app (--no-wait, --local: skip Tailscale)
  hypernova tailscale   the built-in Tailscale node: its sign-in code (login|logout: another account;
                        on|off: use it or the Tailscale app here; control <url>: a Headscale server)
  hypernova status      what runs, how phones reach it, who's paired (--json)
  hypernova doctor      check everything, each problem with its fix (--json)
  hypernova logs        the daemon's log (-n <lines>, -f to follow, --terminals: the shared terminals')
  hypernova codex [on|off]
                        OpenAI's Codex for Hypernova: what's found here, or turn it on or off
  hypernova lan on|off  the phone's direct route on the same network
  hypernova name on|off a private name for the download page (published only while a code is shown)
  hypernova uninstall   remove it (--purge: paired phones and settings too; --yes)
  hypernova run         run the daemon in the foreground (containers, debugging)
  hypernova automation fire <id>
                        fire an automation's webhook trigger (--data <text> or --data-file <file|->: its payload)

Shared terminals: \`claude\` (and \`codex\`, while it's on) in your shell runs in one your phone can see; hn ls, hn attach.
Docs: https://hypernova.nikbraun.tech, or docs/LINUX.md in Hypernova's repository.
`
