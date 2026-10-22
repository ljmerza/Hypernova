// What Codex asks (run a command, change files, more access, a question) as Hypernova's approval
// cards. Codex sends the same request to every client on the thread and takes the first answer
// (docs/specs/codex-app-server.md, 1.4): the phone and a terminal race as they do for Claude.
import { ensureParsed, getSession, notice, requestApproval, type Answer, type ApprovalAsk } from '../hub.ts'
import { maskSecrets, toolSummary } from '../normalize.ts'
import { log } from '../util.ts'
import { isCodex } from './threads.ts'
import { codexConnected, onCodexChange, onCodexNotification, onCodexRequest } from './client.ts'
import type { CodexRpc, RpcMessage } from './rpc.ts'

/** Requests waiting for an answer, by their id: aborted when another client answered first. */
const waiting = new Map<string | number, AbortController>()
const stops: ((id: string) => void)[] = []
/** Nobody answered for 10 minutes (sessions.ts stops the turn, as Claude is told to wait). */
export const onApprovalTimeout = (f: (id: string) => void) => void stops.push(f)

/** The files a change asks about, from its item (item/started came first). */
function changedPaths(sessionId: string, itemId: string): string[] {
  const evs = ensureParsed(getSession(sessionId)!).events
  return evs.filter((e) => e.kind === 'tool' && (e.id === itemId || e.id.startsWith(`${itemId}#`))).map((e) => (e.kind === 'tool' ? e.path ?? '' : '')).filter(Boolean)
}

/** The card for a request, or null for one the phone can't answer (a secret, an MCP form). */
export function askOf(m: RpcMessage, sessionId: string): ApprovalAsk | null {
  const p = m.params ?? {}
  const base = { sessionId, canAlways: false, questions: null, plan: null, reason: typeof p.reason === 'string' ? p.reason : null }
  switch (m.method) {
    case 'item/commandExecution/requestApproval': {
      // The card's line is the command Codex recognized; what runs, whole, is what the card holds.
      const short = maskSecrets(String(p.commandActions?.length === 1 ? p.commandActions[0].command : (p.command ?? '')))
      const command = maskSecrets(String(p.command ?? short))
      return { ...base, kind: 'tool', toolName: 'Bash', summary: `Bash: ${toolSummary('Bash', { command: short })}`, input: { command, ...(p.cwd ? { cwd: p.cwd } : {}) } }
    }
    case 'item/fileChange/requestApproval': {
      const paths = changedPaths(sessionId, String(p.itemId ?? ''))
      const first = paths[0] ?? p.grantRoot ?? ''
      const more = paths.length > 1 ? ` and ${paths.length - 1} more` : ''
      return { ...base, kind: 'tool', toolName: 'Edit', summary: `Edit: ${toolSummary('Edit', { file_path: first })}${more}`, input: { file_path: first, ...(paths.length > 1 ? { files: paths } : {}) } }
    }
    case 'item/permissions/requestApproval': {
      const want = [p.permissions?.network ? 'the network' : null, p.permissions?.fileSystem ? 'more folders' : null].filter(Boolean).join(' and ') || 'more access'
      return { ...base, kind: 'tool', toolName: 'Permissions', summary: `More access: ${want}, for this turn`, input: { permissions: p.permissions ?? {} } }
    }
    case 'item/tool/requestUserInput': {
      const qs: any[] = Array.isArray(p.questions) ? p.questions : []
      if (!qs.length || qs.some((q) => q?.isSecret)) return null
      // In Claude's AskUserQuestion shape, which the phone already draws.
      const questions = qs.map((q) => ({ question: String(q.question ?? ''), header: String(q.header ?? ''), options: (q.options ?? []).map((o: any) => ({ label: String(o?.label ?? ''), description: String(o?.description ?? '') })), multiSelect: false }))
      return { ...base, kind: 'question', toolName: 'AskUserQuestion', summary: 'A question for you', input: { questions }, questions }
    }
  }
  return null
}

/** Codex's answer for the card's: allowed, denied, or (nobody came) cancelled, which stops the turn. */
export function replyOf(m: RpcMessage, ans: Answer): unknown {
  const p = m.params ?? {}
  const yes = ans.decision === 'allow' || ans.decision === 'always' || ans.decision === 'session'
  switch (m.method) {
    case 'item/permissions/requestApproval':
      return { permissions: yes ? Object.fromEntries(Object.entries(p.permissions ?? {}).filter(([, v]) => v != null)) : {}, scope: 'turn' }
    case 'item/tool/requestUserInput': {
      const qs: any[] = Array.isArray(p.questions) ? p.questions : []
      // The phone answers by question text (Claude's way); Codex wants them by id.
      return { answers: Object.fromEntries(qs.map((q) => [q.id, { answers: yes && ans.answers ? [ans.answers[q.question] ?? ans.answers[q.id] ?? ''].filter(Boolean) : [] }])) }
    }
    default:
      return { decision: yes ? 'accept' : ans.decision === 'timeout' ? 'cancel' : 'decline' }
  }
}

async function onRequest(m: RpcMessage, c: CodexRpc): Promise<void> {
  const sessionId = m.params?.threadId
  if (typeof sessionId !== 'string' || !isCodex(sessionId) || !getSession(sessionId) || m.id == null) return
  const ask = askOf(m, sessionId)
  if (!ask) {
    // Shared with any terminal on the thread: an error answer would end it for that terminal too.
    notice(sessionId, 'Codex is asking something Hypernova can’t show. Open the terminal to answer it.', 'warn')
    return
  }
  const abort = new AbortController()
  waiting.set(m.id, abort)
  const ans = await requestApproval(ask, typeof m.params?.itemId === 'string' ? m.params.itemId : null, abort.signal)
  waiting.delete(m.id)
  // Answered in a terminal first: Codex already has its answer.
  if (ans.decision === 'terminal') return
  // On the connection it came on: a request's id means nothing on another one.
  try {
    if (c.open) c.respond(m.id, replyOf(m, ans))
  } catch (e) {
    log('codex approval answer failed', (e as Error).message)
  }
  if (ans.decision === 'timeout') for (const f of stops) f(sessionId)
}

onCodexRequest((m, c) => void onRequest(m, c))
// A request belongs to the connection it came on: once that's gone, it can't be answered from here.
onCodexChange(() => {
  if (codexConnected()) return
  for (const a of waiting.values()) a.abort()
  waiting.clear()
})
onCodexNotification((m) => {
  if (m.method !== 'serverRequest/resolved') return
  const id = m.params?.requestId
  waiting.get(id)?.abort()
  waiting.delete(id)
})
