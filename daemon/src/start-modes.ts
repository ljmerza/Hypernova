// "New sessions start in", per agent, kept on this server (docs/DECISIONS.md, "New sessions start in,
// per server and agent"; docs/PROTOCOL.md, "Snapshot" and POST /v1/claude): what a client's New session
// form opens on. Only that: a session still names its own mode, and POST /v1/sessions checks it as it
// always has. Not the agents' own files: a terminal's `claude` or `codex` doesn't follow this.
import { saveSettings, settings } from './store.ts'
import { log } from './util.ts'

export type StartAgent = 'claude' | 'codex'

/**
 * What each agent can start in, the first being what an unset or unusable value means. Never `bypassPermissions`
 * (chosen each time on the phone, behind its fingerprint), `danger-full-access` or approval `never`: nothing outside
 * this list can be saved, and nothing outside it is read back, so a hand-edited file falls back rather than widens.
 */
const MODES: Record<StartAgent, readonly string[]> = {
  claude: ['default', 'acceptEdits', 'plan', 'auto'],
  codex: ['read-only', 'workspace-write'],
}

export const isStartMode = (agent: StartAgent, mode: unknown): mode is string => typeof mode === 'string' && MODES[agent].includes(mode)

const saved = (agent: StartAgent): string | null => {
  const v = settings.startModes?.[agent]
  return isStartMode(agent, v) ? v : null
}

/** What [agent]'s new sessions open on here. */
export const startMode = (agent: StartAgent): string => saved(agent) ?? MODES[agent][0]

/** Saves it. [ifUnset]: only where nothing usable is saved yet (a phone moving its old setting here, once). Check it with [isStartMode] first. */
export function setStartMode(agent: StartAgent, mode: string, ifUnset = false): void {
  if (!isStartMode(agent, mode) || (ifUnset && saved(agent) != null) || settings.startModes?.[agent] === mode) return
  settings.startModes = { ...settings.startModes, [agent]: mode }
  saveSettings()
  log('new sessions start in:', agent, mode)
}
