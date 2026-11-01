// The automations engine wired to the daemon: its AutoIO (sessions, the account, the disk, one
// timer), the engine made once, and its taps at start.
import { existsSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { LOCAL_PORT, SUPPORT, log, readJson, writeJson } from '../util.ts'
import { systemZone } from '../schedule.ts'
import { broadcast, eventsFor, getSession, getUsage, listSessions, pendingApprovals, tapBroadcast, view } from '../hub.ts'
import { wentOn } from '../rewound.ts'
import { getAccount, providerStatus } from '../account.ts'
import { findProvider, hookSecret, isSdk, settings } from '../store.ts'
import { knownModels, modelsNow } from '../models.ts'
import { getBattery } from '../battery.ts'
import { keepAwake } from '../awake.ts'
import { createSharedSession, endTerm, pressKeys, sharedTerminalsAvailable, termOf } from '../terms.ts'
import { createSession, interrupt } from '../runner.ts'
import { claudeSdk } from '../sdk.ts'
import { integrationStatus } from '../hooks.ts'
import { codexState, codexModels } from '../codex/account.ts'
import { isCodex } from '../codex/threads.ts'
import { createCodexSession, interruptCodex } from '../codex/sessions.ts'
// Automations keep the folder rule they had before the browsing setting (files.ts workPath; LATER L73).
import { workPath } from '../files.ts'
import { getStats } from '../stats.ts'
import { onDenied } from '../denied.ts'
import { onHookEvent } from '../hookevents.ts'
import { appendOutput, checkOutputFile, fireCommand, ghLook, ghReady, githubRepo, postWebhook, runShell, watchFolder, writeHypernovaBin } from './io.ts'
import { CODEX, type AutoIO, type Sent } from './contract.ts'
import { weekText } from './text.ts'
import { Automations } from './engine.ts'

// ── The daemon's wiring ───────────────────────────────────────────────────
const FILE = join(SUPPORT, 'automations.json')
const RUNS_FILE = join(SUPPORT, 'automation-runs.json')
/**
 * The per-run hook (docs/PROTOCOL.md, "Hooks"): PermissionDenied to the daemon, in a file of its
 * own (0600) passed with --settings, so the hook secret never shows in a process list and the
 * owner's settings.json stays as it is.
 */
export const HOOK_FILE = join(SUPPORT, 'automation-hooks.json')
/** Webhook outputs' addresses and tokens (0600), apart from automations.json: never logged, never sent back. */
const SECRETS_FILE = join(SUPPORT, 'automation-secrets.json')

/** What http.ts lends the engine: the prompt route's own sending, and moving a session to another provider. */
export type SessionOps = {
  promptSession(id: string, text: string, afterLimit: boolean): Promise<Sent>
  moveSession(id: string, providerId: string): Promise<string>
}
let ops: SessionOps | null = null
export function useSessionOps(o: SessionOps): void {
  ops = o
}

/** Claude Code's words for a call it refused without asking (don't ask mode, a deny rule). */
const REFUSED = /^(?:Error: )?Permission to use \S+ .*has been denied/

let engine: Automations | null = null
/** Codex's model ids as last read (when a Codex step was saved), for a step's "no longer offered". */
let codexModelList: string[] | null = null
let timer: NodeJS.Timeout | null = null
let timerAt = Infinity

function daemonIO(): AutoIO {
  writeJson(HOOK_FILE, { hooks: { PermissionDenied: [{ matcher: '*', hooks: [{ type: 'http', url: `http://127.0.0.1:${LOCAL_PORT}/hooks/${hookSecret}`, timeout: 10 }] }] } })
  // A session a rewind replaced is the session that replaced it, for a run or a saved target (rewound.ts).
  const viewOf = (id: string) => {
    const r = getSession(wentOn(id))
    return r ? view(r) : null
  }
  return {
    now: Date.now,
    tz: systemZone,
    async start(o) {
      // Codex in its sandbox, asking nobody, told it's an automation's run (codex/sessions.ts).
      if (o.providerId === CODEX) return createCodexSession({ cwd: o.cwd, prompt: o.prompt, model: o.model, mode: o.permissionMode === 'workspace-write' ? 'workspace-write' : 'read-only', title: o.name, automation: o.automation, approvalPolicy: 'never', developerInstructions: o.appendSystemPrompt })
      const opts = { cwd: o.cwd, providerId: o.providerId, model: o.model, permissionMode: o.permissionMode, prompt: o.prompt, title: o.name, automation: o.automation, allowedTools: o.allowedTools, appendSystemPrompt: o.appendSystemPrompt }
      // A shared terminal when the Mac can (as a phone's new session), else the SDK.
      const shared = (await sharedTerminalsAvailable()) && integrationStatus().hooks.installed
      // The SDK's run needs Claude Code's SDK (sdk.ts): without it the step fails at its start, saying why.
      if (!shared) await claudeSdk()
      return shared ? createSharedSession({ ...opts, settingsFile: HOOK_FILE }) : createSession(opts)
    },
    async prompt(id, text, afterLimit) {
      if (!ops) return { ok: false, code: 'terminal_busy' }
      return ops.promptSession(wentOn(id), text, afterLimit)
    },
    async move(id, providerId) {
      if (!ops) throw Object.assign(new Error('not ready'), { code: 'busy' })
      return ops.moveSession(wentOn(id), providerId)
    },
    async interrupt(raw) {
      const id = wentOn(raw)
      if (isCodex(id)) return interruptCodex(id)
      const tid = termOf(id)
      if (tid) pressKeys(tid, '\x1b')
      else if (isSdk(id)) await interrupt(id)
    },
    endTerm: (tid) => void endTerm(tid),
    session: viewOf,
    sessions: listSessions,
    approvals: pendingApprovals,
    account: getAccount,
    providerKind: (id) => (id === CODEX ? (codexState()?.available ? 'codex' : null) : (findProvider(id)?.kind ?? (id === 'anthropic' ? 'anthropic' : null))),
    providerName: (id) => (id === CODEX ? 'Codex' : (findProvider(id)?.name ?? 'That provider')),
    // Codex costs money on an API key; on a ChatGPT plan it spends the plan's limits.
    providerPaid: (id) => (id === CODEX ? codexState()?.account?.type === 'apiKey' : findProvider(id)?.paid !== false),
    codexGate() {
      const c = codexState()
      if (!c?.available || !c.enabled) return { state: 'off', resetsAt: null }
      if (c.account && !c.account.signedIn) return { state: 'signed_out', resetsAt: null }
      if (c.usage?.limited) return { state: 'limited', resetsAt: [c.usage.fiveHour, c.usage.sevenDay].filter((w) => w && w.pct >= 100).map((w) => w!.resetsAt)[0] ?? null }
      return { state: 'ok', resetsAt: null }
    },
    codexFiveHour: () => codexState()?.usage?.fiveHour?.pct ?? null,
    modelKnown(providerId, model) {
      if (providerId === CODEX) return codexModelList ? codexModelList.includes(model.trim()) : null
      const want = model.trim().replace(/\[1m\]$/i, '').toLowerCase()
      // Claude Code takes a full model id it doesn't list in /model: only its aliases are checked.
      if (findProvider(providerId)?.kind !== 'openrouter' && want.startsWith('claude-')) return null
      const list = knownModels(providerId)
      return list ? list.includes(want) : null
    },
    models: (providerId) =>
      providerId === CODEX
        ? codexModels()
            .then((l) => (codexModelList = l.map((m) => m.value)))
            .catch(() => null)
        : modelsNow(providerId),
    providerStatus,
    usage: getUsage,
    battery: getBattery,
    lastReply(id) {
      // The last turn's reply: Claude's last words after the last prompt.
      const evs = eventsFor(id, 400)
      for (let i = evs.length - 1; i >= 0; i--) {
        const e = evs[i]!
        if (e.kind === 'assistant' && e.text.trim()) return e.text
        if (e.kind === 'user') return null
      }
      return viewOf(id)?.lastText ?? null
    },
    refused(id, since) {
      // Codex's sandbox refuses with no words of Claude Code's to read.
      if (isCodex(id)) return []
      return eventsFor(id, 400)
        .filter((e) => e.kind === 'tool' && e.status === 'error' && e.ts >= since && REFUSED.test(e.output ?? ''))
        .map((e) => (e.kind === 'tool' ? { tool: e.name, input: e.input } : { tool: '', input: {} }))
    },
    project(p) {
      if (!p) return null
      const full = workPath(p)
      try {
        return full && existsSync(full) && statSync(full).isDirectory() ? full : null
      } catch {
        return null
      }
    },
    outside(p) {
      try {
        return !!p && !workPath(p) && statSync(p).isDirectory()
      } catch {
        return false
      }
    },
    lastError(id) {
      // The last error notice its turn ended on ("Claude Code stopped with an error: …").
      const evs = eventsFor(id, 400)
      for (let i = evs.length - 1; i >= 0; i--) {
        const e = evs[i]!
        if (e.kind === 'notice' && e.tone === 'error' && e.text.trim()) return e.text.trim()
        if (e.kind === 'user') return null
      }
      return null
    },
    week: async () => weekText(await getStats('week')),
    send: broadcast,
    keepAwake,
    load: () => ({ state: readJson(FILE, null), runs: readJson(RUNS_FILE, null) }),
    save: (st) => writeJson(FILE, st),
    saveRuns: (r) => writeJson(RUNS_FILE, r),
    // Workflows.
    shell: runShell,
    // A self-hosted ntfy on the local network or the tailnet: its host in settings.json's webhookPrivateHosts.
    post: (r) => postWebhook(r, { allowHosts: settings.webhookPrivateHosts ?? [] }),
    webhookPrivateHosts: () => settings.webhookPrivateHosts ?? [],
    checkFile: (p) => checkOutputFile(p, workPath),
    append: (p, line) => appendOutput(p, line, workPath),
    loadSecrets: () => readJson(SECRETS_FILE, {}),
    saveSecrets: (x) => writeJson(SECRETS_FILE, x),
    watch: watchFolder,
    gh: ghLook,
    ghReady,
    githubRepo,
    hooksInstalled: () => integrationStatus().hooks.installed,
    fireInfo: (id) => ({ url: `http://127.0.0.1:${LOCAL_PORT}/local/automations/${id}/fire`, command: fireCommand(id) }),
    wake(ms) {
      const at = Date.now() + Math.max(0, ms)
      if (timer && at >= timerAt) return
      if (timer) clearTimeout(timer)
      timerAt = at
      timer = setTimeout(() => {
        timer = null
        timerAt = Infinity
        void engine?.tick()
      }, Math.max(0, ms))
      timer.unref()
    },
  }
}

/** The engine for the routes, the snapshot and the Mac's status (made on first use; started by [startAutomations]). */
export function automations(): Automations {
  engine ??= new Automations(daemonIO())
  return engine
}

let started = false
/** At start: the engine, its taps on the broadcast and on PermissionDenied, and its first tick. */
export async function startAutomations(): Promise<void> {
  if (started) return
  started = true
  const e = automations()
  tapBroadcast((m) => e.tap(m))
  onDenied((sid, tool, input) => e.denied(sid, tool, input))
  onHookEvent((event, sid, tool, line) => e.hookEvent(event, sid, tool, line))
  // `hypernova automation fire <id>` on this computer (a webhook trigger's command).
  try {
    writeHypernovaBin()
  } catch (err) {
    log('the hypernova command was not written:', (err as Error).message)
  }
  process.on('exit', () => {
    try {
      e.flush()
    } catch {}
  })
  await e.tick()
}
