// Small Linux fixes (docs/LINUX.md): the program's place apart from the state (a container's
// image), log rolling, a container's own start time, and the local route skipping bridges.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-linuxfix-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
after(() => rmSync(scratch, { recursive: true, force: true }))

const { cliContext } = await import('../src/cli/context.ts')
const { launcherText, unitText } = await import('../src/cli/service.ts')
const { rollLog } = await import('../src/util.ts')
const { containerStartedAt } = await import('../src/reboot.ts')
const { lanAddresses } = await import('../src/lan.ts')

test('the program lives where HYPERNOVA_APP says, the state stays under the support folder (a rebuilt image runs its new daemon with the same volume)', () => {
  const env = { HYPERNOVA_APP: '/opt/hypernova/app', HYPERNOVA_NODE_ROOT: '/opt/hypernova/node', HYPERNOVA_NODE_PATH: '/opt/hypernova/node-path', HYPERNOVA_LAUNCHER: '/opt/hypernova/bin/hypernova' }
  const c = cliContext({ platform: 'linux', home: '/home/h', env: { HOME: '/home/h', ...env } })
  assert.equal(c.appDir, '/opt/hypernova/app')
  assert.equal(c.daemonDir, '/opt/hypernova/app/daemon')
  assert.equal(c.nodeRoot, '/opt/hypernova/node')
  assert.equal(c.nodePathFile, '/opt/hypernova/node-path')
  assert.equal(c.launcher, '/opt/hypernova/bin/hypernova')
  assert.match(c.support, /^\/home\/h\/\.local\/share\/hypernova$/)
  // Unset, or not an absolute path: everything under the support folder, as before.
  const d = cliContext({ platform: 'linux', home: '/home/h', env: { HOME: '/home/h', HYPERNOVA_APP: 'relative/app' } })
  assert.equal(d.appDir, '/home/h/.local/share/hypernova/app')
  assert.equal(d.launcher, '/home/h/.local/bin/hypernova')
  // The launcher and the unit carry the choice, so the service finds the same program.
  assert.match(launcherText(c, '/opt/hypernova/node/v24/bin/node'), /export HYPERNOVA_APP='\/opt\/hypernova\/app'/)
  assert.match(unitText(c, '/n/node'), /Environment="HYPERNOVA_APP=\/opt\/hypernova\/app"/)
})

test('a log rolls to .1 at its limit and starts over; below it nothing happens', () => {
  const f = join(scratch, 'x.log')
  writeFileSync(f, 'a'.repeat(100))
  assert.equal(rollLog(f, 1000), false)
  assert.equal(rollLog(join(scratch, 'missing.log'), 10), false)
  writeFileSync(f, 'b'.repeat(2000))
  assert.equal(rollLog(f, 1000), true)
  assert.equal(readFileSync(f + '.1', 'utf8').length, 2000)
  assert.equal(statSync(f).size, 0)
  assert.equal(statSync(f).mode & 0o777, 0o600)
  writeFileSync(f, 'c'.repeat(1500))
  rollLog(f, 1000)
  assert.equal(readFileSync(f + '.1', 'utf8')[0], 'c')
  assert.equal(existsSync(f + '.2'), false)
})

test('a container is as old as its first process, not the host', () => {
  // /proc/1/stat: pid (comm) state ppid ... starttime is the 22nd field; 12345 ticks = 123.45 s after the host booted.
  const stat = '1 (tini) S 0 1 1 0 -1 4194560 100 0 0 0 1 2 0 0 20 0 1 0 12345 1000 100 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0'
  assert.equal(containerStartedAt(1_000_000, () => stat), 1_000_000 + 123_450)
  // A command with spaces and parentheses in its name.
  assert.equal(containerStartedAt(0, () => stat.replace('(tini)', '(my (odd) cmd)')), 123_450)
  assert.equal(containerStartedAt(0, () => { throw new Error('no /proc') }), null)
  assert.equal(containerStartedAt(0, () => 'garbage'), null)
})

test('the local route skips bridges and keeps the Wi-Fi', () => {
  const nic = (address: string) => ({ address, internal: false, family: 'IPv4', netmask: '', mac: '', cidr: null }) as any
  assert.deepEqual(lanAddresses({ docker0: [nic('172.17.0.1')], 'br-1a2b': [nic('172.18.0.1')], virbr0: [nic('192.168.122.1')], wlan0: [nic('192.168.1.20')] }), ['192.168.1.20'])
})
