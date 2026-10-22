// The Lens guide (docs/PROTOCOL.md, "The Lens guide"): a short tour of Hypernova Lens (what it's
// for, the gestures, voice) that the Mac opens when glasses pair, and the Lens phone page shows
// too. Its place is kept here, so both screens show the same step, and "Show on my Mac" on the
// phone brings the Mac's window forward through the Mac app's live feed.
import { join } from 'node:path'
import type { WebSocket } from 'ws'
import { SUPPORT, readJson, writeJson } from './util.ts'
import { broadcast } from './hub.ts'

export const GUIDE_STEPS = ['about', 'gestures', 'voice'] as const
export type GuideStep = (typeof GUIDE_STEPS)[number]
/** `seen`: shown at least once, here or on the phone. `done`: finished or closed for good. */
export type Guide = { step: GuideStep; seen: boolean; done: boolean }

const FILE = join(SUPPORT, 'guide.json')
const fail = (status: number, code: string, message: string) => Object.assign(new Error(message), { status, code })

let guide: Guide = (() => {
  const g = readJson<Partial<Guide> | null>(FILE, null)
  return { step: GUIDE_STEPS.includes(g?.step as GuideStep) ? (g!.step as GuideStep) : 'about', seen: g?.seen === true, done: g?.done === true }
})()

export const guideState = (): Guide => ({ ...guide })

/** A change from either screen; everyone hears it (`{t:"guide"}`) only when something moved. */
export function setGuide(patch: { step?: unknown; seen?: unknown; done?: unknown }): Guide {
  const next = { ...guide }
  if (patch.step !== undefined) {
    if (!GUIDE_STEPS.includes(patch.step as GuideStep)) throw fail(400, 'bad_step', 'That isn’t a step of the guide.')
    next.step = patch.step as GuideStep
  }
  if (typeof patch.seen === 'boolean') next.seen = patch.seen
  if (typeof patch.done === 'boolean') next.done = patch.done
  // Opening the guide again starts where it makes sense, and shows it again.
  if (patch.done === false && patch.step === undefined && guide.done) next.step = 'about'
  if (JSON.stringify(next) !== JSON.stringify(guide)) {
    guide = next
    writeJson(FILE, guide)
    broadcast({ t: 'guide', guide: guideState() })
  }
  return guideState()
}

/** The Mac app's live feeds (`/v1/ws?client=mac` with the admin token): who would hear "show". */
let macFeeds = 0
const macSockets = new Set<WebSocket>()
export function macFeed(ws: WebSocket): void {
  macFeeds++
  macSockets.add(ws)
  ws.once('close', () => {
    macFeeds--
    macSockets.delete(ws)
  })
}

/**
 * A message for the Mac app alone, not the phones (Telescope asking it for an app's window):
 * how many Mac apps it reached.
 */
export function toMacApps(msg: object): number {
  const s = JSON.stringify(msg)
  let n = 0
  for (const ws of macSockets) {
    if (ws.readyState !== ws.OPEN) continue
    try {
      ws.send(s)
      n++
    } catch {}
  }
  return n
}

/** Asks the Mac app to bring its guide window forward; answers how many Mac apps heard it. */
export function showOnMac(reason: 'paired' | 'phone', step?: unknown): number {
  if (step !== undefined) setGuide({ step })
  broadcast({ t: 'guide.show', guide: guideState(), reason })
  return macFeeds
}

/** Glasses just paired: the Mac opens the guide, unless it was finished before. */
export function lensPaired(): void {
  if (!guide.done) showOnMac('paired')
}

/** The REST routes under /v1/guide (also on the Mac's port with the admin token). */
export async function guideApi(path: string, method: string, body: () => Promise<any>): Promise<unknown> {
  if (path === '/v1/guide' && method === 'GET') return { guide: guideState() }
  if (path === '/v1/guide' && method === 'POST') {
    const b = (await body()) ?? {}
    return { guide: setGuide({ step: b.step, seen: b.seen, done: b.done }) }
  }
  if (path === '/v1/guide/show' && method === 'POST') {
    const b = (await body()) ?? {}
    const macs = showOnMac('phone', b.step)
    return { guide: guideState(), macs }
  }
  throw fail(404, 'not_found', 'Unknown endpoint.')
}
