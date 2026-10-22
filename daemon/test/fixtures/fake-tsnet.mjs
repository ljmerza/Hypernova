// A stand-in for the Tailscale helper (daemon/tsnet): speaks its JSON lines, obeys its stdin
// commands, and does what the file fake-tsnet-mode next to its state folder says: "login" (needs
// a sign-in until told "login" again, then runs), "running" (runs at once), "crash" (exits 3 after
// its first line), "slow-exit" (ignores SIGTERM, so the daemon has to SIGKILL it). It records its
// arguments and environment in fake-tsnet-args.log there (the daemon hands it a minimal environment).
import { appendFileSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

const state = process.argv[process.argv.indexOf('--state') + 1] || '.'
const here = dirname(state)
let mode = 'running'
try {
  mode = readFileSync(join(here, 'fake-tsnet-mode'), 'utf8').trim() || 'running'
} catch {}
appendFileSync(join(here, 'fake-tsnet-args.log'), JSON.stringify({ args: process.argv.slice(2), env: process.env }) + '\n')
const port = Number(process.argv[process.argv.indexOf('--port') + 1] || 47321)
const control = process.argv.includes('--control') ? process.argv[process.argv.indexOf('--control') + 1] : ''
const https = mode !== 'pinned'
const say = (o) => process.stdout.write(JSON.stringify({ t: 'status', ...o }) + '\n')
const running = () =>
  say({
    state: 'Running',
    dnsName: 'hypernova-test.tail.ts.net',
    ips: ['100.64.0.9', 'fd7a:115c:a1e0::9'],
    account: 'owner@example.com',
    tailnet: 'tail.ts.net',
    controlUrl: control,
    https,
    listening: https ? `https://hypernova-test.tail.ts.net:${port}` : `https://100.64.0.9:${port}`,
    tls: https ? 'tailscale' : 'pinned',
  })

say({ state: 'Starting', controlUrl: control })
if (mode === 'crash') process.exit(3)
if (mode === 'login') say({ state: 'NeedsLogin', loginUrl: 'https://login.tailscale.com/a/abc123', qr: '▄▄▄\n███\n' })
else running()

if (mode === 'slow-exit') process.on('SIGTERM', () => {})
else process.on('SIGTERM', () => process.exit(0))

let buf = ''
process.stdin.on('data', (d) => {
  buf += d
  let i
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i)
    buf = buf.slice(i + 1)
    let c
    try {
      c = JSON.parse(line).c
    } catch {
      continue
    }
    if (c === 'login') running()
    else if (c === 'logout') say({ state: 'NeedsLogin', loginUrl: 'https://login.tailscale.com/a/def456', qr: '███\n' })
    else if (c === 'status') say({ state: 'Running' })
  }
})
process.stdin.on('end', () => process.exit(0))
