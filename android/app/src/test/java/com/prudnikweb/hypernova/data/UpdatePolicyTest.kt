package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app update (docs/PROTOCOL.md, "The app update"): where the APK comes from, when a browser
 * could open the Mac's page instead, what the archive must be, and the flow's steps.
 */
class UpdatePolicyTest {
    @Test fun theApkComesOverTheRouteInUse() {
        // The local listener (pinned), or the base URL.
        assertEquals("https://192.168.0.149:47323/download/android/Hypernova.apk", UpdatePolicy.apkUrl("https://192.168.0.149:47323"))
        assertEquals("https://hypernova.example.com/download/android/Hypernova.apk", UpdatePolicy.apkUrl("https://hypernova.example.com/"))
    }

    @Test fun aBrowserGetsThePageOnlyWhereItCouldOpenIt() {
        // An address: the computer's own certificate, which only the app pins (the local listener).
        assertNull(UpdatePolicy.browserPage("https://192.168.0.149:47323"))
        assertNull(UpdatePolicy.browserPage("https://[fd00::5]:47323"))
        // Plain http never (the daemon's fallback base URL among them).
        assertNull(UpdatePolicy.browserPage("http://hypernova.example.com"))
        assertNull(UpdatePolicy.browserPage("http://10.0.2.2:47321"))
        // A public name with a real certificate (a server of one's own): the browser can.
        assertEquals("https://hypernova.example.com/download/android", UpdatePolicy.browserPage("https://hypernova.example.com/"))
        assertNull(UpdatePolicy.browserPage("not a url"))
    }

    @Test fun theArchiveMustBeHypernovaAndNewer() {
        val me = "com.prudnikweb.hypernova"
        assertEquals(ArchiveVerdict.Ok, UpdatePolicy.verify(me, 418_000, me, 417_620))
        assertEquals(ArchiveVerdict.NotNewer, UpdatePolicy.verify(me, 417_620, me, 417_620))
        assertEquals(ArchiveVerdict.NotNewer, UpdatePolicy.verify(me, 417_000, me, 417_620))
        assertEquals(ArchiveVerdict.OtherApp, UpdatePolicy.verify("com.example.other", 999_999, me, 417_620))
        // Not an APK Android can read (a page, a truncated file).
        assertEquals(ArchiveVerdict.Unreadable, UpdatePolicy.verify(null, null, me, 417_620))
    }

    @Test fun androidsAnswersBecomeReasons() {
        // A signature mismatch comes as a conflict; its message tells it from others.
        assertEquals(UpdateFailure.OtherKey, UpdatePolicy.installFailure(UpdatePolicy.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.prudnikweb.hypernova signatures do not match newer version; ignoring!"))
        assertEquals(UpdateFailure.Install, UpdatePolicy.installFailure(UpdatePolicy.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_CONFLICTING_PROVIDER"))
        assertEquals(UpdateFailure.Storage, UpdatePolicy.installFailure(UpdatePolicy.STATUS_FAILURE_STORAGE, null))
        assertEquals(UpdateFailure.Damaged, UpdatePolicy.installFailure(UpdatePolicy.STATUS_FAILURE_INVALID, "INSTALL_PARSE_FAILED_NOT_APK"))
        assertEquals(UpdateFailure.Blocked, UpdatePolicy.installFailure(UpdatePolicy.STATUS_FAILURE_BLOCKED, null))
        assertEquals(UpdateFailure.NotNewer, UpdatePolicy.installFailure(UpdatePolicy.STATUS_FAILURE_INCOMPATIBLE, "INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals(UpdateFailure.Install, UpdatePolicy.installFailure(1, null))
        assertNull(UpdatePolicy.httpFailure(200))
        assertEquals(UpdateFailure.Missing, UpdatePolicy.httpFailure(404))
        assertEquals(UpdateFailure.Network, UpdatePolicy.httpFailure(502))
        assertEquals(UpdateFailure.Network, UpdatePolicy.httpFailure(401))
    }

    @Test fun progressIsShownAPercentAtATime() {
        val total = 40L * 1024 * 1024
        assertFalse(UpdatePolicy.progressWorthShowing(0, 64 * 1024, total))
        assertTrue(UpdatePolicy.progressWorthShowing(0, total / 100 + 1, total))
        assertFalse(UpdatePolicy.progressWorthShowing(total / 100 + 1, total / 100 + 64 * 1024, total))
        assertTrue(UpdatePolicy.progressWorthShowing(total - 1, total, total))
        // Size unknown: every quarter megabyte.
        assertFalse(UpdatePolicy.progressWorthShowing(0, 128 * 1024, -1))
        assertTrue(UpdatePolicy.progressWorthShowing(0, 256 * 1024, -1))
    }

    private fun run(vararg events: UpdateFlow.Event, from: UpdateState = UpdateState.Idle): UpdateState =
        events.fold(from) { s, e -> UpdateFlow.next(s, e) }

    @Test fun theHappyPathEndsInAndroidsDialog() {
        var s = run(UpdateFlow.Event.Start(500, canInstall = true))
        assertEquals(UpdateState.Downloading(500, 0, -1), s)
        s = UpdateFlow.next(s, UpdateFlow.Event.Progress(10, 100))
        assertEquals(0.1f, (s as UpdateState.Downloading).fraction!!, 0.0001f)
        s = run(UpdateFlow.Event.Downloaded, UpdateFlow.Event.Checked(ArchiveVerdict.Ok), from = s)
        assertEquals(UpdateState.Confirming(500, shown = false), s)
        s = UpdateFlow.next(s, UpdateFlow.Event.ConfirmShown)
        assertEquals(UpdateState.Confirming(500, shown = true), s)
        assertEquals(UpdateState.Idle, UpdateFlow.next(s, UpdateFlow.Event.Installed))
    }

    @Test fun installsAreAllowedOnceFirst() {
        var s = run(UpdateFlow.Event.Start(500, canInstall = false))
        assertEquals(UpdateState.NeedsPermission(500), s)
        // Progress before the download started means nothing.
        assertSame(s, UpdateFlow.next(s, UpdateFlow.Event.Progress(1, 2)))
        s = UpdateFlow.next(s, UpdateFlow.Event.Allowed)
        assertEquals(UpdateState.Downloading(500, 0, -1), s)
        // Not now: away, nothing kept.
        assertEquals(UpdateState.Idle, run(UpdateFlow.Event.Start(500, false), UpdateFlow.Event.Dismiss))
    }

    @Test fun aSecondTapWhileDownloadingDoesNothing() {
        val s = run(UpdateFlow.Event.Start(500, true), UpdateFlow.Event.Progress(5, 10))
        assertSame(s, UpdateFlow.next(s, UpdateFlow.Event.Start(500, true)))
        assertTrue(UpdateFlow.busy(s))
        // Nor can it be put away mid-download.
        assertSame(s, UpdateFlow.next(s, UpdateFlow.Event.Dismiss))
        // Allowed only means something while asking for it.
        assertSame(s, UpdateFlow.next(s, UpdateFlow.Event.Allowed))
    }

    @Test fun aBadArchiveNeverReachesAndroid() {
        fun checked(v: ArchiveVerdict) = run(UpdateFlow.Event.Start(500, true), UpdateFlow.Event.Downloaded, UpdateFlow.Event.Checked(v))
        assertEquals(UpdateState.Failed(500, UpdateFailure.OtherApp), checked(ArchiveVerdict.OtherApp))
        assertEquals(UpdateState.Failed(500, UpdateFailure.NotNewer), checked(ArchiveVerdict.NotNewer))
        assertEquals(UpdateState.Failed(500, UpdateFailure.Damaged), checked(ArchiveVerdict.Unreadable))
    }

    @Test fun aDeclinedInstallWaitsForInstallAndAFailureForTryAgain() {
        val confirming = run(UpdateFlow.Event.Start(500, true), UpdateFlow.Event.Downloaded, UpdateFlow.Event.Checked(ArchiveVerdict.Ok), UpdateFlow.Event.ConfirmShown)
        // No in Android's dialog: Install shows it again (the file stays).
        assertEquals(UpdateState.Confirming(500, shown = false), UpdateFlow.next(confirming, UpdateFlow.Event.Declined))
        // A Start while confirming is Install, not a new download.
        assertSame(confirming, UpdateFlow.next(confirming, UpdateFlow.Event.Start(500, true)))
        // Another key: Android's refusal, said as such.
        val failed = UpdateFlow.next(confirming, UpdateFlow.Event.InstallFailed(UpdatePolicy.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"))
        assertEquals(UpdateFailure.OtherKey, (failed as UpdateState.Failed).failure)
        // Try again downloads afresh.
        assertEquals(UpdateState.Downloading(500, 0, -1), UpdateFlow.next(failed, UpdateFlow.Event.Start(500, true)))
        // A broken download.
        assertEquals(UpdateState.Failed(500, UpdateFailure.Network), run(UpdateFlow.Event.Start(500, true), UpdateFlow.Event.Failed(UpdateFailure.Network)))
        // A failure with nothing under way changes nothing.
        assertEquals(UpdateState.Idle, UpdateFlow.next(UpdateState.Idle, UpdateFlow.Event.Failed(UpdateFailure.Network)))
        // Put away.
        assertEquals(UpdateState.Idle, UpdateFlow.next(failed, UpdateFlow.Event.Dismiss))
    }
}
