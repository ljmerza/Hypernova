package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.KeyValue
import com.prudnikweb.hypernova.data.OutdatedClaude
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Settings → Card swipes (DECISIONS, "Card swipes in Settings", 2026-10-31): what each way's pick
 * does on a card, never more than its long-press menu offers ([MenuOffer]), and the pick kept.
 */
class CardSwipeChoiceTest {
    private fun session(
        state: SessionState = SessionState.Idle,
        phase: Phase = Phase.Idle,
        job: String? = null,
        running: Boolean? = null,
        agent: String = AgentKind.CLAUDE,
        outdated: OutdatedClaude? = null,
    ) = Session("s1", "s1", "t", "alpha", state, "opus", "default", "", 0L, phase = phase, agentJob = job, agent = agent, agentRunning = running, agentOutdated = outdated)

    private val needs = session(SessionState.NeedsYou, Phase.NeedsYou, job = "j1", running = true)
    private val working = session(SessionState.Working, Phase.Working, job = "j1", running = true)
    private val idleJob = session(job = "j1", running = true)
    private val doneJob = session(job = "j1", running = false)
    private val plain = session()
    private val codexIdle = session(agent = AgentKind.CODEX)
    private val codexWorking = session(SessionState.Working, Phase.Working, agent = AgentKind.CODEX)

    private fun of(c: SwipeChoice, g: HomeGroup, s: Session, unread: Boolean = false) = CardSwipe.of(c, g, s, unread)

    @Test fun smartIsTheSwipeAsItWas() {
        for (g in HomeGroup.entries) for (s in listOf(needs, working, idleJob, doneJob, plain, codexIdle, codexWorking)) {
            assertEquals("$g $s", CardSwipe.of(g, s), of(SwipeChoice.Smart, g, s))
            assertEquals(CardSwipe.of(g, s), of(SwipeChoice.Smart, g, s, unread = true))
        }
        assertEquals(CardSwipe.Open, of(SwipeChoice.Smart, HomeGroup.NeedsInput, needs))
        assertNull(of(SwipeChoice.Smart, HomeGroup.Working, working))
        assertEquals(CardSwipe.Stop, of(SwipeChoice.Smart, HomeGroup.Completed, idleJob))
        assertEquals(CardSwipe.Delete, of(SwipeChoice.Smart, HomeGroup.Completed, doneJob))
        // Any other finished session deletes too (owner, 2026-11-01), asked first.
        assertEquals(CardSwipe.Delete, of(SwipeChoice.Smart, HomeGroup.Completed, plain))
        // Smart keeps a Completed Codex card from swiping to its for-good Delete.
        assertNull(of(SwipeChoice.Smart, HomeGroup.Completed, codexIdle))
    }

    @Test fun openFitsEveryCard() {
        for (g in HomeGroup.entries) assertEquals(CardSwipe.Open, of(SwipeChoice.Open, g, plain))
    }

    @Test fun stopOnlyWhereTheMenuHasIt() {
        assertEquals(CardSwipe.Stop, of(SwipeChoice.Stop, HomeGroup.Working, working))
        assertEquals(CardSwipe.Stop, of(SwipeChoice.Stop, HomeGroup.NeedsInput, needs))
        assertEquals(CardSwipe.Stop, of(SwipeChoice.Stop, HomeGroup.Completed, idleJob))
        assertEquals(CardSwipe.Stop, of(SwipeChoice.Stop, HomeGroup.Working, codexWorking))
        // A done job, an idle Codex session, a session that isn't a job: nothing to stop, no swipe.
        assertNull(of(SwipeChoice.Stop, HomeGroup.Completed, doneJob))
        assertNull(of(SwipeChoice.Stop, HomeGroup.Completed, codexIdle))
        assertNull(of(SwipeChoice.Stop, HomeGroup.Working, session(SessionState.Working, Phase.Working)))
    }

    @Test fun deleteAsTheMenuOffersIt() {
        assertEquals(CardSwipe.Delete, of(SwipeChoice.Delete, HomeGroup.Completed, doneJob))
        assertEquals(CardSwipe.Delete, of(SwipeChoice.Delete, HomeGroup.Working, working))
        // Picked on purpose, Codex's own delete (asked first, as from the menu).
        assertEquals(CardSwipe.Delete, of(SwipeChoice.Delete, HomeGroup.Completed, codexIdle))
        // A finished session whatever made it; not one at work that is neither a job nor Codex.
        assertEquals(CardSwipe.Delete, of(SwipeChoice.Delete, HomeGroup.Completed, plain))
        assertNull(of(SwipeChoice.Delete, HomeGroup.Working, session(SessionState.Working, Phase.Working)))
        assertNull(of(SwipeChoice.Delete, HomeGroup.NeedsInput, session(SessionState.NeedsYou, Phase.NeedsYou)))
    }

    @Test fun renameForAny() {
        for (s in listOf(plain, doneJob, codexIdle, working)) assertEquals(CardSwipe.Rename, of(SwipeChoice.Rename, HomeGroup.Completed, s))
    }

    @Test fun restartAsTheMenuOffersIt() {
        val old = OutdatedClaude("2.1.312", "2.1.314")
        assertEquals(CardSwipe.Restart, of(SwipeChoice.Restart, HomeGroup.Completed, session(job = "j1", running = true, outdated = old)))
        assertEquals(CardSwipe.Restart, of(SwipeChoice.Restart, HomeGroup.Working, session(SessionState.Working, Phase.Working, job = "j1", outdated = old)))
        // A job whose process runs, on the installed Claude Code too; one with none has nothing to restart.
        assertEquals(CardSwipe.Restart, of(SwipeChoice.Restart, HomeGroup.Completed, idleJob))
        assertNull(of(SwipeChoice.Restart, HomeGroup.Completed, doneJob))
        assertNull(of(SwipeChoice.Restart, HomeGroup.Completed, session(outdated = old)))
    }

    @Test fun markReadOnlyOnAnUnreadCard() {
        assertEquals(CardSwipe.MarkRead, of(SwipeChoice.MarkRead, HomeGroup.Completed, doneJob, unread = true))
        assertNull(of(SwipeChoice.MarkRead, HomeGroup.Completed, doneJob, unread = false))
    }

    @Test fun nothingNeverSwipes() {
        for (g in HomeGroup.entries) for (s in listOf(needs, working, doneJob, codexIdle)) assertNull(of(SwipeChoice.Nothing, g, s, unread = true))
    }

    @Test fun bothWaysAreDecidedApart() {
        // Left Smart, right Rename: a done job deletes left and renames right; a Working job only renames, right.
        val picks = CardSwipes(left = SwipeChoice.Smart, right = SwipeChoice.Rename)
        assertEquals(CardSwipe.Delete, of(picks.left, HomeGroup.Completed, doneJob))
        assertEquals(CardSwipe.Rename, of(picks.right, HomeGroup.Completed, doneJob))
        assertNull(of(picks.left, HomeGroup.Working, working))
        assertEquals(CardSwipe.Rename, of(picks.right, HomeGroup.Working, working))
    }

    private class Map : KeyValue {
        val m = HashMap<String, String>()
        override fun get(key: String): String? = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    @Test fun defaultsAreTheSwipeAsItWas() {
        assertEquals(CardSwipes(SwipeChoice.Smart, SwipeChoice.Nothing), CardSwipes.DEFAULT)
        assertEquals(CardSwipes.DEFAULT, CardSwipes.load(Map()))
    }

    @Test fun picksAreKept() {
        val kv = Map()
        for (l in SwipeChoice.entries) for (r in SwipeChoice.entries) {
            CardSwipes(l, r).save(kv)
            assertEquals(CardSwipes(l, r), CardSwipes.load(kv))
        }
    }

    @Test fun anUnknownPickFallsBackToItsDefault() {
        val kv = Map()
        kv.put("card_swipe_left", "Archive")
        kv.put("card_swipe_right", "Open")
        assertEquals(CardSwipes(SwipeChoice.Smart, SwipeChoice.Open), CardSwipes.load(kv))
        kv.put("card_swipe_right", "")
        assertEquals(CardSwipes.DEFAULT, CardSwipes.load(kv))
    }
}
