package com.prudnikweb.hypernova.data.live

import android.os.Looper
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.AboutUpdate
import com.prudnikweb.hypernova.data.AppUpdater
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.UpdatePolicy
import com.prudnikweb.hypernova.data.UpdateState
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import com.prudnikweb.hypernova.ui.settings.SettingsPlan
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Settings with "All servers" (ui/settings/SettingsPlan.kt, Settings.kt) against two scripted
 * daemons: each server's block reads and writes its own server, never the one picked; About offers
 * the newest app any server has and downloads it from that server; Forget in a block forgets that one.
 */
@RunWith(RobolectricTestRunner::class)
class AllServersSettingsTest {
    private fun until(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${Core.statuses})")
            Thread.sleep(3)
        }
    }

    @Test fun eachServersBlockActsOnItsOwnServer() {
        AndroidCore.init(RuntimeEnvironment.getApplication())
        val app = AndroidCore.app
        ComputerWords.clear(app.storage)
        val z2 = FakeDaemon("z2")
        val shed = FakeDaemon("shed")
        try {
            z2.snapshot = snapshot("z2")
            // shed has Browse outside projects (off) and a newer app than this one.
            shed.snapshot = snapshot("shed") {
                getJSONObject("mac")
                    .put("files", JSONObject().put("browseOutsideProjects", false))
                    .put("app", JSONObject().put("versionCode", 2_000_000_000).put("versionName", "9.9.9"))
            }
            Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
            val z2Id = Core.activeId!!
            Core.paired(Pairing(shed.base, "tok-shed", "shed", "m-shed", "dev-2"))
            val shedId = Core.activeId!!
            until("both linked") { Core.allHubs.size == 2 && Core.allHubs.all { it.link.state == LinkState.Connected && it.synced } }
            // z2 picked, then All servers: Settings' "active" hub is z2's.
            Core.switchTo(z2Id)
            Core.showAll()
            assertTrue(Core.all)
            assertEquals(z2Id, Core.activeId)

            // The plan from every server's facts, as SettingsTab builds it: Files only in shed's block.
            val hubs = Core.everyHub.associate { it.first.id to it.second }
            val facts = Core.everyHub.map { (s, h) -> SettingsPlan.ServerFacts(h.serverId, s.state, h.speech.settings != null, h.mac.browseOutsideProjects != null) }
            val parts = SettingsPlan.parts(true, facts, testing = false)
            val files = parts.filterIsInstance<SettingsPlan.Files>().single()
            assertEquals(shedId, files.server)

            // Its switch writes to shed, not to z2 on screen.
            shed.ok("POST", "/v1/mac/files", JSONObject().put("files", JSONObject().put("browseOutsideProjects", true)))
            val set = Core.scope.async { hubs.getValue(files.server).setBrowseOutsideProjects(true) }
            until("the switch") { set.isCompleted }
            assertTrue(set.getCompleted().isSuccess)
            assertEquals(true, shed.calls("POST", "/v1/mac/files").single().json.getBoolean("browseOutsideProjects"))
            assertEquals(0, z2.count("POST", "/v1/mac/files"))
            assertEquals(true, hubs.getValue(shedId).mac.browseOutsideProjects)
            assertEquals(null, hubs.getValue(z2Id).mac.browseOutsideProjects)

            // About: z2 (picked) offers nothing, shed a newer app: shed's, downloaded from shed.
            val offer = AboutUpdate.newest(Core.everyHub.sortedBy { it.first.id != z2Id }.map { it.second.appUpdate })!!
            assertEquals(shedId, offer.server)
            shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
            AppUpdater.start(app, offer)
            until("the download's answer") { AppUpdater.state is UpdateState.Failed }
            assertEquals(1, shed.count("GET", UpdatePolicy.APK_PATH))
            assertEquals(0, z2.count("GET", UpdatePolicy.APK_PATH))
            AppUpdater.dismiss(app)

            // Forget in shed's block: shed is told and forgotten; z2 stays paired and on screen.
            shed.ok("DELETE", "/v1/devices/me")
            val vm = HubViewModel(app)
            var reached: Boolean? = null
            vm.forget(shedId) { reached = it }
            until("forgotten") { reached != null }
            assertEquals(true, reached)
            assertEquals(1, shed.count("DELETE", "/v1/devices/me"))
            assertEquals(0, z2.count("DELETE", "/v1/devices/me"))
            assertEquals(listOf(z2Id), Core.servers.map { it.id })
            assertEquals(z2Id, Core.activeId)
        } finally {
            AppUpdater.cleanUp(app)
            for (s in Core.servers.toList()) Core.forget(s.id)
            shadowOf(Looper.getMainLooper()).idle()
            z2.close()
            shed.close()
        }
    }
}
