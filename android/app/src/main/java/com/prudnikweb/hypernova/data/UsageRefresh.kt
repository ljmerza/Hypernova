package com.prudnikweb.hypernova.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Asks the Mac for a fresh look at Claude Code's /usage screen (`POST /v1/usage/refresh`,
 * docs/PROTOCOL.md "Per-model weeks") when the owner opens his usage: the sun jumps to the usage
 * card, or the chat's usage breakdown opens. Otherwise the per-model weeks (Fable's) are only read
 * every 15 minutes, so the meter he came to look at could be that old.
 *
 * Fire and forget: whatever the look finds reaches every app as a `usage` message, so the answer
 * itself isn't needed, and a Mac without the route (404) or a slow look changes nothing on screen.
 * At most once every few minutes from this phone, because each look starts Claude Code on the Mac
 * for several seconds; the Mac also joins looks that overlap.
 */
object UsageRefresh {
    const val EVERY_MS = 5 * 60_000L

    @Volatile private var last = 0L

    /** Whether a look asked at [now] goes out, given the last one went out at [last]. */
    fun due(now: Long, last: Long): Boolean = last == 0L || now - last >= EVERY_MS || now < last

    fun ask() {
        val p = Core.pairing ?: return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!due(now, last)) return
            last = now
        }
        // The app's scope, not the screen's: closing the sheet mustn't cancel a look under way.
        Core.scope.launch {
            try {
                Api(p.baseUrl, p.token).post("/v1/usage/refresh")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // An older Mac, a dropped connection, a look that took too long: the timed looks carry on.
            }
        }
    }
}
