package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.SlashCommand
import com.prudnikweb.hypernova.data.TermLink
import com.prudnikweb.hypernova.data.TermMenu
import com.prudnikweb.hypernova.ui.session.CommandsTab
import com.prudnikweb.hypernova.ui.session.SessionScreen
import com.prudnikweb.hypernova.ui.session.isSignInCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ways into a sign-in from a session (the owner's "I was able to select the subscription or the
 * API ... after that I didn't show me the long URL"): a /login goes to the app's own, and Claude
 * Code's own, on the terminal's screen, gets a card that says where to go instead of a dead end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h900dp")
class SignInRoutesTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val id = "s-login"

    @Before fun setUp() = resetPrefs()

    /** /login's first screen as the daemon reads it (daemon/test/fixtures/login-screens/login-method.txt). */
    private val method = TermMenu(
        id = "m-login", title = "Login", body = listOf("Select login method:"),
        options = listOf(TermMenu.Option("Claude account with subscription", null, false), TermMenu.Option("Anthropic Console account", null, false)),
        cursor = 0, more = false, cancel = true, alt = null,
    )

    private fun session(codex: Boolean = false, build: Session.() -> Session = { this }) =
        Samples.session(id, "Sign-in", SessionState.Idle, 1).copy(term = TermLink("t-login", mac = 0, phone = 0), agent = if (codex) AgentKind.CODEX else AgentKind.CLAUDE).build()

    private fun chat(s: Session): FakeHub {
        val hub = FakeHub(sessions = listOf(s), approvals = emptyMap(), transcripts = emptyMap())
        rule.setContent { Harness(spy) { SessionScreen(hub, id, onBack = {}) } }
        rule.waitForIdle()
        return hub
    }

    private fun send(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.waitForIdle()
        rule.onNode(hasContentDescription(str(R.string.send))).performClick()
        rule.waitForIdle()
    }

    @Test fun onlyTheCommandItselfIsASignIn() {
        assertTrue(isSignInCommand("/login"))
        assertTrue(isSignInCommand("  /login "))
        assertFalse(isSignInCommand("/logout"))
        assertFalse(isSignInCommand("/login please"))
        assertFalse(isSignInCommand("login"))
    }

    @Test fun aLoginFromTheBoxGoesToTheSignInNotIntoTheTerminal() {
        val hub = chat(session())
        send("/login ")
        assertEquals(listOf("signIn"), spy.opened)
        assertTrue("nothing typed: ${hub.calls}", hub.calls.none { it.startsWith("send") })
        rule.onNodeWithText("/login", substring = true).assertDoesNotExist()
    }

    @Test fun anythingElseFromTheBoxStillGoes() {
        val hub = chat(session())
        send("/logout")
        assertEquals(emptyList<String>(), spy.opened)
        assertTrue(hub.calls.toString(), hub.calls.any { it.startsWith("send") })
    }

    @Test fun codexKeepsItsOwnSlashes() {
        val hub = chat(session(codex = true))
        send("/login")
        assertEquals(emptyList<String>(), spy.opened)
        assertTrue(hub.calls.toString(), hub.calls.any { it.startsWith("send") })
    }

    @Test fun claudeCodesOwnSignInInTheTerminalGetsOneCardWithTheWaysOut() {
        // The code prompt: no menu, the session needs you. It used to be "Claude is waiting in the terminal".
        val hub = chat(session { copy(state = SessionState.NeedsYou, loginScreen = true) })
        rule.onNodeWithText(str(R.string.signin_terminal_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.waiting_terminal_title, "Claude")).assertDoesNotExist()
        rule.snap("signin_terminal_card")
        rule.onNodeWithText(str(R.string.open_terminal)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("terminal:$id"), spy.opened)
        rule.onNodeWithText(str(R.string.signin_here)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("terminal:$id", "signIn"), spy.opened)
        assertTrue("Esc closes /login's screens: ${hub.calls}", hub.calls.any { it.startsWith("interrupt") })
    }

    @Test fun itsMenuIsThatCardTooNotButtonsLeadingNowhere() {
        chat(session { copy(state = SessionState.NeedsYou, loginScreen = true, menu = method) })
        rule.onNodeWithText(str(R.string.signin_terminal_title)).assertIsDisplayed()
        rule.onNodeWithText("Claude account with subscription").assertDoesNotExist()
    }

    @Test fun aMenuThatIsntASignInStaysAMenu() {
        chat(session { copy(state = SessionState.NeedsYou, menu = method) })
        rule.onNodeWithText("Claude account with subscription").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.signin_terminal_title)).assertDoesNotExist()
    }

    @Test fun anotherWaitInTheTerminalStillSaysSo() {
        chat(session { copy(state = SessionState.NeedsYou) })
        rule.onNodeWithText(str(R.string.waiting_terminal_title, "Claude")).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.signin_terminal_title)).assertDoesNotExist()
    }

    @Test fun theCommandsTabsLoginGoesToTheSignInToo() {
        val hub = FakeHub(sessions = listOf(session()), approvals = emptyMap(), transcripts = emptyMap())
        hub.slashCommands = listOf(SlashCommand("login", "Sign in", "", emptyList(), builtin = true))
        var dismissed = 0
        rule.setContent { Harness(spy) { CommandsTab(hub, session(), onDismiss = { dismissed++ }) } }
        rule.waitUntil(3_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("/login")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("/login").performClick()
        rule.waitForIdle()
        assertEquals(listOf("signIn"), spy.opened)
        assertEquals(1, dismissed)
        assertTrue(hub.calls.toString(), hub.calls.none { it.startsWith("send") })
    }
}
