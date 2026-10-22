package com.prudnikweb.hypernova

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.data.AccountWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Notifier
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while a Mac is paired, so the WebSocket (owned by [Core]) stays open
 * and approvals arrive at once, even with the app closed. Its permanent notification is a
 * calm summary ("2 working with 3 agents and 1 needs you") with a Disconnect action; while Claude
 * can't reply (a limit, signed out) its first line says that instead ("Session limit · back at
 * 2:00"), with the counts under it. Type specialUse: dataSync would be capped at 6 hours a day on
 * Android 15.
 */
class ConnectionService : Service() {
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(application)
        running = true
        goForeground(Summary(getString(R.string.app_name), getString(R.string.notif_connecting)))
        watch = Core.scope.launch {
            snapshotFlow { summary() }
                .distinctUntilChanged()
                .collect { s ->
                    if (s == null || !Core.wantsService()) stopSelf()
                    else runCatching { getSystemService(android.app.NotificationManager::class.java)?.notify(Notifier.CONNECTION_ID, notification(s)) }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Core.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        // Restarted by the system after the process died: pairing comes back through Core.init.
        if (!Core.wantsService()) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        running = false
        super.onDestroy()
    }

    private data class Summary(val title: String, val text: String)

    /** What the notification says, from the live state. Null when there's nothing to hold. */
    private fun summary(): Summary? {
        val live = Core.live ?: return null
        val link = live.link
        val title = live.mac.name
        val text = when (link.state) {
            LinkState.Unpaired -> return null
            LinkState.Connecting, LinkState.Demo -> getString(R.string.notif_connecting)
            LinkState.Reconnecting -> getString(R.string.notif_reconnecting)
            // Without a network nothing is tried until one comes (LiveHub.fail).
            LinkState.Offline -> getString(if (link.problem == Problem.NoNetwork) R.string.notif_no_network else R.string.notif_offline)
            LinkState.Connected -> {
                val alive = live.sessions.filter { it.phase != Phase.Ended }
                // Working by the one rule (Session.busy): agents still at work after their
                // session's main agent went idle keep it working, and are said with it.
                val working = alive.count { it.busy }
                val needs = alive.count { it.state == SessionState.NeedsYou }
                val agents = alive.filter { it.busy }.sumOf { it.agentsRunning }
                val parts = listOfNotNull(
                    working.takeIf { it > 0 }?.let {
                        val w = resources.getQuantityString(R.plurals.notif_working, it, it)
                        if (agents > 0) "$w ${resources.getQuantityString(R.plurals.notif_with_agents, agents, agents)}" else w
                    },
                    needs.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.notif_needs_you, it, it) },
                )
                // A phrase, as a notification can't draw: "2 working and 1 needs you".
                val counts = if (parts.isEmpty()) getString(R.string.notif_quiet) else com.prudnikweb.hypernova.data.Phrases.list(parts, getString(R.string.list_and))
                // A limit or a sign-out leads (owner's item 14: the last "2 working" mustn't stand
                // for a Claude that can't reply). LiveHub wakes this at the reset, so it moves on then.
                // Claude Code's account, only where it's installed; with Codex in use too, said as Claude's.
                if (com.prudnikweb.hypernova.data.AgentLead.claudeAvailable(live.claude)) {
                    AccountWords.headline(this, live.account, live.usage, live.mac.name, both = com.prudnikweb.hypernova.data.AgentLead.both(live.claude, live.codex))?.let { return Summary(it, counts) }
                } else if (com.prudnikweb.hypernova.data.AgentLead.codexLeads(live.claude, live.codex)) {
                    // Codex alone: its sign-out or its limit leads the same way.
                    val cx = live.codex
                    if (cx?.enabled == true && cx.account?.signedIn == false) return Summary(getString(R.string.notif_codex_signed_out, live.mac.name), counts)
                    if (cx?.enabled == true && com.prudnikweb.hypernova.data.AgentLead.codexLimitedWindow(cx.usage) != null) return Summary(getString(R.string.notif_codex_limited), counts)
                }
                counts
            }
        }
        return Summary(title, text)
    }

    private fun notification(s: Summary) = Core.notifier.connection(
        s.title, s.text,
        PendingIntent.getService(
            this, 2, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    private fun goForeground(s: Summary) {
        val n = notification(s)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.CONNECTION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.CONNECTION_ID, n)
        }
    }

    companion object {
        const val ACTION_DISCONNECT = "com.prudnikweb.hypernova.DISCONNECT"

        /** True while the service holds the link open: the home screen widgets are live then. */
        var running by mutableStateOf(false)
            private set

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ConnectionService::class.java)) }
        }
    }
}
