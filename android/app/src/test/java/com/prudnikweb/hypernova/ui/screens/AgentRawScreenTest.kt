package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.settings.AgentRawScreen
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TEXT = "{\n  \"theme\": \"dark\",\n  \"hooks\": {\n    \"Stop\": [\n      1,\n      2\n    ],\n    \"Start\": []\n  },\n  \"env\": {\n    \"API_KEY\": \"<hidden on the server>\"\n  }\n}\n"
private fun reply(text: String, hash: String = "h1", more: String = "") = JSONObject("""{"agent":"claude","scope":"user","folder":"home","folders":[{"id":"home","name":"~"},{"id":"f1","name":"parser"}],"where":"~/.claude/settings.json","placeholder":"<hidden on the server>","state":"ok","hash":"$hash","hidden":1,"undo":null$more}""").put("text", text).toString()

/** Agents & providers › Claude Code's Settings › JSON (ui/settings/RawScreens.kt). */
@RunWith(RobolectricTestRunner::class)
class AgentRawScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = ExtHub()
    private var checks = 0

    @Before fun setUp() = resetPrefs()
    @After fun tearDown() { OwnerCheck.prompt = null }

    private fun open(text: String = TEXT) {
        hub.on("GET /v1/agents/raw", reply(text))
        rule.setContent { Harness(spy) { AgentRawScreen(hub, "claude", onBack = {}) } }
        rule.waitForIdle()
    }
    private fun lock(pass: Boolean = true) {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(true)
        OwnerCheck.prompt = { ok, fail -> checks++; if (pass) ok() else fail(null) }
    }
    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().assertExists()
    private fun tap(text: String) { rule.onNodeWithText(text).performScrollTo().performClick(); rule.waitForIdle() }
    private fun fold(path: String) = rule.onNode(hasContentDescription(str(R.string.raw_fold, path)))
    private fun unfold(path: String) = rule.onNode(hasContentDescription(str(R.string.raw_unfold, path)))
    private fun line(n: Int) = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("line $n")))
    private fun saved() = hub.calls.last { it.first == "POST" && it.second == "/v1/agents/raw" }.third!!
    private fun saves() = hub.calls.count { it.first == "POST" && it.second == "/v1/agents/raw" }
    private fun ok() = Result.success(JSONObject("""{"ok":true,"changed":true,"hash":"h2"}"""))

    @Config(qualifiers = "+h1400dp")
    @Test fun itOpensOpenWhenShortWithItsWhereAndWhatsHidden() {
        open()
        seen("~/.claude/settings.json")
        seen("API_KEY")
        seen("theme")
        fold("hooks").assertExists()
        fold("env").assertExists()
        rule.onRoot().snap("raw_json")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun aLongFileOpensWithTheTopLevelShowingAndDeeperFoldedSayingHowMany() {
        val long = "{\n" + (1..30).joinToString(",\n") { "  \"k$it\": {\n    \"inside$it\": 1,\n    \"b\": 2\n  }" } + ",\n  \"list\": [\n    1,\n    2,\n    3\n  ]\n}\n"
        open(long)
        unfold("k1").assertExists()
        unfold("list").assertExists()
        rule.onNodeWithText("inside1", substring = true).assertDoesNotExist()
        rule.onAllNodes(androidx.compose.ui.test.hasText("2 keys },", substring = true))[0].assertExists()
        seen("3 items ]")
        rule.onRoot().snap("raw_json_folded")
        unfold("k1").performScrollTo().performClick()
        rule.waitForIdle()
        seen("inside1")
        fold("k1").assertExists()
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun foldingNeverChangesWhatsSavedAndEditsWorkWithPartsFolded() {
        open()
        hub.on("POST /v1/agents/raw", """{"ok":true,"changed":true,"hash":"h2"}""")
        fold("hooks").performScrollTo().performClick()
        rule.waitForIdle()
        seen("2 keys },")
        rule.onNodeWithText("Stop", substring = true).assertDoesNotExist()
        // The row above it, with hooks folded.
        line(1).performTextReplacement("{\n  \"theme\": \"light\",")
        rule.waitForIdle()
        tap(str(R.string.raw_save))
        assertEquals(TEXT.replace("dark", "light"), saved().getString("text"))
        assertEquals("h1", saved().getString("expected"))
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun collapseAllAndExpandAll() {
        open()
        tap(str(R.string.raw_collapse_all))
        unfold("hooks").assertExists()
        unfold("env").assertExists()
        rule.onNodeWithText("API_KEY", substring = true).assertDoesNotExist()
        tap(str(R.string.raw_expand_all))
        fold("hooks").assertExists()
        fold("hooks.Stop").assertExists()
        seen("API_KEY")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun aFoldControlIsBigEnoughForAThumb() {
        open()
        val bounds = fold("hooks").fetchSemanticsNode().size
        val density = context.resources.displayMetrics.density
        assertTrue("${bounds.height}", bounds.height >= (44 * density).toInt())
        assertTrue("${bounds.width}", bounds.width >= (44 * density).toInt())
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun invalidTextSaysWhereSendsNothingAndFoldingFallsBackToPlainWithNothingLost() {
        open()
        hub.on("POST /v1/agents/raw", """{"ok":true,"changed":true,"hash":"h2"}""")
        fold("hooks").performScrollTo().performClick()
        rule.waitForIdle()
        val broken = "{\n  \"theme\": oops,"
        line(1).performTextReplacement(broken)
        rule.waitForIdle()
        seen(str(R.string.raw_bad_at, 2, 12))
        tap(str(R.string.raw_save))
        assertEquals(0, saves())
        // Folding now can't know the shape: everything comes back as plain text, what was folded and what was typed.
        unfold("hooks").performScrollTo().performClick()
        rule.waitForIdle()
        seen(str(R.string.raw_no_fold, 2, 12))
        rule.onNodeWithText("Stop", substring = true).assertExists()
        rule.onNodeWithText("oops", substring = true).assertExists()
        fold("env").assertDoesNotExist()
        // Fixed: folding comes back by itself, as it was.
        line(1).performTextReplacement(TEXT.replace("dark", "fine"))
        rule.waitForIdle()
        fold("env").assertExists()
        unfold("hooks").assertExists()
        tap(str(R.string.raw_save))
        assertEquals(TEXT.replace("dark", "fine"), saved().getString("text"))
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun whatTheServerWantsCheckedGoesOnTheConfirmThenTheFingerprintThenItSaysSo() {
        hub.handler = { m, path, body ->
            if (m == "POST" && path == "/v1/agents/raw") {
                if (body!!.optBoolean("confirmRun")) ok() else Result.failure(AgentSettingRefused("confirm_run", "These change what runs:\n\nhooks: {\"Stop\":[]}"))
            } else null
        }
        lock()
        open()
        line(1).performTextReplacement("{\n  \"theme\": \"x\",")
        rule.waitForIdle()
        tap(str(R.string.raw_save))
        val ask = spy.confirms.single()
        assertTrue(ask.body, ask.body.contains("hooks: {\"Stop\":[]}"))
        assertTrue(ask.danger)
        ask.onConfirm()
        rule.waitForIdle()
        assertEquals(1, checks)
        assertTrue(saved().getBoolean("confirmRun"))
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun aRefusalOrAChangedFileIsSaidAndWhatsTypedStays() {
        hub.fails("POST /v1/agents/raw", AgentSettingRefused("file_changed", "That file changed on the server since you opened it."))
        open()
        line(1).performTextReplacement("{\n  \"theme\": \"mine\",")
        rule.waitForIdle()
        tap(str(R.string.raw_save))
        seen("changed on the server")
        seen("mine")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun saveIsOffUntilSomethingChangedAndLeavingAsksFirst() {
        open()
        tap(str(R.string.raw_save))
        assertEquals(0, saves())
        tap(str(R.string.mcp_scope_local))
        assertEquals(0, spy.confirms.size)
        assertTrue(hub.calls.any { it.second.contains("scope=local") })
        line(1).performTextReplacement("{\n  \"theme\": \"x\",")
        rule.waitForIdle()
        tap(str(R.string.mcp_scope_user))
        assertEquals(str(R.string.raw_discard_title), spy.confirms.single().title)
    }

    @Test fun aFileThatDoesntParseIsNotShownAndSaysWhere() {
        hub.on("GET /v1/agents/raw", """{"agent":"claude","scope":"user","folder":"home","folders":[],"where":"~/.claude/settings.json","placeholder":"x","state":"malformed","hash":"h","text":null,"hidden":0,"line":3,"column":4,"undo":null}""")
        rule.setContent { Harness(spy) { AgentRawScreen(hub, "claude", onBack = {}) } }
        rule.waitForIdle()
        seen(str(R.string.raw_malformed, 3, 4))
        rule.onNodeWithText(str(R.string.raw_save)).assertDoesNotExist()
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun undoIsOfferedAndAsksWhereItMust() {
        hub.handler = { m, path, body -> if (m == "POST" && path == "/v1/agents/raw/undo") { if (body!!.optBoolean("confirmRun")) ok() else Result.failure(AgentSettingRefused("confirm_run", "Putting this back: hooks")) } else null }
        lock()
        hub.on("GET /v1/agents/raw", reply(TEXT, more = "").replace("\"undo\":null", "\"undo\":{\"scope\":\"user\",\"folder\":null,\"needsCheck\":true}"))
        rule.setContent { Harness(spy) { AgentRawScreen(hub, "claude", onBack = {}) } }
        rule.waitForIdle()
        tap(str(R.string.raw_undo_body))
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertNotNull(hub.calls.last { it.second == "/v1/agents/raw/undo" }.third?.optBoolean("confirmRun")?.takeIf { it })
        assertFalse(hub.calls.isEmpty())
    }
}
