// `hypernova client-logs`: the debug logs clients sent to this server, newest first, each with its
// path (docs/LINUX.md; docs/PROTOCOL.md, "Client logs"). It reads the folder and the device list, so
// it works with the daemon stopped. It shows where they are and never what's in them: they're
// untrusted text from a device.
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

export type ClientLogRow = { device: string; deviceName: string | null; path: string; name: string; size: number; at: number }

/** A paired device's name is what that device said it was: no control characters on this terminal. */
const plain = (s: string) => s.replace(/[\x00-\x1f\x7f-\x9f\u2028\u2029\u202a-\u202e\u2066-\u2069]/g, ' ').trim()

function deviceNames(support: string): Map<string, string> {
  const names = new Map<string, string>()
  try {
    const list = JSON.parse(readFileSync(join(support, 'devices.json'), 'utf8'))
    for (const d of Array.isArray(list) ? list : list?.devices ?? []) {
      if (typeof d?.id === 'string' && typeof d?.name === 'string') names.set(d.id, plain(d.name))
    }
  } catch {}
  return names
}

/** Every kept log under [support], newest first. A stopped upload (`.part`) isn't one. */
export function listClientLogs(support: string): ClientLogRow[] {
  const root = join(support, 'client-logs')
  const names = deviceNames(support)
  const rows: ClientLogRow[] = []
  let devices: string[] = []
  try {
    devices = readdirSync(root)
  } catch {}
  for (const device of devices) {
    const dir = join(root, device)
    let files: string[] = []
    try {
      files = readdirSync(dir)
    } catch {}
    for (const name of files) {
      if (!name.endsWith('.log')) continue
      const path = join(dir, name)
      try {
        const s = statSync(path)
        rows.push({ device, deviceName: names.get(device) ?? null, path, name, size: s.size, at: s.mtimeMs })
      } catch {}
    }
  }
  return rows.sort((a, b) => b.at - a.at || b.name.localeCompare(a.name))
}

const size = (n: number) => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(0)} KB` : `${(n / (1024 * 1024)).toFixed(1)} MB`)

/** The lines the command prints. */
export function clientLogLines(rows: ClientLogRow[], support: string): string[] {
  if (!rows.length) return [`No client logs yet in ${join(support, 'client-logs')}.`, 'On a phone or the desktop app: Settings, Debug logging, Send logs.']
  return rows.map((r) => `${new Date(r.at).toLocaleString()}  ${r.deviceName ?? r.device}  ${size(r.size)}\n  ${r.path}`)
}
