package com.prudnikweb.hypernova.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** What an agent's screen shows with no transcript rows (AgentScreen.kt, docs/PROTOCOL.md: the agent route's `starting`). */
class AgentEmptyTest {
    @Test
    fun anAgentJustStartedIsStartingNotAnError() {
        assertEquals(AgentEmpty.Starting, agentEmpty(loaded = false, error = null, starting = true))
    }

    @Test
    fun beforeTheFirstAnswerItLoads() {
        assertEquals(AgentEmpty.Loading, agentEmpty(loaded = false, error = null, starting = false))
    }

    @Test
    fun aFailureWithNothingLoadedIsAnError() {
        assertEquals(AgentEmpty.Error, agentEmpty(loaded = false, error = "That agent’s transcript isn’t on the server.", starting = false))
    }

    @Test
    fun anAnswerWithNoEventsIsNothingYet() {
        assertEquals(AgentEmpty.Nothing, agentEmpty(loaded = true, error = null, starting = false))
    }
}
