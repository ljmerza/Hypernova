package com.prudnikweb.hypernova.ui.voice

import com.prudnikweb.hypernova.data.VoiceTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mic's gesture (docs/specs/spec-voice.md, 3.2 and section 7, Android test 3): every transition. */
class MicGestureTest {
    private fun down(at: Long = 0, send: Boolean = false, canSend: Boolean = send, voice: Boolean = true, linked: Boolean = true, permitted: Boolean = true) =
        MicEvent.Down(at, send, canSend, voice, linked, permitted)

    /** Runs [events] from [from]; the state at the end and every effect on the way. */
    private fun run(vararg events: MicEvent, from: MicState = MicState.Idle): MicStep {
        var s = from
        val fx = mutableListOf<MicEffect>()
        for (e in events) {
            val step = MicGesture.reduce(s, e)
            s = step.state
            fx += step.effects
        }
        return MicStep(s, fx)
    }

    private fun MicStep.did(vararg want: MicEffect) = assertEquals(want.toList(), effects.filter { it !is MicEffect.Buzz })
    private fun MicStep.felt(vararg want: Haptic) = assertEquals(want.toList(), effects.filterIsInstance<MicEffect.Buzz>().map { it.haptic })

    @Test
    fun down_without_permission_asks_and_stays_idle() {
        val r = run(down(permitted = false))
        assertEquals(MicState.Idle, r.state)
        r.did(MicEffect.AskPermission)
    }

    @Test
    fun down_while_the_mac_is_away_says_so() {
        val r = run(down(linked = false))
        assertEquals(MicState.Idle, r.state)
        r.did(MicEffect.NotLinked)
    }

    @Test
    fun down_with_no_voice_does_nothing() {
        assertEquals(MicStep(MicState.Idle), run(down(voice = false)))
    }

    @Test
    fun down_opens_the_mic_at_once() {
        val r = run(down(at = 100))
        assertEquals(MicState.Holding(100, append = false), r.state)
        r.did(MicEffect.Start(append = false))
        r.felt(Haptic.Start)
    }

    @Test
    fun a_long_hold_finishes_on_release() {
        val r = run(down(at = 0), MicEvent.Up(VoiceTiming.HOLD_MS))
        assertEquals(MicState.Idle, r.state)
        r.did(MicEffect.Start(false), MicEffect.Finish)
        r.felt(Haptic.Start, Haptic.Confirm)
    }

    @Test
    fun a_quick_tap_goes_hands_free_until_done() {
        val tap = run(down(at = 0), MicEvent.Up(VoiceTiming.HOLD_MS - 1))
        assertEquals(MicState.HandsFree, tap.state)
        tap.did(MicEffect.Start(false))
        // Done, a tap on the button (the check), Back or the app leaving: all finish.
        for (end in listOf(MicEvent.Done, down(at = 5_000))) {
            val r = run(end, from = MicState.HandsFree)
            assertEquals(MicState.Idle, r.state)
            r.did(MicEffect.Finish)
            r.felt(Haptic.Confirm)
        }
        val c = run(MicEvent.Cancel, from = MicState.HandsFree)
        assertEquals(MicState.Idle, c.state)
        c.did(MicEffect.Cancel)
        c.felt(Haptic.Reject)
    }

    @Test
    fun sliding_away_arms_cancel_with_hysteresis() {
        val held = MicState.Holding(0, false)
        assertEquals(held, run(MicEvent.Move(80f), from = held).state)
        val armed = run(MicEvent.Move(80.5f), from = held)
        assertEquals(MicState.Armed(0, false), armed.state)
        armed.felt(Haptic.Tick)
        // Between 64 and 80 dp it stays as it is, either way.
        assertEquals(MicState.Armed(0, false), run(MicEvent.Move(70f), from = MicState.Armed(0, false)).state)
        assertEquals(held, run(MicEvent.Move(70f), from = held).state)
        assertEquals(held, run(MicEvent.Move(63f), from = MicState.Armed(0, false)).state)
        // Let go while armed: cancelled, never sent anywhere.
        val r = run(MicEvent.Up(5_000), from = MicState.Armed(0, false))
        assertEquals(MicState.Idle, r.state)
        r.did(MicEffect.Cancel)
        r.felt(Haptic.Reject)
        // Slid back, then let go: finished as usual.
        val back = run(MicEvent.Move(90f), MicEvent.Move(40f), MicEvent.Up(2_000), from = held)
        back.did(MicEffect.Finish)
    }

    @Test
    fun a_system_gesture_never_loses_the_words() {
        for (from in listOf(MicState.Holding(0, false), MicState.Armed(0, false))) {
            val r = run(MicEvent.PointerCancel, from = from)
            assertEquals(MicState.Idle, r.state)
            r.did(MicEffect.Finish)
        }
    }

    @Test
    fun back_while_holding_is_ignored() {
        val held = MicState.Holding(0, false)
        assertEquals(MicStep(held), run(MicEvent.Done, from = held))
    }

    @Test
    fun send_with_text_taps_to_send() {
        val r = run(down(at = 0, send = true), MicEvent.Up(VoiceTiming.SEND_HOLD_MS - 1))
        assertEquals(MicState.Idle, r.state)
        r.did(MicEffect.Send)
        // Still uploading: a tap does nothing.
        run(down(at = 0, send = true, canSend = false), MicEvent.Up(10)).did()
    }

    @Test
    fun send_held_400ms_dictates_more_and_never_goes_hands_free() {
        val pressed = run(down(at = 0, send = true))
        assertEquals(MicState.SendPressed(0, canSend = true, canDictate = true, permitted = true), pressed.state)
        val held = run(MicEvent.SendHeld(VoiceTiming.SEND_HOLD_MS), from = pressed.state)
        assertEquals(MicState.Holding(VoiceTiming.SEND_HOLD_MS, append = true), held.state)
        held.did(MicEffect.Start(append = true))
        held.felt(Haptic.Start)
        // A quick release after the hold still finishes (no hands-free from Send).
        val r = run(MicEvent.Up(VoiceTiming.SEND_HOLD_MS + 50), from = held.state)
        assertEquals(MicState.Idle, r.state)
        r.did(MicEffect.Finish)
        // And it can be cancelled by sliding away.
        run(MicEvent.Move(100f), MicEvent.Up(900), from = held.state).did(MicEffect.Cancel)
    }

    @Test
    fun send_held_without_voice_or_permission() {
        val noVoice = run(down(send = true, voice = false), MicEvent.SendHeld(400))
        assertTrue(noVoice.state is MicState.SendPressed)
        run(MicEvent.Up(900), from = noVoice.state).did(MicEffect.Send)
        val noMic = run(down(send = true, permitted = false), MicEvent.SendHeld(400))
        assertEquals(MicState.Idle, noMic.state)
        noMic.did(MicEffect.AskPermission)
    }

    @Test
    fun the_dictation_ending_by_itself_resets_the_gesture() {
        for (from in listOf(MicState.Holding(0, false), MicState.Armed(0, true), MicState.HandsFree)) {
            assertEquals(MicStep(MicState.Idle), run(MicEvent.Ended, from = from))
        }
        // The finger lifting afterwards does nothing more.
        assertEquals(MicStep(MicState.Idle), run(MicEvent.Up(10_000)))
    }

    @Test
    fun talkback_starts_hands_free_and_dictates_more() {
        val r = run(MicEvent.A11yStart(append = false, linked = true, permitted = true))
        assertEquals(MicState.HandsFree, r.state)
        r.did(MicEffect.Start(false))
        run(MicEvent.A11yStart(append = true, linked = true, permitted = true)).did(MicEffect.Start(true))
        run(MicEvent.A11yStart(append = false, linked = true, permitted = false)).did(MicEffect.AskPermission)
        run(MicEvent.A11yStart(append = false, linked = false, permitted = true)).did(MicEffect.NotLinked)
    }
}
