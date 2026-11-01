package com.prudnikweb.hypernova.data.live

import android.Manifest
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * An automation's notes to the phone (docs/PROTOCOL.md, "Automations", `{t:"note"}`): a banner
 * while the app is open, a notification while it isn't, each note once; what came while the phone
 * was away, folded into one when there are several; a question with its answers as buttons.
 */
@RunWith(RobolectricTestRunner::class)
class AutomationNotesTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }
    private val nm get() = shadowOf(rig.context.getSystemService(NotificationManager::class.java))

    @After fun tearDown() = rig.close()

    private fun note(id: String, name: String = "Nightly tests", text: String = "All 82 tests pass.", at: Long = System.currentTimeMillis(), more: JSONObject.() -> Unit = {}) =
        JSONObject().put("id", id).put("automationId", "auto-1").put("runId", "run-$id").put("name", name).put("text", text).put("to", "phone").put("at", at).apply(more)

    private fun connect(notes: List<JSONObject> = emptyList()) {
        shadowOf(rig.context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        daemon.snapshot = snapshot { put("automations", JSONObject().put("paused", false).put("list", JSONArray()).put("notes", JSONArray(notes))) }
        rig.connect()
        rig.sync()
    }

    private fun titles() = nm.allNotifications.map { it.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() }

    @Test fun aNoteWhileTheAppIsOpenIsABannerOnce() {
        connect()
        rig.push(msg("note") { put("note", note("n1")) }) { rig.banners.isNotEmpty() }
        val b = rig.banners.single()
        assertEquals(Notice.Kind.Automation, b.kind)
        assertEquals("note:n1", b.id)
        assertEquals("Nightly tests", b.title)
        assertEquals("All 82 tests pass.", b.text)
        assertEquals("auto-1", b.automationId)
        // The same note again (a resend after a reconnect): said once.
        daemon.push(msg("note") { put("note", note("n1")) })
        rig.sync()
        assertEquals(1, rig.banners.size)
        assertTrue(nm.allNotifications.isEmpty())
    }

    @Test fun aNoteWhileTheAppIsAwayIsANotification() {
        connect()
        rig.visible = false
        daemon.push(msg("note") { put("note", note("n2", text = "Deployed v0.43.0.")) })
        rig.until("the notification") { titles().contains("Nightly tests") }
        val n = nm.allNotifications.single()
        assertEquals("Deployed v0.43.0.", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertTrue(rig.banners.isEmpty())
        // A note to another place (the Mac's own) isn't the phone's to show.
        daemon.push(msg("note") { put("note", note("n3", name = "Mac only").put("to", "mac")) })
        rig.sync()
        assertEquals(1, nm.allNotifications.size)
    }

    @Test fun whatCameWhileAwayIsFoldedIntoOne() {
        val now = System.currentTimeMillis()
        rig.visible = false
        connect(
            listOf(
                note("a", name = "Nightly tests", at = now - 60_000),
                note("b", name = "Weekly report", at = now - 120_000),
                // Older than the 12 hours the Mac keeps: not news.
                note("c", name = "Old one", at = now - 13 * 3_600_000L),
            ),
        )
        rig.until("the folded one") { nm.allNotifications.isNotEmpty() }
        val res = rig.context.resources
        assertEquals(listOf(res.getQuantityString(R.plurals.au_away_title, 2, 2)), titles())
        val text = nm.allNotifications.single().extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        assertTrue(text, "Nightly tests" in text && "Weekly report" in text && "Old one" !in text)
    }

    @Test fun aQuestionWhileAwayIsANotificationWithItsAnswers() {
        connect()
        rig.visible = false
        val ask = JSONObject().put("stepId", "step-1").put("question", "Deploy now?").put("answers", JSONArray(listOf("Yes", "No")))
            .put("default", "No").put("until", System.currentTimeMillis() + 600_000)
        daemon.push(msg("note") { put("note", note("q1", text = "Deploy now?") { put("ask", ask) }) })
        rig.until("the question") { titles().contains("Deploy now?") }
        val n = nm.allNotifications.single()
        assertEquals(listOf("Yes", "No"), n.actions.map { it.title.toString() })
    }
}
