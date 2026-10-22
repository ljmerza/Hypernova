// A whole hypernova.observer, on this machine and nowhere else, for names-integration.test.ts:
//
//   - the name service: the real Worker (names/src, bundled with names/'s own esbuild) run by Node,
//     as names/test/harness.ts runs it (createWorker with a stand-in Cloudflare API), its D1
//     database an in-memory node:sqlite one with the real migrations, behind a local HTTP port
//     that stamps each request with a chosen CF-Connecting-IP;
//   - Cloudflare's DNS API: a stand-in that keeps the records and mirrors them into
//   - pebble-challtestsrv: a DNS server the test and Pebble both ask;
//   - Let's Encrypt: Pebble, its HTTPS on a certificate made here, its DNS checks against
//     challtestsrv.
//
// Nothing leaves 127.0.0.1. Returns null, with the reason, when a piece isn't installed: names/
// needs `npm install`, Pebble `go install github.com/letsencrypt/pebble/v2/cmd/pebble@latest` and
// `…/cmd/pebble-challtestsrv@latest` (looked for in $PEBBLE_BIN_DIR, ~/go/bin, then on PATH).
import { spawn, execFileSync, type ChildProcess } from 'node:child_process'
import { createServer as createHttpServer, type Server } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { createServer as createTcpServer, type AddressInfo } from 'node:net'
import { existsSync, mkdirSync, readFileSync, writeFileSync, createWriteStream } from 'node:fs'
import { delimiter, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { DatabaseSync } from 'node:sqlite'

const NAMES = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'names')
const OPENSSL = existsSync('/usr/bin/openssl') ? '/usr/bin/openssl' : 'openssl'

export type CfRecord = { id: string; type: string; name: string; content: string; ttl: number; comment: string | null; created_on: string }

export type NamesStack = {
  /** The name service's base URL (plain HTTP on loopback, as the Worker sees https://api.hypernova.observer). */
  serviceUrl: string
  /** The address Cloudflare would report for the next calls (the rate limits count by it). */
  callerIp: string
  /** The records "at Cloudflare". */
  records: Map<string, CfRecord>
  /** challtestsrv's DNS ("127.0.0.1:port"). */
  dns: string
  /** Pebble's directory, the certificate its HTTPS uses, and the root its certificates chain to (PEM files). */
  directory: string
  acmeCaFile: string
  rootsFile: string
  /** The Worker's vars (limits); changes apply to the next request. */
  env: Record<string, unknown>
  close(): Promise<void>
}

const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createTcpServer()
    s.listen(0, '127.0.0.1', () => {
      const p = (s.address() as AddressInfo).port
      s.close(() => resolve(p))
    })
  })

function findBin(name: string, realHome: string): string | null {
  const dirs = [process.env.PEBBLE_BIN_DIR, join(realHome, 'go', 'bin'), ...(process.env.PATH ?? '').split(delimiter)].filter(Boolean) as string[]
  for (const d of dirs) if (existsSync(join(d, name))) return join(d, name)
  return null
}

/** D1's API over node:sqlite, the calls names/src/db.ts makes. */
function d1(db: DatabaseSync) {
  class Stmt {
    sql: string
    args: unknown[]
    constructor(sql: string, args: unknown[] = []) {
      this.sql = sql
      this.args = args
    }
    bind(...args: unknown[]) {
      return new Stmt(this.sql, args)
    }
    async first() {
      return db.prepare(this.sql).get(...(this.args as never[])) ?? null
    }
    async all() {
      return { results: db.prepare(this.sql).all(...(this.args as never[])), success: true }
    }
    async run() {
      const r = db.prepare(this.sql).run(...(this.args as never[]))
      return { success: true, meta: { changes: Number(r.changes) } }
    }
  }
  return {
    prepare: (sql: string) => new Stmt(sql),
    async batch(stmts: Stmt[]) {
      db.exec('BEGIN')
      try {
        const out = stmts.map((s) => db.prepare(s.sql).run(...(s.args as never[])))
        db.exec('COMMIT')
        return out
      } catch (e) {
        db.exec('ROLLBACK')
        throw e
      }
    },
  }
}

async function waitFor(what: string, ok: () => Promise<boolean>, ms = 15_000): Promise<void> {
  const until = Date.now() + ms
  while (Date.now() < until) {
    if (await ok().catch(() => false)) return
    await new Promise((r) => setTimeout(r, 100))
  }
  throw new Error(`${what} didn't start`)
}

function httpsGet(url: string, ca: string): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const req = httpsRequest(url, { ca, headers: { 'user-agent': 'hypernova-test' } }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (body += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body }))
    })
    req.on('error', reject)
    req.end()
  })
}

export async function startNamesStack(dir: string, realHome: string): Promise<NamesStack | string> {
  const children: ChildProcess[] = []
  // Pebble and challtestsrv never outlive the test process, however it ends.
  process.once('exit', () => children.forEach((c) => c.kill()))
  try {
    return await start(dir, realHome, children)
  } catch (e) {
    // Nothing left running when a piece fails to start.
    for (const c of children) c.kill()
    throw e
  }
}

async function start(dir: string, realHome: string, children: ChildProcess[]): Promise<NamesStack | string> {
  const esbuildPath = join(NAMES, 'node_modules', 'esbuild', 'lib', 'main.js')
  if (!existsSync(esbuildPath)) return 'names/ has no node_modules (npm install there)'
  const pebbleBin = findBin('pebble', realHome)
  const challBin = findBin('pebble-challtestsrv', realHome)
  if (!pebbleBin || !challBin) return 'Pebble isn’t installed (go install github.com/letsencrypt/pebble/v2/cmd/pebble@latest and …/cmd/pebble-challtestsrv@latest)'
  mkdirSync(dir, { recursive: true })

  // challtestsrv: DNS only, and no catch-all answers (a name it wasn't told about doesn't exist).
  const [dnsPort, mgmtPort, acmePort, acmeMgmtPort, vaHttp, vaTls] = await Promise.all([freePort(), freePort(), freePort(), freePort(), freePort(), freePort()])
  const mgmt = `http://127.0.0.1:${mgmtPort}`
  const chall = spawn(challBin, [`-dnsserver=127.0.0.1:${dnsPort}`, `-management=127.0.0.1:${mgmtPort}`, '-http01=', '-https01=', '-tlsalpn01=', '-doh=', '-defaultIPv4=', '-defaultIPv6='], { stdio: ['ignore', 'pipe', 'pipe'] })
  const challLog = createWriteStream(join(dir, 'challtestsrv.log'))
  chall.stdout!.pipe(challLog)
  chall.stderr!.pipe(challLog)
  children.push(chall)
  const tell = (path: string, body: unknown) => fetch(mgmt + path, { method: 'POST', body: JSON.stringify(body) }).then((r) => r.text())
  await waitFor('challtestsrv', async () => (await fetch(mgmt + '/dns-request-history', { method: 'POST', body: '{"host":"x"}' })).status < 500)

  // Pebble: its own HTTPS certificate, made here; DNS checks against challtestsrv; nonces refused now
  // and then (so the client's retry is exercised); no random sleeps.
  const key = join(dir, 'pebble.key')
  const crt = join(dir, 'pebble.crt')
  execFileSync(OPENSSL, ['ecparam', '-genkey', '-name', 'prime256v1', '-noout', '-out', key])
  execFileSync(OPENSSL, ['req', '-new', '-x509', '-key', key, '-out', crt, '-days', '2', '-subj', '/CN=localhost', '-addext', 'subjectAltName=IP:127.0.0.1,DNS:localhost'], { stdio: ['ignore', 'pipe', 'pipe'] })
  const config = join(dir, 'pebble.json')
  writeFileSync(
    config,
    JSON.stringify({
      pebble: {
        listenAddress: `127.0.0.1:${acmePort}`,
        managementListenAddress: `127.0.0.1:${acmeMgmtPort}`,
        certificate: crt,
        privateKey: key,
        httpPort: vaHttp,
        tlsPort: vaTls,
        ocspResponderURL: '',
        externalAccountBindingRequired: false,
        domainBlocklist: [],
        retryAfter: { authz: 1, order: 1 },
        keyAlgorithm: 'ecdsa',
        profiles: { default: { description: 'test', validityPeriod: 7776000 } },
      },
    }),
  )
  const pebble = spawn(pebbleBin, ['-config', config, '-dnsserver', `127.0.0.1:${dnsPort}`], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, PEBBLE_VA_NOSLEEP: '1', PEBBLE_WFE_NONCEREJECT: '15', PEBBLE_AUTHZREUSE: '0' },
  })
  const pebbleLog = createWriteStream(join(dir, 'pebble.log'))
  pebble.stdout!.pipe(pebbleLog)
  pebble.stderr!.pipe(pebbleLog)
  children.push(pebble)
  const caPem = readFileSync(crt, 'utf8')
  const directory = `https://127.0.0.1:${acmePort}/dir`
  await waitFor('Pebble', async () => (await httpsGet(directory, caPem)).status === 200)
  const root = await httpsGet(`https://127.0.0.1:${acmeMgmtPort}/roots/0`, caPem)
  const rootsFile = join(dir, 'pebble-root.pem')
  writeFileSync(rootsFile, root.body)

  // Cloudflare's DNS records API: the four calls the Worker makes; each record mirrored into DNS.
  const records = new Map<string, CfRecord>()
  let nextId = 1
  const mirror = async (r: CfRecord, on: boolean) => {
    const host = r.name + '.'
    if (r.type === 'TXT') await tell(on ? '/set-txt' : '/clear-txt', on ? { host, value: r.content } : { host })
    if (r.type === 'A') {
      await tell('/clear-a', { host })
      const left = [...records.values()].filter((x) => x.type === 'A' && x.name === r.name).map((x) => x.content)
      if (left.length) await tell('/add-a', { host, addresses: left })
    }
  }
  const envelope = (status: number, result: unknown) => new Response(JSON.stringify({ success: status < 400, result, errors: status < 400 ? [] : [{ code: 1, message: 'no' }] }), { status, headers: { 'content-type': 'application/json' } })
  const cloudflare = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input instanceof Request ? input.url : input))
    const method = (init?.method ?? 'GET').toUpperCase()
    if (new Headers(init?.headers).get('authorization') !== 'Bearer test-token') return envelope(403, null)
    const m = /\/zones\/zone-test\/dns_records(?:\/([^/]+))?$/.exec(url.pathname)
    if (!m) return envelope(404, null)
    const body = init?.body ? JSON.parse(String(init.body)) : {}
    if (!m[1] && method === 'POST') {
      const r: CfRecord = { id: `rec${nextId++}`, type: body.type, name: body.name, content: body.content, ttl: body.ttl, comment: body.comment ?? null, created_on: new Date().toISOString() }
      records.set(r.id, r)
      await mirror(r, true)
      return envelope(200, r)
    }
    if (!m[1] && method === 'GET') {
      const prefix = url.searchParams.get('comment.startswith')
      return envelope(200, [...records.values()].filter((r) => !prefix || r.comment?.startsWith(prefix)))
    }
    const r = m[1] ? records.get(m[1]) : undefined
    if (!r) return envelope(404, null)
    if (method === 'PATCH') {
      Object.assign(r, { type: body.type ?? r.type, content: body.content ?? r.content })
      await mirror(r, true)
      return envelope(200, r)
    }
    if (method === 'DELETE') {
      records.delete(r.id)
      await mirror(r, false)
      return envelope(200, { id: r.id })
    }
    return envelope(405, null)
  }

  // The Worker: bundled as Wrangler would (TypeScript with parameter properties, which Node won't strip).
  const esbuild = (await import(esbuildPath)) as { build: (o: object) => Promise<{ outputFiles: { text: string }[] }> }
  const out = await esbuild.build({ entryPoints: [join(NAMES, 'src', 'index.ts')], bundle: true, format: 'esm', platform: 'neutral', target: 'es2022', write: false, logLevel: 'silent' })
  const workerFile = join(dir, 'worker.mjs')
  writeFileSync(workerFile, out.outputFiles[0]!.text)
  const { createWorker } = (await import(workerFile)) as { createWorker: (d: { fetch: typeof fetch; now: () => number }) => { fetch: (r: Request, env: unknown, ctx: unknown) => Promise<Response> } }
  const db = new DatabaseSync(':memory:')
  db.exec(readFileSync(join(NAMES, 'migrations', '0001_init.sql'), 'utf8'))
  const env: Record<string, unknown> = { DB: d1(db), DOMAIN: 'hypernova.observer', CF_ZONE_ID: 'zone-test', CF_API_TOKEN: 'test-token', CF_API_BASE: 'https://cloudflare.invalid/client/v4' }
  const worker = createWorker({ fetch: cloudflare as typeof fetch, now: () => Math.floor(Date.now() / 1000) })

  const stack = { callerIp: '93.184.216.34' } as NamesStack
  const http: Server = createHttpServer(async (req, res) => {
    const chunks: Buffer[] = []
    for await (const c of req) chunks.push(c as Buffer)
    const headers = new Headers()
    for (const [k, v] of Object.entries(req.headers)) if (typeof v === 'string' && k !== 'host') headers.set(k, v)
    headers.set('cf-connecting-ip', stack.callerIp)
    const method = req.method ?? 'GET'
    const reply = await worker.fetch(new Request('https://api.hypernova.observer' + (req.url ?? '/'), { method, headers, body: method === 'GET' || method === 'HEAD' ? undefined : Buffer.concat(chunks) }), env, {})
    res.writeHead(reply.status, Object.fromEntries(reply.headers))
    res.end(Buffer.from(await reply.arrayBuffer()))
  })
  await new Promise<void>((r) => http.listen(0, '127.0.0.1', r))

  return Object.assign(stack, {
    serviceUrl: `http://127.0.0.1:${(http.address() as AddressInfo).port}`,
    records,
    dns: `127.0.0.1:${dnsPort}`,
    directory,
    acmeCaFile: crt,
    rootsFile,
    env,
    async close() {
      http.closeAllConnections()
      await new Promise((r) => http.close(r))
      for (const c of children) c.kill()
      db.close()
    },
  })
}
