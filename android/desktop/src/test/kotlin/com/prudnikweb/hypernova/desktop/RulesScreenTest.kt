package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val RULES = """{"agent":"claude","supported":true,"where":"~/.claude/settings.json","state":"ok","whyNot":null,"hash":"h1","undo":null,"lists":{
 "allow":{"others":0,"rules":[{"index":0,"text":"Bash(npm run test:*)","masked":false}]},
 "ask":{"others":0,"rules":[]},
 "deny":{"others":0,"rules":[{"index":0,"text":"Read(~/.ssh/**)","masked":false}]}}}"""
private const val DONE = """{"ok":true,"agent":"claude","key":"permissions.rules","value":null,"changed":true,"hash":"h2","loosened":false}"""

/**
 * Permission rules on the desktop: the phone's own parts, through the real Settings screen over real mutual TLS, against
 * [FakeDaemon]. A loosening is the same plain confirm here too, never "use your phone".
 */
@OptIn(ExperimentalTestApi::class)
class RulesScreenTest {
    private val daemons = mutableListOf<FakeDaemon>()

    private fun daemon() = FakeDaemon("atlas", lan = true).also {
        it.snapshot = snapshot("atlas")
        daemons += it
    }

    @Before fun fresh() {
        DesktopRig.reset()
        shell.settings = false
        shell.rulesAgent = null
    }

    @After fun tearDown() {
        DesktopRig.reset()
        shell.settings = false
        shell.rulesAgent = null
        daemons.forEach { it.close() }
    }

    /** The answer comes over a real socket, so a request having been made doesn't mean it's drawn yet. */
    private fun ComposeUiTest.drawn(text: String) {
        try {
            // Long, for a whole suite on a busy machine: the first GET has a client of its own to connect (Api.getSlow).
            until(text, timeoutMs = 90_000) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            val asked = daemons.sumOf { it.count("GET", "/v1/agents/rules") }
            throw AssertionError("not drawn: $text\nThe rules asked for $asked times.\nOn screen:\n${onRoot(useUnmergedTree = true).printToString()}", e)
        }
    }

    private fun ComposeUiTest.openClaude(d: FakeDaemon) {
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.desk_rules_for_claude.text()).performClick()
        drawn("Bash(npm run test:*)")
    }

    @Test fun aLooseningIsAPlainConfirmAndTheChangeGoesThrough() = runDesktopComposeUiTest(900, 1100) {
        val d = daemon()
        d.on("GET", "/v1/agents/rules") { json(200, JSONObject(RULES)) }
        d.on("PUT", "/v1/agents/settings") { r ->
            val b = JSONObject(r.body)
            if (b.optBoolean("confirmed")) json(200, JSONObject(DONE)) else refusal(403, "confirm_needed", "Claude Code will do what that covers without asking.")
        }
        openClaude(d)
        onNodeWithText(DesktopStrings.text(Str.pr_add)).performClick()
        waitForIdle()
        onNode(hasContentDescription(DesktopStrings.text(Str.pr_field_command))).performTextInput("npm test")
        waitForIdle()
        render("rules_add")
        onNodeWithText(DesktopStrings.text(Str.pr_save)).performClick()
        until("the first answer") { d.count("PUT", "/v1/agents/settings") == 1 }
        drawn("Claude Code will do what that covers without asking.")
        assertFalse(JSONObject(d.calls("PUT", "/v1/agents/settings").single().body).has("confirmed"))
        onNodeWithText(DesktopStrings.text(Str.pr_confirm_action)).performClick()
        until("the confirmed one") { d.count("PUT", "/v1/agents/settings") == 2 }
        val sent = JSONObject(d.calls("PUT", "/v1/agents/settings").last().body)
        assertTrue(sent.getBoolean("confirmed"))
        assertEquals("Bash(npm test *)", sent.getJSONObject("rule").getString("text"))
        assertEquals("h1", sent.getString("expected"))
    }

    @Test fun moveAndDeleteGoByIndexWithNoText() = runDesktopComposeUiTest(900, 1100) {
        val d = daemon()
        d.on("GET", "/v1/agents/rules") { json(200, JSONObject(RULES)) }
        d.on("PUT", "/v1/agents/settings") { json(200, JSONObject(DONE)) }
        openClaude(d)
        onNodeWithText("Bash(npm run test:*)").performClick()
        waitForIdle()
        onNodeWithText(String.format(DesktopStrings.text(Str.pr_move_to), DesktopStrings.text(Str.pr_list_deny))).performClick()
        until("the move") { d.count("PUT", "/v1/agents/settings") == 1 }
        val move = JSONObject(d.calls("PUT", "/v1/agents/settings").single().body).getJSONObject("rule")
        assertEquals("move", move.getString("op"))
        assertEquals("deny", move.getString("to"))
        assertFalse(move.has("text"))
    }

    @Test fun codexSaysWhatItHasAndBackReturnsToSettings() = runDesktopComposeUiTest(900, 1100) {
        val d = daemon()
        d.on("GET", "/v1/agents/rules") { json(200, JSONObject("""{"agent":"codex","supported":false,"rulesFiles":1}""")) }
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.desk_rules_for_codex.text()).performClick()
        drawn(DesktopStrings.text(Str.pr_codex))
        assertTrue(d.calls("GET", "/v1/agents/rules").single().query.contains("agent=codex"))
        onNodeWithText(Ui.back.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.desk_settings.text()).assertExists()
        assertEquals(null, shell.rulesAgent)
    }
}
