package com.prudnikweb.hypernova.data

import kotlinx.coroutines.flow.StateFlow

/** Why a dictation ended early: the phone has no recognizer the setting may use, the two-minute cap, the microphone busy or failing. */
enum class VoiceProblem { Offline, TooLong, MicBusy, Mic }

sealed interface DictationState {
    /** Mic on, the recognizer starting. */
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
