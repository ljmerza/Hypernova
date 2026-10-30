// The platform layer (platform.ts): where state and logs live on a Mac and on Linux, which of the
// Mac's features a Linux server lacks and the reason each gives, where programs are looked for,
// which shell file the `claude` function goes in, and when the LAN listener starts by itself.
// Everything is a pure function of the platform, so both systems are checked from either one.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { NetworkInterfaceInfo } from 'node:os'
import {
  LINUX_REASONS,
  claudeCandidates,
  packageBinDirs,
  findTool,
  lanByDefault,
  inContainer,
  onCloudVm,
  virtualInterface,
  bypassBlockedByRoot,
  loginShell,
  places,
  platformOf,
  publicIPv4,
  shellRc,
  systemdUserDir,
  tailscaleCandidates,
  unavailableOn,
  userBinDir,
} from '../src/platform.ts'

const HOME = '/home/ada'

test('only macOS is the Mac; every other system is treated as Linux', () => {
  assert.equal(platformOf('darwin'), 'darwin')
  assert.equal(platformOf('linux'), 'linux')
  assert.equal(platformOf('freebsd'), 'linux')
})

test('a Mac keeps the folders the menu bar app knows', () => {
  assert.deepEqual(places('darwin', {}, '/Users/ada'), {
    support: '/Users/ada/Library/Application Support/Hypernova',
    logs: '/Users/ada/Library/Logs/Hypernova',
  })
  // XDG variables mean nothing on a Mac.
  assert.equal(places('darwin', { XDG_DATA_HOME: '/x' }, '/Users/ada').support, '/Users/ada/Library/Application Support/Hypernova')
})

test('Linux follows the XDG base directories: data in ~/.local/share, logs in ~/.local/state', () => {
  assert.deepEqual(places('linux', {}, HOME), { support: '/home/ada/.local/share/hypernova', logs: '/home/ada/.local/state/hypernova/logs' })
  assert.deepEqual(places('linux', { XDG_DATA_HOME: '/data', XDG_STATE_HOME: '/state' }, HOME), { support: '/data/hypernova', logs: '/state/hypernova/logs' })
  // The spec says a relative XDG path is invalid and must be ignored.
  assert.deepEqual(places('linux', { XDG_DATA_HOME: 'rel/data', XDG_STATE_HOME: '' }, HOME), { support: '/home/ada/.local/share/hypernova', logs: '/home/ada/.local/state/hypernova/logs' })
})

test('HYPERNOVA_SUPPORT and HYPERNOVA_LOGS win on both systems (test daemons)', () => {
  const env = { HYPERNOVA_SUPPORT: '/tmp/s', HYPERNOVA_LOGS: '/tmp/l', XDG_DATA_HOME: '/data' }
  assert.deepEqual(places('linux', env, HOME), { support: '/tmp/s', logs: '/tmp/l' })
  assert.deepEqual(places('darwin', env, '/Users/ada'), { support: '/tmp/s', logs: '/tmp/l' })
})

test('the systemd unit folder and the command folder', () => {
  assert.equal(systemdUserDir({}, HOME), '/home/ada/.config/systemd/user')
  assert.equal(systemdUserDir({ XDG_CONFIG_HOME: '/cfg' }, HOME), '/cfg/systemd/user')
  assert.equal(userBinDir(HOME), '/home/ada/.local/bin')
})

test('a Mac lacks nothing; Linux lacks the Mac-only features, each with a sentence to show', () => {
  assert.deepEqual(unavailableOn('darwin'), {})
  const linux = unavailableOn('linux')
  assert.deepEqual(Object.keys(linux).sort(), ['awake', 'battery', 'macVoice', 'openWindow'])
  for (const [k, v] of Object.entries(linux)) {
    assert.equal(v, LINUX_REASONS[k as keyof typeof LINUX_REASONS])
    assert.ok(v && v.length > 20 && v.endsWith('.'), `${k}: a whole sentence`)
  }
  // A copy: a caller changing it can't change the reasons.
  ;(linux as any).battery = 'x'
  assert.notEqual(unavailableOn('linux').battery, 'x')
})

test('programs: a tool by full path in the system folders, null when missing', () => {
  const have = new Set(['/usr/bin/lsof', '/sbin/ip'])
  const exists = (p: string) => have.has(p)
  assert.equal(findTool('lsof', undefined, exists), '/usr/bin/lsof')
  assert.equal(findTool('ip', undefined, exists), '/sbin/ip')
  assert.equal(findTool('nope', undefined, exists), null)
  assert.equal(findTool('lsof', ['/opt/x'], exists), null)
})

test('an agent installed with npm is looked for in nvm’s Nodes (newest first), Volta, npm’s prefix, Bun and pnpm', () => {
  const dirs = packageBinDirs('/Users/ada', (p) => (p === '/Users/ada/.nvm/versions/node' ? ['v22.1.0', 'v25.9.0', 'v24.22.0', '.cache'] : []))
  assert.deepEqual(dirs.slice(0, 3), ['/Users/ada/.nvm/versions/node/v25.9.0/bin', '/Users/ada/.nvm/versions/node/v24.22.0/bin', '/Users/ada/.nvm/versions/node/v22.1.0/bin'])
  assert.ok(dirs.includes('/Users/ada/.volta/bin') && dirs.includes('/Users/ada/.npm-global/bin') && dirs.includes('/Users/ada/.bun/bin'))
  // The usual places come first: an npm copy never hides the official installer's.
  const c = claudeCandidates('darwin', '/Users/ada', () => ['v25.9.0'])
  assert.equal(c[0], '/Users/ada/.local/bin/claude')
  assert.ok(c.indexOf('/Users/ada/.nvm/versions/node/v25.9.0/bin/claude') > c.indexOf('/usr/local/bin/claude'))
})

test('Claude Code, the login shell and Tailscale are looked for where each system puts them', () => {
  assert.equal(claudeCandidates('linux', HOME)[0], '/home/ada/.local/bin/claude')
  assert.ok(!claudeCandidates('linux', HOME).some((p) => p.includes('homebrew')))
  assert.ok(claudeCandidates('darwin', '/Users/ada').includes('/opt/homebrew/bin/claude'))
  assert.equal(loginShell('darwin', () => false), '/bin/zsh')
  assert.equal(loginShell('linux', (p) => p === '/bin/bash'), '/bin/bash')
  assert.equal(loginShell('linux', () => false), '/bin/sh')
  assert.ok(tailscaleCandidates('linux').includes('/usr/bin/tailscale'))
  assert.ok(tailscaleCandidates('darwin')[0]!.includes('Tailscale.app'))
})

test('the shell file: zsh’s on both systems (ZDOTDIR honoured), bash’s on Linux only, else none', () => {
  assert.deepEqual(shellRc('linux', '/bin/bash', {}, HOME), { kind: 'bash', path: '/home/ada/.bashrc', name: '~/.bashrc' })
  assert.deepEqual(shellRc('linux', '/usr/bin/zsh', {}, HOME), { kind: 'zsh', path: '/home/ada/.zshrc', name: '~/.zshrc' })
  assert.deepEqual(shellRc('darwin', '/bin/zsh', { ZDOTDIR: '/z' }, '/Users/ada'), { kind: 'zsh', path: '/z/.zshrc', name: '~/.zshrc' })
  // Nothing changes on a Mac: bash there was never touched, and isn't now.
  assert.equal(shellRc('darwin', '/bin/bash', {}, '/Users/ada'), null)
  assert.equal(shellRc('linux', '/usr/bin/fish', {}, HOME), null)
  assert.equal(shellRc('linux', null, {}, HOME), null)
})

test('public IPv4: private, loopback, link-local, CGNAT and reserved ranges are not', () => {
  // 198.18.0.0/15: a proxy tool's tunnel on a PC (Clash, sing-box), never the machine's own public address.
  for (const ip of ['10.1.2.3', '172.16.0.1', '172.31.255.255', '192.168.1.5', '127.0.0.1', '169.254.1.1', '100.64.0.1', '100.127.255.255', '0.0.0.0', '224.0.0.1', '255.255.255.255', '198.18.0.1', '198.19.255.254', '192.0.2.10', '198.51.100.7', '203.0.113.9', '192.0.0.8'])
    assert.equal(publicIPv4(ip), false, ip)
  for (const ip of ['8.8.8.8', '172.32.0.1', '100.128.0.1', '64.226.1.2', '192.169.0.1', '198.20.0.1', '192.0.1.1', '203.0.114.1']) assert.equal(publicIPv4(ip), true, ip)
  for (const bad of ['', '1.2.3', '1.2.3.256', 'a.b.c.d', '::1']) assert.equal(publicIPv4(bad), false, bad)
})

test('the LAN listener starts by itself on a Mac and a home PC, not on a VPS with a public address', () => {
  const nic = (address: string, internal = false, family: 'IPv4' | 'IPv6' = 'IPv4') => ({ address, internal, family, netmask: '', mac: '', cidr: null }) as NetworkInterfaceInfo
  const home = { lo: [nic('127.0.0.1', true)], eth0: [nic('192.168.1.20'), nic('fe80::1', false, 'IPv6')] }
  const vps = { lo: [nic('127.0.0.1', true)], eth0: [nic('64.226.10.20')], eth1: [nic('10.114.0.2')], tailscale0: [nic('100.101.1.2')] }
  const docker = { lo: [nic('127.0.0.1', true)], eth0: [nic('172.17.0.2')] }
  const proxied = { eth0: [nic('192.168.1.20')], utun: [nic('198.18.0.1')] }
  // `{}`: neither a container nor a cloud VM, whatever runs the test (a CI runner is a cloud VM).
  assert.equal(lanByDefault('linux', home, {}), true)
  // A Docker bridge address is private, yet no phone is on that network: off in a container, and on a cloud VM.
  assert.equal(lanByDefault('linux', docker, {}), true)
  assert.equal(lanByDefault('linux', docker, { container: true }), false)
  assert.equal(lanByDefault('linux', home, { cloud: true }), false)
  assert.equal(lanByDefault('darwin', home, { container: true }), true)
  assert.equal(lanByDefault('linux', proxied, {}), true)
  assert.equal(lanByDefault('linux', vps, {}), false)
  // A public address on a Mac changes nothing there.
  assert.equal(lanByDefault('darwin', vps), true)
  // A public IPv6 address alone doesn't count: only IPv4 is checked (the listener filters by source anyway).
  assert.equal(lanByDefault('linux', { eth0: [nic('2a03:b0c0::1', false, 'IPv6'), nic('192.168.0.9')] }, {}), true)
})

test('a container and a cloud VM are recognised; bridge interfaces are not the Wi-Fi; root cannot bypass', () => {
  assert.equal(inContainer({}, (p) => p === '/.dockerenv'), true)
  assert.equal(inContainer({}, (p) => p === '/run/.containerenv'), true)
  assert.equal(inContainer({}, () => false), false)
  assert.equal(inContainer({ HYPERNOVA_CONTAINER: '1' }, () => false), true)
  const dmi = (v: Record<string, string>) => (f: string) => v[f] ?? ''
  assert.equal(onCloudVm(dmi({ sys_vendor: 'Amazon EC2' })), true)
  assert.equal(onCloudVm(dmi({ sys_vendor: 'DigitalOcean' })), true)
  assert.equal(onCloudVm(dmi({ sys_vendor: 'Microsoft Corporation', chassis_asset_tag: '7783-7084-3265-9085-8269-3286-77' })), true)
  assert.equal(onCloudVm(dmi({ sys_vendor: 'Microsoft Corporation', product_name: 'Surface Laptop 5' })), false)
  assert.equal(onCloudVm(dmi({ sys_vendor: 'LENOVO' })), false)
  for (const n of ['docker0', 'br-3f2a9c', 'virbr0', 'veth12ab', 'cni0', 'flannel.1', 'tailscale0']) assert.equal(virtualInterface(n), true, n)
  for (const n of ['eth0', 'enp3s0', 'wlan0', 'wlp2s0', 'en0']) assert.equal(virtualInterface(n), false, n)
  assert.equal(bypassBlockedByRoot(0, {}), true)
  assert.equal(bypassBlockedByRoot(0, { IS_SANDBOX: '1' }), false)
  assert.equal(bypassBlockedByRoot(1000, {}), false)
})
