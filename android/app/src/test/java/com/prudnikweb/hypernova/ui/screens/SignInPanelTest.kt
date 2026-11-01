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
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.home.SignInPanel
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
    override suspend fun signIn(onMac: Boolean): Result<Unit> { calls += "signIn(onMac=$onMac)"; return Result.success(Unit) }
    override suspend fun sendSignInCode(code: String): Result<Unit> { calls += "sendSignInCode($code)"; return Result.success(Unit) }
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

    @Test fun signInHereOrOnTheServer() {
        show()
        click(str(R.string.account_sign_in))
        click(str(R.string.account_sign_in_mac))
        assertEquals(listOf("signIn(onMac=false)", "signIn(onMac=true)"), hub.calls)
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
        login("failed", error = "Signing in timed out.")
        rule.onNodeWithText("Signing in timed out.").assertExists()
        click(str(R.string.try_again))
        click(str(R.string.cancel))
        assertEquals(listOf("signIn(onMac=true)", "signIn(onMac=false)", "cancelSignIn"), hub.calls)
        assertTrue(spy.toasts.isEmpty())
    }
}
