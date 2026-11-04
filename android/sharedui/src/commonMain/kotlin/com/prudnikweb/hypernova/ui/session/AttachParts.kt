package com.prudnikweb.hypernova.ui.session

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.components.glassChoice
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Attachment
import com.prudnikweb.hypernova.data.DraftFile
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.isImage
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.prudnikweb.hypernova.ui.files.sizeText
import java.util.Locale
import com.prudnikweb.hypernova.ui.files.sizeText
import com.prudnikweb.hypernova.ui.platform.SharedUi

/* The files going with a prompt, and those a prompt carried, as both systems draw them. How they're picked is each system's. */

/**
 * The files waiting in a composer: thumbnails and file tiles, each with a remove button. One whose
 * upload failed is marked red with a turning-arrow; a tap sends it again ([onRetry]).
 */
@Composable
fun DraftStrip(files: List<DraftFile>, onRemove: (DraftFile) -> Unit, modifier: Modifier = Modifier, side: Dp = 10.dp, onRetry: ((DraftFile) -> Unit)? = null) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = side)) {
        items(files, key = { it.id }) { f ->
            val uploading = f.uploading
            val failed = f.failed != null
            val retryLabel = stringResource(Str.attach_retry, f.name)
            Box(
                Modifier
                    .animateItem()
                    .then(
                        if (failed && onRetry != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = retryLabel) { onRetry(f) }
                        else Modifier,
                    ),
            ) {
                if (f.isImage) Thumb(f.thumb as? ImageBitmap, Modifier.size(64.dp), dim = uploading || failed, failed = failed)
                else FileTile(f.name, null, Modifier.height(64.dp), dim = uploading || failed, end = 36.dp, failed = failed)
                if (uploading) Spinner(Modifier.align(Alignment.Center).size(18.dp))
                if (failed) Icon(Glyph.Reload, Modifier.align(Alignment.Center), size = 18.dp, color = HN.danger)
                RemoveButton(stringResource(Str.attach_remove, f.name), { onRemove(f) }, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/** Files a prompt carried, above its text. Tap one to open it. */
@Composable
fun SentFiles(files: List<Attachment>, hub: Hub, onOpen: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in files) {
            val label = stringResource(Str.attach_open, f.name)
            val click = Modifier.then(
                if (onOpen != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = label, role = Role.Button) { onOpen(f.path) }
                else Modifier,
            )
            if (f.isImage) {
                val images = SharedUi.platform.images
                val image by produceState(images.cached(f.path), f.path) {
                    if (value != null) return@produceState
                    hub.file(f.path).onSuccess { b ->
                        withContext(Dispatchers.Default) { images.thumbnail(b.bytes) }?.let { images.keep(f.path, it); value = it }
                    }
                }
                Thumb(image, Modifier.size(88.dp).then(click).semantics { contentDescription = f.name })
            } else {
                FileTile(f.name, f.size.takeIf { it > 0 }?.let { sizeText(it) }, Modifier.height(56.dp).then(click))
            }
        }
    }
}

private val TILE = RoundedCornerShape(12.dp)

@Composable
private fun Thumb(image: ImageBitmap?, modifier: Modifier, dim: Boolean = false, failed: Boolean = false) {
    Box(
        modifier.clip(TILE).glassChoice(false, 12.dp).then(if (failed) Modifier.border(1.dp, HN.danger.copy(alpha = 0.6f), TILE) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, alpha = if (dim) 0.45f else 1f, modifier = Modifier.matchParentSize())
        } else {
            Icon(Glyph.Image, size = 20.dp, color = HN.inkDim)
        }
    }
}

@Composable
private fun FileTile(name: String, detail: String?, modifier: Modifier, dim: Boolean = false, end: Dp = 16.dp, failed: Boolean = false) {
    Row(
        modifier
            .widthIn(min = 120.dp, max = 210.dp)
            .clip(TILE)
            .glassChoice(false, 12.dp)
            .then(if (failed) Modifier.border(1.dp, HN.danger.copy(alpha = 0.6f), TILE) else Modifier)
            .padding(start = 12.dp, end = end),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.File, size = 16.dp, color = if (dim) HN.inkDim else HN.accentDim)
        Spacer(Modifier.width(8.dp))
        Column {
            HText(name, style = Type.monoSmall, color = if (dim) HN.inkMuted else HN.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            // Its kind in tracked caps, then what else is known of it, a gap apart (no "PDF · 2 MB").
            val ext = name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(5)
            if (ext.isNotEmpty() || detail != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (ext.isNotEmpty()) HText(ext, style = Type.label, color = HN.inkDim, maxLines = 1)
                if (ext.isNotEmpty() && detail != null) Spacer(Modifier.width(8.dp))
                if (detail != null) HText(detail, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            }
        }
    }
}

/** A small round × in a chip's corner, with a 32dp touch target. */
@Composable
private fun RemoveButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(32.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).glassChoice(false),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyph.Cross, size = 10.dp, color = HN.ink, stroke = 1.4.dp)
        }
    }
}

/** A turning arc: something is on its way. Still when animations are reduced. */
@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color = HN.accent) {
    val reduced = LocalMotion.current.reduced
    val turn = rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    // Read while drawing: the arc turns without recomposing anything.
    Canvas(modifier.graphicsLayer { if (!reduced) rotationZ = turn.value }) {
        val w = 1.8.dp.toPx()
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(w))
        drawArc(color, -90f, 100f, false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** The paperclip at the start of the composer; a cross while the tray is open. */
@Composable
fun AttachButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (open) Str.attach_close else Str.attach)
    Box(
        modifier
            .size(48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (open) HN.bg700 else Color.Transparent), contentAlignment = Alignment.Center) {
            Crossfade(open, label = "attach") { o ->
                if (o) Icon(Glyph.Cross, size = 14.dp, color = HN.ink) else Icon(Glyph.Clip, size = 20.dp, color = HN.inkMuted)
            }
        }
    }
}
