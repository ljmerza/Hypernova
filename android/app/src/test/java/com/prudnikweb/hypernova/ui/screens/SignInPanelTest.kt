package com.prudnikweb.hypernova.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.home.SignInPanel
import com.prudnikweb.hypernova.ui.home.SignInSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** A hub whose account a test sets, and whose sign-in calls land in [calls]. */
private class AccountHub(private val base: FakeHub = FakeHub()) : Hub by base {
    var acct by mutableStateOf(Account(state = Account.LOGGED_OUT, reason = "not_logged_in"))
    val calls = mutableListOf<String>()
    override val account: Account get() = acct
    override suspend fun checkAccount(): Result<Unit> { calls += "checkAccount"; return Result.success(Unit) }
    override val mac get() = base.mac.copy(unavailable = unavailable)
    var unavailable by mutableStateOf<Map<String, String>>(emptyMap())
    override suspend fun signIn(onMac: Boolean, console: Boolean, again: Boolean): Result<Unit> { calls += "signIn(onMac=$onMac, console=$console, again=$again)"; return Result.success(Unit) }
    var codeAnswer: Result<Unit> = Result.success(Unit)
    override suspend fun sendSignInCode(code: String): Result<Unit> { calls += "sendSignInCode($code)"; return codeAnswer }
    override suspend fun cancelSignIn(): Result<Unit> { calls += "cancelSignIn"; return Result.success(Unit) }
}

/**
 * Signing Claude Code in again from the phone (ui/home/HomeScreen.kt, SignInPanel: the account card's
 * and the chat's): Sign in here or on the server, then each step of the sign-in as the server says
 * it (starting, its page and the code, finishing, failed, on the server), Cancel throughout.
 */
@RunWith(RobolectricTestRunner::class)
class SignInPanelTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = AccountHub()
    private val page = "https://claude.ai/oauth/authorize?code=true&state=FakeState1x0123"

    @Before fun setUp() = resetPrefs()

    private fun show() {
        rule.setContent { Harness(spy) { SignInPanel(hub, "z2", Modifier.padding(16.dp)) } }
        rule.waitForIdle()
    }

    private fun login(state: String, url: String? = null, onMac: Boolean = false, error: String? = null) {
        hub.acct = hub.acct.copy(login = Account.Login(state, url, onMac, error))
        rule.waitForIdle()
    }

    private fun click(text: String) {
        rule.onNodeWithText(text).performClick()
        rule.waitForIdle()
    }

    private val codeField get() = rule.onNode(hasSetTextAction() and hasContentDescription(str(R.string.signin_code_hint)))

    @Test fun signInAsksSubscriptionOrApiAndStartsTheOneChosen() {
        show()
        // Nothing starts on Sign in: it asks.
        click(str(R.string.account_sign_in))
        assertEquals(emptyList<String>(), hub.calls)
        rule.onNodeWithText(str(R.string.signin_choice_title)).assertExists()
        rule.snap("signin_choice")
        click(str(R.string.signin_console))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        assertEquals(listOf("signIn(onMac=false, console=true, again=false)"), hub.calls)
    }

    @Test fun onAMacItsWindowAsksTooAndCancelGoesBack() {
        var cancelled = 0
        rule.setContent { Harness(spy) { SignInPanel(hub, "z2", Modifier.padding(16.dp), onCancel = { cancelled++ }) } }
        rule.waitForIdle()
        click(str(R.string.account_sign_in_mac))
        rule.onNodeWithText(str(R.string.signin_choice_title)).assertExists()
        click(str(R.string.cancel))
        assertEquals(1, cancelled)
        rule.onNodeWithText(str(R.string.signin_choice_title)).assertDoesNotExist()
        click(str(R.string.account_sign_in_mac))
        click(str(R.string.signin_subscription))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        assertEquals(listOf("signIn(onMac=true, console=false, again=false)"), hub.calls)
    }

    @Test fun aLinuxServerHasNoWindowToSignInIn() {
        hub.unavailable = mapOf(com.prudnikweb.hypernova.data.Mac.FEATURE_OPEN_WINDOW to "This server has no screen.")
        show()
        rule.onNodeWithText(str(R.string.account_sign_in)).assertExists()
        rule.onNodeWithText(str(R.string.account_sign_in_mac)).assertDoesNotExist()
    }

    @Test fun signedInAlreadySigningInAgainSaysSoToTheServer() {
        hub.acct = Account(state = Account.OK)
        rule.setContent { Harness(spy) { SignInPanel(hub, "z2", Modifier.padding(16.dp), choosing = true) } }
        rule.waitForIdle()
        click(str(R.string.signin_console))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        assertEquals(listOf("signIn(onMac=false, console=true, again=true)"), hub.calls)
    }

    @Test fun theSheetAsksThenShowsTheAddressAndTheCodeBoxThenClosesAndSaysSo() {
        var closed = 0
        rule.setContent { Harness(spy) { SignInSheet(hub, onDismiss = { closed++ }) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.signin_replaces, hub.mac.name)).assertDoesNotExist()
        click(str(R.string.signin_console))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        assertEquals(listOf("signIn(onMac=false, console=true, again=false)"), hub.calls)
        login("waiting_code", url = page)
        rule.onNodeWithText(str(R.string.signin_open_page)).assertExists()
        codeField.assertExists()
        rule.onNodeWithText(str(R.string.signin_choice_title)).assertDoesNotExist()
        assertEquals(0, closed)
        hub.acct = hub.acct.copy(state = Account.OK, login = null)
        rule.waitForIdle()
        assertEquals(1, closed)
        assertEquals(listOf(str(R.string.signin_done)), spy.toasts)
    }

    @Test fun theSheetSaysWhatSigningInAgainDoesAndCancelClosesItQuietly() {
        hub.acct = Account(state = Account.OK)
        var closed = 0
        rule.setContent { Harness(spy) { SignInSheet(hub, onDismiss = { closed++ }) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.signin_replaces, hub.mac.name)).assertExists()
        click(str(R.string.cancel))
        assertEquals(1, closed)
        assertTrue(spy.toasts.isEmpty())
    }

    @Test fun whatTheServerSaysOfACodeIsInTheSheetNotInAToastBehindIt() {
        hub.codeAnswer = Result.failure(RuntimeException("That isn't the whole code. Copy it again from the sign-in page."))
        rule.setContent { Harness(spy) { SignInSheet(hub, onDismiss = {}) } }
        rule.waitForIdle()
        login("waiting_code", url = page)
        codeField.performTextReplacement("half")
        rule.waitForIdle()
        click(str(R.string.send))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        rule.onNodeWithText("That isn't the whole code. Copy it again from the sign-in page.").assertExists()
        assertTrue(spy.toasts.isEmpty())
    }

    @Test fun aSignInCancelledFromTheSheetClosesItWithoutSayingItWorked() {
        var closed = 0
        rule.setContent { Harness(spy) { SignInSheet(hub, onDismiss = { closed++ }) } }
        rule.waitForIdle()
        login("waiting_code", url = page)
        click(str(R.string.cancel))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        hub.acct = hub.acct.copy(login = null)
        rule.waitForIdle()
        assertEquals(1, closed)
        assertTrue(spy.toasts.isEmpty())
    }

    @Test fun eachStepOfASignInAsTheServerSaysIt() {
        show()
        login("starting")
        rule.onNodeWithText(str(R.string.signin_starting, "z2")).assertExists()
        rule.onNodeWithText(str(R.string.account_sign_in)).assertDoesNotExist()
        login("finishing")
        rule.onNodeWithText(str(R.string.signin_finishing)).assertExists()
        // On the server: one line and Cancel, no page and no code here.
        login("waiting_code", onMac = true)
        rule.onNodeWithText(str(R.string.signin_on_mac)).assertExists()
        rule.onNodeWithText(str(R.string.signin_open_page)).assertDoesNotExist()
        click(str(R.string.cancel))
        assertEquals(listOf("cancelSignIn"), hub.calls)
    }

    @Test fun thePageOpensAndItsCodeIsPastedAndSent() {
        show()
        login("waiting_code", url = page)
        rule.onNodeWithText(str(R.string.signin_steps)).assertExists()
        rule.snap("signin_waiting_code")
        // The page opens in the browser.
        click(str(R.string.signin_open_page))
        val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(page, opened.dataString)
        // Paste with nothing copied says so; with the code copied it fills the field, and Send sends it.
        click(str(R.string.signin_paste))
        assertEquals(listOf(str(R.string.signin_nothing_to_paste)), spy.toasts)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", "  AuthCode0123#FakeState1x0123 "))
        click(str(R.string.signin_paste))
        codeField.assertExists()
        rule.onNodeWithText("AuthCode0123#FakeState1x0123").assertExists()
        click(str(R.string.send))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        assertEquals(listOf("sendSignInCode(AuthCode0123#FakeState1x0123)"), hub.calls)
        // Sent: the field is empty again, ready for the next one.
        rule.onNodeWithText("AuthCode0123#FakeState1x0123").assertDoesNotExist()
    }

    @Test fun aRefusedCodeSaysSoOverTheField() {
        show()
        login("waiting_code", url = page, error = "That code was turned down. The sign-in started over.")
        rule.onNodeWithText("That code was turned down. The sign-in started over.").assertExists()
        rule.onNodeWithText(str(R.string.signin_steps)).assertDoesNotExist()
        codeField.assertExists()
    }

    @Test fun aFailedSignInOffersTryAgainTheSameWay() {
        show()
        login("failed", onMac = true)
        rule.onNodeWithText(str(R.string.signin_failed)).assertExists()
        click(str(R.string.try_again))
        hub.acct = hub.acct.copy(login = Account.Login("failed", null, false, "Signing in timed out.", console = true))
        rule.waitForIdle()
        rule.onNodeWithText("Signing in timed out.").assertExists()
        click(str(R.string.try_again))
        click(str(R.string.cancel))
        assertEquals(listOf("signIn(onMac=true, console=false, again=false)", "signIn(onMac=false, console=true, again=false)", "cancelSignIn"), hub.calls)
        assertTrue(spy.toasts.isEmpty())
    }
}
