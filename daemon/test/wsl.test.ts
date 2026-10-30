// WSL on a Windows PC (platform.ts, lan.ts; docs/INSTALL.md, "Windows (experimental)"): WSL told
// apart from other Linuxes by its kernel and its variables, its networking mode read from wslinfo,
// the LAN listener off by default behind WSL's NAT and its unreachable addresses never advertised
// (the explicit setting still turns it on), and the reasons a WSL snapshot gives. Then, in this
// process, a daemon that believes it is under WSL's NAT: its listener runs when told to, and the
// snapshot and the pairing link carry no address. Everything is a stand-in (a fake /proc/version,
// fake variables, fake interfaces); nothing here has run on a real Windows PC.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { NetworkInterfaceInfo } from 'node:os'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-wsl-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
process.env.HYPERNOVA_API_PORT = String(await freePort())
process.env.HYPERNOVA_LOCAL_PORT = String(await freePort())
process.env.HYPERNOVA_LAN_PORT = '0'
// This process's daemon is under WSL 2, NAT networking (what the service would read from /proc/version and wslinfo).
process.env.HYPERNOVA_WSL = '1'
process.env.HYPERNOVA_WSL_NETWORKING = 'nat'

const { isWsl, lanByDefault, onWindowsDrive, unavailableOn, LINUX_REASONS, wslBehindNat, wslFacts, wslNetworking, wslOf } = await import('../src/platform.ts')
const { advertisedAddresses, lanAddresses, lanEnabled, lanInfo, startLan, stopLan } = await import('../src/lan.ts')
const { settings } = await import('../src/store.ts')
const { startPairing } = await import('../src/http.ts')
after(async () => {
  await stopLan()
  rmSync(scratch, { recursive: true, force: true })
})

const proc = (release: string) => () => `Linux version ${release} (root@build) (gcc (GCC) 13.2.0, GNU ld (GNU Binutils) 2.41) #1 SMP PREEMPT_DYNAMIC Thu Oct 10 21:43:11 UTC 2026\n`
const nic = (address: string, internal = false, family: 'IPv4' | 'IPv6' = 'IPv4') => ({ address, internal, family, netmask: '', mac: '', cidr: null }) as NetworkInterfaceInfo

test('WSL is told by its kernel: WSL 2 (new and old kernels), WSL 1, and no other Linux', () => {
  assert.deepEqual(wslOf({}, proc('6.6.87.2-microsoft-standard-WSL2')), { version: 2 })
  assert.deepEqual(wslOf({}, proc('5.15.167.4-microsoft-standard-WSL2+')), { version: 2 })
  assert.deepEqual(wslOf({}, proc('4.19.104-microsoft-standard')), { version: 2 })
  assert.deepEqual(wslOf({}, proc('4.4.0-19041-Microsoft')), { version: 1 })
  // Ubuntu's own, Azure's (a cloud VM, not WSL: Microsoft builds it, but its release doesn't say so), Debian's, a Raspberry Pi's.
  for (const r of ['6.8.0-45-generic', '6.8.0-1021-azure', '6.1.0-26-amd64', '6.6.51+rpt-rpi-v8']) assert.equal(wslOf({}, proc(r)), null, r)
  // "Microsoft" elsewhere in the line (a compiler's build string) isn't the kernel's release.
  assert.equal(wslOf({}, () => 'Linux version 6.8.0-45-generic (buildd@microsoft-builder) (gcc 13.2.0)'), null)
  // Nothing to read (a Mac, a locked-down container): not WSL.
  assert.equal(wslOf({}, () => ''), null)
  assert.equal(isWsl({}, proc('6.6.87.2-microsoft-standard-WSL2')), true)
  assert.equal(isWsl({}, proc('6.8.0-45-generic')), false)
})

test('a WSL 2 kernel of the owner’s own is told by WSL’s variables; HYPERNOVA_WSL decides either way', () => {
  const own = proc('6.10.4-custom')
  assert.equal(wslOf({}, own), null)
  assert.deepEqual(wslOf({ WSL_DISTRO_NAME: 'Ubuntu' }, own), { version: 2 })
  assert.deepEqual(wslOf({ WSL_INTEROP: '/run/WSL/12_interop' }, own), { version: 2 })
  assert.deepEqual(wslOf({ HYPERNOVA_WSL: '1' }, own), { version: 2 })
  assert.equal(wslOf({ HYPERNOVA_WSL: '0', WSL_DISTRO_NAME: 'Ubuntu' }, proc('6.6.87.2-microsoft-standard-WSL2')), null)
})

test('a path on a Windows drive, as WSL mounts it, is told apart (agents found through Windows’ PATH)', () => {
  for (const p of ['/mnt/c/Users/ada/AppData/Roaming/npm/claude', '/mnt/D/tools/codex']) assert.equal(onWindowsDrive(p), true, p)
  for (const p of ['/usr/local/bin/claude', '/home/ada/.local/bin/claude', '/mnt/data/claude', '/mnt/cc/claude', 'mnt/c/claude']) assert.equal(onWindowsDrive(p), false, p)
})

test('the networking mode comes from wslinfo, in lower case; no answer or nonsense is unknown', () => {
  assert.equal(wslNetworking({}, () => 'nat\n'), 'nat')
  assert.equal(wslNetworking({}, () => 'Mirrored\r\n'), 'mirrored')
  assert.equal(wslNetworking({}, () => 'virtioproxy'), 'virtioproxy')
  assert.equal(wslNetworking({}, () => null), null)
  assert.equal(wslNetworking({}, () => 'wslinfo: unknown option --networking-mode'), null)
  assert.equal(wslNetworking({ HYPERNOVA_WSL_NETWORKING: 'mirrored' }, () => 'nat'), 'mirrored')
})

test('behind WSL’s NAT: WSL 2 in every mode but mirrored and bridged, unknown counting as NAT; never WSL 1 or another Linux', () => {
  const two = { version: 2 as const }
  assert.equal(wslBehindNat(two, 'nat'), true)
  assert.equal(wslBehindNat(two, null), true)
  assert.equal(wslBehindNat(two, 'virtioproxy'), true)
  assert.equal(wslBehindNat(two, 'none'), true)
  assert.equal(wslBehindNat(two, 'mirrored'), false)
  assert.equal(wslBehindNat(two, 'bridged'), false)
  assert.equal(wslBehindNat({ version: 1 }, null), false)
  assert.equal(wslBehindNat(null, 'nat'), false)
})

test('the LAN listener is off by default behind WSL’s NAT, on with mirrored networking as on any PC', () => {
  // WSL 2's NAT: one interface, eth0, on Windows' private network for it.
  const nat = { lo: [nic('127.0.0.1', true)], eth0: [nic('172.24.81.5'), nic('fe80::215:5dff:fe3a:1b2c', false, 'IPv6')] }
  // Mirrored: Windows' own addresses (the Wi-Fi's).
  const mirrored = { lo: [nic('127.0.0.1', true)], eth0: [nic('192.168.1.20')] }
  assert.equal(lanByDefault('linux', nat, { wslNat: true }), false)
  assert.equal(lanByDefault('linux', mirrored, { wslNat: false }), true)
  // The same interfaces on a PC that isn't WSL: on, as before.
  assert.equal(lanByDefault('linux', nat, {}), true)
  // A Mac is a Mac.
  assert.equal(lanByDefault('darwin', nat, { wslNat: true }), true)
})

test('behind WSL’s NAT no address is advertised; otherwise the local network’s, unchanged', () => {
  const nat = { eth0: [nic('172.24.81.5')] }
  assert.deepEqual(lanAddresses(nat), ['172.24.81.5'])
  assert.deepEqual(advertisedAddresses(nat, true), [])
  assert.deepEqual(advertisedAddresses(nat, false), ['172.24.81.5'])
  // WSL may pick another private range when 172.16/12 is taken: still behind Windows, still left out.
  assert.deepEqual(advertisedAddresses({ eth0: [nic('192.168.208.4')] }, true), [])
  const home = { wlan0: [nic('192.168.1.20')], docker0: [nic('172.17.0.1')] }
  assert.deepEqual(advertisedAddresses(home, false), lanAddresses(home))
})

test('a WSL snapshot’s reasons point at Windows for Stay awake and windows; the rest as on Linux', () => {
  const wsl = unavailableOn('linux', true)
  assert.match(wsl.awake!, /On Windows, keep the PC from sleeping/)
  assert.match(wsl.openWindow!, /hn attach/)
  assert.equal(wsl.battery, LINUX_REASONS.battery)
  assert.equal(wsl.macVoice, LINUX_REASONS.macVoice)
  assert.deepEqual(unavailableOn('linux'), { ...LINUX_REASONS })
  assert.deepEqual(unavailableOn('darwin', true), {})
})

test('the install script’s word under WSL: experimental, how to turn systemd on (the install goes on), WSL 1; nothing elsewhere', () => {
  const script = readFileSync(join(import.meta.dirname, '..', '..', 'scripts', 'install-linux.sh'), 'utf8')
  const say = /^say\(\) .*$/m.exec(script)![0]
  const fn = /^wsl_hint\(\) \{[\s\S]*?^\}$/m.exec(script)![0]
  // Called before anything is installed, and asks nothing (so --yes, and no terminal at all, go through).
  assert.match(script, /^wsl_hint \/proc\/version \/run\/systemd\/system$/m)
  assert.ok(script.indexOf('wsl_hint /proc/version') < script.indexOf('# ── 1. System packages'))
  assert.doesNotMatch(fn, /\bread\b/)
  const dir = mkdtempSync(join(scratch, 'hint-'))
  const run = (release: string, systemd: boolean, env: NodeJS.ProcessEnv = {}) => {
    const version = join(dir, 'version')
    writeFileSync(version, release ? `Linux version ${release} (root@build) (gcc 13.2.0) #1 SMP\n` : '')
    const sd = join(dir, systemd ? 'systemd' : 'no-systemd')
    if (systemd) mkdirSync(sd, { recursive: true })
    return execFileSync('/bin/bash', ['-c', `set -euo pipefail\n${say}\n${fn}\nwsl_hint "$1" "$2"`, 'hint', version, sd], { encoding: 'utf8', env: { PATH: '/usr/bin:/bin', ...env } })
  }
  let out = run('6.6.87.2-microsoft-standard-WSL2', true)
  assert.match(out, /This is WSL on Windows\. Hypernova's support for it is experimental/)
  assert.match(out, /WSL's default network \(NAT\) keeps this PC off your phone's Wi-Fi/)
  assert.doesNotMatch(out, /systemd=true|WSL 1/)
  out = run('6.6.87.2-microsoft-standard-WSL2', false)
  assert.match(out, /WSL doesn't run systemd here[^]*\[boot\]\n {6}systemd=true\n[^]*wsl --shutdown[^]*Installing anyway: until then, start it with {2}hypernova run/)
  out = run('4.4.0-19041-Microsoft', false, { WSL_DISTRO_NAME: 'Debian' })
  assert.match(out, /WSL 1: Hypernova is meant for WSL 2\. In PowerShell on Windows: wsl --set-version Debian 2/)
  // A kernel of the owner's own, told by WSL's variable.
  assert.match(run('6.10.4-custom', true, { WSL_DISTRO_NAME: 'Ubuntu' }), /This is WSL on Windows/)
  // Not WSL, a container on WSL's kernel (Docker Desktop builds the image with HYPERNOVA_CONTAINER=1), or told not to: nothing.
  assert.equal(run('6.8.0-45-generic', false), '')
  assert.equal(run('', false), '')
  assert.equal(run('6.6.87.2-microsoft-standard-WSL2', false, { HYPERNOVA_CONTAINER: '1' }), '')
  assert.equal(run('6.6.87.2-microsoft-standard-WSL2', false, { HYPERNOVA_WSL: '0' }), '')
})

test('a daemon behind WSL’s NAT: the listener on when the owner says so, and neither lanInfo (the snapshot’s lan) nor the pairing link names an address', async () => {
  assert.deepEqual(wslFacts(), { wsl: { version: 2 }, networking: 'nat' })
  assert.deepEqual(advertisedAddresses(), [])
  // The explicit setting still works: on…
  settings.lanAccess = true
  assert.equal(lanEnabled(), true)
  startLan((_req, res) => res.end(), (_req, socket) => socket.destroy())
  for (let i = 0; i < 200 && !lanInfo()?.port; i++) await new Promise((r) => setTimeout(r, 25))
  const lan = lanInfo()
  assert.ok(lan?.port, 'the listener started')
  assert.deepEqual(lan.addresses, [])
  const p = await startPairing(true)
  const link = new URL(p.appLink)
  assert.equal(link.searchParams.get('lan'), '')
  assert.equal(link.searchParams.get('lp'), String(lan.port))
  assert.equal(link.searchParams.get('fp'), lan.fingerprint)
  // …and off.
  settings.lanAccess = false
  assert.equal(lanEnabled(), false)
})
