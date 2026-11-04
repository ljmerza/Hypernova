// What a client's New session form opens on, per agent: that agent's own setting in its own file on this
// server (docs/DECISIONS.md, "One starting mode, the agent's own"; docs/PROTOCOL.md, "Snapshot"). Only that:
// a session still names its own mode, and POST /v1/sessions checks it as it always has. This only reads, and
// only through agent-settings.ts's fixed list, whose care it shares; a change goes through agent-settings-edit.ts.
//
// That file can hold anything: `bypassPermissions`, `dontAsk`, `danger-full-access`, approval `never`, a word from a
// newer agent, or it can be missing, broken or unreadable. Each of those is Ask or Read only here, so it never widens.
import { statSync } from 'node:fs'
import { join } from 'node:path'
import { claudeSettings, codexHome, codexSettings, type ShownValue } from './agent-settings.ts'
import { CLAUDE_DIR } from './util.ts'

export type StartAgent = 'claude' | 'codex'

/** What the form offers without asking again. Never Bypass: it's chosen each time on the phone. */
const CLAUDE_MODES: readonly unknown[] = ['default', 'acceptEdits', 'plan', 'auto']

const valueOf = (values: readonly ShownValue[], key: string): unknown => {
  const v = values.find((s) => s.key === key)
  return v?.state === 'set' ? v.value : undefined
}

function claudeMode(values: readonly ShownValue[]): string {
  const mode = valueOf(values, 'permissions.defaultMode')
  return CLAUDE_MODES.includes(mode) ? (mode as string) : 'default'
}

/**
 * Can edit only where the file says `workspace-write` and asks as a thread from a client always does: `on-request`, or
 * not set, which means that. `never` isn't carried over, and nor is `untrusted`, as a thread from here would ask less.
 */
function codexMode(values: readonly ShownValue[]): string {
  const approval = values.find((s) => s.key === 'approval_policy')
  const asks = approval?.state === 'unset' || valueOf(values, 'approval_policy') === 'on-request'
  return asks && valueOf(values, 'sandbox_mode') === 'workspace-write' ? 'workspace-write' : 'read-only'
}

const fileOf = (agent: StartAgent) => (agent === 'claude' ? join(CLAUDE_DIR, 'settings.json') : join(codexHome(), 'config.toml'))
const read = (agent: StartAgent) => (agent === 'claude' ? claudeMode(claudeSettings().values) : codexMode(codexSettings().values))

/** When the file last changed, its permissions too (ctime). Empty: it can't be looked at. */
function stampOf(file: string): string {
  try {
    const s = statSync(file)
    return `${s.ino}:${s.size}:${s.ctimeMs}`
  } catch {
    return ''
  }
}

/** The last reading, per agent: this is asked every few seconds (main.ts), and the file seldom changes. */
const last = new Map<StartAgent, { file: string; stamp: string; mode: string }>()

/** What [agent]'s new sessions open on here. */
export function startMode(agent: StartAgent): string {
  const file = fileOf(agent)
  const stamp = stampOf(file)
  const hit = last.get(agent)
  if (hit && hit.file === file && hit.stamp === stamp) return hit.mode
  const mode = read(agent)
  last.set(agent, { file, stamp, mode })
  return mode
}
