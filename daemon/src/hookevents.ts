// Claude Code's session events as automations' hook triggers hear them (docs/PROTOCOL.md,
// "Automations", "Workflows"): Stop, StopFailure, PostToolUseFailure, PreCompact and SessionEnd,
// from the hooks (hooks.ts) or, for sessions the daemon runs itself, the SDK (runner.ts). Its own
// small module, so neither imports the automations (which import them).
type Listener = (event: string, sessionId: string, tool: string | null, line: string) => void
let listener: Listener = () => {}

/** The automations listen (one listener). */
export function onHookEvent(f: Listener): void {
  listener = f
}

/** [event] happened in session [sessionId]; [tool]: a failed call's tool; [line]: it in a few words. */
export function noteHookEvent(event: string, sessionId: string, tool: string | null, line: string): void {
  try {
    listener(event, sessionId, tool, line)
  } catch {}
}
