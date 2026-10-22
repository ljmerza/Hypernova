// Workflows' outputs (docs/PROTOCOL.md, "Automations", "Workflows", Outputs): a webhook's secret
// checked, the request each preset sends, and a file output's line. Pure: the engine sends them
// through its AutoIO (post, append).
import { isIP } from 'node:net'
import { bad, type OutputSecret, type Run, type WebhookPreset, type WebhookRequest } from './contract.ts'

export const PRESETS: WebhookPreset[] = ['ntfy', 'slack', 'discord', 'telegram', 'post']
const SLACK = /^hooks\.slack\.com$/i
const DISCORD = /^(?:discord|discordapp)\.com$/i
const BOT_TOKEN = /^\d{3,20}:[A-Za-z0-9_-]{20,100}$/
const CHAT = /^(?:-?\d{1,20}|@[A-Za-z][A-Za-z0-9_]{3,64})$/

// ── Where a webhook may go ────────────────────────────────────────────────
/** An IPv4 address's four bytes (a plain dotted quad only), or null. */
function v4(s: string): number[] | null {
  const p = s.split('.')
  if (p.length !== 4 || !p.every((x) => /^\d{1,3}$/.test(x) && Number(x) <= 255)) return null
  return p.map(Number)
}
/** An IPv6 address's eight 16-bit groups (a dotted IPv4 tail allowed), or null. */
function v6(s: string): number[] | null {
  let text = s.toLowerCase()
  const last = text.lastIndexOf(':')
  if (last < 0) return null
  if (text.slice(last + 1).includes('.')) {
    const b = v4(text.slice(last + 1))
    if (!b) return null
    text = `${text.slice(0, last + 1)}${((b[0]! << 8) | b[1]!).toString(16)}:${((b[2]! << 8) | b[3]!).toString(16)}`
  }
  const halves = text.split('::')
  if (halves.length > 2) return null
  const part = (h: string) => (h ? h.split(':') : [])
  const head = part(halves[0]!)
  const tail = halves.length === 2 ? part(halves[1]!) : []
  const fill = halves.length === 2 ? 8 - head.length - tail.length : 0
  if (fill < 0 || (halves.length === 1 && head.length !== 8)) return null
  const groups = [...head, ...Array(fill).fill('0'), ...tail]
  if (groups.length !== 8 || !groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) return null
  return groups.map((g) => parseInt(g, 16))
}
/** This network, private, CGNAT and the tailnet, loopback, link-local and metadata, test, multicast, reserved. */
function private4([a, b, c]: number[]): boolean {
  return (
    a === 0 || a === 10 || a === 127 || a! >= 224 ||
    (a === 100 && (b! & 0xc0) === 64) || // 100.64.0.0/10: carrier-grade NAT, Tailscale's addresses
    (a === 169 && b === 254) || // link-local, cloud metadata (169.254.169.254)
    (a === 172 && (b! & 0xf0) === 16) ||
    (a === 192 && b === 168) ||
    (a === 192 && b === 0 && (c === 0 || c === 2)) || // IETF assignments (192.0.0.192: a metadata address too), TEST-NET-1
    (a === 192 && b === 88 && c === 99) ||
    (a === 198 && (b === 18 || b === 19)) || // benchmarking
    (a === 198 && b === 51 && c === 100) ||
    (a === 203 && b === 0 && c === 113)
  )
}
function private6(g: number[]): boolean {
  const z = (from: number, to: number) => g.slice(from, to).every((x) => x === 0)
  const inner = (hi: number, lo: number) => private4([hi >> 8, hi & 0xff, lo >> 8, lo & 0xff])
  // IPv4 inside IPv6 is judged as that IPv4: mapped (::ffff:a.b.c.d), compatible (::a.b.c.d),
  // translated (::ffff:0:a.b.c.d), NAT64 (64:ff9b::a.b.c.d) and 6to4 (2002:aabb:ccdd::).
  if (z(0, 5) && g[5] === 0xffff) return inner(g[6]!, g[7]!)
  if (z(0, 6)) return inner(g[6]!, g[7]!) // ::a.b.c.d; :: and ::1 are 0.0.0.0 and 0.0.0.1 so
  if (z(0, 4) && g[4] === 0xffff && g[5] === 0) return inner(g[6]!, g[7]!)
  if (g[0] === 0x64 && g[1] === 0xff9b && z(2, 6)) return inner(g[6]!, g[7]!)
  if (g[0] === 0x2002) return inner(g[1]!, g[2]!)
  // Only global unicast (2000::/3) goes out: not unique-local (fc00::/7: also fd00:ec2::254, a
  // metadata address), link-local, site-local or multicast; and not Teredo or documentation there.
  if ((g[0]! & 0xe000) !== 0x2000) return true
  return (g[0] === 0x2001 && (g[1] === 0 || g[1] === 0xdb8))
}
/**
 * An address a webhook never goes to: this computer, the local network, the tailnet (CGNAT),
 * link-local and cloud metadata, unique-local, multicast, reserved and documentation ranges; IPv4
 * inside IPv6 judged as IPv4. Anything that isn't an address counts as one (never sent to).
 */
export function privateAddress(ip: string): boolean {
  const s = ip.trim().replace(/^\[|\]$/g, '')
  // A zone (fe80::1%en0) is link-local.
  if (s.includes('%')) return true
  if (isIP(s) === 4) return private4(v4(s) ?? [0])
  if (isIP(s) === 6) {
    const g = v6(s)
    return !g || private6(g)
  }
  return true
}
/** A host as compared: lower case, without an IPv6 address's brackets or a final dot. */
export const hostKey = (h: string) => h.trim().toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '')
/**
 * The hosts a webhook may reach on a private address (settings.json `webhookPrivateHosts`: a
 * self-hosted ntfy on the local network or the tailnet), as written in its URL.
 */
export const hostAllowed = (list: readonly string[] | null | undefined, host: string) => (list ?? []).some((h) => typeof h === 'string' && hostKey(h) === hostKey(host))
/** A URL's host that is this computer or a private address as it stands (a name is judged when it's sent, after DNS). */
export function privateHost(host: string): boolean {
  const h = hostKey(host)
  return h === 'localhost' || h.endsWith('.localhost') || (isIP(h) !== 0 && privateAddress(h))
}

/**
 * A webhook output's secret as sent in a save, checked ([at]: its field's path, "outputs[0]").
 * Returns the secret to keep, or null when none was sent (it keeps the one it has). [allowed]: the
 * hosts that may be private (settings.json `webhookPrivateHosts`).
 */
export function checkSecret(preset: WebhookPreset, v: { url?: unknown; botToken?: unknown; chatId?: unknown }, at: string, allowed: readonly string[] = []): OutputSecret | null {
  if (preset === 'telegram') {
    const token = typeof v.botToken === 'string' ? v.botToken.trim() : ''
    const chat = typeof v.chatId === 'string' || typeof v.chatId === 'number' ? String(v.chatId).trim() : ''
    if (!token && !chat) return null
    if (!BOT_TOKEN.test(token)) throw bad('bad_bot_token', 'That isn’t a bot token as BotFather gives it (123456789:AA…).', `${at}.botToken`)
    if (!CHAT.test(chat)) throw bad('bad_chat', 'A chat id is a number, or @ and a channel’s name.', `${at}.chatId`)
    return { botToken: token, chatId: chat }
  }
  const raw = typeof v.url === 'string' ? v.url.trim() : ''
  if (!raw) return null
  let u: URL
  try {
    u = new URL(raw)
  } catch {
    throw bad('bad_url', 'That isn’t a web address.', `${at}.url`)
  }
  if (u.protocol === 'http:') throw bad('insecure_url', 'Use an https address: a webhook’s address is its key.', `${at}.url`)
  if (u.protocol !== 'https:' || !u.hostname || u.username || u.password || raw.length > 2000) throw bad('bad_url', 'That isn’t an https address.', `${at}.url`)
  if (privateHost(u.hostname) && !hostAllowed(allowed, u.hostname)) throw bad('bad_url', 'That address is this computer or a private network: webhooks go to the internet only.', `${at}.url`)
  if (preset === 'slack' && !(SLACK.test(u.hostname) && u.pathname.startsWith('/services/'))) throw bad('bad_url', 'A Slack webhook’s address starts https://hooks.slack.com/services/.', `${at}.url`)
  if (preset === 'discord' && !(DISCORD.test(u.hostname) && u.pathname.startsWith('/api/webhooks/'))) throw bad('bad_url', 'A Discord webhook’s address starts https://discord.com/api/webhooks/.', `${at}.url`)
  if (preset === 'ntfy' && u.pathname.replace(/\/+$/, '').split('/').filter(Boolean).length !== 1) throw bad('bad_url', 'An ntfy address is the topic’s: https://ntfy.sh/<topic>.', `${at}.url`)
  return { url: u.toString() }
}

/** What the card shows of a secret: the host, and its last 4 characters. */
export function secretHint(preset: WebhookPreset, s: OutputSecret | null): { host: string; last4: string } {
  if (!s) return { host: '', last4: '' }
  if (preset === 'telegram') return { host: 'api.telegram.org', last4: (s.botToken ?? '').slice(-4) }
  let host = ''
  try {
    host = new URL(s.url ?? '').hostname
  } catch {}
  return { host, last4: (s.url ?? '').replace(/\/+$/, '').slice(-4) }
}

/**
 * The request an output sends: [name] the automation's, [text] what it says (masked already). The
 * text may carry outside words (a PR's title, a file's name): JSON carries them as data, Slack gets
 * its control characters escaped (no `<!channel>` or disguised link) and Discord mentions nobody.
 */
export function webhookRequest(preset: WebhookPreset, s: OutputSecret, name: string, text: string, run: Pick<Run, 'id' | 'state' | 'reason' | 'startedAt' | 'endedAt' | 'result'> & { firedBy: string; automationId: string }): WebhookRequest {
  const json = { 'content-type': 'application/json' }
  const said = `${name}: ${text}`
  switch (preset) {
    case 'ntfy':
      // ntfy takes the message as the body; a header can't carry every character, so the title is cut to ASCII.
      return { url: s.url!, headers: { 'content-type': 'text/plain; charset=utf-8', title: name.replace(/[^\x20-\x7e]/g, '').slice(0, 120) || 'Hypernova' }, body: text }
    case 'slack':
      return { url: s.url!, headers: json, body: JSON.stringify({ text: said.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') }) }
    case 'discord':
      return { url: s.url!, headers: json, body: JSON.stringify({ content: said.slice(0, 2000), allowed_mentions: { parse: [] } }) }
    case 'telegram':
      return { url: `https://api.telegram.org/bot${s.botToken}/sendMessage`, headers: json, body: JSON.stringify({ chat_id: s.chatId, text: said.slice(0, 4096) }) }
    case 'post':
      return {
        url: s.url!,
        headers: json,
        body: JSON.stringify({ automation: { id: run.automationId, name }, run: { id: run.id, state: run.state, reason: run.reason, firedBy: run.firedBy, startedAt: run.startedAt, endedAt: run.endedAt }, text, result: run.result }),
      }
  }
}

/** A file output's line: `- 2026-10-18 09:02 Morning brief: <text on one line>`. */
export function fileLine(day: string, clock: string, name: string, text: string): string {
  const one = text.replace(/\s+/g, ' ').trim().slice(0, 2000)
  return `- ${day} ${clock} ${name}: ${one}\n`
}
