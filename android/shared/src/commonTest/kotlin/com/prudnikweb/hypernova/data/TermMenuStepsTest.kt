package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AskUserQuestion's form of several questions in a terminal (an ask handed back): the daemon sends
 * its tabs as `menu.steps`, which the menu card draws as Back, the questions and Submit, Next
 * (owner, 2026-10-31). It's still a menu card, never an approval.
 */
class TermMenuStepsTest {
    private fun menu(extra: String) = Wire.session(
        JSONObject(
            """{"id":"s1","cwd":"/p","state":"needs_you","term":{"id":"t1","mac":0,"phone":1},
            "menu":{"id":"q1","title":"Which versioning scheme should releases use?","body":[],
            "options":[{"label":"Semver","detail":"MAJOR.MINOR.PATCH"},{"label":"CalVer"},{"label":"Type something."},{"label":"Chat about this"}],
            "cursor":0,"more":false,"cancel":true,"alt":null,"permission":null$extra}}""",
        ),
    ).menu

    @Test fun readsTheTabs() {
        val m = menu(""","steps":{"labels":["Versioning","First version"],"answered":[true,false]}""")!!
        assertEquals(TermMenu.Steps(listOf("Versioning", "First version"), listOf(true, false)), m.steps)
        assertNull(TerminalAsk.of(m))
    }

    @Test fun noStepsFromAnOlderDaemonOrAnotherMenu() {
        assertNull(menu("")!!.steps)
        assertNull(menu(""","steps":null""")!!.steps)
        assertNull(menu(""","steps":{"labels":[]}""")!!.steps)
        // Answered missing: none ticked.
        assertEquals(listOf(false, false), menu(""","steps":{"labels":["A","B"]}""")!!.steps!!.answered)
    }
}
