package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Opening a session: never a session over another session, so Back lands on Home (owner, 2026-10-30). */
class NavTest {
    private val home = Route.Home
    private fun s(id: String) = Route.Session(id)

    @Test fun fromHomeItGoesOnTop() {
        assertEquals(listOf(home, s("a")), sessionStack(listOf(home), "a"))
    }

    @Test fun aSessionOverAnotherLeavesHomeAndTheNewOne() {
        assertEquals(listOf(home, s("b")), sessionStack(listOf(home, s("a")), "b"))
    }

    @Test fun anAgentsScreenThenANotificationForAnother() {
        val stack = listOf(home, s("a"), Route.Agent("a", "x"))
        assertEquals(listOf(home, s("b")), sessionStack(stack, "b"))
        assertEquals(listOf(home, s("b")), sessionStack(stack, "b", fresh = true))
    }

    @Test fun overWhatASessionOpenedToo() {
        assertEquals(listOf(home, s("b")), sessionStack(listOf(home, s("a"), Route.Terminal("a")), "b"))
        assertEquals(listOf(home, s("b")), sessionStack(listOf(home, s("a"), Route.Files("/p", "p"), Route.FileView("/p/f")), "b"))
        assertEquals(listOf(home, s("b")), sessionStack(listOf(home, s("a"), Route.Web("http://127.0.0.1:3000", "dev")), "b"))
    }

    @Test fun theSameSessionStaysAsItIs() {
        val stack = listOf(home, s("a"))
        assertSame(stack, sessionStack(stack, "a"))
        assertSame(stack, sessionStack(stack, "a", fresh = true))
    }

    @Test fun itsOwnSessionFromItsAgentGoesBackToIt() {
        assertEquals(listOf(home, s("a")), sessionStack(listOf(home, s("a"), Route.Agent("a", "x")), "a"))
    }

    @Test fun elsewhereItGoesOnTopUnlessFromOutside() {
        val runs = listOf(home, Route.AutomationEdit("au1"))
        assertEquals(runs + s("a"), sessionStack(runs, "a"))
        assertEquals(listOf(home, s("a")), sessionStack(runs, "a", fresh = true))
    }

    @Test fun withinASessionBackReturnsToIt() {
        val nav = Nav()
        nav.openSession("a")
        nav.push(Route.Agent("a", "x"))
        assertEquals(listOf(home, s("a"), Route.Agent("a", "x")), nav.stack.toList())
        nav.pop()
        assertEquals(listOf(home, s("a")), nav.stack.toList())
        nav.push(Route.Terminal("a"))
        nav.pop()
        assertEquals(s("a"), nav.top)
    }

    @Test fun backFromTheSecondSessionLandsOnHome() {
        val nav = Nav()
        nav.openSession("a")
        nav.push(Route.Agent("a", "x"))
        nav.openSession("b")
        assertEquals(listOf(home, s("b")), nav.stack.toList())
        nav.pop()
        assertEquals(listOf<Route>(home), nav.stack.toList())
        nav.openSession("b")
        nav.openSession("b")
        assertEquals(2, nav.depth)
    }
}
