package com.prudnikweb.hypernova.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.assertIsEnabled
import org.robolectric.Shadows.shadowOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.PairFlow
import com.prudnikweb.hypernova.data.PairLink
import com.prudnikweb.hypernova.data.PairLinks
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.ui.onboarding.OfferScreen
import com.prudnikweb.hypernova.ui.onboarding.Onboarding
import com.prudnikweb.hypernova.ui.onboarding.PairScreen
import com.prudnikweb.hypernova.ui.onboarding.ScanCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pairing on the phone (ui/onboarding): the steps with Paste, which takes a link off the clipboard
 * or opens a field for one; adding a server with Cancel; then "Pairing with…", done, and each
 * failure with what the owner can do (Try again only where it can help). The pairing itself:
 * data/live/PairTest.
 */
@RunWith(RobolectricTestRunner::class)
class PairingScreensTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val spy = AppSpy()
    private val pasted = mutableListOf<String>()
    private val link = "https://192.168.1.20:47323/pair?c=c0de-1234&n=z2"

    @Before fun setUp() {
        resetPrefs()
        clipboard(null)
    }

    private fun clipboard(text: String?) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        if (text == null) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("link", text))
    }

    // ─── Stand-ins: the camera (none under Robolectric) and Android's "Allow the camera?" ───

    private var cameraOn = 0
    private var cameraStops = 0
    private var scanned: ((String) -> Unit)? = null
    private var cameraFails: (() -> Unit)? = null

    private fun camera(present: Boolean = true) = ScanCamera(
        present = { present },
        viewfinder = { modifier, onCode, onFailed ->
            scanned = onCode
            cameraFails = onFailed
            DisposableEffect(Unit) {
                cameraOn++
                onDispose { cameraStops++ }
            }
            Box(modifier)
        },
    )

    private val asked = mutableListOf<String>()
    private var answer = false
    private val permissions = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                asked += input as String
                dispatchResult(requestCode, answer)
            }
        }
    }

    private fun allowCamera(allowed: Boolean) {
        val app = shadowOf(context as android.app.Application)
        if (allowed) app.grantPermissions(Manifest.permission.CAMERA) else app.denyPermissions(Manifest.permission.CAMERA)
    }

    private fun onboarding(onClose: (() -> Unit)? = null, cam: ScanCamera = camera()) {
        rule.setContent {
            Harness(spy) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissions) {
                    Onboarding(onPaste = { pasted += it }, onClose = onClose, camera = cam)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tapScan() {
        rule.onNodeWithText(str(R.string.onb_scan)).performClick()
        rule.waitForIdle()
    }

    @Config(qualifiers = "+h1300dp")
    @Test fun theStepsAsAFreshInstallShowsThem() {
        onboarding()
        rule.onNodeWithText(str(R.string.onb_title)).assertExists()
        for (s in listOf(R.string.onb_step1_title, R.string.onb_step2_title, R.string.onb_step3_title)) rule.onNodeWithText(str(s)).assertExists()
        // No Cancel: there is nothing to go back to.
        rule.onNodeWithText(str(R.string.cancel)).assertDoesNotExist()
        rule.snap("onboarding")
    }

    @Test fun pasteTakesALinkOffTheClipboard() {
        clipboard("from the server: $link thanks")
        onboarding()
        rule.onNodeWithText(str(R.string.onb_paste)).performScrollTo().performClick()
        assertEquals(listOf("from the server: $link thanks"), pasted)
        assertEquals("c0de-1234", PairLinks.parse(pasted.single())!!.code)
    }

    @Test fun withoutALinkOnTheClipboardAFieldTakesOne() {
        clipboard("not a link")
        onboarding()
        rule.onNodeWithText(str(R.string.onb_paste)).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(pasted.isEmpty())
        rule.onNodeWithText(str(R.string.onb_paste_help)).assertExists()
        // Pair waits for words.
        rule.onNodeWithText(str(R.string.onb_pair)).performScrollTo().assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput(link)
        rule.onNodeWithText(str(R.string.onb_pair)).performScrollTo().performClick()
        assertEquals(listOf(link), pasted)
    }

    @Config(qualifiers = "+h1300dp")
    @Test fun addingAServerSaysSoAndCanBeCancelled() {
        var closed = 0
        onboarding(onClose = { closed++ })
        rule.onNodeWithText(str(R.string.onb_title_add)).assertExists()
        rule.onNodeWithText(str(R.string.onb_intro_add)).assertExists()
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        assertEquals(1, closed)
        rule.snap("onboarding_add")
    }

    // ─── Scan ───

    @Test fun scanWithTheCameraAllowedOpensAtOnceAndAsksNothing() {
        allowCamera(true)
        onboarding()
        rule.onNodeWithText(str(R.string.scan_title)).assertDoesNotExist()
        tapScan()
        assertTrue("asked for nothing", asked.isEmpty())
        rule.onNodeWithText(str(R.string.scan_title)).assertExists()
        assertEquals(1, cameraOn)
    }

    @Config(qualifiers = "+h900dp")
    @Test fun whatItReadsGoesStraightOnWithNoTapAndTheCameraStops() {
        allowCamera(true)
        onboarding()
        tapScan()
        rule.snap("scan")
        val code = "https://192.168.1.20:47323/pair?c=c0de-1234&n=z2"
        rule.runOnIdle { scanned!!(code) }
        rule.waitForIdle()
        assertEquals(listOf(code), pasted)
        assertEquals("the camera stops as the screen goes", 1, cameraStops)
        rule.onNodeWithText(str(R.string.scan_title)).assertDoesNotExist()
    }

    /** It's the pairing screen that refuses it, in its own words (anUnreadableLinkSaysItIsntOne): the camera isn't left running over it. */
    @Test fun aCodeThatIsntALinkLeavesTheCameraToo() {
        allowCamera(true)
        onboarding()
        tapScan()
        rule.runOnIdle { scanned!!("https://example.com/menu") }
        rule.waitForIdle()
        assertEquals(listOf("https://example.com/menu"), pasted)
        assertEquals(1, cameraStops)
        assertEquals(null, PairLinks.parse(pasted.single()))
    }

    @Test fun cancelAndBackStopTheCamera() {
        allowCamera(true)
        onboarding()
        tapScan()
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        rule.waitForIdle()
        assertEquals(1, cameraStops)
        tapScan()
        assertEquals(2, cameraOn)
        systemBack()
        assertEquals(2, cameraStops)
        rule.onNodeWithText(str(R.string.onb_scan)).assertExists()
        assertTrue(pasted.isEmpty())
    }

    @Test fun theCameraIsAskedForOnlyOnTheTapAndOpensWhenAllowed() {
        allowCamera(false)
        answer = true
        onboarding()
        assertTrue("nothing asked on the way in", asked.isEmpty())
        tapScan()
        assertEquals(listOf(Manifest.permission.CAMERA), asked)
        rule.onNodeWithText(str(R.string.scan_title)).assertExists()
    }

    @Config(qualifiers = "+h900dp")
    @Test fun refusedSaysSoOffersPasteAndDoesntAskAgainByItself() {
        allowCamera(false)
        answer = false
        clipboard(link)
        onboarding()
        tapScan()
        assertEquals("once", 1, asked.size)
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertEquals("not in a loop", 1, asked.size)
        rule.onNodeWithText(str(R.string.onb_scan_refused)).assertExists()
        rule.onNodeWithText(str(R.string.scan_title)).assertDoesNotExist()
        assertEquals(0, cameraOn)
        rule.snap("scan_refused")
        // Scan stays, for when it's allowed in Android's settings: another tap, another ask.
        tapScan()
        assertEquals(2, asked.size)
        rule.onNodeWithText(str(R.string.onb_paste)).performClick()
        assertEquals(listOf(link), pasted)
    }

    @Test fun aCameraThatWontStartSaysSoAndOffersPaste() {
        allowCamera(true)
        clipboard(link)
        onboarding()
        tapScan()
        rule.runOnIdle { cameraFails!!() }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.scan_failed)).assertExists()
        assertEquals(1, cameraStops)
        rule.onNodeWithText(str(R.string.onb_paste)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(link), pasted)
        rule.onNodeWithText(str(R.string.scan_title)).assertDoesNotExist()
    }

    @Config(qualifiers = "+h1300dp")
    @Test fun aPhoneWithNoCameraHasOnlyPaste() {
        onboarding(cam = camera(present = false))
        rule.onNodeWithText(str(R.string.onb_scan)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.onb_step3_title_paste)).assertExists()
        rule.onNodeWithText(str(R.string.onb_paste)).assertExists()
    }

    // ─── "Add this server?": a link from outside the app ───

    private val offered = PairLinks.parse(
        "hypernova://pair?v=1&u=https%3A%2F%2F203.0.113.9%3A47323&c=c0de-1234&n=z2&fp=${"a".repeat(64)}&lan=192.168.1.20,192.168.1.21&lp=47323",
    )!!
    private var adds = 0

    private fun offer(l: PairLink = offered) {
        rule.setContent { Harness(spy) { OfferScreen(l, onAdd = { adds++ }, onCancel = { closes++ }) } }
        rule.waitForIdle()
    }

    @Config(qualifiers = "+h900dp")
    @Test fun itNamesTheServerAndEveryAddressTheCodeWouldGoTo() {
        offer()
        rule.onNodeWithText(str(R.string.pair_offer_title)).assertExists()
        rule.onNodeWithText(str(R.string.pair_offer_body)).assertExists()
        rule.onNodeWithText("z2").assertExists()
        // The listeners it would try, and `u` after them: a link can't show one and use another.
        for (place in listOf("192.168.1.20:47323", "192.168.1.21:47323", "203.0.113.9:47323")) rule.onNodeWithText(place).assertExists()
        rule.snap("pair_offer")
    }

    @Test fun manyAddressesAreCounted() {
        offer(offered.copy(lan = List(9) { "10.0.0.$it" }))
        rule.onNodeWithText("10.0.0.3:47323").assertExists()
        rule.onNodeWithText("10.0.0.4:47323").assertDoesNotExist()
        rule.onNodeWithText(str(R.string.pair_offer_more, 6)).assertExists()
    }

    @Test fun addIsTheOneTapButNotInTheFirstMoment() {
        rule.mainClock.autoAdvance = false
        offer()
        rule.mainClock.advanceTimeByFrame()
        // A tap meant for what was on screen when the link arrived.
        rule.onNodeWithText(str(R.string.pair_offer_add)).assertIsNotEnabled().performClick()
        assertEquals(0, adds)
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText(str(R.string.pair_offer_add)).assertIsEnabled().performClick()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1, adds)
    }

    @Test fun cancelAndBackAddNothing() {
        offer()
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        assertEquals(1, closes)
        systemBack()
        assertEquals(2, closes)
        assertEquals(0, adds)
    }

    // ─── The pairing's own screen ───

    private val pairLink = PairLink("https://192.168.1.20:47323", "c0de-1234", "z2")
    private val retried = mutableListOf<PairLink>()
    private var closes = 0
    private var dones = 0

    private fun pairScreen(flow: PairFlow) {
        var f by mutableStateOf(flow)
        rule.setContent { Harness(spy) { PairScreen(f, onRetry = { retried += it }, onClose = { closes++ }, onDone = { dones++ }) } }
        rule.waitForIdle()
    }

    @Test fun pairingSaysWhichComputerAndCanBeCancelled() {
        pairScreen(PairFlow.Working(pairLink))
        rule.onNodeWithText(str(R.string.pair_working, "z2")).assertExists()
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        assertEquals(1, closes)
    }

    @Test fun pairedSaysSoAndOpensTheApp() {
        AndroidCore.init(context as android.app.Application)
        pairScreen(PairFlow.Done("z2"))
        rule.onNodeWithText(str(R.string.pair_done, "z2")).assertExists()
        rule.onNodeWithText(str(R.string.pair_open)).performClick()
        assertEquals(1, dones)
    }

    @Test fun anExpiredCodeOffersNoTryAgain() {
        pairScreen(PairFlow.Failed(pairLink, Problem.CodeExpired, null))
        rule.onNodeWithText(str(R.string.pair_expired)).assertExists()
        rule.onNodeWithText(str(R.string.try_again)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.pair_back)).performClick()
        assertEquals(1, closes)
    }

    @Test fun noCodeShownAndTooManyTriesSayWhatToDo() {
        pairScreen(PairFlow.Failed(pairLink, Problem.CodeExpired, null, code = "pairing_closed"))
        rule.onNodeWithText(str(R.string.pair_closed)).assertExists()
        rule.onNodeWithText(str(R.string.pair_closed_body)).assertExists()
    }

    @Test fun tooManyWrongCodesSaysHowLongToWait() {
        pairScreen(PairFlow.Failed(pairLink, Problem.CodeExpired, null, code = "too_many_tries", retryAfterSeconds = 420))
        rule.onNodeWithText(str(R.string.pair_too_many)).assertExists()
        // 420 s: 7 minutes.
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.pair_too_many_body, 7, 7)).assertExists()
    }

    @Test fun anUnreadableLinkSaysItIsntOne() {
        pairScreen(PairFlow.Failed(null, null, null))
        rule.onNodeWithText(str(R.string.pair_bad_link)).assertExists()
        rule.onNodeWithText(str(R.string.pair_bad_link_body)).assertExists()
        rule.onNodeWithText(str(R.string.try_again)).assertDoesNotExist()
    }

    @Test fun nobodyAnsweringOffersTryAgainWithTheSameLink() {
        pairScreen(PairFlow.Failed(pairLink, Problem.Unreachable, null))
        rule.onNodeWithText(str(R.string.pair_failed, "z2")).assertExists()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        assertEquals(listOf(pairLink), retried)
        rule.snap("pair_failed")
    }

    @Test fun theServersOwnWordsWhenItSaysWhatWentWrong() {
        pairScreen(PairFlow.Failed(pairLink, Problem.Server, "The pairing store is full."))
        rule.onNodeWithText("The pairing store is full.").assertExists()
    }

    private fun systemBack() {
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
    }
}
