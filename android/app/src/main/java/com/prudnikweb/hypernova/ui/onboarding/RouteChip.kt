package com.prudnikweb.hypernova.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Which route the app uses now ([Mac.via]), drawn: a capsule with its glyph and word. Local: the
 * home network's arcs. The base URL as it is: the globe.
 */
@Composable
fun RouteChip(via: String, modifier: Modifier = Modifier) {
    val local = via == Mac.VIA_LOCAL
    val said = stringResource(if (local) R.string.route_said_local else R.string.route_said_remote)
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = said }
            .frostedGlass(if (local) HN.accent else null, glowX = 14.dp)
            .heightIn(min = 26.dp)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val caps = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.6f)
        if (local) HomeNetIcon(HN.accent) else Icon(Glyph.Globe, size = 12.dp, color = HN.inkDim, stroke = 1.2.dp)
        Spacer(Modifier.width(6.dp))
        HText(stringResource(if (local) R.string.route_chip_local else R.string.route_chip_remote).uppercase(), style = caps, color = HN.inkMuted, maxLines = 1)
    }
}

/** Settings' route line: its key as an info row has it, and the [RouteChip] at the end. */
@Composable
fun RouteRow(key: String, via: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        HText(key, style = Type.small, color = HN.inkDim)
        Spacer(Modifier.weight(1f))
        RouteChip(via)
    }
}

/** The home network, 12dp in the icon set's hairline: two arcs over a point. */
@Composable
internal fun HomeNetIcon(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val st = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(color, 225f, 90f, false, Offset(w * 0.06f, w * 0.18f), Size(w * 0.88f, w * 0.88f), style = st)
        drawArc(color, 225f, 90f, false, Offset(w * 0.26f, w * 0.4f), Size(w * 0.48f, w * 0.48f), style = st)
        drawCircle(color, w * 0.08f, Offset(w * 0.5f, w * 0.8f))
    }
}
