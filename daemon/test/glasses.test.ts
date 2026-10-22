// The glasses through the phone (glasses.ts; docs/PROTOCOL.md, "The glasses through the phone"):
// Lens reaches the daemon through the Hypernova app on the same phone, with the phone's token and
// `X-Hypernova-Via: lens`. The marker counts only from a phone app; the glasses show in the Mac's
// device list and open the Lens guide the first time; they can't manage the phone; their read-aloud
// plays on their own phone and its progress comes back to them alone; "Forget" on the Mac refuses
// them until the phone allows them again.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { WebSocket } from 'ws'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-glasses-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
const fakeClaude = join(scratch, 'claude-bin')
writeFileSync(fakeClaude, '#!/bin/sh\necho "9.9.9 (Claude Code)"\n')
chmodSync(fakeClaude, 0o755)
process.env.CLAUDE_BIN = fakeClaude
// Nothing here reads aloud for real: Soniox is a closed port and the plays only need routing.
const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
const API = await freePort()
const LOCAL = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)
process.env.HYPERNOVA_LAN_PORT = '0'

const { startServers } = await import('../src/http.ts')
const { addDevice, adminToken, getDevice } = await import('../src/store.ts')
const { anyGlasses, viaLens } = await import('../src/glasses.ts')
const { setGuide } = await import('../src/guide.ts')

const PHONE = 'glasses-test-phone-0123456789abcdef'
const PHONE2 = 'glasses-test-phone2-0123456789abcdef'
const LENSDEV = 'glasses-test-lensdev-0123456789abcdef'
addDevice({ id: 'phoneA', name: 'Nothing Phone', model: 'A065', createdAt: Date.now(), lastSeenAt: Date.now() }, PHONE)
addDevice({ id: 'phoneB', name: 'Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, PHONE2)
addDevice({ id: 'oldLens', name: 'G2 · Lens', model: 'Even G2 · Hypernova Lens', createdAt: Date.now(), lastSeenAt: Date.now(), client: 'lens' }, LENSDEV)

const VIA = { 'x-hypernova-via': 'lens', 'x-hypernova-lens-version': '0.1.7' }

type Reply = { status: number; json: any }
function send(port: number, method: string, path: string, token: string | null, headers: Record<string, string> = {}, body?: unknown): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const req = request({ host: '127.0.0.1', port, method, path, headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(data ? { 'content-type': 'application/json' } : {}), ...headers } }, (res) => {
      let text = ''
      res.on('data', (c) => (text += c))
      res.on('end', () => {
        let json: any = null
        try {
          json = JSON.parse(text)
        } catch {}
        resolve({ status: res.statusCode ?? 0, json })
      })
    })
    req.on('error', reject)
    req.end(data)
  })
}
const api = (method: string, path: string, token: string | null, headers: Record<string, string> = {}, body?: unknown) => send(API, method, path, token, headers, body)
const mac = (method: string, path: string, body?: unknown) => send(LOCAL, method, path, adminToken, {}, body)

/** A live feed that keeps every message it hears, and can wait for one. */
class Feed {
  ws: WebSocket
  msgs: any[] = []
  closed: number | null = null
  private waiters: Array<() => void> = []
  constructor(path: string, token: string, headers: Record<string, string> = {}) {
    this.ws = new WebSocket(`ws://127.0.0.1:${API}${path}`, { headers: { authorization: `Bearer ${token}`, ...headers } })
    this.ws.on('message', (d) => {
      try {
        this.msgs.push(JSON.parse(String(d)))
      } catch {}
      for (const w of [...this.waiters]) w()
    })
    this.ws.on('close', (code) => {
      this.closed = code
      for (const w of [...this.waiters]) w()
    })
    this.ws.on('error', () => {})
  }
  open(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.ws.once('open', () => resolve())
      this.ws.once('unexpected-response', (_req, res) => reject(new Error(`status ${res.statusCode}`)))
      this.ws.once('error', reject)
    })
  }
  json(m: object): void {
    this.ws.send(JSON.stringify(m))
  }
  wait(pred: (m: any) => boolean, ms = 3000): Promise<any> {
    const found = this.msgs.find(pred)
    if (found) return Promise.resolve(found)
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('no such message')), ms)
      const w = () => {
        const m = this.msgs.find(pred)
        if (!m) return
        clearTimeout(t)
        this.waiters = this.waiters.filter((x) => x !== w)
        resolve(m)
      }
      this.waiters.push(w)
    })
  }
  waitClosed(ms = 3000): Promise<number> {
    if (this.closed !== null) return Promise.resolve(this.closed)
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('still open')), ms)
      this.waiters.push(() => {
        if (this.closed === null) return
        clearTimeout(t)
        resolve(this.closed)
      })
    })
  }
  close(): void {
    try {
      this.ws.close()
    } catch {}
  }
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

let stop: () => Promise<void>
let macFeed: Feed
before(async () => {
  stop = startServers()
  await sleep(150)
  macFeed = new Feed('/v1/ws?client=mac', adminToken)
  await macFeed.open()
})
after(async () => {
  macFeed.close()
  await stop()
  rmSync(scratch, { recursive: true, force: true })
})

const glassesListed = async () => ((await mac('GET', '/local/devices')).json.devices as any[]).filter((d) => d.client === 'lens' && d.via)

test('the marker counts only from a phone app: not from the admin token, not from glasses paired on their own', async () => {
  assert.equal(viaLens({ headers: { 'x-hypernova-via': 'lens' } } as any, getDevice('oldLens')), false)
  assert.equal(viaLens({ headers: { 'x-hypernova-via': 'lens' } } as any, null), false)
  assert.equal(viaLens({ headers: { 'x-hypernova-via': 'LENS ' } } as any, getDevice('phoneA')), true)
  assert.equal(viaLens({ headers: { 'x-hypernova-via': 'mac' } } as any, getDevice('phoneA')), false)
  // The admin token (the Mac app over loopback) with the marker is still the Mac app.
  assert.equal((await api('GET', '/v1/snapshot', adminToken, VIA)).status, 200)
  // A Lens that paired as a device of its own, with the marker, is still that device.
  assert.equal((await api('GET', '/v1/snapshot', LENSDEV, VIA)).status, 200)
  // The phone itself without the marker is the phone.
  assert.equal((await api('GET', '/v1/snapshot', PHONE)).status, 200)
  assert.deepEqual(await glassesListed(), [])
  assert.equal(anyGlasses(), false)
})

test('the glasses through a phone show in the Mac’s list and open the Lens guide the first time', async () => {
  const r = await api('GET', '/v1/snapshot', PHONE, VIA)
  assert.equal(r.status, 200)
  assert.ok(Array.isArray(r.json.sessions))
  const listed = await glassesListed()
  assert.equal(listed.length, 1)
  assert.equal(listed[0].id, 'lens-phoneA')
  assert.equal(listed[0].via, 'phoneA')
  assert.equal(listed[0].client, 'lens')
  assert.equal(listed[0].appVersion, '0.1.7')
  assert.match(listed[0].name, /Even glasses · Nothing Phone/)
  // The phone stays listed as itself.
  assert.ok(((await mac('GET', '/local/devices')).json.devices as any[]).some((d) => d.id === 'phoneA' && !d.client))
  await macFeed.wait((m) => m.t === 'guide.show' && m.reason === 'paired')
  await macFeed.wait((m) => m.t === 'devices')
  assert.equal(anyGlasses(), true)
  // Seen again: no second "paired".
  const shows = macFeed.msgs.filter((m) => m.t === 'guide.show').length
  assert.equal((await api('GET', '/v1/snapshot', PHONE, VIA)).status, 200)
  await sleep(100)
  assert.equal(macFeed.msgs.filter((m) => m.t === 'guide.show').length, shows)
})

test('the glasses can’t manage the phone they come through', async () => {
  const forget = await api('DELETE', '/v1/devices/me', PHONE, VIA)
  assert.equal(forget.status, 403)
  assert.equal(forget.json.error.code, 'not_for_glasses')
  assert.ok(getDevice('phoneA'), 'the phone is still paired')
  assert.equal((await api('POST', '/v1/device/cert', PHONE, VIA, { csr: 'AA' })).status, 403)
  assert.equal((await api('PUT', '/v1/devices/me/glasses', PHONE, VIA, {})).status, 403)
  assert.equal((await api('DELETE', '/v1/devices/me/glasses', PHONE, VIA)).status, 403)
  // Only a phone app carries glasses.
  assert.equal((await api('PUT', '/v1/devices/me/glasses', LENSDEV, {}, {})).json.error.code, 'not_a_phone')
})

test('what the phone guards with a fingerprint is the phone’s alone: the glasses get 403 needs_phone', async () => {
  const hub = await import('../src/hub.ts')
  const project = join(process.env.HOME!, 'Projects', 'app')
  mkdirSync(project, { recursive: true })
  const refused = (r: Reply, what: string) => {
    assert.equal(r.status, 403, what)
    assert.equal(r.json.error.code, 'needs_phone', what)
  }
  // "Always", through a phone or from Lens paired on its own: refused before the approval is looked for.
  refused(await api('POST', '/v1/approvals/nope', PHONE, VIA, { decision: 'always' }), 'always via the phone')
  refused(await api('POST', '/v1/approvals/nope', LENSDEV, {}, { decision: 'always' }), 'always from Lens itself')
  // Allow is the glasses' own (this one was answered already); the phone may say Always.
  assert.equal((await api('POST', '/v1/approvals/nope', PHONE, VIA, { decision: 'allow' })).json.error.code, 'resolved')
  assert.equal((await api('POST', '/v1/approvals/nope', PHONE, {}, { decision: 'always' })).json.error.code, 'resolved')
  // Switching to Bypass, and starting a session in it.
  const id = '9a000000-0000-4000-8000-000000000001'
  hub.upsertSession(id, { cwd: project, origin: 'terminal' })
  refused(await api('POST', `/v1/sessions/${id}/mode`, PHONE, VIA, { permissionMode: 'bypassPermissions' }), 'bypass')
  refused(await api('POST', `/v1/sessions/${id}/mode`, LENSDEV, {}, { permissionMode: 'bypassPermissions' }), 'bypass from Lens itself')
  // Another mode goes on to its own answer (a terminal session that isn't the daemon's).
  assert.equal((await api('POST', `/v1/sessions/${id}/mode`, PHONE, VIA, { permissionMode: 'plan' })).json.error.code, 'terminal_mode')
  refused(await api('POST', '/v1/sessions', PHONE, VIA, { prompt: 'hi', projectPath: project, permissionMode: 'bypassPermissions' }), 'start in bypass')
  // An automation's No asking, and its shell steps: whatever the body holds, on a new one or over one.
  refused(await api('POST', '/v1/automations', PHONE, VIA, { name: 'x', confirmBypass: true }), 'no asking')
  refused(await api('POST', '/v1/automations', LENSDEV, {}, { name: 'x', confirmBypass: true }), 'no asking from Lens itself')
  const shell = { name: 'Tests', enabled: true, trigger: { kind: 'time', at: '07:00', days: [7] }, steps: [{ kind: 'shell', projectPath: project, command: 'npm test' }], confirmShell: true }
  refused(await api('POST', '/v1/automations', PHONE, VIA, shell), 'a shell step')
  refused(await api('POST', '/v1/automations', LENSDEV, {}, shell), 'a shell step from Lens itself')
  const made = await api('POST', '/v1/automations', PHONE, {}, shell)
  assert.equal(made.status, 200, 'the phone may')
  const auto = made.json.automation.id
  refused(await api('PUT', `/v1/automations/${auto}`, PHONE, VIA, { ...shell, name: 'Renamed' }), 'over a shell step')
  refused(await api('PUT', `/v1/automations/${auto}`, LENSDEV, {}, { ...shell, name: 'Renamed', confirmBypass: true, confirmShell: false }), 'No asking over it from Lens itself')
  // Without the flag the glasses can't save it either: the daemon asks for the fingerprint.
  const bare = await api('PUT', `/v1/automations/${auto}`, PHONE, VIA, { ...shell, name: 'Renamed', confirmShell: undefined })
  assert.deepEqual([bare.status, bare.json.error.code], [400, 'confirm_shell'])
  // An old client's {kind: "workflow"} keeps the steps, and still needs the fingerprint the glasses can't give.
  const kept = await api('PUT', `/v1/automations/${auto}`, PHONE, VIA, { name: 'Renamed', enabled: true, trigger: shell.trigger, action: { kind: 'workflow', steps: 1 } })
  assert.deepEqual([kept.status, kept.json.error.code], [400, 'confirm_shell'])
  assert.equal((await api('DELETE', `/v1/automations/${auto}`, PHONE, {})).status, 200)
  // Browse outside projects: never turned on from the glasses; turning it off is anyone's.
  refused(await api('POST', '/v1/mac/files', PHONE, VIA, { browseOutsideProjects: true }), 'browse on')
  const off = await api('POST', '/v1/mac/files', PHONE, VIA, { browseOutsideProjects: false })
  assert.equal(off.status, 200)
  assert.equal(off.json.files.browseOutsideProjects, false)
})

test('read aloud: the glasses’ own phone plays it, and only the glasses hear how it goes', async () => {
  const phone = new Feed('/v1/ws', PHONE)
  const phone2 = new Feed('/v1/ws', PHONE2)
  const glasses = new Feed('/v1/ws', PHONE, VIA)
  await Promise.all([phone.open(), phone2.open(), glasses.open()])
  phone.json({ t: 'speech.player', on: true })
  await sleep(60)
  // Another phone offered later: before the marker, the latest offer won.
  phone2.json({ t: 'speech.player', on: true })
  await sleep(80)
  const old = await api('POST', '/v1/speech/play', LENSDEV, {}, { text: 'Paired on its own.' })
  assert.equal(old.status, 200)
  assert.equal(old.json.play.to.id, 'phoneB')

  const r = await api('POST', '/v1/speech/play', PHONE, VIA, { text: 'Fixed the export.', sessionId: 's1' })
  assert.equal(r.status, 200)
  const id = r.json.play.id
  assert.equal(r.json.play.to.id, 'phoneA')
  assert.equal(r.json.play.source, 'glasses')
  await phone.wait((m) => m.t === 'speech.play' && m.play.id === id)
  await sleep(100)
  assert.ok(!glasses.msgs.some((m) => m.t === 'speech.play'), 'the play is the phone app’s to do, not the glasses’')
  phone.json({ t: 'speech.progress', id, state: 'started', ms: 0, level: 0.4 })
  const p = await glasses.wait((m) => m.t === 'speech.progress' && m.progress.id === id)
  assert.equal(p.progress.state, 'playing')
  await sleep(100)
  assert.ok(!phone.msgs.some((m) => m.t === 'speech.progress' && m.progress), 'the phone app isn’t told its own progress')
  // The glasses may stop what they asked for.
  const stopped = await api('POST', `/v1/speech/plays/${id}/stop`, PHONE, VIA, {})
  assert.equal(stopped.status, 200)
  await glasses.wait((m) => m.t === 'speech.progress' && m.progress.id === id && m.progress.state === 'stopped')
  for (const f of [phone, phone2, glasses]) f.close()
})

test('“Forget” on the Mac refuses the glasses until the phone allows them again', async () => {
  const phone = new Feed('/v1/ws', PHONE)
  const glasses = new Feed('/v1/ws', PHONE, VIA)
  await Promise.all([phone.open(), glasses.open()])
  const forgot = await mac('DELETE', '/local/devices/lens-phoneA')
  assert.equal(forgot.status, 200)
  assert.equal(forgot.json.ok, true)
  assert.equal(await glasses.waitClosed(), 4001)
  await phone.wait((m) => m.t === 'glasses.removed')
  assert.equal(phone.closed, null, 'the phone app stays connected')
  assert.deepEqual(await glassesListed(), [])
  assert.ok(getDevice('phoneA'), 'the phone is still paired')
  assert.equal(anyGlasses(), false)

  const refused = await api('GET', '/v1/snapshot', PHONE, VIA)
  assert.equal(refused.status, 403)
  assert.equal(refused.json.error.code, 'glasses_removed')
  await assert.rejects(new Feed('/v1/ws', PHONE, VIA).open(), /403/)
  // The phone app itself goes on as before.
  assert.equal((await api('GET', '/v1/snapshot', PHONE)).status, 200)
  // Forgetting them twice changes nothing.
  assert.equal((await mac('DELETE', '/local/devices/lens-phoneA')).json.ok, false)

  // The owner allows Lens on the phone again: the phone app says so.
  const shows = macFeed.msgs.filter((m) => m.t === 'guide.show').length
  const allowed = await api('PUT', '/v1/devices/me/glasses', PHONE, {}, { appVersion: '0.1.8' })
  assert.equal(allowed.status, 200)
  assert.equal(allowed.json.glasses.appVersion, '0.1.8')
  assert.equal((await api('GET', '/v1/snapshot', PHONE, VIA)).status, 200)
  assert.equal((await glassesListed()).length, 1)
  await macFeed.wait((m) => m.t === 'guide.show' && macFeed.msgs.filter((x) => x.t === 'guide.show').length > shows)
  phone.close()
})

test('the phone disconnecting its last Lens drops the glasses; a finished guide doesn’t open again', async () => {
  const gone = await api('DELETE', '/v1/devices/me/glasses', PHONE)
  assert.equal(gone.json.ok, true)
  assert.deepEqual(await glassesListed(), [])
  setGuide({ done: true })
  const shows = macFeed.msgs.filter((m) => m.t === 'guide.show').length
  // They come back by themselves (no removal to undo), listed again, without the guide.
  assert.equal((await api('GET', '/v1/snapshot', PHONE, VIA)).status, 200)
  assert.equal((await glassesListed()).length, 1)
  await sleep(100)
  assert.equal(macFeed.msgs.filter((m) => m.t === 'guide.show').length, shows)
})

test('forgetting the phone forgets its glasses too', async () => {
  assert.equal((await mac('DELETE', '/local/devices/phoneA')).json.ok, true)
  assert.deepEqual(await glassesListed(), [])
  assert.equal((await api('GET', '/v1/snapshot', PHONE, VIA)).status, 401)
  // An id that only looks like glasses is an unknown device.
  assert.equal((await mac('DELETE', '/local/devices/lens-phoneB')).json.ok, false)
  assert.ok(getDevice('phoneB'))
})
