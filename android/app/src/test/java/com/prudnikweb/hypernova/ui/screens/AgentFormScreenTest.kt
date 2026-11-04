package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.ui.settings.AgentFormScreen
import org.json.JSONArray
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

private const val PLACEHOLDER = "<hidden on the server>"
private val ROWS = """[
 {"key":"permissions.defaultMode","group":"perms","groupTitle":"Permissions and safety","title":"Default mode","description":"The schema's words.","control":"pick","state":"set","value":"bypassPermissions","removable":true,"asks":null,"applies":"new_sessions","of":[{"value":"plan","asks":null},{"value":"default","asks":null},{"value":"auto","asks":"loosens"}]},
 {"key":"permissions.allow","group":"perms","groupTitle":"Permissions and safety","title":"Allow","description":"","control":"link","state":"unset","asks":null,"removable":false,"screen":"rules"},
 {"key":"hooks","group":"commands","groupTitle":"Commands that run","title":"Hooks","description":"Lifecycle hooks.","control":"json","state":"set","value":{"Stop":[]},"text":"{\n  \"Stop\": [],\n  \"Start\": {\n    \"a\": \"$PLACEHOLDER\"\n  }\n}","hidden":1,"asks":"runs","removable":true},
 {"key":"apiKeyHelper","group":"auth","groupTitle":"Sign-in and credentials","title":"Api key helper","description":"A script.","control":"text","state":"set","value":"$PLACEHOLDER","hidden":1,"asks":"runs","removable":true,"rules":{"minLength":1}},
 {"key":"model","group":"models","groupTitle":"Models","title":"Model","description":"Which one.","control":"text","state":"unset","asks":null,"removable":false},
 {"key":"availableModels","group":"models","groupTitle":"Models","title":"Available models","description":"Which may be chosen.","control":"list","state":"set","value":["opus"],"asks":null,"removable":true,"rules":{"maxItems":2,"unique":true,"itemMinLength":1}},
 {"key":"cleanupPeriodDays","group":"sessions","groupTitle":"Sessions and context","title":"Cleanup period days","description":"Keeps transcripts.","control":"number","state":"unset","default":30,"asks":null,"removable":false,"rules":{"integer":true,"min":1}},
 {"key":"verbose","group":"look","groupTitle":"Look and feel","title":"Verbose","description":"Full output.","control":"switch","state":"unset","default":true,"asks":null,"removable":false},
 {"key":"showThinkingSummaries","group":"look","groupTitle":"Look and feel","title":"Show thinking summaries","description":"The schema's own line.","control":"switch","state":"set","value":true,"default":false,"asks":null,"removable":true,"applies":"maybe_new_session"},
 {"key":"diffTool","group":"look","groupTitle":"Look and feel","title":"Diff tool","description":"Where diffs go.","control":"pick","state":"unset","default":"auto","asks":null,"removable":false,"notRead":true,"of":[{"value":"auto","asks":null},{"value":"terminal","asks":null}]},
 {"key":"allowManagedHooksOnly","group":"managed","groupTitle":"For managed installs","title":"Allow managed hooks only","description":"Managed.","control":"readonly","state":"set","value":true,"text":"true","readOnly":"managed","asks":"unknown","removable":false},
 {"key":"skipDangerousModePermissionPrompt","group":"perms","groupTitle":"Permissions and safety","title":"Skip dangerous mode permission prompt","description":"Off for good.","control":"readonly","state":"set","value":true,"text":"true","readOnly":"not_offered","asks":"loosens","removable":true},
 {"key":"laterKey","group":"other","groupTitle":"Other","title":"Later key","description":"","control":"json","state":"set","value":1,"text":"1","asks":"unknown","removable":true,"notInSchema":true}
]"""
private fun reply(rows: String = ROWS, more: String = "") = """{"agent":"claude","scope":"user","source":{"from":"SchemaStore","licence":"Apache-2.0","fetched":"2026-11-04","checkedAgainst":"Claude Code 2.1.318"},"placeholder":"$PLACEHOLDER","state":"ok","hash":"h1","whyNot":null,"undo":null,"rows":$rows,"hiddenKeys":0$more}"""
private const val SAVED = """{"ok":true,"agent":"claude","key":"k","changed":true,"hash":"h2","undo":null}"""

/** Agents & providers › an agent's settings › All settings: each kind of control, through what the server sends. */
@RunWith(RobolectricTestRunner::class)
class AgentFormScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = ExtHub()
    private val screens = mutableListOf<String>()
    private var backs = 0

    @Before fun setUp() = resetPrefs()

    private fun open(json: String = reply()) {
        hub.on("GET /v1/agents/settings/form", json)
        hub.on("PUT /v1/agents/settings/form", SAVED)
        rule.setContent { Harness(spy) { AgentFormScreen(hub, "claude", onBack = { backs++ }, onScreen = { screens += it }) } }
        rule.waitForIdle()
    }
    private fun scrollTo(text: String) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        rule.waitForIdle()
    }
    private fun tap(text: String) {
        scrollTo(text)
        rule.onNodeWithText(text).performClick()
        rule.waitForIdle()
    }
    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).assertExists()
    private fun puts() = hub.calls.filter { it.first == "PUT" }
    private fun lastPut() = puts().last().third!!
    private fun field(label: String) = rule.onNode(hasContentDescription(label))

    @Test fun whatsInTheFileComesFirstAndGroupsStayTheServers() {
        open()
        seen("Search names")
        seen(str(R.string.as_k_mode))
        seen("permissions.defaultMode")
        seen(str(R.string.mode_title_bypass))
        scrollTo("showThinkingSummaries")
    }

    @Config(qualifiers = "+h1600dp")
    @Test fun aKeyTheAppWordsKeepsItsWordsAndOthersUseTheSchemas() {
        open()
        seen(str(R.string.as_k_mode))
        seen(str(R.string.as_k_thinking))
        assertEquals("the schema's title gives way to the row's words", 0, rule.onAllNodesWithText("Show thinking summaries").fetchSemanticsNodes().size)
        seen("Available models")
        rule.onRoot().snap("settings_form_list")
    }

    @Test fun aSearchFindsNamesAndDescriptionsAndSaysWhenNothingMatches() {
        open()
        field(str(R.string.sf_search)).performTextInput("transcripts")
        rule.waitForIdle()
        seen("cleanupPeriodDays")
        assertEquals(0, rule.onAllNodesWithText("permissions.defaultMode").fetchSemanticsNodes().size)
        field(str(R.string.sf_search)).performTextReplacement("nothing like this")
        rule.waitForIdle()
        seen(str(R.string.sf_none))
    }

    @Test fun anUnsetKeyReadsAsUnsetWithItsDefault() {
        open()
        scrollTo("verbose")
        seen(str(R.string.sf_not_set_default, str(R.string.sf_on)))
        scrollTo("cleanupPeriodDays")
        seen(str(R.string.sf_not_set_default, "30"))
    }

    @Test fun aSwitchSavesAtOnceAndAsksNothingWhenPlain() {
        open()
        scrollTo("verbose")
        rule.onNode(hasContentDescription("Verbose")).assertIsOn().performClick()
        rule.waitUntil { puts().isNotEmpty() }
        assertEquals("verbose", lastPut().getString("key"))
        assertEquals("flips what it's drawn as, its default", false, lastPut().getBoolean("value"))
        assertEquals("h1", lastPut().getString("expected"))
        assertFalse(lastPut().has("confirm"))
        assertTrue(spy.confirms.isEmpty())
        rule.waitUntil { spy.toasts.isNotEmpty() }
        assertEquals(str(R.string.as_saved, hub.mac.name), spy.toasts.last())
    }

    @Test fun aPickerOffersOnlyTheServersChoicesAndAFileHoldingBypassShowsIt() {
        open()
        tap(str(R.string.as_k_mode))
        seen(str(R.string.as_l_plan))
        seen(str(R.string.mode_title_bypass))
        seen(str(R.string.as_when_new_sessions))
        rule.onNodeWithText(str(R.string.mode_title_plan)).performClick()
        rule.waitUntil { puts().isNotEmpty() }
        assertEquals("permissions.defaultMode", lastPut().getString("key"))
        assertEquals("plan", lastPut().getString("value"))
    }

    @Test fun whatRunsCodeAsksOneTapInTheServersWordsAndOnlyThenSaysSo() {
        hub.handler = { m, p, body ->
            if (m == "PUT" && p.endsWith("/form") && !body!!.optBoolean("confirm")) Result.failure(AgentSettingRefused("confirm_needed", "apiKeyHelper\n\nThis can run a command on the server."))
            else null
        }
        open()
        tap("Api key helper")
        seen(str(R.string.sf_asks_runs))
        field("Api key helper").performTextInput("/opt/new")
        rule.waitForIdle()
        tap(str(R.string.raw_save))
        rule.waitUntil { spy.confirms.isNotEmpty() }
        assertEquals(1, puts().size)
        val c = spy.confirms.single()
        assertTrue(c.body.contains("can run a command"))
        assertEquals(str(R.string.sf_change_action), c.action)
        c.onConfirm()
        rule.waitUntil { puts().size == 2 }
        assertEquals(true, lastPut().getBoolean("confirm"))
        assertEquals("/opt/new", lastPut().getString("value"))
    }

    @Test fun aHiddenValueIsNeverPutInTheBoxAndSaveWaitsForANewOne() {
        open()
        tap("Api key helper")
        seen(str(R.string.sf_hidden))
        assertEquals("the mask isn't typed text", 0, rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(PLACEHOLDER))).fetchSemanticsNodes().size)
        seen(str(R.string.sf_set_hidden))
        assertTrue(puts().isEmpty())
    }

    @Test fun aNumberIsCheckedAsTypedAndGoesAsANumber() {
        open()
        tap("Cleanup period days")
        field("Cleanup period days").performTextInput("0")
        rule.waitForIdle()
        seen(str(R.string.sf_p_below, "1"))
        field("Cleanup period days").performTextReplacement("1.5")
        rule.waitForIdle()
        seen(str(R.string.sf_p_whole))
        field("Cleanup period days").performTextReplacement("60")
        rule.waitForIdle()
        tap(str(R.string.raw_save))
        rule.waitUntil { puts().isNotEmpty() }
        assertEquals(60, lastPut().getInt("value"))
    }

    @Test fun aListAddsAndRemovesAndGoesWhole() {
        open()
        tap("Available models")
        scrollTo("opus")
        field(str(R.string.sf_item_hint)).performTextInput("sonnet")
        rule.waitForIdle()
        tap(str(R.string.sf_add))
        seen("sonnet")
        field(str(R.string.sf_item_hint)).performTextInput("sonnet")
        rule.waitForIdle()
        tap(str(R.string.sf_add))
        seen(str(R.string.sf_p_many, 2))
        rule.onNode(hasContentDescription(str(R.string.sf_item_remove, "sonnet"))).let { }
        rule.onAllNodes(hasContentDescription(str(R.string.sf_item_remove, "sonnet"))).onFirst().performClick()
        rule.waitForIdle()
        tap(str(R.string.raw_save))
        rule.waitUntil { puts().isNotEmpty() }
        val sent = lastPut().get("value") as JSONArray
        assertEquals(listOf("opus", "sonnet"), (0 until sent.length()).map { sent.getString(it) })
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]

    @Config(qualifiers = "+h1400dp")
    @Test fun aJsonRowEditsOnlyThatKeyFoldsAndKeepsThePlaceholderWhereItIs() {
        open()
        tap("Hooks")
        seen(str(R.string.sf_hidden_json, PLACEHOLDER))
        rule.onNode(hasContentDescription(str(R.string.raw_fold, "Start"))).assertExists()
        rule.onRoot().snap("settings_form_json")
        rule.onNode(hasContentDescription(str(R.string.raw_fold, "Start"))).performClick()
        rule.waitForIdle()
        rule.onNode(hasContentDescription(str(R.string.raw_unfold, "Start"))).assertExists()
        // The first row is the text before the first thing that folds: its first two lines.
        val line1 = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("line 1")))
        line1.performTextReplacement("{\n  \"Stop\": [1],")
        rule.waitForIdle()
        tap(str(R.string.raw_save))
        rule.waitUntil { puts().isNotEmpty() }
        val text = lastPut().getString("valueJson")
        assertTrue("folded parts go too, the mask where it was: $text", text.contains(PLACEHOLDER))
        assertTrue(text.contains("[1]"))
        assertFalse(lastPut().has("value"))
    }

    @Test fun aJsonRowThatIsntJsonSaysWhereAndWontSave() {
        open()
        tap("Later key")
        seen(str(R.string.sf_not_in_schema))
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("line 1"))).performTextReplacement("{oops")
        rule.waitForIdle()
        seen("isn't valid JSON")
        scrollTo(str(R.string.raw_save))
        rule.onNodeWithText(str(R.string.raw_save)).performClick()
        rule.waitForIdle()
        assertTrue(puts().isEmpty())
    }

    @Test fun whatHasItsOwnScreenGoesThereAndHasNoControl() {
        open()
        tap("Allow")
        assertEquals(listOf("rules"), screens)
        assertTrue(puts().isEmpty())
    }

    @Test fun anEnterpriseKeyIsReadOnlyAndCantBeRemoved() {
        open()
        tap("Allow managed hooks only")
        seen(str(R.string.sf_ro_managed))
        assertEquals(0, rule.onAllNodesWithText(str(R.string.sf_remove)).fetchSemanticsNodes().size)
    }

    @Test fun aPermissiveKeyHasNoControlAndCanOnlyGo() {
        open()
        tap("Skip dangerous mode permission prompt")
        seen(str(R.string.sf_ro_not_offered))
        tap(str(R.string.sf_remove))
        rule.waitUntil { puts().isNotEmpty() }
        assertEquals("skipDangerousModePermissionPrompt", lastPut().getString("key"))
        assertTrue(lastPut().isNull("value"))
    }

    @Test fun whenItAppliesIsSaidOrSaidToBeUnknown() {
        open()
        tap(str(R.string.as_k_thinking))
        seen(str(R.string.as_when_maybe_new_session))
        tap("Verbose")
        seen(str(R.string.sf_when_unknown))
    }

    @Test fun aKeyTheInstalledAgentDoesntCheckSaysItMayDoNothing() {
        open()
        tap("Diff tool")
        seen(str(R.string.sf_not_read, "Claude Code 2.1.318"))
    }

    @Test fun aRefusalStaysUnderItsRowAndWhatsTypedStays() {
        open()
        tap("Model")
        field("Model").performTextInput("opus")
        rule.waitForIdle()
        hub.fails("PUT /v1/agents/settings/form", AgentSettingRefused("file_changed", "That file changed on the server."))
        tap(str(R.string.raw_save))
        rule.waitUntil { rule.onAllNodesWithText("That file changed on the server.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("opus"))).fetchSemanticsNodes().size)
    }

    @Test fun anUndoSaysWhatItPutsBackAndAsksWhenTheServerWants() {
        open(reply(more = ""","x":1""").replace("\"undo\":null", """"undo":{"key":"hooks","asks":true}"""))
        hub.handler = { m, p, body ->
            if (m == "POST" && p.endsWith("/undo") && !body!!.optBoolean("confirm")) Result.failure(AgentSettingRefused("confirm_needed", "hooks\n\nThis can run a command on the server."))
            else if (m == "POST") Result.success(JSONObject("""{"ok":true,"agent":"claude","key":"hooks","hash":"h3"}"""))
            else null
        }
        tap(str(R.string.as_undo_row))
        rule.waitUntil { spy.confirms.isNotEmpty() }
        spy.confirms.single().onConfirm()
        rule.waitUntil { spy.toasts.isNotEmpty() }
        assertEquals(str(R.string.as_undone, hub.mac.name), spy.toasts.last())
        val sent = hub.calls.last { it.first == "POST" }.third!!
        assertEquals("h1", sent.getString("expected"))
        assertEquals(true, sent.getBoolean("confirm"))
    }

    @Test fun aLinkedFileSaysSoAndOffersNoControl() {
        open(reply().replace("\"whyNot\":null", "\"whyNot\":\"link\""))
        seen(str(R.string.as_why_link, hub.mac.name))
        scrollTo("verbose")
        assertEquals(0, rule.onAllNodes(hasContentDescription("Verbose")).fetchSemanticsNodes().size)
        tap("Model")
        assertEquals(0, rule.onAllNodesWithText(str(R.string.raw_save)).fetchSemanticsNodes().size)
    }

    @Test fun aFileThatIsntValidSaysSoWithNoRows() {
        open(reply(rows = "[]").replace("\"state\":\"ok\"", "\"state\":\"malformed\""))
        seen(str(R.string.sf_state_malformed))
    }

    @Test fun anOlderServerSaysSoAndDoesntOfferToTryAgain() {
        hub.fails("GET /v1/agents/settings/form", AgentSettingsTooOld("This server is older than this."))
        rule.setContent { Harness(spy) { AgentFormScreen(hub, "claude", onBack = {}, onScreen = {}) } }
        rule.waitForIdle()
        seen("older than this")
        assertEquals(0, rule.onAllNodesWithText(str(R.string.try_again)).fetchSemanticsNodes().size)
    }

    /** All 142 of a real catalog's size: only what's on screen is composed, and a search reaches the last one. */
    @Test fun aLongListIsLazyAndSearchStillFindsTheEnd() {
        val many = JSONArray((0 until 142).map { i -> JSONObject("""{"key":"key$i","group":"g${i / 12}","groupTitle":"Group ${i / 12}","title":"Setting number $i","description":"Line $i.","control":"switch","state":"unset","asks":null,"removable":false}""") })
        open(reply(rows = many.toString()))
        seen("Setting number 0")
        assertEquals("the last row isn't composed", 0, rule.onAllNodesWithText("Setting number 141").fetchSemanticsNodes().size)
        field(str(R.string.sf_search)).performTextInput("number 141")
        rule.waitForIdle()
        seen("Setting number 141")
    }

    @Test fun leavingWithSomethingTypedAsksFirst() {
        open()
        tap("Model")
        field("Model").performTextInput("opus")
        rule.waitForIdle()
        spy.confirms.clear()
        // The scaffold's back control: a tap on it goes through `leaving`.
        rule.onNode(hasClickLabelOrBack()).performClick()
        rule.waitForIdle()
        assertEquals(0, backs)
        assertEquals(str(R.string.raw_discard_action), spy.confirms.single().action)
        spy.confirms.single().onConfirm()
        assertEquals(1, backs)
    }

    private fun hasClickLabelOrBack() = hasContentDescription(str(R.string.back))
}
