package com.prudnikweb.hypernova.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.Balance
import com.prudnikweb.hypernova.data.ClaudeState
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderCheck
import com.prudnikweb.hypernova.data.ProviderInput
import com.prudnikweb.hypernova.data.ProviderModels
import com.prudnikweb.hypernova.data.ProviderRefused
import com.prudnikweb.hypernova.data.ProviderSaved
import com.prudnikweb.hypernova.data.ProviderStatus
import com.prudnikweb.hypernova.ui.settings.ProviderEditScreen
import com.prudnikweb.hypernova.ui.settings.ProvidersScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A hub with providers to show and edit: the screens' calls (saveProvider, checkNewProvider,
 * checkProvider, deleteProvider) land in [calls] and answer what a test sets. Everything else is
 * [FakeHub]'s.
 */
private class ProvidersHub(private val base: FakeHub = FakeHub()) : Hub by base {
    var list by mutableStateOf(listOf<Provider>())
    var acct by mutableStateOf(Account(plan = "max"))
    val calls = mutableListOf<String>()
    var saveAnswer: Result<ProviderSaved>? = null
    var checkAnswer: Result<ProviderCheck>? = null
    var deleteAnswer: Result<Unit> = Result.success(Unit)
    var saved: ProviderInput? = null

    override val providers: List<Provider> get() = list
    override val account: Account get() = acct
    override val claude: ClaudeState get() = ClaudeState(available = true, version = "2.1.314", agents = true)

    override suspend fun checkProvider(id: String): Result<Provider> {
        calls += "checkProvider($id)"
        return list.firstOrNull { it.id == id }?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
    }

    override suspend fun checkNewProvider(kind: String, baseUrl: String?, apiKey: String?, id: String?): Result<ProviderCheck> {
        calls += "checkNewProvider($kind, $baseUrl, $apiKey, $id)"
        return checkAnswer!!
    }

    override suspend fun saveProvider(id: String?, input: ProviderInput): Result<ProviderSaved> {
        calls += "saveProvider($id)"
        saved = input
        return saveAnswer!!.onSuccess { s -> list = list.filter { it.id != s.provider.id } + s.provider }
    }

    override suspend fun deleteProvider(id: String): Result<Unit> {
        calls += "deleteProvider($id)"
        return deleteAnswer.onSuccess { list = list.filter { it.id != id } }
    }
}

/**
 * Settings → Agents & providers (ui/settings/Providers.kt): the list (the agents, then every
 * provider; OpenRouter's balance read again as it opens), and the editor: Check, Save with the
 * server's verdict, a refusal under its field, a key left behind, Delete after asking.
 */
@RunWith(RobolectricTestRunner::class)
class ProvidersScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = ProvidersHub()
    private val edits = mutableListOf<String>()
    private var back = 0
    private var focus: androidx.compose.ui.focus.FocusManager? = null

    private val builtIn = Provider("anthropic", "Claude (your subscription)", "anthropic", null, hasKey = false, models = ProviderModels(), builtIn = true)
    private val openRouter = Provider(
        "prov-openrouter", "OpenRouter", "openrouter", "https://openrouter.ai/api", hasKey = true,
        models = ProviderModels(default = "anthropic/claude-sonnet-5"), builtIn = false, balance = Balance(4.2, 0.31, 37, 50, 1L, null),
    )
    private val gateway = Provider(
        "prov-gateway", "Gateway", "custom", "https://gateway.example.com", hasKey = true,
        models = ProviderModels(default = "gateway-large", haiku = "gateway-small"), builtIn = false,
        status = ProviderStatus(ProviderStatus.BAD_KEY, "Gateway refused the key.", null),
    )

    @Before fun setUp() {
        resetPrefs()
        hub.list = listOf(builtIn, openRouter, gateway)
    }

    private fun list() {
        rule.setContent { Harness(spy) { ProvidersScreen(hub, onBack = { back++ }, onEdit = { id, kind -> edits += "$id/$kind" }) } }
        rule.waitForIdle()
    }

    private fun editor(id: String?, kind: String) {
        rule.setContent {
            focus = androidx.compose.ui.platform.LocalFocusManager.current
            Harness(spy) { ProviderEditScreen(hub, id, kind, onBack = { back++ }) }
        }
        rule.waitForIdle()
    }

    private fun field(label: Int) = rule.onNode(hasSetTextAction() and hasContentDescription(str(label)))

    private fun click(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun theListShowsTheAgentsThenEachProviderAndReadsOpenRoutersBalanceAgain() {
        list()
        // Only OpenRouter has a balance to read; the built-in one and a custom endpoint don't.
        assertEquals(listOf("checkProvider(prov-openrouter)"), hub.calls)
        rule.onNodeWithText("Gateway refused the key.").assertExists()
        rule.onNodeWithText(str(R.string.providers_intro)).assertExists()
        rule.snap("providers_list")
        // The built-in one opens nothing; the others their editor; Add opens a new one of its kind.
        click("Claude (your subscription)")
        assertTrue(edits.isEmpty())
        click("Gateway")
        click(str(R.string.add_openrouter))
        click(str(R.string.add_custom))
        assertEquals(listOf("prov-gateway/custom", "null/openrouter", "null/custom"), edits)
    }

    @Test fun theAgentsCardsLinkToTheirAccountPages() {
        val both = object : Hub by hub {
            override val codex get() = com.prudnikweb.hypernova.data.CodexState(available = true, enabled = true, connected = true, version = "0.164.0")
        }
        rule.setContent { Harness(spy) { ProvidersScreen(both, onBack = {}, onEdit = { _, _ -> }) } }
        rule.waitForIdle()
        val app = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
        rule.onNodeWithText(str(R.string.ag_account_claude)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("https://claude.ai/settings/usage", app.nextStartedActivity.dataString)
        rule.onNodeWithText(str(R.string.ag_account_codex)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("https://chatgpt.com/#settings", app.nextStartedActivity.dataString)
    }

    @Test fun withoutClaudeCodeThereAreNoProvidersToAdd() {
        val noClaude = object : Hub by hub {
            override val claude: ClaudeState get() = ClaudeState(available = false, version = null)
        }
        rule.setContent { Harness(spy) { ProvidersScreen(noClaude, onBack = {}, onEdit = { id, kind -> edits += "$id/$kind" }) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.ag_providers_without_claude)).assertExists()
        rule.onNodeWithText(str(R.string.add_openrouter)).assertDoesNotExist()
        rule.onNodeWithText("Gateway").assertDoesNotExist()
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun aNewCustomProviderIsCheckedThenSavedWithTheServersVerdict() {
        hub.checkAnswer = Result.success(ProviderCheck("ok", "Key works. 2 models.", listOf("gateway-large", "gateway-small"), null, 1L))
        editor(null, "custom")
        // Nothing typed: Save says what's missing and asks nothing.
        click(str(R.string.save))
        rule.onNodeWithText(str(R.string.provider_needs_name)).assertExists()
        assertTrue(hub.calls.isEmpty())
        field(R.string.provider_name).performTextReplacement("Local gateway")
        field(R.string.provider_base_url).performTextReplacement("https://gw.example.com")
        field(R.string.provider_api_key).performTextReplacement("gw-key-0123")
        rule.waitForIdle()
        click(str(R.string.provider_check))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        assertEquals(listOf("checkNewProvider(custom, https://gw.example.com, gw-key-0123, null)"), hub.calls)
        rule.onNodeWithText("Key works. 2 models.").assertExists()
        // No cursor in the picture: it blinks.
        rule.runOnIdle { focus!!.clearFocus() }
        rule.snap("provider_edit_checked")
        val made = gateway.copy(id = "prov-new", name = "Local gateway", status = null)
        hub.saveAnswer = Result.success(ProviderSaved(made, ProviderCheck("ok", "Key works. 2 models.", null, null, 1L), keyDropped = false))
        click(str(R.string.save))
        rule.waitUntil(3_000) { back == 1 }
        assertEquals("saveProvider(null)", hub.calls.last())
        assertEquals(ProviderInput("Local gateway", "custom", "https://gw.example.com", "gw-key-0123", ProviderModels("", "", "", ""), paid = true), hub.saved)
        assertEquals(listOf(str(R.string.pv_saved_verdict, "Key works. 2 models.")), spy.toasts)
    }

    @Test fun aRefusalGoesUnderItsFieldAndTheEditorStays() {
        hub.saveAnswer = Result.failure(ProviderRefused("insecure_url", "The key would travel unencrypted. Use the https:// address."))
        editor("prov-gateway", "custom")
        // The saved key is never shown; the field keeps it unless a new one is typed.
        field(R.string.provider_api_key).assertExists()
        rule.onNode(hasText("gw-key", substring = true)).assertDoesNotExist()
        field(R.string.provider_base_url).performTextReplacement("http://gateway.example.com")
        click(str(R.string.save))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        assertEquals(listOf("saveProvider(prov-gateway)"), hub.calls)
        rule.onNodeWithText("The key would travel unencrypted. Use the https:// address.").assertExists()
        assertEquals(0, back)
        // No new key typed: none sent.
        assertEquals(null, hub.saved?.apiKey)
    }

    @Test fun aKeyLeftBehindByANewHostKeepsTheEditorOpenAndSaysSo() {
        hub.saveAnswer = Result.success(ProviderSaved(gateway.copy(baseUrl = "https://other.example.com", hasKey = false, status = null), null, keyDropped = true))
        editor("prov-gateway", "custom")
        field(R.string.provider_base_url).performTextReplacement("https://other.example.com")
        click(str(R.string.save))
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        rule.onNodeWithText(str(R.string.pv_key_dropped)).assertExists()
        assertEquals(0, back)
        assertTrue(spy.toasts.isEmpty())
    }

    @Test fun deleteAsksFirstThenGoesBack() {
        editor("prov-gateway", "custom")
        click(str(R.string.delete))
        val ask = spy.confirms.single()
        assertEquals(str(R.string.delete_provider_q, "Gateway"), ask.title)
        assertTrue(ask.danger)
        assertTrue(hub.calls.isEmpty())
        ask.onConfirm()
        rule.waitUntil(3_000) { back == 1 }
        assertEquals(listOf("deleteProvider(prov-gateway)"), hub.calls)
        assertEquals(listOf(str(R.string.pv_deleted)), spy.toasts)
        assertTrue(hub.list.none { it.id == "prov-gateway" })
    }

    @Test fun aDeleteTheServerRefusesSaysWhyAndStays() {
        hub.deleteAnswer = Result.failure(com.prudnikweb.hypernova.data.HubException("That provider isn’t set up on this server."))
        editor("prov-gateway", "custom")
        click(str(R.string.delete))
        spy.confirms.single().onConfirm()
        rule.waitUntil(3_000) { hub.calls.isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithText("That provider isn’t set up on this server.").assertExists()
        assertEquals(0, back)
    }
}
