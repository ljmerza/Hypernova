package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Settings → About's update row (data/UpdatePolicy.kt [AboutUpdate]): there only while the computer
 * offers a newer app, following the in-app update's steps for that version; and `{t:"app"}`
 * replacing the snapshot's offer (docs/PROTOCOL.md, "The app update").
 */
class AboutUpdateTest {
    private val offer = AppUpdate("0.6.0", 500_000, "https://192.168.0.149:47323/download/android")

    @Test fun noOfferNoRow() {
        assertNull(AboutUpdate.of(null, UpdateState.Idle, null))
        // A flow left over for a version no longer offered doesn't bring the row back.
        assertNull(AboutUpdate.of(null, UpdateState.Downloading(500_000, 10, 100), offer))
    }

    @Test fun offeredSaysUpdate() {
        val row = AboutUpdate.of(offer, UpdateState.Idle, null)!!
        assertEquals(AboutUpdate.Step.Offered, row.step)
        assertEquals(AboutUpdate.Action.Update, row.action)
        assertEquals("0.6.0", row.versionName)
        assertEquals(offer, row.update)
    }

    @Test fun followsTheFlowForThatVersion() {
        fun at(s: UpdateState) = AboutUpdate.of(offer, s, offer)!!
        at(UpdateState.NeedsPermission(500_000)).let {
            assertEquals(AboutUpdate.Step.NeedsPermission, it.step)
            assertEquals(AboutUpdate.Action.Allow, it.action)
        }
        at(UpdateState.Downloading(500_000, 25, 100)).let {
            assertEquals(AboutUpdate.Step.Downloading, it.step)
            assertNull(it.action)
            assertEquals(0.25f, it.fraction!!, 0.0001f)
        }
        // The size not known yet: no fraction, the bar shows only its track.
        assertNull(at(UpdateState.Downloading(500_000, 25, -1)).fraction)
        at(UpdateState.Checking(500_000)).let {
            assertEquals(AboutUpdate.Step.Checking, it.step)
            assertNull(it.action)
        }
        at(UpdateState.Confirming(500_000, shown = false)).let {
            assertEquals(AboutUpdate.Step.Confirming, it.step)
            assertEquals(AboutUpdate.Action.Install, it.action)
        }
        assertEquals(AboutUpdate.Action.Install, at(UpdateState.Confirming(500_000, shown = true)).action)
        at(UpdateState.Failed(500_000, UpdateFailure.Network)).let {
            assertEquals(AboutUpdate.Step.Failed, it.step)
            assertEquals(AboutUpdate.Action.TryAgain, it.action)
            assertEquals(UpdateFailure.Network, it.failure)
        }
    }

    @Test fun aFlowForAnotherVersionStartsAfresh() {
        val older = AppUpdate("0.5.9", 499_000, offer.url)
        val row = AboutUpdate.of(offer, UpdateState.Failed(499_000, UpdateFailure.OtherKey), older)!!
        assertEquals(AboutUpdate.Step.Offered, row.step)
        assertEquals(AboutUpdate.Action.Update, row.action)
        assertNull(row.failure)
    }

    @Test fun aVersionWithoutANameIsNamedByItsBuild() {
        assertNull(AboutUpdate.of(AppUpdate(" ", 500_000, offer.url), UpdateState.Idle, null)!!.versionName)
    }

    @Test fun theAppMessageReplacesTheOffer() {
        val mac = MacInfo(id = "m", name = "z2", daemonVersion = "0.6.0", claudeVersion = null, projectsRoot = null, appVersionCode = 100, appVersionName = "0.5.0")
        Wire.withApp(mac, JSONObject("""{"versionCode":500000,"versionName":"0.6.0"}""")).let {
            assertEquals(500_000, it.appVersionCode)
            assertEquals("0.6.0", it.appVersionName)
            assertEquals("z2", it.name)
        }
        // None any more (the APK taken away): no offer.
        Wire.withApp(mac, null).let {
            assertNull(it.appVersionCode)
            assertNull(it.appVersionName)
        }
        assertNull(Wire.withApp(mac, JSONObject("""{"versionName":"0.6.0"}""")).appVersionCode)
    }

    @Test fun allServersOffersTheNewestOfAny() {
        val older = AppUpdate("0.5.9", 499_000, offer.url, server = "z2")
        val newer = AppUpdate("0.6.0", 500_000, "https://192.168.0.50:47323/download/android", server = "shed")
        assertNull(AboutUpdate.newest(emptyList()))
        assertNull(AboutUpdate.newest(listOf(null, null)))
        // Not the one picked first's when another server has a newer one.
        assertEquals(newer, AboutUpdate.newest(listOf(older, null, newer)))
        assertEquals(newer, AboutUpdate.newest(listOf(newer, older)))
        // The same version on two: the first's (the one picked).
        val same = newer.copy(server = "z2")
        assertEquals(same, AboutUpdate.newest(listOf(same, newer)))
    }
}
