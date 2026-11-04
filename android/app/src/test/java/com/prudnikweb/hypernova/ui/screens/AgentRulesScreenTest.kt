package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.ui.settings.AgentRulesScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val MASKED = "Bash(curl -H \\\"Authorization: Bearer <hidden on the server 1>\\\" *)"
private const val RULES = """{"agent":"claude","supported":true,"where":"~/.claude/settings.json","state":"ok","whyNot":null,"hash":"h1","undo":null,"lists":{
 "allow":{"others":1,"rules":[
  {"index":0,"text":"Bash(npm run test:*)","masked":false},
  {"index":2,"text":"Bash","masked":false,"broad":"Claude Code can run any command without asking. That’s close to Bypass."},
  {"index":3,"text":"$MASKED","masked":true},
  {"index":4,"text":"Read(src/**)","masked":false,"overriddenBy":"deny","overrides":"Read"},
  {"index":5,"text":"Bash(","masked":false,"problem":"Rules are written Tool or Tool(content)."},
  {"index":6,"text":"Write(~/**)","masked":false,"warning":"Write(path) never matches: use Edit(path), which covers it."}]},
 "ask":{"others":0,"rules":[{"index":0,"text":"WebFetch(domain:example.com)","masked":false}]},
 "deny":{"others":0,"rules":[{"index":0,"text":"Read","masked":false}]}}}"""
private const val DONE = """{"ok":true,"agent":"claude","key":"permissions.rules","value":null,"changed":true,"hash":"h2","loosened":false}"""

/** Agents & providers › an agent's settings › Permission rules (sharedui's ui/settings/AgentRulesParts.kt). */
@RunWith(RobolectricTestRunner::class)
class AgentRulesScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = ExtHub()

    @Before fun setUp() = resetPrefs()

    private fun open(agent: String = "claude") {
        rule.setContent { Harness(spy) { AgentRulesScreen(hub, agent, onBack = {}) } }
        rule.waitForIdle()
    }
    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().assertExists()
    private fun gone(text: String) = rule.onNodeWithText(text, substring = true).assertDoesNotExist()
    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }
    private fun field(label: String) = rule.onNode(hasContentDescription(label))
    private fun puts() = hub.calls.filter { it.first == "PUT" && it.second == "/v1/agents/settings" }.map { it.third!! }

    @Config(qualifiers = "+h1800dp")
    @Test fun rulesAreShownByListWithWhatEachOneMeans() {
        hub.on("GET /v1/agents/rules", RULES)
        open()
        // Allow first, each row as the server wrote it, with its notes.
        seen("Bash(npm run test:*)")
        seen("close to Bypass")
        seen("<hidden on the server 1>")
        seen(str(R.string.pr_wins, str(R.string.pr_list_deny), "Read"))
        seen(str(R.string.pr_skipped, "Rules are written Tool or Tool(content)."))
        seen("never matches")
        seen(str(R.string.pr_when))
        seen(str(R.string.pr_scope))
        seen(str(R.string.pr_allow_body))
        seen(context.resources.getQuantityString(R.plurals.pr_others, 1, 1))
        gone("WebFetch(domain:example.com)")
        rule.onRoot().snap("agent_rules")
        tap(str(R.string.pr_list_with, str(R.string.pr_list_ask), "1"))
        seen("WebFetch(domain:example.com)")
        seen(str(R.string.pr_ask_body))
        gone("Bash(npm run test:*)")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun searchLooksInEveryListAndSaysWhenNothingHasIt() {
        hub.on("GET /v1/agents/rules", RULES)
        open()
        field(str(R.string.pr_search)).performTextInput("READ")
        rule.waitForIdle()
        seen("Read(src/**)")
        seen("${str(R.string.pr_list_deny)} 1")
        gone("Bash(npm run test:*)")
        field(str(R.string.pr_search)).performTextClearance()
        field(str(R.string.pr_search)).performTextInput("nothing has this")
        rule.waitForIdle()
        seen(str(R.string.pr_none_found))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun addingToDenyAsksNothingAndAddingToAllowConfirmsInTheServersWords() {
        hub.on("GET /v1/agents/rules", RULES)
        hub.handler = { m, p, b ->
            if (m == "PUT" && p == "/v1/agents/settings") {
                val allow = b!!.getJSONObject("rule").getString("list") == "allow"
                if (allow && !b.optBoolean("confirmed")) Result.failure(AgentSettingRefused("confirm_needed", "Claude Code will do what that covers without asking.")) else Result.success(JSONObject(DONE))
            } else null
        }
        open()
        tap(str(R.string.pr_list_with, str(R.string.pr_list_deny), "1"))
        tap(str(R.string.pr_add))
        field(str(R.string.pr_field_command)).performTextInput("rm -rf")
        rule.waitForIdle()
        seen(str(R.string.pr_will_be, "Bash(rm -rf *)"))
        rule.onNode(hasContentDescription(str(R.string.pr_field_command))).assertExists()
        tap(str(R.string.pr_save))
        val first = puts().single()
        assertEquals("deny", first.getJSONObject("rule").getString("list"))
        assertEquals("Bash(rm -rf *)", first.getJSONObject("rule").getString("text"))
        assertEquals("h1", first.getString("expected"))
        assertFalse(first.has("confirmed"))
        assertEquals(0, spy.confirms.size)

        tap(str(R.string.pr_list_with, str(R.string.pr_list_allow), "6"))
        tap(str(R.string.pr_add))
        field(str(R.string.pr_field_command)).performTextInput("npm test")
        rule.waitForIdle()
        tap(str(R.string.pr_save))
        val asked = spy.confirms.single()
        assertEquals("Claude Code will do what that covers without asking.", asked.body)
        assertEquals(str(R.string.pr_confirm_action), asked.action)
        asked.onConfirm()
        rule.waitForIdle()
        assertTrue(puts().last().getBoolean("confirmed"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun anEditSendsTheRuleWithItsPlaceholderSoTheSecretStaysAndAMoveOrDeleteSendsNoText() {
        hub.on("GET /v1/agents/rules", RULES)
        hub.on("PUT /v1/agents/settings", DONE)
        open()
        val shownInRow = "Bash(curl -H \"Authorization: Bearer <hidden on the server 1>\" *)"
        tap(shownInRow)
        tap(str(R.string.pr_edit))
        seen(str(R.string.pr_hidden))
        val shown = "Bash(curl -H \"Authorization: Bearer <hidden on the server 1>\" *)"
        field(str(R.string.pr_field_text)).performTextClearance()
        field(str(R.string.pr_field_text)).performTextInput(shown.replace("curl", "wget"))
        rule.waitForIdle()
        tap(str(R.string.pr_save))
        val edit = puts().single().getJSONObject("rule")
        assertEquals("edit", edit.getString("op"))
        assertEquals(3, edit.getInt("index"))
        assertEquals(shown.replace("curl", "wget"), edit.getString("text"))

        tap("Bash(npm run test:*)")
        tap(str(R.string.pr_move_to, str(R.string.pr_list_deny)))
        val move = puts().last().getJSONObject("rule")
        assertEquals("move", move.getString("op"))
        assertEquals("deny", move.getString("to"))
        assertEquals(0, move.getInt("index"))
        assertFalse(move.has("text"))

        tap("Write(~/**)")
        tap(str(R.string.pr_delete))
        val del = puts().last().getJSONObject("rule")
        assertEquals("delete", del.getString("op"))
        assertEquals(6, del.getInt("index"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aRuleClaudeCodeWouldSkipSaysWhyAndCanBeKeptAsTyped() {
        hub.on("GET /v1/agents/rules", RULES)
        hub.handler = { m, _, b ->
            if (m == "PUT") {
                if (b!!.optBoolean("asTyped")) Result.success(JSONObject(DONE)) else Result.failure(AgentSettingRefused("bad_rule", "Empty parentheses: use just \"Bash\"."))
            } else null
        }
        open()
        tap(str(R.string.pr_list_with, str(R.string.pr_list_deny), "1"))
        tap(str(R.string.pr_add))
        tap(str(R.string.pr_kind_text))
        seen(str(R.string.pr_text_note))
        field(str(R.string.pr_field_text)).performTextInput("Bash()")
        rule.waitForIdle()
        gone(str(R.string.pr_save_anyway))
        tap(str(R.string.pr_save))
        seen("Empty parentheses")
        seen(str(R.string.pr_save_anyway_body))
        rule.onRoot().snap("agent_rules_form_bad")
        tap(str(R.string.pr_save_anyway))
        assertTrue(puts().last().getBoolean("asTyped"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aFileThatMovedOnSaysSoAndIsReadAgain() {
        hub.on("GET /v1/agents/rules", RULES)
        hub.fails("PUT /v1/agents/settings", AgentSettingRefused("file_changed", "That file changed on the server since this was read. Nothing was written."))
        open()
        tap("Bash(npm run test:*)")
        tap(str(R.string.pr_delete))
        seen("That file changed on the server")
        assertEquals(2, hub.calls.count { it.first == "GET" })
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun undoSaysNothingWhenItTightensAndConfirmsWhenItLoosens() {
        val offer = """"undo":{"needsConfirm":true,"change":"Edit goes on Allow."}"""
        hub.on("GET /v1/agents/rules", RULES.replace(""""undo":null""", offer))
        hub.handler = { m, p, b ->
            if (m == "POST" && p == "/v1/agents/settings/undo") {
                if (b!!.optBoolean("confirmed")) Result.success(JSONObject("""{"ok":true}""")) else Result.failure(AgentSettingRefused("confirm_needed", "Putting this back lets more through: Edit goes on Allow."))
            } else null
        }
        open()
        tap(str(R.string.pr_undo))
        assertEquals("Putting this back lets more through: Edit goes on Allow.", spy.confirms.single().body)
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        val sent = hub.calls.last { it.first == "POST" }.third!!
        assertEquals("h1", sent.getString("expected"))
        assertTrue(sent.getBoolean("confirmed"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aLinkIsShownAndOffersNothingToChange() {
        hub.on("GET /v1/agents/rules", RULES.replace(""""whyNot":null""", """"whyNot":"link"""").replace(""""hash":"h1"""", """"hash":null"""))
        open()
        seen(str(R.string.as_why_link, hub.mac.name))
        seen("Bash(npm run test:*)")
        gone(str(R.string.pr_add))
        tap("Bash(npm run test:*)")
        gone(str(R.string.pr_delete))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aListThatIsntOneAndAFileThatDoesntParseSaySo() {
        hub.on("GET /v1/agents/rules", RULES.replace(""""ask":{"others":0,"rules":[{"index":0,"text":"WebFetch(domain:example.com)","masked":false}]}""", """"ask":{"others":0,"rules":null}"""))
        open()
        tap(str(R.string.pr_list_with, str(R.string.pr_list_ask), "?"))
        seen(str(R.string.pr_not_a_list, "ask"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun anOlderServerSaysItNeedsUpdatingWithNoTryAgain() {
        hub.fails("GET /v1/agents/rules", AgentSettingsTooOld("This server needs updating to change these."))
        open()
        seen("needs updating")
        gone(str(R.string.try_again))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun codexSaysPlainlyWhatItHasAndWhatItDoesnt() {
        hub.on("GET /v1/agents/rules", """{"agent":"codex","supported":false,"rulesFiles":2}""")
        open("codex")
        seen(str(R.string.pr_codex))
        seen("2 files")
        gone(str(R.string.pr_add))
        rule.onRoot().snap("agent_rules_codex")
    }
}
