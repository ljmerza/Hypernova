import { execFile } from 'node:child_process'
import { findClaude, log, trimText } from './util.ts'
import { settings } from './store.ts'
import { applyAgentList, type AgentJob } from './hub.ts'

/**
 * `claude agents` as its own list shows it (settings.claudeAgents): `claude agents --json --all`,
 * every background job with its state there, finished ones included. About 0.2 s of one core a
 * read; one read at a time, and a list that doesn't come leaves the last one standing.
 */
let reading = false
let lastError = ''

export function pollClaudeAgents(): void {
  if (settings.claudeAgents !== true || reading) return
  const file = findClaude()
  if (!file) return
  reading = true
  execFile(file, ['agents', '--json', '--all'], { timeout: 15_000, maxBuffer: 8 * 1024 * 1024 }, (e, out) => {
    reading = false
    const list = e ? null : parseAgentList(out)
    if (!list) {
      const why = trimText(String(e?.message ?? out), 200) ?? ''
      if (why !== lastError) log('claude agents --json --all gave no list:', why)
      lastError = why
      return
    }
    lastError = ''
    applyAgentList(list)
  })
}

/** The background jobs in `claude agents --json --all`'s answer; null when it isn't one. */
export function parseAgentList(out: string): AgentJob[] | null {
  let a: unknown
  try {
    a = JSON.parse(out)
  } catch {
    return null
  }
  if (!Array.isArray(a)) return null
  return a.filter((o) => o && o.kind === 'background' && typeof o.id === 'string' && typeof o.sessionId === 'string' && typeof o.cwd === 'string')
}
