package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.data.Attention.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Attention cards swiped away (data/Attention.kt): an Undo for a moment, then gone until what they say changes. */
class AttentionTest {
    private val t0 = 1_800_000_000_000L

    @Test fun aCardShowsUntilItIsSwipedAway() {
        assertEquals(Show.Card, Attention.show(emptyMap(), "account", "logged_out:expired", t0))
    }

    @Test fun swipedItLeavesAnUndoForAFewSecondsThenIsGone() {
        val gone = Attention.dismiss(emptyMap(), "provider:or", "bad_key:false", t0)
        assertEquals(Show.Undo, Attention.show(gone, "provider:or", "bad_key:false", t0 + 1_000))
        assertEquals(Show.Nothing, Attention.show(gone, "provider:or", "bad_key:false", t0 + Attention.UNDO_MS))
        // Its Undo ran out on screen: gone at once, whatever the clock says.
        assertEquals(Show.Nothing, Attention.show(Attention.settle(gone, "provider:or"), "provider:or", "bad_key:false", t0 + 10))
    }

    @Test fun undoBringsItBack() {
        val gone = Attention.dismiss(emptyMap(), "offline", "Unreachable:null", t0)
        assertEquals(Show.Card, Attention.show(Attention.undo(gone, "offline"), "offline", "Unreachable:null", t0 + 500))
    }

    @Test fun itComesBackWhenWhatItSaysChanges() {
        val gone = Attention.settle(Attention.dismiss(emptyMap(), "account", "limited:null:five_hour:100", t0), "account")
        // A new limit (another reset) is a new episode.
        assertEquals(Show.Card, Attention.show(gone, "account", "limited:null:five_hour:200", t0 + 60_000))
        // And the old dismissal is forgotten, so going back to the first state shows it too.
        val forgot = Attention.forgetChanged(gone, "account", "limited:null:five_hour:200")
        assertTrue(forgot.isEmpty())
        assertEquals(Show.Card, Attention.show(forgot, "account", "limited:null:five_hour:100", t0 + 120_000))
    }

    @Test fun cardsAreDismissedOneByOne() {
        val gone = Attention.dismiss(emptyMap(), "provider:a", "down:false", t0)
        assertEquals(Show.Card, Attention.show(gone, "provider:b", "down:false", t0))
        // The same state again keeps the map as it is.
        assertTrue(Attention.forgetChanged(gone, "provider:a", "down:false") === gone)
    }
}
