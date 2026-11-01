package com.prudnikweb.hypernova.ui.screens

import android.app.KeyguardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.ClaudeMcp
import com.prudnikweb.hypernova.data.ClaudeSettingsSummary
import com.prudnikweb.hypernova.data.CodexSettingsSummary
import com.prudnikweb.hypernova.data.EditableSetting
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.McpName
import com.prudnikweb.hypernova.data.PluginSetting
import com.prudnikweb.hypernova.data.SettingChange
import com.prudnikweb.hypernova.data.SettingChoice
import com.prudnikweb.hypernova.data.SettingUndo
import com.prudnikweb.hypernova.data.ShownSetting
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.settings.AgentSettingsScreen
import com.prudnikweb.hypernova.ui.settings.settingWords
import com.prudnikweb.hypernova.ui.settings.valueWord
import com.prudnikweb.hypernova.ui.settings.whenWords
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class SettingsHub(private val base: FakeHub = FakeHub()) : Hub by base {
    var answer: Result<AgentSettingsReply> = Result.failure(HubException("nothing yet"))
    var asked = 0
    /** What a change or an undo answers. */
    var changing: Result<SettingChange> = Result.success(SettingChange(true, false, "hash-after"))
    var undoing: Result<Unit> = Result.success(Unit)
    val changes = mutableListOf<String>()
    val undos = mutableListOf<String>()
    private var version by mutableIntStateOf(0)
    override val agentSettingsVersion get() = version
    fun heard() { version++ }
    override suspend fun agentSettings(): Result<AgentSettingsReply> {
        asked++
        return answer
    }
    override suspend fun changeAgentSetting(agent: String, key: String, value: Any, expected: String?): Result<SettingChange> {
        changes += "$agent $key $value $expected"
        return changing
    }
    override suspend fun undoAgentSetting(agent: String, expected: String): Result<Unit> {
        undos += "$agent $expected"
        return undoing
    }
}

/**
 * Agents & providers › an agent's Settings (ui/settings/AgentSettings.kt): the labelled values, the
 * summaries, the line about where they come from, what each state of the file says, and changing
 * what the server lists as editable: the picker, the confirm and the fingerprint, a refusal, an older server.
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
        hash = "hash-claude",
        editable = listOf(
            EditableSetting(
                "permissions.defaultMode",
                listOf(
                    SettingChoice("plan", null, false, null),
                    SettingChoice("default", null, false, null),
                    SettingChoice("dontAsk", null, false, null),
                    SettingChoice("acceptEdits", null, false, null),
                    SettingChoice("auto", null, true, null),
                ),
            ),
            EditableSetting("showThinkingSummaries", listOf(SettingChoice(null, true, false, null), SettingChoice(null, false, false, null))),
            EditableSetting("autoUpdatesChannel", listOf(SettingChoice("latest", null, false, null), SettingChoice("stable", null, false, null))),
        ),
        mcp = ClaudeMcp(
            AgentSettingsFile.OK,
            listOf(
                McpName("home-assistant", "user", null),
                McpName("github", "user", null),
                McpName("db-tools", "local", "parser"),
                McpName("docs", "project", "parser"),
                McpName("search", "project", "app"),
            ),
            hidden = 2,
            foldersNotRead = 0,
        ),
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
        hash = "hash-codex",
        editable = listOf(
            EditableSetting("model_reasoning_effort", listOf("low", "high").map { SettingChoice(it, null, false, null) }),
            EditableSetting("approval_policy", listOf(SettingChoice("on-request", null, true, null))),
            EditableSetting("sandbox_mode", listOf(SettingChoice("read-only", null, false, null), SettingChoice("workspace-write", null, false, null))),
            EditableSetting("web_search", listOf("disabled", "cached", "indexed", "live").map { SettingChoice(it, null, false, null) }),
        ),
    )
    private fun file(state: String) = AgentSettingsFile(state, emptyList(), null, null)

    @Before fun setUp() = resetPrefs()

    private fun open(agent: String, back: () -> Unit = {}) {
        rule.setContent { Harness(spy) { AgentSettingsScreen(hub, agent, onBack = back) } }
        rule.waitForIdle()
    }

    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().assertExists()

    @Config(qualifiers = "+h1800dp")
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
        // The server's own first, then each project's under its name.
        seen(str(R.string.as_mcp_here))
        seen(str(R.string.as_mcp_claude_body))
        seen("db-tools")
        seen("parser")
        seen("PreToolUse")
        seen(org.robolectric.RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.as_hidden, 3, 3))
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

    @Test fun accountDetailsComeFirstEvenWhenTheFileCant() {
        val hub = object : Hub by FakeHub() {
            override val account get() = com.prudnikweb.hypernova.data.Account(plan = "max")
            override suspend fun agentSettings() = Result.failure<com.prudnikweb.hypernova.data.AgentSettingsReply>(IllegalStateException("nope"))
        }
        rule.setContent { Harness(spy) { AgentSettingsScreen(hub, com.prudnikweb.hypernova.data.AgentSettingsReply.CLAUDE, onBack = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.as_account).uppercase()).assertExists()
        rule.onNodeWithText(str(R.string.ag_claude_signed_in_plan, "Max")).assertExists()
    }

    @Test fun codexAccountSaysItsPlanAndUsage() {
        val hub = object : Hub by FakeHub() {
            override val codex get() = com.prudnikweb.hypernova.data.CodexState(
                available = true, enabled = true, connected = true,
                account = com.prudnikweb.hypernova.data.CodexAccount(signedIn = true, type = "chatgpt", planType = "plus"),
                usage = com.prudnikweb.hypernova.data.CodexUsage(null, com.prudnikweb.hypernova.data.Window(37.0, 0L), false, null),
            )
            override suspend fun agentSettings() = Result.failure<com.prudnikweb.hypernova.data.AgentSettingsReply>(IllegalStateException("nope"))
        }
        rule.setContent { Harness(spy) { AgentSettingsScreen(hub, com.prudnikweb.hypernova.data.AgentSettingsReply.CODEX, onBack = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.as_account_week)).assertExists()
        rule.onNodeWithText("37%").assertExists()
        rule.onNodeWithText(str(R.string.as_account_five)).assertDoesNotExist()
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

    private fun quantity(id: Int, n: Int) = org.robolectric.RuntimeEnvironment.getApplication().resources.getQuantityString(id, n, n)

    @Test fun mcpServersAreListedEvenWithNoSettingsFile() {
        val missing = AgentSettingsFile(AgentSettingsFile.MISSING, emptyList(), null, null, mcp = claude.mcp)
        hub.answer = Result.success(AgentSettingsReply(missing, codex))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.as_missing_claude, hub.mac.name))
        seen(str(R.string.as_mcp_here))
        seen("home-assistant")
        seen("parser")
        seen("docs")
        seen(quantity(R.plurals.as_hidden, 2))
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun anOlderServersMcpNamesStillShowAndNothingSaysServersArentListed() {
        val older = claude.copy(mcp = null)
        hub.answer = Result.success(AgentSettingsReply(older, codex))
        open(AgentSettingsReply.CLAUDE)
        seen("home-assistant")
        rule.onNodeWithText(str(R.string.as_mcp_here)).assertDoesNotExist()
        rule.onNodeWithText("claude mcp add", substring = true).assertDoesNotExist()
    }

    @Test fun longMcpListsAreCappedAndSayHowManyMore() {
        val own = List(ClaudeMcp.NAMES_SHOWN + 3) { McpName("server$it", "user", null) }
        val folders = List(ClaudeMcp.GROUPS_SHOWN + 2) { McpName("tool", "project", "p%02d".format(it)) }
        val m = ClaudeMcp(AgentSettingsFile.OK, own + folders, 0, 4)
        val (groups, more) = m.groups()
        assertEquals(ClaudeMcp.GROUPS_SHOWN + 1, groups.size)
        assertEquals(null, groups[0].project)
        assertEquals(3, groups[0].more)
        assertEquals("p00", groups[1].project)
        assertEquals(2, more)
        hub.answer = Result.success(AgentSettingsReply(AgentSettingsFile(AgentSettingsFile.MISSING, emptyList(), null, null, mcp = m), codex))
        open(AgentSettingsReply.CLAUDE)
        seen(quantity(R.plurals.as_mcp_more, 3))
        seen(quantity(R.plurals.as_mcp_more_projects, 2))
        seen(quantity(R.plurals.as_mcp_not_read, 4))
        rule.onNodeWithText("server${ClaudeMcp.NAMES_SHOWN}", substring = true).assertDoesNotExist()
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun anUnreadableClaudeJsonSaysTheListMayBeShort() {
        val m = ClaudeMcp(AgentSettingsFile.MALFORMED, emptyList(), 0, 0)
        hub.answer = Result.success(AgentSettingsReply(claude.copy(mcp = m), codex))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.as_mcp_trouble))
    }

    @Test fun everyShownKeyHasItsWords() {
        for (k in claude.values.map { it.key } + codex.values.map { it.key }) assertNotNull(k, settingWords(k))
        assertNull(settingWords("env"))
        assertEquals(R.string.mode_title_default, valueWord("permissions.defaultMode", "manual"))
        assertNull(valueWord("model", "gpt-6.1-sol"))
    }

    // ─── Changing them (docs/DECISIONS.md, "The agents' settings, edited") ───

    private fun allow(ok: Boolean = true, secure: Boolean = true) {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(secure)
        OwnerCheck.prompt = { pass, fail ->
            checks++
            if (ok) pass() else fail(null)
        }
    }
    private var checks = 0
    @After fun tearDown() {
        OwnerCheck.prompt = null
    }

    private fun claudeOpen() {
        hub.answer = Result.success(AgentSettingsReply(claude, file(AgentSettingsFile.MISSING)))
        open(AgentSettingsReply.CLAUDE)
    }
    private fun row(label: Int) = rule.onNodeWithText(str(label), substring = true).performScrollTo().performClick().also { rule.waitForIdle() }
    /** A choice in the open picker: its row, not a line that happens to say the same. */
    private fun choice(label: Int) = rule.onNode(hasText(str(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

    @Config(qualifiers = "+h1500dp")
    @Test fun anEditableRowOpensItsPickerWithTheCurrentValueTickedAndWhyEachIsThere() {
        claudeOpen()
        row(R.string.as_k_mode)
        choice(R.string.mode_title_accept).assertIsSelected()
        choice(R.string.mode_title_plan).assertIsNotSelected()
        // A line on what each does, and when it applies.
        rule.onNodeWithText(str(R.string.as_l_auto), substring = true).assertExists()
        rule.onAllNodes(hasText(str(R.string.as_when_new_sessions))).fetchSemanticsNodes().let { assert(it.isNotEmpty()) }
        // What the server didn't offer isn't there.
        rule.onNode(hasText(str(R.string.mode_title_bypass))).assertDoesNotExist()
        rule.onAllNodes(isRoot()).onLast().snap("agent_settings_picker")
        // Tapping the one it already is only closes it.
        choice(R.string.mode_title_accept).performClick()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), hub.changes)
        choice(R.string.mode_title_plan).assertDoesNotExist()
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aViewOnlyRowHasNothingToOpen() {
        claudeOpen()
        rule.onNodeWithText(str(R.string.as_k_effort)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertDoesNotExist()
        assertEquals(emptyList<String>(), hub.changes)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun tighteningAsksNothingSavesAgainstTheHashReadAndReadsAgain() {
        allow()
        claudeOpen()
        assertEquals(1, hub.asked)
        row(R.string.as_k_mode)
        choice(R.string.mode_title_plan).performClick()
        rule.waitForIdle()
        assertEquals(listOf("claude permissions.defaultMode plan hash-claude"), hub.changes)
        assertEquals(0, spy.confirms.size)
        assertEquals(0, checks)
        assertEquals(listOf(str(R.string.as_saved, hub.mac.name)), spy.toasts)
        assertEquals(2, hub.asked)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aBooleanGoesAsABoolean() {
        claudeOpen()
        row(R.string.as_k_thinking)
        choice(R.string.as_off).performClick()
        rule.waitForIdle()
        assertEquals(listOf("claude showThinkingSummaries false hash-claude"), hub.changes)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun looseningAsksSayingWhatItAllowsThenTheFingerprintAndOnlyThenSaves() {
        allow()
        claudeOpen()
        row(R.string.as_k_mode)
        choice(R.string.mode_title_auto).performClick()
        rule.waitForIdle()
        // Nothing yet: the confirm says what it allows.
        assertEquals(emptyList<String>(), hub.changes)
        val ask = spy.confirms.single()
        assertEquals(str(R.string.as_loosen_title, hub.mac.name), ask.title)
        assertEquals(str(R.string.as_loosen_body, str(R.string.mode_title_auto), str(R.string.as_l_auto)), ask.body)
        assertEquals(0, checks)
        ask.onConfirm()
        rule.waitForIdle()
        assertEquals(1, checks)
        assertEquals(listOf("claude permissions.defaultMode auto hash-claude"), hub.changes)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aCancelledFingerprintSavesNothing() {
        allow(ok = false)
        claudeOpen()
        row(R.string.as_k_mode)
        choice(R.string.mode_title_auto).performClick()
        rule.waitForIdle()
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertEquals(1, checks)
        assertEquals(emptyList<String>(), hub.changes)
        assertEquals(emptyList<String>(), spy.toasts)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aPhoneWithNoLockCantLoosenAndIsToldWhy() {
        allow(secure = false)
        claudeOpen()
        row(R.string.as_k_mode)
        choice(R.string.mode_title_auto).performClick()
        rule.waitForIdle()
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), hub.changes)
        assertEquals(listOf(str(R.string.check_no_lock)), spy.toasts)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aPermissiveValueInTheFileShowsAndCanOnlyBeMovedOff() {
        val bypass = claude.copy(
            values = claude.values.map { if (it.key == "permissions.defaultMode") set(it.key, "bypassPermissions") else it },
            editable = listOf(EditableSetting("permissions.defaultMode", listOf(SettingChoice("plan", null, false, null), SettingChoice("auto", null, false, null)))),
        )
        hub.answer = Result.success(AgentSettingsReply(bypass, file(AgentSettingsFile.MISSING)))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.mode_title_bypass))
        row(R.string.as_k_mode)
        rule.onNodeWithText(str(R.string.as_now_off_list, str(R.string.mode_title_bypass))).assertExists()
        // Moving off it is tightening, so nothing asks.
        choice(R.string.mode_title_auto).performClick()
        rule.waitForIdle()
        assertEquals(0, spy.confirms.size)
        assertEquals(listOf("claude permissions.defaultMode auto hash-claude"), hub.changes)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aFileThatChangedSaysSoInTheServersWordsAndReadsAgain() {
        val words = "That file changed on the server since this was read. Nothing was written."
        hub.changing = Result.failure(AgentSettingRefused(AgentSettingRefused.FILE_CHANGED, words))
        claudeOpen()
        row(R.string.as_k_mode)
        choice(R.string.mode_title_plan).performClick()
        rule.waitForIdle()
        seen(words)
        assertEquals(2, hub.asked)
        assertEquals(emptyList<String>(), spy.toasts)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun anOlderServerKeepsItsRowsViewOnlyAndSaysItNeedsUpdating() {
        hub.changing = Result.failure(AgentSettingsTooOld(str(R.string.as_edit_too_old)))
        claudeOpen()
        row(R.string.as_k_mode)
        choice(R.string.mode_title_plan).performClick()
        rule.waitForIdle()
        seen(str(R.string.as_edit_too_old))
        seen(str(R.string.as_intro_claude_view, hub.mac.name))
        // The row no longer opens anything.
        rule.onNodeWithText(str(R.string.as_k_mode), substring = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertDoesNotExist()
        assertEquals(1, hub.changes.size)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aServerWithNothingEditableSaysItCantBeChangedAndNoRowOpens() {
        hub.answer = Result.success(AgentSettingsReply(claude.copy(hash = null, editable = emptyList()), file(AgentSettingsFile.MISSING)))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.as_intro_claude_view, hub.mac.name))
        rule.onNodeWithText(str(R.string.as_k_mode), substring = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertDoesNotExist()
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aLinkedFileSaysWhyItCantBeChanged() {
        hub.answer = Result.success(AgentSettingsReply(claude.copy(editable = emptyList(), whyNot = AgentSettingsFile.LINK), file(AgentSettingsFile.MISSING)))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.as_why_link, hub.mac.name))
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun undoIsARowThatPutsTheLastChangeBack() {
        val after = claude.copy(undo = SettingUndo("permissions.defaultMode", "plan", null, false))
        hub.answer = Result.success(AgentSettingsReply(after, file(AgentSettingsFile.MISSING)))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.as_undo_to, str(R.string.as_k_mode), str(R.string.mode_title_plan)))
        rule.onNodeWithText(str(R.string.as_undo_row)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("claude hash-claude"), hub.undos)
        assertEquals(0, spy.confirms.size)
        assertEquals(listOf(str(R.string.as_undone, hub.mac.name)), spy.toasts)
        assertEquals(2, hub.asked)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun anUndoThatLoosensAsksLikeAnyOtherAndItsFailureIsTheServersWords() {
        allow()
        val after = claude.copy(undo = SettingUndo("permissions.defaultMode", "auto", null, true))
        hub.answer = Result.success(AgentSettingsReply(after, file(AgentSettingsFile.MISSING)))
        hub.undoing = Result.failure(AgentSettingRefused(AgentSettingRefused.FILE_CHANGED, "That file changed."))
        open(AgentSettingsReply.CLAUDE)
        rule.onNodeWithText(str(R.string.as_undo_row)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), hub.undos)
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertEquals(1, checks)
        assertEquals(listOf("claude hash-claude"), hub.undos)
        seen("That file changed.")
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aFileNotThereYetOffersWhatCanBeSetByMakingIt() {
        val missing = AgentSettingsFile(AgentSettingsFile.MISSING, emptyList(), null, null, editable = claude.editable)
        hub.answer = Result.success(AgentSettingsReply(missing, file(AgentSettingsFile.MISSING)))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.as_missing_claude, hub.mac.name))
        row(R.string.as_k_channel)
        choice(R.string.as_v_stable).performClick()
        rule.waitForIdle()
        assertEquals(listOf("claude autoUpdatesChannel stable null"), hub.changes)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun aMissingFileKeepsItsStateItsMcpServersAndWhatCanBeSet() {
        val missing = AgentSettingsFile(AgentSettingsFile.MISSING, emptyList(), null, null, editable = claude.editable, mcp = claude.mcp)
        hub.answer = Result.success(AgentSettingsReply(missing, file(AgentSettingsFile.MISSING)))
        open(AgentSettingsReply.CLAUDE)
        seen(str(R.string.as_missing_claude, hub.mac.name))
        seen("home-assistant")
        seen(str(R.string.as_k_mode))
        rule.onNodeWithText(str(R.string.as_summary)).assertDoesNotExist()
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun codexSaysItAppliesToNewThreadsAndModelStaysViewOnlyWithoutAList() {
        hub.answer = Result.success(AgentSettingsReply(file(AgentSettingsFile.MISSING), codex))
        open(AgentSettingsReply.CODEX)
        rule.onNodeWithText(str(R.string.as_k_model), substring = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertDoesNotExist()
        row(R.string.as_k_sandbox)
        choice(R.string.as_v_workspace_write).assertIsSelected()
        rule.onAllNodes(hasText(str(R.string.as_when_new_threads_unchosen))).fetchSemanticsNodes().let { assert(it.isNotEmpty()) }
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun anotherPhonesChangeIsReadAgainWithNoLoadingFlash() {
        claudeOpen()
        assertEquals(1, hub.asked)
        hub.answer = Result.success(AgentSettingsReply(claude.copy(values = claude.values.map { if (it.key == "effortLevel") set(it.key, "low") else it }), file(AgentSettingsFile.MISSING)))
        hub.heard()
        rule.waitForIdle()
        assertEquals(2, hub.asked)
        seen(str(R.string.as_v_low))
    }

    @Test fun whenEachKeyAppliesIsSaidForEveryEditableKeyAndNoOther() {
        assertEquals(R.string.as_when_new_sessions, whenWords("permissions.defaultMode"))
        assertEquals(R.string.as_when_maybe_new_session, whenWords("showThinkingSummaries"))
        assertEquals(R.string.as_when_next_update, whenWords("autoUpdatesChannel"))
        for (k in listOf("model", "model_reasoning_effort", "web_search")) assertEquals(k, R.string.as_when_new_threads, whenWords(k))
        // A thread from this app names its own sandbox and approvals, so the file reaches only those that do not.
        for (k in listOf("approval_policy", "sandbox_mode")) assertEquals(k, R.string.as_when_new_threads_unchosen, whenWords(k))
        for (k in listOf("effortLevel", "includeCoAuthoredBy", "autoDreamEnabled", "inputNeededNotifEnabled", "agentPushNotifEnabled", "personality")) assertNull(k, whenWords(k))
    }
}
