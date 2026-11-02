package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.ui.settings.SkillsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SKILLS = """{"agent":"claude","folder":"home","states":["on","name-only","user-invocable-only","off"],"folders":[{"id":"home","name":"~"},{"id":"f1","name":"parser"}],
 "skills":[{"name":"mine","description":"One of mine.","source":"user","state":"on","tokens":5},{"name":"own","description":"Of this folder.","source":"project","state":"off","tokens":8},{"name":"hello:greet","displayName":"greet","description":"Says hello.","source":"plugin","state":"on","tokens":4,"lockedBy":"plugin"}]}"""
private const val CODEX_SKILLS = """{"agent":"codex","folder":"home","states":["on","off"],"folders":[{"id":"home","name":"~"}],"skills":[{"name":"imagegen","displayName":"Image Gen","description":"Makes images.","source":"built-in","state":"on"}]}"""

/** Agents & providers › an agent's settings › Skills (ui/settings/SkillScreens.kt). */
@RunWith(RobolectricTestRunner::class)
class SkillScreensTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = ExtHub()

    @Before fun setUp() = resetPrefs()

    private fun open(agent: String = "claude") {
        rule.setContent { Harness(spy) { SkillsScreen(hub, agent, onBack = {}) } }
        rule.waitForIdle()
    }
    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().assertExists()
    private fun tap(text: String) { rule.onNodeWithText(text).performScrollTo().performClick(); rule.waitForIdle() }
    private fun sent() = hub.calls.last { it.first == "POST" }.third!!

    @Config(qualifiers = "+h1800dp")
    @Test fun eachSkillSaysWhereItsFromItsDescriptionAndHowItsListed() {
        hub.on("GET /v1/agents/skills", SKILLS)
        open()
        seen("mine")
        seen("One of mine.")
        seen("Of this folder.")
        seen("greet")
        seen(str(R.string.sk_off))
        rule.onRoot().snap("skills_list")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aSkillOpensToItsFourStatesEachSaidAndPickingOneSendsIt() {
        hub.on("GET /v1/agents/skills", SKILLS)
        hub.on("POST /v1/agents/skills", """{"ok":true}""")
        open()
        tap("mine")
        rule.onNodeWithText(str(R.string.sk_name_only_body)).assertExists()
        rule.onNodeWithText(str(R.string.sk_user_only_body)).assertExists()
        rule.onNodeWithText(str(R.string.sk_off_body)).assertExists()
        rule.onNodeWithText(str(R.string.sk_tokens, 5)).assertExists()
        rule.onAllNodes(isRoot()).onLast().snap("skill_sheet")
        rule.onNodeWithText(str(R.string.sk_user_only)).performClick()
        rule.waitForIdle()
        val b = sent()
        assertEquals("mine", b.getString("name"))
        assertEquals("user-invocable-only", b.getString("state"))
        assertEquals("home", b.getString("folder"))
        assertEquals(0, spy.confirms.size)
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aLockedSkillSaysWhoHoldsItAndHasNoStates() {
        hub.on("GET /v1/agents/skills", SKILLS)
        open()
        tap("greet")
        rule.onNodeWithText(str(R.string.sk_lock_plugin)).assertExists()
        rule.onNodeWithText(str(R.string.sk_off_body)).assertDoesNotExist()
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aRefusalIsSaidAndAChosenFolderIsAsked() {
        hub.on("GET /v1/agents/skills", SKILLS)
        hub.fails("POST /v1/agents/skills", AgentSettingRefused("cli_failed", "Claude Code didn't change that skill."))
        open()
        tap("mine")
        rule.onNodeWithText(str(R.string.sk_off_body)).performClick()
        rule.waitForIdle()
        seen("Claude Code didn't change that skill.")
        tap(str(R.string.mcp_folder))
        rule.onNodeWithText("parser").performClick()
        rule.waitForIdle()
        assertTrue(hub.calls.any { it.second.contains("/skills") && it.second.contains("folder=f1") })
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun codexHasOnAndOffAndNoFolder() {
        hub.on("GET /v1/agents/skills", CODEX_SKILLS)
        hub.on("POST /v1/agents/skills", """{"ok":true}""")
        open("codex")
        rule.onNodeWithText(str(R.string.mcp_folder)).assertDoesNotExist()
        seen("Image Gen")
        tap("Image Gen")
        rule.onNodeWithText(str(R.string.sk_name_only)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.sk_off)).performClick()
        rule.waitForIdle()
        assertEquals("off", sent().getString("state"))
        assertFalse(sent().has("folder"))
    }
}
