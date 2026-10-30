package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fork: held prompts (data/Held.kt; docs/PROTOCOL.md, "Held prompts"): the Mac's `session.held`
 * read, drawn as the owner's own "Held" bubbles in order, never twice once the Mac has typed one
 * in, and the ones that left the Mac's hold told from the ones this phone took back.
 */
class HeldTest {
    private fun held(id: String, text: String, clientId: String? = "c-$id") = WQueued(id, text, emptyList(), 1_000L, clientId)

    @Test fun readsTheMacsHeldPromptsAndNoneFromAnOlderMac() {
        val s = Wire.session(JSONObject("""{"id":"s1","title":"t","cwd":"/p","state":"needs_you","held":[{"id":"h1","text":"then this","at":5,"clientId":"c1"},{"id":"h2","text":"and this","at":6,"clientId":null}]}"""))
        assertEquals(listOf("h1" to "c1", "h2" to null), s.held.map { it.id to it.clientId })
        assertTrue(Wire.session(JSONObject("""{"id":"s1","title":"t","cwd":"/p","state":"idle"}""")).held.isEmpty())
    }

    @Test fun heldPromptsAreTheOwnersBubblesInOrderWithTheirIds() {
        val items = Held.items(listOf(held("h1", "first"), held("h2", "second")), emptyList(), emptyList())
        assertEquals(listOf("first", "second"), items.map { it.text })
        assertEquals(listOf("h1", "h2"), items.map { it.heldId })
        assertTrue(items.all { it.status == SendStatus.Held && it.held && it.pending && !it.waiting && !it.queued })
    }

    @Test fun oneTheMacHasTypedInIsntDrawnTwice() {
        val events = listOf(WEvent.User("u1", 2_000L, "first", clientId = "c-h1"))
        val queue = listOf(WQueued("q1", "second", emptyList(), 2_500L, "c-h2"))
        val items = Held.items(listOf(held("h1", "first"), held("h2", "second"), held("h3", "third")), events, queue)
        assertEquals(listOf("h3"), items.map { it.heldId })
    }

    @Test fun theOnesThatLeftExceptTheOnesThisPhoneTookBack() {
        val before = listOf(held("h1", "a"), held("h2", "b"), held("h3", "c"))
        assertEquals(listOf("h1"), Held.left(before, listOf(held("h3", "c")), removed = setOf("h2")).map { it.id })
        assertTrue(Held.left(emptyList(), before, emptySet()).isEmpty())
        assertTrue(Held.left(before, before, emptySet()).isEmpty())
    }

    @Test fun heldPromptsGoJustAboveTheAsksWaitingAtTheEnd() {
        // As the session screen splits the chat: the asks are taken out and drawn last, so the
        // held prompts appended after the transcript sit right above them.
        val ask = Item.Approval("ap", "Bash", "ls", "", resolved = null)
        val items = listOf(Item.User("u", "go"), ask, Item.Assistant("r", "ok")) + Held.items(listOf(held("h1", "after")), emptyList(), emptyList())
        val (chat, asks) = com.prudnikweb.hypernova.ui.session.Asks.split(items)
        assertEquals(listOf("u", "r", "held:h1"), chat.map { it.key })
        assertEquals(listOf("ap"), asks.map { it.key })
    }
}
