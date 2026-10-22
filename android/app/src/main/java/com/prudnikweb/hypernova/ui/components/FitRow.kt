package com.prudnikweb.hypernova.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A row of small drawn parts (a model chip, a figure with its glyph) that gives way, whole, when
 * it's too narrow, instead of cutting one off in the middle. Each part has its variants, widest
 * first ([parts]; an empty list is a part with nothing to show); [giveWay] lists, in order, the part
 * that steps down to its next variant, or out after its last, until the row fits ([RowFit]). The
 * parts sit [gap] apart, centred on the row's height, from its start (or its end, [end]).
 *
 * Every variant is measured, only the chosen ones are placed, and only placed ones draw.
 */
@Composable
fun FitRow(
    parts: List<List<@Composable () -> Unit>>,
    giveWay: IntArray,
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    end: Boolean = false,
) {
    Layout(content = { parts.forEach { variants -> variants.forEach { it() } } }, modifier = modifier) { measurables, constraints ->
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(loose) }
        // The placeables part by part, as [parts] has them.
        var i = 0
        val byPart = parts.map { variants -> List(variants.size) { placeables[i++] } }
        val gapPx = gap.roundToPx()
        val room = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val pick = RowFit.choose(byPart.map { v -> IntArray(v.size) { v[it].width } }, gapPx, room, giveWay)
        val shown = byPart.indices.mapNotNull { p -> pick[p].takeIf { it >= 0 }?.let { byPart[p][it] } }
        val used = shown.sumOf { it.width } + gapPx * (shown.size - 1).coerceAtLeast(0)
        val width = if (end && constraints.hasBoundedWidth) constraints.maxWidth else used.coerceIn(constraints.minWidth, room)
        val height = (shown.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var x = if (end) width - used else 0
            for (pl in shown) {
                pl.placeRelative(x, (height - pl.height) / 2)
                x += pl.width + gapPx
            }
        }
    }
}

/** What [FitRow] shows in the room it has. Pure, unit-tested. */
object RowFit {
    /**
     * For each part (its variants' [widths], widest first), the variant shown, or -1 when it's left
     * out: everything at its widest, then [giveWay]'s steps one by one until the parts and the
     * [gap]s between them fit in [room]. A step naming a part that's already out does nothing.
     */
    fun choose(widths: List<IntArray>, gap: Int, room: Int, giveWay: IntArray): IntArray {
        val pick = IntArray(widths.size) { if (widths[it].isEmpty()) -1 else 0 }
        fun total(): Int {
            var sum = 0
            var n = 0
            for (p in widths.indices) if (pick[p] >= 0) { sum += widths[p][pick[p]]; n++ }
            return sum + gap * (n - 1).coerceAtLeast(0)
        }
        var step = 0
        while (total() > room && step < giveWay.size) {
            val p = giveWay[step++]
            if (p !in widths.indices || pick[p] < 0) continue
            pick[p] = if (pick[p] + 1 < widths[p].size) pick[p] + 1 else -1
        }
        return pick
    }
}

/** One part of a [FitRow]: its [variants], widest first. */
fun fitPart(vararg variants: @Composable () -> Unit): List<@Composable () -> Unit> = variants.toList()

/** A [FitRow] part with nothing to show (left out). */
val NoPart: List<@Composable () -> Unit> = emptyList()
