package com.prudnikweb.hypernova.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Unread replies on Home's Completed cards (docs/IDEAS.md, HM6; docs/DECISIONS.md, "Unread and
 * finished on Completed cards"). Kept on the phone, per server: opening a session marks it read up
 * to its last activity ([Session.updatedAt], the Mac's lastActivityAt), and a later activity is a
 * reply the owner hasn't seen. No daemon change: two phones each keep their own.
 *
 * The rules and the store are plain Kotlin over [KeyValue], so they run in unit tests.
 */

object Unread {
    /**
     * A reply he hasn't read: its last activity ([updatedAt]) is later than what was on screen when
     * he last opened it ([readUpTo]). One never opened on this phone counts from [since], when this
     * phone started keeping track for its server: what finished before then (every old job, the first
     * time the app runs) is read, what finished after is not.
     */
    fun unread(updatedAt: Long, readUpTo: Long?, since: Long): Boolean = updatedAt > (readUpTo ?: since)
}

/**
 * When a Completed card's session finished, short: "just now", "5m ago", "2h ago", "yesterday",
 * "3d ago". Under a day it's hours; after that by the calendar ([zone]), so "yesterday" is the day
 * before today.
 */
sealed interface Finished {
    data object Now : Finished
    data class Minutes(val n: Int) : Finished
    data class Hours(val n: Int) : Finished
    data object Yesterday : Finished
    data class Days(val n: Int) : Finished

    companion object {
        fun of(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Finished {
            val s = ((now - at) / 1000).coerceAtLeast(0)
            return when {
                s < 45 -> Now
                s < 3600 -> Minutes(maxOf(1L, s / 60).toInt())
                s < 86_400 -> Hours((s / 3600).toInt())
                else -> {
                    val days = ChronoUnit.DAYS.between(
                        Instant.ofEpochMilli(at).atZone(zone).toLocalDate(),
                        Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
                    ).toInt().coerceAtLeast(1)
                    if (days == 1) Yesterday else Days(days)
                }
            }
        }
    }
}

/**
 * One server's read marks: when this phone started keeping track ([since], set the first time it's
 * asked) and, per session id, the last activity it had when it was on screen.
 */
class ReadMarks(private val kv: KeyValue, private val clock: () -> Long = System::currentTimeMillis) {
    fun since(): Long {
        kv.get(SINCE)?.toLongOrNull()?.let { return it }
        val now = clock()
        kv.put(SINCE, now.toString())
        return now
    }

    fun readUpTo(id: String): Long? = kv.get(READ + id)?.toLongOrNull()

    /** Read up to [at]; never back. True when that changed what's kept. */
    fun mark(id: String, at: Long): Boolean {
        val old = readUpTo(id)
        if (old != null && old >= at) return false
        kv.put(READ + id, at.toString())
        return true
    }

    fun unread(s: Session): Boolean = Unread.unread(s.updatedAt, readUpTo(s.id), since())

    /**
     * A rewind replaced session [old] with [to], last active at [at]: a session he had opened is
     * still one he has read (the rewind added no reply), and the old id's mark goes. Taken once:
     * nothing happens for an id with no mark, so being told again can't hide a later reply.
     */
    fun moved(old: String, to: String, at: Long): Boolean {
        if (readUpTo(old) == null) return false
        kv.put(READ + old, null)
        return mark(to, at)
    }

    companion object {
        private const val SINCE = "since"
        private const val READ = "read."
    }
}

/**
 * The app's read marks, a preferences file per server (`hypernova.read`, [Servers.scoped]). [version]
 * moves with each new mark, so Home redraws its cards when it's back.
 */
object ReadState {
    var version by mutableIntStateOf(0)
        private set
    private val marks = HashMap<String, ReadMarks>()

    /** The server [hub] is: a paired computer's id; null for any other hub, which keeps none. */
    fun server(hub: Hub): String? = serverOf(hub)

    /** How [server] tells: a test's hub names a server of its own here. */
    var serverOf: (Hub) -> String? = { (it as? LiveHub)?.serverId }

    @Synchronized
    fun of(storage: Storage, server: String): ReadMarks = marks.getOrPut(server) {
        ReadMarks(PrefsKeyValue(storage.prefs(Servers.scoped("hypernova.read", server))))
    }

    /** [s] has a reply not read on this phone ([marks]). Read in a composition, a new mark redraws it. */
    fun unread(marks: ReadMarks, s: Session): Boolean {
        version
        return marks.unread(s)
    }

    /** A rewind on [server] replaced session [old] with [to] ([ReadMarks.moved]). */
    fun moved(storage: Storage, server: String, old: String, to: String, at: Long) {
        if (of(storage, server).moved(old, to, at)) version++
    }

    /** Session [id] on [server] was on screen with its last activity at [at]. */
    fun mark(storage: Storage, server: String, id: String, at: Long) {
        if (of(storage, server).mark(id, at)) version++
    }
}

