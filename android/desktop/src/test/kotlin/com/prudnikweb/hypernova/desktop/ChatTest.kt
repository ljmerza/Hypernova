package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DebugHead
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.data.DebugLogger
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.choose
import com.prudnikweb.hypernova.desktop.DesktopRig.inPane
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import com.prudnikweb.hypernova.desktop.ui.BOX_TAG
import com.prudnikweb.hypernova.desktop.ui.ChatDrafts
import com.prudnikweb.hypernova.desktop.ui.CHAT_TAG
import com.prudnikweb.hypernova.desktop.ui.PANE_TAG
import com.prudnikweb.hypernova.desktop.ui.Scaled
import com.prudnikweb.hypernova.desktop.EnterKeyPref
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/** The chat in the pane, through the real screen, over [FakeDaemon] on real mutual TLS: docs/DECISIONS.md, "The chat on the desktop". */
@OptIn(ExperimentalTestApi::class)
class ChatTest {
    private val shell get() = DesktopRig.shell
    private lateinit var d: FakeDaemon
    private val name = "Stream invoice exports"

    private fun user(id: String, text: String, ts: Long = System.currentTimeMillis()) = JSONObject().put("id", id).put("kind", "user").put("ts", ts).put("text", text)
    private fun reply(id: String, text: String, ts: Long = System.currentTimeMillis()) = JSONObject().put("id", id).put("kind", "assistant").put("ts", ts).put("text", text)
    private fun tool(id: String, name: String, summary: String) = JSONObject().put("id", id).put("kind", "tool").put("ts", System.currentTimeMillis()).put("toolUseId", id).put("name", name)
        .put("summary", summary).put("input", JSONObject().put("file_path", summary)).put("status", "done")

    private fun history(vararg e: JSONObject) = history(e.toList())
    private fun history(e: List<JSONObject>) = d.ok("GET", "/v1/sessions/s1/events", JSONObject().put("events", JSONArray(e)))

    private val prompt = "/v1/sessions/s1/prompt"

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name) { put("projectName", "invoices").put("cwd", "/home/dev/invoices") }))
        EnterKeyPrefReset.toDefault()
        // What the last test left in the box: they're one process.
        DesktopRig.main {
            ChatDrafts.set("s1", "")
            AttachDrafts.takeBack("s1")
            shell.settings = false
        }
    }

    @After fun tearDown() {
        DesktopRig.reset()
        d.close()
    }

    private object EnterKeyPrefReset {
        fun toDefault() = DesktopRig.main { DesktopRig.shell; EnterKeyPref.set(EnterKey.DEFAULT) }
    }

    private fun ComposeUiTest.up(scale: Float? = null) {
        setContent { if (scale != null) Scaled(scale) { App(shell, reduced = true) } else App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { (Core.live?.sessions?.size ?: 0) >= 1 }
        waitForIdle()
        choose(name)
        until("its history") { Core.live?.loadingTranscript("s1") == false && d.count("GET", "/v1/sessions/s1/events") > 0 }
        waitForIdle()
    }

    private fun ComposeUiTest.box() = onNode(hasTestTag(BOX_TAG))
    private fun ComposeUiTest.inChat(text: String, substring: Boolean = false) = onNode(hasText(text, substring = substring) and hasAnyAncestor(hasTestTag(CHAT_TAG)))
    private fun ComposeUiTest.type(text: String) {
        box().performClick()
        box().performTextInput(text)
        waitForIdle()
    }
    private fun ComposeUiTest.press(k: Key) {
        box().performKeyInput { pressKey(k) }
        waitForIdle()
    }

    // ─── Reading ───

    @Test fun aConversationIsDrawnAsOnThePhone() = runDesktopComposeUiTest(1200, 1000) {
        history(
            user("u1", "Stream the CSV instead of building it"),
            JSONObject().put("id", "th1").put("kind", "thinking").put("ts", System.currentTimeMillis()).put("chars", 2140),
            tool("t1", "Read", "src/a.ts"), tool("t2", "Read", "src/b.ts"), tool("t3", "Read", "src/c.ts"),
            reply("r1", "**The cause** is `buildCsv()`.\n\n```\nconst rows = all()\n```\n\n| Rows | Time |\n|---|---|\n| 50000 | 2s |\n\nSee https://example.com/doc for it."),
            JSONObject().put("id", "n1").put("kind", "notice").put("ts", System.currentTimeMillis()).put("text", "Compacted the conversation").put("tone", "info"),
        )
        up()
        inChat("Stream the CSV instead of building it").assertIsDisplayed()
        inChat("YOU").assertIsDisplayed()
        inChat(Ui.claude.text().uppercase()).assertIsDisplayed()
        // Markdown, as blocks: the code in its block, the cells in their table.
        inChat("const rows = all()").assertIsDisplayed()
        onNode(hasContentDescription("50000", substring = true) and hasAnyAncestor(hasTestTag(CHAT_TAG))).assertIsDisplayed()
        inChat("buildCsv()", substring = true).assertIsDisplayed()
        // Three reads fold into one line.
        inChat("USED 3 TOOLS", substring = true).assertIsDisplayed()
        inChat("src/a.ts").assertDoesNotExist()
        inChat("Compacted the conversation").assertIsDisplayed()
        render("chat-conversation")
    }

    @Test fun aFoldedRunOpensWithAMouseClickOnItsWords() = runDesktopComposeUiTest(1200, 1000) {
        history(user("u1", "Look"), tool("t1", "Read", "src/a.ts"), tool("t2", "Read", "src/b.ts"), tool("t3", "Read", "src/c.ts"), reply("r1", "Done"))
        up()
        inChat("USED 3 TOOLS", substring = true).performMouseInput { click() }
        waitForIdle()
        inChat("src/a.ts").assertIsDisplayed()
        inChat("USED 3 TOOLS", substring = true).performMouseInput { click() }
        waitForIdle()
        inChat("src/a.ts").assertDoesNotExist()
    }

    @Test fun aCodeBlockWraps() = runDesktopComposeUiTest(1000, 900) {
        val long = "x".repeat(10) + " " + (1..60).joinToString(" ") { "word$it" }
        history(reply("r1", "```\n$long\n```"))
        up()
        val b = inChat(long).getBoundsInRoot()
        val pane = onNode(hasTestTag(CHAT_TAG)).getBoundsInRoot()
        assertTrue("it stays inside the chat", b.right <= pane.right)
        assertTrue("and is several lines", b.bottom - b.top > androidx.compose.ui.unit.Dp(40f))
    }

    @Test fun aDiagramSaysWhatItIsRatherThanBreaking() = runDesktopComposeUiTest(1200, 900) {
        history(reply("r1", "```mermaid\nflowchart LR\n  A --> B\n```"))
        up()
        inChat(Ui.desk_diagram_note.text()).assertIsDisplayed()
        inChat("flowchart LR", substring = true).assertIsDisplayed()
    }

    @Test fun anEmptySessionAndOneThatWontLoadSaySo() = runDesktopComposeUiTest(1200, 900) {
        d.on("GET", "/v1/sessions/s1/events") { refusal(500, "boom", "No history") }
        up()
        until("the error") { Core.live?.transcriptError("s1") != null }
        waitForIdle()
        inChat(Ui.try_again.text()).assertIsDisplayed()
        history()
        inChat(Ui.try_again.text()).performClick()
        until("loaded") { Core.live?.transcriptError("s1") == null && Core.live?.loadingTranscript("s1") == false }
        waitForIdle()
        inChat(Ui.empty_conversation.text()).assertIsDisplayed()
    }

    // ─── Replying ───

    @Test fun enterSendsTheBoxAndItEmpties() = runDesktopComposeUiTest(1200, 900) {
        history(user("u1", "Hello"))
        d.ok("POST", prompt, JSONObject().put("session", session("s1", state = "working", title = name)))
        up()
        type("Now the tests")
        press(Key.Enter)
        until("the prompt") { d.count("POST", prompt) == 1 }
        assertEquals("Now the tests", d.calls("POST", prompt).last().json.getString("text"))
        waitForIdle()
        // In the chat at once, as on the phone: on its way, until the server logs it.
        inChat("Now the tests").assertIsDisplayed()
        onNode(hasTestTag(BOX_TAG) and hasText("Now the tests")).assertDoesNotExist()
    }

    @Test fun sendSaysSendingThenTheQueueSaysQueued() = runDesktopComposeUiTest(1200, 900) {
        history(user("u1", "Hello"))
        val hold = java.util.concurrent.CountDownLatch(1)
        d.on("POST", prompt) {
            hold.await(10, java.util.concurrent.TimeUnit.SECONDS)
            json(200, JSONObject().put("session", session("s1", state = "working", title = name)).put("queued", true))
        }
        up()
        type("One more thing")
        inPane(Ui.send.text()).performClick()
        until("on its way") { Core.live?.sending("s1") == true }
        waitForIdle()
        inChat("SENDING…", substring = true).assertIsDisplayed()
        hold.countDown()
        until("queued") { Core.live?.transcript("s1")?.any { it is Item.User && it.queued } == true }
        waitForIdle()
        inChat(Ui.queued_label.text().uppercase(), substring = true).assertIsDisplayed()
        render("chat-queued")
    }

    @Test fun aRightClickOnAQueuedMessageEditsOrRemovesIt() = runDesktopComposeUiTest(1200, 900) {
        history(user("u1", "Hello"))
        val q = JSONArray().put(JSONObject().put("id", "q:1").put("text", "And the docs").put("at", 1L).put("clientId", JSONObject.NULL))
        val term = JSONObject().put("id", "t").put("mac", 0).put("phone", 0)
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "working", title = name) { put("projectName", "invoices").put("cwd", "/home/dev/invoices"); put("queue", q); put("queued", 1); put("queueEdit", true); put("term", term) }))
        up()
        until("queued") { Core.live?.transcript("s1")?.any { it is Item.User && it.queueId == "q:1" } == true }
        waitForIdle()
        val heldNow = JSONArray().put(JSONObject().put("id", "h1").put("text", "And the docs").put("at", 1L).put("editing", true))
        d.ok("POST", "/v1/sessions/s1/queue/q:1/edit", JSONObject().put("session", session("s1", state = "working", title = name) { put("held", heldNow); put("queueEdit", true) }).put("heldId", "h1").put("text", "And the docs"))
        inChat("And the docs").performMouseInput { rightClick() }
        waitForIdle()
        onNode(hasText(Ui.waiting_remove.text())).assertIsDisplayed()
        onNode(hasText(Ui.waiting_edit.text())).performMouseInput { click() }
        waitForIdle()
        until("taken out") { d.count("POST", "/v1/sessions/s1/queue/q:1/edit") == 1 }
        until("it says it's being edited") { Core.live?.transcript("s1")?.any { it is Item.User && it.editing } == true }
        waitForIdle()
        inChat(Ui.waiting_editing_label.text().uppercase(), substring = true).assertIsDisplayed()
        d.ok("PUT", "/v1/sessions/s1/held/h1", JSONObject().put("session", session("s1", state = "working", title = name) { put("queueEdit", true) }))
        onNode(hasText(Ui.waiting_save.text())).performClick()
        until("saved") { d.count("PUT", "/v1/sessions/s1/held/h1") == 1 }
        assertEquals("And the docs", d.calls("PUT", "/v1/sessions/s1/held/h1").last().json.getString("text"))
    }

    @Test fun shiftEnterIsANewLineAndNewLineModeNeverSends() = runDesktopComposeUiTest(1200, 900) {
        history()
        d.ok("POST", prompt, JSONObject().put("session", session("s1", state = "working", title = name)))
        up()
        type("first")
        box().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        waitForIdle()
        assertEquals(0, d.count("POST", prompt))
        DesktopRig.main { EnterKeyPref.set(EnterKey.NewLine) }
        waitForIdle()
        press(Key.Enter)
        assertEquals(0, d.count("POST", prompt))
        inPane(Ui.send.text()).performClick()
        until("sent by the button") { d.count("POST", prompt) == 1 }
    }

    @Test fun anEmptyBoxSendsNothing() = runDesktopComposeUiTest(1200, 900) {
        history()
        d.ok("POST", prompt, JSONObject())
        up()
        box().performClick()
        press(Key.Enter)
        assertEquals(0, d.count("POST", prompt))
    }

    @Test fun aRefusedSendPutsTheWordsBackAndSaysWhy() = runDesktopComposeUiTest(1200, 900) {
        history()
        d.on("POST", prompt) { refusal(400, "nope", "That didn't go") }
        up()
        type("Keep these words")
        press(Key.Enter)
        until("refused") { d.count("POST", prompt) == 1 && Core.live?.sending("s1") == false }
        until("given back") { runCatching { onNode(hasTestTag(BOX_TAG) and hasText("Keep these words")).assertExists() }.isSuccess }
        assertEquals("That didn't go", shell.toast)
    }

    @Test fun textWaitingInTheTerminalAsksBeforeTypingOverIt() = runDesktopComposeUiTest(1200, 900) {
        history()
        d.on("POST", prompt) { refusal(409, "typing", "Unsent text") { put("pending", "half a command") } }
        up()
        type("Replace it")
        press(Key.Enter)
        until("the card") { runCatching { inPane(Ui.busy_replace.text()).assertExists() }.isSuccess }
        inPane("half a command").assertIsDisplayed()
        d.ok("POST", prompt, JSONObject().put("session", session("s1", state = "working", title = name)))
        inPane(Ui.busy_replace.text()).performClick()
        until("sent again") { d.count("POST", prompt) == 2 }
        assertTrue(d.calls("POST", prompt).last().json.optBoolean("replace"))
    }

    @Test fun whatsTypedWaitsWhileAnotherSessionIsRead() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name), session("s2", state = "idle", title = "Another one")))
        history()
        d.ok("GET", "/v1/sessions/s2/events", JSONObject().put("events", JSONArray()))
        up()
        type("half a thought")
        choose("Another one")
        onNode(hasTestTag(BOX_TAG) and hasText("half a thought")).assertDoesNotExist()
        choose(name)
        onNode(hasTestTag(BOX_TAG) and hasText("half a thought")).assertExists()
    }

    @Test fun anArrowInTheBoxDoesntChooseAnotherSession() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name), session("s2", state = "idle", title = "Zed")))
        history()
        up()
        type("words")
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        assertEquals("s1", shell.chosen(Core.activeId.orEmpty()))
        inPane(name).assertIsDisplayed()
    }

    // ─── What it asks ───

    private fun bash(id: String, command: String) = approval(id, "s1", summary = "Bash: run") { put("toolName", "Bash").put("input", JSONObject().put("command", command)) }

    @Test fun aToolsAskHasItsAllowOnlyBesideTheWholeCommand() = runDesktopComposeUiTest(1200, 900) {
        val command = "npm run db:migrate -- --env staging"
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "needs_you", title = name)), approvals = listOf(bash("a1", command)))
        d.ok("POST", "/v1/approvals/a1")
        history(user("u1", "Migrate"))
        up()
        inPane("$ $command").assertIsDisplayed()
        // One Allow in the whole pane, the card's: the chat's own approval card, with its cut command, isn't drawn here.
        assertEquals(1, onAllNodes(hasText(Ui.allow.text()) and hasAnyAncestor(hasTestTag(PANE_TAG))).fetchSemanticsNodes().size)
        onNode(hasText(Ui.allow.text()) and hasAnyAncestor(hasTestTag(CHAT_TAG))).assertDoesNotExist()
        onNode(hasText("Always", substring = true) and hasAnyAncestor(hasTestTag(PANE_TAG))).assertDoesNotExist()
        inPane(Ui.allow.text()).performClick()
        until("the answer") { d.count("POST", "/v1/approvals/a1") == 1 }
        assertEquals("allow", d.calls("POST", "/v1/approvals/a1").last().json.getString("decision"))
    }

    private fun question(id: String) = approval(id, "s1", kind = "question") {
        put("toolName", "AskUserQuestion")
        put("questions", JSONArray().put(
            JSONObject().put("question", "Which format?").put("header", "Format").put("multiSelect", false)
                .put("options", JSONArray().put(JSONObject().put("label", "CSV").put("description", "As it is")).put(JSONObject().put("label", "Parquet"))),
        ))
    }

    @Test fun aQuestionIsAnsweredInTheChatWithAMouse() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "needs_you", title = name)), approvals = listOf(question("q1")))
        d.ok("POST", "/v1/approvals/q1")
        history(user("u1", "Export it"))
        up()
        inPane(Ui.desk_ask_question.text()).assertIsDisplayed()
        inChat("Which format?").assertIsDisplayed()
        // On an option's own words, where a selection would start.
        inChat("Parquet").performMouseInput { click() }
        waitForIdle()
        render("chat-question")
        inChat(DesktopStrings.text(Str.send_answer)).performClick()
        until("the answer") { d.count("POST", "/v1/approvals/q1") == 1 }
        val body = d.calls("POST", "/v1/approvals/q1").last().json
        assertEquals("allow", body.getString("decision"))
        assertEquals("Parquet", body.getJSONObject("answers").getString("Which format?"))
    }

    @Test fun aPlanIsReadAndApprovedInTheChat() = runDesktopComposeUiTest(1200, 1000) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "needs_you", title = name)), approvals = listOf(
            approval("p1", "s1", kind = "plan") { put("toolName", "ExitPlanMode").put("plan", "# The plan\n\n1. Stream it\n2. Test it") },
        ))
        d.ok("POST", "/v1/approvals/p1")
        history(user("u1", "Plan it"))
        up()
        inPane(Ui.desk_ask_plan.text()).assertIsDisplayed()
        inChat("Stream it").assertIsDisplayed()
        inChat(DesktopStrings.text(Str.approve_plan)).performClick()
        until("the answer") { d.count("POST", "/v1/approvals/p1") == 1 }
        assertEquals("allow", d.calls("POST", "/v1/approvals/p1").last().json.getString("decision"))
    }

    @Test fun aQuestionInWordsIsRepliedToInTheBox() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "needs_you", title = name) { put("question", "Which branch?") }))
        d.ok("POST", prompt, JSONObject().put("session", session("s1", state = "working", title = name)))
        history()
        up()
        inPane("Which branch?").assertIsDisplayed()
        inPane(Ui.desk_reply_below.text()).assertIsDisplayed()
        type("main")
        press(Key.Enter)
        until("the reply") { d.count("POST", prompt) == 1 }
    }

    // ─── A session going ───

    @Test fun aSessionVanishingWhileReadSaysSoAndChoosesNothingElse() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name), session("s2", state = "idle", title = "Other")))
        history(user("u1", "Gone soon"))
        up()
        inChat("Gone soon").assertIsDisplayed()
        d.push(msg("session.removed") { put("id", "s1") })
        until("it gone") { Core.live?.session("s1") == null }
        waitForIdle()
        inPane(Ui.desk_pane_gone.text()).assertIsDisplayed()
        onNode(hasTestTag(CHAT_TAG)).assertDoesNotExist()
        onNode(hasTestTag(BOX_TAG)).assertDoesNotExist()
    }

    @Test fun theSamePanesFactsAreAtTheStartOfTheChat() = runDesktopComposeUiTest(1200, 900) {
        history(user("u1", "Short"))
        up()
        inChat("/home/dev/invoices").assertIsDisplayed()
        inChat(Ui.desk_pane_phone_only.text()).assertIsDisplayed()
    }

    // ─── What has ended ───

    private fun ended(id: String, title: String) = session(id, state = "ended", title = title)

    @Test fun anEndedSessionOnlyReadsUntilItsWrittenIn() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s2", state = "idle", title = "Still here"), ended("s1", name)))
        history(user("u1", "What was said"), reply("r1", "And its answer"))
        d.ok("POST", prompt, JSONObject().put("session", session("s1", state = "working", title = name)))
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { (Core.live?.sessions?.size ?: 0) >= 2 }
        waitForIdle()
        onNode(hasText(Ui.history.text())).performClick()
        waitForIdle()
        choose(name)
        until("its history") { Core.live?.loadingTranscript("s1") == false && d.count("GET", "/v1/sessions/s1/events") > 0 }
        waitForIdle()
        inChat("What was said").assertIsDisplayed()
        inChat("And its answer").assertIsDisplayed()
        inChat(Ui.desk_pane_in_history.text()).assertIsDisplayed()
        onNode(hasTestTag(BOX_TAG) and hasText(Ui.prompt_hint_ended.text())).assertExists()
        // Reading started nothing.
        assertEquals(0, d.count("POST", "/v1/sessions/s1/resume"))
        assertEquals(0, d.count("POST", prompt))
        inPane(Ui.session_rename.text()).assertDoesNotExist()
        render("chat-history")
        type("Carry on from there")
        press(Key.Enter)
        until("it went") { d.count("POST", prompt) == 1 }
        assertEquals("Carry on from there", d.calls("POST", prompt).last().json.getString("text"))
    }

    @Test fun aSessionThatEndsWhileReadKeepsItsChatAndWhatsTyped() = runDesktopComposeUiTest(1200, 900) {
        history(user("u1", "Still reading this"))
        up()
        type("half a thought")
        inPane(Ui.desk_pane_phone_only.text()).assertExists()
        d.push(msg("session") { put("session", ended("s1", name)) })
        until("it ended") { Core.live?.session("s1")?.phase == com.prudnikweb.hypernova.data.Phase.Ended }
        waitForIdle()
        inChat("Still reading this").assertIsDisplayed()
        onNode(hasTestTag(BOX_TAG) and hasText("half a thought")).assertExists()
        inChat(Ui.desk_pane_in_history.text()).assertExists()
    }

    // ─── Links ───

    private val browsed = java.util.concurrent.CopyOnWriteArrayList<String>()
    private inner class StandDesk : DeskActions {
        val copied = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun openFolder(folder: File) = true
        override fun copy(text: String) { copied += text }
        override fun browse(url: String): Boolean { browsed += url; return true }
    }

    private fun ComposeUiTest.clickLink(text: String) {
        val node = inChat(text, substring = true)
        val layout = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        layout(results)
        val r = results.first()
        val at = r.layoutInput.text.text.indexOf("https://")
        val box = r.getBoundingBox(at + 2)
        node.performMouseInput { click(Offset(box.center.x, box.center.y)) }
        waitForIdle()
    }

    @Test fun aLinkAsksFirstAndOpensInTheBrowser() = runDesktopComposeUiTest(1200, 900) {
        val desk = StandDesk()
        history(reply("r1", "See https://example.com/doc for it."))
        up()
        val before = shell.desk
        shell.desk = desk
        try {
            clickLink("See https://example.com/doc")
            inPane(Ui.link_open_title.text()).assertIsDisplayed()
            assertTrue("nothing yet", browsed.isEmpty())
            render("chat-link")
            inPane(Ui.link_open.text()).performClick()
            until("the browser") { browsed.isNotEmpty() }
            assertEquals("https://example.com/doc", browsed.single())
        } finally {
            shell.desk = before
        }
    }

    @Test fun aLinkCanBeCancelledOrCopiedInstead() = runDesktopComposeUiTest(1200, 900) {
        val desk = StandDesk()
        history(reply("r1", "See https://example.com/doc for it."))
        up()
        val before = shell.desk
        shell.desk = desk
        try {
            clickLink("See https://example.com/doc")
            inPane(Ui.cancel.text()).performClick()
            waitForIdle()
            inPane(Ui.link_open_title.text()).assertDoesNotExist()
            clickLink("See https://example.com/doc")
            inPane(Ui.link_copy.text()).performClick()
            waitForIdle()
            assertEquals("https://example.com/doc", desk.copied.single())
            assertTrue(browsed.isEmpty())
        } finally {
            shell.desk = before
        }
    }

    @Test fun aLinkToADevServerSaysThePreviewIsOnThePhone() = runDesktopComposeUiTest(1200, 900) {
        val desk = StandDesk()
        history(reply("r1", "Running at https://localhost:5173/ now."))
        up()
        val before = shell.desk
        shell.desk = desk
        try {
            clickLink("Running at")
            assertEquals(Ui.desk_preview_on_phone.text(), shell.toast)
            assertTrue("this computer's localhost is another program", browsed.isEmpty())
        } finally {
            shell.desk = before
        }
    }

    // ─── A long session ───

    private fun long(n: Int) = (1..n).map { if (it % 2 == 1) user("u$it", "Prompt number $it", ts = 1_000_000L + it) else reply("r$it", "Reply number $it\n\nWith **more** than one line.", ts = 1_000_000L + it) }

    @Test fun itOpensAtTheEndAndFollowsNewOutput() = runDesktopComposeUiTest(1200, 800) {
        history(long(120))
        up()
        inChat("Reply number 120", substring = true).assertIsDisplayed()
        d.push(msg("event") { put("sessionId", "s1").put("event", reply("r121", "The newest words")) })
        until("it arrived") { Core.live?.transcript("s1")?.any { it.key == "r121" } == true }
        waitUntil("followed", 10_000) { runCatching { inChat("The newest words").assertIsDisplayed() }.isSuccess }
    }

    @Test fun scrollingUpStopsFollowingAndJumpGoesBack() = runDesktopComposeUiTest(1200, 800) {
        history(long(120))
        up()
        onNode(hasTestTag(CHAT_TAG)).performMouseInput { moveTo(center); scroll(-30f) }
        waitForIdle()
        inChat("Reply number 120", substring = true).assertDoesNotExist()
        d.push(msg("event") { put("sessionId", "s1").put("event", reply("r121", "The newest words")) })
        until("it arrived") { Core.live?.transcript("s1")?.any { it.key == "r121" } == true }
        waitForIdle()
        inChat("The newest words").assertDoesNotExist()
        inChat(Ui.jump_latest.text()).assertIsDisplayed()
        inChat(Ui.jump_latest.text()).performClick()
        waitUntil("back at the end", 10_000) { runCatching { inChat("The newest words").assertIsDisplayed() }.isSuccess }
    }

    @Test fun pageUpFromTheScreensKeysMovesTheChat() = runDesktopComposeUiTest(1200, 800) {
        history(long(120))
        up()
        inChat("Reply number 120", substring = true).assertIsDisplayed()
        onRoot().performKeyInput { pressKey(Key.PageUp); pressKey(Key.PageUp); pressKey(Key.PageUp) }
        waitForIdle()
        inChat("Reply number 120", substring = true).assertDoesNotExist()
        inChat(Ui.jump_latest.text()).assertIsDisplayed()
    }

    @Test fun whileScrollingTheTimeShowsOverTheChat() = runDesktopComposeUiTest(1200, 800) {
        val at = System.currentTimeMillis() - 60_000
        history((1..80).map { if (it % 2 == 1) user("u$it", "Prompt number $it", ts = at) else reply("r$it", "Reply number $it", ts = at) })
        up()
        mainClock.autoAdvance = false
        onNode(hasTestTag(CHAT_TAG)).performMouseInput { moveTo(center); scroll(-20f) }
        mainClock.advanceTimeBy(100)
        onNode(hasText("Today ·", substring = true)).assertExists()
        mainClock.autoAdvance = true
    }

    // ─── Sizes ───

    @Test fun aNarrowWindowHasTheChatAndTheBoxWithAWayBack() = runDesktopComposeUiTest(600, 500) {
        history(user("u1", "Narrow"))
        d.ok("POST", prompt, JSONObject().put("session", session("s1", state = "working", title = name)))
        up()
        inChat("Narrow").assertExists()
        inPane(Ui.back.text()).assertIsDisplayed()
        box().assertIsDisplayed()
        type("From a small window")
        press(Key.Enter)
        until("sent") { d.count("POST", prompt) == 1 }
    }

    @Test fun theSmallestWindowStillHasAllowAndTheBoxUnderALongCommand() = runDesktopComposeUiTest(520, 420) {
        val long = "echo " + (1..50).joinToString(" ") { "word$it" }
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "needs_you", title = name)), approvals = listOf(bash("a1", long)))
        history(user("u1", "Echo"))
        up()
        inPane(Ui.allow.text()).assertIsDisplayed()
        box().assertIsDisplayed()
        render("chat-smallest")
    }

    @Test fun a4kWindowAt2xDrawsItAndAClickLands() = runDesktopComposeUiTest(3840, 2160) {
        history(user("u1", "Big"), tool("t1", "Read", "src/a.ts"), tool("t2", "Read", "src/b.ts"), tool("t3", "Read", "src/c.ts"), reply("r1", "**Done** at scale."))
        d.ok("POST", prompt, JSONObject().put("session", session("s1", state = "working", title = name)))
        up(scale = 2f)
        inChat("Big").assertIsDisplayed()
        inChat("USED 3 TOOLS", substring = true).performMouseInput { click() }
        waitForIdle()
        inChat("src/a.ts").assertIsDisplayed()
        type("At 4K")
        press(Key.Enter)
        until("sent") { d.count("POST", prompt) == 1 }
        render("chat-4k")
    }

    // ─── The keys ───

    @Test fun tabGoesFromThePillsToTheBoxNotThroughEveryMessage() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "needs_you", title = name)), approvals = listOf(bash("a1", "ls")))
        history(user("u1", "One"), reply("r1", "Two"), user("u2", "Three"), reply("r2", "Four"))
        up()
        // The top bar's New session, Sessions, History, Statistics, Settings and Pair another, then Deny and Allow.
        repeat(8) {
            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
        }
        inPane(Ui.allow.text()).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        box().assertIsFocused()
    }

    @Test fun escapeInTheBoxLeavesTheBoxAndTheNextLetsGoOfTheSession() = runDesktopComposeUiTest(1200, 900) {
        history()
        up()
        type("keep me")
        press(Key.Escape)
        assertEquals("s1", shell.chosen(Core.activeId.orEmpty()))
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(null, shell.chosen(Core.activeId.orEmpty()))
    }

    // ─── Settings ───

    @Test fun settingsSaysWhatEnterDoesAndKeepsIt() = runDesktopComposeUiTest(1200, 900) {
        history()
        up()
        onNode(hasText(Ui.desk_settings.text())).performClick()
        waitForIdle()
        onNode(hasText(Ui.st_enter_newline.text())).performScrollTo().performClick()
        waitForIdle()
        assertEquals(EnterKey.NewLine, EnterKeyPref.state().value)
        assertEquals(EnterKey.NewLine.name, DesktopRig.platform.storage.prefs("hypernova_typing").getString("enter_key", null))
    }

    // ─── Earlier ───

    @Test fun showEarlierBringsTheStartAndKeepsThePlace() = runDesktopComposeUiTest(1200, 800) {
        val all = long(600)
        d.on("GET", "/v1/sessions/s1/events") { r ->
            val limit = r.query.orEmpty().substringAfter("limit=").substringBefore('&').toInt()
            json(200, JSONObject().put("events", JSONArray(all.takeLast(limit))))
        }
        up()
        until("more to load") { Core.live?.hasEarlier("s1") == true }
        waitForIdle()
        repeat(40) {
            onNode(hasTestTag(CHAT_TAG)).performMouseInput { moveTo(center); scroll(-30f) }
            waitForIdle()
        }
        inChat(DesktopStrings.text(Str.earlier_show)).assertIsDisplayed()
        inChat("Prompt number 401", substring = true).assertExists()
        inChat(DesktopStrings.text(Str.earlier_show)).performClick()
        until("the earlier ones") { (Core.live?.transcript("s1")?.size ?: 0) > 200 }
        waitForIdle()
        inChat("Prompt number 401", substring = true).assertExists()
    }

    /** Not a benchmark: a JVM with no GPU. It catches a pass over the whole history, which would show at this length. */
    @Test fun aLongSessionScrollsWithNoPassOverTheWholeOfIt() = runDesktopComposeUiTest(1200, 800) {
        history(long(200))
        up()
        val chat = onNode(hasTestTag(CHAT_TAG))
        val started = System.nanoTime()
        repeat(40) {
            chat.performMouseInput { moveTo(center); scroll(-3f) }
            waitForIdle()
        }
        val perStepMs = (System.nanoTime() - started) / 1_000_000 / 40
        println("chat scroll: $perStepMs ms a step, in the test scene")
        assertTrue("$perStepMs ms a step", perStepMs < 2_000)
    }

    // ─── Secrets ───

    @Test fun whatsSaidAndSentStaysOutOfTheLogs() = runDesktopComposeUiTest(1200, 900) {
        val secret = "sk-ant-api03-PLANTEDabcdefghijklmnopqrstuvwxyz0123456789"
        val root = Files.createTempDirectory("chat-log").toFile()
        val logger = DebugLogger(File(root, "logs"), DesktopRig.platform.storage.prefs("chat-test-log"), DebugHead("1.2.3", "test", "Test OS", "Test Model"))
        logger.turnOn()
        DebugLog.use(logger)
        val err = ByteArrayOutputStream()
        val before = System.err
        System.setErr(PrintStream(err, true))
        try {
            history(user("u1", "My key is $secret"), reply("r1", "I won't repeat $secret here"))
            d.ok("POST", prompt, JSONObject().put("session", session("s1", state = "working", title = name)))
            up()
            type("Use $secret please")
            press(Key.Enter)
            until("sent") { d.count("POST", prompt) == 1 }
            d.push(msg("event") { put("sessionId", "s1").put("event", reply("r2", "PLANTEDREPLY $secret")) })
            until("it arrived") { Core.live?.transcript("s1")?.any { it.key == "r2" } == true }
            waitForIdle()
            logger.flush()
            val log = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
            assertTrue("it is logging: $log", log.contains("POST /v1/sessions/s1/prompt"))
            assertFalse(log, log.contains("PLANTED"))
            assertFalse(err.toString(), err.toString().contains("PLANTED"))
        } finally {
            System.setErr(before)
            DebugLog.use(null)
        }
    }
}
