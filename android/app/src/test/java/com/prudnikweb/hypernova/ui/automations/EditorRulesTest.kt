package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.Automation
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.AutoMsg
import com.prudnikweb.hypernova.data.AutomationBook
import com.prudnikweb.hypernova.data.AutomationWire
import com.prudnikweb.hypernova.data.AutomationsState
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderModels
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.RunPage
import com.prudnikweb.hypernova.data.Then
import com.prudnikweb.hypernova.data.Trigger
import com.prudnikweb.hypernova.data.withRun
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The Automations tab and editor's rules (A1, A4–A7, A13, A14, Retry): pure, without a screen. */
class EditorRulesTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private fun at(day: LocalDate, hh: Int, mm: Int) = ZonedDateTime.of(day, LocalTime.of(hh, mm), zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 10, 16)
    private val now = at(today, 11, 40)
    private val start = Action.Start("/p", "Check the build", "anthropic", null, AutoMode.ALLOWED, emptyList(), Then.NOTIFY)

    private fun auto(id: String, enabled: Boolean = true, run: Run? = null, nextAt: Long? = null, trigger: Trigger = Trigger.Time("09:00", Trigger.WEEKDAYS)) =
        Automation(id, id, enabled, false, trigger, start, null, nextAt = nextAt, lastRun = run)

    private fun run(state: String, reason: String? = null, id: String = "ru_1") = Run(id, "au_1", "x", "schedule", now, null, state, reason, null, emptyList(), now, null)

    @Test fun anOnlyOnceRunThatNeedsYouLeadsThoughItsSwitchIsOff() {
        val list = listOf(
            auto("off"),
            auto("due", nextAt = now + 3_600_000),
            auto("once-asks", enabled = false, run = run(Run.NEEDS_YOU)),
            auto("events", trigger = Trigger.Plugged),
            auto("once-runs", enabled = false, run = run(Run.RUNNING)),
        ).map { if (it.id == "off") it.copy(enabled = false) else it }
        assertEquals(listOf("once-asks", "once-runs", "due", "events", "off"), sorted(list).map { it.id })
    }

    @Test fun aSpentOnceCanBeRenamedButNotSwitchedOnWithItsOldTime() {
        val spent = Trigger.Once(now - 60_000)
        val saved = AutomationInput("Remind me", false, false, spent, Action.Notify("x"), null)
        assertFalse("renamed, still off, same time", EditorRules.pastOnce(saved.copy(name = "Remind me later"), saved, now))
        assertTrue("switched on with the old time", EditorRules.pastOnce(saved.copy(enabled = true), saved, now))
        assertTrue("a new time in the past", EditorRules.pastOnce(saved.copy(trigger = Trigger.Once(now - 120_000)), saved, now))
        assertFalse(EditorRules.pastOnce(saved.copy(trigger = Trigger.Once(now + 60_000), enabled = true), saved, now))
        assertFalse("not a Once", EditorRules.pastOnce(saved.copy(trigger = Trigger.Plugged, enabled = true), saved, now))
    }

    @Test fun aSpentOnceMovesToItsNextDayAtThatTime() {
        // 9:00 has passed today: tomorrow at 9:00. 15:30 is still ahead: today.
        assertEquals(at(today.plusDays(1), 9, 0), EditorRules.nextOnce(Trigger.Once(at(today.minusDays(3), 9, 0)), now, zone).at)
        assertEquals(at(today, 15, 30), EditorRules.nextOnce(Trigger.Once(at(today.minusDays(1), 15, 30)), now, zone).at)
    }

    @Test fun theSessionThatFiredItNeedsASessionsTrigger() {
        val cont = Action.Continue(Action.Continue.TRIGGER, "Carry on", Then.NONE)
        assertTrue(EditorRules.badTarget(AutomationInput("x", true, false, Trigger.Time("09:00", Trigger.ALL_DAYS), cont, null)))
        assertFalse(EditorRules.badTarget(AutomationInput("x", true, false, Trigger.SessionEvent(Trigger.FINISHED, null, Trigger.MINE), cont, null)))
        assertFalse(EditorRules.badTarget(AutomationInput("x", true, false, Trigger.Time("09:00", Trigger.ALL_DAYS), cont.copy(target = Action.Continue.WORKING), null)))
    }

    @Test fun aroundTheClockRunsOnOneGridADay() {
        assertEquals("01:00", EditorRules.firstOfDay("09:00", 2))
        assertEquals("02:00", EditorRules.firstOfDay("08:00", 3))
        assertEquals("00:00", EditorRules.firstOfDay("08:00", 4))
        assertEquals("00:30", EditorRules.firstOfDay("12:30", 1))
        assertEquals("09:15", EditorRules.firstOfDay("09:15", 12).let { EditorRules.firstOfDay(it, 12) })
        assertEquals("not a time: as it was", "9", EditorRules.firstOfDay("9", 2))
    }

    @Test fun repeatableTemplatesStayAndTheOnePerAccountOnesGo() {
        // A Mac without workflows: every template that needs nothing of them.
        val all = Templates.ALL.filter { it.needs.isEmpty() }.map { it.id }
        assertEquals(all, Templates.offered(emptySet()).map { it.id })
        val used = setOf(Templates.CARRY_ON, Templates.RESET, Templates.BRIEF, Templates.SWITCH, Templates.DEBRIEF, Templates.BATTERY, Templates.PRS)
        // Budget again, a second morning brief, PR watch: offered again. Carry on, Switch, the debrief, the battery: once.
        assertEquals(listOf(Templates.RESET, Templates.NIGHT, Templates.BRIEF, Templates.PRS, Templates.PLUGGED), Templates.offered(used).map { it.id })
        // The workflow ones are repeatable: a second review for another repository.
        val flows = setOf(Templates.FIX_TESTS, Templates.REVIEW, Templates.DIGEST, Templates.TEAM)
        assertEquals(flows, Templates.offered(used + flows, com.prudnikweb.hypernova.data.Features.ALL).map { it.id }.filter { it in flows }.toSet())
    }

    @Test fun switchProviderAsksForAProviderFirst() {
        val claude = Provider("anthropic", "Claude", "anthropic", null, false, ProviderModels(), true)
        val or = Provider("p1", "OpenRouter", "openrouter", "https://openrouter.ai/api", true, ProviderModels(), false)
        val switch = Templates.byId(Templates.SWITCH)!!
        assertTrue(Templates.needsProvider(switch, listOf(claude)))
        assertFalse(Templates.needsProvider(switch, listOf(claude, or)))
        assertFalse(Templates.needsProvider(Templates.byId(Templates.BRIEF)!!, listOf(claude)))
    }

    @Test fun aProviderTheOwnerSaidIsFreeAsksForNothing() {
        val local = AutomationInput("x", true, false, Trigger.Plugged, start.copy(providerId = "p_local"), null)
        assertEquals("p_local", local.paidProvider("anthropic"))
        assertNull(local.paidProvider("anthropic", costs = { it != "p_local" }))
        assertFalse(local.paid("anthropic", costs = { false }))
    }

    @Test fun retryIsForRunsThatEndedBadly() {
        assertTrue(run(Run.FAILED, "error").retriable)
        assertTrue(run(Run.STOPPED, "away").retriable)
        assertTrue(run(Run.SKIPPED, "missed").retriable)
        assertFalse(run(Run.DONE).retriable)
        assertFalse(run(Run.RUNNING).retriable)
        val o = JSONObject("""{"id":"ru_2","automationId":"au_1","state":"running","retryOf":"ru_1","fired":{"by":"manual","at":1}}""")
        assertEquals("ru_1", AutomationWire.run(o)!!.retryOf)
        assertNull(AutomationWire.run(JSONObject("""{"id":"ru_3","state":"done"}"""))!!.retryOf)
    }

    @Test fun theRunsCountsKeepOneWindowAsRunsCome() {
        val p = RunPage(listOf(run(Run.DONE, id = "a")), total = 9, skipped = 2)
        val fresh = withRun(p, run(Run.RUNNING, id = "b"))
        assertEquals(listOf("b", "a"), fresh.runs.map { it.id })
        assertEquals(10, fresh.total)
        assertEquals(2, fresh.skipped)
        val skipped = withRun(fresh, run(Run.SKIPPED, "paused", id = "b"))
        assertEquals("the same run, not a new one", 10, skipped.total)
        assertEquals(3, skipped.skipped)
        assertEquals("said once", 3, withRun(skipped, run(Run.SKIPPED, "paused", id = "b")).skipped)
        // An older Mac said no counts: none are made up.
        assertNull(withRun(RunPage(), run(Run.DONE)).total)
    }

    @Test fun aRunsMessageUpdatesTheBook() {
        val book = object : AutomationBook() {
            fun load(s: AutomationsState) { applySnapshot(s) }
        }
        book.load(AutomationsState(false, listOf(auto("au_1"))))
        book.apply(AutoMsg.RunChanged(run(Run.FAILED, "error")))
        assertEquals(Run.FAILED, book.current!!.byId("au_1")!!.lastRun!!.state)
        book.apply(AutoMsg.Counts(paused = true))
        assertTrue(book.current!!.paused)
    }
}
