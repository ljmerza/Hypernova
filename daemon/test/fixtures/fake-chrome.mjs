// A stand-in for headless Chrome in the "show me" tests: it speaks just enough of the DevTools
// protocol over the pipe (`--remote-debugging-pipe`: fds 3 and 4, NUL-ended JSON) to be driven
// like the real one, notes each address it's sent to, and answers with a plain picture of the
// size asked for. FAKE_CHROME_MODE: "ok", "refused" (the page doesn't load), "garbage" (no picture).
// FAKE_CHROME_REDIRECT: the page redirects there, which the daemon is asked about (Fetch) like any
// main-frame request. FAKE_CHROME_CALLS: a file that gets every method called, with its params.
// FAKE_CHROME_FRAME=none: the paused requests name no frame.
import { Socket } from 'node:net'
import { appendFileSync } from 'node:fs'
import { crc32, deflateSync } from 'node:zlib'

const mode = process.env.FAKE_CHROME_MODE ?? 'ok'
const delay = Number(process.env.FAKE_CHROME_DELAY ?? 0)
const input = new Socket({ fd: 3, readable: true, writable: false })
const output = new Socket({ fd: 4, readable: false, writable: true })
let size = { width: 800, height: 600 }

/** A real PNG of one colour (the daemon only reads its header, but a viewer can open it). */
function png(width, height) {
  const chunk = (type, data) => {
    const len = Buffer.alloc(4)
    len.writeUInt32BE(data.length)
    const body = Buffer.concat([Buffer.from(type, 'latin1'), data])
    const crc = Buffer.alloc(4)
    crc.writeUInt32BE(crc32(body))
    return Buffer.concat([len, body, crc])
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 2 // RGB
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(width * 3, 0x7f)])
  const raw = Buffer.concat(Array.from({ length: height }, () => row))
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}

const send = (m) => output.write(JSON.stringify(m) + '\0')
const answer = (id, result) => send({ id, result })

/** Once the daemon has enabled Fetch for documents, each one waits for its answer: go on, or fail. */
let intercepting = false
const paused = new Map()
let jobs = 0
function pause(url, redirectedRequestId) {
  if (!intercepting) return Promise.resolve('Fetch.continueRequest')
  const requestId = `job-${++jobs}`
  return new Promise((resolve) => {
    paused.set(requestId, resolve)
    // The main frame has the target's ID, as in Chrome.
    const frame = process.env.FAKE_CHROME_FRAME === 'none' ? {} : { frameId: 'T1' }
    send({ method: 'Fetch.requestPaused', sessionId: 'S1', params: { requestId, request: { url, method: 'GET' }, ...frame, resourceType: 'Document', ...(redirectedRequestId ? { redirectedRequestId } : {}) } })
  })
}

async function handle(m) {
  if (process.env.FAKE_CHROME_CALLS) appendFileSync(process.env.FAKE_CHROME_CALLS, `${m.method} ${JSON.stringify(m.params ?? {})}\n`)
  switch (m.method) {
    case 'Target.createBrowserContext':
      return answer(m.id, { browserContextId: 'C1' })
    case 'Target.createTarget':
      return answer(m.id, { targetId: 'T1' })
    case 'Target.attachToTarget':
      return answer(m.id, { sessionId: 'S1' })
    case 'Emulation.setDeviceMetricsOverride':
      size = { width: m.params.width, height: m.params.height }
      return answer(m.id, {})
    case 'Fetch.enable':
      intercepting = (m.params?.patterns ?? []).some((p) => p.resourceType === 'Document')
      return answer(m.id, {})
    case 'Fetch.continueRequest':
    case 'Fetch.failRequest':
      paused.get(m.params.requestId)?.(m.method)
      paused.delete(m.params.requestId)
      return answer(m.id, {})
    case 'Page.navigate': {
      if (process.env.FAKE_CHROME_LOG) appendFileSync(process.env.FAKE_CHROME_LOG, m.params.url + '\n')
      if (mode === 'refused') return answer(m.id, { frameId: 'T1', errorText: 'net::ERR_CONNECTION_REFUSED' })
      let url = m.params.url
      if ((await pause(url)) === 'Fetch.failRequest') return answer(m.id, { frameId: 'T1', errorText: 'net::ERR_BLOCKED_BY_CLIENT' })
      const to = process.env.FAKE_CHROME_REDIRECT
      if (to) {
        if ((await pause(to, `job-${jobs}`)) === 'Fetch.failRequest') return answer(m.id, { frameId: 'T1', errorText: 'net::ERR_BLOCKED_BY_CLIENT' })
        url = to
      }
      answer(m.id, { frameId: 'T1' })
      send({ method: 'Page.frameNavigated', sessionId: 'S1', params: { frame: { id: 'T1', url } } })
      return send({ method: 'Page.loadEventFired', sessionId: 'S1', params: { timestamp: 1 } })
    }
    case 'Runtime.evaluate':
      return answer(m.id, { result: { type: 'string', value: process.env.FAKE_CHROME_TITLE ?? '' } })
    case 'Page.captureScreenshot':
      if (delay) await new Promise((r) => setTimeout(r, delay))
      return answer(m.id, { data: (mode === 'garbage' ? Buffer.from('not a picture') : png(size.width, size.height)).toString('base64') })
    case 'Browser.close':
      answer(m.id, {})
      return setTimeout(() => process.exit(0), 10)
    default:
      return answer(m.id, {})
  }
}

let rest = ''
input.on('data', (d) => {
  rest += d.toString('utf8')
  for (let i = rest.indexOf('\0'); i >= 0; i = rest.indexOf('\0')) {
    const m = JSON.parse(rest.slice(0, i))
    rest = rest.slice(i + 1)
    void handle(m)
  }
})
input.on('end', () => process.exit(0))
