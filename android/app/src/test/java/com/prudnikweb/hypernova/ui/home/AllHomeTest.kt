package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.MAC_RESTART
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TextHit
import com.prudnikweb.hypernova.data.TextHits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's "All servers" (AllHome.kt): every computer's sessions together, each card going to its own. */
class AllHomeTest {
    private fun s(
        id: String, name: String, state: SessionState = SessionState.Idle, updatedAt: Long = 0L,
        phase: Phase = Phase.of(state), project: String = "p", activity: String = "", endedBy: String? = null,
    ) = Session(hex = id.take(2), id = id, name = name, project = project, state = state, model = "opus", mode = "default", activity = activity, updatedAt = updatedAt, phase = phase, endedBy = endedBy)

    /** Each computer's hub is its name here: where a card's actions would go. */
    private fun part(id: String, name: String, sessions: List<Session>, state: LinkState = LinkState.Connected, hits: TextHits? = null) =
        ServerPart(id, name, state, hub = "hub-$name", sessions = sessions, hits = hits)

    private val z2 = part(
        "0", "z2",
        listOf(
            s("a1", "zeta work", SessionState.Working),
            s("a2", "asks first", SessionState.NeedsYou),
            s("a3", "older done", updatedAt = 10),
            s("a4", "gone", phase = Phase.Ended),
        ),
    )
    private val gym = part(
        "b", "gym",
        listOf(
            s("b1", "Alpha work", SessionState.Working),
            s("b2", "Backup asks", SessionState.NeedsYou),
            s("b3", "newest done", updatedAt = 30),
            s("b4", "cut short", phase = Phase.Ended, updatedAt = 20, endedBy = MAC_RESTART),
        ),
    )

    private fun shape(groups: List<Pair<HomeGroup, List<HomeCard<String>>>>) =
        groups.map { (g, list) -> g to list.map { "${it.name}:${it.session.name}" } }

    @Test fun everyComputersSessionsInTheThreeGroupsAndTheirSorts() {
        val groups = AllHome.groups(AllHome.cards(listOf(z2, gym)))
        assertEquals(
            listOf(
                // A to Z, any case, whichever computer.
                HomeGroup.NeedsInput to listOf("z2:asks first", "gym:Backup asks"),
                HomeGroup.Working to listOf("gym:Alpha work", "z2:zeta work"),
                // By name too since the sort button (Name by default); what a restart cut short is
                // with them; ended ones are History's.
                HomeGroup.Completed to listOf("gym:cut short", "gym:newest done", "z2:older done"),
            ),
            shape(groups),
        )
        // Recent: newest first, across both computers.
        assertEquals(
            listOf("gym:newest done", "gym:cut short", "z2:older done"),
            shape(AllHome.groups(AllHome.cards(listOf(z2, gym)), HomeSort.Recent)).toMap().getValue(HomeGroup.Completed),
        )
    }

    @Test fun byServerGroupsEachComputersCardsTogetherThenByName() {
        val groups = AllHome.groups(AllHome.cards(listOf(z2, gym)), HomeSort.Server).toMap()
        assertEquals(listOf("gym:Backup asks", "z2:asks first"), groups.getValue(HomeGroup.NeedsInput).map { "${it.name}:${it.session.name}" })
        assertEquals(listOf("gym:cut short", "gym:newest done", "z2:older done"), groups.getValue(HomeGroup.Completed).map { "${it.name}:${it.session.name}" })
    }

    @Test fun theGroupCountsAreAllOfThem() {
        val groups = AllHome.groups(AllHome.cards(listOf(z2, gym))).toMap()
        assertEquals(2, groups.getValue(HomeGroup.NeedsInput).size)
        assertEquals(2, groups.getValue(HomeGroup.Working).size)
        assertEquals(3, groups.getValue(HomeGroup.Completed).size)
        assertEquals(2, AllHome.needsYou(AllHome.cards(listOf(z2, gym))))
    }

    @Test fun eachCardIsTaggedWithItsComputer() {
        val cards = AllHome.cards(listOf(z2, gym))
        assertEquals("z2", cards.first { it.session.id == "a1" }.name)
        assertEquals("gym", cards.first { it.session.id == "b1" }.name)
        assertEquals("0", cards.first { it.session.id == "a1" }.server)
    }

    @Test fun oneComputerHasNoTag() {
        val card = HomeCard(null, null, "hub", s("a1", "x"))
        assertNull(card.name)
        assertFalse(card.offline)
        assertEquals("a1", card.key)
    }

    @Test fun theSameIdOnTwoComputersIsTwoCards() {
        val one = part("0", "z2", listOf(s("same", "fix it")))
        val two = part("b", "gym", listOf(s("same", "fix it")))
        val cards = AllHome.cards(listOf(one, two))
        assertEquals(2, cards.map { it.key }.toSet().size)
        // Same name and id: by the computer's name, so they hold still.
        assertEquals(listOf("gym", "z2"), AllHome.groups(cards).single().second.map { it.name })
    }

    @Test fun aCardsActionsGoToItsOwnComputer() {
        val cards = AllHome.cards(listOf(z2, gym))
        val b2 = AllHome.cardOf(cards, AllHome.key("b", "b2"))!!
        assertEquals("b", b2.server)
        assertEquals("hub-gym", b2.hub)
        val a2 = AllHome.cardOf(cards, AllHome.key("0", "a2"))!!
        assertEquals("hub-z2", a2.hub)
        // A session the other computer doesn't have isn't found there.
        assertNull(AllHome.cardOf(cards, AllHome.key("0", "b2")))
    }

    @Test fun anOfflineComputersCardsAreDimmedAndSaySo() {
        val down = part("b", "gym", listOf(s("b1", "Alpha work", SessionState.Working)), state = LinkState.Offline)
        val cards = AllHome.cards(listOf(z2, down))
        assertTrue(cards.filter { it.server == "b" }.all { it.offline && !it.live })
        assertTrue(cards.filter { it.server == "0" }.none { it.offline })
        // Reconnecting is still current; connecting (nothing heard yet) isn't.
        assertFalse(AllHome.cards(listOf(part("b", "gym", gym.sessions, LinkState.Reconnecting))).any { it.offline })
        assertTrue(AllHome.cards(listOf(part("b", "gym", gym.sessions, LinkState.Connecting))).all { it.offline })
    }

    @Test fun theProjectFilterNarrowsEachComputer() {
        val one = part("0", "z2", listOf(s("a1", "x", project = "hypernova"), s("a2", "y", project = "0stems")))
        val two = part("b", "gym", listOf(s("b1", "z", project = "hypernova")))
        val cards = AllHome.cards(listOf(one, two)) { list -> list.filter { it.project == "hypernova" } }
        assertEquals(setOf("a1", "b1"), cards.map { it.session.id }.toSet())
    }

    @Test fun searchFindsOnEveryComputer() {
        val one = part("0", "z2", listOf(s("a1", "Fix the widget"), s("a2", "Weather")))
        val two = part("b", "gym", listOf(s("b1", "widget colours"), s("b2", "Backups")))
        val cards = AllHome.cards(listOf(one, two), "widget")
        assertEquals(setOf("a1", "b1"), cards.map { it.session.id }.toSet())
        assertTrue(AllHome.cards(listOf(one, two), "zzqx").isEmpty())
        assertEquals(4, AllHome.cards(listOf(one, two), "  ").size)
    }

    @Test fun eachComputersFullTextHitsAreMergedIntoItsOwn() {
        fun hit(id: String) = TextHit(id, "…the VPN port…", listOf(5 until 8), fromClaude = true, matches = 1)
        // gym's server found "vpn" inside b2; z2's found it inside a2. Each merges only its own.
        val one = part("0", "z2", listOf(s("a1", "Fix the widget"), s("a2", "Weather")), hits = TextHits(listOf(hit("a2"), hit("b2")), partial = false))
        val two = part("b", "gym", listOf(s("b1", "widget colours"), s("b2", "Backups")), hits = TextHits(listOf(hit("b2")), partial = false))
        val cards = AllHome.cards(listOf(one, two), "vpn")
        assertEquals(listOf("0:a2", "b:b2"), cards.map { it.key }.sorted())
    }

    @Test fun homeListsWhatsAliveAndWhatARestartCut() {
        val list = listOf(
            s("1", "live", SessionState.Working),
            s("2", "ended", phase = Phase.Ended),
            s("3", "cut", phase = Phase.Ended, endedBy = MAC_RESTART),
            s("4", "switched").copy(switchedTo = "5"),
        )
        assertEquals(listOf("live", "cut"), AllHome.homeSessions(list).map { it.name })
    }

    @Test fun theNewSessionPickerListsEveryComputerOnlyLinkedOnesToPick() {
        val picks = AllHome.picks(
            listOf(
                ServerStatus("0", "z2", LinkState.Connected, 0, active = true),
                ServerStatus("b", "gym", LinkState.Offline, 1, active = false),
                ServerStatus("c", "shed", LinkState.Reconnecting, 0, active = false),
                ServerStatus("d", "mini", LinkState.Connecting, 0, active = false),
            ),
        )
        assertEquals(listOf("z2", "gym", "shed", "mini"), picks.map { it.name })
        assertEquals(listOf(true, false, false, false), picks.map { it.enabled })
        assertEquals(LinkState.Offline, picks[1].state)
    }
}
