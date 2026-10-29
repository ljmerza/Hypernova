package com.prudnikweb.hypernova.ui.components

import com.prudnikweb.hypernova.data.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The status line's mode tag, tapped (ui/components/ModeCycle.kt): Ask, Plan, Auto, round again. */
class ModeCycleTest {
    @Test fun claudeStepsAskPlanAutoAndRound() {
        assertEquals("plan", ModeCycle.next("default", codex = false))
        assertEquals("auto", ModeCycle.next("plan", codex = false))
        assertEquals("default", ModeCycle.next("auto", codex = false))
    }

    @Test fun aModeOutsideTheCycleGoesBackToAsk() {
        assertEquals("default", ModeCycle.next("acceptEdits", codex = false))
        assertEquals("default", ModeCycle.next("bypassPermissions", codex = false))
        assertEquals("default", ModeCycle.next("dontAsk", codex = false))
    }

    @Test fun codexTogglesBetweenItsTwo() {
        val (first, second) = AgentKind.CODEX_MODES
        assertEquals(second, ModeCycle.next(first, codex = true))
        assertEquals(first, ModeCycle.next(second, codex = true))
        assertEquals(first, ModeCycle.next("default", codex = true))
    }
}
