package com.prudnikweb.hypernova.ui.voice

import com.prudnikweb.hypernova.data.VoiceTiming

/*
 * The mic's gesture as one pure reducer (docs/specs/spec-voice.md, 3.2): hold to talk, tap for
 * hands-free, slide away to cancel, and Send's own tap and hold. The button feeds it touches and
 * clocks; it answers with the next state and what to do (start, finish, cancel, send, a haptic).
 * Nothing here knows about Compose or Android, so every transition is tested on the JVM.
 */

/** Where the gesture is. */
sealed interface MicState {
    data object Idle : MicState
    /** A finger is on the button and the mic is open. [append]: started from Send, so it adds to the box. */
    data class Holding(val downAt: Long, val append: Boolean) : MicState
    /** Held, and slid far enough away that letting go cancels. */
    data class Armed(val downAt: Long, val append: Boolean) : MicState
    /** Let go quickly: it keeps listening until Done. */
    data object HandsFree : MicState
    /**
     * Send with text in the box, pressed: a tap sends, a hold dictates more. [canSend]: Send works
     * now (nothing uploading); [canDictate]: the Mac offers voice and the phone is linked.
     */
    data class SendPressed(val downAt: Long, val canSend: Boolean, val canDictate: Boolean, val permitted: Boolean) : MicState
}

sealed interface MicEvent {
    /**
     * A finger on the button. [send]: it shows Send (text or files in the box). [permitted]: the
     * microphone may be used. [linked]: the Mac can be reached now. [voice]: the Mac offers voice.
     */
    data class Down(val at: Long, val send: Boolean, val canSend: Boolean, val voice: Boolean, val linked: Boolean, val permitted: Boolean) : MicEvent
    /** The finger is [dp] away from where it went down. */
    data class Move(val dp: Float) : MicEvent
    data class Up(val at: Long) : MicEvent
    /** The system took the touch (a gesture from the edge, a dialog). */
    data object PointerCancel : MicEvent
    /** Send has been held [VoiceTiming.SEND_HOLD_MS]. */
    data class SendHeld(val at: Long) : MicEvent
    /** Hands-free's Done (the pill), Back, or the app leaving the screen. */
    data object Done : MicEvent
    /** Hands-free's Cancel, or leaving the screen. */
    data object Cancel : MicEvent
    /** TalkBack's actions: start hands-free (a double tap on the mic; "Dictate more" on Send). */
    data class A11yStart(val append: Boolean, val linked: Boolean, val permitted: Boolean) : MicEvent
    /** The dictation ended or was cancelled by itself (an error, the two-minute cap). */
    data object Ended : MicEvent
}

enum class Haptic { Start, Tick, Confirm, Reject }

sealed interface MicEffect {
    data object AskPermission : MicEffect
    data object NotLinked : MicEffect
    data class Start(val append: Boolean) : MicEffect
    data object Finish : MicEffect
    data object Cancel : MicEffect
    data object Send : MicEffect
    data class Buzz(val haptic: Haptic) : MicEffect
}

data class MicStep(val state: MicState, val effects: List<MicEffect> = emptyList())

object MicGesture {
    fun reduce(s: MicState, e: MicEvent): MicStep = when (e) {
        is MicEvent.Ended -> MicStep(MicState.Idle)
        is MicEvent.Down -> down(s, e)
        is MicEvent.Move -> when (s) {
            is MicState.Holding -> if (e.dp > VoiceTiming.CANCEL_DP) MicStep(MicState.Armed(s.downAt, s.append), listOf(MicEffect.Buzz(Haptic.Tick))) else MicStep(s)
            // Hysteresis: back under 64 dp, not 80, so the edge doesn't flicker.
            is MicState.Armed -> if (e.dp < VoiceTiming.UNCANCEL_DP) MicStep(MicState.Holding(s.downAt, s.append)) else MicStep(s)
            else -> MicStep(s)
        }
        is MicEvent.Up -> when (s) {
            is MicState.Holding -> when {
                // From Send it only ever adds: no hands-free there.
                s.append || e.at - s.downAt >= VoiceTiming.HOLD_MS -> MicStep(MicState.Idle, listOf(MicEffect.Finish, MicEffect.Buzz(Haptic.Confirm)))
                else -> MicStep(MicState.HandsFree)
            }
            is MicState.Armed -> MicStep(MicState.Idle, listOf(MicEffect.Cancel, MicEffect.Buzz(Haptic.Reject)))
            is MicState.SendPressed -> MicStep(MicState.Idle, if (s.canSend) listOf(MicEffect.Send) else emptyList())
            else -> MicStep(s)
        }
        // Never lose words to a system gesture: what was heard goes into the box.
        is MicEvent.PointerCancel -> when (s) {
            is MicState.Holding, is MicState.Armed -> MicStep(MicState.Idle, listOf(MicEffect.Finish, MicEffect.Buzz(Haptic.Confirm)))
            is MicState.SendPressed -> MicStep(MicState.Idle)
            else -> MicStep(s)
        }
        is MicEvent.SendHeld -> when {
            s !is MicState.SendPressed || !s.canDictate -> MicStep(s)
            !s.permitted -> MicStep(MicState.Idle, listOf(MicEffect.AskPermission))
            else -> MicStep(MicState.Holding(e.at, append = true), listOf(MicEffect.Start(append = true), MicEffect.Buzz(Haptic.Start)))
        }
        is MicEvent.Done -> when (s) {
            MicState.HandsFree -> MicStep(MicState.Idle, listOf(MicEffect.Finish, MicEffect.Buzz(Haptic.Confirm)))
            // Back while the finger is down is ignored: the finger decides.
            else -> MicStep(s)
        }
        is MicEvent.Cancel -> when (s) {
            MicState.HandsFree, is MicState.Holding, is MicState.Armed -> MicStep(MicState.Idle, listOf(MicEffect.Cancel, MicEffect.Buzz(Haptic.Reject)))
            else -> MicStep(s)
        }
        is MicEvent.A11yStart -> when {
            s != MicState.Idle -> MicStep(s)
            !e.linked -> MicStep(s, listOf(MicEffect.NotLinked))
            !e.permitted -> MicStep(s, listOf(MicEffect.AskPermission))
            else -> MicStep(MicState.HandsFree, listOf(MicEffect.Start(e.append), MicEffect.Buzz(Haptic.Start)))
        }
    }

    private fun down(s: MicState, e: MicEvent.Down): MicStep = when (s) {
        // A tap on the button while hands-free is Done.
        MicState.HandsFree -> MicStep(MicState.Idle, listOf(MicEffect.Finish, MicEffect.Buzz(Haptic.Confirm)))
        MicState.Idle -> when {
            e.send -> MicStep(MicState.SendPressed(e.at, e.canSend, e.voice && e.linked, e.permitted))
            !e.voice -> MicStep(s)
            !e.linked -> MicStep(s, listOf(MicEffect.NotLinked))
            !e.permitted -> MicStep(s, listOf(MicEffect.AskPermission))
            else -> MicStep(MicState.Holding(e.at, append = false), listOf(MicEffect.Start(append = false), MicEffect.Buzz(Haptic.Start)))
        }
        // A second finger, or a down we missed the end of: keep what's going.
        else -> MicStep(s)
    }
}
