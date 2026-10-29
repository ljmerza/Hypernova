// Two HTTP servers on loopback: the phone API (exposed through `tailscale serve`) and the
// local API for the Mac app, Claude Code hooks and the testing checklist.
import { listCommands } from './commands.ts'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { existsSync, statSync, readdirSync, readFileSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { WebSocketServer } from 'ws'
import { createHash } from 'node:crypto'
import { API_PORT, LOCAL_PORT, PROTOCOL, SUPPORT, VERSION, claudeCached, claudeVersion, findClaude, log, macName, randomToken, readJson, sha256, safeEqual, tail } from './util.ts'
import type { Ev } from './normalize.ts'
import { companionUrl } from './speech.ts'
import { speechFeed, speechRoute, speechState } from './readaloud.ts'
import { screenshot, shotPath } from './shots.ts'
import { telescope } from './telescope/index.ts'
import { windowAnswer } from './telescope/window.ts'
import { adminToken, addDevice, deviceForToken, deleteProvider, editProvider, cleanBaseUrl, cleanKey, findProvider, getDevice, hasDeviceCert, identity, isSdk, listDevices, listProviders, removeDevice, setDeviceCert, settings, getManaged, saveManaged, saveSettings, setManagedProvider, OPENROUTER_URL, type ProviderSaved, getProvider } from './store.ts'
import { lanDevice, lanInfo, lanNamed, lanNamedHost, pairAllowed, pairFailed, pickLanAddress, plainIp, setLanAccess, startLan, stopLan } from './lan.ts'
import { nameKick, nameStatus, namedOrigin, releaseName, setNameService, setNameTermsShown, wantName } from './names.ts'
import { allowGlasses, dropGlasses, glassesEntries, glassesSeen, isGlassesFeed, lensWho, phoneOfGlassesId, removeGlasses, trackGlasses, viaLens } from './glasses.ts'
import { TlsError, issueDeviceCert, serverIdentity } from './tls.ts'
import { recheckSystem, setBuiltInTailscale, setControlUrl, tailnetHints, tailnetNow, tsnetBaseUrl, tsnetLogin, tsnetLogout, tsnetState } from './tsnet.ts'
import { addClient, agentJobOf, answerApproval, broadcast, clearRestarting, clientCount, getSession, getUsage, isLiveTerminal, isRestarting, listSessions, markRestarting, pendingApprovals, upsertSession, ensureParsed } from './hub.ts'
import { createSession, interrupt, isRunning, prepareFork, sendPrompt, setMode } from './runner.ts'
import { answerMenu, attachAgentsTerm, createAgentsSession, createSharedSession, endTermAndWait, openCodexTerminal, openOnMac, pressKeys, redeemTicket, relayPhone, sharedTerminalsAvailable, switchTermMode, termChildPid, terminalTicket, termOf, termScreen, typeInto } from './terms.ts'
import { codexBinary, setCodexEnabled } from './codex/client.ts'
import { codexModels, codexSessionUsage, codexState, refreshCodexAccount } from './codex/account.ts'
import { isCodex, isCodexMode } from './codex/threads.ts'
import { createCodexSession, ensureCodexHistory, interruptCodex, promptCodex, setCodexMode, setCodexModel } from './codex/sessions.ts'
import { SwitchError, commitSwitch, previewSwitch, useShared } from './switch.ts'
import { linkOf } from './switch-links.ts'
import { boxLines, boxText } from './term/shared.ts'
import { cancelLogin, clearProviderStatus, getAccount, noteProviderRemoved, providerKeyRefused, requestCheck, sendLoginCode, startLogin } from './account.ts'
import { handleHook, hookPathOk, installIntegration, integrationStatus, uninstallIntegration } from './hooks.ts'
import { createPreview, createProject, enableServe, listProjects, publicBaseUrl, sendFile, servePreview, tailscaleStatus } from './services.ts'
import { DOWNLOAD_MAX, allowedPath, fileFor, filesState, folderOpens, listDir, sendChecked, setBrowseOutside } from './files.ts'
import { qaPage, qaSave, qaShot, qaShotPath, qaState } from './qa.ts'
import { ATTACH_DIR, MAX_ATTACHMENT, attachmentFile, saveAttachment, withAttachments } from './attachments.ts'
import { agentEvents, endSessionAgents, listAgents, validAgentId } from './agents.ts'
import { getStats, noteApproval, notePrompt, parseRange } from './stats.ts'
import { getBattery } from './battery.ts'
import { refreshUsage } from './usage-probe.ts'
import { awakeClient, awakeState, setAwake } from './awake.ts'
import { sttApi, sttState, sttUpgrade } from './stt.ts'
import { guideApi, guideState, lensPaired, macFeed } from './guide.ts'
import { createNebulaProject, nebulaApi } from './nebula.ts'
import { notesApi } from './notes.ts'
import { ChangeError, changesOf, diffOf } from './changes.ts'
import { toolPath } from './normalize.ts'
import { ResumeError, setAutoContinue, typeContinue } from './resume.ts'
import { CONTINUE_TEXT } from './blockers.ts'
import { checkBalance, hasBalance, providerSaved } from './balance.ts'
import { checkProvider, type ProviderCheck } from './providers-check.ts'
import { listModels } from './models.ts'
import { sessionUsage } from './session-usage.ts'
import { todosOf } from './todos.ts'
import { RewindError, rewindTo } from './rewind.ts'
import { SdkMissingError, claudeSdk, sdkState } from './sdk.ts'
import { AutomationError, FIRE_BODY_MAX, automations, lensConnected, useSessionOps } from './automations.ts'
import { systemZone } from './schedule.ts'
import { PLATFORM, RELEASES, UNAVAILABLE, bypassBlockedByRoot, has, unavailable } from './platform.ts'

/**
 * `lan`: came in on the LAN listener (lan.ts), where the admin token counts for nothing and pairing
 * is limited. `lens`: the glasses, through the phone app whose token this is (glasses.ts).
 */
type Ctx = { req: IncomingMessage; res: ServerResponse; url: URL; local: boolean; deviceId: string | null; lan: boolean; lens?: boolean }
class HttpError extends Error {
  status: number
  code: string
  /** More fields for the error object (say, the text that's in the way). */
  extra?: Record<string, unknown>
  constructor(status: number, code: string, message: string, extra?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.code = code
    this.extra = extra
  }
}

/**
 * The glasses: through a phone (`X-Hypernova-Via: lens`, glasses.ts) or paired as Lens in a private
 * build. They can't check a fingerprint, so what the phone guards with one is the phone's alone
 * (docs/PROTOCOL.md, "Only on the phone"): "Always", Bypass (a switch, a session started in it, an
 * automation's No asking) and Browse outside projects turned on.
 */
const fromGlasses = (c: Ctx): boolean => !!c.lens || (!!c.deviceId && getDevice(c.deviceId)?.client === 'lens')
const phoneOnly = () => new HttpError(403, 'needs_phone', 'Do this on your phone: it asks for your fingerprint or screen lock first.')
/** What only Claude Code sessions do (rewind, providers, auto-continue, terminal menus): docs/PROTOCOL.md, "Codex". */
const notForCodex = () => new HttpError(409, 'unsupported_for_agent', 'Codex sessions can’t do this.')
/**
 * Before a prompt goes to a session the daemon runs itself: Claude Code's SDK must be here (sdk.ts),
 * else 409 `sdk_missing`, said before anything is made or queued.
 */
async function needSdk(): Promise<void> {
  try {
    await claudeSdk()
  } catch (e) {
    if (e instanceof SdkMissingError) throw new HttpError(e.status, e.code, e.message)
    throw e
  }
}

function json(res: ServerResponse, status: number, body: unknown): void {
  const s = JSON.stringify(body)
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(s)
}

async function readBody(req: IncomingMessage, limit = 2 * 1024 * 1024): Promise<Buffer> {
  const chunks: Buffer[] = []
  let size = 0
  for await (const c of req) {
    size += c.length
    if (size > limit) throw new HttpError(413, 'too_large', 'That is too large.')
    chunks.push(c as Buffer)
  }
  return Buffer.concat(chunks)
}
/** A webhook trigger's payload: text, 64 KB at most (413 `too_large`), UTF-8 (415 `not_text`). */
async function readPayload(req: IncomingMessage): Promise<string> {
  const b = await readBody(req, FIRE_BODY_MAX)
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(b)
  } catch {
    throw new HttpError(415, 'not_text', 'Send the payload as text (UTF-8).')
  }
}
/**
 * An automation's webhook trigger fired, by a paired device (null) or with its fire token on
 * 127.0.0.1. A fire token is checked before the body is read, and a wrong one is 401 whether or
 * not the automation exists (no way to tell which ids do without a token).
 */
async function fireAutomation(res: ServerResponse, req: IncomingMessage, id: string, token: string | null): Promise<void> {
  try {
    if (token != null) automations().fireAuth(id, token)
    const payload = await readPayload(req)
    return json(res, 200, { run: await automations().fireWebhook(id, payload, token) })
  } catch (e) {
    if (e instanceof AutomationError) throw new HttpError(e.status, e.code, e.message, e.extra)
    throw e
  }
}
async function readJsonBody(req: IncomingMessage): Promise<any> {
  const b = await readBody(req)
  if (!b.length) return {}
  try {
    return JSON.parse(b.toString('utf8'))
  } catch {
    throw new HttpError(400, 'bad_json', 'That request was not valid JSON.')
  }
}

/**
 * A session's events as asked: the last `limit`; with `turn=last`, only from its last prompt on;
 * with `output=<n>`, tool outputs cut to their last n characters (the glasses app reads the last
 * turn over a phone connection, and a build log's head is rarely what it shows).
 */
function eventsView(rec: Parameters<typeof ensureParsed>[0], q: URLSearchParams): Ev[] {
  const all = ensureParsed(rec).events
  let from = 0
  if (q.get('turn') === 'last') {
    for (let i = all.length - 1; i >= 0; i--) if (all[i]!.kind === 'user') {
      from = i
      break
    }
  }
  let events = all.slice(from).slice(-Number(q.get('limit') ?? 300))
  const cut = Number(q.get('output'))
  if (q.has('output') && Number.isFinite(cut)) {
    const n = Math.min(64 * 1024, Math.max(256, Math.round(cut)))
    events = events.map((e) => (e.kind === 'tool' && e.output && e.output.length > n ? { ...e, output: tail(e.output, n) } : e))
  }
  return events
}

/** The files a prompt carries (paths from POST /v1/attachments), checked to be ours. */
function attachments(b: any): string[] {
  if (b?.attachments == null) return []
  if (!Array.isArray(b.attachments) || b.attachments.length > 10) throw new HttpError(400, 'bad_attachments', 'Attach up to 10 files.')
  return b.attachments.map((p: unknown) => {
    // The path as uploaded (not its real path), so the chat recognizes it in the transcript.
    const path = typeof p === 'string' ? resolve(p) : ''
    if (!path.startsWith(ATTACH_DIR + '/') || !attachmentFile(path)) throw new HttpError(410, 'attachment_gone', 'An attached file is no longer on the Mac. Attach it again.')
    return path
  })
}

// ── Pairing codes (single use, 10 minutes) ───────────────────────────────
const pairCodes = new Map<string, number>()
/**
 * A pairing code. [forName]: it's shown where the name is used (Setup's phone step, `hypernova
 * pair`), so it counts for the name until it expires (names.ts); the menu bar's code doesn't.
 */
/**
 * A code the Mac stopped showing (the Pair page closed, or a fresh code replaced it): it stops
 * pairing at once instead of at its expiry. With no code, every live code. Returns how many ended.
 */
export function cancelPairing(code?: string | null): number {
  if (code) return pairCodes.delete(sha256(String(code))) ? 1 : 0
  const n = pairCodes.size
  pairCodes.clear()
  return n
}

export async function startPairing(dev = false, forName = false) {
  const code = randomToken(16)
  const expiresAt = Date.now() + 10 * 60_000
  pairCodes.set(sha256(code), expiresAt)
  // A code on screen until it expires, where its download link uses the name: published meanwhile.
  if (!dev && forName) wantName(expiresAt)
  const base = dev ? `http://10.0.2.2:${API_PORT}` : (await publicBaseUrl()) ?? `http://10.0.2.2:${API_PORT}`
  // The local route too (lan.ts): the phone pairs over Wi-Fi with the certificate pinned when the
  // base URL is out of reach, and the pairing page hands these on to the app unchanged.
  // `fp` whenever the daemon has its certificate: the built-in Tailscale route pins it too when the
  // tailnet has no HTTPS certificates (tsnet.ts); `lan` and `lp` only while the listener is on.
  const lan = lanInfo()
  const fp = lan?.fingerprint ?? serverIdentity()?.fingerprint
  const lanQuery = (fp ? `&fp=${fp}` : '') + (lan ? `&lan=${lan.addresses.map(encodeURIComponent).join(',')}&lp=${lan.port}` : '')
  // And the tailnet (tsnet.ts): the computer's name and address there, the account it signed in
  // with (the phone hints "sign in as you@…") and the control server when it isn't tailscale.com.
  const hints = await tailnetHints()
  // The account (`ta`) not in the web link: that page is opened in a browser, whose history keeps it.
  const tsQuery = (account: boolean) => (hints.name ? `&ts=${encodeURIComponent(hints.name)}${hints.ip ? `&ti=${hints.ip}` : ''}${account && hints.account ? `&ta=${encodeURIComponent(hints.account)}` : ''}${hints.control ? `&tc=${encodeURIComponent(hints.control)}` : ''}` : '')
  const params = `v=1&u=${encodeURIComponent(base)}&c=${code}&n=${encodeURIComponent(macName())}${lanQuery}${tsQuery(true)}`
  // The app's own link, for Paste link and for adb; and the QR code's: the same parameters after the
  // `#` of https://hypernova.observer/pair. Phone cameras open https links, Android opens this one
  // straight in the app (App Links: names/src/links.ts serves the domain's assetlinks.json), and
  // without the app a browser shows a page there that hands over to it or says how to get it. The
  // fragment never leaves the phone: nothing of it reaches that site. Unlike a page on this
  // computer, it opens before the phone can reach the computer at all (no tailnet yet).
  const appLink = `hypernova://pair?${params}`
  const link = `${PAIR_LINK}#${params}`
  // This computer's own pairing page (its name's on the local network while it has one: names.ts),
  // for links made before; the QR code no longer carries it.
  const builtIn = !dev && base === tsnetBaseUrl()
  const pageBase = (builtIn && namedOrigin()) || base
  const webLink = `${pageBase}/pair?c=${code}&n=${encodeURIComponent(macName())}${lanQuery}${tsQuery(false)}`
  return { code, link, appLink, webLink, expiresAt, url: base, download: downloadUrl(base), tailnet: { name: hints.name, ip: hints.ip, control: hints.control } }
}

/**
 * Where the pairing QR code points (docs/PROTOCOL.md, "Pairing"): the app's verified https link
 * (android/…/AndroidManifest.xml, `autoVerify`), with the pairing details in its fragment.
 */
export const PAIR_LINK = 'https://hypernova.observer/pair'
/**
 * Where a phone gets the app: this computer's name on the local network whenever it works
 * (names.ts): a brand-new phone on the same Wi-Fi opens that page with a real certificate, with no
 * app and no Tailscale yet. Otherwise the base URL's download page when a phone can open it (the
 * Tailscale app's serve, or a public address), or, with the built-in node serving or no tailnet at
 * all, the local network's listener at its Wi-Fi address (the browser warns about this Mac's own
 * certificate once).
 */
export function downloadUrl(base: string): string {
  return downloadTarget(base).url
}
/**
 * The download URL; `local`: this daemon's own LAN listener (bundled APK), as opposed to a published
 * release or the base URL's page; `trusted`: a browser can check the page's certificate (false only
 * for the listener's own address, whose certificate is self-signed); `anywhere`: with the link on
 * the local network's name, the tailnet's page too, for a phone that has Tailscale (null when
 * there's none a browser could check).
 */
export function downloadTarget(base: string): { url: string; local: boolean; trusted: boolean; anywhere: string | null } {
  // A published build (a release URL the owner sets) beats anything of ours: it needs no certificate warning.
  if (process.env.HYPERNOVA_APK_URL) return { url: process.env.HYPERNOVA_APK_URL, local: false, trusted: true, anywhere: null }
  const builtIn = base === tsnetBaseUrl()
  const named = namedOrigin()
  if (named) {
    // The tailnet's page has a certificate a browser checks when the Tailscale app serves it, or the
    // built-in node has the tailnet's own; with the node's pinned one it would warn.
    const tailnetChecked = /^https:\/\//.test(base) && (!builtIn || tsnetState().helper?.tls === 'tailscale')
    return { url: `${named}/download/android`, local: true, trusted: true, anywhere: tailnetChecked ? `${base}/download/android` : null }
  }
  const lan = lanInfo()
  // The listener too with no tailnet yet (Tailscale left for later): the base is then the emulator's
  // plain-http address, which no phone on the Wi-Fi can open.
  const a = (builtIn || !base.startsWith('https://')) && lan ? pickLanAddress(lan.addresses) : null
  if (a) return { url: `https://${a.includes(':') ? `[${a}]` : a}:${lan!.port}/download/android`, local: true, trusted: false, anywhere: null }
  // The tailnet's or a public address's page; a plain-http one (no tailnet: the emulator's address) can't be checked.
  return { url: `${base}/download/android`, local: false, trusted: base.startsWith('https://'), anywhere: null }
}
/** Whether [code] is a pairing code being shown now (unused, not expired). */
function liveCode(code: string): boolean {
  pairingOpen() // forgets expired codes
  return !!code && pairCodes.has(sha256(code))
}
/** Any pairing code still live: when none is, the LAN listener's /v1/pair has nothing to compare and says so. */
function pairingOpen(): boolean {
  const now = Date.now()
  for (const [k, exp] of pairCodes) if (exp < now) pairCodes.delete(k)
  return pairCodes.size > 0
}
/**
 * What the pairing page's app link carries besides the code: the daemon's own listener and
 * tailnet hints, read here and now (never relayed from the page's query, which anyone can write),
 * and only for a code that is live: without one the page says nothing about this computer (the
 * account, the tailnet name, the control server), so the page alone leaks nothing.
 */
async function pairPageExtras(code: string): Promise<string> {
  pairingOpen() // forgets expired codes
  if (!code || !pairCodes.has(sha256(code))) return ''
  const lan = lanInfo()
  const fp = lan?.fingerprint ?? serverIdentity()?.fingerprint
  let out = (fp ? `&fp=${fp}` : '') + (lan ? `&lan=${lan.addresses.map(encodeURIComponent).join(',')}&lp=${lan.port}` : '')
  const hints = await tailnetHints()
  if (hints.name) out += `&ts=${encodeURIComponent(hints.name)}${hints.ip ? `&ti=${hints.ip}` : ''}${hints.account ? `&ta=${encodeURIComponent(hints.account)}` : ''}${hints.control ? `&tc=${encodeURIComponent(hints.control)}` : ''}`
  return out
}

// ── Snapshot ─────────────────────────────────────────────────────────────
/** The LAN listener as this device sees it: with whether it holds a device certificate that hasn't run out (never the Mac app). */
function lanSnapshot(deviceId: string | null) {
  const lan = lanInfo()
  return lan && { ...lan, deviceCert: !!deviceId && hasDeviceCert(getDevice(deviceId)) }
}
/**
 * Claude Code is on this computer: found where it's usually installed, or its hooks are in (its
 * own lookup can miss a Claude Code only an interactive shell finds). Absence is what makes Codex
 * lead, so it must be sure. Looked at again at most once a minute.
 */
let claudeSeen: { at: number; here: boolean } | null = null
export function claudeHere(): boolean {
  if (!claudeSeen || Date.now() - claudeSeen.at > 60_000) {
    let here = !!claudeCached()
    if (!here) {
      try {
        here = integrationStatus().hooks.installed
      } catch {}
    }
    claudeSeen = { at: Date.now(), here }
  }
  return claudeSeen.here
}

function snapshot(deviceId: string | null) {
  return {
    mac: {
      id: identity.id, name: macName(), daemonVersion: VERSION, claudeVersion: claudeVersion(), projectsRoot: settings.projectsRoot, app: appInfo(), battery: getBattery(), awake: awakeState(), tz: systemZone(),
      // Settings → Browse outside projects (files.ts).
      files: filesState(),
      // Where the phone API is on the tailnet now (tsnet.ts): a phone paired before the computer
      // switched between the Tailscale app and the built-in node follows it.
      tailnet: tailnetNow(),
      // The server's system, and the Mac features it lacks with the reason to show (platform.ts):
      // empty on a Mac; on Linux the battery, Stay awake, the Mac's voice, windows, the Simulator.
      platform: PLATFORM, unavailable: UNAVAILABLE,
    },
    usage: getUsage(),
    sessions: listSessions(),
    approvals: pendingApprovals(),
    projects: listProjects(),
    providers: listProviders(),
    account: getAccount(),
    // Speech to text (stt.ts) and the Lens guide's place (guide.ts).
    stt: sttState(),
    // Read aloud: Soniox's voices or the Mac's, and whether a phone plays the glasses' speech (readaloud.ts).
    speech: speechState(),
    guide: guideState(),
    // "When … → Do …" on the Mac (automations.ts).
    automations: automations().snapshot(),
    // The local route (lan.ts): null while the listener is off.
    lan: lanSnapshot(deviceId),
    // The agents (docs/PROTOCOL.md, "Codex"): Claude Code here or not, and Codex (null: not installed).
    // `sdk`: Claude Code's SDK is here (sdk.ts); without it what the daemon runs through it answers `sdk_missing`.
    claude: { available: claudeHere(), version: claudeVersion(), sdk: sdkState().installed },
    codex: codexState(),
  }
}

function baseUrlOf(req: IncomingMessage): string {
  const host = req.headers['x-forwarded-host'] ?? req.headers.host ?? `127.0.0.1:${API_PORT}`
  // The LAN listener speaks TLS itself; tailscale serve ends it before the daemon.
  const tls = !!(req.socket as { encrypted?: boolean }).encrypted
  const proto = req.headers['x-forwarded-proto'] ?? (tls || req.headers['tailscale-user-login'] || String(host).includes('.ts.net') ? 'https' : 'http')
  return `${proto}://${host}`
}

const QA_KEY = sha256('qa:' + adminToken).slice(0, 32)

// ── The phone API (also usable locally with the admin token) ────────────
async function api(c: Ctx): Promise<void> {
  const { req, res, url } = c
  const path = url.pathname
  const m = req.method ?? 'GET'

  if (path === '/v1/health') return json(res, 200, { ok: true, protocol: PROTOCOL, name: macName() })
  if (path === '/v1/pair' && m === 'POST') {
    const b = (await readJsonBody(req)) ?? {}
    const source = plainIp(req.socket.remoteAddress)
    if (c.lan) {
      // Anyone on the Wi-Fi can reach this: closed with no code to compare, and few tries each (lan.ts).
      if (!pairingOpen()) throw new HttpError(403, 'pairing_closed', 'No pairing code is being shown on the Mac.')
      const gate = pairAllowed(source)
      if (!gate.ok) throw new HttpError(429, 'too_many_tries', 'Too many wrong codes from this device. Try again later.', { retryAfterSeconds: gate.retryAfterSeconds })
    }
    for (const [k, exp] of pairCodes) if (exp < Date.now()) pairCodes.delete(k)
    const h = sha256(String(b.code ?? ''))
    const exp = pairCodes.get(h)
    if (!exp || exp < Date.now()) {
      if (c.lan) pairFailed(source)
      throw new HttpError(403, 'code_expired', 'This pairing code has expired or was already used. Show a new one on your Mac.')
    }
    pairCodes.delete(h)
    const token = randomToken(32)
    const deviceId = randomToken(8)
    // Which app paired ("lens": the glasses app), so the Mac's device list can say so.
    const client = typeof b.client === 'string' && /^[a-z][a-z0-9-]{0,23}$/.test(b.client) ? b.client : undefined
    const appVersion = typeof b.appVersion === 'string' ? b.appVersion.slice(0, 40) : undefined
    // The same install of the app pairing again (its token lost, the app's data cleared, a new
    // code scanned): it takes its old place rather than add a twin to the device list. Its
    // glasses stay allowed; its old token and feeds end.
    const installId = typeof b.installId === 'string' && /^[\w-]{8,64}$/.test(b.installId) ? b.installId : undefined
    const before = installId ? listDevices().filter((d) => d.installId === installId && (d.client ?? null) === (client ?? null)) : []
    for (const d of before) {
      removeDevice(d.id)
      dropDevice(d.id)
    }
    const glasses = before.find((d) => d.glasses)?.glasses
    addDevice({ id: deviceId, name: String(b.deviceName ?? 'Phone').slice(0, 60), model: String(b.model ?? '').slice(0, 60), createdAt: Date.now(), lastSeenAt: Date.now(), appVersion, ...(client ? { client } : {}), ...(installId ? { installId } : {}), ...(glasses ? { glasses } : {}) }, token)
    log('paired', b.deviceName, b.model, client ?? '', before.length ? `(in place of ${before.length})` : '')
    broadcast({ t: 'devices' })
    if (client === 'lens') lensPaired()
    return json(res, 200, { token, deviceId, mac: { id: identity.id, name: macName() } })
  }
  // A page first (a bare APK link just starts a download, which looks like nothing happened).
  // Through the name (names.ts) a browser can check the certificate, so the page has no warning.
  const viaName = c.lan && lanNamed(req.socket)
  if (path === '/download/android') return appPage(res, viaName)
  if (path === '/download/android/Hypernova.apk') return serveApk(req, res)
  if (path === '/pair' && m === 'GET') {
    const code = url.searchParams.get('c') ?? ''
    if (!viaName) return pairPage(res, baseUrlOf(req), code, url.searchParams.get('n') ?? macName(), await pairPageExtras(code))
    // Through the name, only for a code being shown on the Mac: the page is this computer's own
    // listener, and the app it hands over to pairs as from that code (the base URL, then the
    // pinned local addresses). Its own links go to the name, never to a host the request names.
    if (!liveCode(code)) throw new HttpError(404, 'not_found', 'Unknown endpoint.')
    const port = lanInfo()?.port
    const host = lanNamedHost()
    if (!port || !host) throw new HttpError(404, 'not_found', 'Unknown endpoint.')
    return pairPage(res, (await publicBaseUrl()) ?? `http://10.0.2.2:${API_PORT}`, code, url.searchParams.get('n') ?? macName(), await pairPageExtras(code), `https://${host}:${port}`)
  }
  if (path === '/qa' || path.startsWith('/qa/')) return qa(c, false)
  const p = /^\/p\/([\w-]+)\/?(.*)$/.exec(path)
  if (p) {
    if (servePreview(req, res, p[1]!, (p[2] ?? '') + url.search, true)) return
    throw new HttpError(404, 'preview_expired', 'This preview link has expired. Open it again from the app.')
  }
  // A screenshot ("show me"): its unguessable name is the key, as an <img> or a canvas can't send
  // the bearer header. Under /v1, so it has CORS, which a canvas needs to read its pixels.
  const shot = /^\/v1\/shots\/([^/]+)\.png$/.exec(path)
  if (shot && (m === 'GET' || m === 'HEAD')) {
    const file = shotPath(shot[1]!)
    if (!file) throw new HttpError(404, 'shot_expired', 'This screenshot has expired. Ask for a new one.')
    const bytes = readFileSync(file)
    res.writeHead(200, { 'content-type': 'image/png', 'content-length': bytes.length, 'cache-control': 'private, max-age=43200', 'x-content-type-options': 'nosniff' })
    return void res.end(m === 'HEAD' ? undefined : bytes)
  }

  // Everything below needs a paired device (or the Mac app's admin token).
  if (!c.local && !c.deviceId) {
    // Absolute asset paths requested by a previewed page carry the preview cookie.
    const ck = /(?:^|;\s*)hn_preview=([\w-]+)/.exec(req.headers.cookie ?? '')
    if (ck && !path.startsWith('/v1/') && servePreview(req, res, ck[1]!, path.slice(1) + url.search, false)) return
    throw new HttpError(401, 'unauthorized', 'This phone is not paired with this Mac.')
  }
  // The glasses through a phone (glasses.ts) may do what the glasses did as a device of their own,
  // but never manage the phone: forget it, take its certificate, or speak for its glasses.
  if (c.lens) {
    if (path.startsWith('/v1/devices/') || path.startsWith('/v1/device/')) throw new HttpError(403, 'not_for_glasses', 'The glasses can’t change this phone’s pairing.')
    const phone = getDevice(c.deviceId!)
    if (phone && glassesSeen(phone, req) === 'removed') throw new HttpError(403, 'glasses_removed', 'These glasses were removed on your Mac. Allow them again in Hypernova on your phone.')
  }

  if (path === '/v1/snapshot') return json(res, 200, snapshot(c.deviceId))
  // This phone's certificate for the LAN listener's mutual TLS (tls.ts), from its own key's request.
  if (path === '/v1/device/cert' && m === 'POST') {
    if (!c.deviceId) throw new HttpError(401, 'unauthorized', 'Only a paired phone can ask for a device certificate.')
    const b = await readJsonBody(req)
    try {
      const issued = issueDeviceCert(c.deviceId, String(b?.csr ?? ''))
      setDeviceCert(c.deviceId, issued.certFingerprint, issued.expiresAt)
      dropOldCert(c.deviceId, issued.certFingerprint)
      log('device certificate issued', c.deviceId)
      return json(res, 200, { cert: issued.cert.toString('base64'), ca: issued.ca.toString('base64'), fingerprint: issued.fingerprint })
    } catch (e) {
      if (e instanceof TlsError) throw new HttpError(e.code === 'no_tls' ? 503 : 400, e.code, e.message)
      throw e
    }
  }
  // Automations: the cards, their runs, Run now, Pause all (automations.ts).
  const fired = /^\/v1\/automations\/([\w-]+)\/fire$/.exec(path)
  if (fired && m === 'POST') return fireAutomation(res, req, fired[1]!, null)
  if (path === '/v1/automations' || path.startsWith('/v1/automations/')) {
    try {
      // No asking and shell steps need the fingerprint the glasses can't give.
      const body = () => readJsonBody(req).then((b) => {
        if ((b?.confirmBypass === true || b?.confirmShell === true) && fromGlasses(c)) throw phoneOnly()
        return b
      })
      // Who answers an ask step: the Mac app (its admin token), the glasses, or a phone.
      const who = c.local ? 'mac' : fromGlasses(c) ? 'glasses' : 'phone'
      return json(res, 200, await automations().api(path, m, url.searchParams, body, who))
    } catch (e) {
      if (e instanceof AutomationError) throw new HttpError(e.status, e.code, e.message, e.extra)
      throw e
    }
  }
  if (path === '/v1/usage') return json(res, 200, getUsage())
  // Codex (codex/account.ts): its state, a fresh look at its account and limits, its models.
  if (path === '/v1/codex' && m === 'GET') return json(res, 200, { codex: codexState() })
  // Codex on or off for Hypernova from the phone (Settings → Agents & providers); never the glasses.
  if (path === '/v1/codex' && m === 'POST') {
    const b = await readJsonBody(req)
    if (typeof b?.enabled !== 'boolean') throw new HttpError(400, 'bad_value', 'Say enabled: true or false.')
    if (fromGlasses(c)) throw new HttpError(403, 'needs_phone', 'Turn Codex on or off from your phone or the Mac.')
    if (b.enabled && !codexBinary().ok) throw new HttpError(409, 'no_codex', 'Codex isn’t installed on this computer, or it’s too old for Hypernova.')
    setCodexEnabled(b.enabled)
    return json(res, 200, { codex: codexState() })
  }
  if (path === '/v1/codex/refresh' && m === 'POST') return json(res, 200, { codex: await refreshCodexAccount() })
  if (path === '/v1/codex/models' && m === 'GET') return json(res, 200, { models: await codexModels(url.searchParams.get('fresh') === '1') })
  // A fresh look at the per-model weeks on Claude Code's /usage screen (usage-probe.ts): waits for it.
  if (path === '/v1/usage/refresh' && m === 'POST') return json(res, 200, { usage: await refreshUsage() })
  // Stay awake (awake.ts): While working, Backpack or Off, and the battery guard's level.
  if (path === '/v1/mac/awake' && m === 'GET') return json(res, 200, { awake: awakeState() })
  if (path === '/v1/mac/awake' && m === 'POST') {
    const r = setAwake((await readJsonBody(req)) ?? {})
    if (!r.ok) throw new HttpError(r.code === 'bad_mode' || r.code === 'bad_battery' ? 400 : r.code === 'unavailable' ? 501 : 409, r.code, r.message, r.code === 'unavailable' ? { feature: 'awake' } : undefined)
    return json(res, 200, { awake: r.awake })
  }
  // Browse outside projects (files.ts): sessions' folders in the home folder, read-only. The phone
  // asks for the fingerprint before it turns it on; the glasses can't.
  if (path === '/v1/mac/files' && m === 'GET') return json(res, 200, { files: filesState() })
  if (path === '/v1/mac/files' && m === 'POST') {
    const b = await readJsonBody(req)
    if (typeof b?.browseOutsideProjects !== 'boolean') throw new HttpError(400, 'bad_value', 'Say browseOutsideProjects: true or false.')
    if (b.browseOutsideProjects && fromGlasses(c)) throw phoneOnly()
    setBrowseOutside(b.browseOutsideProjects)
    return json(res, 200, { files: filesState() })
  }
  if (path === '/v1/stats' && m === 'GET') {
    // Statistics: how long Claude worked, tokens, sessions… (see stats.ts).
    const range = parseRange(url.searchParams.get('range') ?? 'week')
    if (!range) throw new HttpError(400, 'bad_range', 'Ask for today, week, month or all.')
    return json(res, 200, await getStats(range))
  }
  if (path === '/v1/projects' && m === 'GET') return json(res, 200, { projects: listProjects(), root: settings.projectsRoot })
  if (path === '/v1/projects' && m === 'POST') {
    const b = await readJsonBody(req)
    // `nebula: true`: a new project of its own in Nebula (nebula.ts), its name made unique.
    return json(res, 200, { project: b.nebula === true ? createNebulaProject(String(b.name ?? '')) : createProject(String(b.name ?? ''), b.git !== false) })
  }
  if (path === '/v1/providers' && m === 'GET') return json(res, 200, { providers: listProviders() })
  // The models each provider offers, for the new-session sheet (models.ts): Claude Code's own list,
  // OpenRouter's catalogue, the providers' settings.
  if (path === '/v1/models' && m === 'GET') return json(res, 200, { providers: await listModels(url.searchParams.get('provider')) })
  // Read aloud: Soniox's voices or the Mac's, whole clips for the glasses' own player, and plays the
  // phone app streams (readaloud.ts).
  // The glasses through a phone ask as themselves (glasses.ts): their plays go to that phone, and
  // its progress comes back to them, not to the phone app.
  const speaker = c.lens && c.deviceId ? lensWho(c.deviceId) : (c.deviceId ?? 'mac')
  if (path.startsWith('/v1/speech') && path !== '/v1/speech/companion' && (await speechRoute(path, m, req, res, speaker, () => readJsonBody(req)))) return
  if (path === '/v1/speech/companion' && m === 'GET') return json(res, 200, { url: await companionUrl() })
  // Speech to text through Soniox (stt.ts) and the Lens guide (guide.ts).
  if (path === '/v1/stt' || path.startsWith('/v1/stt/')) return json(res, 200, await sttApi(path, m, () => readJsonBody(req)))
  if (path === '/v1/guide' || path.startsWith('/v1/guide/')) return json(res, 200, await guideApi(path, m, () => readJsonBody(req)))
  // Nebula, where new sessions from the glasses are born (nebula.ts), and the notes said to them (notes.ts).
  if (path === '/v1/nebula') return json(res, 200, await nebulaApi(m, () => readJsonBody(req)))
  if (path === '/v1/nebula/notes') return json(res, 200, await notesApi(m, () => readJsonBody(req)))
  if (path === '/v1/commands' && m === 'GET') {
    // The "/" list: Claude Code's commands and skills in a project.
    const p = url.searchParams.get('project') ?? ''
    const full = (p && (allowedPath(p) ?? (existsSync(p) ? p : null))) || process.env.HOME || '/'
    return json(res, 200, { commands: await listCommands(full) })
  }
  if (path === '/v1/providers' && m === 'POST') {
    const b = await readJsonBody(req)
    const saved = editProvider(b ?? {})
    return json(res, 200, await afterSave(saved))
  }
  // An address and a key checked without saving them (providers-check.ts): the editors' Check.
  // [id]: an existing provider whose saved key is used when none is sent, only at its own host.
  if (path === '/v1/providers/check' && m === 'POST') {
    const b = (await readJsonBody(req)) ?? {}
    const kind = b.kind ?? (typeof b.id === 'string' ? findProvider(b.id)?.kind : undefined)
    if (kind !== 'openrouter' && kind !== 'custom') throw new HttpError(400, 'bad_kind', 'Choose OpenRouter or Custom.')
    const saved = typeof b.id === 'string' ? findProvider(b.id) : null
    if (typeof b.id === 'string' && !saved) throw new HttpError(404, 'no_provider', 'That provider isn’t set up on this Mac.')
    const raw = typeof b.baseUrl === 'string' && b.baseUrl.trim() ? b.baseUrl : (saved?.baseUrl ?? (kind === 'openrouter' ? OPENROUTER_URL : null))
    if (!raw) throw new HttpError(400, 'missing_url', 'A custom provider needs a base URL.')
    const baseUrl = cleanBaseUrl(raw)
    const host = (u: string | null | undefined) => {
      try {
        return u ? new URL(u).host.toLowerCase() : null
      } catch {
        return null
      }
    }
    const sameHost = !!saved?.baseUrl && host(saved.baseUrl) === host(baseUrl)
    const apiKey = cleanKey(b.apiKey) ?? (sameHost ? saved!.apiKey : null)
    return json(res, 200, { check: await checkProvider({ kind, name: typeof b.name === 'string' ? b.name : saved?.name, baseUrl, apiKey }) })
  }
  // What's left on a provider's key, read now (balance.ts): Settings opening, the Check button.
  const check = /^\/v1\/providers\/([\w-]+)\/check$/.exec(path)
  if (check && m === 'POST') {
    if (!hasBalance(findProvider(check[1]!))) throw new HttpError(409, 'no_balance', 'This provider has no balance to read.')
    await checkBalance(check[1]!, true)
    return json(res, 200, { provider: listProviders().find((x) => x.id === check[1]) })
  }
  const prov = /^\/v1\/providers\/([\w-]+)$/.exec(path)
  if (prov && m === 'PUT') {
    const b = await readJsonBody(req)
    const saved = editProvider(b ?? {}, prov[1])
    // A new key, address or models: whatever went wrong with the old ones is for the next session to say.
    clearProviderStatus(prov[1]!)
    return json(res, 200, await afterSave(saved))
  }
  if (prov && m === 'DELETE') {
    if (!findProvider(prov[1]!)) throw new HttpError(404, 'no_provider', 'That provider isn’t set up on this Mac.')
    if (!deleteProvider(prov[1]!)) throw new HttpError(400, 'cannot_delete', 'This provider can’t be deleted.')
    clearProviderStatus(prov[1]!)
    // Sessions that would start on it again (SDK turns, resumes) now say it was removed.
    noteProviderRemoved(prov[1]!)
    void providerSaved(prov[1]!, false)
    broadcast({ t: 'providers', providers: listProviders() })
    return json(res, 200, { ok: true })
  }
  // The Claude account (docs/PROTOCOL.md, "Account"): check it now, sign in again from the phone.
  if (path === '/v1/account/check' && m === 'POST') return json(res, 200, { account: await requestCheck() })
  if (path === '/v1/account/login' && m === 'POST') {
    const b = await readJsonBody(req)
    const where = b?.where === 'mac' ? 'mac' : b?.where === 'phone' || b?.where == null ? 'phone' : null
    if (!where) throw new HttpError(400, 'bad_where', 'Sign in on the phone or on the Mac.')
    // Signing in "on the Mac" opens a terminal window there; a Linux server has no screen for one.
    if (where === 'mac' && !has('openWindow')) throw unavailable('openWindow')
    return json(res, 200, { account: startLogin(where) })
  }
  if (path === '/v1/account/login' && m === 'DELETE') {
    cancelLogin()
    return json(res, 200, { ok: true })
  }
  if (path === '/v1/account/login/code' && m === 'POST') {
    // The code is a live credential: it goes to the sign-in's screen and nowhere else, never a log.
    const b = await readJsonBody(req)
    await sendLoginCode(b?.code)
    return json(res, 200, { ok: true })
  }
  if (path === '/v1/sessions' && m === 'POST') {
    const b = await readJsonBody(req)
    const files = attachments(b)
    // Which agent: Claude Code (none, or "claude"), or Codex (codex/sessions.ts).
    if (b.agent != null && b.agent !== 'claude' && b.agent !== 'codex') throw new HttpError(400, 'bad_agent', 'Pick Claude or Codex.')
    // No agent named: Claude Code, or Codex on a computer without it.
    const codex = b.agent === 'codex' || (b.agent == null && !claudeHere() && codexBinary().ok)
    if (!String(b.prompt ?? '').trim() && !files.length) throw new HttpError(400, 'no_prompt', `Write what you want ${codex ? 'Codex' : 'Claude'} to do.`)
    let cwd = b.projectPath as string | undefined
    if (b.newProject?.name) cwd = (b.newProject.nebula === true ? createNebulaProject(String(b.newProject.name)) : createProject(String(b.newProject.name), b.newProject.git !== false)).path
    if (!cwd) throw new HttpError(400, 'no_project', 'Pick a project for the session.')
    const full = allowedPath(cwd) ?? (existsSync(cwd) ? cwd : null)
    if (!full) throw new HttpError(400, 'bad_project', 'That project folder doesn’t exist.')
    // A folder macOS guards (Documents, Desktop, iCloud Drive, an external disk) from the service:
    // said now, not as a session stalled on a dialog on the Mac while its owner is away.
    const opens = await folderOpens(full)
    if (opens) throw new HttpError(409, opens.code, opens.message)
    if (codex) {
      const mode = b.permissionMode ?? 'read-only'
      if (!isCodexMode(mode)) throw new HttpError(400, 'bad_mode', 'Codex runs read-only or workspace-write.')
      const clientId = typeof b.clientId === 'string' && /^[\w-]{1,64}$/.test(b.clientId) ? b.clientId : undefined
      const text = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : null)
      const id = await createCodexSession({ cwd: full, prompt: withAttachments(String(b.prompt ?? ''), files), clientId, model: text(b.model), effort: text(b.effort), mode })
      notePrompt(c.deviceId, c.local, b.via)
      return json(res, 200, { session: listSessions().find((s) => s.id === id) })
    }
    if (b.permissionMode === 'bypassPermissions' && fromGlasses(c)) throw phoneOnly()
    // Claude Code won't run bypass mode as root (it would exit at once): said now, with the way out.
    if (b.permissionMode === 'bypassPermissions' && bypassBlockedByRoot()) throw new HttpError(409, 'bypass_root', 'Claude Code won’t skip permissions as root. Run Hypernova as a normal user (see docs/LINUX.md), or pick another mode.')
    const opts = { cwd: full, providerId: b.providerId, model: b.model, permissionMode: b.permissionMode, prompt: withAttachments(String(b.prompt ?? ''), files) }
    const inTerminal = await shared()
    if (!inTerminal) await needSdk()
    // settings.claudeAgents: in Claude Code's background daemon, so it shows in `claude agents`
    // (on the subscription only: another provider's environment is the holder's to give).
    const agents = inTerminal && settings.claudeAgents === true && (b.providerId == null || b.providerId === 'anthropic')
    const id = agents ? await createAgentsSession(opts) : inTerminal ? createSharedSession(opts) : createSession(opts)
    notePrompt(c.deviceId, c.local, b.via)
    return json(res, 200, { session: listSessions().find((s) => s.id === id) })
  }
  // The agents a session's main agent started, and one agent's own transcript (read-only).
  const ag = /^\/v1\/sessions\/([\w-]+)\/agents(?:\/([^/]+)\/events)?$/.exec(path)
  if (ag && m === 'GET') {
    const [, id, agentId] = ag
    if (!getSession(id!)) throw new HttpError(404, 'no_session', 'That session no longer exists.')
    if (agentId === undefined) return json(res, 200, listAgents(id!))
    // The id names a file: only an agent's own shape gets near the disk.
    if (!validAgentId(agentId)) throw new HttpError(400, 'bad_agent', 'That isn’t an agent of this session.')
    const limit = Math.min(1000, Math.max(1, Number(url.searchParams.get('limit') ?? 200) || 200))
    // A phone following a running agent asks only for what changed since its last answer.
    const got = agentEvents(id!, agentId, limit, url.searchParams.get('after'))
    if (!got) throw new HttpError(404, 'no_agent', 'That agent’s transcript isn’t on the Mac.')
    return json(res, 200, got)
  }
  // The chat's own: the usage strip (session-usage.ts), Claude's to-do list (todos.ts) and
  // "Rewind to here" (rewind.ts).
  const cq = /^\/v1\/sessions\/([\w-]+)\/(usage|todos|rewind)$/.exec(path)
  if (cq) {
    const rec = getSession(cq[1]!)
    if (!rec) throw new HttpError(404, 'no_session', 'That session no longer exists.')
    // The 1M window: the model it asked for says so ("opus[1m]"), which its replies' ids don't.
    const asked = [rec.model, getManaged(rec.id)?.model].find((x) => /\[1m\]/i.test(x ?? '')) ?? rec.model
    if (cq[2] === 'usage' && m === 'GET') return json(res, 200, { usage: isCodex(rec.id) ? codexSessionUsage(rec) : sessionUsage({ ...rec, model: asked }) })
    if (cq[2] === 'todos' && m === 'GET') return json(res, 200, todosOf(ensureParsed(rec).events) ?? { todos: [], updatedAt: null })
    if (cq[2] === 'rewind' && m === 'POST') {
      if (isCodex(rec.id)) throw notForCodex()
      const b = await readJsonBody(req)
      if (typeof b?.eventId !== 'string' || !b.eventId) throw new HttpError(400, 'no_message', 'Say which message to go back to.')
      try {
        return json(res, 200, await rewindTo(rec, b.eventId, await shared()))
      } catch (e) {
        if (e instanceof RewindError) throw new HttpError(e.status, e.code, e.message)
        throw e
      }
    }
    throw new HttpError(405, 'method', 'That isn’t how this is asked for.')
  }
  // What the session changed in its folder: the files with +/- and one file's diff (changes.ts).
  const ch = /^\/v1\/sessions\/([\w-]+)\/changes(\/diff)?$/.exec(path)
  if (ch && m === 'GET') {
    const rec = getSession(ch[1]!)
    if (!rec) throw new HttpError(404, 'no_session', 'That session no longer exists.')
    try {
      if (ch[2]) return json(res, 200, await diffOf(rec.cwd, url.searchParams.get('path') ?? ''))
      // Files this session's own edit tools wrote (Bash edits can't be told from the outside).
      const wrote: string[] = []
      for (const e of ensureParsed(rec).events) {
        if (e.kind !== 'tool' || e.name === 'Read') continue
        const p = toolPath(e.name, e.input)
        if (p) wrote.push(resolve(rec.cwd, p))
      }
      return json(res, 200, { changes: await changesOf(rec.cwd, wrote, url.searchParams.get('all') === '1') })
    } catch (e) {
      if (e instanceof ChangeError) throw new HttpError(e.status, e.code, e.message)
      throw e
    }
  }
  // Switching agents (switch.ts): what would be carried over, then the switch itself.
  const sw = /^\/v1\/sessions\/([\w-]+)\/switch(\/preview)?$/.exec(path)
  if (sw && m === 'POST') {
    const b = (await readJsonBody(req)) ?? {}
    try {
      if (sw[2]) {
        if (b.to !== 'claude' && b.to !== 'codex') throw new HttpError(400, 'bad_agent', 'Switch to claude or codex.')
        const choice = b.context ?? 'recent'
        if (!['recent', 'all', 'none'].includes(choice)) throw new HttpError(400, 'bad_context', 'Carry over recent, all or none.')
        return json(res, 200, await previewSwitch(sw[1]!, b.to, choice))
      }
      if (b.permissionMode === 'bypassPermissions' && fromGlasses(c)) throw phoneOnly()
      return json(res, 200, { session: await commitSwitch(sw[1]!, b) })
    } catch (e) {
      if (e instanceof SwitchError) throw new HttpError(e.status, e.code, e.message, e.extra)
      throw e
    }
  }
  // Carrying on by itself after a limit, turned on or off (resume.ts).
  const ac = /^\/v1\/sessions\/([\w-]+)\/auto-continue$/.exec(path)
  if (ac && m === 'POST') {
    if (isCodex(ac[1]!)) throw notForCodex()
    const b = await readJsonBody(req)
    if (typeof b?.on !== 'boolean') throw new HttpError(400, 'bad_on', 'Say on: true or false.')
    try {
      await setAutoContinue(ac[1]!, b.on)
    } catch (e) {
      if (e instanceof ResumeError) throw new HttpError(e.status, e.code, e.message, e.extra)
      throw e
    }
    return json(res, 200, { session: listSessions().find((x) => x.id === ac[1]) })
  }
  const s = /^\/v1\/sessions\/([\w-]+)\/(events|prompt|interrupt|continue|mode|terminal|open|menu|screenshot|telescope|provider|model)$/.exec(path)
  if (s) {
    const [, id, action] = s
    const rec = getSession(id!)
    if (!rec) throw new HttpError(404, 'no_session', 'That session no longer exists.')
    let tid = termOf(id!)
    // A `claude agents` session gets a shared terminal when one is needed: `claude attach` (terms.ts).
    const agentsJob = settings.claudeAgents === true && !tid && ['terminal', 'interrupt', 'menu', 'mode'].includes(action!) && isLiveTerminal(id!) ? agentJobOf(id!) : null
    if (agentsJob) tid = await attachAgentsTerm(id!, rec.cwd, agentsJob)
    const noTerm = () => new HttpError(409, 'no_terminal', 'This session isn’t running in a shared terminal.')
    const codex = isCodex(id!)
    if (codex && (action === 'provider' || action === 'menu')) throw notForCodex()
    // A Codex session's history is Codex's own: read once, when it's first opened.
    if (action === 'events' && codex) await ensureCodexHistory(id!)
    if (action === 'events') return json(res, 200, { events: eventsView(rec, url.searchParams) })
    if (action === 'screenshot') {
      if (m !== 'POST') throw new HttpError(405, 'method', 'Ask for a screenshot with POST.')
      const b = await readJsonBody(req)
      return json(res, 200, { shot: await screenshot(rec, ensureParsed(rec).events, b) })
    }
    if (action === 'telescope') {
      // What the session builds, as a picture: its page, emulator, simulator, terminal, a file it
      // made, or its Mac app's window (telescope/index.ts).
      if (m !== 'POST') throw new HttpError(405, 'method', 'Point the telescope with POST.')
      const b = await readJsonBody(req)
      return json(res, 200, { shot: await telescope({ ...rec, term: rec.term ?? (tid ? { id: tid } : null) }, ensureParsed(rec).events, b) })
    }
    if (action === 'model') {
      if (m !== 'POST') throw new HttpError(405, 'method', 'Change the model with POST.')
      return json(res, 200, await setSessionModel(id!, await readJsonBody(req)))
    }
    if (action === 'provider') {
      if (m !== 'POST') throw new HttpError(405, 'method', 'Switch providers with POST.')
      const b = await readJsonBody(req)
      const r = await moveSession(id!, typeof b?.providerId === 'string' ? b.providerId : '')
      return json(res, 200, { session: listSessions().find((x) => x.id === r.id), forked: r.forked })
    }
    if (action === 'terminal') {
      // A Codex session without one gets one: `codex resume` in a new shared terminal.
      const t = codex ? await openCodexTerminal(id!) : tid
      if (!t) throw noTerm()
      return json(res, 200, { termId: t, url: `/v1/terms/${t}/ws?ticket=${terminalTicket(t, c.deviceId)}` })
    }
    if (action === 'open') {
      const t = codex ? await openCodexTerminal(id!) : tid
      if (!t) throw noTerm()
      return json(res, 200, await openOnMac(t))
    }
    if (action === 'interrupt') {
      // Codex stops over its app-server, terminal or not.
      if (codex) await interruptCodex(id!)
      else if (tid) pressKeys(tid, '\x1b')
      else if (isSdk(id!)) await interrupt(id!)
      else throw new HttpError(409, 'terminal_session', 'This session runs in a terminal on your Mac. Press Esc there to stop it.')
      return json(res, 200, { ok: true })
    }
    if (action === 'menu') {
      // An option of the menu its terminal shows (session.menu), or Esc.
      if (!tid) throw noTerm()
      const b = await readJsonBody(req)
      const r = await answerMenu(tid, String(b.menuId ?? ''), { choice: Number.isInteger(b.choice) ? Number(b.choice) : undefined, alt: b.alt === true, cancel: b.cancel === true })
      if (!r.ok) {
        if (r.reason === 'menu_gone') throw new HttpError(409, 'menu_gone', 'That menu isn’t in the terminal any more.')
        throw new HttpError(409, 'menu_moved', 'The terminal didn’t take that answer. Open it to check.')
      }
      return json(res, 200, { ok: true })
    }
    if (action === 'mode' && codex) {
      const b = await readJsonBody(req)
      if (!isCodexMode(b.permissionMode)) throw new HttpError(400, 'bad_mode', 'Codex runs read-only or workspace-write.')
      const applies = setCodexMode(id!, b.permissionMode)
      return json(res, 200, { session: listSessions().find((x) => x.id === id), applies })
    }
    if (action === 'mode') {
      const b = await readJsonBody(req)
      const mode = String(b.permissionMode ?? 'default')
      if (!['default', 'acceptEdits', 'plan', 'auto', 'bypassPermissions'].includes(mode)) throw new HttpError(400, 'bad_mode', 'That isn’t a permission mode.')
      if (mode === 'bypassPermissions' && fromGlasses(c)) throw phoneOnly()
      if (tid) {
        // Bypass in a terminal that wasn't started with it allowed: Shift+Tab never gets there, so
        // don't press it (the phone knows beforehand from session.bypassAvailable).
        if (mode === 'bypassPermissions' && getSession(id!)?.bypassAvailable === false) throw new HttpError(409, 'mode_unavailable', 'This session wasn’t started with “bypass permissions”, so it can’t switch to it.')
        // A shared terminal: the holder presses Shift+Tab until Claude Code shows the mode.
        const r = await switchTermMode(tid, mode)
        if (!r.ok) {
          if (r.reason === 'no_input') throw new HttpError(409, 'waiting', 'Claude is showing something in the terminal. Answer it first, then change the mode.')
          if (r.reason === 'unavailable') throw new HttpError(409, 'mode_unavailable', mode === 'bypassPermissions' ? 'This session wasn’t started with “bypass permissions”, so it can’t switch to it.' : 'This session can’t switch to that mode.')
          throw new HttpError(409, 'terminal_busy', 'The terminal didn’t answer. Try again in a moment.')
        }
        upsertSession(id!, { permissionMode: r.mode ?? mode })
        return json(res, 200, { session: listSessions().find((x) => x.id === id) })
      }
      if (!isSdk(id!)) throw new HttpError(409, 'terminal_mode', 'Change the mode in the terminal: Shift+Tab cycles through them.')
      const applies = await setMode(id!, mode)
      // "next_turn": a turn is running and the mode starts with the next one.
      return json(res, 200, { session: listSessions().find((x) => x.id === id), applies })
    }
    // Continue after the 10-minute away stop, or (`reason: "limit"`) after a limit the session didn't
    // carry on from by itself: its ready card goes once the turn starts.
    const afterLimit = action === 'continue' && (await readJsonBody(req))?.reason === 'limit'
    if (afterLimit && codex) throw notForCodex()
    const b = action === 'continue' ? { text: afterLimit ? CONTINUE_TEXT.manual : "I'm back. Please continue where you left off." } : await readJsonBody(req)
    const files = attachments(b)
    if (!String(b.text ?? '').trim() && !files.length) throw new HttpError(400, 'empty', 'Write something first.')
    const text = withAttachments(String(b.text ?? '').trim(), files)
    // The phone's name for this send: the queue entry and the event it becomes carry it back.
    const clientId = typeof b.clientId === 'string' && /^[\w-]{1,64}$/.test(b.clientId) ? b.clientId : undefined
    // The same clientId for the same session within the window is one send: an answer that got lost
    // on the way (a phone sending again when the Mac came back) doesn't type the prompt twice.
    const r = await dedupSend(id!, clientId, () => promptSession(id!, text, { afterLimit, replace: b.replace === true, clientId }))
    if (action === 'prompt') notePrompt(c.deviceId, c.local, b.via)
    return json(res, 200, { session: listSessions().find((x) => x.id === r.id), ...(r.forked === undefined ? {} : { forked: r.forked }), queued: r.queued })
  }
  const ap = /^\/v1\/approvals\/([\w-]+)$/.exec(path)
  if (ap && m === 'POST') {
    const b = await readJsonBody(req)
    if (!['allow', 'deny', 'always', 'session'].includes(b?.decision)) throw new HttpError(400, 'bad_decision', 'Answer with allow, deny, always or session.')
    const decision = b.decision as 'allow' | 'deny' | 'always' | 'session'
    if (decision === 'always' && fromGlasses(c)) throw phoneOnly()
    const ok = answerApproval(ap[1]!, { decision, message: b.message, answers: b.answers }, c.local ? 'mac' : 'phone')
    if (!ok) throw new HttpError(409, 'resolved', 'This was already answered.')
    noteApproval(c.deviceId, c.local)
    return json(res, 200, { ok: true })
  }
  if (path === '/v1/attachments' && m === 'POST') {
    if (Number(req.headers['content-length'] ?? 0) > MAX_ATTACHMENT) throw new HttpError(413, 'too_large', 'That file is too big to attach (30 MB at most).')
    const a = await saveAttachment(url.searchParams.get('name') || 'file', req)
    log('attachment', a.name, `${Math.round(a.size / 1024)} KB`)
    return json(res, 200, { attachment: a })
  }
  // Files (files.ts): a folder's entries, and a file to view or (`download=1`) to save, streamed,
  // with ranges. Errors carry codes the phone acts on (docs/PROTOCOL.md, "Files").
  if (path === '/v1/files' && m === 'GET') return json(res, 200, await listDir(url.searchParams.get('path') || settings.projectsRoot))
  if (path === '/v1/file' && (m === 'GET' || m === 'HEAD')) {
    const download = url.searchParams.get('download') === '1'
    const f = await fileFor(url.searchParams.get('path') ?? '', download ? DOWNLOAD_MAX : null)
    return sendChecked(req, res, f, download)
  }
  if (path === '/v1/preview' && m === 'POST') {
    const b = await readJsonBody(req)
    try {
      return json(res, 200, createPreview(b, c.local ? `http://127.0.0.1:${API_PORT}` : baseUrlOf(req)))
    } catch (e: any) {
      throw new HttpError(e.status ?? 400, e.code ?? 'preview', e.message, e.extra)
    }
  }
  if (path === '/v1/qa') return json(res, 200, { url: `${c.local ? `http://127.0.0.1:${LOCAL_PORT}` : baseUrlOf(req)}/qa?k=${QA_KEY}` })
  if (path === '/v1/devices/me' && m === 'DELETE') {
    if (c.deviceId) {
      removeDevice(c.deviceId)
      dropDevice(c.deviceId)
    }
    return json(res, 200, { ok: true })
  }
  // The glasses through this phone (glasses.ts): the phone app says its owner allowed Lens on it, or
  // disconnected the last one. Only a phone app itself, never the glasses through it (refused above).
  if (path === '/v1/devices/me/glasses' && (m === 'PUT' || m === 'DELETE')) {
    if (!c.deviceId || getDevice(c.deviceId)?.client === 'lens') throw new HttpError(403, 'not_a_phone', 'Only the Hypernova app on a phone carries glasses.')
    if (m === 'DELETE') return json(res, 200, { ok: dropGlasses(c.deviceId) })
    return json(res, 200, { glasses: allowGlasses(c.deviceId, await readJsonBody(req)) })
  }
  throw new HttpError(404, 'not_found', 'Unknown endpoint.')
}

/** The pairing page: hands [base] (the app's `u`) and the code over to the app; [pageBase]: where this page itself is, for its download links. */
function pairPage(res: ServerResponse, base: string, code: string, name: string, lanQuery = '', pageBase = base): void {
  const esc = (x: string) => x.replace(/[&<>"']/g, (ch) => `&#${ch.charCodeAt(0)};`)
  const appLink = `hypernova://pair?v=1&u=${encodeURIComponent(base)}&c=${encodeURIComponent(code)}&n=${encodeURIComponent(name)}${lanQuery}`
  const intent = `intent://pair?v=1&u=${encodeURIComponent(base)}&c=${encodeURIComponent(code)}&n=${encodeURIComponent(name)}${lanQuery}#Intent;scheme=hypernova;package=com.prudnikweb.hypernova;S.browser_fallback_url=${encodeURIComponent(pageBase + '/download/android')};end`
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
  res.end(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"><title>Pair with Hypernova</title>
<style>:root{color-scheme:dark}body{margin:0;min-height:100vh;background:#14110E;color:#F5F2EB;font:500 16px/24px system-ui,sans-serif;display:flex;align-items:center;justify-content:center}
main{max-width:420px;padding:32px 24px}.wm{font-weight:200;font-size:22px;color:#B5B0A6}.wm b{color:#7FFFA0;font-weight:200}h1{font-size:28px;line-height:32px;letter-spacing:-.02em;margin:20px 0 8px}
p{color:#B5B0A6;margin:0 0 24px}a.b{display:block;text-align:center;text-decoration:none;font-weight:600;border-radius:999px;padding:15px 20px;margin:10px 0}
.p{background:#7FFFA0;color:#14110E}.g{color:#B5B0A6;box-shadow:inset 0 0 0 1px #2C2823}small{display:block;color:#7A7468;margin-top:18px;font-size:13px;line-height:19px}</style></head>
<body><main><div class="wm">hypernova<b>/</b></div><h1>Pair with ${esc(name)}.</h1><p>Open Hypernova on this phone to finish pairing.</p>
<a class="b p" href="${esc(intent)}">Open Hypernova</a><a class="b g" href="${esc(pageBase)}/download/android">Get the app first</a>
<small>If nothing happens, install the app with the second button, then scan the code on your Mac again.</small></main>
<script>if (/Android/.test(navigator.userAgent)) setTimeout(function(){ location.href = ${JSON.stringify(intent)} }, 300); else document.querySelector('.p').href = ${JSON.stringify(appLink)}</script></body></html>`)
}

/** The Android app this Mac hands out: its own copy, the one bundled with the daemon, or a dev build. */
function findApk(): string | null {
  const here = dirname(fileURLToPath(import.meta.url))
  const candidates = [join(SUPPORT, 'android', 'hypernova.apk'), join(here, '..', 'android', 'hypernova.apk')]
  const dist = join(here, '..', '..', 'dist')
  // HYPERNOVA_NO_DEV_APK: tests that need "no APK here" whatever a checkout's dist/ holds.
  if (!process.env.HYPERNOVA_NO_DEV_APK) try {
    for (const f of readdirSync(dist).filter((x) => x.endsWith('.apk')).sort((a, b) => statSync(join(dist, b)).mtimeMs - statSync(join(dist, a)).mtimeMs)) candidates.push(join(dist, f))
  } catch {}
  return candidates.find((p) => existsSync(p)) ?? null
}

/**
 * The Android app this Mac hands out: its version (the build writes `<apk>.json` next to it),
 * so the phone can tell when there's a newer one.
 */
function appInfo(): { versionCode: number; versionName: string } | null {
  const apk = findApk()
  if (!apk) return null
  const meta = readJson<{ versionCode?: number; versionName?: string } | null>(apk.replace(/\.apk$/, '.json'), null)
  return typeof meta?.versionCode === 'number' ? { versionCode: meta.versionCode, versionName: String(meta.versionName ?? '') } : null
}

/**
 * The bundled APK's SHA-256 (hex), computed once per file and remembered by its path, size and
 * time: the download page shows it, and the Mac shows the same next to its "Get the app" code, so
 * a download over the local network (a self-signed certificate the browser can't check) can be
 * compared by eye with what the Mac says.
 */
let apkDigest: { key: string; sha256: string } | null = null
export function apkInfo(): { sha256: string; size: number; versionCode: number | null; versionName: string | null } | null {
  const apk = findApk()
  if (!apk) return null
  const s = statSync(apk)
  const key = `${apk}:${s.size}:${s.mtimeMs}`
  if (apkDigest?.key !== key) apkDigest = { key, sha256: createHash('sha256').update(readFileSync(apk)).digest('hex') }
  const info = appInfo()
  return { sha256: apkDigest.sha256, size: s.size, versionCode: info?.versionCode ?? null, versionName: info?.versionName ?? null }
}
/** "ab12 cd34 …": a checksum in groups of four, easier to compare by eye. */
export const groupedHex = (hex: string): string => hex.replace(/(.{4})/g, '$1 ').trim()

/**
 * "Get Hypernova for Android": what it is, a Download button, its checksum, and how to install it.
 * [trusted]: opened through this computer's name (names.ts), whose certificate the browser checked,
 * so the warning about comparing the checksum isn't needed.
 */
function appPage(res: ServerResponse, trusted = false): void {
  const apk = findApk()
  const s = apk ? statSync(apk) : null
  const built = s ? new Date(s.mtimeMs).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''
  const info = appInfo()
  const digest = s ? apkInfo()?.sha256 : null
  const body = s
    ? `<p>Version ${info ? `${info.versionName} (${info.versionCode})` : VERSION} · ${built} · ${(s.size / 1048576).toFixed(1)} MB</p>
<a class="b p" href="/download/android/Hypernova.apk" download="Hypernova.apk">Download the app</a>
<p class="sum">SHA-256 of the file<br>${groupedHex(digest ?? '')}</p>
${trusted ? '' : `<small class="warn">Opened over your own Wi‑Fi, your browser can't vouch for this page (the computer's certificate is its own), so the checksum above must be the one shown next to the code on your computer. If it isn't, don't install.</small>`}
<ol><li>Tap <b>Download the app</b>. If your browser asks whether to keep the file, keep it.</li>
<li>Open it from the notification, or from Files → Downloads.</li>
<li>Tap <b>Install</b>, or <b>Update</b> if Hypernova is already on this phone. The first time, Android asks to let your browser install apps: allow it, then go back.</li></ol>
<small>Updating keeps your pairing and settings.</small>`
    : `<p>The Android app isn’t on this computer. Get <b>Hypernova.apk</b> from <a href="${RELEASES}">Hypernova’s latest release</a>.</p>`
  res.writeHead(s ? 200 : 404, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
  res.end(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"><title>Get Hypernova</title>
<style>:root{color-scheme:dark}body{margin:0;min-height:100vh;background:#14110E;color:#F5F2EB;font:500 16px/24px system-ui,sans-serif;display:flex;align-items:center;justify-content:center}
main{box-sizing:border-box;width:100%;max-width:440px;padding:32px 24px}.wm{font-weight:200;font-size:22px;color:#B5B0A6}.wm b{color:#7FFFA0;font-weight:200}h1{font-size:28px;line-height:32px;letter-spacing:-.02em;margin:20px 0 8px}
p{color:#B5B0A6;margin:0 0 24px;font-family:ui-monospace,monospace;font-size:13px}a.b{display:block;text-align:center;text-decoration:none;font-weight:600;border-radius:999px;padding:15px 20px;margin:10px 0 26px}
.p{background:#7FFFA0;color:#14110E}ol{color:#B5B0A6;padding-left:20px;margin:0}li{margin:0 0 12px}li b{color:#F5F2EB;font-weight:600}small{display:block;color:#7A7468;margin-top:18px;font-size:13px;line-height:19px}
.sum{font-size:12px;line-height:18px;word-break:break-all;margin:-14px 0 12px}.warn{margin:0 0 22px;color:#E8B341}</style></head>
<body><main><div class="wm">hypernova<b>/</b></div><h1>Get Hypernova for Android.</h1>${body}</main></body></html>`)
}

/** The APK (48 MB), with ranges: a download cut off on a phone's network carries on. */
async function serveApk(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const apk = findApk()
  if (!apk) return json(res, 404, { error: { code: 'no_apk', message: `The Android app isn’t on this computer: get Hypernova.apk from ${RELEASES}` } })
  const s = statSync(apk)
  return sendChecked(req, res, { full: apk, size: s.size, mtime: s.mtimeMs, mime: 'application/vnd.android.package-archive' }, true, 'Hypernova.apk')
}

/**
 * The testing checklist: its page and the routes the page calls. Each needs its key (`k`, from
 * `GET /v1/qa`, which the page passes on to its own requests) or the admin token. [macPort]: on
 * the Mac app's port, whose menu opens the bare page in a browser: that gets sent on to the page
 * with its key, so a site in the browser can't reach the routes that change anything.
 */
async function qa(c: Ctx, macPort: boolean): Promise<void> {
  const { req, res, url } = c
  const sub = url.pathname.replace(/^\/qa\/?/, '')
  const host = String(req.headers.host ?? '')
  const origin = req.headers.origin
  // tailscale serve adds X-Forwarded-For on the phone port. Nothing does on the Mac's own, so
  // there it would only be a page on a rebound domain dodging the host check.
  const viaTailnet = !macPort && !!req.headers['x-forwarded-for']
  // The LAN listener lets only a certified device this far (a browser has neither the pin nor the
  // device certificate, rebound domain or not), and its host is the Mac's address.
  const okHost = viaTailnet || c.lan || /^(127\.0\.0\.1|localhost)(:\d+)?$/.test(host)
  const okOrigin = !origin || origin === 'null' ? true : (() => {
    try {
      return new URL(origin).host === host
    } catch {
      return false
    }
  })()
  if (!okHost || !okOrigin) throw new HttpError(403, 'forbidden', 'Open the checklist from Hypernova.')
  if (!c.local && !safeEqual(url.searchParams.get('k') ?? '', QA_KEY)) {
    // Opened by hand or by the Mac app (Sec-Fetch-Site "none"; a reload is "same-origin"; a
    // browser too old to say sends none): never when another site sent the browser here. That
    // site couldn't read the page anyway, as the host check holds off a rebound domain.
    const site = req.headers['sec-fetch-site']
    if (macPort && !sub && req.method === 'GET' && (site === undefined || site === 'none' || site === 'same-origin')) {
      res.writeHead(302, { location: `/qa?k=${QA_KEY}`, 'cache-control': 'no-store', 'referrer-policy': 'no-referrer' })
      return void res.end()
    }
    throw new HttpError(401, 'unauthorized', 'Open the checklist from the Hypernova app.')
  }
  if (sub === 'save' && !String(req.headers['content-type'] ?? '').includes('application/json')) throw new HttpError(415, 'json_only', 'Send JSON.')
  if (!sub) {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
    return void res.end(qaPage())
  }
  if (sub === 'state') return json(res, 200, qaState())
  if (sub === 'save' && req.method === 'POST') return json(res, 200, qaSave(await readJsonBody(req)))
  if (sub === 'shot' && req.method === 'POST') {
    const bytes = await readBody(req, 25 * 1024 * 1024)
    return json(res, 200, { name: qaShot(url.searchParams.get('target') ?? 'misc', bytes, String(req.headers['content-type'] ?? 'image/png')) })
  }
  const shot = /^shot\/([\w.-]+)$/.exec(sub)
  if (shot) {
    const p = qaShotPath(shot[1]!)
    if (!p) throw new HttpError(404, 'not_found', 'No such screenshot.')
    return sendFile(res, p)
  }
  throw new HttpError(404, 'not_found', 'Unknown endpoint.')
}

/** Phone sessions run in shared terminals when this Mac can (and hooks bring their approvals to the phone). */
async function shared(): Promise<boolean> {
  return (await sharedTerminalsAvailable()) && integrationStatus().hooks.installed
}
useShared(shared)

/** How long a send's clientId is remembered (the offline queue sends again after a dropped answer). */
export const DEDUP_WINDOW_MS = 10 * 60_000
const recentSends = new Map<string, { at: number; done: Promise<{ id: string; queued: boolean; forked?: boolean }> }>()

/**
 * Runs [send] once per ([sessionId], [clientId]) within [DEDUP_WINDOW_MS]: a repeat gets the first
 * send's answer (or waits for it) and sends nothing. A send that failed is forgotten, so it can be
 * tried again. No clientId: no dedup.
 */
export async function dedupSend<T extends { id: string; queued: boolean; forked?: boolean }>(sessionId: string, clientId: string | undefined, send: () => Promise<T>, now: () => number = Date.now): Promise<T> {
  if (!clientId) return send()
  const t = now()
  for (const [k, v] of recentSends) if (t - v.at > DEDUP_WINDOW_MS) recentSends.delete(k)
  const key = `${sessionId}/${clientId}`
  const seen = recentSends.get(key)
  if (seen) return seen.done as Promise<T>
  const done = send()
  recentSends.set(key, { at: t, done })
  try {
    return await done
  } catch (e) {
    if (recentSends.get(key)?.done === done) recentSends.delete(key)
    throw e
  }
}

/**
 * Send [text] to session [id] the way `POST /v1/sessions/{id}/prompt` does (also its `continue`, and
 * an automation's continue, automations.ts): typed into its shared terminal, queued in the SDK
 * runner, or, with neither, a fork of a session open elsewhere or a resume of one that ended.
 * Returns where it went (a fork's id), whether it waits in the queue, and (fork or resume) whether
 * it forked. Throws the route's errors: 404 `no_session`, 409 `needs_answer`, `waiting`, `typing`
 * (with `pending`), `starting`, `not_seen`, `not_sent`, `terminal_busy`; with [noFork], 409
 * `no_terminal` rather than a fork (an automation never copies a session open in a plain terminal).
 */
export async function promptSession(id: string, text: string, o: { afterLimit?: boolean; replace?: boolean; clientId?: string; noFork?: boolean } = {}): Promise<{ id: string; queued: boolean; forked?: boolean }> {
  const rec = getSession(id)
  if (!rec) throw new HttpError(404, 'no_session', 'That session no longer exists.')
  // A conversation that switched agents goes on in its new session (switch.ts).
  const moved = linkOf(id).to
  if (moved) throw new HttpError(409, 'switched', 'This conversation went on in another session. Write there.', { to: moved })
  // Codex takes prompts over its app-server, whether or not a terminal shows the session.
  if (isCodex(id)) {
    upsertSession(id, { stoppedForAway: false })
    return { id, queued: (await promptCodex(id, text, o.clientId)).queued }
  }
  let tid = termOf(id)
  // A `claude agents` session: typed in through `claude attach` (the same conversation), never forked.
  const job = settings.claudeAgents === true && !tid && isLiveTerminal(id) ? agentJobOf(id) : null
  if (job) {
    tid = await attachAgentsTerm(id, rec.cwd, job)
    if (!tid) throw new HttpError(409, 'terminal_busy', 'The background session didn’t open. Try again in a moment.')
  }
  if (tid) {
    // Typed into the live terminal, only into Claude Code's empty input box (never a dialog).
    // While Claude works, Claude Code queues it: `queued` says so.
    if (pendingApprovals().some((a) => a.sessionId === id)) throw new HttpError(409, 'needs_answer', 'Claude is waiting for your answer above. Answer it first.')
    const type = (t: string) => typeInto(tid, t, o.replace === true, o.clientId)
    // After a limit, Claude Code's own "continue" in the box gives way (resume.ts).
    const r = o.afterLimit ? await typeContinue({ type, keys: (d) => void pressKeys(tid, d), sleep: (ms) => new Promise((ok) => setTimeout(ok, ms)) }, text) : await type(text)
    if (!r.ok) {
      const why: Record<string, [string, string]> = {
        no_input: ['waiting', 'Claude is showing something in the terminal (a question or a menu). Open the terminal to answer it.'],
        busy_input: ['typing', 'There’s unsent text in this session’s terminal on the Mac. Send or clear it there first.'],
        not_started: ['starting', 'The session is still starting. Try again in a moment.'],
        not_seen: ['not_seen', 'Your message is in the terminal’s input box but wasn’t sent. Open the terminal to check it.'],
        not_sent: ['not_sent', 'Your message is in the terminal’s input box but wasn’t sent. Open the terminal to send it.'],
        unreachable: ['terminal_busy', 'The terminal didn’t answer. Try again in a moment.'],
      }
      const [code, message] = why[r.reason ?? 'unreachable'] ?? why.unreachable!
      // Unsent text in the way: the phone shows it and can replace it.
      throw new HttpError(409, code, message, r.reason === 'busy_input' && r.text ? { pending: r.text } : undefined)
    }
    upsertSession(id, { stoppedForAway: false })
    return { id, queued: r.queued === true }
  }
  if (isSdk(id)) {
    await needSdk()
    upsertSession(id, { stoppedForAway: false })
    return { id, queued: sendPrompt(id, text, undefined, o.clientId) }
  }
  // A session with no shared terminal: fork it while it's open somewhere else, continue it in place once it has ended.
  const live = isLiveTerminal(id)
  if (live && o.noFork) throw new HttpError(409, 'no_terminal', 'This session runs in a terminal Hypernova doesn’t hold.')
  const useShared = await shared()
  if (!useShared) await needSdk()
  upsertSession(id, { stoppedForAway: false })
  const newId = live
    ? useShared
      ? createSharedSession({ cwd: rec.cwd, prompt: text, forkFrom: id })
      : createSession({ cwd: rec.cwd, prompt: text, forkFrom: id })
    : useShared
      ? createSharedSession({ cwd: rec.cwd, prompt: text, resume: id, ...(resumeModels.get(id) ? { model: resumeModels.get(id) } : {}) })
      : (adoptTerminal(id, rec.cwd, resumeModels.get(id)), sendPrompt(id, text, undefined, o.clientId), id)
  if (!live) resumeModels.delete(id)
  return { id: newId, queued: false, forked: live }
}

/**
 * A provider was added or changed: a new key or address is checked at once and the answer waits
 * for it (8 s at most) so the editor can say "Key works", "refused this key" or "nothing answers
 * there" (docs/PROTOCOL.md, "Providers": `check`, null when nothing new needed one). OpenRouter's
 * check is its balance read (balance.ts); a refused key shows on the provider as well.
 */
async function afterSave(saved: ProviderSaved): Promise<{ provider: unknown; check: ProviderCheck | null; keyDropped: boolean }> {
  const p = saved.provider
  let verdict: ProviderCheck | null = null
  if (!p.builtIn && (saved.keyChanged || saved.addressChanged)) {
    if (hasBalance(p)) {
      await providerSaved(p.id, true)
      const bal = listProviders().find((x) => x.id === p.id)?.balance ?? null
      const state = bal?.error === 'bad_key' ? 'bad_key' : bal && bal.error === null ? 'ok' : 'unreachable'
      verdict = { state, text: state === 'ok' ? (bal?.remaining != null ? `Key works. $${bal.remaining.toFixed(2)} left.` : 'Key works.') : state === 'bad_key' ? `${p.name} refused this key.` : `Saved. Couldn’t reach ${p.name} to check the key.`, models: null, balance: bal, checkedAt: Date.now() }
    } else if (p.kind === 'custom' && p.baseUrl) {
      verdict = await checkProvider({ kind: p.kind, name: p.name, baseUrl: p.baseUrl, apiKey: p.apiKey })
      if (verdict.state === 'bad_key') providerKeyRefused(p.id, verdict.text)
    }
  }
  broadcast({ t: 'providers', providers: listProviders() })
  return { provider: listProviders().find((x) => x.id === p.id), check: verdict, keyDropped: saved.keyDropped }
}

/**
 * Move session [id] to provider [providerId], as `POST /v1/sessions/{id}/provider` (also an
 * automation's "Switch provider at the limit"). Returns the session it goes on in and whether
 * that's a fork. 404 `no_session`, `no_provider`; 409 `busy`, `same_provider`, `terminal_busy`;
 * with [noFork] (an automation's), also `no_terminal` and `typing` (automationMayMove).
 */
export async function moveSession(id: string, providerId: string, o: { noFork?: boolean } = {}): Promise<{ id: string; forked: boolean }> {
  const rec = getSession(id)
  if (!rec) throw new HttpError(404, 'no_session', 'That session no longer exists.')
  if (isCodex(id)) throw notForCodex()
  const p = providerId ? findProvider(providerId) : null
  if (!p) throw new HttpError(404, 'no_provider', 'That provider isn’t set up on this Mac.')
  // A move still under way (its old claude closing, the new one starting): one at a time, or a
  // second restart would close the terminal the first just opened.
  if (isRestarting(id)) throw new HttpError(409, 'busy', 'This session is moving to another provider right now. Try again in a moment.')
  if (p.id === rec.providerId) throw new HttpError(409, 'same_provider', 'This session already runs on that provider.')
  // A session a limit stopped may show Claude Code's own dialog about it (what to do about the
  // limit): that waits on nothing another provider can't do. An approval still does.
  const limitDialog = rec.state === 'needs_you' && !!rec.blocked && !pendingApprovals().some((a) => a.sessionId === id)
  if (rec.state === 'working' || (rec.state === 'needs_you' && !limitDialog) || rec.state === 'starting' || isRunning(id)) throw new HttpError(409, 'busy', 'Claude is still at work in this session. Try again once it stops.')
  const tid = termOf(id)
  if (o.noFork) await automationMayMove(rec, tid)
  return switchProvider(rec, p.id, tid)
}

/** A model the owner chose for an ended session, for when it resumes (a session the daemon doesn't run otherwise has no record to keep it in). */
const resumeModels = new Map<string, string | null>()

/**
 * Session [id]'s model, as `POST /v1/sessions/{id}/model {model, confirmPaid?}`: one of
 * `GET /v1/models`' for its provider (`null` or "default": the provider's default). Shown at once.
 * A shared terminal gets `/model <value>` typed (Claude Code takes it while Claude works, from its
 * next request); a session the daemon runs takes it from its next turn; an ended one when it
 * resumes. A terminal Hypernova doesn't hold can't be typed into. On a provider other than the
 * subscription (it costs money) only with `confirmPaid: true`, as automations ask.
 */
export async function setSessionModel(id: string, b: any): Promise<{ model: string | null; session: ReturnType<typeof listSessions>[number] | undefined; applies: 'now' | 'next_turn' }> {
  const rec = getSession(id)
  if (!rec) throw new HttpError(404, 'no_session', 'That session no longer exists.')
  const raw = b?.model
  const want = raw === null || raw === 'default' ? null : typeof raw === 'string' && raw.trim() ? raw.trim() : undefined
  if (want === undefined) throw new HttpError(400, 'unknown_model', 'Say which model: one from the list, or null for the default.')
  if (isCodex(id)) {
    // One of Codex's own (GET /v1/codex/models), with an effort it offers; null: its default.
    const list = await codexModels()
    const opt = want === null ? list.find((o) => o.isDefault) : list.find((o) => o.value === want)
    if (!opt) throw new HttpError(400, 'unknown_model', 'Codex doesn’t offer that model here.')
    const effort = typeof b?.effort === 'string' && b.effort ? b.effort : undefined
    if (effort && !opt.efforts.includes(effort)) throw new HttpError(400, 'unknown_effort', `${opt.name} doesn’t offer that reasoning effort.`)
    const applies = setCodexModel(id, opt.value, effort)
    return { model: opt.value, session: listSessions().find((x) => x.id === id), applies }
  }
  const provider = getProvider(rec.providerId)
  const list = (await listModels(provider.id))[0]
  const opt = list?.models.find((o) => (want === null ? o.value === null : o.value === want || o.model === want))
  if (!opt) throw new HttpError(400, 'unknown_model', `${provider.name} doesn’t offer that model here.`)
  const free = !!opt.price && opt.price.input === 0 && opt.price.output === 0
  if (provider.kind !== 'anthropic' && !free && b?.confirmPaid !== true && b?.paid !== true) {
    throw new HttpError(409, 'confirm_paid', `This session runs on ${provider.name}, which costs money. Confirm to switch its model.`)
  }
  // What Claude Code takes, and what the session shows (the id when the list knows it).
  const value = opt.value
  const shown = opt.model ?? opt.value
  const busy = (reason: string, message: string, extra?: Record<string, unknown>) => new HttpError(409, 'busy', message, { reason, ...(extra ?? {}) })
  const keep = () => {
    const m = getManaged(id)
    if (m && m.model !== value) saveManaged({ ...m, model: value })
  }
  let applies: 'now' | 'next_turn'
  const tid = termOf(id)
  if (tid) {
    if (pendingApprovals().some((a) => a.sessionId === id)) throw busy('needs_answer', 'Claude is waiting for your answer. Answer it first, then change the model.')
    const r = await typeInto(tid, `/model ${value ?? 'default'}`)
    if (!r.ok) {
      if (r.reason === 'no_input') throw busy('waiting', 'Claude is showing something in the terminal (a question or a menu). Answer it first, then change the model.')
      if (r.reason === 'busy_input') throw busy('typing', 'There’s unsent text in this session’s terminal on the Mac. Send or clear it there first.', r.text ? { pending: r.text } : undefined)
      if (r.reason === 'not_started') throw busy('starting', 'The session is still starting. Try again in a moment.')
      throw busy('terminal_busy', 'The terminal didn’t answer. Try again in a moment.')
    }
    keep()
    applies = rec.state === 'working' || rec.state === 'starting' ? 'next_turn' : 'now'
  } else if (isSdk(id)) {
    keep()
    applies = isRunning(id) ? 'next_turn' : 'now'
  } else if (isLiveTerminal(id)) {
    throw new HttpError(409, 'terminal_model', 'This session runs in a terminal Hypernova doesn’t hold. Type /model there on the Mac.')
  } else {
    // Ended: it runs on this model once it resumes.
    keep()
    resumeModels.set(id, value)
    applies = 'next_turn'
  }
  upsertSession(id, { model: shown })
  return { model: shown, session: listSessions().find((x) => x.id === id), applies }
}

/** A shared terminal Hypernova started for this session itself (the phone's), which it may restart. */
const heldHere = (rec: NonNullable<ReturnType<typeof getSession>>) => rec.origin === 'hypernova' && getManaged(rec.id)?.engine === 'term'

/**
 * An automation moves a session only where that copies nothing and loses nothing: never a fork
 * (an automation never copies a session the owner has open in a terminal Hypernova doesn't hold:
 * 409 `no_terminal`), and never a restart over unsent text in its input box, which the owner may be
 * typing right then (409 `typing`, tried again later). The owner's own move on the phone does both.
 */
async function automationMayMove(rec: NonNullable<ReturnType<typeof getSession>>, tid: string | null): Promise<void> {
  if (isSdk(rec.id)) return
  if (tid && heldHere(rec)) {
    const screen = await termScreen(tid)
    if (!screen) throw new HttpError(409, 'terminal_busy', 'The terminal didn’t answer. Try again in a moment.')
    const typed = unsentText(screen.text)
    if (typed) throw new HttpError(409, 'typing', 'There’s unsent text in this session’s terminal on the Mac. Send or clear it there first.', { pending: typed.slice(0, 500) })
    return
  }
  if (tid || isLiveTerminal(rec.id)) throw new HttpError(409, 'no_terminal', 'This session runs in a terminal Hypernova doesn’t hold.')
}

/** The text in Claude Code's input box on a terminal's screen, less its own "continue" after a limit ('' when none). */
export function unsentText(screen: string): string {
  const lines = screen.split('\n')
  const box = boxLines(lines)
  const inBox = box ? boxText(lines, box).trim() : ''
  return inBox === 'continue' ? '' : inBox
}

// An automation sends its continues, and moves sessions, the routes' own way (automations.ts).
useSessionOps({
  async promptSession(id, text, afterLimit) {
    try {
      return { ok: true, sessionId: (await promptSession(id, text, { afterLimit, noFork: true })).id }
    } catch (e) {
      if (e instanceof HttpError) return { ok: false, code: e.code }
      throw e
    }
  },
  async moveSession(id, providerId) {
    return (await moveSession(id, providerId, { noFork: true })).id
  },
})

/** After a restart onto another provider, how long the old claude's end is still expected (its hook, its registry entry). */
const RESTART_GRACE_MS = 15_000

/**
 * Continue session [rec]'s conversation on another provider (docs/PROTOCOL.md, "Account"). An SDK
 * session takes it with its next prompt. A phone-started shared terminal (idle) restarts on the
 * same conversation with the new provider's environment, which it only reads at start. One
 * started in a Mac terminal, or one still open in a terminal Hypernova doesn't hold, forks: the
 * new session is the one to use. One that has ended resumes on the new provider.
 */
async function switchProvider(rec: NonNullable<ReturnType<typeof getSession>>, providerId: string, tid: string | null): Promise<{ id: string; forked: boolean }> {
  const id = rec.id
  if (isSdk(id)) {
    setManagedProvider(id, providerId)
    upsertSession(id, { providerId, model: null, blocked: null })
    return { id, forked: false }
  }
  if (tid && heldHere(rec)) {
    // Its claude process is about to go on purpose: while the terminal closes, its exit, its
    // SessionEnd hook (it gets SIGHUP) and its registry entry mustn't read as the session ending.
    // A terminal that stays open is the session's again, found by the next poll.
    const old = rec.pid ?? termChildPid(tid)
    // A minute: longer than closing the terminal can take (6 s at most).
    markRestarting(id, old, 60_000)
    upsertSession(id, { pid: null })
    if (!(await endTermAndWait(tid))) {
      clearRestarting(id)
      throw new HttpError(409, 'terminal_busy', 'The session’s terminal didn’t close. Try again in a moment.')
    }
    // Agents and workflows the old process ran in the background went with it.
    endSessionAgents(id)
    let started: string
    try {
      started = createSharedSession({ cwd: rec.cwd, resume: id, providerId })
    } catch (e) {
      // The old claude is gone and no new one came: the session has ended (it resumes from the
      // phone like any other), and nothing it says on its way out is passed over any more.
      clearRestarting(id)
      upsertSession(id, { state: 'ended', activity: null })
      throw e
    }
    // The old process's last word (its SessionEnd hook) may still be on its way.
    markRestarting(id, old, RESTART_GRACE_MS)
    return { id: started, forked: false }
  }
  if (tid || isLiveTerminal(id)) {
    const newId = (await shared()) ? createSharedSession({ cwd: rec.cwd, forkFrom: id, providerId, title: rec.title }) : prepareFork(id, rec.cwd, providerId, rec.title)
    return { id: newId, forked: true }
  }
  if (await shared()) return { id: createSharedSession({ cwd: rec.cwd, resume: id, providerId }), forked: false }
  adoptTerminal(id, rec.cwd)
  setManagedProvider(id, providerId)
  upsertSession(id, { providerId, model: null, blocked: null })
  return { id, forked: false }
}

/** Adopt an ended terminal session so Hypernova can continue it in place. */
function adoptTerminal(id: string, cwd: string, model?: string | null): void {
  if (getManaged(id)) return
  saveManaged({ id, cwd, providerId: 'anthropic', model: model ?? null, permissionMode: 'default', createdAt: Date.now() })
  upsertSession(id, { origin: 'hypernova' })
}

// ── Local API for the Mac app ────────────────────────────────────────────
async function local(c: Ctx): Promise<void> {
  const { req, res, url } = c
  const path = url.pathname
  const m = req.method ?? 'GET'
  if (path.startsWith('/hooks/')) {
    if (!hookPathOk(path) || m !== 'POST') throw new HttpError(404, 'not_found', 'Unknown hook.')
    const ev = await readJsonBody(req)
    return json(res, 200, await handleHook(ev, req, res))
  }
  if (path === '/qa' || path.startsWith('/qa/')) return qa(c, true)
  if (path === '/v1/health') return json(res, 200, { ok: true, protocol: PROTOCOL, name: macName() })
  const auth = String(req.headers.authorization ?? '')
  // An automation's webhook trigger, fired on this computer (git hooks, cron, CI, the `hypernova`
  // command): its own token, or the admin token. The one route here that takes another token.
  const fired = /^\/local\/automations\/([\w-]+)\/fire$/.exec(path)
  if (fired && m === 'POST') {
    const admin = safeEqual(auth, `Bearer ${adminToken}`)
    const token = /^Bearer (\S+)$/.exec(auth)?.[1] ?? ''
    if (!admin && !token) throw new HttpError(401, 'bad_token', 'Send its token: Authorization: Bearer <token>.')
    return fireAutomation(res, req, fired[1]!, admin ? null : token)
  }
  if (!safeEqual(auth, `Bearer ${adminToken}`)) throw new HttpError(401, 'unauthorized', 'Missing the admin token.')
  if (path === '/local/status') {
    // Setup shows a download or pairing code while it asks this way: the name is published for it (names.ts).
    if (url.searchParams.has('qr')) wantName()
    const integ = integrationStatus()
    const dl = downloadTarget((await publicBaseUrl()) ?? `http://10.0.2.2:${API_PORT}`)
    return json(res, 200, {
      daemonVersion: VERSION,
      apiPort: API_PORT,
      claude: { path: claudeCached(), version: claudeVersion(), sdk: sdkState() },
      // Codex as a second agent: installed, on, connected, signed in (null when it isn't installed).
      codex: codexState(),
      tailscale: await tailscaleStatus(url.searchParams.has('fresh')),
      ...integ,
      devices: listDevices(),
      connectedClients: clientCount(),
      sessions: listSessions().filter((s) => s.state !== 'ended'),
      approvals: pendingApprovals(),
      usage: getUsage(),
      projectsRoot: settings.projectsRoot,
      // Browse outside projects (files.ts): the Mac's Settings shows the same switch as the phone.
      files: filesState(),
      account: getAccount(),
      // The popover's one line: what's next, what runs (automations.ts).
      automations: automations().status(),
      // The local route (lan.ts).
      lan: lanSnapshot(null),
      // Tailscale built in (tsnet.ts): which Tailscale serves the phone, and the sign-in link while one is needed.
      tsnet: tsnetState(),
      // Where a phone gets the app right now (the base URL's page, or the local network's), and the APK's checksum to compare.
      downloadUrl: dl.url,
      // true only for this daemon's own LAN listener (bundled APK)…
      downloadLocal: dl.local,
      // …and false when that is its address with the self-signed certificate: then the checksum below is the one to compare.
      downloadTrusted: dl.trusted,
      // With the link on the local network's name: the tailnet's page too, for a phone that has Tailscale (else null).
      downloadAnywhere: dl.anywhere,
      apk: apkInfo(),
      // This computer's name under hypernova.observer and its certificate (names.ts).
      name: nameStatus(),
    })
  }
  if (path === '/local/pair/start' && m === 'POST') return json(res, 200, await startPairing(url.searchParams.has('dev'), url.searchParams.has('name')))
  if (path === '/local/pair/cancel' && m === 'POST') {
    const b = await readJsonBody(req)
    return json(res, 200, { ok: true, cancelled: cancelPairing(typeof b?.code === 'string' ? b.code : null) })
  }
  // The name (names.ts): its state, and giving it up before an uninstall.
  if (path === '/local/name' && m === 'GET') return json(res, 200, nameStatus())
  if (path === '/local/name/release' && m === 'POST') return json(res, 200, await releaseName())
  if (path === '/local/tsnet' ) return json(res, 200, tsnetState())
  if (path === '/local/tsnet/login' && m === 'POST') return json(res, 200, { ok: tsnetLogin(), tsnet: tsnetState() })
  if (path === '/local/tsnet/logout' && m === 'POST') return json(res, 200, { ok: tsnetLogout(), tsnet: tsnetState() })
  if (path === '/local/tailscale/serve' && m === 'POST') {
    try {
      const r = await enableServe()
      // The app serves now: the built-in node steps aside at once (tsnet.ts).
      await recheckSystem()
      return json(res, 200, r)
    } catch (e: any) {
      throw new HttpError(500, 'tailscale', e.message)
    }
  }
  if (path === '/local/integration/install' && m === 'POST') return json(res, 200, installIntegration())
  if (path === '/local/integration/uninstall' && m === 'POST') return json(res, 200, uninstallIntegration())
  // Telescope: the Mac app hands back the window it was asked to capture (telescope/window.ts).
  const tw = /^\/local\/telescope\/([0-9a-f]{24})$/.exec(path)
  if (tw && m === 'POST') return json(res, 200, { ok: windowAnswer(tw[1]!, await readBody(req, 64 * 1024 * 1024)) })
  // The glasses that come through a phone are listed as a device of their own (glasses.ts).
  if (path === '/local/devices') return json(res, 200, { devices: [...listDevices(), ...glassesEntries()] })
  const dev = /^\/local\/devices\/([\w-]+)$/.exec(path)
  // "Forget" on a phone's glasses: refused from now on, and the phone app is told, so it drops its
  // Lens connections too; the phone itself stays paired.
  const glassesOf = dev && m === 'DELETE' ? phoneOfGlassesId(dev[1]!) : null
  if (glassesOf) {
    const ok = removeGlasses(glassesOf)
    toPhoneApp(glassesOf, { t: 'glasses.removed' })
    return json(res, 200, { ok })
  }
  if (dev && m === 'DELETE') {
    const ok = removeDevice(dev[1]!)
    dropDevice(dev[1]!)
    return json(res, 200, { ok })
  }
  if (path === '/local/settings' && m === 'POST') {
    const b = await readJsonBody(req)
    if (b.projectsRoot && existsSync(b.projectsRoot)) settings.projectsRoot = b.projectsRoot
    saveSettings()
    // Browse outside projects (files.ts): every phone hears of it, with the sessions it opens or closes.
    if (typeof b.browseOutsideProjects === 'boolean') setBrowseOutside(b.browseOutsideProjects)
    // Codex for Hypernova (codex/client.ts): connects, and the shell's `codex` function comes with it.
    if (typeof b.codex === 'boolean') setCodexEnabled(b.codex)
    // The LAN listener starts or stops at once (lan.ts), and the name follows it (names.ts).
    if (typeof b.lanAccess === 'boolean') {
      setLanAccess(b.lanAccess)
      nameKick()
    }
    // The name: what it is has been shown (Setup, `hypernova pair`); off gives it up at once (and
    // says whether that got through); on resumes. Its certificate's authority.
    if (b.nameTermsShown === true) setNameTermsShown()
    let nameRelease = null
    if (typeof b.nameService === 'boolean') nameRelease = (await setNameService(b.nameService)).release
    if (b.acmeDirectory === null || ['staging', 'production'].includes(b.acmeDirectory) || /^https:\/\/\S+$/.test(String(b.acmeDirectory ?? ''))) {
      if (b.acmeDirectory === null) delete settings.acmeDirectory
      else settings.acmeDirectory = b.acmeDirectory
      saveSettings()
      nameKick()
    }
    // The built-in Tailscale node too (tsnet.ts); a control URL ("" for tailscale.com) restarts it.
    if (typeof b.builtInTailscale === 'boolean') await setBuiltInTailscale(b.builtInTailscale)
    if (typeof b.tailscaleControlUrl === 'string') {
      try {
        await setControlUrl(b.tailscaleControlUrl.trim() || null)
      } catch (e: any) {
        throw new HttpError(400, 'bad_control_url', e.message)
      }
    }
    return json(res, 200, nameRelease ? { ...settings, nameRelease } : settings)
  }
  return api({ ...c, local: true })
}

/**
 * On the LAN listener (lan.ts), what a connection without a certified device may still reach: a
 * health check, pairing, and the certificate route itself (a bearer token is the way to a first
 * certificate). A browser's preflight carries nothing either way.
 */
function openOnLan(method: string, path: string): boolean {
  // The download page and the APK: a phone without a tailnet gets the app here (the pairing page
  // isn't served at an address: the built-in route's code carries the app link itself). A /v1
  // preflight is answered before this; no other OPTIONS reaches a route.
  if (method === 'GET' && (path === '/download/android' || path === '/download/android/Hypernova.apk')) return true
  return (method === 'GET' && path === '/v1/health') || (method === 'POST' && (path === '/v1/pair' || path === '/v1/device/cert'))
}

/**
 * Through the name (names.ts), what a browser on the network may reach, and nothing else: the
 * download page and the APK, and the pairing page for a code being shown (checked by the route).
 * Not the API, health or pairing endpoints: any web page that knows the name could otherwise
 * read this computer's name or spend a source's wrong-code tries.
 */
const openByName = (method: string, path: string): boolean => method === 'GET' && (path === '/download/android' || path === '/download/android/Hypernova.apk' || path === '/pair')

function handler(isLocal: boolean, lan = false) {
  return async (req: IncomingMessage, res: ServerResponse) => {
    const url = new URL(req.url ?? '/', 'http://x')
    // Requests through tailscale serve carry X-Forwarded-For; only direct loopback is "local".
    const direct = !lan && !req.headers['x-forwarded-for'] && !req.headers['tailscale-user-login']
    const auth = String(req.headers.authorization ?? '')
    const token = auth.startsWith('Bearer ') ? auth.slice(7) : ''
    const device = token ? deviceForToken(token) : null
    const adminLocal = direct && token !== '' && safeEqual(token, adminToken)
    const c: Ctx = { req, res, url, local: adminLocal, deviceId: device?.id ?? null, lan, lens: viaLens(req, device) }
    const viaName = lan && lanNamed(req.socket)
    // A web client (the glasses app runs in the Even App's WebView, which enforces CORS) may call
    // the phone API from any origin: every route but health and pairing needs the bearer token,
    // which only a paired client has. No credentials mode, so a browser never sends cookies.
    // Never through the name, which serves browser pages only.
    if (!isLocal && !viaName && url.pathname.startsWith('/v1/')) {
      res.setHeader('access-control-allow-origin', '*')
      if (req.method === 'OPTIONS') return preflight(req, res)
    }
    try {
      if (viaName) {
        if (!openByName(req.method ?? 'GET', url.pathname)) throw new HttpError(404, 'not_found', 'Unknown endpoint.')
      } else if (lan) {
        // On the local network OPTIONS is a /v1 preflight only (answered above): it never reaches a
        // route, whose handlers don't look at the method (a preview, the download page).
        if (req.method === 'OPTIONS') throw new HttpError(404, 'not_found', 'Unknown endpoint.')
        // Mutual TLS: the phone's device certificate names it; the bearer token must agree.
        const certified = lanDevice(req.socket)
        if (!certified && !openOnLan(req.method ?? 'GET', url.pathname)) throw new HttpError(401, 'device_cert_required', 'This route needs your device certificate on the local network.')
        if (certified && device && certified.id !== device.id) throw new HttpError(401, 'wrong_device', 'That token belongs to another device.')
      }
      if (isLocal) await local(c)
      else await api(c)
    } catch (e: any) {
      const status = e instanceof HttpError ? e.status : typeof e?.status === 'number' ? e.status : 500
      // 501 is a feature this machine doesn't have (platform.ts): an answer, not a fault to log.
      if (status >= 500 && status !== 501) log('http error', req.method, url.pathname, e)
      if (!res.headersSent) json(res, status, { error: { code: e.code ?? 'error', message: e.message ?? 'Something went wrong.', ...(e.extra ?? {}) } })
      else res.end()
    }
  }
}

/**
 * A browser's preflight for a /v1 call (it asks before sending Authorization or a JSON body):
 * any of the API's methods with those headers, remembered for 2 hours (Chromium's most), so a
 * web client pays for it once, not on every call. No token: browsers never send one here.
 */
function preflight(req: IncomingMessage, res: ServerResponse): void {
  const headers: Record<string, string> = {
    'access-control-allow-methods': 'GET, POST, PUT, DELETE',
    'access-control-allow-headers': 'Authorization, Content-Type',
    'access-control-max-age': '7200',
  }
  // Chromium also asks before a public page reaches a private address (the tailnet's 100.x).
  if (req.headers['access-control-request-private-network'] === 'true') headers['access-control-allow-private-network'] = 'true'
  res.writeHead(204, headers)
  res.end()
}

// Open sockets per paired phone, so forgetting a phone cuts it off at once (its live terminal too),
// and the device certificate each one came in with over the LAN listener.
const byDevice = new Map<string, Set<import('ws').WebSocket>>()
const certOf = new WeakMap<import('ws').WebSocket, string>()
function track(device: string | null, ws: import('ws').WebSocket, cert?: string): void {
  if (!device) return
  let set = byDevice.get(device)
  if (!set) byDevice.set(device, (set = new Set()))
  set.add(ws)
  if (cert) certOf.set(ws, cert)
  ws.on('close', () => set.delete(ws))
}
function dropDevice(device: string): void {
  for (const ws of byDevice.get(device) ?? []) ws.close(4001, 'unpaired')
  byDevice.delete(device)
}
/** A message for a phone app's own live feeds, never the glasses' feeds through it (glasses.ts). */
function toPhoneApp(device: string, msg: object): void {
  const s = JSON.stringify(msg)
  for (const ws of byDevice.get(device) ?? []) if (ws.readyState === ws.OPEN && !isGlassesFeed(ws)) ws.send(s)
}
/** A new device certificate: the sockets opened with an older one close (the app comes back with the new one). */
function dropOldCert(device: string, fingerprint: string): void {
  for (const ws of byDevice.get(device) ?? []) {
    const cert = certOf.get(ws)
    if (cert && cert !== fingerprint) ws.close(4002, 'certificate replaced')
  }
}

/** Start the servers (the LAN listener too, lan.ts); the returned function closes them (for tests). */
export function startServers(): () => Promise<void> {
  // A 30 MB attachment over a slow link can take a while: 15 minutes for a whole request.
  const apiServer = createServer({ requestTimeout: 15 * 60_000 }, handler(false))
  const wss = new WebSocketServer({ noServer: true, perMessageDeflate: true })
  // `lan`: on the LAN listener, only a connection with a certified device gets a socket at all.
  const onUpgrade = (lan: boolean) => (req: IncomingMessage, socket: import('node:stream').Duplex, head: Buffer) => {
    const url = new URL(req.url ?? '/', 'http://x')
    const certified = lan ? lanDevice(socket) : null
    if (lan && !certified) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n')
      socket.destroy()
      return
    }
    // A phone's terminal view: a single-use ticket from POST /v1/sessions/<id>/terminal.
    const tm = /^\/v1\/terms\/([\w-]+)\/ws$/.exec(url.pathname)
    if (tm) {
      const t = redeemTicket(url.searchParams.get('ticket') ?? '')
      // Over the LAN, the ticket must be the certified device's own.
      if (!t || t.term !== tm[1] || (certified && t.device !== certified.id)) {
        socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n')
        socket.destroy()
        return
      }
      wss.handleUpgrade(req, socket, head, (ws) => {
        track(t.device, ws, certified?.certFingerprint)
        relayPhone(t.term, ws, Number(url.searchParams.get('cols')) || 60, Number(url.searchParams.get('rows')) || 30)
      })
      return
    }
    const auth = String(req.headers.authorization ?? '')
    const token = auth.startsWith('Bearer ') ? auth.slice(7) : (url.searchParams.get('token') ?? '')
    const direct = !lan && !req.headers['x-forwarded-for']
    let device = token ? deviceForToken(token) : null
    // The certificate and the token must be one device's.
    if (certified && device && certified.id !== device.id) device = null
    const admin = !device && direct && !!token && safeEqual(token, adminToken)
    // The glasses through a phone app (glasses.ts): refused once the Mac removed them; their feeds
    // are kept apart from the phone's own, so removing them closes theirs alone.
    const glasses = viaLens(req, device)
    if (glasses && device && glassesSeen(device, req) === 'removed') {
      socket.write('HTTP/1.1 403 Forbidden\r\n\r\n')
      socket.destroy()
      return
    }
    const keep = (ws: import('ws').WebSocket) => {
      track(device?.id ?? null, ws, certified?.certFingerprint)
      if (glasses && device) trackGlasses(device.id, ws)
    }
    // Speech to text: the glasses' microphone, relayed to Soniox (stt.ts). One stream per device, the
    // glasses through a phone counting apart from the phone's own microphone.
    if (url.pathname === '/v1/stt/ws' && (device || admin)) return sttUpgrade(req, socket, head, device ? (glasses ? lensWho(device.id) : device.id) : 'mac', keep)
    const ok = url.pathname === '/v1/ws' && token && (device || admin)
    if (!ok) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n')
      socket.destroy()
      return
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      keep(ws)
      // The Mac app's own feed: it brings the Lens guide forward when asked (guide.ts).
      if (admin && url.searchParams.get('client') === 'mac') macFeed(ws)
      const remove = addClient({ send: (s) => ws.readyState === ws.OPEN && ws.send(s), phone: !!device })
      ws.send(JSON.stringify({ t: 'snapshot', snapshot: snapshot(device?.id ?? null) }))
      // A phone or the glasses on the live feed keep the Mac awake While working (the Mac app's own
      // feed doesn't). After the snapshot, which is always a client's first message.
      const awake = device ? awakeClient() : null
      // The glasses on the live feed: an automation's read-aloud goes to them, else to the phone.
      const lens = device?.client === 'lens' || glasses ? lensConnected() : null
      const ping = setInterval(() => ws.readyState === ws.OPEN && ws.send('{"t":"ping"}'), 25_000)
      ws.on('close', () => {
        clearInterval(ping)
        remove()
        awake?.()
        lens?.()
      })
      ws.on('error', () => {})
      ws.on('message', () => {})
      // A phone app offers to play the glasses' speech on it, and says how a play goes (readaloud.ts).
      speechFeed(device ? (glasses ? lensWho(device.id) : device.id) : null, ws, req)
    })
  }
  apiServer.on('upgrade', onUpgrade(false))
  apiServer.listen(API_PORT, '127.0.0.1', () => log(`api on 127.0.0.1:${API_PORT}`))
  // The same API over HTTPS on the local network, when the setting allows (lan.ts).
  startLan(handler(false, true), onUpgrade(true))
  const localServer = createServer(handler(true))
  localServer.listen(LOCAL_PORT, '127.0.0.1', () => log(`local api on 127.0.0.1:${LOCAL_PORT}`))
  apiServer.on('error', (e) => {
    log('api server error', e)
    process.exit(1)
  })
  localServer.on('error', (e) => {
    log('local server error', e)
    process.exit(1)
  })
  return async () => {
    for (const ws of wss.clients) ws.terminate()
    apiServer.closeAllConnections()
    localServer.closeAllConnections()
    await Promise.all([new Promise((r) => apiServer.close(r)), new Promise((r) => localServer.close(r)), stopLan()])
  }
}
