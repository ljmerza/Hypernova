// The admin feeds (`/v1/ws?client=mac` with the admin token; the name is from the Mac app, which is gone), for what is meant for
// them alone and not the phones: who is speaking (readaloud.ts).
import type { WebSocket } from 'ws'

const macSockets = new Set<WebSocket>()
export function macFeed(ws: WebSocket): void {
  macSockets.add(ws)
  ws.once('close', () => macSockets.delete(ws))
}

/** A message for the admin feeds alone, not the phones: how many it reached. */
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
