package com.prudnikweb.hypernova.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import com.prudnikweb.hypernova.ui.onboarding.Onboarding
import com.prudnikweb.hypernova.ui.onboarding.PairScreen
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
    @get:Rule val rule = createComposeRule()
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

    private fun onboarding(onClose: (() -> Unit)? = null) {
        rule.setContent { Harness(spy) { Onboarding(onPaste = { pasted += it }, onClose = onClose) } }
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
}
