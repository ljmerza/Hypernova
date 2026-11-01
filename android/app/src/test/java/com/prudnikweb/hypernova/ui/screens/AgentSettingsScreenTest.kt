package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.ClaudeSettingsSummary
import com.prudnikweb.hypernova.data.CodexSettingsSummary
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.PluginSetting
import com.prudnikweb.hypernova.data.ShownSetting
import com.prudnikweb.hypernova.ui.settings.AgentSettingsScreen
import com.prudnikweb.hypernova.ui.settings.settingWords
import com.prudnikweb.hypernova.ui.settings.valueWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class SettingsHub(private val base: FakeHub = FakeHub()) : Hub by base {
    var answer: Result<AgentSettingsReply> = Result.failure(HubException("nothing yet"))
    var asked = 0
    override suspend fun agentSettings(): Result<AgentSettingsReply> {
        asked++
        return answer
    }
}

/**
 * Agents & providers › an agent's Settings (ui/settings/AgentSettings.kt): the labelled values, the
 * summaries, the line about where they come from, and what each state of the file says. Nothing on
 * it changes anything.
 */
@RunWith(RobolectricTestRunner::class)
class AgentSettingsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = SettingsHub()

    private fun set(key: String, text: String) = ShownSetting(key, ShownSetting.SET, text, null)
    private fun set(key: String, on: Boolean) = ShownSetting(key, ShownSetting.SET, null, on)
    private fun unset(key: String) = ShownSetting(key, ShownSetting.UNSET, null, null)
    private fun odd(key: String) = ShownSetting(key, ShownSetting.UNRECOGNISED, null, null)

    private val claude = AgentSettingsFile(
        AgentSettingsFile.OK,
        listOf(
            set("effortLevel", "xhigh"),
            set("permissions.defaultMode", "acceptEdits"),
            set("showThinkingSummaries", true),
            unset("autoDreamEnabled"),
            set("includeCoAuthoredBy", false),
            odd("autoUpdatesChannel"),
            unset("inputNeededNotifEnabled"),
            unset("agentPushNotifEnabled"),
        ),
        ClaudeSettingsSummary(
            allow = 12, ask = 1, deny = 3,
            plugins = listOf(PluginSetting("formatter", true), PluginSetting("linter", false)),
            mcpServers = listOf("home-assistant"),
            hookEvents = listOf("Stop", "PreToolUse"),
            hidden = 1,
        ),
        null,
    )
    private val codex = AgentSettingsFile(
        AgentSettingsFile.OK,
        listOf(
            set("model", "gpt-6.1-sol"),
            set("model_reasoning_effort", "high"),
            odd("approval_policy"),
            set("sandbox_mode", "workspace-write"),
            unset("web_search"),
            set("personality", "none"),
        ),
        null,
        CodexSettingsSummary(projects = 7, mcpServers = emptyList(), hidden = 0),
    )
    private fun file(state: String) = AgentSettingsFile(state, emptyList(), null, null)

    @Before fun setUp() = resetPrefs()

    private fun open(agent: String, back: () -> Unit = {}) {
        rule.setContent { Harness(spy) { AgentSettingsScreen(hub, agent, onBack = back) } }
        rule.waitForIdle()
    }

    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().assertExists()

    @Config(qualifiers = "+h1500dp")
    @Test fun claudeCodesValuesInWordsThenItsSummaries() {
        hub.answer = Result.success(AgentSettingsReply(claude, file(AgentSettingsFile.MISSING)))
        open(AgentSettingsReply.CLAUDE)
        assertEquals(1, hub.asked)
        rule.onNodeWithText(str(R.string.as_title_claude)).assertExists()
        // Where they come from, that they can't be changed here, and that a project can override them.
        seen(str(R.string.as_intro_claude, hub.mac.name))
        seen(str(R.string.as_k_effort))
        seen(str(R.string.as_v_xhigh))
        seen(str(R.string.mode_title_accept))
        seen(str(R.string.as_k_input))
        // Not set and unrecognised are each said.
        rule.onAllNodes(androidx.compose.ui.test.hasText(str(R.string.as_not_set), substring = true)).fetchSemanticsNodes().let { assertEquals(3, it.size) }
        seen(str(R.string.as_unrecognised))
        seen(str(R.string.as_rules_value, "12", "1", "3"))
        seen("formatter: ${str(R.string.as_on)}")
        seen("linter: ${str(R.string.as_off)}")
        seen("home-assistant")
        seen("PreToolUse")
        seen(org.robolectric.RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.as_hidden, 1, 1))
        rule.snap("agent_settings_claude")
    }

    @Config(qualifiers = "+h1100dp")
    @Test fun codexValuesAndSummaries() {
        hub.answer = Result.success(AgentSettingsReply(file(AgentSettingsFile.MISSING), codex))
        open(AgentSettingsReply.CODEX)
        rule.onNodeWithText(str(R.string.as_title_codex)).assertExists()
        seen(str(R.string.as_intro_codex, hub.mac.name))
        // A model's id as it is.
        seen("gpt-6.1-sol")
        seen(str(R.string.as_v_workspace_write))
        seen(str(R.string.as_projects))
        seen("7")
        rule.snap("agent_settings_codex")
    }

    @Test fun eachStateOfTheFileIsSaidForItsAgent() {
        hub.answer = Result.success(AgentSettingsReply(file(AgentSettingsFile.MALFORMED), file(AgentSettingsFile.UNREADABLE)))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.as_malformed_claude, hub.mac.name))
        rule.onNodeWithText(str(R.string.as_values)).assertDoesNotExist()
    }

    @Test fun codexUnreadable() {
        hub.answer = Result.success(AgentSettingsReply(file(AgentSettingsFile.OK), file(AgentSettingsFile.UNREADABLE)))
        open(AgentSettingsReply.CODEX)
        seen(str(R.string.as_unreadable_codex, hub.mac.name))
    }

    @Test fun aMissingFileIsTheDefaultsAndNoTrouble() {
        hub.answer = Result.success(AgentSettingsReply(file(AgentSettingsFile.MISSING), file(AgentSettingsFile.MISSING)))
        open(AgentSettingsReply.CODEX)
        seen(str(R.string.as_missing_codex, hub.mac.name))
    }

    @Test fun anOlderServerSaysItNeedsUpdatingWithNoTryAgain() {
        hub.answer = Result.failure(AgentSettingsTooOld(str(R.string.as_too_old)))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.as_too_old))
        rule.onNodeWithText(str(R.string.try_again)).assertDoesNotExist()
    }

    @Test fun anotherFailureCanBeTriedAgain() {
        hub.answer = Result.failure(HubException("No network."))
        open(AgentSettingsReply.CLAUDE)
        seen("No network.")
        hub.answer = Result.success(AgentSettingsReply(claude, codex))
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        rule.waitForIdle()
        assertEquals(2, hub.asked)
        seen(str(R.string.as_k_effort))
    }

    @Test fun aKeyWithoutWordsKeepsItsRawNameAndAValueItsOwnText() {
        val newer = AgentSettingsFile(AgentSettingsFile.OK, listOf(set("someNewKey", "brand-new"), set("effortLevel", "brand-new-level")), claude.claude, null)
        hub.answer = Result.success(AgentSettingsReply(newer, codex))
        open(AgentSettingsReply.CLAUDE)
        seen("someNewKey")
        rule.onNodeWithText("brand-new").performScrollTo().assertExists()
        seen("brand-new-level")
    }

    @Test fun everyShownKeyHasItsWords() {
        for (k in claude.values.map { it.key } + codex.values.map { it.key }) assertNotNull(k, settingWords(k))
        assertNull(settingWords("env"))
        assertEquals(R.string.mode_title_default, valueWord("permissions.defaultMode", "manual"))
        assertNull(valueWord("model", "gpt-6.1-sol"))
    }
}
