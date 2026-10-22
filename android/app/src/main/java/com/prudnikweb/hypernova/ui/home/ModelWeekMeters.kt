package com.prudnikweb.hypernova.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ModelWeek
import com.prudnikweb.hypernova.data.ModelWeeks
import com.prudnikweb.hypernova.data.UsageProjection
import com.prudnikweb.hypernova.ui.components.ProjectionMeter
import com.prudnikweb.hypernova.ui.components.projectionSaid

/**
 * One meter for each model with a week of its own ("[FABLE] 7 DAYS 41%  ↻ Tue 20:00": the model as
 * its chip before the window's label, never "Fable · week"), under the
 * 5-hour and weekly meters and in their style (ui/components/ProjectionMeter.kt): on Home's usage
 * card and in the chat's usage breakdown. They have no pace (the Mac reads them off /usage every
 * 15 minutes), so they're plain meters: what's used, and the reset as the one tag, a weekday and
 * time more than about a day off, a countdown nearer. The sun stays on the 5-hour window. Nothing
 * when the Mac hasn't read any.
 */
@Composable
internal fun ModelWeekMeters(weeks: List<ModelWeek>, now: Long, gap: Dp = 14.dp) {
    if (weeks.isEmpty()) return
    val ctx = LocalContext.current
    val res = ctx.resources
    val is24 = DateFormat.is24HourFormat(ctx)
    for (w in weeks) {
        Spacer(Modifier.height(gap))
        val trail = ModelWeeks.trail(w, now)
        val p = UsageProjection.of(w.pct, if (trail == ModelWeeks.Trail.None) 0L else w.resetsAt ?: 0L, now, perMin = null, limited = w.full)
        // A reset more than about a day off reads as a weekday and time; nearer, as a countdown.
        // TalkBack hears the day in full, "on Tuesday at 3:56 PM", not "in Tue 3:56 PM".
        val on = trail as? ModelWeeks.Trail.On
        val resetText = on?.let { ModelWeeks.weekdayTime(it.at, is24) }
        val resetAt = on?.let { ModelWeeks.weekdayTimeSaid(it.at, is24) }?.let { (day, time) -> stringResource(R.string.usage_reset_day_at, day, time) }
        ProjectionMeter(
            stringResource(R.string.seven_days), p,
            projectionSaid(res, stringResource(R.string.usage_name_model, w.label), p, resetAt),
            resetText = resetText,
            model = w.label,
        )
    }
}
