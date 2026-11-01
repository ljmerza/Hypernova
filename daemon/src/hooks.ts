// Claude Code hooks for sessions started in a terminal, and the reversible integration
// installer (hooks in ~/.claude/settings.json + a status line tap).
import { existsSync, mkdirSync, readFileSync, copyFileSync, statSync, realpathSync, unlinkSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { userInfo } from 'node:os'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { CLAUDE_DIR, HOME, LOCAL_PORT, SUPPORT, claudeCached, log, readJson, safeEqual, writeJson, writeText, trimText } from './util.ts'
import { HN_BIN, watchHandedBack } from './terms.ts'
import { ON_MAC, PLATFORM, shellRc } from './platform.ts'

/** Read settings.json, refusing to continue if it exists but isn't valid JSON (we'd wipe it). */
function readSettings(): any {
  if (!existsSync(SETTINGS)) return {}
  try {
    return JSON.parse(readFileSync(SETTINGS, 'utf8'))
  } catch {
    throw Object.assign(new Error('Your ~/.claude/settings.json isn’t valid JSON right now, so Hypernova won’t touch it. Fix it (or ask Claude Code to) and try again.'), { status: 409 })
  }
}
function stripBlock(text: string): string {
  const i = text.indexOf(BEGIN)
  if (i < 0) return text
  const j = text.indexOf(END, i)
  if (j < 0) return text.slice(0, i) + text.slice(i).split('\n').slice(2).join('\n') // BEGIN line + tee line
  return text.slice(0, i) + text.slice(j + END.length + 1)
}
import { hookSecret, isSdk, isSubscription, settings } from './store.ts'
import { onCodexChange, onCodexEnabled } from './codex/client.ts'
import { classifyApiError } from './blockers.ts'
import {
  blockSession,
  finalRead,
  forgetSessionAllowed,
  isRestarting,
  resolveSession,
  sessionAllowedTools,
  sessionAlwaysRules,
  sessionRule,
  ensureParsed,
  getSession,
  hasPairedDevice,
  requestApproval,
  resolveByToolUse,
  setState,
  settleHandBack,
  startTool,
  toolRunning,
  upsertSession,
  emitEvent,
  mainActive,
  mainEnded,
  mainStopped,
} from './hub.ts'
import { applyToolResult, isLauncher, toolSummary } from './normalize.ts'
import { agentLabel, endSessionAgents, hookActivity, hookLaunched, hookStop, hookSubagentStop, hookToolDone } from './agents.ts'
import { attachmentReadInput, readsAttachment } from './attachments.ts'
import { noteDenied } from './denied.ts'
import { noteBlocked } from './blocked.ts'
import { noteHookEvent } from './hookevents.ts'
import { alwaysUpdates, suggestedRules, suggestedScope } from './always.ts'
import { rulesInEffect } from './permission-rules.ts'
import { whyFromRules, whyFromSuggestions, type Why } from './why.ts'

// PreToolUse → tool_use_id, to pair with PermissionRequest (which has no id).
const recentPre = new Map<string, string>()
const preKey = (sid: string, tool: string, input: unknown) => `${sid}|${tool}|${JSON.stringify(input)}`

export async function handleHook(ev: any, req: IncomingMessage, res: ServerResponse): Promise<object> {
  const sid: string | undefined = typeof ev?.session_id === 'string' ? ev.session_id : undefined
  if (!sid || isSdk(sid)) return {}
  const name: string = ev.hook_event_name
  const exists = !!getSession(sid)
  if (!exists && typeof ev.cwd !== 'string') return {}
  // A session stays in the folder it started in: hooks carry Claude's current folder, which
  // moves when it cd's somewhere or a subagent works elsewhere.
  if (!exists) upsertSession(sid, { cwd: ev.cwd, origin: 'terminal', ...(typeof ev.transcript_path === 'string' ? { transcriptPath: ev.transcript_path } : {}) })
  else if (typeof ev.transcript_path === 'string' && ev.transcript_path !== getSession(sid)!.transcriptPath) upsertSession(sid, { transcriptPath: ev.transcript_path })
  const rec = getSession(sid)!
  ensureParsed(rec)
  // Every hook carries the session's current mode (Shift+Tab in the terminal changes it).
  if (typeof ev.permission_mode === 'string' && ev.permission_mode !== rec.permissionMode) upsertSession(sid, { permissionMode: ev.permission_mode })
  switch (name) {
    case 'SessionStart':
      setState(sid, 'idle')
      return {}
    case 'SessionEnd':
      // A restart onto another provider: the old claude ends (SIGHUP), the conversation goes on in
      // a new one. Its background agents went with it (the restart ends them itself).
      if (isRestarting(sid)) return {}
      forgetSessionAllowed(sid)
      resolveSession(sid)
      finalRead(sid)
      setState(sid, 'ended')
      mainEnded(sid)
      // Claude Code quit: agents and workflows it ran in the background ended with it (no task
      // notification will say so). After /clear or a switch to another session the same process
      // goes on, and they may too: those settle once their files go quiet.
      if (ev.reason !== 'clear' && ev.reason !== 'resume') endSessionAgents(sid)
      noteHookEvent('SessionEnd', sid, null, `The session ended${typeof ev.reason === 'string' && ev.reason ? ` (${ev.reason})` : ''}.`)
      return {}
    case 'UserPromptSubmit':
      upsertSession(sid, { stoppedForAway: false })
      mainActive(sid)
      // Claude Code 2.1.299 runs this hook when a prompt typed while Claude works is queued, not
      // when Claude gets it: the tool it's running (and an approval it waits for) carries on.
      if (rec.state === 'working' && toolRunning(sid)) return {}
      resolveSession(sid, true)
      setState(sid, 'working', 'Thinking')
      return {}
    case 'PreToolUse': {
      // A subagent's tool call (session_id is its main session's): what that agent does now.
      if (typeof ev.agent_id === 'string' && ev.agent_id) hookActivity(sid, ev.agent_id, ev.agent_type, String(ev.tool_name ?? 'Tool'), toolSummary(String(ev.tool_name ?? ''), ev.tool_input ?? {}))
      if (readsAttachment(ev.tool_name, ev.tool_input)) {
        if (ev.tool_use_id && !ev.agent_id) startTool(sid, ev.tool_use_id, ev.tool_name, ev.tool_input ?? {})
        const updatedInput = attachmentReadInput(sid, ev.tool_input)
        return { hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'allow', permissionDecisionReason: 'A file you attached in Hypernova', ...(updatedInput ? { updatedInput } : {}) } }
      }
      if (ev.agent_id) return {} // a subagent's own tools: its card on the phone shows them
      mainActive(sid)
      if (ev.tool_use_id) {
        recentPre.set(preKey(sid, ev.tool_name, ev.tool_input), ev.tool_use_id)
        if (recentPre.size > 500) recentPre.delete(recentPre.keys().next().value!)
        const t = startTool(sid, ev.tool_use_id, ev.tool_name, ev.tool_input ?? {})
        setState(sid, 'working', `${ev.tool_name}: ${t.summary}`)
      }
      return {}
    }
    case 'PostToolUse':
    case 'PostToolUseFailure': {
      if (typeof ev.agent_id === 'string' && ev.agent_id) hookToolDone(sid, ev.agent_id)
      if (ev.tool_use_id && !ev.agent_id) {
        resolveByToolUse(ev.tool_use_id)
        if (isLauncher(String(ev.tool_name ?? ''))) hookLaunched(sid, ev.tool_use_id, ev.tool_response)
        const t = rec.parse?.byId.get(ev.tool_use_id)
        if (t && t.kind === 'tool' && t.status === 'running') {
          const failed = name === 'PostToolUseFailure'
          applyToolResult(t, { is_error: failed, content: failed ? String(ev.error ?? 'Failed') : '' }, ev.tool_response, Date.now())
          emitEvent(sid, t.id)
        }
        if (name === 'PostToolUseFailure') {
          const tool = String(ev.tool_name ?? 'Tool')
          noteHookEvent('PostToolUseFailure', sid, tool, `${tool} failed: ${toolSummary(tool, ev.tool_input ?? {})}${ev.error ? `: ${String(ev.error).split('\n')[0]}` : ''}`)
        }
      }
      return {}
    }
    case 'Stop':
      // A subagent's own stop (it carries agent_id) isn't the session's.
      if (typeof ev.agent_id === 'string' && ev.agent_id) return {}
      // The main turn ended; agents it started may still be at work (background_tasks says which).
      mainStopped(sid)
      hookStop(sid, ev.background_tasks)
      resolveSession(sid, true)
      if (ev.last_assistant_message) upsertSession(sid, { lastText: trimText(ev.last_assistant_message, 200) })
      setState(sid, 'idle')
      noteHookEvent('Stop', sid, null, 'A turn ended.')
      return {}
    case 'SubagentStop':
      // An agent the main agent started has finished, unless it's waiting on its own background
      // work: its count goes down now, not when a notification (that may never come) says so.
      if (typeof ev.agent_id === 'string' && ev.agent_id) {
        hookSubagentStop(sid, ev.agent_id, ev.last_assistant_message, ev.background_tasks)
        // Its asks handed back to the terminal were answered there: it couldn't finish otherwise.
        settleHandBack(sid, (a) => a.agentId === ev.agent_id)
      }
      return {}
    case 'StopFailure': {
      // In place of Stop when an API error ended the turn (a usage limit, signed out…), at once,
      // before the transcript shows it. Its last message is Claude Code's error, not Claude's
      // words, so lastText stays as it was.
      if (typeof ev.agent_id === 'string' && ev.agent_id) return {}
      mainStopped(sid)
      resolveSession(sid, true)
      const details = typeof ev.error_details === 'string' ? ev.error_details : ''
      const text = typeof ev.last_assistant_message === 'string' && ev.last_assistant_message ? ev.last_assistant_message : details
      const at = Date.now()
      blockSession(sid, classifyApiError({ error: ev.error, text, builtIn: isSubscription(rec.providerId), at }), at)
      setState(sid, 'idle')
      noteHookEvent('StopFailure', sid, null, `An API error ended its turn${text ? `: ${text.split('\n')[0]}` : '.'}`)
      return {}
    }
    case 'PreCompact':
      // Only automations' hook triggers listen: its context is about to be compacted.
      noteHookEvent('PreCompact', sid, null, `Its context is about to be compacted (${ev.trigger === 'manual' ? 'by /compact' : 'automatically'}).`)
      return {}
    case 'Notification':
      if (ev.notification_type === 'idle_prompt') setState(sid, 'idle')
      return {}
    case 'PermissionRequest':
      return permission(ev, sid, req, res)
    case 'PermissionDenied':
      // A call refused without asking. An automation's run keeps it as a rule to allow next time
      // (automations.ts). Auto mode's classifier fires it too: the call goes on the session's
      // Blocked tab (blocked.ts), as the transcript would put it there a moment later.
      noteDenied(sid, String(ev.tool_name ?? ''), ev.tool_input ?? {})
      noteBlocked(sid, { tool: String(ev.tool_name ?? ''), input: ev.tool_input ?? {}, reason: 'auto_mode', why: typeof ev.reason === 'string' ? ev.reason : null, toolUseId: typeof ev.tool_use_id === 'string' ? ev.tool_use_id : null })
      return {}
  }
  return {}
}

/**
 * Why a hook's ask asked (IDEAS PA7): Claude Code's offer to add a folder, else Hypernova's reading
 * of the session's rules in effect (its own folder's settings files and the rules Hypernova gave it).
 * Null if the files can't be read: the ask goes on without it.
 */
function hookWhy(sid: string, tool: string, input: Record<string, unknown>, suggestions: unknown): Why | null {
  try {
    const rec = getSession(sid)
    if (!rec) return null
    return whyFromSuggestions(suggestions) ?? whyFromRules(tool, input, rec.permissionMode ?? null, rec.cwd, rulesInEffect(rec.cwd, { allow: [...new Set([...sessionAllowedTools(sid), ...sessionAlwaysRules(sid)])] }), HOME)
  } catch (e) {
    log('why it asked:', (e as Error).message)
    return null
  }
}

async function permission(ev: any, sid: string, req: IncomingMessage, res: ServerResponse): Promise<object> {
  // Nobody to ask: let the terminal dialog handle it.
  if (!hasPairedDevice()) return {}
  const tool: string = ev.tool_name
  const input = ev.tool_input ?? {}
  const kind = tool === 'AskUserQuestion' ? 'question' : tool === 'ExitPlanMode' ? 'plan' : 'tool'
  // If Claude Code drops the hook (the terminal answered, or Esc), the response closes early.
  const abort = new AbortController()
  res.on('close', () => {
    if (!res.writableEnded) abort.abort()
  })
  void req
  const ans = await requestApproval(
    {
      sessionId: sid,
      kind,
      toolName: tool,
      summary: kind === 'tool' ? `${tool}: ${toolSummary(tool, input)}` : toolSummary(tool, input),
      input,
      reason: null,
      canAlways: Array.isArray(ev.permission_suggestions) && ev.permission_suggestions.length > 0,
      // The rule Claude Code suggests, for the phone's Always sheet to show and edit (always.ts).
      alwaysRules: suggestedRules(ev.permission_suggestions),
      alwaysScope: suggestedScope(ev.permission_suggestions),
      // Why it asked: the hook says no reason (Claude Code 2.1.316), so its suggestion's folder
      // first, else Hypernova's reading of the rules in effect (why.ts).
      why: kind === 'tool' ? hookWhy(sid, tool, input, ev.permission_suggestions) : null,
      questions: kind === 'question' ? (input.questions ?? null) : null,
      plan: kind === 'plan' ? String(input.plan ?? '') : null,
      // A subagent asks: the phone says which (and notifies later, as agents often settle it).
      agentId: typeof ev.agent_id === 'string' && ev.agent_id ? ev.agent_id : null,
      agentLabel: typeof ev.agent_id === 'string' && ev.agent_id ? agentLabel(sid, ev.agent_id) : null,
    },
    recentPre.get(preKey(sid, tool, input)) ?? null,
    abort.signal,
  )
  const out = (decision: object) => ({ hookSpecificOutput: { hookEventName: 'PermissionRequest', decision } })
  switch (ans.decision) {
    case 'terminal':
      return {}
    // Nobody answered before Claude Code stops waiting for this hook (its timeout): no decision, so
    // Claude Code asks in its own dialog and the session waits there; never a "no" (owner, 2026-10-30).
    // A `claude agents` job gets a terminal attached for it, so the phone has that dialog as a card.
    case 'timeout':
      void watchHandedBack(sid)
      return {}
    case 'deny':
      return out({ behavior: 'deny', message: ans.message || (kind === 'plan' ? 'The user wants to keep planning. Ask what to change.' : 'The user denied this from Hypernova.') })
    // Claude Code writes the rule where the owner chose (the session, the project's local file, the
    // user's), or its own suggestion as it is when he chose nothing (always.ts).
    case 'always':
      return out({ behavior: 'allow', updatedPermissions: alwaysUpdates(ev.permission_suggestions, ans.always) })
    case 'session':
      return out({ behavior: 'allow', updatedPermissions: sessionRule(tool) })
    default:
      if (kind === 'question' && ans.answers) return out({ behavior: 'allow', updatedInput: { ...input, answers: ans.answers } })
      // A plan dialog only closes when the hook hands the plan back.
      if (kind === 'plan') return out({ behavior: 'allow', updatedInput: input })
      return out({ behavior: 'allow' })
  }
}

// ── Integration installer ────────────────────────────────────────────────
const SETTINGS = join(CLAUDE_DIR, 'settings.json')
const HOOK_URL_PREFIX = `http://127.0.0.1:${LOCAL_PORT}/hooks/`
const EVENTS: [string, number][] = [
  ['PermissionRequest', 660],
  ['PreToolUse', 10],
  ['PostToolUse', 10],
  ['PostToolUseFailure', 10],
  ['UserPromptSubmit', 10],
  ['Stop', 10],
  ['Notification', 10],
  ['SessionStart', 10],
  ['SessionEnd', 10],
]
/**
 * Installed with the rest, and added at start to an install from before, but never required:
 * one missing must not make the integration look uninstalled (phone sessions would move to the
 * SDK fallback until a reinstall). StopFailure: an API error ended the turn. SubagentStop: an
 * agent finished (agents.ts).
 */
const OPTIONAL_EVENTS: [string, number][] = [
  ['StopFailure', 10],
  ['SubagentStop', 10],
  // Automations' hook triggers (docs/PROTOCOL.md, "Automations", "Workflows"): the context is about to be compacted.
  ['PreCompact', 10],
  // Auto mode's classifier refused a call: the session's Blocked tab (blocked.ts; the transcript stays the fallback).
  ['PermissionDenied', 10],
]
const TOOL_EVENTS = ['PermissionRequest', 'PreToolUse', 'PostToolUse', 'PostToolUseFailure', 'PermissionDenied']
const BEGIN = '# >>> hypernova'
const END = '# <<< hypernova'
const STATUS_DIR = join(SUPPORT, 'statusline')
mkdirSync(STATUS_DIR, { recursive: true, mode: 0o700 })

function teeBlock(): string {
  if (!ON_MAC) return linuxTeeBlock()
  return `${BEGIN} (shares usage with the Hypernova app; safe to delete)
{ d="$HOME/Library/Application Support/Hypernova/statusline"; if [ -d "$d" ]; then s=$(printf '%s' "$input" | /usr/bin/sed -n 's/.*"session_id" *: *"\\([A-Za-z0-9-]*\\)".*/\\1/p' | /usr/bin/head -n 1); f="$d/\${s:-unknown}.json"; printf '%s' "$input" > "$f.$$" && /bin/mv "$f.$$" "$f"; fi; } 2>/dev/null
${END}
`
}
/**
 * The same tap on Linux, into this daemon's own statusline folder (~/.local/share/hypernova, or
 * wherever HYPERNOVA_SUPPORT puts it). The tools by name: Debian systems from before the /usr merge
 * keep sed in /bin, and the status line runs with Claude Code's PATH, which has both.
 */
function linuxTeeBlock(): string {
  return `${BEGIN} (shares usage with the Hypernova app; safe to delete)
{ d=${zq(STATUS_DIR)}; if [ -d "$d" ]; then s=$(printf '%s' "$input" | sed -n 's/.*"session_id" *: *"\\([A-Za-z0-9-]*\\)".*/\\1/p' | head -n 1); f="$d/\${s:-unknown}.json"; printf '%s' "$input" > "$f.$$" && mv "$f.$$" "$f"; fi; } 2>/dev/null
${END}
`
}

function backup(): void {
  backupFile(SETTINGS, 'settings')
}
/**
 * settings.json written back as the owner keeps it: through a dotfiles symlink (stow, chezmoi) to
 * the real file, with its own mode (it can hold `env` keys and the hook secret); 0600 when new.
 */
function writeSettings(s: unknown): void {
  let real = SETTINGS
  let mode = 0o600
  try {
    real = realpathSync(SETTINGS)
    mode = statSync(real).mode & 0o777
  } catch {}
  writeJson(real, s, mode)
}
function backupFile(path: string, name: string): void {
  const dir = join(SUPPORT, 'backups')
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  if (existsSync(path)) copyFileSync(path, join(dir, `${name}-${Date.now()}${name === 'settings' ? '.json' : ''}`))
}

/** Which script the status line runs, if it's a bash script (tapped when it reads its input as `input=$(cat)`). */
function statusScript(s: any): string | null {
  const cmd: string | undefined = s?.statusLine?.command
  if (!cmd) return null
  const m = /^(?:bash|sh|\/bin\/bash|\/bin\/sh)?\s*("?)([^"]+\.sh)\1\s*$/.exec(cmd.trim())
  const path = m?.[2]?.replace(/^~(?=\/)/, process.env.HOME ?? '~')
  return path && existsSync(path) ? path : null
}

// ── Shell: `claude` typed in a terminal starts a shared terminal ─────────
/**
 * The owner's shell startup file (platform.ts): ~/.zshrc for zsh, and on Linux ~/.bashrc for bash;
 * null for another shell. The name ZSHRC stays from the days of zsh alone.
 */
const RC = (() => {
  try {
    return shellRc(PLATFORM, userInfo().shell, process.env, HOME)
  } catch {
    return null
  }
})()
const ZSHRC = RC?.path ?? join(process.env.ZDOTDIR ?? HOME, '.zshrc')
const RC_NAME = RC?.name ?? '~/.zshrc'
const SH_BEGIN = '# >>> hypernova shared terminals'
const SH_END = '# <<< hypernova shared terminals'
const zq = (s: string) => (s.startsWith(HOME + '/') ? `"$HOME${s.slice(HOME.length).replace(/(["\\$`])/g, '\\$1')}"` : `'${s.replace(/'/g, `'\\''`)}'`)
/**
 * The block, remembering how it was added: `sep` newlines went before it (1 after a final
 * newline, 2 when the file had none), and `new` when Hypernova created the file. Uninstall
 * uses that to put the file back byte for byte.
 */
function shellBlock(sep: number, created: boolean): string {
  // `function claude` (not `claude()`): an alias named claude can't break the definition.
  // Exit code 97 from hn means "not a shared session" (pipes, -p, subcommands, no Hypernova).
  // $commands[claude] hands hn the same claude this shell would run; bash's `type -P` is the same
  // lookup there (the file on PATH, never this function). The rest is the same in both shells.
  const which = RC?.kind === 'bash' ? '$(type -P claude)' : '${commands[claude]}'
  // With Codex on for Hypernova (codex/client.ts), `codex` the same way: its TUI in a shared terminal.
  const codexWhich = RC?.kind === 'bash' ? '$(type -P codex)' : '${commands[codex]}'
  const codex = settings.codex
    ? `function codex {
  if [[ -t 0 && -t 1 && -z "$HYPERNOVA_TERM" && -z "$HYPERNOVA_OFF" && -x ${zq(HN_BIN)} ]]; then
    HYPERNOVA_CODEX="${codexWhich}" ${zq(HN_BIN)} run-codex "$@"
    local rc=$?
    (( rc != 97 )) && return $rc
  fi
  command codex "$@"
}
`
    : ''
  return `${SH_BEGIN} (lets your phone see and type into claude sessions; safe to delete) hn:sep=${sep}${created ? ',new' : ''}
function claude {
  if [[ -t 0 && -t 1 && -z "$HYPERNOVA_TERM" && -z "$HYPERNOVA_OFF" && -x ${zq(HN_BIN)} ]]; then
    HYPERNOVA_CLAUDE="${which}" ${zq(HN_BIN)} run "$@"
    local rc=$?
    (( rc != 97 )) && return $rc
  fi
  command claude "$@"
}
${codex}function hn { ${zq(HN_BIN)} "$@"; }
${SH_END}
`
}
const shellSupported = () => RC !== null
const count = (text: string, s: string) => text.split(s).length - 1
type Found = { start: number; end: number; sep: number; created: boolean } | null
/** Where our block is, or null. Throws if the markers were edited (we'd cut the owner's lines). */
function findShell(text: string): Found {
  const b = count(text, SH_BEGIN)
  const e = count(text, SH_END)
  if (!b && !e) return null
  const i = text.indexOf(SH_BEGIN)
  const j = text.indexOf(SH_END)
  if (b !== 1 || e !== 1 || j < i) throw Object.assign(new Error(`The Hypernova block in ${RC_NAME} was edited, so Hypernova won’t change that file. Remove the lines between “>>> hypernova” and “<<< hypernova” yourself, then try again.`), { status: 409 })
  const head = text.slice(i, text.indexOf('\n', i) < 0 ? text.length : text.indexOf('\n', i))
  const m = /hn:sep=(\d)(,new)?/.exec(head)
  // Blocks from before the marker (2026-10-14 morning) were added after one blank line.
  const sep = m ? Number(m[1]) : text.slice(i - 2, i) === '\n\n' ? 1 : 0
  let end = j + SH_END.length
  if (text[end] === '\n') end++
  return { start: Math.max(0, i - sep), end, sep, created: !!m?.[2] }
}
/** The real file (a dotfiles symlink stays a symlink), written in place to keep its attributes. */
const zshrcReal = () => (existsSync(ZSHRC) ? realpathSync(ZSHRC) : ZSHRC)
function installShell(): void {
  if (!shellSupported()) return
  const exists = existsSync(ZSHRC)
  const text = exists ? readFileSync(ZSHRC, 'utf8') : ''
  const found = findShell(text)
  const base = found ? text.slice(0, found.start) + text.slice(found.end) : text
  const created = found ? found.created : !exists
  const sep = !base ? 0 : base.endsWith('\n') ? 1 : 2
  const next = base + '\n'.repeat(sep) + shellBlock(sep, created)
  if (next === text) return
  if (exists) backupFile(ZSHRC, 'zshrc')
  writeFileSync(zshrcReal(), next, exists ? undefined : { mode: 0o644 })
}
function uninstallShell(): void {
  if (!existsSync(ZSHRC)) return
  const text = readFileSync(ZSHRC, 'utf8')
  const found = findShell(text)
  if (!found) return
  backupFile(ZSHRC, 'zshrc')
  const rest = text.slice(0, found.start) + text.slice(found.end)
  if (found.created && !rest) unlinkSync(zshrcReal())
  else writeFileSync(zshrcReal(), rest)
}
// Codex turned on or off: its `codex` function comes or goes with it. Turned on on a Mac without
// Claude Code, where its integration never put the block in, the block goes in for Codex. (A Claude
// user who left the integration out doesn't get its `claude` function this way.)
onCodexChange(() => refreshShell())
onCodexEnabled(() => {
  if (process.env.HYPERNOVA_SUPPORT || !shellSupported() || claudeCached() || integrationStatus().hooks.installed) return
  try {
    if (!existsSync(ZSHRC) || !readFileSync(ZSHRC, 'utf8').includes(SH_BEGIN)) installShell()
  } catch (e) {
    log('left ~/.zshrc alone:', (e as Error).message)
  }
})
/**
 * The block is rewritten when the daemon starts, so it always points at this daemon's `hn`.
 * A development daemon on its own state folder (HYPERNOVA_SUPPORT) leaves it to the real one.
 */
export function refreshShell(): void {
  if (process.env.HYPERNOVA_SUPPORT) return
  try {
    if (existsSync(ZSHRC) && readFileSync(ZSHRC, 'utf8').includes(SH_BEGIN)) installShell()
  } catch (e) {
    log('left ~/.zshrc alone:', (e as Error).message)
  }
}

/**
 * Hooks added since the owner installed the integration (StopFailure), added to his install at
 * start, as the shell block is rewritten. Only to an install that is there, complete, and lacks
 * them; settings.json is backed up first. Returns whether it changed anything.
 */
export function addOptionalHooks(): boolean {
  const s = readSettings()
  const ours = (e: string) => (s.hooks?.[e] ?? []).some((g: any) => (g.hooks ?? []).some((h: any) => String(h.url ?? '').startsWith(HOOK_URL_PREFIX)))
  if (!EVENTS.every(([e]) => ours(e))) return false
  const missing = OPTIONAL_EVENTS.filter(([e]) => !ours(e))
  if (!missing.length) return false
  backup()
  const url = `${HOOK_URL_PREFIX}${hookSecret}`
  for (const [event, timeout] of missing) s.hooks[event] = [...(s.hooks[event] ?? []), { ...(TOOL_EVENTS.includes(event) ? { matcher: '*' } : {}), hooks: [{ type: 'http', url, timeout }] }]
  writeSettings(s)
  log('hooks: added', missing.map(([e]) => e).join(', '))
  return true
}

/** At start: [addOptionalHooks]. A development daemon on its own state folder (HYPERNOVA_SUPPORT) leaves the owner's settings alone. */
export function refreshHooks(): void {
  if (process.env.HYPERNOVA_SUPPORT) return
  try {
    healHooks()
    addOptionalHooks()
    // The wrapper a status line runs through is this daemon's (it may have learned more since).
    if (ownStatusLine(readSettings().statusLine?.command)) writeOwnScript()
  } catch (e) {
    log('left the hooks alone:', (e as Error).message)
  }
}

export function integrationStatus(): { hooks: { installed: boolean }; statusLine: { installed: boolean; supported: boolean }; shell: { installed: boolean; supported: boolean } } {
  const s = readJson<any>(SETTINGS, {})
  const hooks = s.hooks ?? {}
  const installed = EVENTS.every(([e]) => (hooks[e] ?? []).some((g: any) => (g.hooks ?? []).some((h: any) => String(h.url ?? '').startsWith(HOOK_URL_PREFIX))))
  const script = statusScript(s)
  let tapped = false
  const ours = ownStatusLine(s.statusLine?.command)
  if (script) tapped = readFileSync(script, 'utf8').includes(BEGIN)
  const shell = { installed: existsSync(ZSHRC) && readFileSync(ZSHRC, 'utf8').includes(SH_BEGIN), supported: shellSupported() }
  // Any status line can be tapped now: a bash script in place, anything else wrapped.
  return { hooks: { installed }, statusLine: { installed: tapped || !!ours, supported: true }, shell }
}

export function installIntegration(): ReturnType<typeof integrationStatus> {
  const s = readSettings()
  // Refuse before changing anything if the ~/.zshrc block was edited by hand.
  if (shellSupported() && existsSync(ZSHRC)) findShell(readFileSync(ZSHRC, 'utf8'))
  backup()
  s.hooks ??= {}
  const url = `${HOOK_URL_PREFIX}${hookSecret}`
  for (const [event, timeout] of [...EVENTS, ...OPTIONAL_EVENTS]) {
    const groups: any[] = (s.hooks[event] ?? []).map((g: any) => ({ ...g, hooks: (g.hooks ?? []).filter((h: any) => !String(h.url ?? '').startsWith(HOOK_URL_PREFIX)) })).filter((g: any) => g.hooks.length)
    groups.push({ ...(TOOL_EVENTS.includes(event) ? { matcher: '*' } : {}), hooks: [{ type: 'http', url, timeout }] })
    s.hooks[event] = groups
  }
  // Status line: tap the owner's bash script, wrap any other command, or add a silent one of our own.
  installStatusLine(s)
  writeSettings(s)
  installShell()
  log('integration installed')
  return integrationStatus()
}

export function uninstallIntegration(): ReturnType<typeof integrationStatus> {
  const s = readSettings()
  backup()
  if (s.hooks) {
    for (const event of Object.keys(s.hooks)) {
      s.hooks[event] = (s.hooks[event] ?? []).map((g: any) => ({ ...g, hooks: (g.hooks ?? []).filter((h: any) => !String(h.url ?? '').startsWith(HOOK_URL_PREFIX)) })).filter((g: any) => g.hooks.length)
      if (!s.hooks[event].length) delete s.hooks[event]
    }
    if (!Object.keys(s.hooks).length) delete s.hooks
  }
  uninstallStatusLine(s)
  writeSettings(s)
  try {
    uninstallShell()
  } catch (e) {
    log('left ~/.zshrc alone:', (e as Error).message)
  }
  log('integration removed')
  return integrationStatus()
}

/** The hooks' route, its secret compared in constant time (anything on the machine can try it). */
export const hookPathOk = (path: string) => safeEqual(path, `/hooks/${hookSecret}`)

// ── The status line tap ──────────────────────────────────────────────────
const OWN_SCRIPT = join(SUPPORT, 'bin', 'statusline.sh')
const OWN_COMMAND = `bash "${OWN_SCRIPT}"`
/** A bash script that reads its input as `input=$(cat)`: the tap goes in it, as before. */
const tappable = (script: string | null): script is string => !!script && /^input=\$\(cat\)\s*$/m.test(stripBlock(readFileSync(script, 'utf8')))

/**
 * Hypernova's own status line script: passes Claude Code's status (usage limits, the model) to the
 * app and prints nothing; with `--then <base64>`, then runs the owner's own status line (any
 * command: ccstatusline through npx, a Python script, an inline one) with the same input and
 * prints what it prints. Rewritten whenever it's used, so it's always this daemon's.
 */
function writeOwnScript(): void {
  writeText(
    OWN_SCRIPT,
    `#!/bin/bash
# Hypernova: passes Claude Code's status (usage limits) to the Hypernova app. Prints nothing itself;
# with --then, runs your own status line after (its command is the base64 that follows).
input=$(cat)
${teeBlock()}if [ "$1" = "--then" ] && [ -n "$2" ]; then
  cmd=$(printf '%s' "$2" | base64 -d 2>/dev/null || printf '%s' "$2" | base64 -D 2>/dev/null)
  [ -n "$cmd" ] && printf '%s' "$input" | /bin/sh -c "$cmd"
fi
`,
    0o755,
  )
}
/** The owner's status line command, run after the tap. */
const wrapCommand = (original: string) => `${OWN_COMMAND} --then ${Buffer.from(original, 'utf8').toString('base64')}`
/** The owner's own command inside a wrapped one, or null when [cmd] isn't one. */
function wrappedOriginal(cmd: unknown): string | null {
  if (typeof cmd !== 'string') return null
  const m = /hypernova\/bin\/statusline\.sh"?\s+--then\s+([A-Za-z0-9+/=]+)\s*$/i.exec(cmd)
  return m ? Buffer.from(m[1]!, 'base64').toString('utf8') : null
}

/**
 * Tap the status line: a bash script reading `input=$(cat)` gets the marked block after that line;
 * any other command (ccstatusline, Python, inline, a script that reads its input another way) is
 * wrapped by Hypernova's own script, which hands it the same input (undone on uninstall); none at
 * all gets Hypernova's own silent one.
 */
function installStatusLine(s: any): void {
  if (ownStatusLine(s.statusLine?.command)) return writeOwnScript()
  const script = statusScript(s)
  if (tappable(script)) {
    const text = stripBlock(readFileSync(script, 'utf8')).replace(/^input=\$\(cat\)\s*$/m, (line) => `${line}\n${teeBlock().trimEnd()}`)
    writeText(script, text, statSync(script).mode & 0o777)
    return
  }
  const cmd = s.statusLine?.command
  if (!s.statusLine || typeof cmd !== 'string' || !cmd.trim()) {
    writeOwnScript()
    s.statusLine = { ...(s.statusLine ?? {}), type: 'command', command: OWN_COMMAND }
    return
  }
  writeOwnScript()
  s.statusLine = { ...s.statusLine, command: wrapCommand(cmd) }
}

/** Undo [installStatusLine]: the block out of a tapped script, the owner's own command back, ours gone. */
function uninstallStatusLine(s: any): void {
  const script = statusScript(s)
  if (script) {
    const text = readFileSync(script, 'utf8')
    if (text.includes(BEGIN)) writeText(script, stripBlock(text), statSync(script).mode & 0o777)
  }
  const original = wrappedOriginal(s.statusLine?.command)
  if (original != null) s.statusLine = { ...s.statusLine, command: original }
  else if (ownStatusLine(s.statusLine?.command)) delete s.statusLine
}

/**
 * Hooks pointing at this daemon with another secret (a lost hook-secret file made a new one, an
 * image built with one baked in): each gets the current secret, or Claude Code's calls would be
 * refused as unknown. Returns whether it changed anything.
 */
export function healHooks(): boolean {
  const s = readSettings()
  const url = `${HOOK_URL_PREFIX}${hookSecret}`
  let changed = false
  for (const groups of Object.values<any>(s.hooks ?? {})) {
    if (!Array.isArray(groups)) continue
    for (const g of groups) for (const h of Array.isArray(g?.hooks) ? g.hooks : []) {
      if (typeof h?.url === 'string' && h.url.startsWith(HOOK_URL_PREFIX) && h.url !== url) {
        h.url = url
        changed = true
      }
    }
  }
  if (!changed) return false
  backup()
  writeSettings(s)
  log('hooks: the secret brought up to date')
  return true
}

/**
 * The silent status line Hypernova adds when there's none: `<support>/bin/statusline.sh`, in
 * "Hypernova" on a Mac and "hypernova" in ~/.local/share on Linux.
 */
const ownStatusLine = (cmd: unknown): boolean => typeof cmd === 'string' && /hypernova\/bin\/statusline\.sh/i.test(cmd)
