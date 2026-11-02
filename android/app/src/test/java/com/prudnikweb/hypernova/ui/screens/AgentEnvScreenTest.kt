package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.settings.AgentEnvScreen
import org.json.JSONObject
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

private const val ENV = """{"agent":"claude","folder":"home","folders":[{"id":"home","name":"~"},{"id":"f1","name":"parser"}],"hidden":1,"undo":null,
 "files":[{"scope":"user","where":"~/.claude/settings.json","state":"ok","hash":"h-user"},{"scope":"local","where":"~/.claude/settings.local.json","state":"missing","hash":null},{"scope":"project","where":"parser/.claude/settings.json","state":"link","hash":null}],
 "vars":[{"name":"DISABLE_TELEMETRY","scope":"user","masked":false,"value":"1","flag":true,"effective":true,"guarded":false,"refused":false},
 {"name":"BASH_MAX_OUTPUT_LENGTH","scope":"user","masked":false,"value":"30000","effective":false,"overriddenBy":"local","guarded":false,"refused":false},
 {"name":"BASH_MAX_OUTPUT_LENGTH","scope":"local","masked":false,"value":"40000","effective":true,"guarded":false,"refused":false},
 {"name":"API_TOKEN","scope":"user","masked":true,"effective":true,"guarded":false,"refused":false},
 {"name":"PATH","scope":"user","masked":false,"value":"/opt/bin","effective":true,"guarded":true,"refused":false},
 {"name":"IS_SANDBOX","scope":"user","masked":false,"value":"1","flag":true,"effective":true,"guarded":false,"refused":true}]}"""

/** Agents & providers › an agent's settings › Environment variables (ui/settings/EnvScreens.kt). */
@RunWith(RobolectricTestRunner::class)
class AgentEnvScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = ExtHub()
    private var checks = 0

    @Before fun setUp() = resetPrefs()
    @After fun tearDown() { OwnerCheck.prompt = null }

    private fun lock(pass: Boolean = true) {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(true)
        OwnerCheck.prompt = { ok, fail -> checks++; if (pass) ok() else fail(null) }
    }
    private fun open(agent: String = "claude") {
        rule.setContent { Harness(spy) { AgentEnvScreen(hub, agent, onBack = {}) } }
        rule.waitForIdle()
    }
    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().assertExists()
    private fun field(label: String) = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)))
    private fun posts(path: String) = hub.calls.filter { it.first == "POST" && it.second == path }.map { it.third!! }
    private fun ok() = Result.success(JSONObject("""{"ok":true}"""))

    @Config(qualifiers = "+h1800dp")
    @Test fun eachRowSaysItsNameValueAndWhereItsFromAndASecretIsNeverShown() {
        hub.on("GET /v1/agents/env", ENV)
        open()
        seen(str(R.string.env_when_claude))
        seen("DISABLE_TELEMETRY")
        seen("30000")
        seen(str(R.string.env_overridden, str(R.string.mcp_scope_local)))
        seen("40000")
        seen(str(R.string.env_hidden_value))
        seen(str(R.string.env_guarded))
        seen(str(R.string.env_refused))
        seen(str(R.string.env_file_trouble, "parser/.claude/settings.json"))
        rule.onRoot().snap("agent_env")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun theSwitchFlipsWithoutSayingAValueAndSendsTheFilesHash() {
        hub.on("GET /v1/agents/env", ENV)
        hub.on("POST /v1/agents/env", """{"ok":true}""")
        open()
        rule.onNode(hasContentDescription(str(R.string.env_switch, "DISABLE_TELEMETRY"))).performScrollTo().performClick()
        rule.waitForIdle()
        val b = posts("/v1/agents/env").single()
        assertTrue(b.getBoolean("flip"))
        assertFalse(b.has("value"))
        assertEquals("h-user", b.getString("expected"))
        assertEquals("user", b.getString("scope"))
        assertEquals(0, spy.confirms.size)
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aRefusedOneHasNoSwitchAndNoEditOnlyDelete() {
        hub.on("GET /v1/agents/env", ENV)
        hub.on("POST /v1/agents/env/remove", """{"ok":true}""")
        open()
        rule.onNode(hasContentDescription(str(R.string.env_switch, "IS_SANDBOX"))).assertDoesNotExist()
        rule.onNodeWithText("IS_SANDBOX").performScrollTo().performClick()
        rule.waitForIdle()
        field(str(R.string.env_new_value)).assertDoesNotExist()
        rule.onNode(hasContentDescription(str(R.string.env_delete, "IS_SANDBOX"))).performScrollTo().performClick()
        rule.waitForIdle()
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertEquals("IS_SANDBOX", posts("/v1/agents/env/remove").single().getString("name"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun tappingAValueEditsItAndAMaskedOneStartsEmptyAndHidesWhatsTyped() {
        hub.on("GET /v1/agents/env", ENV)
        hub.on("POST /v1/agents/env", """{"ok":true}""")
        open()
        rule.onNodeWithText("API_TOKEN").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.env_new_value_body)).assertExists()
        rule.onNodeWithText(str(R.string.env_save)).performClick()
        rule.waitForIdle()
        assertTrue("an empty secret isn't sent", posts("/v1/agents/env").isEmpty())
        field(str(R.string.env_new_value)).performTextInput("the-new-secret")
        rule.onNodeWithText(str(R.string.env_save)).performClick()
        rule.waitForIdle()
        assertEquals("the-new-secret", posts("/v1/agents/env").single().getString("value"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun addingWithNoValueMeansOnAndGoesToTheChosenScope() {
        hub.on("GET /v1/agents/env", ENV)
        hub.on("POST /v1/agents/env", """{"ok":true}""")
        open()
        seen(str(R.string.env_add_hint))
        field(str(R.string.env_add_name)).performScrollTo().performTextInput("MY_FLAG")
        rule.onNodeWithText(str(R.string.env_add)).performScrollTo().performClick()
        rule.waitForIdle()
        val b = posts("/v1/agents/env").single()
        assertEquals("MY_FLAG", b.getString("name"))
        assertEquals("1", b.getString("value"))
        assertEquals("user", b.getString("scope"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun whenTheServerWantsTheCheckItsWordsGoOnTheConfirmThenTheFingerprintThenItSaysSo() {
        hub.on("GET /v1/agents/env", ENV)
        hub.handler = { m, path, body ->
            if (m == "POST" && path == "/v1/agents/env") {
                if (body!!.optBoolean("confirmRun")) ok() else Result.failure(AgentSettingRefused("confirm_run", "LD_PRELOAD: (not set) → /tmp/x.so changes what runs or where data goes."))
            } else null
        }
        lock()
        open()
        field(str(R.string.env_add_name)).performScrollTo().performTextInput("LD_PRELOAD")
        field(str(R.string.env_add_value)).performScrollTo().performTextInput("/tmp/x.so")
        rule.onNodeWithText(str(R.string.env_add)).performScrollTo().performClick()
        rule.waitForIdle()
        val ask = spy.confirms.single()
        assertTrue(ask.body, ask.body.contains("LD_PRELOAD: (not set) → /tmp/x.so"))
        assertTrue(ask.danger)
        assertEquals(1, posts("/v1/agents/env").size)
        ask.onConfirm()
        rule.waitForIdle()
        assertEquals(1, checks)
        assertTrue(posts("/v1/agents/env").last().getBoolean("confirmRun"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aCancelledFingerprintChangesNothing() {
        hub.on("GET /v1/agents/env", ENV)
        hub.handler = { m, path, _ -> if (m == "POST" && path == "/v1/agents/env/remove") Result.failure(AgentSettingRefused("confirm_run", "Taking PATH away changes what runs.")) else null }
        lock(pass = false)
        open()
        rule.onNode(hasContentDescription(str(R.string.env_delete, "PATH"))).performScrollTo().performClick()
        rule.waitForIdle()
        spy.confirms[0].onConfirm()
        rule.waitForIdle()
        spy.confirms[1].onConfirm()
        rule.waitForIdle()
        assertEquals(1, posts("/v1/agents/env/remove").size)
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aRefusalIsSaidAndTheListIsReadAgain() {
        hub.on("GET /v1/agents/env", ENV)
        hub.fails("POST /v1/agents/env", AgentSettingRefused("file_changed", "That file changed on the server since this was read."))
        open()
        val reads = hub.calls.count { it.first == "GET" }
        rule.onNode(hasContentDescription(str(R.string.env_switch, "DISABLE_TELEMETRY"))).performScrollTo().performClick()
        rule.waitForIdle()
        seen("That file changed on the server")
        assertTrue(hub.calls.count { it.first == "GET" } > reads)
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun undoSaysWhatItWouldDoAndAsksWhereItMust() {
        hub.on("GET /v1/agents/env", ENV.replace("\"undo\":null", "\"undo\":{\"name\":\"PATH\",\"scope\":\"user\",\"folder\":null,\"needsCheck\":true,\"change\":\"PATH: /opt/bin → (not set)\"}"))
        hub.handler = { m, path, body -> if (m == "POST" && path == "/v1/agents/env/undo") { if (body!!.optBoolean("confirmRun")) ok() else Result.failure(AgentSettingRefused("confirm_run", "Putting this back changes what runs.")) } else null }
        lock()
        open()
        seen("PATH: /opt/bin → (not set)")
        rule.onNodeWithText("PATH: /opt/bin → (not set)").performClick()
        rule.waitForIdle()
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertTrue(posts("/v1/agents/env/undo").last().getBoolean("confirmRun"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aFolderIsAskedForAndCodexHasNoFolderOrScopes() {
        hub.on("GET /v1/agents/env", ENV)
        open()
        rule.onNodeWithText(str(R.string.mcp_folder)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("parser").performClick()
        rule.waitForIdle()
        assertTrue(hub.calls.any { it.second.contains("folder=f1") })
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun codexSaysWhatItsTableReachesAndHasNoFolderOrScopes() {
        hub.on("GET /v1/agents/env", """{"agent":"codex","folder":"home","folders":[{"id":"home","name":"~"}],"hidden":0,"undo":null,"files":[{"scope":"user","where":"~/.codex/config.toml","state":"ok","hash":"sha256:a"}],"vars":[{"name":"HN_FLAG","scope":"user","masked":false,"value":"true","flag":true,"effective":true,"guarded":false,"refused":false}]}""")
        hub.on("POST /v1/agents/env", """{"ok":true}""")
        open("codex")
        seen(str(R.string.env_when_codex))
        rule.onNodeWithText(str(R.string.mcp_folder)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.mcp_scope_user)).assertDoesNotExist()
        rule.onNode(hasContentDescription(str(R.string.env_switch, "HN_FLAG"))).performClick()
        rule.waitForIdle()
        assertEquals("sha256:a", posts("/v1/agents/env").single().getString("expected"))
    }
}
