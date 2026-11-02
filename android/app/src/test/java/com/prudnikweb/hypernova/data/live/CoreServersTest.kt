package com.prudnikweb.hypernova.data.live

import android.os.Looper
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.AppUpdate
import com.prudnikweb.hypernova.data.AppUpdater
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.UpdateFailure
import com.prudnikweb.hypernova.data.UpdatePolicy
import com.prudnikweb.hypernova.data.UpdateState
import okhttp3.mockwebserver.MockResponse
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [Core] itself (the app's process starts it: HypernovaApp.onCreate), its hubs on the main thread:
 * two computers paired, the switcher, "All servers" and where an answer goes; the in-app update from
 * the active one. Core is the process's and outlives a test in the same sandbox, so each test
 * forgets every computer it paired.
 */
@RunWith(RobolectricTestRunner::class)
class CoreServersTest {
    private fun until(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${Core.statuses})")
            Thread.sleep(3)
        }
    }

    /**
     * The in-app update (data/AppUpdater.kt) from the active computer: the permission first, then
     * the download over its route, and a 404 or a file Android can't read is a failure it can say.
     */
    @Test fun theAppUpdateAsksForThePermissionThenDownloadsAndSaysWhatWentWrong() {
        AndroidCore.init(RuntimeEnvironment.getApplication())
        val app = AndroidCore.app
        val z2 = FakeDaemon("z2")
        try {
            z2.snapshot = snapshot("z2", sessions = listOf(session("z-1")))
            Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
            until("linked") { Core.live?.link?.state == LinkState.Connected }
            val u = AppUpdate("9.9.9", 2_000_000_000, z2.base + "/download/android")
            val pm = shadowOf(app.packageManager)

            pm.setCanRequestPackageInstalls(false)
            AppUpdater.start(app, u)
            assertEquals(UpdateState.NeedsPermission(u.versionCode), AppUpdater.state)

            // Allowed in the system's settings, back in the app: the download, and the server has none.
            pm.setCanRequestPackageInstalls(true)
            AppUpdater.resumed(app)
            until("the 404") { AppUpdater.state is UpdateState.Failed }
            assertEquals(UpdateFailure.Missing, (AppUpdater.state as UpdateState.Failed).failure)
            assertEquals(1, z2.count("GET", UpdatePolicy.APK_PATH))
            AppUpdater.dismiss(app)
            assertEquals(UpdateState.Idle, AppUpdater.state)

            // A file that isn't an APK Android can read.
            z2.on("GET", UpdatePolicy.APK_PATH) { MockResponse().setResponseCode(200).setBody("not an apk") }
            AppUpdater.start(app, u)
            until("the check") { AppUpdater.state is UpdateState.Failed }
            assertEquals(UpdateFailure.Damaged, (AppUpdater.state as UpdateState.Failed).failure)
            assertEquals(2, z2.count("GET", UpdatePolicy.APK_PATH))
            AppUpdater.dismiss(app)
        } finally {
            AppUpdater.cleanUp(app)
            for (s in Core.servers.toList()) Core.forget(s.id)
            shadowOf(Looper.getMainLooper()).idle()
            z2.close()
        }
    }

    @Test fun twoComputersPairedTheSwitcherAllServersAndAnswersGoingToTheRightOne() {
        AndroidCore.init(RuntimeEnvironment.getApplication())
        ComputerWords.clear(AndroidCore.app.storage)
        val z2 = FakeDaemon("z2")
        val gym = FakeDaemon("gym")
        try {
            z2.snapshot = snapshot("z2", sessions = listOf(session("z-1", state = "working")))
            gym.snapshot = snapshot("gym", sessions = listOf(session("g-1", state = "working")))
            Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
            val z2Id = Core.activeId!!
            Core.paired(Pairing(gym.base, "tok-gym", "gym", "m-gym", "dev-2"))
            val gymId = Core.activeId!!
            assertTrue(z2Id != gymId)
            assertEquals(listOf(z2Id, gymId), Core.servers.map { it.id })
            until("both linked") { Core.allHubs.size == 2 && Core.allHubs.all { it.link.state == LinkState.Connected } && z2.socket != null && gym.socket != null }

            // The newest pairing is on screen; the other is watched.
            assertEquals("gym", Core.live!!.mac.name)
            assertEquals(listOf(gymId, z2Id), Core.statuses.map { it.id })
            assertEquals(listOf(true, false), Core.statuses.map { it.active })
            assertEquals("z2", Core.nameOf(z2Id))

            // An approval on the watched one: the widget's answer (no computer named) goes there.
            z2.push(msg("approval") { put("approval", approval("z-a1", "z-1")) })
            until("z2's approval") { Core.hub(z2Id)!!.pendingApprovals.isNotEmpty() }
            assertEquals(1, Core.statuses.single { it.id == z2Id }.needsYou)
            assertTrue(Core.hubForApproval(null, "z-a1") === Core.hub(z2Id))
            assertTrue(Core.hubForApproval(gymId, "z-a1") === Core.hub(gymId))
            z2.ok("POST", "/v1/approvals/z-a1")
            val answered = Core.scope.async { Core.hubForApproval(null, "z-a1")!!.answerApproval("z-a1", Answer.Deny) }
            until("the answer") { answered.isCompleted }
            assertEquals("deny", z2.calls("POST", "/v1/approvals/z-a1").single().json.getString("decision"))
            assertEquals(0, gym.requests.count { it.bare.startsWith("/v1/approvals/") })

            // The switcher: z2 on screen, gym watched, both still linked (no reconnect).
            Core.switchTo(z2Id)
            assertEquals("z2", Core.live!!.mac.name)
            assertEquals(1, z2.opened.get())
            assertEquals(1, gym.opened.get())

            // "All servers": Home shows both; a session of gym opened from it makes gym the one on
            // screen without becoming the one picked; back on Home, z2 again.
            Core.showAll()
            assertTrue(Core.all)
            assertEquals(2, Core.everyHub.size)
            Core.visit(gymId)
            assertEquals(gymId, Core.activeId)
            Core.backToPicked()
            assertEquals(z2Id, Core.activeId)
            Core.switchTo(gymId)
            assertFalse("one computer picked: Home is that one's alone", Core.all)

            // Forgetting the active one shows the other; its hub stops.
            Core.forget(gymId)
            assertEquals(z2Id, Core.activeId)
            assertEquals(listOf(z2Id), Core.servers.map { it.id })
            assertEquals(1, Core.allHubs.size)
        } finally {
            for (s in Core.servers.toList()) Core.forget(s.id)
            shadowOf(Looper.getMainLooper()).idle()
            z2.close()
            gym.close()
        }
    }
}
