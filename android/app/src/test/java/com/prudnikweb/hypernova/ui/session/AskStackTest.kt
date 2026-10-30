package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: the asks still waiting, one stack at the end of the chat (ui/session/AskStack.kt). */
class AskStackTest {
    private fun ask(k: String, expiresAt: Long? = null, resolved: Resolution? = null, kind: ApprovalKind = ApprovalKind.Tool) =
        Item.Approval(k, "Bash", "ls", "", resolved = resolved, kind = kind, expiresAt = expiresAt)

    @Test fun theWaitingAsksLeaveTheChatAndTheFirstToBeAutoDeniedIsFirst() {
        val items = listOf(Item.User("u", "go"), ask("late", 900L), Item.Assistant("r", "ok"), ask("soon", 300L, kind = ApprovalKind.Question), ask("arriving"))
        val (chat, asks) = Asks.split(items)
        assertEquals(listOf("u", "r"), chat.map { it.key })
        assertEquals(listOf("soon", "late", "arriving"), asks.map { it.key })
    }

    @Test fun answeredOnesStayInTheChat() {
        val items = listOf(ask("denied", 100L, resolved = Resolution.Denied), Item.Assistant("r", "ok"))
        val (chat, asks) = Asks.split(items)
        assertSame(items, chat)
        assertTrue(asks.isEmpty())
    }

    @Test fun theOneBroughtForwardIsInFrontUntilItsAnswered() {
        val asks = listOf(ask("a", 100L), ask("b", 200L), ask("c", 300L))
        assertEquals("a", Asks.front(asks, null).key)
        assertEquals("c", Asks.front(asks, "c").key)
        // Answered (gone from the stack): the first again.
        assertEquals("a", Asks.front(asks.dropLast(1), "c").key)
    }

    @Test fun theStackIsTheLastRowAndHasEachOfItsAsks() {
        val row = ChatRow.Asks(listOf(ask("a"), ask("b")))
        assertEquals("asks", row.key)
        assertTrue(row.has("b"))
    }
}
