// Claude Code's /mcp as plain text (2.1.317, real screens in test/fixtures/mcp-screens). mcp-session.ts opens it in a
// session's shared terminal, the only way to ask a running terminal session about its own connections.
//
// A server's page also prints its whole command or URL and the path of its config file, so nothing here keeps a line it
// doesn't recognise: a row gives a name, a status and a count; a page gives its status and its options' words.
//
//   Manage MCP servers
//   7 servers
//     Project MCPs (/path/.mcp.json)
//   ❯ ✔ projsrv     1 tool
//     Local MCPs (/path/.claude.json [project: /path])
//     ✘ broken
//     … slowstart
//     ⚠ webauth     needs authentication
//     ◯ off
//     ↓ 17 more
import { maskText } from './mcp-view.ts'

export type McpRowStatus = 'connected' | 'failed' | 'needs-auth' | 'pending' | 'disabled'
export type McpScreenRow = { name: string; status: McpRowStatus; source: string; toolCount: number | null; /** The screen was too narrow for the whole name. */ cut: boolean; cursor: boolean }
export type McpListScreen = { rows: McpScreenRow[]; total: number | null; moreBelow: number; moreAbove: number }
export type McpPageScreen = { status: McpRowStatus | null; options: string[]; cursor: number }
export type McpToolLine = { name: string; cut: boolean; cursor: boolean; readOnly?: boolean; destructive?: boolean; openWorld?: boolean }
export type McpToolsScreen = { tools: McpToolLine[]; total: number | null; moreBelow: number; moreAbove: number }

const GLYPHS: Record<string, McpRowStatus> = { '✔': 'connected', '✘': 'failed', '⚠': 'needs-auth', '…': 'pending', '◯': 'disabled' }
const ROW = /^\s*(❯\s+)?([✔✘⚠…◯])\s+(\S.*?)\s*$/u
const SECTION = /^\s*(Active agent|Agent|Built-in|Enterprise|Local|Managed|Project|User) MCPs\b/
const SOURCES: Record<string, string> = { 'Active agent': 'agent', Agent: 'agent', 'Built-in': 'builtin', Enterprise: 'managed', Local: 'local', Managed: 'managed', Project: 'project', User: 'user' }
const CUT = '…'
const moreOf = (line: string, arrow: '↓' | '↑') => {
  const m = new RegExp(`^\\s*${arrow}\\s*(\\d+) more\\s*$`).exec(line)
  return m ? Number(m[1]) : 0
}

/** The list of servers on [text], or null when it isn't showing it. */
export function readMcpList(text: string): McpListScreen | null {
  const lines = text.split('\n')
  const at = lines.findIndex((l) => /^\s*Manage MCP servers\s*$/.test(l))
  if (at < 0) return null
  const rows: McpScreenRow[] = []
  let source = 'unknown'
  let total: number | null = null
  let moreBelow = 0
  let moreAbove = 0
  for (const line of lines.slice(at + 1)) {
    const count = /^\s*(\d+) servers?\s*$/.exec(line)
    if (count) {
      total = Number(count[1])
      continue
    }
    const section = SECTION.exec(line)
    if (section) {
      source = SOURCES[section[1]!] ?? 'unknown'
      continue
    }
    moreBelow = moreOf(line, '↓') || moreBelow
    moreAbove = moreOf(line, '↑') || moreAbove
    const m = ROW.exec(line)
    if (!m) continue
    const [name, trail] = m[3]!.split(/\s{2,}/)
    const tools = /^(\d+) tools?$/.exec(trail ?? '')
    rows.push({ name: name!, status: GLYPHS[m[2]!]!, source, toolCount: tools ? Number(tools[1]) : null, cut: name!.endsWith(CUT), cursor: !!m[1] })
  }
  return { rows, total, moreBelow, moreAbove }
}

/** `/mcp` with nothing to manage: it says so and opens nothing. */
export const noMcpServers = (text: string) => /No MCP servers configured\./.test(text)

/** A server's page, or null. Only its status and its options; its command, URL and file aren't read. */
export function readMcpPage(text: string): McpPageScreen | null {
  const lines = text.split('\n')
  const at = lines.findIndex((l) => /^\s*Status:\s+\S/.test(l))
  if (at < 0 || !/\bEsc to back\b/.test(text)) return null
  const glyph = /^\s*Status:\s+(\S)/u.exec(lines[at]!)?.[1] ?? ''
  const options: string[] = []
  let cursor = -1
  for (const line of lines.slice(at + 1)) {
    const m = /^\s*(❯\s+)?\d+\.\s+(\S.*?)\s*$/u.exec(line)
    if (!m) continue
    if (m[1]) cursor = options.length
    options.push(m[2]!)
  }
  return { status: GLYPHS[glyph] ?? null, options, cursor }
}

/** A server's tools, or null. Each with the hints the screen gives. */
export function readMcpTools(text: string): McpToolsScreen | null {
  const lines = text.split('\n')
  const at = lines.findIndex((l) => /^\s*Tools for \S/.test(l))
  if (at < 0 || !/\bEsc to back\b/.test(text)) return null
  const tools: McpToolLine[] = []
  let total: number | null = null
  let moreBelow = 0
  let moreAbove = 0
  for (const line of lines.slice(at + 1)) {
    const count = /^\s*(\d+) tools?\s*$/.exec(line)
    if (count) {
      total = Number(count[1])
      continue
    }
    moreBelow = moreOf(line, '↓') || moreBelow
    moreAbove = moreOf(line, '↑') || moreAbove
    if (/\bto navigate\b/.test(line)) break
    const m = /^\s*(❯\s+)?(\S.*?)\s*$/u.exec(line)
    if (!m || moreOf(line, '↓') || moreOf(line, '↑')) continue
    const [name, trail] = m[2]!.split(/\s{2,}/)
    if (!name || /\s/.test(name)) continue
    tools.push({
      name,
      cut: name.endsWith(CUT),
      cursor: !!m[1],
      ...(/read-only/.test(trail ?? '') ? { readOnly: true } : {}),
      ...(/destructive/.test(trail ?? '') ? { destructive: true } : {}),
      ...(/open.?world/i.test(trail ?? '') ? { openWorld: true } : {}),
    })
  }
  return { tools, total, moreBelow, moreAbove }
}

/** Whether [text] shows one of /mcp's dialogs, which Esc closes without touching a turn. */
export const showsMcpDialog = (text: string) => !!readMcpList(text) || !!readMcpPage(text) || !!readMcpTools(text)

export type McpSaid = 'ok' | 'failed' | 'needs_auth' | 'not_found' | 'already' | 'turned_off' | 'other_session'
export type McpHit = { said: McpSaid; words: string }

const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/**
 * Every answer of /mcp to [action] on [name] that's on [text], oldest first: under the command as it's echoed, or
 * while Claude works, a moment in the row above the box, right-aligned. A count, since an older answer can still be
 * on the screen. A long one wraps, so it's looked for across lines. What it quotes comes through the mask.
 */
export function mcpHits(text: string, action: 'reconnect' | 'enable' | 'disable', name: string): McpHit[] {
  const n = esc(name)
  const q = `"${n}"`
  const flat = text
    .split('\n')
    .map((l) => l.replace(/^\s*⎿\s*/, '').trim())
    .join(' ')
    .replace(/\s+/g, ' ')
  const kinds: [RegExp, McpSaid][] =
    action === 'reconnect'
      ? [
          [new RegExp(`Successfully reconnected to ${n}(?![\\w-])`, 'g'), 'ok'],
          [new RegExp(`Failed to reconnect to ${n}(?![\\w-])(?:: [A-Za-z0-9_ .:'-]{0,200})?`, 'g'), 'failed'],
          [new RegExp(`${n} requires authentication\\.`, 'g'), 'needs_auth'],
          [new RegExp(`MCP server ${q} not found`, 'g'), 'not_found'],
          [new RegExp(`${q} is disabled — enable it in /mcp first`, 'g'), 'turned_off'],
          [new RegExp(`${n} was disabled in another session — disable and re-enable it in /mcp, or restart, to reconnect`, 'g'), 'other_session'],
        ]
      : [
          [new RegExp(`MCP server ${q} ${action}d`, 'g'), 'ok'],
          [new RegExp(`MCP server ${q} is already ${action}d`, 'g'), 'already'],
          [new RegExp(`MCP server ${q} not found`, 'g'), 'not_found'],
          [new RegExp(`${q} is still available in this session, but another session disabled it — it keeps working here and won't reconnect after the next launch\\.`, 'g'), 'other_session'],
        ]
  const hits: { at: number; hit: McpHit }[] = []
  for (const [re, said] of kinds) for (const m of flat.matchAll(re)) hits.push({ at: m.index, hit: { said, words: maskText(m[0]).slice(0, 300) } })
  return hits.sort((a, b) => a.at - b.at).map((h) => h.hit)
}

/** The full name of a row the screen cut: the one name [known] it can be, else null. */
export function wholeName(cut: string, known: Iterable<string>): string | null {
  const start = cut.endsWith(CUT) ? cut.slice(0, -CUT.length) : cut
  const fits = [...new Set(known)].filter((k) => k.startsWith(start))
  return fits.length === 1 ? fits[0]! : null
}
