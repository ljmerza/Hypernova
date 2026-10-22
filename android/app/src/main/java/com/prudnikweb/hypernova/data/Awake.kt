package com.prudnikweb.hypernova.data

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import com.prudnikweb.hypernova.R
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Stay awake (docs/PROTOCOL.md, "Stay awake"): whether the Mac is kept awake for its sessions, the
 * phone and the glasses, and in Backpack mode with its lid closed in a bag. The Mac decides and
 * holds; the phone shows it and picks the mode.
 */
@Immutable
data class MacAwake(
    /** [WORKING], [BACKPACK] or [OFF]. */
    val mode: String,
    /** Keeping the Mac awake right now. */
    val holding: Boolean,
    /** A closed lid won't put it to sleep now. */
    val lidClosedOk: Boolean = false,
    /** Backpack's one-time setup on the Mac is done. */
    val backpackReady: Boolean = false,
    /** When Backpack ends by itself (epoch ms). */
    val until: Long? = null,
    /** "battery", "hot", "time" (a guard), "setup" or "failed" (Backpack's switch); null: nothing. */
    val reason: String? = null,
    /** When a guard lets the Mac sleep (counting down now). */
    val sleepAt: Long? = null,
    /** The lid is closed; null without a lid. */
    val lidClosed: Boolean? = null,
    /** A MacBook: Backpack and the battery guard apply. */
    val laptop: Boolean = true,
    /** The battery level the Mac is let go at. */
    val minBattery: Int = 15,
    /**
     * Whose SleepDisabled is on: "backpack", or "other" (another app's, Amphetamine's say, which keeps
     * the Mac awake whatever Hypernova does); null when it's off.
     */
    val sleepDisabledBy: String? = null,
    /** The app macOS names as keeping the Mac awake, when it's another app's. */
    val otherApp: String? = null,
    /** Backpack ends within 10 minutes: "+2 h" is on offer ([Hub.extendAwake]). */
    val extendable: Boolean = false,
) {
    /** Another app keeps the Mac awake: Hypernova letting go won't put it to sleep. */
    val keptByOther: Boolean get() = sleepDisabledBy == "other"

    /** A guard has let the Mac sleep (it no longer holds because of it). */
    val letGo: Boolean get() = !holding && sleepAt == null && (reason == BATTERY || reason == HOT)

    /** Kept awake with the lid closed right now: in a bag, most likely. */
    val awakeLidClosed: Boolean get() = holding && lidClosed == true

    companion object {
        const val WORKING = "working"
        const val BACKPACK = "backpack"
        const val OFF = "off"
        const val BATTERY = "battery"
        const val HOT = "hot"
        const val TIME = "time"

        /** `Snapshot.mac.awake` or `{t:"awake"}`'s; null from a Mac too old to say, or a mode this app doesn't know. */
        fun parse(o: JSONObject?): MacAwake? {
            val mode = o?.str("mode")?.takeIf { it == WORKING || it == BACKPACK || it == OFF } ?: return null
            return MacAwake(
                mode = mode,
                holding = o.bool("holding"),
                lidClosedOk = o.bool("lidClosedOk"),
                backpackReady = o.bool("backpackReady"),
                until = o.long("until"),
                reason = o.str("reason"),
                sleepAt = o.long("sleepAt"),
                lidClosed = if (o.has("lidClosed") && !o.isNull("lidClosed")) o.optBoolean("lidClosed") else null,
                laptop = o.optBoolean("laptop", true),
                minBattery = o.int("minBattery") ?: 15,
                sleepDisabledBy = o.str("sleepDisabledBy"),
                otherApp = o.str("otherApp"),
                extendable = o.bool("extendable"),
            )
        }
    }
}

/**
 * `{t:"mac_sleep_soon"}`: the Mac is let go in [inSeconds], for [reason]. [stillAwake]: another
 * app ([otherApp] when macOS names it) keeps it awake all the same, so it won't sleep.
 */
@Immutable
data class SleepSoon(val inSeconds: Int, val reason: String, val percent: Int?, val stillAwake: Boolean = false, val otherApp: String? = null) {
    companion object {
        fun parse(o: JSONObject): SleepSoon? {
            val s = o.int("inSeconds") ?: return null
            return SleepSoon(s.coerceAtLeast(0), o.str("reason") ?: "", o.dbl("percent")?.roundToInt(), o.bool("stillAwake"), o.str("otherApp"))
        }
    }
}

/** `{t:"mac_backpack_ending"}`: Backpack ends in [inSeconds] (at [until], epoch ms); "+2 h" is on offer. */
@Immutable
data class BackpackEnding(val inSeconds: Int, val until: Long?) {
    companion object {
        fun parse(o: JSONObject): BackpackEnding? = o.int("inSeconds")?.let { BackpackEnding(it.coerceAtLeast(0), o.long("until")) }
    }
}

/** Stay awake in words: the Mac card's line, the line under the picker, the pop-up. */
object AwakeWords {
    /**
     * "Awake with the lid closed", "Sleeps in 2 minutes", "Stay awake off": a phrase; the Mac card
     * draws its battery beside it (ui/settings/StayAwake.kt), never "Awake · lid closed · 62%".
     */
    fun status(res: Resources, a: MacAwake, now: Long): String = when {
        // Never "sleeps" while another app keeps the Mac awake: it won't.
        a.sleepAt != null && a.keptByOther -> res.getString(R.string.awake_state_lets_go_in, soon(res, (a.sleepAt - now) / 1000))
        a.sleepAt != null -> res.getString(R.string.awake_state_sleeps_in, soon(res, (a.sleepAt - now) / 1000))
        a.letGo && a.keptByOther -> res.getString(R.string.awake_state_kept_by, other(res, a.otherApp))
        a.letGo -> res.getString(R.string.awake_state_let_go)
        a.mode == MacAwake.OFF -> res.getString(R.string.awake_state_off)
        a.holding -> res.getString(if (a.lidClosed == true) R.string.awake_state_awake_lid else R.string.awake_state_awake)
        else -> res.getString(R.string.awake_state_idle)
    }

    /** "battery 15%", "it's running hot", "8 hours are up"; null for anything else. */
    fun why(res: Resources, reason: String?, percent: Int?): String? = when (reason) {
        MacAwake.BATTERY -> if (percent != null) res.getString(R.string.awake_why_battery, percent) else res.getString(R.string.awake_why_battery_low)
        MacAwake.HOT -> res.getString(R.string.awake_why_hot)
        MacAwake.TIME -> res.getString(R.string.awake_why_time)
        else -> null
    }

    /** The app keeping the Mac awake, or "another app" when macOS names none. */
    fun other(res: Resources, app: String?): String = app ?: res.getString(R.string.awake_another_app)

    /**
     * "Your Mac will sleep in 2 minutes: battery 15%". With another app keeping the Mac awake
     * ([stillAwake]) it says that instead: "Hypernova lets go in 2 minutes: battery 15%, but
     * Amphetamine is still keeping your Mac awake".
     */
    fun sleepSoon(res: Resources, inSeconds: Long, reason: String?, percent: Int?, stillAwake: Boolean = false, otherApp: String? = null): String {
        val inTime = soon(res, inSeconds)
        if (stillAwake && reason == MacAwake.TIME) return res.getString(R.string.awake_backpack_ends_kept, inTime, other(res, otherApp))
        val base = res.getString(if (stillAwake) R.string.awake_lets_go_in else R.string.awake_sleep_soon, inTime)
        val said = why(res, reason, percent)?.let { res.getString(R.string.awake_dot, base, it) } ?: base
        return if (stillAwake) res.getString(R.string.awake_but_kept, said, other(res, otherApp)) else said
    }

    /** The line under the picker, when there's something to say: (text, a warning?). */
    fun line(res: Resources, a: MacAwake, battery: MacBattery?, now: Long): Pair<String, Boolean>? = when {
        a.sleepAt != null -> sleepSoon(res, (a.sleepAt - now) / 1000, a.reason, battery?.percent, a.keptByOther, a.otherApp) to true
        a.letGo && a.keptByOther && a.reason == MacAwake.BATTERY ->
            res.getString(R.string.awake_let_go_battery_kept, battery?.percent ?: a.minBattery, other(res, a.otherApp)) to true
        a.letGo && a.keptByOther -> res.getString(R.string.awake_let_go_hot_kept, other(res, a.otherApp)) to true
        a.letGo && a.reason == MacAwake.BATTERY -> res.getString(R.string.awake_let_go_battery, battery?.percent ?: a.minBattery) to true
        a.letGo -> res.getString(R.string.awake_let_go_hot) to true
        a.reason == "setup" -> res.getString(R.string.awake_problem_setup) to true
        a.reason == "failed" && a.mode == MacAwake.BACKPACK -> res.getString(R.string.awake_problem_failed) to true
        a.mode == MacAwake.BACKPACK && a.holding && a.until != null ->
            res.getString(R.string.awake_backpack_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(a.until)), a.minBattery) to false
        else -> null
    }

    /** "2 minutes", "1 minute", "40 seconds". */
    fun soon(res: Resources, seconds: Long): String {
        val s = seconds.coerceAtLeast(0).toInt()
        return if (s >= 60) {
            val m = ((s + 30) / 60).coerceAtLeast(1)
            res.getQuantityString(R.plurals.awake_minutes, m, m)
        } else res.getQuantityString(R.plurals.awake_seconds, s, s)
    }

    fun modeLabel(res: Resources, mode: String): String = when (mode) {
        MacAwake.BACKPACK -> res.getString(R.string.awake_mode_backpack)
        MacAwake.OFF -> res.getString(R.string.awake_mode_off)
        else -> res.getString(R.string.awake_mode_working)
    }

    /** [backpack]: Backpack's words, by the agents in use ("Claude keeps working in your bag", or Codex, or both). */
    fun modeHelp(res: Resources, mode: String, backpack: Int = R.string.awake_help_backpack): String = when (mode) {
        MacAwake.BACKPACK -> res.getString(backpack)
        MacAwake.OFF -> res.getString(R.string.awake_help_off)
        else -> res.getString(R.string.awake_help_working)
    }
}
