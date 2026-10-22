// The glasses through the phone (docs/PROTOCOL.md, "The glasses through the phone"). Lens on the
// Even glasses no longer has to pair on its own: it talks to the Hypernova app on the same phone
// over loopback, and the app carries its requests here with the phone's own token and one marker,
// `X-Hypernova-Via: lens`. The daemon honours the marker only from a paired phone app: never from
// the Mac app's admin token, never from a device that paired as Lens itself. That keeps what the
// glasses did as a device of their own working: the Mac's device list says "Even glasses", the Lens
// guide opens the first time they come, and the glasses' read-aloud plays on their own phone.
//
// The glasses are kept with the phone they come through (`Device.glasses`), so forgetting the phone
// forgets them too. "Forget" on the Mac marks them removed rather than deleting them: otherwise the
// next request through the phone would simply bring them back. The phone app clears that when its
// owner allows glasses again (PUT /v1/devices/me/glasses).
import type { IncomingMessage } from 'node:http'
import type { WebSocket } from 'ws'
import { getDevice, listDevices, updateDevice, type Device, type Glasses } from './store.ts'
import { broadcast } from './hub.ts'
import { lensPaired } from './guide.ts'
import { log } from './util.ts'

export const VIA_HEADER = 'x-hypernova-via'
const VERSION_HEADER = 'x-hypernova-lens-version'
/** How the Mac's device list names the glasses of a phone: never the id of a real device (those are 11 characters). */
const PREFIX = 'lens-'
/** Seen-at is kept in memory and written at most this often, like a device's lastSeenAt. */
const SEEN_WRITE_MS = 60_000

/**
 * The request comes from the glasses through this phone app: the marker, on a request whose token
 * is a paired phone app's. A device that paired as Lens itself already is the glasses; the admin
 * token is the Mac app.
 */
export function viaLens(req: IncomingMessage, device: Device | null): boolean {
  if (!device || (device.client ?? 'android') === 'lens') return false
  return String(req.headers[VIA_HEADER] ?? '').trim().toLowerCase() === 'lens'
}

/** Who the glasses of [phoneId] are where speech keeps its plays and streams apart from the phone's own. */
export const lensWho = (phoneId: string) => `${phoneId}:lens`
/** The phone a [lensWho] belongs to; null for any other asker. */
export const phoneOfWho = (who: string) => (who.endsWith(':lens') ? who.slice(0, -5) : null)

/** The id the Mac's device list shows for a phone's glasses, and back. */
export const glassesId = (phoneId: string) => PREFIX + phoneId
export function phoneOfGlassesId(id: string): string | null {
  if (!id.startsWith(PREFIX) || getDevice(id)) return null
  const phone = getDevice(id.slice(PREFIX.length))
  return phone?.glasses ? phone.id : null
}

function cleanVersion(v: unknown): string | undefined {
  const s = typeof v === 'string' ? v.trim() : ''
  return /^[\w.+-]{1,40}$/.test(s) ? s : undefined
}

/** The glasses came (back): the Mac's list changes, and the Lens guide opens unless it was finished. */
function arrived(phone: Device): void {
  log('glasses through', phone.id)
  broadcast({ t: 'devices' })
  lensPaired()
}

export type Arrival = 'ok' | 'removed'

/**
 * Every request (and live feed) from the glasses through [phone]: 'removed' when the Mac forgot
 * them (the request is refused); else they're noted as seen, and the first time as arrived.
 */
export function glassesSeen(phone: Device, req: IncomingMessage): Arrival {
  const g = phone.glasses
  const version = cleanVersion(req.headers[VERSION_HEADER])
  if (g?.removedAt) return 'removed'
  const now = Date.now()
  if (!g) {
    updateDevice(phone.id, (d) => (d.glasses = { since: now, seenAt: now, ...(version ? { appVersion: version } : {}) }))
    arrived(phone)
    return 'ok'
  }
  if (now - g.seenAt > SEEN_WRITE_MS || (version && version !== g.appVersion)) {
    updateDevice(phone.id, (d) => {
      if (!d.glasses) return
      d.glasses.seenAt = now
      if (version) d.glasses.appVersion = version
    })
  }
  return 'ok'
}

/**
 * `PUT /v1/devices/me/glasses` (the phone app, after its owner allowed Lens on it): the glasses are
 * this phone's again, also after the Mac forgot them.
 */
export function allowGlasses(phoneId: string, body: any): Glasses | null {
  const phone = getDevice(phoneId)
  if (!phone) return null
  const before = phone.glasses
  const now = Date.now()
  const version = cleanVersion(body?.appVersion) ?? before?.appVersion
  updateDevice(phoneId, (d) => {
    d.glasses = { since: before && !before.removedAt ? before.since : now, seenAt: now, ...(version ? { appVersion: version } : {}) }
  })
  if (!before || before.removedAt) arrived(phone)
  return getDevice(phoneId)?.glasses ?? null
}

/** `DELETE /v1/devices/me/glasses` (the phone app: its owner disconnected the last Lens). */
export function dropGlasses(phoneId: string): boolean {
  const had = !!getDevice(phoneId)?.glasses
  if (!had) return false
  updateDevice(phoneId, (d) => delete d.glasses)
  closeGlasses(phoneId, 4001, 'glasses disconnected')
  broadcast({ t: 'devices' })
  return true
}

/**
 * "Forget" on the Mac for a phone's glasses: refused from now on (403 `glasses_removed`), their
 * live feeds closed, until the phone allows them again. False when there were none.
 */
export function removeGlasses(phoneId: string): boolean {
  const g = getDevice(phoneId)?.glasses
  if (!g || g.removedAt) return false
  updateDevice(phoneId, (d) => {
    if (d.glasses) d.glasses.removedAt = Date.now()
  })
  closeGlasses(phoneId, 4001, 'glasses removed')
  broadcast({ t: 'devices' })
  return true
}

/** Glasses that come through a phone and weren't forgotten (automations' "on my glasses"). */
export const anyGlasses = () => listDevices().some((d) => !!d.glasses && !d.glasses.removedAt)

/**
 * The glasses as the Mac's device list shows them (`GET /local/devices`): one entry per phone that
 * carries glasses, with `client: "lens"` so the Mac app names them "Even glasses", and `via`, the
 * phone they come through.
 */
export function glassesEntries(): Array<Record<string, unknown>> {
  return listDevices()
    .filter((d) => d.glasses && !d.glasses.removedAt)
    .map((d) => ({
      id: glassesId(d.id),
      name: `Even glasses · ${d.name}`,
      model: 'Even G2 · Hypernova Lens',
      client: 'lens',
      via: d.id,
      createdAt: d.glasses!.since,
      lastSeenAt: d.glasses!.seenAt,
      ...(d.glasses!.appVersion ? { appVersion: d.glasses!.appVersion } : {}),
    }))
}

// ── The glasses' live feeds, by phone ───────────────────────────────────

const feeds = new Map<string, Set<WebSocket>>()

/** A live feed (`/v1/ws`, `/v1/stt/ws`) from the glasses through [phoneId]: closed when they're removed. */
export function trackGlasses(phoneId: string, ws: WebSocket): void {
  let set = feeds.get(phoneId)
  if (!set) feeds.set(phoneId, (set = new Set()))
  set.add(ws)
  ws.on('close', () => set.delete(ws))
}

/** Is this socket the glasses' (so a message meant for the phone app itself skips it)? */
export const isGlassesFeed = (ws: WebSocket) => [...feeds.values()].some((s) => s.has(ws))

function closeGlasses(phoneId: string, code: number, reason: string): void {
  for (const ws of feeds.get(phoneId) ?? []) ws.close(code, reason)
  feeds.delete(phoneId)
}
