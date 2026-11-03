package com.prudnikweb.hypernova.ui.session

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ChatTimes
import com.prudnikweb.hypernova.data.Finished
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** How long the time stays after a scroll stops, before it fades. */
internal const val TIME_HOLD_MS = 1_000L

/** A session whose last message is older than this says so on opening (docs/DECISIONS.md, "The time over the chat"). */
internal const val QUIET_AFTER_MS = 3 * 3_600_000L

/** How long it says so. */
internal const val QUIET_SHOW_MS = 4_000L

/** A moment after the history is in, so a reload that brings something newer gets there first. */
private const val QUIET_SETTLE_MS = 500L

/** Past this many days "Last message" names the day. */
private const val QUIET_DAYS_MAX = 30

/** What Android's newer patterns put before AM; a font without it would fall back for that one gap. */
private const val NARROW_SPACE = '\u202F'

/** Below the top bar, where the list's first row starts. */
internal val TIME_LABEL_GAP = 8.dp

/**
 * The words for a time, on the phone's 12/24-hour setting and in its [locale]: a message of this
 * year has no year, an older one does.
 */
internal class ChatClock(private val locale: Locale, is24: Boolean, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val hours = if (is24) "Hm" else "hm"
    private val seconds = if (is24) "Hms" else "hms"
    private val time by lazy { format(hours) }
    private val exactTime by lazy { format(seconds) }
    private val day by lazy { format("EEEMMMd", noComma = true) }
    private val dayYear by lazy { format("EEEMMMdy", noComma = true) }
    private val plainDay by lazy { format("MMMd") }
    private val plainDayYear by lazy { format("MMMdy") }
    private val saidDay by lazy { format("EEEEMMMMd") }
    private val saidDayYear by lazy { format("EEEEMMMMdy") }

    /** The phone's own pattern, a plain space before AM; [noComma]: none after the weekday ("Mon Nov 2"). */
    private fun format(skeleton: String, noComma: Boolean = false) = SimpleDateFormat(
        DateFormat.getBestDateTimePattern(locale, skeleton).let { if (noComma) it.replaceFirst(",", "") else it }.replace(NARROW_SPACE, ' '),
        locale,
    ).apply { timeZone = TimeZone.getTimeZone(zone) }

    private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun sameYear(at: Long, now: Long) = date(at).year == date(now).year

    /** The floating label: "Today · 9:14 PM", "Yesterday · 9:14 PM", "Mon Nov 2 · 9:14 PM", "Mon Nov 2, 2025 · 9:14 PM". */
    fun scrolled(res: Resources, at: Long, now: Long): String {
        val days = ChronoUnit.DAYS.between(date(at), date(now))
        val on = when {
            days == 0L -> res.getString(R.string.chat_time_today)
            days == 1L -> res.getString(R.string.chat_time_yesterday)
            sameYear(at, now) -> day.format(Date(at))
            else -> dayYear.format(Date(at))
        }
        return res.getString(R.string.chat_time_at, on, time.format(Date(at)))
    }

    /** The message menu's line: always the date itself, to the second. */
    fun exact(res: Resources, at: Long, now: Long): String =
        res.getString(R.string.chat_time_at, (if (sameYear(at, now)) day else dayYear).format(Date(at)), exactTime.format(Date(at)))

    /** The same for TalkBack: "Monday, November 2 at 9:14:07 PM" (with a year when it's older). */
    fun exactSaid(res: Resources, at: Long, now: Long): String =
        res.getString(R.string.chat_time_said, (if (sameYear(at, now)) saidDay else saidDayYear).format(Date(at)), exactTime.format(Date(at)))

    /** "Last message 2 days ago" for a session quiet since [at]; null when it hasn't been quiet long enough. */
    fun quiet(res: Resources, at: Long, now: Long): String? {
        if (now - at < QUIET_AFTER_MS) return null
        return when (val f = Finished.of(at, now, zone)) {
            Finished.Now, is Finished.Minutes -> null
            is Finished.Hours -> res.getQuantityString(R.plurals.chat_quiet_hours, f.n, f.n)
            Finished.Yesterday -> res.getString(R.string.chat_quiet_yesterday)
            is Finished.Days ->
                if (f.n <= QUIET_DAYS_MAX) res.getQuantityString(R.plurals.chat_quiet_days, f.n, f.n)
                else res.getString(R.string.chat_quiet_on, (if (sameYear(at, now)) plainDay else plainDayYear).format(Date(at)))
        }
    }
}

@Composable
private fun rememberChatClock(): ChatClock {
    val configuration = LocalConfiguration.current
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    return remember(configuration, is24) { ChatClock(configuration.locales[0], is24) }
}

/**
 * The time of what's at the top of what's on screen: the first row not wholly under the top bar (a
 * row's offset counts from below the list's top padding) that has a real time, its own or, for a
 * folded run, its first call's. A row with none ("Show earlier", a prompt still queued) gives way
 * to the next on screen; with none on screen there's nothing to say.
 */
internal fun topTime(info: LazyListLayoutInfo, times: ChatTimes): Long? {
    val rows = info.visibleItemsInfo
    for (i in rows.indices) {
        val row = rows[i]
        if (row.offset + row.size <= 0) continue
        val key = row.key as? String ?: continue
        times.at(key.removePrefix(ChatRow.Run.PREFIX))?.let { return it }
    }
    return null
}

/**
 * A small floating label over the chat, in no one's way (owner, 2026-11-03; docs/DECISIONS.md, "The
 * time over the chat"). While the owner scrolls it says when what's at the top was said, and fades
 * a second after the scroll stops; on opening a session quiet for [QUIET_AFTER_MS] it says how long
 * for, for a few seconds. It's a sibling of the list, so nothing in the chat moves for it, and has
 * no pointer input, so touches go to the chat under it. It reads the list's layout only while
 * it shows, in a derived state, so a scroll recomposes it only when its words change and never the
 * list. TalkBack doesn't hear it: the times are on each message's menu.
 *
 * [following]: the chat is following its tail, so the list is moving by itself and that's no reason
 * to show anything. [top]: where it sits, in pixels from the top of the screen.
 */
@Composable
internal fun ChatTimeLabel(hub: Hub, sessionId: String, opening: Int, list: LazyListState, following: () -> Boolean, top: () -> Int, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val clock = rememberChatClock()
    val reduced = LocalMotion.current.reduced
    val followingNow by rememberUpdatedState(following)
    val scrolling by remember(list) { derivedStateOf { list.isScrollInProgress && !followingNow() } }
    // From the scroll's start to a moment after its end.
    var held by remember(list) { mutableStateOf(false) }
    // The last message's time while the session's quiet line shows.
    var quiet by remember(sessionId, opening) { mutableStateOf<Long?>(null) }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            quiet = null
            held = true
        } else if (held) {
            delay(TIME_HOLD_MS)
            held = false
        }
    }
    // Once for each opening: coming back from a screen opened over the chat is the same one.
    val told = rememberSaveable(sessionId) { intArrayOf(Int.MIN_VALUE) }
    LaunchedEffect(sessionId, opening) {
        if (told[0] == opening) return@LaunchedEffect
        // Not on a history that's old or still coming: the Mac may have said more since.
        snapshotFlow { hub.link.state == LinkState.Connected && !hub.loadingTranscript(sessionId) && hub.chatTimes(sessionId).lastMessage != null }.first { it }
        delay(QUIET_SETTLE_MS)
        told[0] = opening
        val s = hub.session(sessionId) ?: return@LaunchedEffect
        // At work isn't quiet, even with no message for hours.
        if (held || hub.sending(sessionId) || s.state == SessionState.Working || s.agentsRunning > 0) return@LaunchedEffect
        val last = hub.chatTimes(sessionId).lastMessage ?: return@LaunchedEffect
        if (System.currentTimeMillis() - last < QUIET_AFTER_MS) return@LaunchedEffect
        quiet = last
        delay(QUIET_SHOW_MS)
        quiet = null
    }
    // To the minute, as the words are: rows of the same minute change nothing.
    val minute by remember(list, sessionId) {
        derivedStateOf { if (held) topTime(list.layoutInfo, hub.chatTimes(sessionId))?.let { it / 60_000 * 60_000 } else null }
    }
    val said = quiet
    val text = when {
        held -> minute?.let { clock.scrolled(res, it, System.currentTimeMillis()) }
        // Something newer came in: it isn't so any more.
        said != null && said == hub.chatTimes(sessionId).lastMessage -> clock.quiet(res, said, System.currentTimeMillis())
        else -> null
    }
    // Keeps the last words on screen while they fade.
    val last = remember { arrayOfNulls<String>(1) }
    if (text != null) last[0] = text
    AnimatedVisibility(
        text != null,
        modifier.offset { IntOffset(0, top()) },
        enter = if (reduced) EnterTransition.None else fadeIn(tween(Motion.DIALOG)),
        exit = if (reduced) ExitTransition.None else fadeOut(tween(Motion.REVEAL)),
    ) {
        Box(
            Modifier
                .timeGlass()
                .semantics(mergeDescendants = true) { hideFromAccessibility() }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            HText(last[0].orEmpty(), style = Type.small, color = HN.ink, maxLines = 1)
        }
    }
}

/** The message menu's first line: when it was said, to the second. */
@Composable
internal fun MessageTimeLine(at: Long) {
    val res = LocalContext.current.resources
    val clock = rememberChatClock()
    val now = remember { System.currentTimeMillis() }
    val said = clock.exactSaid(res, at, now)
    HText(clock.exact(res, at, now), style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.semantics { contentDescription = said })
    Spacer(Modifier.height(10.dp))
}

/** A dense glass capsule, the voice notes' family, so the words read over whatever is under them. */
private fun Modifier.timeGlass(): Modifier = drawWithCache {
    val r = size.height / 2f
    val body = Brush.verticalGradient(listOf(lerp(HN.bg800, HN.ink, 0.06f).copy(alpha = 0.94f), HN.bg900.copy(alpha = 0.92f)))
    val sw = 1.dp.toPx()
    val edge = Brush.verticalGradient(0f to HN.ink.copy(alpha = 0.22f), 0.5f to HN.ink.copy(alpha = 0.05f), 1f to HN.ink.copy(alpha = 0.08f))
    onDrawBehind {
        drawRoundRect(body, cornerRadius = CornerRadius(r))
        drawRoundRect(edge, topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, size.height - sw), cornerRadius = CornerRadius(r - sw / 2f), style = Stroke(sw))
    }
}
