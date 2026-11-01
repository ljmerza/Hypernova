package com.prudnikweb.hypernova.data.live

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AppUpdate
import com.prudnikweb.hypernova.data.AppUpdater
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.UpdateFailure
import com.prudnikweb.hypernova.data.UpdatePolicy
import com.prudnikweb.hypernova.data.UpdateState
import com.prudnikweb.hypernova.data.UpdateStatusReceiver
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowPackageInstaller
import java.io.File

/**
 * The in-app update after the download (data/AppUpdater.kt): Android's own reading of the archive
 * (Robolectric's PackageManager, told what the file is), the hand-off to PackageInstaller (a real
 * session for this package, committed with UpdateStatusReceiver as its
 * answer), and each answer PackageInstaller gives: "Update this app?" shown now or once the app is
 * back, declined and asked again, failed, installed, and the next start's "updated" note.
 *
 * What stays out of reach on the JVM: Android's own dialog and the replacement itself (the
 * system's, not the app's); here PackageInstaller's answers are the broadcasts it would send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [HeldSession::class])
class AppUpdaterTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val z2 = FakeDaemon("z2")
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val newer = AppUpdate("9.9.9", BuildConfig.VERSION_CODE + 1, "")
    private val installer get() = app.packageManager.packageInstaller
    private val apk get() = File(File(app.cacheDir, "update"), "Hypernova.apk")

    private fun until(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${AppUpdater.state})")
            Thread.sleep(3)
        }
    }

    @Before fun pair() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Core.init(app)
        z2.snapshot = snapshot("z2")
        z2.on("GET", UpdatePolicy.APK_PATH) { MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)) }
        Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
        until("linked") { Core.live?.link?.state == LinkState.Connected }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        Core.setVisible(true)
        // Sessions an earlier test left (the package manager is the sandbox's).
        for (s in installer.mySessions) installer.abandonSession(s.sessionId)
        HeldSession.receivers.clear()
    }

    @After fun forget() {
        HeldSession.receivers.clear()
        Core.setVisible(false)
        // AppUpdater is the process's: the flow put away (a new process starts Idle), and its file.
        AppUpdater.dismiss(app)
        AppUpdater.cleanUp(app)
        // The cleaning runs off the main thread: done before the next test downloads into the same folder.
        until("cleaned up") { !apk.parentFile!!.exists() }
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        z2.close()
    }

    /** What Android reads in the downloaded file. */
    private fun archive(pkg: String = app.packageName, version: Long = newer.versionCode.toLong()) =
        shadowOf(app.packageManager).setPackageArchiveInfo(apk.path, PackageInfo().apply { packageName = pkg; longVersionCode = version })

    /** PackageInstaller's word, through the status receiver the last session was committed with. */
    private fun answer(status: Int, prompt: Intent? = null, message: String? = null) {
        val fill = Intent().putExtra(PackageInstaller.EXTRA_STATUS, status)
            .apply { if (prompt != null) putExtra(Intent.EXTRA_INTENT, prompt) }
            .apply { if (message != null) putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message) }
        HeldSession.receivers.last().sendIntent(app, 0, fill, null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")

    /**
     * Committed, waiting for PackageInstaller's word, which the test then gives through the
     * session's own status receiver (the app's PendingIntent), as Android would.
     */
    private fun committed(): PackageInstaller.SessionInfo {
        until("handed to PackageInstaller") { AppUpdater.state is UpdateState.Confirming && HeldSession.receivers.isNotEmpty() }
        return installer.mySessions.last()
    }

    private fun start(u: AppUpdate = newer) = AppUpdater.start(app, u)

    @Test fun aCheckedArchiveIsHandedToPackageInstallerAndItsDialogShown() {
        archive()
        start()
        val s = committed()
        assertEquals(app.packageName, s.appPackageName)
        // (Robolectric's session keeps no size or bytes: the download's own length is checked.)
        assertEquals(bytes.size.toLong(), apk.length())
        assertEquals(UpdateState.Confirming(newer.versionCode, shown = false), AppUpdater.state)

        // PackageInstaller asks the user: the app on screen shows its dialog at once.
        shadowOf(app).clearNextStartedActivities()
        answer(UpdatePolicy.STATUS_PENDING_USER_ACTION, confirm)
        assertEquals(confirm.action, shadowOf(app).nextStartedActivity?.action)
        assertEquals(UpdateState.Confirming(newer.versionCode, shown = true), AppUpdater.state)

        // Installed: the flow is done and the file goes.
        answer(UpdatePolicy.STATUS_SUCCESS)
        assertEquals(UpdateState.Idle, AppUpdater.state)
        until("the file gone") { !apk.exists() }
    }

    @Test fun withTheAppAwayTheDialogWaitsForItsReturn() {
        archive()
        Core.setVisible(false)
        start()
        committed()
        shadowOf(app).clearNextStartedActivities()
        answer(UpdatePolicy.STATUS_PENDING_USER_ACTION, confirm)
        assertNull("no start from the background", shadowOf(app).nextStartedActivity)
        assertEquals(UpdateState.Confirming(newer.versionCode, shown = false), AppUpdater.state)

        Core.setVisible(true)
        AppUpdater.resumed(app)
        assertEquals(confirm.action, shadowOf(app).nextStartedActivity?.action)
        assertEquals(UpdateState.Confirming(newer.versionCode, shown = true), AppUpdater.state)
    }

    @Test fun declinedThenAskedAgainCommitsAFreshSessionWithoutDownloadingAgain() {
        archive()
        start()
        val first = committed()
        answer(UpdatePolicy.STATUS_PENDING_USER_ACTION, confirm)
        answer(UpdatePolicy.STATUS_FAILURE_ABORTED)
        assertEquals(UpdateState.Confirming(newer.versionCode, shown = false), AppUpdater.state)
        assertTrue("the file waits for another try", apk.exists())

        start()
        until("a second session") { installer.mySessions.singleOrNull()?.sessionId.let { it != null && it != first.sessionId } }
        assertEquals("one download", 1, z2.count("GET", UpdatePolicy.APK_PATH))
    }

    @Test fun aFailureFromPackageInstallerIsSaidAndTheFileGoes() {
        archive()
        start()
        committed()
        answer(UpdatePolicy.STATUS_FAILURE_CONFLICT, message = "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        val s = AppUpdater.state as UpdateState.Failed
        assertEquals(newer.versionCode, s.versionCode)
        assertTrue(s.failure != UpdateFailure.Network)
        until("the file gone") { !apk.exists() }
        AppUpdater.dismiss(app)
        assertEquals(UpdateState.Idle, AppUpdater.state)
    }

    @Test fun anotherAppsArchiveOrAnOlderOneIsNeverHandedOver() {
        for ((pkg, version, failure) in listOf(
            Triple("com.example.other", newer.versionCode.toLong(), UpdateFailure.OtherApp),
            Triple(app.packageName, BuildConfig.VERSION_CODE.toLong(), UpdateFailure.NotNewer),
        )) {
            archive(pkg, version)
            AppUpdater.start(app, newer)
            until("checked") { AppUpdater.state is UpdateState.Failed }
            assertEquals(failure, (AppUpdater.state as UpdateState.Failed).failure)
            assertTrue("nothing handed to PackageInstaller", installer.mySessions.isEmpty())
            AppUpdater.dismiss(app)
        }
    }

    @Test fun theStartAfterItsOwnUpdateSaysItIsDone() {
        val prefs = app.getSharedPreferences("hypernova", android.content.Context.MODE_PRIVATE)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        // Replaced by someone else's install (no update handed over): nothing said.
        app.sendBroadcast(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(nm.getNotification(4733))

        prefs.edit().putInt("selfUpdateFrom", BuildConfig.VERSION_CODE - 1).apply()
        app.sendBroadcast(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        val n = nm.getNotification(4733)
        assertEquals(app.getString(R.string.update_done_title), n?.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString())
        assertFalse("said once", prefs.contains("selfUpdateFrom"))
    }
}

/**
 * A PackageInstaller session that, committed, waits for the system's answer as a real one does.
 * (Robolectric's own "succeeds" at once and tells the receiver with no status at all, which a real
 * PackageInstaller never sends.) The status receiver is kept for the test to answer through.
 */
@Implements(PackageInstaller.Session::class)
class HeldSession : ShadowPackageInstaller.ShadowSession() {
    @Implementation
    override fun commit(statusReceiver: android.content.IntentSender) {
        receivers += statusReceiver
    }

    companion object {
        val receivers = java.util.concurrent.CopyOnWriteArrayList<android.content.IntentSender>()
    }
}
