package com.prudnikweb.hypernova.data.live

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.AutomationNotes
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.NotifyActions
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.RunAsk
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The notifications' buttons (data/NotifyActions.kt), as Android delivers them: a broadcast to the
 * receiver (not exported, so only the notifications' own PendingIntents reach it), which goes
 * through [Core] to the computer the notification names. Allow and Deny on an approval, Reply on a
 * session's notice, an automation's answer; what becomes of the notification each way.
 */
@RunWith(RobolectricTestRunner::class)
class NotifyActionsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val z2 = FakeDaemon("z2")
    private lateinit var server: String
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    private fun until(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(3)
        }
    }

    @Before fun pair() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidCore.init(app)
        ComputerWords.clear(app.storage)
        z2.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = "Fix the parser")), approvals = listOf(approval("a1", "s1")))
        Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
        server = Core.activeId!!
        until("linked") { Core.live?.link?.state == LinkState.Connected && Core.live!!.pendingApprovals.isNotEmpty() }
    }

    @After fun forget() {
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        nm.allNotifications.clear()
        z2.close()
    }

    private fun send(action: String, build: Intent.() -> Unit) {
        app.sendBroadcast(Intent(app, NotifyActions::class.java).setAction(action).putExtra(NotifyActions.EXTRA_SERVER, server).apply(build))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun showApproval() {
        val live = Core.live!!
        Core.notifier.approval(live.pendingApprovals.single(), live.session("s1"), server = server)
        assertNotNull(nm.getNotification("a1".hashCode()))
    }

    private fun text(id: Int) = nm.getNotification(id)?.extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()

    private fun answers(action: String, decision: String) {
        showApproval()
        z2.ok("POST", "/v1/approvals/a1")
        send(action) { putExtra(NotifyActions.EXTRA_APPROVAL, "a1") }
        until("the $decision") { z2.count("POST", "/v1/approvals/a1") == 1 }
        assertEquals(decision, z2.calls("POST", "/v1/approvals/a1").single().json.getString("decision"))
        until("its notification gone") { nm.getNotification("a1".hashCode()) == null }
    }

    @Test fun allowAnswersTheApprovalAndTheNotificationGoes() = answers(NotifyActions.ACTION_ALLOW, "allow")

    @Test fun denyAnswersTheApprovalAndTheNotificationGoes() = answers(NotifyActions.ACTION_DENY, "deny")

    @Test fun anAnswerThatDidntGetThroughSaysSoOnTheNotification() {
        showApproval()
        z2.on("POST", "/v1/approvals/a1") { refusal(500, "boom", "Something went wrong.") }
        send(NotifyActions.ACTION_ALLOW) { putExtra(NotifyActions.EXTRA_APPROVAL, "a1") }
        until("said") { text("a1".hashCode()) == app.getString(R.string.notif_answer_failed) }
        assertEquals(1, z2.count("POST", "/v1/approvals/a1"))
    }

    @Test fun anAnswerWithoutItsApprovalIdDoesNothing() {
        showApproval()
        send(NotifyActions.ACTION_ALLOW) {}
        assertEquals(0, z2.count("POST", "/v1/approvals/a1"))
        assertNotNull(nm.getNotification("a1".hashCode()))
    }

    /** The session's notice with Reply, as an approval-free finished turn posts it. */
    private fun showNotice(): Int {
        Core.notifier.notice(Notice("n1", Notice.Kind.Finished, "s1", "Fix the parser", "Done. Want me to commit?"), server = server)
        val id = ("session:" + (if (server == com.prudnikweb.hypernova.data.Servers.LEGACY_ID) "s1" else "$server:s1")).hashCode()
        assertNotNull("the notice", nm.getNotification(id))
        return id
    }

    private fun reply(text: String?) = send(NotifyActions.ACTION_REPLY) {
        putExtra(NotifyActions.EXTRA_SESSION, "s1")
        if (text != null) RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(NotifyActions.KEY_REPLY).build()), this, Bundle().apply { putCharSequence(NotifyActions.KEY_REPLY, text) })
    }

    @Test fun aReplySendsThePromptAndTheNoticeGoes() {
        val id = showNotice()
        z2.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1", state = "working")).put("queued", false))
        reply("  Yes, commit it  ")
        until("the prompt") { z2.count("POST", "/v1/sessions/s1/prompt") == 1 }
        assertEquals("Yes, commit it", z2.calls("POST", "/v1/sessions/s1/prompt").single().json.getString("text"))
        until("the notice gone") { nm.getNotification(id) == null }
    }

    @Test fun aReplyThatDidntGetThroughSaysWhyAndOffersReplyAgain() {
        val id = showNotice()
        val why = "There’s unsent text in this session’s terminal on the server. Send or clear it there first."
        z2.on("POST", "/v1/sessions/s1/prompt") { refusal(409, "typing", why) { put("pending", "half a line") } }
        reply("Yes, commit it")
        until("said") { z2.count("POST", "/v1/sessions/s1/prompt") >= 1 && text(id) != "Done. Want me to commit?" && text(id) != null }
        val n = nm.getNotification(id)!!
        assertTrue(text(id)!!.isNotBlank())
        assertEquals("Fix the parser", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals(app.getString(R.string.notif_reply), n.actions.single().title.toString())
        assertTrue("its remote input is there again", n.actions.single().remoteInputs?.isNotEmpty() == true)
    }

    @Test fun anEmptyReplyOnlyClearsTheNotice() {
        val id = showNotice()
        reply("   ")
        assertNull(nm.getNotification(id))
        assertEquals(0, z2.count("POST", "/v1/sessions/s1/prompt"))
    }

    @Test fun aReplyForAComputerNoLongerPairedSaysItWasntSent() {
        val id = showNotice()
        Core.forget(server)
        shadowOf(Looper.getMainLooper()).idle()
        Core.notifier.notice(Notice("n1", Notice.Kind.Finished, "s1", "Fix the parser", "Done."), server = server)
        reply("Yes")
        assertEquals(app.getString(R.string.notif_reply_failed), text(id))
        assertEquals(0, z2.count("POST", "/v1/sessions/s1/prompt"))
    }

    private fun askNotification(): Int {
        val ask = RunAsk("step-1", "Deploy now?", listOf("Yes", "No"), "No", System.currentTimeMillis() + 600_000)
        AutomationNotes(app, server).postAsk("run-1", "auto-1", "Nightly deploy", ask)
        val id = ("ask:run-1").hashCode()
        val n = nm.getNotification(id)
        assertNotNull("the ask", n)
        assertEquals(listOf("Yes", "No"), n!!.actions.map { it.title.toString() })
        return id
    }

    private fun answer(a: String) = send(NotifyActions.ACTION_ANSWER) {
        putExtra(NotifyActions.EXTRA_RUN, "run-1").putExtra(NotifyActions.EXTRA_STEP, "step-1").putExtra(NotifyActions.EXTRA_ANSWER, a)
    }

    private val answerPath = "/v1/automations/runs/run-1/answer"

    @Test fun anAutomationsQuestionIsAnsweredFromItsNotification() {
        val id = askNotification()
        z2.ok("POST", answerPath, JSONObject().put("run", JSONObject().put("id", "run-1").put("automationId", "auto-1").put("state", "running").put("startedAt", 1_000L)))
        answer("Yes")
        until("the answer") { z2.count("POST", answerPath) == 1 }
        val body = z2.calls("POST", answerPath).single().json
        assertEquals("step-1", body.getString("stepId"))
        assertEquals("Yes", body.getString("answer"))
        until("the ask gone") { nm.getNotification(id) == null }
    }

    @Test fun anAnswerThatFailsLeavesTheQuestionToAnswerAgainUnlessNothingIsAsking() {
        val id = askNotification()
        z2.on("POST", answerPath) { refusal(500, "boom", "Something went wrong.") }
        answer("No")
        until("the try") { z2.count("POST", answerPath) == 1 }
        // Settled: Core's scope has run the receiver's work to its end.
        until("settled") { shadowOf(Looper.getMainLooper()).idle(); true }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("left to answer again", nm.getNotification(id))

        // Answered elsewhere first: nothing to answer, and it goes.
        z2.on("POST", answerPath) { refusal(409, "not_asking", "Nothing is asking any more.") }
        answer("No")
        until("the ask gone") { nm.getNotification(id) == null }
    }

    @Test fun anAnswerWithNoComputerLinkedLeavesTheNotification() {
        val id = askNotification()
        Core.forget(server)
        shadowOf(Looper.getMainLooper()).idle()
        answer("Yes")
        assertNotNull(nm.getNotification(id))
        assertEquals(0, z2.count("POST", answerPath))
    }

    @Test fun aFailedServerUpdateOpensThatServersSettings_andGoesOnceItsFine() {
        val notice = Notice(com.prudnikweb.hypernova.data.SERVER_UPDATE_NOTICE, Notice.Kind.ServerUpdateFailed, null, "z2", "Couldn't update to 0.14.0.")
        Core.notifier.notice(notice, server = server)
        val id = ("session:" + (if (server == com.prudnikweb.hypernova.data.Servers.LEGACY_ID) notice.id else "$server:${notice.id}")).hashCode()
        val n = nm.getNotification(id)!!
        val sent = shadowOf(n.contentIntent).savedIntent
        assertEquals(com.prudnikweb.hypernova.MainActivity.ACTION_SERVER_UPDATE, sent.action)
        assertEquals(server, sent.getStringExtra(com.prudnikweb.hypernova.MainActivity.EXTRA_SERVER))
        assertEquals("z2", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("nothing to press: it says what happened", 0, n.actions?.size ?: 0)
        Core.notifier.cancelServerUpdate(server)
        org.junit.Assert.assertNull(nm.getNotification(id))
    }

    @Test fun backpacksNoticeOffersTwoHoursMoreWithoutOpeningTheApp() {
        Core.notifier.notice(Notice("backpack-ending", Notice.Kind.Backpack, null, "z2", "Backpack ends in 9 minutes", action = Notice.ACTION_EXTEND), server = server)
        val id = ("session:" + (if (server == com.prudnikweb.hypernova.data.Servers.LEGACY_ID) "backpack-ending" else "$server:backpack-ending")).hashCode()
        val n = nm.getNotification(id)!!
        val extend = n.actions.single()
        assertEquals(app.getString(R.string.notice_extend), extend.title.toString())
        val sent = shadowOf(extend.actionIntent).savedIntent
        assertEquals(com.prudnikweb.hypernova.MainActivity.ACTION_EXTEND, sent.action)
        assertEquals(server, sent.getStringExtra(com.prudnikweb.hypernova.MainActivity.EXTRA_SERVER))
    }
}
