package com.prudnikweb.hypernova.data.live

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.prudnikweb.hypernova.BootReceiver
import com.prudnikweb.hypernova.ConnectionService
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Notifier
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Phrases
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * "Stay connected in the background" (ConnectionService, BootReceiver): the service's permanent
 * notification says what the computer is doing and follows it, Disconnect closes the link until the
 * app opens, and the service starts again after a restart or an update only while a computer is
 * paired and the switch is on. Through [Core], against [FakeDaemon].
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionServiceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val z2 = FakeDaemon("z2")
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    private fun until(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${text()})")
            Thread.sleep(3)
        }
    }

    private fun text() = nm.getNotification(Notifier.CONNECTION_ID)?.extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
    private fun title() = nm.getNotification(Notifier.CONNECTION_ID)?.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()

    @Before fun pair() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // A Wi-Fi network with the internet, so a dropped link is tried again (HubRig.online).
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.activeNetwork?.let { net ->
            shadowOf(cm).setNetworkCapabilities(net, org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            })
        }
        Core.init(app)
        ComputerWords.clear(app.storage)
        Core.stayConnected(true)
        z2.snapshot = snapshot(
            "z2",
            sessions = listOf(session("s1", state = "working"), session("s2", state = "working"), session("s3", state = "idle")),
            approvals = listOf(approval("a1", "s3")),
        )
        Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
        until("linked") { Core.live?.link?.state == LinkState.Connected && Core.live!!.sessions.size == 3 }
        shadowOf(app).clearStartedServices()
    }

    @After fun forget() {
        Core.stayConnected(true)
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        nm.allNotifications.clear()
        z2.close()
    }

    private fun counts(working: Int, needs: Int): String {
        val r = app.resources
        return Phrases.list(
            listOf(r.getQuantityString(R.plurals.notif_working, working, working), r.getQuantityString(R.plurals.notif_needs_you, needs, needs)),
            app.getString(R.string.list_and),
        )
    }

    @Test fun itsNotificationSaysWhatTheComputerIsDoingAndFollowsIt() {
        val c = Robolectric.buildService(ConnectionService::class.java).create()
        val service = c.get()
        assertTrue(ConnectionService.running)
        assertEquals(Notifier.CONNECTION_ID, shadowOf(service).lastForegroundNotificationId)
        until("the counts") { text() == counts(2, 1) }
        assertEquals("z2", title())
        val n = nm.getNotification(Notifier.CONNECTION_ID)!!
        assertTrue("ongoing", n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(app.getString(R.string.notif_disconnect), n.actions.single().title.toString())

        // The approval answered elsewhere and one turn over: it follows.
        z2.push(FakeDaemon.msg("approval.resolved") { put("id", "a1").put("sessionId", "s3").put("decision", "allow").put("by", "phone") })
        z2.push(FakeDaemon.msg("session") { put("session", session("s1", state = "idle")) })
        until("one working") { text() == app.resources.getQuantityString(R.plurals.notif_working, 1, 1) }

        z2.push(FakeDaemon.msg("session") { put("session", session("s2", state = "idle")) })
        until("quiet") { text() == app.getString(R.string.notif_quiet) }
        c.destroy()
        assertFalse(ConnectionService.running)
    }

    @Test fun disconnectClosesTheLinkUntilTheAppOpensAndStopsTheService() {
        val c = Robolectric.buildService(ConnectionService::class.java).create()
        until("the counts") { text() == counts(2, 1) }
        val disconnect = shadowOf(nm.getNotification(Notifier.CONNECTION_ID)!!.actions.single().actionIntent).savedIntent
        assertEquals(ConnectionService.ACTION_DISCONNECT, disconnect.action)

        c.withIntent(disconnect).startCommand(0, 2)
        until("paused") { Core.live?.link?.state == LinkState.Offline }
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertFalse(Core.wantsService())

        // The app opens: linked again, and the service is wanted again.
        val opened = z2.opened.get()
        Core.setVisible(true)
        until("linked again") { Core.live?.link?.state == LinkState.Connected && z2.opened.get() > opened }
        assertTrue(Core.wantsService())
        Core.setVisible(false)
    }

    @Test fun aComputerThatForgotThisPhoneStopsIt() {
        val c = Robolectric.buildService(ConnectionService::class.java).create()
        until("the counts") { text() == counts(2, 1) }
        z2.unauthorized = true
        z2.closeSocket()
        until("unpaired") { Core.live?.link?.state == LinkState.Unpaired }
        until("stopped") { shadowOf(c.get()).isStoppedBySelf }
    }

    @Test fun startedWithNothingToHoldItStops() {
        Core.stayConnected(false)
        val c = Robolectric.buildService(ConnectionService::class.java).create().startCommand(0, 1)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    private fun started(): String? = shadowOf(app).nextStartedService?.component?.className

    @Test fun afterARestartOrAnUpdateItStartsAgainWhilePairedAndOn() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            BootReceiver().onReceive(app, Intent(action))
            assertEquals(action, ConnectionService::class.java.name, started())
        }
        // Anything else is not its business.
        BootReceiver().onReceive(app, Intent(Intent.ACTION_USER_PRESENT))
        assertNull(started())
    }

    @Test fun afterARestartItStaysOffWhenTheSwitchIsOffOrNothingIsPaired() {
        Core.stayConnected(false)
        shadowOf(app).clearStartedServices()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(started())

        Core.stayConnected(true)
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(app).clearStartedServices()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(started())
    }
}
