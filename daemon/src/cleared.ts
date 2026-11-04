// /clear goes on under a new session id, in the same process and terminal (docs/PROTOCOL.md, "After
// /clear"). A client still on the old session and writing to it means the emptied conversation, not the
// one the owner just cleared, so its prompts follow for a while. Later, or after a restart of the daemon,
// writing to the old session resumes it from History, as writing to any ended session does.
const FOLLOW_MS = 10 * 60_000

/** Sessions whose SessionEnd said "clear", until their terminal is seen on the next one. */
const ending = new Map<string, number>()
const goesTo = new Map<string, { to: string; at: number }>()

/** Claude Code said [id] ended by /clear. */
export function noteCleared(id: string, now = Date.now()): void {
  ending.set(id, now)
}

/** The terminal or job that ran [from] now runs [to]; true if that was /clear's doing. */
export function linkCleared(from: string, to: string, now = Date.now()): boolean {
  if (!ending.delete(from)) return false
  goesTo.set(from, { to, at: now })
  return true
}

/** The session [id]'s conversation went on in after a /clear not long ago, through any later ones; else [id]. */
export function clearedTo(id: string, now = Date.now()): string {
  let at = id
  for (let hops = 0; hops < 8; hops++) {
    const n = goesTo.get(at)
    if (!n || now - n.at > FOLLOW_MS) break
    at = n.to
  }
  return at
}
