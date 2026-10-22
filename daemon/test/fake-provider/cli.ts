#!/usr/bin/env node
// The fake provider from a shell (docs/DEV-SETUP.md, "Fake provider"):
//   node test/fake-provider/cli.ts start [--port 48279]           run it (Ctrl-C ends it)
//   node test/fake-provider/cli.ts setup [--daemon 127.0.0.1:48272] register "Fake OpenRouter" on a TEST daemon
//   node test/fake-provider/cli.ts fault <kind> [--sid …] [--times n] [--for 10m] [--retry-after s] [--ms n] [--bytes n] [--window min|day]
//   node test/fake-provider/cli.ts clear | log | balance <usd|none|401>
// Kinds: credits, budget, in_flight, rate, rate_short, rate_bare, key, forbidden, overloaded, slow,
// cut, error_event, limit_sub. Its folder (the control token) is $HYPERNOVA_SUPPORT/fake-provider,
// else /tmp/hypernova-fake-<uid>; FAKE_PORT names its port for the other commands.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { startFake } from './server.ts'
import { FAULT_KINDS } from './faults.ts'

const dir = process.env.HYPERNOVA_SUPPORT ? join(process.env.HYPERNOVA_SUPPORT, 'fake-provider') : join(tmpdir(), `hypernova-fake-${process.getuid?.() ?? 0}`)
const [cmd, ...rest] = process.argv.slice(2)
const flag = (name: string) => {
  const i = rest.indexOf(`--${name}`)
  return i >= 0 ? rest[i + 1] : undefined
}
const port = Number(flag('port') ?? process.env.FAKE_PORT ?? 48279)
const base = `http://127.0.0.1:${port}`

/** "10m", "90s", "2h" or plain seconds. */
function seconds(s: string | undefined): number | undefined {
  if (!s) return undefined
  const m = /^(\d+(?:\.\d+)?)(s|m|h)?$/.exec(s.trim())
  if (!m) throw new Error(`not a duration: ${s}`)
  return Math.round(Number(m[1]) * (m[2] === 'h' ? 3600 : m[2] === 'm' ? 60 : 1))
}

async function control(method: string, path: string, body?: unknown): Promise<unknown> {
  const token = readFileSync(join(dir, 'token'), 'utf8').trim()
  const res = await fetch(base + path, { method, headers: { 'x-fake-token': token, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  const out = await res.json()
  if (!res.ok) throw new Error(`${res.status} ${JSON.stringify(out)}`)
  return out
}

async function main(): Promise<void> {
  switch (cmd) {
    case 'start': {
      const f = await startFake({ port, dir })
      console.log(`fake provider on ${f.url} (OpenRouter-style at ${f.url}/or), token in ${join(dir, 'token')}`)
      const stop = () => void f.close().then(() => process.exit(0))
      process.on('SIGINT', stop)
      process.on('SIGTERM', stop)
      return
    }
    case 'setup': {
      // Only a test daemon: its own folder must be named, so the owner's is never touched.
      const support = process.env.HYPERNOVA_SUPPORT
      if (!support) throw new Error('set HYPERNOVA_SUPPORT to the TEST daemon’s folder first')
      const daemon = flag('daemon') ?? `127.0.0.1:${process.env.HYPERNOVA_LOCAL_PORT ?? ''}`
      if (!/^127\.0\.0\.1:\d+$/.test(daemon) || daemon.endsWith(':47322')) throw new Error('name a test daemon’s local port (not 47322)')
      const admin = readFileSync(join(support, 'admin-token'), 'utf8').trim()
      const res = await fetch(`http://${daemon}/v1/providers`, {
        method: 'POST',
        headers: { authorization: `Bearer ${admin}`, 'content-type': 'application/json' },
        body: JSON.stringify({ name: 'Fake OpenRouter', kind: 'openrouter', baseUrl: `${base}/or`, apiKey: 'fake-or-key', models: { default: 'fake-model', opus: 'fake-model', sonnet: 'fake-model', haiku: 'fake-model' } }),
      })
      console.log(res.status, JSON.stringify(await res.json()))
      return
    }
    case 'fault': {
      const kind = rest[0]
      if (!kind || !FAULT_KINDS.includes(kind as never)) throw new Error(`kinds: ${FAULT_KINDS.join(', ')}`)
      const n = (v: string | undefined) => (v == null ? undefined : Number(v))
      console.log(JSON.stringify(await control('POST', '/_fake/faults', { kind, sid: flag('sid'), times: n(flag('times')), for: seconds(flag('for')), retryAfter: n(flag('retry-after')), ms: n(flag('ms')), bytes: n(flag('bytes')), window: flag('window') })))
      return
    }
    case 'clear':
      console.log(JSON.stringify(await control('DELETE', '/_fake/faults')))
      return
    case 'log': {
      const o = (await control('GET', '/_fake/faults')) as { faults: unknown[]; log: Array<Record<string, unknown>> }
      console.log('faults:', JSON.stringify(o.faults))
      for (const l of o.log) console.log(new Date(l.at as number).toISOString(), l.sid, l.method, l.path, l.status, `${l.ms}ms`, l.fault ?? '')
      return
    }
    case 'balance': {
      const v = rest[0]
      const body = v === '401' ? { status: 401 } : v === 'none' ? { remaining: null } : { remaining: Number(v) }
      console.log(JSON.stringify(await control('POST', '/_fake/balance', body)))
      return
    }
    default:
      console.log('usage: cli.ts start|setup|fault <kind>|clear|log|balance <usd|none|401>')
  }
}

main().catch((e) => {
  console.error((e as Error).message)
  process.exit(1)
})
