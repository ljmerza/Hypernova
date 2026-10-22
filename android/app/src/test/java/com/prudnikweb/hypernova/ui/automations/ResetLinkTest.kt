package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.ui.home.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The bottom bar's order (the owner's brief) and the editor route a limited session opens. */
class ResetLinkTest {
    @Test fun historySitsNextToSessions() {
        assertEquals(listOf(Tab.Sessions, Tab.History, Tab.Automations, Tab.Settings), Tab.entries.toList())
    }

    @Test fun resetRouteCarriesTheSessionsFolder() {
        val a = Route.AutomationEdit(null, Templates.RESET, project = "/Users/me/a")
        val b = Route.AutomationEdit(null, Templates.RESET, project = "/Users/me/b")
        assertEquals("/Users/me/a", a.project)
        assertNotEquals(a.key, b.key)
    }
}
