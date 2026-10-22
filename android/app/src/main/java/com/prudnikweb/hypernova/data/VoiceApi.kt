package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

/** The Mac's speech to text (snapshot `stt`). */
@Immutable data class Stt(val provider: String?, val ready: Boolean, val error: String?)
/** `GET /v1/stt`: the state plus the key's last four characters. */
@Immutable data class SttInfo(val stt: Stt, val last4: String?, val keysUrl: String?)

enum class VoiceRoute { None, Soniox, Companion }
@Immutable data class Voice(val route: VoiceRoute, val stt: Stt? = null)

enum class VoiceProblem { Offline, NoKey, BadKey, NoCredit, Busy, Unreachable, Soniox, TooLong, MicBusy, Mic, Companion, CompanionBusy }

sealed interface DictationState {
    /** Mic on, link to the Mac opening. */
    data object Opening : DictationState
    data class Live(val final: String, val interim: String) : DictationState
    /** Released: [text] is everything heard; from [unsettledFrom] on it may still change. */
    data class Settling(val text: String, val unsettledFrom: Int) : DictationState
    /** The end. [text] may be empty; [problem] is why it ended early, if it did. */
    data class Ended(val text: String, val problem: VoiceProblem? = null) : DictationState
    data object Cancelled : DictationState
}

interface Dictation {
    val state: StateFlow<DictationState>
    /** SystemClock.elapsedRealtime() at touch-down. */
    val startedAt: Long
    /** 0..1, the voice level now; cheap; read it only while drawing. */
    fun level(): Float
    fun finish()
    fun cancel()
}

object VoiceTiming {
    const val HOLD_MS = 350L; const val SEND_HOLD_MS = 400L; const val TAIL_MS = 100L
    const val SETTLE_MS = 1_500L; const val MAX_MS = 120_000L; const val COUNTDOWN_MS = 105_000L
    const val CANCEL_DP = 80; const val UNCANCEL_DP = 64
}
