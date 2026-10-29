// Hypernova daemon entry point.
import { log, VERSION, API_PORT, LOCAL_PORT } from './util.ts'
import { discover, pollRegistry, pollTranscripts, tickUsage } from './hub.ts'
import { pollStatusLine } from './services.ts'
import { ensureProjectsRoot } from './files.ts'
import { startServers } from './http.ts'
import { refreshClaudeCommands, scanTerms, watchMenus, writeHnBin } from './terms.ts'
import { refreshHooks, refreshShell } from './hooks.ts'
import { startAccount } from './account.ts'
import { reapAttachments } from './attachments.ts'
import { pollAgents } from './agents.ts'
import { pollClaudeAgents } from './claude-agents.ts'
import { reapShots } from './shots.ts'
import { cleanSpeechTemp } from './speech.ts'
import { startStats } from './stats.ts'
import { startBattery } from './battery.ts'
import { startAwake } from './awake.ts'
import { startAutomations } from './automations.ts'
import { startTsnet, stopTsnet } from './tsnet.ts'
import { startNames } from './names.ts'
import { startUsageProbe } from './usage-probe.ts'
import { PLATFORM, has } from './platform.ts'
import { startCodex } from './codex/client.ts'
import { tickCodex } from './codex/account.ts'
import { restoreCodex } from './codex/sessions.ts'

log(`hypernova daemon ${VERSION} starting on ${PLATFORM} (pid ${process.pid}, api ${API_PORT}, local ${LOCAL_PORT})`)
process.on('uncaughtException', (e) => log('uncaught', e))
process.on('unhandledRejection', (e) => log('unhandled rejection', e))

// The folder new projects go in, on first run where the owner keeps code (files.ts).
try {
  ensureProjectsRoot()
} catch (e) {
  log('projects folder not checked', e)
}
writeHnBin()
refreshShell()
refreshHooks()
refreshClaudeCommands()
discover()
// Codex's threads Hypernova knows (codex/sessions.ts), before the terminals link to them.
restoreCodex()
scanTerms()
pollRegistry()
pollClaudeAgents()
pollStatusLine()
startServers()
// Codex's shared app-server, only when the owner uses Codex (codex/client.ts).
startCodex()
// Tailscale built in (tsnet.ts): after the servers, which the node forwards to.
void startTsnet()
// A name under hypernova.observer and its certificate for the LAN listener's pages (names.ts).
startNames()
startAccount()
// The Mac's battery and Stay awake (pmset, caffeinate): nothing to read or hold on Linux.
if (has('battery')) startBattery()
startAwake()
reapAttachments()
reapShots()
cleanSpeechTemp()
startStats()
// The per-model weeks (Fable, Sonnet) only Claude Code's /usage screen shows, and readings of the
// 5-hour window and the week for the pace: while a client is connected, every 2 minutes while
// something works, else every 15, and after a limit.
startUsageProbe()
// After the servers, the account, the battery and Stay awake: automations fire off all of them.
void startAutomations()

setInterval(pollRegistry, 2000)
setInterval(pollTranscripts, 700)
setInterval(pollAgents, 2000)
setInterval(pollClaudeAgents, 5000) // settings.claudeAgents: the whole `claude agents` list, finished jobs too
setInterval(pollStatusLine, 3000)
setInterval(tickUsage, 3000) // a window that resets with nothing running is announced then
setInterval(tickCodex, 3000) // and Codex's
setInterval(scanTerms, 1500)
setInterval(watchMenus, 800)
setInterval(reapAttachments, 6 * 3600_000)
setInterval(reapShots, 3600_000)

for (const sig of ['SIGTERM', 'SIGINT'] as const) {
  process.on(sig, () => {
    log(`stopping (${sig})`)
    // The helper is a child: it dies with us, but politely first.
    void stopTsnet().finally(() => process.exit(0))
  })
}
