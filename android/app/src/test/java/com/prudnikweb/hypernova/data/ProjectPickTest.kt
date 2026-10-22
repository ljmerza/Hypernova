package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The new-session sheet's project picker: search, recent first, the rest by folder (data/ProjectPick.kt, data/Fuzzy.kt). */
class ProjectPickTest {
    private val now = 1_800_000_000_000L
    private fun p(path: String, name: String = path.substringAfterLast('/'), agoH: Long? = null, sessions: Int = 1) =
        Project(path, name, agoH?.let { now - it * 3_600_000L }, sessions)

    private val root = "/Users/me/Programming"
    private val all = listOf(
        p("/Users/me/Programming/hypernova", agoH = 0),
        p("/Users/me/Programming/0stems", agoH = 1),
        p("/Users/me/Documents/programming/even_realities/g2-local-stt", "Hyperscribe", agoH = 2),
        p("/Users/me/Programming/hypernova/.claude/worktrees/new-session", agoH = 3),
        p("/Users/me/Programming/Nebula/lens-route-glow", agoH = 4),
        // Not among the five most recent: grouped by folder.
        p("/Users/me/Programming/Nebula/weather-glance", agoH = 300),
        p("/Users/me/Programming/budget-tracker", agoH = 500),
        p("/Users/me/Programming/accessibility-audit", agoH = 400),
        p("/Users/me/Programming/hypernova/.claude/worktrees/drawn-ui", agoH = 30),
        p("/Users/me/Documents/programming/even_realities/hypertasks", "Hypertasks", agoH = 200),
        p("/Users/me/dotfiles", agoH = 150),
        p("/Users/me/Programming/never-run", agoH = null, sessions = 0),
    )

    @Test fun pathsAreShortenedToTheHomeFolder() {
        assertEquals("~/Programming/x", ProjectPick.tilde("/Users/me/Programming/x"))
        assertEquals("~/srv/app", ProjectPick.tilde("/home/me/srv/app"))
        assertEquals("~", ProjectPick.tilde("/Users/me"))
        assertEquals("/opt/thing", ProjectPick.tilde("/opt/thing"))
        assertEquals("~/Usersfoo", ProjectPick.tilde("~/Usersfoo"))
        assertEquals("~/Programming", ProjectPick.parent("/Users/me/Programming/hypernova"))
    }

    @Test fun theMostRecentLeadThenTheRestByFolder() {
        val g = ProjectPick.groups(all, root)
        assertEquals(ProjectPick.Kind.Recent, g[0].kind)
        assertEquals(listOf("hypernova", "0stems", "Hyperscribe", "new-session", "lens-route-glow"), g[0].projects.map { it.name })
        // The projects folder first, then Nebula, then the others, the most recently active first.
        assertEquals(
            listOf(ProjectPick.Kind.Root to "~/Programming", ProjectPick.Kind.Nebula to null, ProjectPick.Kind.Worktrees to "hypernova", ProjectPick.Kind.Folder to "~", ProjectPick.Kind.Folder to "~/Documents/programming/even_realities"),
            g.drop(1).map { it.kind to it.label },
        )
        // Inside a folder, by name; a project never run from here is listed too.
        assertEquals(listOf("accessibility-audit", "budget-tracker", "never-run"), g[1].projects.map { it.name })
        // Each project once.
        assertEquals(all.size, g.sumOf { it.projects.size })
    }

    @Test fun noRecentPartWhenNothingWasEverActive() {
        val g = ProjectPick.groups(listOf(p("/Users/me/Programming/a", agoH = null), p("/Users/me/Programming/b", agoH = null)), root)
        assertEquals(listOf(ProjectPick.Kind.Root), g.map { it.kind })
    }

    @Test fun searchFindsByNameOrPathBestFirst() {
        assertEquals("hypernova", ProjectPick.search(all, "hyper").first().name)
        // A word's start in the path: the folder Hyperscribe lives in.
        assertEquals("Hyperscribe", ProjectPick.search(all, "g2").first().name)
        // Letters in order, when nothing matches better.
        assertTrue(ProjectPick.search(all, "bdgt").any { it.name == "budget-tracker" })
        // Once a word is found as itself, letters scattered through a path don't count.
        assertEquals(setOf("Hyperscribe", "Hypertasks"), ProjectPick.search(all, "even").map { it.name }.toSet())
        // Every word must be found: in the path or the name.
        assertEquals(setOf("Hyperscribe", "Hypertasks"), ProjectPick.search(all, "even hyp").take(2).map { it.name }.toSet())
        assertEquals(listOf("lens-route-glow", "weather-glance"), ProjectPick.search(all, "nebula").map { it.name })
        assertTrue(ProjectPick.search(all, "zzz").isEmpty())
        assertTrue(ProjectPick.search(all, "  ").isEmpty())
        // The name counts more than the same letters in a path: "drawn" is a worktree's name.
        assertEquals("drawn-ui", ProjectPick.search(all, "drawn").first().name)
    }

    @Test fun fuzzyScoresWholeThenStartThenWordStartThenInsideThenLetters() {
        val whole = Fuzzy.word("hypernova", "hypernova")!!
        val start = Fuzzy.word("hyper", "hypernova")!!
        val wordStart = Fuzzy.word("nova", "hyper-nova")!!
        val inside = Fuzzy.word("nova", "hypernova")!!
        val letters = Fuzzy.word("hnv", "hypernova")!!
        assertTrue(whole > start && start > wordStart && wordStart > inside && inside > letters && letters > 0)
        assertNull(Fuzzy.word("xyz", "hypernova"))
        // A capital after a small letter starts a word too.
        assertTrue(Fuzzy.word("scribe", "HyperScribe")!! > Fuzzy.word("scribe", "hyperscribe")!!)
        assertEquals(0, Fuzzy.score("", "anything"))
        assertNull(Fuzzy.score("hyper zzz", "hypernova"))
    }

    @Test fun newProjectNamesAreFolderNames() {
        assertTrue(ProjectPick.validName("my-app_2.0"))
        assertFalse(ProjectPick.validName("-x"))
        assertFalse(ProjectPick.validName("a b"))
        assertFalse(ProjectPick.validName(""))
        assertEquals("my-new-app", ProjectPick.asName("  my new  app "))
        assertEquals("caf", ProjectPick.asName("café/"))
    }

    @Test fun theSheetStartsWhereItWasAskedElseWhereItLastWas() {
        val hyper = "/Users/me/Programming/hypernova"
        val stems = "/Users/me/Programming/0stems"
        assertEquals(stems, ProjectPick.initial(all, stems, hyper))
        // The sky's filter names the project.
        assertEquals("/Users/me/Documents/programming/even_realities/g2-local-stt", ProjectPick.initial(all, "Hyperscribe", hyper))
        assertEquals("/Users/me/Programming/budget-tracker", ProjectPick.initial(all, null, "/Users/me/Programming/budget-tracker"))
        // The last one is gone: the most recently active.
        assertEquals(hyper, ProjectPick.initial(all, "nope", "/Users/me/gone"))
        assertNull(ProjectPick.initial(emptyList(), null, null))
    }
}
