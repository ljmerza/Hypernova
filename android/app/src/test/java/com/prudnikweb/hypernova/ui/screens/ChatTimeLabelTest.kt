@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ChatTimes
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.WEvent
import com.prudnikweb.hypernova.ui.session.ChatClock
import com.prudnikweb.hypernova.ui.session.exact
import com.prudnikweb.hypernova.ui.session.exactSaid
import com.prudnikweb.hypernova.ui.session.quiet
import com.prudnikweb.hypernova.ui.session.scrolled
import com.prudnikweb.hypernova.ui.session.ChatTimeLabel
import com.prudnikweb.hypernova.ui.session.QUIET_AFTER_MS
import com.prudnikweb.hypernova.ui.session.QUIET_SHOW_MS
import com.prudnikweb.hypernova.ui.session.SessionScreen
import com.prudnikweb.hypernova.ui.session.TIME_HOLD_MS
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.MotionPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/** The time over the chat (session/ChatTimeLabel.kt), in the chat: when it shows, what it says, and what it leaves alone. */
@RunWith(RobolectricTestRunner::class)
class ChatTimeLabelTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val zoneBefore = TimeZone.getDefault()
    private val res get() = context.resources
    private val clock get() = ChatClock(Locale.US, is24 = false)

    private companion object {
        const val ID = "times"
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }

    @Before fun setUp() {
        resetPrefs()
        // The pictures' words: a fixed zone, and dates long past so "this year" never comes round.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun tearDown() = TimeZone.setDefault(zoneBefore)

    private fun session(state: SessionState = SessionState.Idle): Session = Samples.session(ID, "Times", state, 0)

    /** Monday, March 4, 2024, 9:14 PM, the first of [messages], a day apart. */
    private val long = ZonedDateTime.of(2024, 3, 4, 21, 14, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun text(i: Int) = "Message $i: " + "words that wrap across a few lines of the chat ".repeat(4)

    /** [n] messages, the first at [first], [apart] from each other. */
    private fun messages(n: Int, first: Long, apart: Long = DAY): List<WEvent> =
        List(n) { i -> if (i % 2 == 0) WEvent.User("m$i", first + i * apart, text(i)) else WEvent.Assistant("m$i", first + i * apart, text(i), false) }

    private fun items(events: List<WEvent>): List<Item> = events.map {
        when (it) {
            is WEvent.User -> Item.User(it.id, it.text)
            is WEvent.Assistant -> Item.Assistant(it.id, it.text)
            else -> error("only messages here")
        }
    }

    private fun hub(events: List<WEvent>, extra: List<Item> = emptyList(), state: SessionState = SessionState.Idle) = FakeHub(
        sessions = listOf(session(state)),
        transcripts = mapOf(ID to items(events) + extra),
        approvals = emptyMap(),
    ).also { it.timesMap = mapOf(ID to ChatTimes.of(events)) }

    private fun chat(hub: FakeHub, motion: MotionPrefs? = null) {
        rule.setContent {
            Harness(spy) {
                if (motion == null) SessionScreen(hub, ID, onBack = {}, usageOf = { _, _ -> null })
                else CompositionLocalProvider(LocalMotion provides motion) { SessionScreen(hub, ID, onBack = {}, usageOf = { _, _ -> null }) }
            }
        }
        rule.waitForIdle()
    }

    /** Past the quiet line's little wait, with it showing if it's going to. */
    private fun settleOpening() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    private fun past(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun quietWords() = "Last message"
    private fun label() = rule.onNode(hasText(" · ", substring = true), useUnmergedTree = false)
    private fun labels() = rule.onAllNodes(hasText(" · ", substring = true)).fetchSemanticsNodes().size

    private fun dragAndHold(by: Float = 400f) = rule.onRoot().performTouchInput {
        down(center)
        moveBy(Offset(0f, by / 4))
        advanceEventTime(50)
        moveBy(Offset(0f, by / 4))
        advanceEventTime(50)
        moveBy(Offset(0f, by / 4))
        advanceEventTime(50)
        moveBy(Offset(0f, by / 4))
    }

    /** Stopped dead before the finger lifts, so no fling goes on after it. */
    private fun lift() = rule.onRoot().performTouchInput {
        advanceEventTime(500)
        moveBy(Offset.Zero)
        advanceEventTime(500)
        up()
    }

    // ─── While scrolling ───

    @Test fun whileScrollingItSaysTheTimeOfTheTopRowAndGoesASecondAfter() {
        val events = messages(30, long)
        chat(hub(events))
        settleOpening()
        assertEquals("nothing before a scroll", 0, labels())

        dragAndHold()
        rule.waitForIdle()
        // The top row's: the first whose end is below where the list starts, which is where the label sits.
        val shown = label().fetchSemanticsNode().boundsInRoot
        val top = events.indices.first {
            val nodes = rule.onAllNodesWithText("Message $it:", substring = true).fetchSemanticsNodes()
            nodes.isNotEmpty() && nodes.first().boundsInRoot.bottom > shown.top
        }
        label().assert(hasText(clock.scrolled(res, events[top].ts, System.currentTimeMillis())))
        rule.snap("chat_time_label")

        // Held still with the finger down, it stays.
        past(3_000)
        label().assertExists()

        lift()
        past(TIME_HOLD_MS - 100)
        label().assertExists()
        past(200)
        assertEquals("gone a second after the scroll stopped", 0, labels())
    }

    @Test fun theWordsMoveOnAsTheChatDoes() {
        val events = messages(30, long)
        chat(hub(events))
        settleOpening()
        dragAndHold(200f)
        rule.waitForIdle()
        val first = label().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        rule.onRoot().performTouchInput { repeat(6) { moveBy(Offset(0f, 400f)); advanceEventTime(50) } }
        rule.waitForIdle()
        val later = label().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        assertTrue("$first, then $later", first != later)
    }

    @Test fun thePhonesTwentyFourHourClockIsUsed() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        try {
            chat(hub(messages(30, long)))
            settleOpening()
            dragAndHold()
            rule.waitForIdle()
            val said = label().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertTrue(said, said.endsWith("21:14"))
        } finally {
            Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, null)
        }
    }

    @Test fun theListMovingByItselfSaysNothing() {
        val events = messages(30, System.currentTimeMillis() - 30 * 60_000, apart = 60_000)
        val h = hub(events)
        chat(h)
        settleOpening()
        // A new reply comes in at the end, and the chat follows it.
        val more = events + WEvent.Assistant("new", System.currentTimeMillis(), text(99), false)
        h.transcriptMap = mapOf(ID to items(more))
        h.timesMap = mapOf(ID to ChatTimes.of(more))
        rule.mainClock.autoAdvance = false
        repeat(40) {
            rule.mainClock.advanceTimeBy(32)
            assertEquals("no label while it follows, at step $it", 0, labels())
        }
        rule.mainClock.autoAdvance = true
    }

    @Test fun underReduceMotionItGoesAtOnceAndElseItFades() {
        val fading = MotionPrefs(reduced = false)
        chat(hub(messages(30, long)), fading)
        settleOpening()
        dragAndHold()
        rule.waitForIdle()
        lift()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(TIME_HOLD_MS + 100)
        // Fading, not gone.
        assertEquals(1, labels())
        rule.mainClock.advanceTimeBy(1_000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(0, labels())
    }

    // ─── It takes nothing from the chat ───

    private fun rowsNow(n: Int): Map<Int, Rect> = (0 until n).mapNotNull { i ->
        rule.onAllNodesWithText("Message $i:", substring = true).fetchSemanticsNodes().firstOrNull()?.let { i to it.boundsInRoot }
    }.toMap()

    @Test fun nothingInTheChatMovesWhenItShowsOrGoes() {
        val events = messages(30, long)
        chat(hub(events))
        rule.waitForIdle()
        // Before it says anything...
        val before = rowsNow(events.size)
        assertTrue(before.isNotEmpty())
        // ...with it up (a session this old says so on opening)...
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertExists()
        assertEquals("while it shows", before, rowsNow(events.size))
        // ...and after it.
        past(QUIET_SHOW_MS + 1_000)
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
        assertEquals("after it goes", before, rowsNow(events.size))
    }

    @Test fun theBarsDontMoveForItEither() {
        chat(hub(messages(30, long)))
        rule.waitForIdle()
        val title = rule.onNodeWithText("Times").fetchSemanticsNode().boundsInRoot
        val box = rule.onNodeWithText(str(R.string.prompt_hint)).fetchSemanticsNode().boundsInRoot
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertExists()
        assertEquals(title, rule.onNodeWithText("Times").fetchSemanticsNode().boundsInRoot)
        assertEquals(box, rule.onNodeWithText(str(R.string.prompt_hint)).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun itSitsBelowTheTopBar() {
        chat(hub(messages(30, long)))
        settleOpening()
        val line = rule.onNodeWithText(quietWords(), substring = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithText("Times").fetchSemanticsNode().boundsInRoot
        assertTrue("below the title: ${line.top} against ${title.bottom}", line.top >= title.bottom)
    }

    @Test fun aTouchOnItGoesToTheMessageUnderIt() {
        chat(hub(messages(30, long)))
        settleOpening()
        val line = rule.onNodeWithText(quietWords(), substring = true).fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { longClick(line.center) }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.chat_copy)).assertExists()
    }

    @Test fun aDragStartedOnItScrollsTheChat() {
        chat(hub(messages(30, long)))
        settleOpening()
        val line = rule.onNodeWithText(quietWords(), substring = true).fetchSemanticsNode().boundsInRoot
        val before = rowsNow(30)
        rule.onRoot().performTouchInput {
            down(line.center)
            repeat(4) { moveBy(Offset(0f, 100f)); advanceEventTime(50) }
        }
        rule.waitForIdle()
        assertTrue("the chat moved", before != rowsNow(30))
    }

    @Test fun talkBackDoesntHearIt() {
        chat(hub(messages(30, long)))
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    /** The list's own items aren't recomposed by it: each row composes once however far the words go. */
    @Test fun aScrollRecomposesNothingOfTheListForIt() {
        val hub = hub(messages(200, long))
        val rows = HashMap<Int, Int>()
        var host = 0
        rule.setContent {
            Harness(spy) {
                val list = rememberLazyListState(initialFirstVisibleItemIndex = 199)
                SideEffect { host++ }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        items((0 until 200).toList(), key = { "m$it" }) { i ->
                            SideEffect { rows[i] = (rows[i] ?: 0) + 1 }
                            HText("Row $i", Modifier.height(90.dp))
                        }
                    }
                    ChatTimeLabel(hub, ID, 0, list, following = { false }, top = { 0 }, modifier = Modifier.align(Alignment.TopCenter))
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            down(center)
            // A row or more each step, so the top row, and the words, change nearly every frame.
            repeat(30) { moveBy(Offset(0f, 300f)); advanceEventTime(16) }
        }
        rule.waitForIdle()
        label().assertExists()
        assertTrue("it went through days of them: ${rows.size} rows", rows.size > 50)
        assertEquals("the screen around the list, once", 1, host)
        assertEquals("every row composed once: ${rows.filterValues { it > 1 }}", emptyMap<Int, Int>(), rows.filterValues { it > 1 })
    }

    // ─── A quiet session, on opening ───

    @Test fun quietPastTheThresholdItSaysSoForAFewSeconds() {
        val now = System.currentTimeMillis()
        chat(hub(messages(4, now - 2 * DAY - 3 * HOUR, apart = HOUR)))
        settleOpening()
        val expected = clock.quiet(res, now - 2 * DAY, System.currentTimeMillis())!!
        rule.onNodeWithText(expected).assertExists()
        // It came up 500 ms in, at most a frame ago, and goes QUIET_SHOW_MS after that.
        past(QUIET_SHOW_MS - 400)
        rule.onNodeWithText(expected).assertExists()
        past(400)
        rule.onNodeWithText(expected).assertDoesNotExist()
    }

    @Test fun justPastTheThresholdItShows() {
        val now = System.currentTimeMillis()
        chat(hub(listOf(WEvent.User("m0", now - QUIET_AFTER_MS - 60_000, text(0)))))
        settleOpening()
        rule.onNodeWithText("Last message 3 hours ago").assertExists()
    }

    @Test fun underTheThresholdItDoesnt() {
        val now = System.currentTimeMillis()
        chat(hub(listOf(WEvent.User("m0", now - QUIET_AFTER_MS + 60_000, text(0)))))
        settleOpening()
        past(QUIET_SHOW_MS)
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
    }

    @Test fun openedSecondsAfterTheLastMessageItSaysNothing() {
        val now = System.currentTimeMillis()
        chat(hub(listOf(WEvent.User("m0", now - 3 * HOUR, text(0)), WEvent.Assistant("m1", now - 5_000, text(1), false))))
        settleOpening()
        past(QUIET_SHOW_MS)
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
    }

    @Test fun aSessionAtWorkIsntQuiet() {
        val now = System.currentTimeMillis()
        chat(hub(listOf(WEvent.User("m0", now - 2 * DAY, text(0))), state = SessionState.Working))
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
    }

    @Test fun aSessionWithAgentsAtWorkIsntQuiet() {
        val now = System.currentTimeMillis()
        val h = hub(listOf(WEvent.User("m0", now - 2 * DAY, text(0))))
        h.sessionList = h.sessionList.map { it.copy(agentsRunning = 2) }
        chat(h)
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
    }

    @Test fun aHistoryStillLoadingWaits() {
        val now = System.currentTimeMillis()
        var loading by androidx.compose.runtime.mutableStateOf(true)
        val base = hub(listOf(WEvent.User("m0", now - 2 * DAY, text(0))))
        val h = object : com.prudnikweb.hypernova.data.Hub by base {
            override fun loadingTranscript(id: String) = loading
        }
        rule.setContent { Harness(spy) { SessionScreen(h, ID, onBack = {}, usageOf = { _, _ -> null }) } }
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
        loading = false
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertExists()
    }

    @Test fun whileTheServerIsAwayItSaysNothing() {
        val now = System.currentTimeMillis()
        val h = hub(listOf(WEvent.User("m0", now - 2 * DAY, text(0))))
        h.linkState = com.prudnikweb.hypernova.data.Link(com.prudnikweb.hypernova.data.LinkState.Reconnecting)
        chat(h)
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
    }

    @Test fun aNewerMessageTakesTheLineAway() {
        val now = System.currentTimeMillis()
        val events = listOf<WEvent>(WEvent.User("m0", now - 2 * DAY, text(0)))
        val h = hub(events)
        chat(h)
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertExists()
        val more = events + WEvent.Assistant("m1", System.currentTimeMillis(), text(1), false)
        h.timesMap = mapOf(ID to ChatTimes.of(more))
        rule.waitForIdle()
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
    }

    @Test fun aScrollTakesTheQuietLinesPlace() {
        chat(hub(messages(30, long)))
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertExists()
        dragAndHold()
        rule.waitForIdle()
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
        label().assertExists()
    }

    @Test fun itSaysSoOnceForAnOpeningNotAgainOnComingBackAndAgainOnTheNextOpening() {
        val h = hub(messages(4, long))
        var opening by androidx.compose.runtime.mutableIntStateOf(1)
        val saved = androidx.compose.runtime.saveable.SaveableStateRegistry(emptyMap()) { true }
        var shown by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            Harness(spy) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.runtime.saveable.LocalSaveableStateRegistry provides saved) {
                    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                    if (shown) holder.SaveableStateProvider("chat") {
                        SessionScreen(h, ID, onBack = {}, opening = opening, usageOf = { _, _ -> null })
                    }
                }
            }
        }
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertExists()
        past(QUIET_SHOW_MS + 1_000)
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
        // A screen over it, and back: the same opening.
        shown = false
        rule.waitForIdle()
        shown = true
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
        // Closed, and opened again.
        opening = 2
        settleOpening()
        rule.onNodeWithText(quietWords(), substring = true).assertExists()
    }

    // ─── An item with no real time ───

    @Test fun aChatWithNoRealTimeSaysNothingWhileScrolling() {
        val h = hub(messages(30, long)).also { it.timesMap = emptyMap() }
        chat(h)
        settleOpening()
        dragAndHold()
        rule.waitForIdle()
        assertEquals(0, labels())
        rule.onNodeWithText(quietWords(), substring = true).assertDoesNotExist()
    }

    @Test fun aTimeMadeUpOnThePhoneIsntShown() {
        val parsed = com.prudnikweb.hypernova.data.Wire.event(org.json.JSONObject().put("id", "m0").put("kind", "user").put("text", text(0)))
        chat(hub(listOf(parsed)))
        settleOpening()
        longPress("Message 0:")
        rule.onNodeWithText(str(R.string.chat_copy)).assertExists()
        // The menu's YOU, and no time before it.
        assertEquals(0, labels())
    }

    // ─── The long-press menu ───

    private fun longPress(text: String) {
        rule.onAllNodes(hasAnyDescendant(hasText(text, substring = true)) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick), useUnmergedTree = true)
            .onLast().performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
    }

    @Test fun theMenuStartsWithTheExactTimeAndItsAnActionNoMore() {
        val at = ZonedDateTime.of(2024, 3, 4, 21, 14, 7, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        chat(hub(listOf(WEvent.User("m0", at, "Sketch the lexer"), WEvent.Assistant("m1", at + 60_000, "Here it is.", false))))
        longPress("Sketch the lexer")
        val line = rule.onNodeWithText("Mon Mar 4, 2024 · 9:14:07 PM").assertExists()
        line.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        line.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Monday, March 4, 2024 at 9:14:07 PM")))
        // First in the menu, above its words and its actions.
        val top = line.fetchSemanticsNode().boundsInRoot.top
        assertTrue(top < rule.onAllNodesWithText(str(R.string.you).uppercase()).onLast().fetchSemanticsNode().boundsInRoot.top)
        assertTrue(top < rule.onNodeWithText(str(R.string.chat_copy)).fetchSemanticsNode().boundsInRoot.top)
        rule.snap("chat_time_menu")
    }

    @Test fun aMessageWithNoRealTimeHasNoLineInItsMenu() {
        val at = ZonedDateTime.of(2024, 3, 4, 21, 14, 7, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val queued = Item.User("queued:1", "Waiting in the queue", status = com.prudnikweb.hypernova.data.SendStatus.QueuedStep)
        chat(hub(listOf(WEvent.User("m0", at, "Sketch the lexer")), extra = listOf(queued)))
        longPress("Waiting in the queue")
        rule.onNodeWithText(str(R.string.chat_copy)).assertExists()
        assertEquals(0, labels())
    }
}
