package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Where a server kept a client's log (`POST /v1/client-logs`; docs/PROTOCOL.md, "Client logs"). */
@Immutable
data class ClientLogSaved(val path: String, val name: String, val size: Long, val at: Long) {
    companion object {
        fun wire(o: JSONObject?): ClientLogSaved? {
            val path = o?.str("path")?.takeIf { it.isNotEmpty() } ?: return null
            return ClientLogSaved(path, o.str("name").orEmpty(), o.long("size") ?: 0, o.long("at") ?: 0)
        }
    }
}

/** The server doesn't have the route: it's from before there were client logs. Share still works. */
class ClientLogTooOld : HubException("")

/** Where Send logs stands, for either client's Settings. */
sealed interface DebugSendState {
    data object Idle : DebugSendState
    /** More than one server paired: which? */
    data class Choosing(val servers: List<ServerStatus>) : DebugSendState
    data class Sending(val server: String) : DebugSendState
    data class Sent(val server: String, val saved: ClientLogSaved) : DebugSendState
    /** [server] needs updating. */
    data class TooOld(val server: String) : DebugSendState
    data class Failed(val server: String, val why: String) : DebugSendState
}

/**
 * Send logs, the same on both clients: only ever on a tap, only to the server chosen ([send], then
 * [sendTo] with more than one paired), over that server's own link: the route its other requests go by.
 */
@Stable
class DebugSendFlow(
    private val scope: CoroutineScope,
    private val logger: () -> DebugLogger? = { DebugLog.logger },
    private val servers: () -> List<Pair<ServerStatus, Hub>> = { Core.everyHub },
) {
    var state by mutableStateOf<DebugSendState>(DebugSendState.Idle)
        private set

    fun send() {
        val all = servers()
        when (all.size) {
            0 -> Unit
            1 -> sendTo(all.single().first.id)
            else -> state = DebugSendState.Choosing(all.map { it.first })
        }
    }

    fun sendTo(id: String) {
        val (status, hub) = servers().firstOrNull { it.first.id == id } ?: return
        val l = logger() ?: return
        state = DebugSendState.Sending(status.name)
        scope.launch {
            state = withContext(Dispatchers.IO) { upload(l, hub, status.name) }
        }
    }

    fun dismiss() {
        state = DebugSendState.Idle
    }

    private suspend fun upload(l: DebugLogger, hub: Hub, name: String): DebugSendState {
        val file = File(File(l.folder, "outgoing"), "bundle.log")
        return try {
            val size = l.bundle(file)
            val r = hub.sendClientLog(Upload("hypernova-debug.log", "text/plain; charset=utf-8", size) { out -> file.inputStream().use { it.copyTo(out) } })
            r.fold(
                { saved -> DebugSendState.Sent(name, saved) },
                { e -> if (e is ClientLogTooOld) DebugSendState.TooOld(name) else DebugSendState.Failed(name, e.message.orEmpty()) },
            )
        } catch (e: java.io.IOException) {
            DebugLog.w("log", e) { "couldn't gather the log to send" }
            DebugSendState.Failed(name, e.message.orEmpty())
        } finally {
            file.delete()
            file.parentFile?.delete()
        }
    }
}
