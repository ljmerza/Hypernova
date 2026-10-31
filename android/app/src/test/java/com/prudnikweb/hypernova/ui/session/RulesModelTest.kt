package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.PermissionRules
import com.prudnikweb.hypernova.data.PermissionRulesWire
import com.prudnikweb.hypernova.data.RuleSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Permissions tab's rules in effect (RulesModel.kt, data/PermissionRules.kt): read as the Mac
 * sends them, grouped in its order with counts, the sources with no file in one line, what the
 * managed policy sets aside, and what's folded.
 */
class RulesModelTest {
    private val cwd = "/home/u/p/alpha"
    private fun src(source: String, path: String?, state: String = RuleSource.OK, allow: List<String> = emptyList(), ask: List<String> = emptyList(), deny: List<String> = emptyList(), mode: String? = null, dirs: List<String> = emptyList()) =
        RuleSource(source, path, state, allow, ask, deny, mode, dirs, false)

    @Test fun readsTheWireInOrderAndSkipsWhatItDoesNotKnow() {
        val r = PermissionRulesWire.of(
            JSONObject(
                """{"permissions":{"managedOnly":false,"sources":[
                {"source":"managed","path":"/etc/claude-code/managed-settings.json","state":"ok","allow":[],"ask":[],"deny":["Bash(curl:*)"],"defaultMode":null,"additionalDirectories":[],"clipped":false},
                {"source":"session","path":null,"state":"ok","allow":["WebFetch",7,""],"ask":[],"deny":[]},
                {"source":"somewhere","path":"/x","state":"ok","allow":["X"]},
                {"source":"local","path":"$cwd/.claude/settings.local.json","state":"invalid"},
                {"source":"user","path":"/home/u/.claude/settings.json","state":"ok","allow":["Read"],"defaultMode":"plan","clipped":true}
                ]}}""",
            ),
        )
        assertEquals(listOf("managed", "session", "local", "user"), r.sources.map { it.source })
        assertEquals(listOf("WebFetch"), r.sources[1].allow)
        assertNull(r.sources[1].path)
        assertEquals(RuleSource.INVALID, r.sources[2].state)
        assertEquals("plan", r.sources[3].defaultMode)
        assertTrue(r.sources[3].clipped)
    }

    @Test fun anOlderMacSendsNothing() {
        assertEquals(PermissionRules(emptyList(), false), PermissionRulesWire.of(JSONObject("{}")))
    }

    @Test fun groupsKeepTheOrderCountByKindAndPutMissingFilesInOneLine() {
        val v = rulesView(
            PermissionRules(
                listOf(
                    src(RuleSource.SESSION, null, allow = listOf("WebFetch")),
                    src(RuleSource.LOCAL, "$cwd/.claude/settings.local.json", state = RuleSource.MISSING),
                    src(RuleSource.PROJECT, "$cwd/.claude/settings.json", ask = listOf("Bash(git push:*)"), deny = listOf("Read(./.env)", "Bash(rm:*)")),
                    src(RuleSource.USER, "/home/u/.claude/settings.json", allow = listOf("Read", "Grep")),
                ),
                managedOnly = false,
            ),
            cwd,
        )
        assertEquals(listOf(RuleSource.SESSION, RuleSource.PROJECT, RuleSource.USER), v.groups.map { it.source.source })
        assertEquals(listOf(RuleSource.LOCAL), v.missing.map { it.source })
        assertEquals(RuleCounts(deny = 2, ask = 1, allow = 3), v.counts)
        assertEquals(RuleCounts(2, 1, 0), v.groups[1].counts)
        // The project's file from the session's folder; the user's as it is.
        assertEquals(".claude/settings.json", v.groups[1].path)
        assertEquals("/home/u/.claude/settings.json", v.groups[2].path)
        assertNull(v.groups[0].path)
        assertFalse(v.none)
        assertEquals(v.groups.map { it.key }.toSet().size, v.groups.size)
    }

    @Test fun aBrokenFileIsARowThatDoesNotOpenAndAnEmptyOneIsNoRow() {
        val v = rulesView(
            PermissionRules(
                listOf(
                    src(RuleSource.LOCAL, "$cwd/.claude/settings.local.json", state = RuleSource.INVALID),
                    src(RuleSource.PROJECT, "$cwd/.claude/settings.json"),
                    src(RuleSource.USER, "/home/u/.claude/settings.json", mode = "acceptEdits"),
                ),
                managedOnly = false,
            ),
            cwd,
        )
        assertEquals(listOf(RuleSource.LOCAL, RuleSource.USER), v.groups.map { it.source.source })
        assertFalse(v.groups[0].expandable)
        // Only a default mode: no rules, but something to open.
        assertTrue(v.groups[1].expandable)
        assertTrue(v.none)
        assertEquals(emptyList<RuleSource>(), v.missing)
    }

    @Test fun noRulesAtAll() {
        val v = rulesView(PermissionRules(listOf(src(RuleSource.USER, "/u/s.json", state = RuleSource.MISSING)), false), cwd)
        assertTrue(v.none)
        assertTrue(v.groups.isEmpty())
        assertEquals(1, v.missing.size)
    }

    @Test fun aManagedOnlyPolicyCountsItsOwnAndSetsTheOthersAside() {
        val v = rulesView(
            PermissionRules(
                listOf(
                    src(RuleSource.MANAGED, "/etc/claude-code/managed-settings.json", deny = listOf("Bash(curl:*)")),
                    src(RuleSource.USER, "/home/u/.claude/settings.json", allow = listOf("Bash(*)")),
                ),
                managedOnly = true,
            ),
            cwd,
        )
        assertEquals(RuleCounts(deny = 1), v.counts)
        assertTrue(v.groups[0].inEffect)
        assertFalse(v.groups[1].inEffect)
        assertTrue(v.managedOnly)
    }

    @Test fun foldedAtFirstThenEachSourceOnItsOwn() {
        var f = RulesFold()
        assertFalse(f.open)
        assertFalse(f.shows("a"))
        f = f.toggled("a")
        // Expanded, but the part is still folded.
        assertFalse(f.shows("a"))
        f = f.toggled()
        assertTrue(f.shows("a"))
        assertFalse(f.shows("b"))
        f = f.toggled("b").toggled("a")
        assertTrue(f.shows("b"))
        assertFalse(f.shows("a"))
        // Folding the part keeps what was open inside it.
        f = f.toggled().toggled()
        assertTrue(f.shows("b"))
    }

    @Test fun shownPathTakesOnlyTheSessionsOwnFolderOff() {
        assertEquals(".claude/settings.json", shownPath("/p/a/.claude/settings.json", "/p/a/"))
        assertEquals("/p/ab/.claude/settings.json", shownPath("/p/ab/.claude/settings.json", "/p/a"))
        assertEquals("/x", shownPath("/x", null))
        assertNull(shownPath(null, "/p/a"))
    }
}
