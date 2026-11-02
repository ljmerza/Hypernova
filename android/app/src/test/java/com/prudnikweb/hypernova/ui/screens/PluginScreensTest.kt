package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.settings.PluginBrowseScreen
import com.prudnikweb.hypernova.ui.settings.PluginScreen
import com.prudnikweb.hypernova.ui.settings.PluginsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val PLUGINS = """{"agent":"claude","scopes":["user","project","local"],"folders":[{"id":"home","name":"~"},{"id":"f1","name":"parser"}],
 "plugins":[{"id":"hello@testmp","name":"hello","marketplace":"testmp","version":"1.0.0","scope":"user","enabled":true,"hasConfig":true,"mcpServers":["hellomcp"]},{"id":"old@testmp","name":"old","marketplace":"testmp","scope":"project","enabled":false,"hasConfig":false,"mcpServers":[],"problem":"It failed to load."}],
 "marketplaces":[{"name":"testmp","type":"github","where":"acme/plugins"},{"name":"other","type":"directory","where":"/srv/mp"}]}"""
private const val DETAIL = """{"agent":"claude","id":"hello@testmp","description":"A test plugin","skills":["greet","hi"],"agents":["helper"],"hooks":["SessionStart"],"mcpServers":["hellomcp"],"lspServers":[],"commands":[],"alwaysOnTokens":17,
 "options":[{"key":"api_token","title":"API token","description":"A token","sensitive":true,"set":true},{"key":"region","title":"Region","sensitive":false,"set":true,"value":"eu"}]}"""
private const val AVAILABLE = """{"agent":"claude","total":120,"plugins":[{"id":"hello@testmp","description":"A test plugin","marketplace":"testmp","version":"1.0.0","source":"./plugins/hello","marketplaceSource":"github · acme/plugins"}]}"""

/** Agents & providers › an agent's settings › Plugins (ui/settings/PluginScreens.kt). */
@RunWith(RobolectricTestRunner::class)
class PluginScreensTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = ExtHub()
    private val opened = mutableListOf<String>()

    @Before fun setUp() = resetPrefs()
    @After fun tearDown() { OwnerCheck.prompt = null }

    private fun lock(pass: Boolean = true) {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(true)
        OwnerCheck.prompt = { ok, fail -> if (pass) ok() else fail(null) }
    }
    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().assertExists()
    private fun tap(text: String) { rule.onNodeWithText(text).performScrollTo().performClick(); rule.waitForIdle() }
    private fun sent(path: String) = hub.calls.last { it.first == "POST" && it.second == path }.third!!
    private fun list() {
        rule.setContent { Harness(spy) { PluginsScreen(hub, "claude", onBack = {}, onOpen = { id, s, f, on -> opened += "$id $s $f $on" }, onBrowse = { opened += "browse $it" }) } }
        rule.waitForIdle()
    }
    private fun page(enabled: Boolean = true) {
        rule.setContent { Harness(spy) { PluginScreen(hub, "claude", "hello@testmp", "user", "home", enabled, onBack = { opened += "back" }) } }
        rule.waitForIdle()
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun theListSaysWhatsInstalledAndFromWhereAndATapOpensIt() {
        hub.on("GET /v1/agents/plugins", PLUGINS)
        list()
        seen("hello")
        seen("testmp · 1.0.0")
        seen(str(R.string.pl_on))
        seen(str(R.string.pl_off))
        seen("It failed to load.")
        seen("acme/plugins")
        rule.onRoot().snap("plugins_list")
        tap("hello")
        assertEquals(listOf("hello@testmp user home true"), opened)
    }

    @Test fun aChosenFolderAsksForThatFolder() {
        hub.on("GET /v1/agents/plugins", PLUGINS)
        list()
        tap(str(R.string.mcp_folder))
        rule.onNodeWithText("parser").performClick()
        rule.waitForIdle()
        assertTrue(hub.calls.any { it.second.contains("folder=f1") })
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun addingAMarketplaceShowsItsSourceAndAsksForTheFingerprint() {
        hub.on("GET /v1/agents/plugins", PLUGINS)
        hub.on("POST /v1/agents/plugins/marketplaces/add", """{"ok":true,"name":"new"}""")
        lock()
        list()
        tap(str(R.string.pl_market_add))
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf(str(R.string.pl_market_source)))).performTextInput("evil/repo")
        rule.onAllNodes(androidx.compose.ui.test.hasText(str(R.string.pl_market_add))).let { it[it.fetchSemanticsNodes().size - 1].performClick() }
        rule.waitForIdle()
        assertTrue(hub.calls.none { it.first == "POST" })
        val ask = spy.confirms.single()
        assertTrue(ask.body, ask.body.contains("evil/repo"))
        assertTrue(ask.danger)
        ask.onConfirm()
        rule.waitForIdle()
        val b = sent("/v1/agents/plugins/marketplaces/add")
        assertEquals("evil/repo", b.getString("source"))
        assertTrue(b.getBoolean("confirmRun"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aMarketplaceCanBeUpdatedAndRemovedWithoutAFingerprint() {
        hub.on("GET /v1/agents/plugins", PLUGINS)
        hub.on("POST /v1/agents/plugins/marketplaces/update", """{"ok":true}""")
        hub.on("POST /v1/agents/plugins/marketplaces/remove", """{"ok":true}""")
        list()
        tap(str(R.string.pl_market_update_all))
        assertFalse(sent("/v1/agents/plugins/marketplaces/update").has("name"))
        rule.onAllNodes(androidx.compose.ui.test.hasText(str(R.string.pl_market_remove)))[0].performScrollTo().performClick()
        rule.waitForIdle()
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertEquals("testmp", sent("/v1/agents/plugins/marketplaces/remove").getString("name"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aPluginsPageSaysWhatItBringsAndItsOptionsNeverASecret() {
        hub.on("GET /v1/agents/plugins/detail", DETAIL)
        page()
        seen("A test plugin")
        seen("greet")
        seen("helper")
        seen("SessionStart")
        seen("hellomcp")
        seen(str(R.string.pl_tokens, 17))
        seen(str(R.string.pl_option_set))
        seen("eu")
        rule.onRoot().snap("plugin_page")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun offUpdateAndOptionsNeedNoFingerprintAndARefusalIsSaid() {
        hub.on("GET /v1/agents/plugins/detail", DETAIL)
        hub.on("POST /v1/agents/plugins/toggle", """{"ok":true,"message":"Disabled."}""")
        hub.on("POST /v1/agents/plugins/update", """{"ok":true}""")
        hub.fails("POST /v1/agents/plugins/configure", AgentSettingRefused("cli_failed", "It didn't take that."))
        page()
        tap(str(R.string.pl_disable))
        assertFalse(sent("/v1/agents/plugins/toggle").getBoolean("enabled"))
        seen(str(R.string.pl_enable))
        tap(str(R.string.pl_update))
        assertEquals("user", sent("/v1/agents/plugins/update").getString("scope"))
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("api_token"))).performScrollTo().performTextInput("new-secret")
        tap(str(R.string.pl_options_save))
        assertEquals("new-secret", sent("/v1/agents/plugins/configure").getJSONObject("values").getString("api_token"))
        seen("It didn't take that.")
        assertEquals(0, spy.confirms.size)
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun uninstallingConfirmsWithoutAFingerprintAndGoesBack() {
        hub.on("GET /v1/agents/plugins/detail", DETAIL)
        hub.on("POST /v1/agents/plugins/uninstall", """{"ok":true}""")
        var checks = 0
        lock()
        OwnerCheck.prompt = { ok, _ -> checks++; ok() }
        page()
        tap(str(R.string.pl_uninstall))
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertEquals("hello@testmp", sent("/v1/agents/plugins/uninstall").getString("plugin"))
        assertEquals(0, checks)
        assertTrue(opened.contains("back"))
    }

    private fun browse(folder: String? = "home") {
        rule.setContent { Harness(spy) { PluginBrowseScreen(hub, "claude", folder, onBack = { opened += "back" }) } }
        rule.waitForIdle()
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun installingShowsItsWholeSourceThenAsksForTheFingerprint() {
        hub.on("GET /v1/agents/plugins/available", AVAILABLE)
        hub.on("POST /v1/agents/plugins/install", """{"ok":true,"id":"hello@testmp"}""")
        lock()
        browse()
        seen(str(R.string.pl_more, 119))
        rule.onRoot().snap("plugin_browse")
        tap("hello")
        val ask = spy.confirms.single()
        assertTrue(ask.body, ask.body.contains("github · acme/plugins"))
        assertTrue(ask.body, ask.body.contains("./plugins/hello"))
        assertTrue(hub.calls.none { it.first == "POST" })
        ask.onConfirm()
        rule.waitForIdle()
        val b = sent("/v1/agents/plugins/install")
        assertTrue(b.getBoolean("confirmRun"))
        assertEquals("user", b.getString("scope"))
        assertTrue(opened.contains("back"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aCancelledFingerprintInstallsNothing() {
        hub.on("GET /v1/agents/plugins/available", AVAILABLE)
        lock(pass = false)
        browse()
        tap("hello")
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertTrue(hub.calls.none { it.first == "POST" })
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aPluginThatInstallsByACommandShowsThatCommandThenSendsItsHash() {
        hub.on("GET /v1/agents/plugins/available", AVAILABLE)
        hub.on("POST /v1/agents/plugins/install", """{"ok":false,"needsAccept":{"command":"curl x | sh","sha256":"abc"}}""")
        lock()
        browse()
        tap("hello")
        spy.confirms[0].onConfirm()
        rule.waitForIdle()
        assertEquals(2, spy.confirms.size)
        assertTrue(spy.confirms[1].body, spy.confirms[1].body.contains("curl x | sh"))
        spy.confirms[1].onConfirm()
        rule.waitForIdle()
        assertEquals("abc", sent("/v1/agents/plugins/install").getString("acceptCommand"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aSearchGoesToTheServerAndProjectNeedsAFolder() {
        hub.on("GET /v1/agents/plugins/available", AVAILABLE)
        browse("f1")
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf(str(R.string.pl_search)))).performTextInput("hel")
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertTrue(hub.calls.any { it.second.contains("q=hel") })
    }
}
