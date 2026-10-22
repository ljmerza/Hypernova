// Calls Claude Code refused without asking (docs/PROTOCOL.md, "Hooks": the PermissionDenied hook of
// an automation's run; the SDK's `permission_denied` messages), handed to whoever listens: the
// automations, which keep them as rules the owner can allow next time. Its own small module, so
// hooks.ts and runner.ts don't import the automations (which import them).
type Listener = (sessionId: string, tool: string, input: unknown) => void
let listener: Listener = () => {}

/** The automations listen (one listener). */
export function onDenied(f: Listener): void {
  listener = f
}

/** Claude Code refused [tool] in session [sessionId] without asking. */
export function noteDenied(sessionId: string, tool: string, input: unknown): void {
  try {
    listener(sessionId, tool, input)
  } catch {}
}
