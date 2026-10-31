package com.prudnikweb.hypernova.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay

/*
 * What a Working card says it's doing now, and for how long (docs/IDEAS.md, HM1 and HM7;
 * docs/DECISIONS.md, "Home cards: what it's doing, for how long, and swipes"). The choices are
 * plain Kotlin, for the unit tests; the composables only draw them.
 */

/** A Working card's second line: the to-do under way with its tally, else what Claude is doing now. */
internal sealed interface WorkingLine {
    /** "Running the tests" and `3/7`: [done] of [total], as the session's own status bar counts them. */
    data class Todo(val text: String, val done: Int, val total: Int) : WorkingLine {
        val tally: String get() = "$done/$total"
        val said: String get() = "$text $tally"
    }

    /** The session's activity line ("Bash: npm test", "Thinking"). */
    data class Doing(val text: String) : WorkingLine

    companion object {
        /** The to-do under way when its list has one, else the activity. */
        fun of(s: Session): WorkingLine {
            val t = s.todo
            val active = t?.active?.trim()
            return if (t != null && t.total > 0 && !active.isNullOrEmpty()) Todo(active, t.done, t.total) else Doing(s.activity)
        }
    }
}

/**
 * How long a Working card's turn has run ("for 12 min", "for 1 h 5 min", "for 1 d 3 h"), from
 * [Session.workingSince]. Nothing under a minute, nor when it's unknown or ahead of this phone's clock.
 */
internal sealed interface Elapsed {
    data class Minutes(val n: Int) : Elapsed
    data class Hours(val h: Int, val m: Int) : Elapsed
    data class Days(val d: Int, val h: Int) : Elapsed

    companion object {
        fun of(since: Long, now: Long): Elapsed? {
            if (since <= 0L || now < since) return null
            val min = (now - since) / 60_000
            return when {
                min < 1 -> null
                min < 60 -> Minutes(min.toInt())
                min < 24 * 60 -> Hours((min / 60).toInt(), (min % 60).toInt())
                else -> Days((min / (24 * 60)).toInt(), ((min % (24 * 60)) / 60).toInt())
            }
        }

        /** How long until the figure next changes: the next whole minute since [since]. */
        fun nextTick(since: Long, now: Long): Long = 60_000 - ((now - since).coerceAtLeast(0) % 60_000)
    }
}

/**
 * The Working card's second line: one line, quiet (mono small, muted). The to-do's words give way
 * (an ellipsis) before its tally does; "for 12 min" stays at the end, dimmer.
 */
@Composable
internal fun WorkingNow(s: Session) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            when (val line = WorkingLine.of(s)) {
                is WorkingLine.Todo -> {
                    HText(line.text, Modifier.weight(1f, fill = false), style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    HText(line.tally, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                }
                is WorkingLine.Doing -> HText(line.text, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (s.workingSince > 0L) {
            Spacer(Modifier.width(10.dp))
            WorkingFor(s.workingSince)
        }
    }
}

/**
 * "for 12 min", in its own scope: it ticks on the minute since the turn began, so only this text
 * recomposes, once a minute, not the card or the list.
 */
@Composable
private fun WorkingFor(since: Long) {
    val now by produceState(System.currentTimeMillis(), since) {
        while (true) {
            delay(Elapsed.nextTick(since, System.currentTimeMillis()) + 50)
            value = System.currentTimeMillis()
        }
    }
    val e = Elapsed.of(since, now) ?: return
    val res = LocalContext.current.resources
    val shown = when (e) {
        is Elapsed.Minutes -> stringResource(R.string.working_for_m, e.n)
        is Elapsed.Hours -> if (e.m == 0) stringResource(R.string.working_for_h, e.h) else stringResource(R.string.working_for_hm, e.h, e.m)
        is Elapsed.Days -> if (e.h == 0) stringResource(R.string.working_for_d, e.d) else stringResource(R.string.working_for_dh, e.d, e.h)
    }
    val said = when (e) {
        is Elapsed.Minutes -> res.getQuantityString(R.plurals.working_minutes, e.n, e.n)
        is Elapsed.Hours -> listOfNotNull(res.getQuantityString(R.plurals.working_hours, e.h, e.h), e.m.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.working_minutes, it, it) }).joinToString(" ")
        is Elapsed.Days -> listOfNotNull(res.getQuantityString(R.plurals.working_days, e.d, e.d), e.h.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.working_hours, it, it) }).joinToString(" ")
    }
    val spoken = stringResource(R.string.working_for_said, said)
    HText(shown, Modifier.clearAndSetSemantics { contentDescription = spoken }, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
}
