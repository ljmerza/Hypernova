@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import android.content.ClipboardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Question
import com.prudnikweb.hypernova.data.Resume
import com.prudnikweb.hypernova.data.SendStatus
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.session.QuickPromptChips
import com.prudnikweb.hypernova.ui.session.QuickPrompts
import com.prudnikweb.hypernova.ui.session.ResumeLine
import com.prudnikweb.hypernova.ui.session.SessionScreen
import com.prudnikweb.hypernova.ui.session.SessionUsage
import com.prudnikweb.hypernova.ui.session.Todo
import com.prudnikweb.hypernova.ui.session.TodoChecklist
import com.prudnikweb.hypernova.ui.session.TodoStatus
import com.prudnikweb.hypernova.ui.session.WaitingMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A session's chat beyond its rows (FEATURES sections 4, 6 and 7): the message menu (C10) and
 * Rewind to here (C9), Show earlier (C4), Compact (C7), the to-do list (C8), carrying on after a
 * limit (C13) and after the away stop (Y4), Markdown (C2), the plan and question cards (A3, A4),
 * quick prompts (Q9), a waiting prompt's menu (Q4) and prompts in Claude Code's queue (Q5).
 */
@RunWith(RobolectricTestRunner::class)
class ChatMoreTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    /** A [FakeHub] with the calls these screens make that it doesn't record. */
    private class ChatHub(val base: FakeHub, var earlier: Boolean = false) : Hub by base {
        override fun hasEarlier(id: String) = earlier
        override fun loadEarlier(id: String) { base.calls += "loadEarlier($id)" }
        override suspend fun autoContinue(sessionId: String, on: Boolean): Result<Unit> {
            base.calls += "autoContinue($sessionId, $on)"
            return Result.success(Unit)
        }
        override suspend fun continueAfterLimit(sessionId: String): Result<String> {
            base.calls += "continueAfterLimit($sessionId)"
            return Result.success(sessionId)
        }
        override fun editWaiting(waitingId: String, text: String): Boolean {
            base.calls += "editWaiting($waitingId, $text)"
            return true
        }
        override fun removeWaiting(waitingId: String) { base.calls += "removeWaiting($waitingId)" }
    }

    @Before fun setUp() {
        resetPrefs()
        // ChatRepo (Rewind) asks Core for the computer: none is paired here.
        Core.init(RuntimeEnvironment.getApplication())
    }

    private val conversation = listOf(
        Item.User("u1", "Sketch the lexer"),
        Item.Assistant("a1", "Here is a **sketch**."),
        Item.User("u2", "Now make it faster"),
        Item.Assistant("a2", "Done."),
    )

    private fun chat(hub: Hub, id: String, usage: SessionUsage? = null) {
        rule.setContent { Harness(spy) { SessionScreen(hub, id, onBack = {}, usageOf = { _, _ -> usage }) } }
        rule.waitForIdle()
    }

    /** A long-press on the message that says [text] (its menu is on the bubble around the words). */
    private fun longPress(text: String) {
        rule.onAllNodes(hasAnyDescendant(hasText(text, substring = true)) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick), useUnmergedTree = true)
            .onLast().performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
    }

    // ─── C10, Q9: the message menu ───

    @Config(qualifiers = "+h1600dp")
    @Test fun aMessagesMenuCopiesQuotesAndSavesItAsAQuickPrompt() {
        val hub = FakeHub(transcripts = mapOf(Samples.ZETA to conversation))
        chat(hub, Samples.ZETA)
        longPress("Sketch the lexer")
        for (a in listOf(R.string.chat_copy, R.string.chat_quote, R.string.chat_read, R.string.chat_select)) rule.onNodeWithText(str(a)).assertExists()
        // No Save as quick prompt: nothing on the chat shows quick prompts any more.
        rule.onNodeWithText(str(R.string.chat_save_prompt)).assertDoesNotExist()
        // The first message has nothing before it to go back to.
        rule.onNodeWithText(str(R.string.chat_rewind)).assertDoesNotExist()
        rule.snap("chat_message_menu")
        rule.onNodeWithText(str(R.string.chat_copy)).performClick()
        rule.waitForIdle()
        assertEquals("Sketch the lexer", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())

        // Claude's reply: Quote puts it in the box as a quote; no Rewind for it.
        longPress("Here is a")
        rule.onNodeWithText(str(R.string.chat_quote)).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertTextContains("> Here is a **sketch**.", substring = true)
        assertTrue("nothing sent", hub.calls.none { it.startsWith("send") })
    }

    // ─── C9: Rewind to here ───

    @Test fun rewindAsksFirstSayingWhatGoesAndSaysWhenItCant() {
        chat(FakeHub(transcripts = mapOf(Samples.ZETA to conversation)), Samples.ZETA)
        longPress("Now make it faster")
        rule.onNodeWithText(str(R.string.chat_rewind)).performClick()
        rule.waitForIdle()
        val yours = context.resources.getQuantityString(R.plurals.chat_rewind_yours, 1, 1)
        val lost = str(R.string.chat_rewind_both, yours, context.resources.getQuantityString(R.plurals.chat_rewind_replies, 1, 1))
        rule.onNodeWithText(str(R.string.chat_rewind_body, lost)).assertExists()
        rule.onNodeWithText(str(R.string.chat_rewind_files)).assertExists()
        rule.snap("chat_rewind_confirm")
        // No computer to ask here: it says so, and nothing moved.
        rule.onNodeWithText(str(R.string.chat_rewind_go)).performClick()
        rule.waitUntil(5_000) { spy.toasts.isNotEmpty() }
        assertEquals(listOf(str(R.string.chat_err_mac)), spy.toasts)
        assertTrue(spy.opened.isEmpty())
    }

    // ─── C4: Show earlier ───

    @Test fun aLongSessionOffersItsStartAndAsksForIt() {
        val base = FakeHub(transcripts = mapOf(Samples.ZETA to conversation))
        val hub = ChatHub(base, earlier = true)
        chat(hub, Samples.ZETA)
        rule.onNodeWithText(str(R.string.earlier_show)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("loadEarlier(${Samples.ZETA})"), base.calls.filter { it.startsWith("loadEarlier") })
    }

    @Test fun aSessionThatHasItAllOffersNothingEarlier() {
        chat(FakeHub(transcripts = mapOf(Samples.ZETA to conversation)), Samples.ZETA)
        rule.onNodeWithText(str(R.string.earlier_show)).assertDoesNotExist()
    }

    // ─── C7: Compact at 85% ───

    @Test fun aNearlyFullContextOffersCompactInOneTap() {
        val hub = FakeHub(transcripts = mapOf(Samples.ZETA to conversation))
        val full = SessionUsage("claude-opus-5-5", "Opus 5.5", 180_000, 200_000, 90, true, 100_000, 5_000, 70_000, 5_000, 0, null, false)
        chat(hub, Samples.ZETA, usage = full)
        rule.onNodeWithText(str(R.string.chat_compact)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("send(${Samples.ZETA}, /compact)"), hub.calls.filter { it.startsWith("send") })
        // A second tap at once is the same tap.
        rule.onNodeWithText(str(R.string.chat_compact)).performClick()
        rule.waitForIdle()
        assertEquals(1, hub.calls.count { it.startsWith("send") })
    }

    @Test fun aContextWithRoomHasNoCompact() {
        val roomy = SessionUsage("claude-opus-5-5", "Opus 5.5", 84_000, 200_000, 42, true, 12_000, 3_400, 60_000, 8_000, 0, null, false)
        chat(FakeHub(transcripts = mapOf(Samples.ZETA to conversation)), Samples.ZETA, usage = roomy)
        rule.onNodeWithText(str(R.string.chat_compact)).assertDoesNotExist()
    }

    // ─── C8: the to-do list ───

    @Test fun theToDoListShowsWhatsDoneWhatsUnderWayAndWhatsLeft() {
        val todos = listOf(
            Todo("1", "Trim the input", "Trimming the input", TodoStatus.Done),
            Todo("2", "Run the tests", "Running the tests", TodoStatus.Active),
            Todo("3", "Update the docs", "Updating the docs", TodoStatus.Pending),
        )
        rule.setContent { Harness(spy) { TodoChecklist(todos) } }
        rule.onNodeWithText("Trim the input", substring = true).assertExists()
        // The one under way reads as what it's doing.
        rule.onNodeWithText("Running the tests", substring = true).assertExists()
        rule.onNodeWithText("Update the docs", substring = true).assertExists()
        rule.snap("chat_todo_list")
    }

    // ─── C13, Y4: carrying on ───

    @Test fun carryingOnByItselfCanBeTurnedOffOrDoneNow() {
        val base = FakeHub()
        val hub = ChatHub(base)
        val s = Samples.sessions().first { it.id == Samples.MID }
        val armed = Resume(Resume.ARMED, at = null, byClaude = false, canCancel = true, canArm = false, why = null)
        val moved = mutableListOf<String>()
        rule.setContent { Harness(spy) { ResumeLine(hub, s, armed, amber = false, resetsAt = null, onMoved = { moved += it }) } }
        rule.onNodeWithText(str(R.string.resume_carries_on_soon)).assertExists()
        rule.snap("chat_resume_armed")
        rule.onNodeWithText(str(R.string.resume_dont)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.resume_continue_now)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("autoContinue(${Samples.MID}, false)", "continueAfterLimit(${Samples.MID})"), base.calls)
        assertEquals(listOf(Samples.MID), moved)
    }

    @Test fun carryingOnByItselfTurnedOffCanBeTurnedBackOn() {
        val base = FakeHub()
        val hub = ChatHub(base)
        val s = Samples.sessions().first { it.id == Samples.MID }
        val off = Resume(Resume.OFF, at = null, byClaude = false, canCancel = false, canArm = true, why = null)
        rule.setContent { Harness(spy) { ResumeLine(hub, s, off, amber = false, resetsAt = null) } }
        rule.onNodeWithText(str(R.string.resume_arm_plain)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("autoContinue(${Samples.MID}, true)"), base.calls)
    }

    @Test fun aSessionStoppedWhileAwayContinues() {
        val hub = FakeHub(
            sessions = Samples.sessions().map { if (it.id == Samples.ZETA) it.copy(stoppedForAway = true) else it },
            transcripts = mapOf(Samples.ZETA to conversation),
        )
        chat(hub, Samples.ZETA)
        rule.onNodeWithText(str(R.string.paused_title)).assertExists()
        rule.onNodeWithText(str(R.string.continue_session)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("resume(${Samples.ZETA})"), hub.calls.filter { it.startsWith("resume") })
    }

    // ─── C2: Markdown ───

    @Config(qualifiers = "+h1400dp")
    @Test fun aReplysMarkdownIsDrawnNotShown() {
        val reply = """
            |## The plan
            |
            || File | Lines |
            ||---|---|
            || parser.ts | 12 |
            |
            |```ts
            |export const parse = (s: string) => s.trim()
            |```
        """.trimMargin()
        chat(FakeHub(transcripts = mapOf(Samples.ZETA to listOf(Item.User("u1", "How?"), Item.Assistant("a1", reply)))), Samples.ZETA)
        rule.onNodeWithText("The plan", substring = true).assertExists()
        rule.onNodeWithText("## The plan", substring = true).assertDoesNotExist()
        rule.onNodeWithText("|---|", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("export const parse", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("```", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.snap("chat_markdown")
    }

    // ─── A3, A4: a plan, and questions ───

    private fun planChat(): FakeHub {
        val plan = Item.Approval("ap-plan", tool = "ExitPlanMode", detail = "Plan: split the parser", reason = "", approvalId = "ap-plan", kind = ApprovalKind.Plan, canAlways = false, plan = "1. Split the lexer out\n2. Trim the input")
        val hub = FakeHub(
            sessions = Samples.sessions().map { if (it.id == Samples.ZETA) it.copy(state = SessionState.NeedsYou) else it },
            transcripts = mapOf(Samples.ZETA to listOf(Item.User("u1", "Plan the refactor"), plan)),
            approvals = emptyMap(),
        )
        chat(hub, Samples.ZETA)
        return hub
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun aPlanIsApproved() {
        val hub = planChat()
        rule.onNodeWithText("Split the lexer out", substring = true).assertExists()
        rule.snap("chat_plan_card")
        rule.onNodeWithText(str(R.string.approve_plan)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("answer(${Samples.ZETA}, ap-plan, ${Answer.ApprovePlan})"), hub.calls)
    }

    @Test fun aPlanCanBeKeptPlanning() {
        val hub = planChat()
        rule.onNodeWithText(str(R.string.keep_planning)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("answer(${Samples.ZETA}, ap-plan, ${Answer.KeepPlanning})"), hub.calls)
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun aQuestionIsAnsweredWithTheOptionPicked() {
        val q = Question("Which version comes first?", "Version", multiSelect = false, options = listOf(Question.Option("1.0", "The stable one"), Question.Option("2.0", "The new parser")))
        val ask = Item.Approval("ap-q", tool = "AskUserQuestion", detail = "Which version?", reason = "", approvalId = "ap-q", kind = ApprovalKind.Question, canAlways = false, questions = listOf(q))
        val hub = FakeHub(
            sessions = Samples.sessions().map { if (it.id == Samples.ZETA) it.copy(state = SessionState.NeedsYou) else it },
            transcripts = mapOf(Samples.ZETA to listOf(Item.User("u1", "Ship it"), ask)),
            approvals = emptyMap(),
        )
        chat(hub, Samples.ZETA)
        rule.onNodeWithText("Which version comes first?", substring = true).assertExists()
        rule.onNodeWithText("2.0").performClick()
        rule.snap("chat_question_card")
        rule.onNodeWithText(str(R.string.send_answer)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("answer(${Samples.ZETA}, ap-q, ${Answer.Answers(mapOf("Which version comes first?" to "2.0"))})"), hub.calls)
    }

    // ─── Q9: quick prompts ───

    // The chips aren't on the chat since "no quick prompts" (8a36f54), and Save as quick prompt left
    // the message menu (2026-11-01); the chips' tap is kept here, with no picture (their list is the
    // phone's, so it read differently by which tests ran first).
    @Test fun aQuickPromptIsSentInOneTap() {
        val sent = mutableListOf<String>()
        rule.setContent { Harness(spy) { QuickPromptChips(onSend = { sent += it }) } }
        val first = QuickPrompts.list(context).first()
        rule.onNodeWithText(first).performClick()
        rule.waitForIdle()
        assertEquals(listOf(first), sent)
    }

    @Test fun aQuickPromptIsEditedWithALongPressAndTheListIsKept() {
        rule.setContent { Harness(spy) { QuickPromptChips(onSend = {}) } }
        // The first chip (the row scrolls sideways: the last ones aren't on screen yet).
        val first = QuickPrompts.list(context).first()
        rule.onNodeWithText(first).performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("Run the linter")
        rule.onNodeWithText(str(R.string.save)).performClick()
        rule.waitForIdle()
        assertEquals("Run the linter", QuickPrompts.list(context).first())
        rule.onNodeWithText("Run the linter").assertExists()
        // Saved again, a prompt moves to the front rather than coming twice.
        assertTrue(QuickPrompts.add(context, "Run the linter"))
        assertEquals("Run the linter", QuickPrompts.list(context).first())
        assertEquals(1, QuickPrompts.list(context).count { it == "Run the linter" })
    }

    // ─── Q4: a waiting prompt's menu ───

    @Test fun aWaitingPromptsMenuEditsOrRemovesIt() {
        val base = FakeHub()
        val hub = ChatHub(base)
        val w = Item.User("local-1", "when you're back", status = SendStatus.WaitingMac, waitingId = "w1")
        var open by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { Harness(spy) { if (open) WaitingMenu(hub, w, onDismiss = { open = false }, sessionId = Samples.ZETA) } }
        rule.onNodeWithText(str(R.string.waiting_edit)).assertExists()
        rule.snap("chat_waiting_menu")
        rule.onNodeWithText(str(R.string.waiting_remove)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("removeWaiting(w1)"), base.calls)
        open = true
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.waiting_edit)).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("when you are back, run the tests")
        rule.onNodeWithText(str(R.string.waiting_save)).performClick()
        rule.waitForIdle()
        assertEquals("editWaiting(w1, when you are back, run the tests)", base.calls.last())
    }

    // ─── Q5: a prompt in Claude Code's queue, drawn ───

    @Test fun aQueuedPromptSaysItWaitsForTheStepToFinish() {
        val queued = Item.User("queued:q:2", "Check the lexer as well", status = SendStatus.QueuedStep)
        chat(FakeHub(transcripts = mapOf(Samples.MID to conversation + queued)), Samples.MID)
        rule.onNodeWithText("Check the lexer as well", substring = true).assertExists()
        rule.onNodeWithText(str(R.string.queued_label), substring = true, ignoreCase = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(str(R.string.queued_hint_step), substring = true, useUnmergedTree = true).assertExists()
        rule.snap("chat_queued_prompt")
    }
}
