package com.prudnikweb.hypernova.data.live

import android.Manifest
import android.app.NotificationManager
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AutomationError
import com.prudnikweb.hypernova.data.AutomationNotes
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.NotifyActions
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.strings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Automations on the phone (data/AutomationsLive.kt; docs/PROTOCOL.md, "Automations", "Workflows"):
 * the real hub's [com.prudnikweb.hypernova.data.Automations] against a [FakeDaemon] that answers with
 * the contract fixtures. Each call's request, and what the list shows after it; the socket's five
 * messages; a note as a banner on screen and a notification off it, an ask's with its answers.
 */
@RunWith(RobolectricTestRunner::class)
class AutomationsLiveTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }
    private val autos get() = rig.hub.automations
    private val res get() = rig.context.resources

    private companion object {
        const val AU = "au_0000000001"
        const val RU = "ru_0000000001"
    }

    @After fun tearDown() = rig.close()

    /** The automation of the fixtures (ask "Deploy now?", then notify), its runs and notes, in the snapshot. */
    private fun connect(paused: Boolean = false, lastRun: JSONObject? = null, notes: JSONArray = JSONArray()) {
        val a = Fixtures.json("rest-automation").getJSONObject("automation")
        if (lastRun != null) a.put("lastRun", lastRun)
        daemon.snapshot = snapshot {
            put("automations", JSONObject().put("paused", paused).put("list", JSONArray().put(a)).put("notes", notes).put("features", JSONArray(listOf("steps", "ask"))))
        }
        rig.connect()
    }

    private fun last(method: String, path: String) = daemon.calls(method, path).last()

    private fun lastRun() = autos.state!!.byId(AU)!!.lastRun

    @Test fun theSnapshotsListAndThenTheSocketsFiveMessages() {
        connect()
        val st = autos.state!!
        assertFalse(st.paused)
        assertEquals(listOf(AU), st.list.map { it.id })
        assertEquals(listOf("st_q", "st_n"), st.byId(AU)!!.steps!!.map { it.id })
        assertEquals(setOf("steps", "ask"), st.features)

        // {t:"run"}: the card's run follows; a run that asks is one of the asks.
        val run = Fixtures.json("rest-automation-run").getJSONObject("run")
        rig.push(JSONObject().put("t", "run").put("run", run)) { lastRun()?.state == Run.NEEDS_YOU }
        assertEquals(listOf(RU), autos.state!!.asks.map { it.id })
        rig.push(Fixtures.json("ws-run")) { lastRun()?.state == Run.DONE }
        assertTrue(autos.state!!.asks.isEmpty())

        // {t:"automation"}: changed in its place.
        val renamed = Fixtures.json("ws-automation")
        renamed.getJSONObject("automation").put("name", "Deploy gate")
        rig.push(renamed) { autos.state!!.byId(AU)!!.name == "Deploy gate" }
        // {t:"automations"}: Pause all, said by another phone.
        rig.push(Fixtures.json("ws-automations")) { autos.state!!.paused }
        // {t:"automation.removed"}: off the list.
        rig.push(Fixtures.json("ws-automation-removed")) { autos.state!!.list.isEmpty() }
    }

    @Test fun switchOnAndOffAndPauseAllPostTheirWordsAndTheListFollows() {
        connect()
        daemon.ok("POST", "/v1/automations/$AU/enabled", Fixtures.json("rest-automation-enabled"))
        val off = rig.call { autos.setEnabled(AU, false) }.getOrThrow()
        assertFalse(off.enabled)
        assertEquals(JSONObject().put("enabled", false).toString(), last("POST", "/v1/automations/$AU/enabled").json.toString())
        assertFalse(autos.state!!.byId(AU)!!.enabled)

        daemon.ok("POST", "/v1/automations/paused", Fixtures.json("rest-automations-paused"))
        assertTrue(rig.call { autos.setPaused(true) }.isSuccess)
        assertTrue(last("POST", "/v1/automations/paused").json.getBoolean("paused"))
        assertTrue(autos.state!!.paused)
        // An older Mac's answer without `paused`: what was asked for.
        daemon.ok("POST", "/v1/automations/paused", JSONObject())
        rig.call { autos.setPaused(false) }
        assertFalse(autos.state!!.paused)
    }

    @Test fun deleteTakesItOffTheList() {
        connect()
        daemon.ok("DELETE", "/v1/automations/$AU", JSONObject().put("ok", true))
        assertTrue(rig.call { autos.delete(AU) }.isSuccess)
        assertEquals(1, daemon.count("DELETE", "/v1/automations/$AU"))
        assertTrue(autos.state!!.list.isEmpty())
    }

    @Test fun runNowShowsItsRunAtOnceAndARefusalIsSaidInTheAppsWords() {
        connect()
        daemon.ok("POST", "/v1/automations/$AU/run", Fixtures.json("rest-automation-run"))
        val r = rig.call { autos.runNow(AU) }.getOrThrow()
        assertEquals(RU, r.id)
        assertEquals("{}", last("POST", "/v1/automations/$AU/run").body)
        assertEquals(Run.NEEDS_YOU, lastRun()?.state)

        // Running already (409): the app's own sentence, the code kept for the editor.
        daemon.on("POST", "/v1/automations/$AU/run") { refusal(409, "running", "It’s running already.") }
        val busy = rig.call { autos.runNow(AU) }.exceptionOrNull() as AutomationError
        assertEquals("running", busy.code)
        assertEquals(res.getString(R.string.au_err_running), busy.message)
        // At a limit: when it lifts comes with it, for the card's line.
        daemon.on("POST", "/v1/automations/$AU/run") { refusal(409, "limited", "At the limit.") { put("until", 1_800_000_000_000L) } }
        val limited = rig.call { autos.runNow(AU) }.exceptionOrNull() as AutomationError
        assertEquals(1_800_000_000_000L, limited.until)
        // A code this build doesn't know: the Mac's own words.
        daemon.on("POST", "/v1/automations/$AU/run") { refusal(409, "brand_new", "Something new got in the way.") }
        assertEquals("Something new got in the way.", rig.call { autos.runNow(AU) }.exceptionOrNull()!!.message)
    }

    @Test fun stopAndRetryFromAStep() {
        connect(lastRun = Fixtures.json("rest-automation-run").getJSONObject("run"))
        daemon.ok("POST", "/v1/automations/runs/$RU/stop", Fixtures.json("rest-automation-stop"))
        val stopped = rig.call { autos.stop(RU) }.getOrThrow()
        assertEquals(Run.STOPPED, stopped.state)
        assertEquals("{}", last("POST", "/v1/automations/runs/$RU/stop").body)
        assertEquals(Run.STOPPED, lastRun()?.state)

        // Retry from a step names it; a plain Retry sends nothing.
        val retried = Fixtures.json("rest-automation-retry")
        daemon.ok("POST", "/v1/automations/runs/$RU/retry", retried)
        val again = rig.call { autos.retry(RU, "st_q") }.getOrThrow()
        assertEquals("st_q", last("POST", "/v1/automations/runs/$RU/retry").json.getString("fromStep"))
        assertEquals("st_q", again.retryFrom)
        assertEquals(Run.NEEDS_YOU, lastRun()?.state)
        rig.call { autos.retry(RU) }
        assertEquals("{}", last("POST", "/v1/automations/runs/$RU/retry").body)
    }

    @Test fun anAnswerFromTheAppEndsTheAskAndOneAnsweredElsewhereFirstPutsTheRunAsItIs() {
        connect(lastRun = Fixtures.json("rest-automation-run").getJSONObject("run"))
        assertEquals(listOf(RU), autos.state!!.asks.map { it.id })
        daemon.ok("POST", "/v1/automations/runs/$RU/answer", Fixtures.json("rest-automation-answer"))
        val done = rig.call { autos.answer(RU, "st_q", "Deploy") }.getOrThrow()
        assertEquals(Run.DONE, done.state)
        val b = last("POST", "/v1/automations/runs/$RU/answer").json
        assertEquals("st_q", b.getString("stepId"))
        assertEquals("Deploy", b.getString("answer"))
        assertTrue(autos.state!!.asks.isEmpty())
    }

    @Test fun anAnswerTooLateFailsButTheCardShowsTheRunTheMacHas() {
        connect(lastRun = Fixtures.json("rest-automation-run").getJSONObject("run"))
        daemon.on("POST", "/v1/automations/runs/$RU/answer") { json(409, Fixtures.json("rest-automation-not-asking")) }
        val e = rig.call { autos.answer(RU, "st_q", "Not now") }.exceptionOrNull() as AutomationError
        assertEquals("not_asking", e.code)
        assertEquals(res.getString(R.string.wf_err_not_asking), e.message)
        assertEquals(Run.DONE, lastRun()?.state)
        assertTrue(autos.state!!.asks.isEmpty())
    }

    @Test fun runsLoadAPageThenTheNextFromTheLastOnesTime() {
        connect()
        daemon.ok("GET", "/v1/automations/$AU/runs", Fixtures.json("rest-automation-runs"))
        assertTrue(rig.call { autos.loadRuns(AU) }.isSuccess)
        assertEquals("limit=30", last("GET", "/v1/automations/$AU/runs").query)
        val p = autos.runs(AU)!!
        assertEquals(listOf(RU), p.runs.map { it.id })
        assertEquals(1, p.total)
        assertEquals(0, p.skipped)
        assertFalse("fewer than a page: nothing more", p.more)
        rig.call { autos.loadRuns(AU, more = true) }
        assertEquals("limit=30&before=1800000000000", last("GET", "/v1/automations/$AU/runs").query)
        // A run the socket brings goes on top, and counts.
        rig.push(JSONObject().put("t", "run").put("run", Fixtures.json("rest-automation-retry").getJSONObject("run").put("id", "ru_0000000009"))) { autos.runs(AU)!!.runs.size == 2 }
        assertEquals("ru_0000000009", autos.runs(AU)!!.runs.first().id)
        assertEquals(2, autos.runs(AU)!!.total)
    }

    @Test fun whileTheServerIsAwayACallFailsInTheHubsWords() {
        connect()
        daemon.down = true
        val e = rig.call { autos.setEnabled(AU, false) }.exceptionOrNull()!!
        assertTrue(e.message!!.isNotBlank())
        assertFalse("not the daemon's refusal", e is AutomationError)
        assertTrue(autos.state!!.byId(AU)!!.enabled)
        daemon.down = false
    }

    // ─── Notes ───

    private fun notifications() = shadowOf(rig.context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun aNoteOnScreenIsABannerThatOpensItsAutomation() {
        connect()
        val note = JSONObject().put("t", "note").put("note", JSONObject().put("id", "no_1").put("automationId", AU).put("runId", RU).put("name", "Deploy check").put("text", "Deploying.").put("to", "phone").put("at", rig.now))
        rig.push(note) { rig.banners.any { it.id == "note:no_1" } }
        val b = rig.banners.single { it.id == "note:no_1" }
        assertEquals(Notice.Kind.Automation, b.kind)
        assertEquals("Deploy check", b.title)
        assertEquals("Deploying.", b.text)
        assertEquals(AU, b.automationId)
        assertTrue(notifications().isEmpty())
        // The same note again (a reconnect's replay): never shown twice.
        rig.push(note) { true }
        rig.sync()
        assertEquals(1, rig.banners.count { it.id == "note:no_1" })
        // One for the Mac only isn't the phone's.
        rig.push(JSONObject().put("t", "note").put("note", JSONObject(note.getJSONObject("note").toString()).put("id", "no_2").put("to", "mac"))) { true }
        rig.sync()
        assertTrue(rig.banners.none { it.id == "note:no_2" })
    }

    @Test fun offScreenANoteIsANotificationAndAnAskOneHasItsAnswersAsButtons() {
        shadowOf(rig.context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        connect()
        rig.visible = false
        rig.push(JSONObject().put("t", "note").put("note", JSONObject().put("id", "no_1").put("automationId", AU).put("name", "Deploy check").put("text", "Deploying.").put("to", "phone").put("at", rig.now))) { notifications().isNotEmpty() }
        val plain = notifications().single()
        assertEquals(AutomationNotes.CHANNEL, plain.channelId)
        assertEquals("Deploy check", plain.extras.getString("android.title"))
        assertEquals("Deploying.", plain.extras.getCharSequence("android.text").toString())
        assertTrue(rig.banners.none { it.id == "note:no_1" })

        // The ask's note (the fixture): its question, and Deploy and Not now as its buttons.
        rig.push(JSONObject().put("t", "run").put("run", Fixtures.json("rest-automation-run").getJSONObject("run"))) { lastRun()?.state == Run.NEEDS_YOU }
        rig.push(Fixtures.json("ws-note")) { notifications().size == 2 }
        val ask = notifications().first { it.extras.getString("android.title") == "Deploy now?" }
        assertEquals(listOf("Deploy", "Not now"), ask.actions.map { it.title.toString() })
        // Answered (here or anywhere): the run moves on and its notification goes.
        rig.push(Fixtures.json("ws-run")) { notifications().none { it.extras.getString("android.title") == "Deploy now?" } }
        assertEquals(1, notifications().size)
    }

    @Test fun notesFromWhileThePhoneWasAwayFoldIntoOne() {
        val now = System.currentTimeMillis()
        val notes = JSONArray()
            .put(JSONObject().put("id", "no_a").put("automationId", AU).put("name", "Deploy check").put("text", "One.").put("to", "phone").put("at", now - 60_000))
            .put(JSONObject().put("id", "no_b").put("automationId", "au_other").put("name", "Morning brief").put("text", "Two.").put("to", "phone").put("at", now - 120_000))
            // Older than the 12 hours the Mac keeps: not shown again.
            .put(JSONObject().put("id", "no_c").put("automationId", AU).put("name", "Old").put("text", "Three.").put("to", "phone").put("at", now - 13 * 3_600_000L))
        connect(notes = notes)
        rig.until("the folded banner") { rig.banners.any { it.id == "note:away" } }
        val b = rig.banners.single { it.id == "note:away" }
        assertEquals(res.getQuantityString(R.plurals.au_away_title, 2, 2), b.title)
        assertEquals(com.prudnikweb.hypernova.data.automationNames(res.strings, listOf("Deploy check", "Morning brief")), b.text)
        assertEquals("", b.automationId)
    }

    /**
     * An ask answered from its notification (data/NotifyActions.kt, ACTION_ANSWER): the answer goes
     * to the computer the notification names, and its notification goes once the Mac has it.
     */
    @Test fun anAnswerFromTheNotificationGoesToTheMacAndTheNotificationGoes() {
        val app = rig.context as android.app.Application
        com.prudnikweb.hypernova.data.Core.init(app)
        val mac = FakeDaemon("gym")
        try {
            mac.snapshot = snapshot("gym") { put("automations", JSONObject().put("paused", false).put("list", JSONArray().put(Fixtures.json("rest-automation").getJSONObject("automation").put("lastRun", Fixtures.json("rest-automation-run").getJSONObject("run")))).put("features", JSONArray())) }
            mac.ok("POST", "/v1/automations/runs/$RU/answer", Fixtures.json("rest-automation-answer"))
            com.prudnikweb.hypernova.data.Core.paired(com.prudnikweb.hypernova.data.Pairing(mac.base, "tok-gym", "gym", "m-gym", "dev-1"))
            val server = com.prudnikweb.hypernova.data.Core.activeId!!
            until("linked") { com.prudnikweb.hypernova.data.Core.live?.link?.state == LinkState.Connected }
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val run = com.prudnikweb.hypernova.data.Core.live!!.automations.state!!.asks.single()
            AutomationNotes(app, server).postAsk(RU, AU, "Deploy check", run.ask!!)
            assertEquals(1, notifications().size)

            NotifyActions().onReceive(
                app,
                android.content.Intent(app, NotifyActions::class.java).setAction(NotifyActions.ACTION_ANSWER)
                    .putExtra(NotifyActions.EXTRA_RUN, RU).putExtra(NotifyActions.EXTRA_STEP, "st_q").putExtra(NotifyActions.EXTRA_ANSWER, "Deploy").putExtra(NotifyActions.EXTRA_SERVER, server),
            )
            until("the answer") { mac.count("POST", "/v1/automations/runs/$RU/answer") == 1 && notifications().isEmpty() }
            val b = mac.calls("POST", "/v1/automations/runs/$RU/answer").single().json
            assertEquals("st_q", b.getString("stepId"))
            assertEquals("Deploy", b.getString("answer"))
            assertNull(com.prudnikweb.hypernova.data.Core.live!!.automations.state!!.byId(AU)!!.lastRun!!.ask)
        } finally {
            for (s in com.prudnikweb.hypernova.data.Core.servers.toList()) com.prudnikweb.hypernova.data.Core.forget(s.id)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            mac.close()
        }
    }

    private fun until(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) org.junit.Assert.fail("timed out waiting for: $what")
            Thread.sleep(3)
        }
    }
}
