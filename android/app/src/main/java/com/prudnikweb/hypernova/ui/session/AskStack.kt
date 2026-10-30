package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Fork: the asks still waiting for an answer (approvals, questions, plans) are always last in the
 * chat, and several are a stack (owner, 2026-10-29). Pure, unit-tested.
 */
internal object Asks {
    /** An approval, question or plan nobody has answered yet. */
    fun waiting(item: Item): Boolean = item is Item.Approval && item.resolved == null

    /**
     * [items] without the asks still waiting, and those asks: the one the Mac auto-denies first
     * first (one still arriving, with no deadline yet, after those), else in the chat's order.
     */
    fun split(items: List<Item>): Pair<List<Item>, List<Item.Approval>> {
        if (items.none(::waiting)) return items to emptyList()
        val (asks, rest) = items.partition(::waiting)
        return rest to asks.map { it as Item.Approval }.sortedBy { it.expiresAt ?: Long.MAX_VALUE }
    }

    /** The ask in front: the one brought forward ([chosen]) while it still waits, else the first. */
    fun front(asks: List<Item.Approval>, chosen: String?): Item.Approval =
        asks.firstOrNull { it.key == chosen } ?: asks.first()
}

/** How much of each ask behind the front one shows above it, and how much narrower each step back is. */
private val PEEK = 9.dp
private val INSET = 12.dp

/** Asks drawn behind the front one; more are only counted. */
private const val SHOWN_BEHIND = 2

/**
 * Fork: the asks still waiting, as one stack at the end of the chat. The one in front is whole and
 * answerable ([card] draws it, as the chat would); the others peek out above it, each a little
 * narrower and dimmer, under "3 WAITING", so several read as several at a glance. A tap on one
 * behind brings it forward; answering the front one brings the next. One alone is just its card.
 */
@Composable
internal fun AskStack(asks: List<Item.Approval>, modifier: Modifier = Modifier, card: @Composable (Item.Approval, Modifier) -> Unit) {
    // The one brought forward (its key), until it's answered.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val front = Asks.front(asks, chosen)
    if (asks.size == 1) {
        key(front.key) { card(front, modifier) }
        return
    }
    val behind = asks.filter { it.key != front.key }.take(SHOWN_BEHIND)
    val shape = RoundedCornerShape(16.dp)
    val bring = stringResource(R.string.asks_bring_forward)
    // TalkBack hears which one is in front and how many wait ("1 of 3 waiting for you").
    val said = stringResource(R.string.asks_front_said, asks.indexOf(front) + 1, asks.size)
    Column(modifier) {
        HText(
            pluralStringResource(R.plurals.asks_waiting, asks.size, asks.size).uppercase(), style = Type.label, color = HN.amber, maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).semantics { contentDescription = said },
        )
        Box {
            // The farthest first, so each nearer one is drawn over it, and the front card over them all.
            for (depth in behind.size downTo 1) key(behind[depth - 1].key) {
                val a = behind[depth - 1]
                val what = when (a.kind) {
                    ApprovalKind.Tool -> a.tool.substringAfterLast("__")
                    ApprovalKind.Question -> stringResource(R.string.question)
                    ApprovalKind.Plan -> stringResource(R.string.plan)
                }
                val behindSaid = stringResource(R.string.asks_behind_said, what, a.detail.lineSequence().firstOrNull().orEmpty().take(80), asks.indexOf(a) + 1, asks.size)
                Box(
                    Modifier
                        .padding(top = PEEK * (behind.size - depth), start = INSET * depth, end = INSET * depth)
                        .fillMaxWidth()
                        // Its lower part goes under the nearer one, so no gap shows at the corners.
                        .height(PEEK + 16.dp)
                        .clip(shape)
                        .background(HN.bg950.copy(alpha = 0.96f))
                        .background(HN.amber.copy(alpha = 0.05f / depth))
                        .border(1.dp, HN.amber.copy(alpha = 0.45f - 0.13f * depth), shape),
                ) {
                    // Only the part that shows takes a tap.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(PEEK)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = bring) { chosen = a.key }
                            .semantics { contentDescription = behindSaid },
                    )
                }
            }
            key(front.key) { card(front, Modifier.padding(top = PEEK * behind.size)) }
        }
    }
}
