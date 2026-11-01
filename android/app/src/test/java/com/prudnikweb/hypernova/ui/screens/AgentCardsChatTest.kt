package com.prudnikweb.hypernova.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Items
import com.prudnikweb.hypernova.data.Wire
import com.prudnikweb.hypernova.data.mapObjects
import com.prudnikweb.hypernova.ui.session.TranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat's agent and workflow cards (ui/components: AgentCard, WorkflowCard), built as the live hub
 * builds them: the contract fixtures' transcript (an Agent call and a Workflow call) with their board
 * (rest-agents.json, its times moved to the samples' clock and both ended, so the picture holds still).
 * A tap on an agent opens its own screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h1400dp")
class AgentCardsChatTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    private companion object {
        const val MAIN = "c0ffee00-0000-4000-8000-00000000c0de"
        const val PLAIN = "a0123456789abcdef"
    }

    @Before fun setUp() = resetPrefs()

    private fun board(): AgentBoard {
        val o = ScreenFixtures.json("rest-agents")
        val start = Samples.NOW - 5 * 60_000L
        val end = Samples.NOW - 60_000L
        val agents = o.getJSONArray("agents")
        for (i in 0 until agents.length()) agents.getJSONObject(i).put("status", "done").put("startedAt", start).put("endedAt", end).put("lastActivityAt", end)
        agents.getJSONObject(1).put("result", "The parser never trims its input.")
        val wfs = o.getJSONArray("workflows")
        wfs.getJSONObject(0).put("status", "done").put("startedAt", start).put("endedAt", end)
        return AgentBoard(o.optJSONArray("agents").mapObjects(Wire::agent).associateBy { it.id }, o.optJSONArray("workflows").mapObjects(Wire::workflow).associateBy { it.id })
    }

    private fun cards(): List<Item> {
        val events = ScreenFixtures.events("rest-session-events")
        val items = Items.build(events, emptyMap(), emptyMap(), emptyMap(), MAIN, "/home/me/Projects/app-parser-fix", Samples.NOW, active = false, board = board(), orphans = false)
        return items.filter { it is Item.AgentCard || it is Item.WorkflowCard }
    }

    @Test fun anAgentsCardAndAWorkflowsCardAreDrawnAndAnAgentOpensItsScreen() {
        val items = cards()
        assertEquals(listOf(Item.AgentCard::class, Item.WorkflowCard::class), items.map { it::class })
        val hub = FakeHub()
        rule.setContent {
            Harness(spy) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    for (it in items) TranscriptItem(hub, MAIN, it, Modifier.fillMaxWidth())
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Review the parser", substring = true, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("parser-survey", substring = true, useUnmergedTree = true).onFirst().assertExists()
        rule.onAllNodesWithText("Survey", substring = true, useUnmergedTree = true).onFirst().assertExists()
        rule.snap("chat_agent_and_workflow_cards")
        rule.onNodeWithText("Review the parser", substring = true, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(spy.opened.toString(), "agent:$MAIN/$PLAIN" in spy.opened)
    }
}
