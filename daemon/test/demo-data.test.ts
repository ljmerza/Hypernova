// Keeps demo/data honest (demo/README.md). Those files are the made-up replies the demo server sends
// for the screenshots, written by hand, so nothing but this notices when the protocol moves on. Each
// is held against the shapes in protocol/fixtures/, which contract.test.ts keeps equal to what the
// daemon really sends: a field the protocol lost, gained or changed the type of fails here, naming
// the file and the field, rather than making a screenshot quietly wrong. Then the demo server itself
// runs, through a real pairing and the mutual TLS a phone uses, and must answer with those files.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { X509Certificate, createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs'
import { request } from 'node:https'
import { connect, createServer } from 'node:net'
import { networkInterfaces, tmpdir } from 'node:os'
import { join, relative } from 'node:path'
import WebSocket from 'ws'
import { LOOKS_LIKE_PLACEHOLDER, resolve } from '../../demo/placeholders.ts'

const ROOT = join(import.meta.dirname, '..', '..')
const DATA = join(ROOT, 'demo', 'data')
const FIXTURES = join(ROOT, 'protocol', 'fixtures')

const walk = (d: string): string[] => readdirSync(d).flatMap((n) => (statSync(join(d, n)).isDirectory() ? walk(join(d, n)) : [join(d, n)]))
const read = (f: string): unknown => JSON.parse(readFileSync(f, 'utf8'))
const fixture = (name: string): unknown => read(join(FIXTURES, name))
const files = walk(DATA).map((f) => relative(DATA, f).replaceAll('\\', '/'))
const dataOf = (rel: string): unknown => read(join(DATA, rel))

// ── Shapes ───────────────────────────────────────────────────────────────
// A shape is what examples of a value have in common. An object's keys present in every example are
// required, the others optional. Objects carrying `kind` (events, triggers, steps) are told apart by
// it, as the protocol's unions are. A null says nothing about the type next to it.
type Shape = { types: Set<string>; variants: Map<string, Variant>; items: Shape | null }
type Variant = { seen: number; keys: Map<string, { count: number; shape: Shape }> }
const blank = (): Shape => ({ types: new Set(), variants: new Map(), items: null })
const typeOf = (v: unknown) => (v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v)
const variantOf = (o: Record<string, unknown>) => (typeof o.kind === 'string' ? o.kind : '')

function learn(shape: Shape, v: unknown): Shape {
  shape.types.add(typeOf(v))
  if (Array.isArray(v)) {
    shape.items ??= blank()
    for (const x of v) learn(shape.items, x)
  } else if (v && typeof v === 'object') {
    const o = v as Record<string, unknown>
    const name = variantOf(o)
    let vr = shape.variants.get(name)
    if (!vr) shape.variants.set(name, (vr = { seen: 0, keys: new Map() }))
    vr.seen++
    for (const [k, x] of Object.entries(o)) {
      let e = vr.keys.get(k)
      if (!e) vr.keys.set(k, (e = { count: 0, shape: blank() }))
      e.count++
      learn(e.shape, x)
    }
  }
  return shape
}

/**
 * Where the fixtures show no example worth comparing with, or the protocol leaves the value open. A
 * path is dots and `[]`. `open`: anything goes below it (a tool's own input, a free-form map).
 * `optional`: documented in docs/PROTOCOL.md as present only sometimes, and used here without a
 * fixture that has it.
 */
type Allow = { open?: string[]; extra?: string[]; union?: string[] }

function differences(shape: Shape, v: unknown, path: string, allow: Allow, out: string[]): void {
  if (allow.open?.some((p) => path === p)) return
  const t = typeOf(v)
  const known = new Set(shape.types)
  known.delete('null')
  // Only ever seen as null, or never seen: nothing to compare with.
  if (known.size === 0) return
  // docs/PROTOCOL.md says which fields may be null; a fixture is one example and can't.
  if (t === 'null') return
  if (!known.has(t)) {
    out.push(`${path}: ${t}, where the protocol sends ${[...known].join(' or ')}`)
    return
  }
  if (Array.isArray(v)) {
    if (shape.items && shape.items.types.size) for (const x of v) differences(shape.items, x, `${path}[]`, allow, out)
    return
  }
  if (t !== 'object') return
  const o = v as Record<string, unknown>
  const name = variantOf(o)
  const vr = shape.variants.get(name)
  if (!vr) {
    // A `kind` no fixture shows (a trigger the snapshot's example doesn't use): the test's list says which.
    if (!allow.union?.includes(`${path}:${name}`)) out.push(`${path}: kind "${name}", which no fixture shows (${[...shape.variants.keys()].join(', ')})`)
    return
  }
  for (const [k, e] of vr.keys) if (e.count === vr.seen && !(k in o)) out.push(`${path}.${k}: missing`)
  for (const [k, x] of Object.entries(o)) {
    const e = vr.keys.get(k)
    if (!e) {
      if (!allow.extra?.includes(`${path}.${k}`)) out.push(`${path}.${k}: no fixture has this field`)
      continue
    }
    differences(e.shape, x, `${path}.${k}`, allow, out)
  }
}

// ── Which fixtures each file answers to ─────────────────────────────────
const snapshotShape = () => {
  const s = learn(blank(), fixture('snapshot.json'))
  // Each session and the rest as the feed says them: more examples of the same things.
  const top = s.variants.get('')!.keys
  learn(top.get('sessions')!.shape.items!, (fixture('ws-session.json') as { session: unknown }).session)
  learn(top.get('sessions')!.shape.items!, (fixture('ws-session-limited.json') as { session: unknown }).session)
  learn(top.get('approvals')!.shape.items!, (fixture('ws-approval.json') as { approval: unknown }).approval)
  // The snapshot's own list of automations is empty: their shape comes from the feed's.
  const autos = top.get('automations')!.shape.variants.get('')!.keys.get('list')!.shape
  const auto = (fixture('ws-automation.json') as { automation: Record<string, unknown> }).automation
  learn(autos.items ?? (autos.items = blank()), { ...auto, lastRun: (fixture('ws-run.json') as { run: unknown }).run })
  // Null in every fixture, so spelled out here from docs/PROTOCOL.md, "Types".
  const session = (fixture('ws-session.json') as { session: object }).session
  learn(top.get('sessions')!.shape.items!, { ...session, automation: { id: 'au', name: 'n', runId: 'ru' } })
  learn(top.get('usage')!.shape, { ...(fixture('rest-usage.json') as object), rate: { fiveHour: 0, sevenDay: 0, working: true } })
  return s
}

const allow: Record<string, Allow> = {
  snapshot: {
    // docs/PROTOCOL.md, "Automations": each has its own fields, and no fixture shows these kinds of them.
    open: ['$.sessions[].term', '$.approvals[].input', '$.approvals[].why'],
    extra: ['$.sessions[].agent', '$.automations.list[].lastRun.fired.detail'],
    union: [
      '$.automations.list[].trigger:cron', '$.automations.list[].trigger:github', '$.automations.list[].trigger:reset', '$.automations.list[].trigger:webhook',
      '$.automations.list[].action:start', '$.automations.list[].action:continue',
      '$.automations.list[].steps[]:start', '$.automations.list[].steps[]:continue',
      '$.automations.list[].lastRun.steps[]:start', '$.automations.list[].lastRun.steps[]:continue',
      '$.automations.list[].outputs[]:notify',
    ],
  },
  events: { open: ['$.events[].input'], extra: ['$.cursor', '$.full'] },
  agents: {},
  usage: {},
  // docs/PROTOCOL.md, "Statistics": codex is absent only where Codex isn't installed, and the fixture's server hasn't.
  stats: { extra: ['$.byAgent.codex'] },
  changes: {},
  diff: {},
  todos: {},
  models: {},
  commands: {},
}

/** What a file in demo/data is: its fixtures' shape and what may differ. Null: a file nothing explains, which fails. */
function kindOf(rel: string): { shape: () => Shape; allow: Allow } | null {
  const name = rel.split('/').pop()!
  const one = (f: string, a: Allow) => ({ shape: () => learn(blank(), fixture(f)), allow: a })
  if (name === 'snapshot.json') return { shape: snapshotShape, allow: allow.snapshot! }
  if (/^stats-(today|week|month|ytd|all)\.json$/.test(name)) return { shape: () => learn(learn(blank(), fixture('rest-stats.json')), fixture('rest-stats-backfill.json')), allow: allow.stats! }
  if (name === 'models.json') return one('rest-models.json', allow.models!)
  if (name === 'commands.json') return one('rest-commands.json', allow.commands!)
  if (!rel.includes('/sessions/')) return null
  if (name === 'events.json') return one('rest-session-events.json', allow.events!)
  if (name === 'agents.json') return one('rest-agents.json', allow.agents!)
  if (name === 'usage.json') {
    // The fixture's context is null, so its shape is spelled out here from docs/PROTOCOL.md, "Session usage".
    const example = fixture('rest-session-usage.json') as { usage: object }
    return { shape: () => learn(blank(), { usage: { ...example.usage, context: { used: 1, window: 1, pct: 1, exact: true } } }), allow: allow.usage! }
  }
  if (name === 'todos.json') return one('rest-session-todos.json', allow.todos!)
  if (name === 'changes.json') return one('rest-changes.json', allow.changes!)
  if (/^diff.*\.json$/.test(name)) return one('rest-changes-diff.json', allow.diff!)
  return null
}

test('the shape check itself: a missing, a new and a mistyped field all fail', () => {
  const shape = learn(learn(blank(), { a: 1, b: 'x', k: [{ kind: 'p', n: 1 }] }), { a: 2, b: null, k: [{ kind: 'q', m: 'y' }] })
  const check = (v: unknown) => {
    const out: string[] = []
    differences(shape, v, '$', {}, out)
    return out
  }
  assert.deepEqual(check({ a: 3, b: 'y', k: [{ kind: 'p', n: 2 }] }), [])
  assert.deepEqual(check({ b: 'y', k: [] }), ['$.a: missing'])
  assert.deepEqual(check({ a: 3, b: 'y', k: [], z: 1 }), ['$.z: no fixture has this field'])
  assert.deepEqual(check({ a: 'three', b: 'y', k: [] }), ['$.a: string, where the protocol sends number'])
  assert.deepEqual(check({ a: 3, b: 'y', k: [{ kind: 'r' }] }), ['$.k[]: kind "r", which no fixture shows (p, q)'])
})

test('demo/data has all three servers, each with a snapshot', () => {
  const servers = readdirSync(DATA).filter((n) => statSync(join(DATA, n)).isDirectory())
  assert.equal(servers.length, 3)
  for (const s of servers) assert.ok(files.includes(`${s}/snapshot.json`), `${s} has no snapshot`)
})

for (const rel of files) {
  test(`demo/data/${rel} has the protocol's shape`, () => {
    const kind = kindOf(rel)
    assert.ok(kind, `Nothing says what ${rel} is: add it to kindOf in this test, against the fixture it mirrors.`)
    const out: string[] = []
    differences(kind.shape(), resolve(dataOf(rel)), '$', kind.allow, out)
    assert.deepEqual(out, [], `\n${out.join('\n')}\nThe protocol and the demo's data disagree: change the data, or the allow list here if the protocol leaves it open.`)
  })
}

test('every placeholder in demo/data resolves', () => {
  const bad: string[] = []
  const look = (v: unknown, at: string) => {
    if (typeof v === 'string') {
      if (LOOKS_LIKE_PLACEHOLDER.test(v) && resolve(v) === v) bad.push(`${at}: ${v}`)
    } else if (Array.isArray(v)) v.forEach((x, i) => look(x, `${at}[${i}]`))
    else if (v && typeof v === 'object') for (const [k, x] of Object.entries(v)) look(x, `${at}.${k}`)
  }
  for (const rel of files) look(dataOf(rel), rel)
  assert.deepEqual(bad, [])
})

test('placeholders mean what demo/README.md says', () => {
  const now = new Date(2026, 10, 4, 9, 30, 0).getTime() // a Wednesday
  assert.equal(resolve('@now', now), now)
  assert.equal(resolve('@now-3m', now), now - 180_000)
  assert.equal(resolve('@now+1d2h', now), now + 26 * 3_600_000)
  assert.equal(resolve('@day', now), '2026-11-04')
  assert.equal(resolve('@day-4', now), '2026-10-31')
  assert.equal(resolve('@next 07:30', now), new Date(2026, 10, 5, 7, 30).getTime())
  assert.equal(resolve('@next 10:00', now), new Date(2026, 10, 4, 10, 0).getTime())
  assert.equal(resolve('@next 16:00 5', now), new Date(2026, 10, 6, 16, 0).getTime())
  assert.equal(resolve('@next 07:30 1-2', now), new Date(2026, 10, 9, 7, 30).getTime())
  assert.equal(resolve('@last 02:00', now), new Date(2026, 10, 4, 2, 0).getTime())
  assert.equal(resolve('@last 10:00', now), new Date(2026, 10, 3, 10, 0).getTime())
  assert.equal(resolve('@last 07:30 6-7', now), new Date(2026, 10, 1, 7, 30).getTime())
  assert.equal(resolve('@nowhere', now), '@nowhere')
  assert.deepEqual(resolve({ a: ['@now'], b: 1, c: null }, now), { a: [now], b: 1, c: null })
})

test('the data agrees with itself: approvals, ids and running workflows point at something', () => {
  for (const rel of files.filter((f) => f.endsWith('snapshot.json'))) {
    const s = dataOf(rel) as { mac: { projectsRoot: string }; sessions: { id: string; cwd: string; pendingApprovalIds: string[]; state: string }[]; approvals: { id: string; sessionId: string }[]; projects: { name: string }[] }
    const ids = new Set(s.sessions.map((x) => x.id))
    assert.equal(ids.size, s.sessions.length, `${rel}: a session id repeats`)
    for (const a of s.approvals) assert.ok(ids.has(a.sessionId), `${rel}: approval ${a.id} is for no session`)
    const asked = s.sessions.flatMap((x) => x.pendingApprovalIds)
    assert.deepEqual(asked.sort(), s.approvals.map((a) => a.id).sort(), `${rel}: pendingApprovalIds and approvals differ`)
    for (const x of s.sessions) assert.ok(x.cwd.startsWith(s.mac.projectsRoot), `${rel}: ${x.cwd} is outside the projects folder`)
  }
  for (const rel of files.filter((f) => f.includes('/sessions/'))) {
    const [server, , id] = rel.split('/')
    const s = dataOf(`${server}/snapshot.json`) as { sessions: { id: string }[] }
    assert.ok(s.sessions.some((x) => x.id === id), `${rel}: its session isn't in ${server}'s snapshot`)
  }
})

test('nothing in demo/data is real: no owner names, addresses, tokens', () => {
  // The owner's servers, projects and accounts, and the old demo's names. Whole words, any case.
  const names = /\b(z2|k1|shed|gym|orbit|arc|docker|cubxi|ljmerza|merza|prudnik\w*|hypernavigate|0stems|hyperscribe)\b/i
  const email = /[\w.+-]+@[\w-]+\.[a-z]{2,}/i
  const ipv4 = /\b(?!127\.0\.0\.1\b)\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/
  const token = /\b(sk-[a-z0-9]|ghp_|gho_|xox[bp]-|AKIA[0-9A-Z]{8})/
  const bad: string[] = []
  for (const rel of files) {
    const text = readFileSync(join(DATA, rel), 'utf8')
    for (const [what, re] of [['a real name', names], ['an email', email], ['an address', ipv4], ['a token', token]] as const) {
      const m = re.exec(text)
      if (m) bad.push(`${rel}: ${what}: ${m[0]}`)
    }
  }
  assert.deepEqual(bad, [])
})

// ── The server itself, as a phone meets it ───────────────────────────────
const scratch = mkdtempSync(join(tmpdir(), 'hn-demo-'))
const freePort = () =>
  new Promise<number>((res) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const p = (s.address() as { port: number }).port
      s.close(() => res(p))
    })
  })

const PORT = await freePort()
const child = spawn(process.execPath, [join(ROOT, 'demo', 'server.ts'), 'atlas'], {
  env: { ...process.env, HYPERNOVA_DEMO_HOME: join(scratch, 'home'), HYPERNOVA_DEMO_PORT: String(PORT), HYPERNOVA_DEMO_ADDRESS: '127.0.0.1' },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let printed = ''
child.stdout!.on('data', (b) => (printed += b))
child.stderr!.on('data', (b) => (printed += b))
after(() => {
  child.kill('SIGTERM')
  rmSync(scratch, { recursive: true, force: true })
})

type Reply = { status: number; body: any; peer: Buffer | null }
function call(path: string, o: { method?: string; body?: unknown; token?: string; key?: string; cert?: string } = {}): Promise<Reply> {
  return new Promise((res, rej) => {
    const text = o.body === undefined ? undefined : JSON.stringify(o.body)
    const r = request(
      {
        host: '127.0.0.1', port: PORT, path, method: o.method ?? 'GET', rejectUnauthorized: false, agent: false,
        key: o.key, cert: o.cert,
        headers: { ...(o.token ? { Authorization: `Bearer ${o.token}` } : {}), ...(text ? { 'Content-Type': 'application/json' } : {}) },
      },
      (m) => {
        const peer = (m.socket as import('node:tls').TLSSocket).getPeerCertificate?.()?.raw ?? null
        const parts: Buffer[] = []
        m.on('data', (p) => parts.push(p))
        m.on('end', () => res({ status: m.statusCode ?? 0, body: JSON.parse(Buffer.concat(parts).toString('utf8')), peer }))
      },
    )
    r.on('error', rej)
    r.end(text)
  })
}

async function pairLink(): Promise<URLSearchParams> {
  for (let i = 0; i < 200 && !/pair with /.test(printed); i++) await new Promise((r) => setTimeout(r, 50))
  const m = /pair with (hypernova:\/\/pair\?\S+)/.exec(printed)
  assert.ok(m, `The server didn't say a pairing link: ${printed}`)
  return new URL(m[1]!.replace('hypernova://pair', 'https://x/pair')).searchParams
}

/** A phone's key and CSR, CN its device id, as DeviceCert.kt makes them. */
function phoneKeys(deviceId: string): { key: string; csr: string } {
  const d = mkdtempSync(join(scratch, 'phone-'))
  const key = join(d, 'k.pem')
  execFileSync('openssl', ['ecparam', '-genkey', '-name', 'prime256v1', '-noout', '-out', key], { stdio: 'ignore' })
  const der = execFileSync('openssl', ['req', '-new', '-key', key, '-subj', `/CN=${deviceId}`, '-outform', 'DER'])
  return { key: readFileSync(key, 'utf8'), csr: der.toString('base64') }
}

test('a phone pairs, gets its certificate, and only then sees anything', async () => {
  const link = await pairLink()
  assert.equal(link.get('u'), `https://127.0.0.1:${PORT}`)
  assert.equal(link.get('lan'), '127.0.0.1')
  assert.equal(link.get('lp'), String(PORT))

  // The certificate on the wire is the one the link pins.
  const health = await call('/v1/health')
  assert.equal(health.status, 200)
  assert.equal(health.body.name, 'atlas')
  assert.equal(createHash('sha256').update(health.peer!).digest('hex'), link.get('fp'))

  // Nobody gets in by a wrong code, or to the data without a certificate.
  assert.equal((await call('/v1/pair', { method: 'POST', body: { code: 'wrong', deviceName: 'x' } })).status, 403)
  assert.equal((await call('/v1/snapshot')).body.error.code, 'device_cert_required')

  const paired = await call('/v1/pair', { method: 'POST', body: { code: link.get('c'), deviceName: 'Test phone', model: 'Test', installId: 'test-install-1', appVersion: '1' } })
  assert.equal(paired.status, 200)
  assert.deepEqual(Object.keys(paired.body).sort(), Object.keys(fixture('rest-pair.json') as object).sort())
  assert.equal(paired.body.mac.name, 'atlas')
  const token = paired.body.token as string

  // A code works once.
  assert.equal((await call('/v1/pair', { method: 'POST', body: { code: link.get('c'), deviceName: 'x' } })).status, 403)
  // The token alone isn't enough over this route: the same refusal the app asks for its certificate on.
  const noCert = await call('/v1/snapshot', { token })
  assert.equal(noCert.status, 401)
  assert.equal(noCert.body.error.code, 'device_cert_required')

  const phone = phoneKeys(paired.body.deviceId)
  const issued = await call('/v1/device/cert', { method: 'POST', token, body: { csr: phone.csr } })
  assert.equal(issued.status, 200)
  assert.deepEqual(Object.keys(issued.body).sort(), Object.keys(fixture('rest-device-cert.json') as object).sort())
  assert.equal(issued.body.fingerprint, link.get('fp'))
  const certPem = new X509Certificate(Buffer.from(issued.body.cert, 'base64')).toString()
  const me = { token, key: phone.key, cert: certPem }

  // A certificate someone else's CA signed doesn't get in.
  const stranger = mkdtempSync(join(scratch, 'stranger-'))
  execFileSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes', '-keyout', join(stranger, 'k.pem'), '-out', join(stranger, 'c.pem'), '-subj', `/CN=${paired.body.deviceId}`, '-days', '1'], { stdio: 'ignore' })
  const refused = await call('/v1/snapshot', { token, key: readFileSync(join(stranger, 'k.pem'), 'utf8'), cert: readFileSync(join(stranger, 'c.pem'), 'utf8') })
  assert.equal(refused.body.error.code, 'device_cert_required')

  // In: the snapshot is the file's, times resolved, with the listener as the phone must use it.
  const snap = await call('/v1/snapshot', me)
  assert.equal(snap.status, 200)
  assert.equal(snap.body.mac.name, 'atlas')
  assert.deepEqual(snap.body.lan, { port: PORT, addresses: ['127.0.0.1'], fingerprint: link.get('fp'), deviceCert: true })
  assert.equal(typeof snap.body.sessions[0].createdAt, 'number')
  const raw = dataOf('atlas/snapshot.json') as { sessions: { id: string; title: string }[] }
  assert.deepEqual(snap.body.sessions.map((s: { id: string }) => s.id), raw.sessions.map((s) => s.id))

  // The routes the six screens use answer with their files; each shape was held to the protocol above.
  const session = raw.sessions[0]!.id
  for (const [path, rel, key] of [
    [`/v1/sessions/${session}/events?limit=40&output=4096`, `atlas/sessions/${session}/events.json`, 'events'],
    [`/v1/sessions/${session}/agents`, `atlas/sessions/${session}/agents.json`, 'agents'],
    [`/v1/sessions/${session}/changes`, `atlas/sessions/${session}/changes.json`, 'changes'],
    ['/v1/stats?range=all', 'atlas/stats-all.json', 'days'],
    ['/v1/stats', 'atlas/stats-week.json', 'days'],
    ['/v1/models', 'atlas/models.json', 'providers'],
  ] as const) {
    const r = await call(path, me)
    assert.equal(r.status, 200, path)
    assert.equal(JSON.stringify(r.body[key]).length > 2, true, `${path}: empty ${key}`)
    assert.equal(r.body[key].length, (dataOf(rel) as Record<string, unknown[]>)[key]!.length ?? undefined, path)
  }
  const changes = (await call(`/v1/sessions/${session}/changes`, me)).body.changes.files as { path: string }[]
  const diff = await call(`/v1/sessions/${session}/changes/diff?path=${encodeURIComponent(changes[0]!.path)}`, me)
  assert.equal(diff.status, 200)
  assert.match(diff.body.diff, /^diff --git/)
  assert.equal((await call('/v1/stats?range=decade', me)).body.error.code, 'bad_range')
  // Anything that would change something isn't there.
  assert.equal((await call(`/v1/sessions/${session}/stop`, { ...me, method: 'POST', body: {} })).status, 404)

  // The feed: refused without the certificate; with it, the snapshot first, as on a real server.
  const bare = new WebSocket(`wss://127.0.0.1:${PORT}/v1/ws`, { rejectUnauthorized: false, headers: { Authorization: `Bearer ${token}` } })
  const bareStatus = await new Promise<number>((res) => {
    bare.on('unexpected-response', (_q, m) => res(m.statusCode ?? 0))
    bare.on('open', () => res(101))
    bare.on('error', () => {})
  })
  bare.terminate()
  assert.equal(bareStatus, 401)
  const ws = new WebSocket(`wss://127.0.0.1:${PORT}/v1/ws`, { rejectUnauthorized: false, key: phone.key, cert: certPem, headers: { Authorization: `Bearer ${token}` } })
  const first = await new Promise<{ t: string; snapshot: { mac: { name: string }; lan: { deviceCert: boolean } } }>((res, rej) => {
    ws.once('message', (m) => res(JSON.parse(String(m))))
    ws.once('error', rej)
  })
  ws.terminate()
  assert.equal(first.t, 'snapshot')
  assert.equal(first.snapshot.mac.name, 'atlas')
  assert.equal(first.snapshot.lan.deviceCert, true)
})

test('the demo server is out of reach from the network, and keeps to its own folder', async () => {
  await pairLink()
  assert.ok(statSync(join(scratch, 'home', 'atlas', 'tls', 'server.crt')).isFile())
  const addresses = Object.values(networkInterfaces()).flat().filter((a) => a && !a.internal && a.family === 'IPv4')
  for (const a of addresses) {
    const reached = await new Promise<boolean>((res) => {
      const s = connect({ host: a!.address, port: PORT, timeout: 1500 })
      s.once('connect', () => (s.destroy(), res(true)))
      s.once('error', () => res(false))
      s.once('timeout', () => (s.destroy(), res(false)))
    })
    assert.equal(reached, false, `Port ${PORT} answers on ${a!.address}`)
  }
})
