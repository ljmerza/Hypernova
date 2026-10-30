package com.prudnikweb.hypernova.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/*
 * Fork: mermaid diagrams in the chat. A reply's ```mermaid block, once its closing fence is in, is
 * drawn by mermaid.js (bundled in assets/mermaid, nothing from the network) in one shared WebView
 * the owner never sees, which hands back the SVG and a picture of it. The chat shows the picture,
 * so a long chat with many diagrams scrolls as images do; a tap opens the SVG full screen, where it
 * pinches and zooms. A syntax error or a failed drawing shows the code, as any code block.
 */

private const val HOST = "appassets.androidplatform.net"
private const val PAGE = "https://$HOST/mermaid/index.html"
/** The page's files, and the only requests answered: everything else gets a 404. */
private val FILES = setOf("index.html", "frames.js", "mermaid.min.js", "render.js", "Manrope.ttf")
/**
 * The full-screen view's page, made by the app for the one diagram it shows and loaded by its URL,
 * as the drawing page is (a page given as data, with a base URL, showed "not found" on the owner's
 * phone). Its files: the page and Manrope.
 */
private const val VIEW = "view.html"
private val VIEW_FILES = setOf(VIEW, "Manrope.ttf")

/** The file a request is answered with ([viewer]: the full-screen view's), or null for a 404. */
internal fun mermaidFile(scheme: String?, host: String?, path: String?, viewer: Boolean): String? {
    if (scheme != "https" || host != HOST) return null
    return path?.removePrefix("/mermaid/")?.takeIf { it in if (viewer) VIEW_FILES else FILES }
}

/** Raised when the page draws differently (its theme, its sizes), so diagrams already drawn are drawn again. */
private const val DRAWING = 1

/** The cache key for a diagram: its source (trimmed) and how the page draws. */
internal fun mermaidKey(source: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest("$DRAWING\n${source.trim()}".toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
}

/**
 * How a diagram [width] × [height] (CSS px, about dp) sits in a column [column] dp wide: never wider
 * than drawn, scaled down to fit, and shown at most [max] tall ([cut] when there's more below).
 */
internal data class DiagramFit(val width: Float, val height: Float, val shown: Float, val cut: Boolean)

internal fun diagramFit(width: Float, height: Float, column: Float, max: Float): DiagramFit {
    val w = minOf(width, column).coerceAtLeast(1f)
    val h = height * w / width.coerceAtLeast(1f)
    return DiagramFit(w, h, minOf(h, max), h > max)
}

/** What became of a diagram: drawn (a picture for the chat, the SVG for full screen), or not. */
internal sealed interface Diagram {
    class Drawn(val picture: ImageBitmap, val svg: String, val width: Float, val height: Float, val bytes: Int) : Diagram
    data object Failed : Diagram
}

/**
 * The one WebView that draws diagrams, made when the first one shows and closed after a minute with
 * none to draw. It sits in the window, 1 px and clear, so it runs as a page on screen does. One
 * diagram at a time; drawn ones are kept by key (a source drawn once is never drawn again while it's
 * kept), failures too. Main thread only.
 */
internal object MermaidRenderer {
    private val main = Handler(Looper.getMainLooper())
    private val decode = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kept = object : LruCache<String, Diagram>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Diagram) = when (value) {
            is Diagram.Drawn -> value.bytes + value.svg.length * 2
            Diagram.Failed -> 64
        }
    }
    private val waiting = HashMap<String, CompletableDeferred<Diagram?>>()
    /** Asked before the page was ready: key, source, scale. */
    private val early = ArrayList<Triple<String, String, Float>>()
    private var web: WebView? = null
    private var ready = false
    private val sleep = Runnable { close() }

    fun cached(key: String): Diagram? = kept.get(key)

    /** [source] drawn, from the cache if it's there; null when the page didn't answer (not kept, so it's tried again). */
    suspend fun draw(host: View, key: String, source: String, scale: Float): Diagram? {
        kept.get(key)?.let { return it }
        main.removeCallbacks(sleep)
        val w = web ?: open(host.context.applicationContext).also { web = it }
        // In the window it's opened from: a WebView on screen keeps its timers and frames going.
        if (!w.isAttachedToWindow) {
            (w.parent as? ViewGroup)?.removeView(w)
            (host.rootView as? ViewGroup)?.addView(w, ViewGroup.LayoutParams(1, 1))
        }
        val job = waiting[key] ?: CompletableDeferred<Diagram?>().also {
            waiting[key] = it
            if (ready) send(w, key, source, scale) else early += Triple(key, source, scale)
        }
        return withTimeoutOrNull(20_000) { job.await() }.also { if (it == null) settle(key, null) }
    }

    private fun send(w: WebView, key: String, source: String, scale: Float) {
        // The source goes in as a JSON string, never as markup.
        w.evaluateJavascript("hn.render(${JSONObject.quote(key)}, ${JSONObject.quote(source)}, $scale)", null)
    }

    private fun settle(key: String, d: Diagram?) {
        if (d != null) kept.put(key, d)
        waiting.remove(key)?.complete(d)
        if (waiting.isEmpty()) {
            main.removeCallbacks(sleep)
            main.postDelayed(sleep, 60_000)
        }
    }

    private fun close() {
        val w = web ?: return
        web = null
        ready = false
        early.clear()
        (w.parent as? ViewGroup)?.removeView(w)
        w.destroy()
        for (key in waiting.keys.toList()) settle(key, null)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun open(ctx: Context): WebView = object : WebView(ctx) {
        // Never in the way: a touch on its 1 px goes to what's under it.
        override fun dispatchTouchEvent(event: MotionEvent?) = false
    }.apply {
        alpha = 0f
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        settings.textZoom = 100
        addJavascriptInterface(Bridge(), "HN")
        webViewClient = AssetsOnly(ctx) { gone -> if (gone === web) close() }
        loadUrl(PAGE)
    }

    /** Calls from the page, moved to the main thread; a picture is decoded on the way. */
    private class Bridge {
        @JavascriptInterface fun ready() {
            main.post {
                val w = web ?: return@post
                ready = true
                for ((key, source, scale) in early) send(w, key, source, scale)
                early.clear()
            }
        }

        @JavascriptInterface fun drawn(key: String, json: String) {
            decode.launch {
                val d = runCatching {
                    val o = JSONObject(json)
                    val png = Base64.decode(o.getString("png"), Base64.DEFAULT)
                    val bmp: Bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("no picture")
                    Diagram.Drawn(bmp.asImageBitmap(), o.getString("svg"), o.getDouble("width").toFloat(), o.getDouble("height").toFloat(), bmp.byteCount)
                }.getOrElse { Diagram.Failed }
                withContext(Dispatchers.Main) { settle(key, d) }
            }
        }

        @JavascriptInterface fun failed(key: String, @Suppress("UNUSED_PARAMETER") why: String) {
            main.post { settle(key, Diagram.Failed) }
        }
    }
}

/**
 * Serves the page's own files from the app (assets/mermaid, and Manrope from res/font) and answers
 * every other request with a 404, so nothing reaches the network; no navigation. With a [page], it
 * serves the full-screen view instead: that page as view.html, and Manrope. [onGone]: the
 * WebView's renderer died (handled, so the app lives on).
 */
private class AssetsOnly(
    private val ctx: Context,
    private val page: String? = null,
    private val onGone: (WebView) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val u = request.url
        val name = mermaidFile(u.scheme, u.host, u.path, viewer = page != null) ?: return notFound()
        return try {
            if (name == "Manrope.ttf") WebResourceResponse("font/ttf", null, ctx.resources.openRawResource(R.font.manrope))
            else if (name == VIEW) WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(checkNotNull(page).toByteArray(Charsets.UTF_8)))
            else WebResourceResponse(
                when (name.substringAfterLast('.')) { "html" -> "text/html"; else -> "text/javascript" },
                "utf-8",
                ctx.assets.open("mermaid/$name"),
            )
        } catch (_: Exception) {
            notFound()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone(view)
        return true
    }
}

private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** The tallest a diagram shows in the chat; a taller one is cut under a fade, and a tap shows it whole. */
private val MAX_INLINE = 400.dp

/**
 * A ```mermaid block: the diagram in the code block's frame, with </> in its corner to see the code.
 * The code shows while it's drawn, and instead of it when mermaid can't draw it.
 */
@Composable
internal fun MermaidBlock(source: String) {
    val key = remember(source) { mermaidKey(source) }
    val host = LocalView.current
    val scale = LocalDensity.current.density
    var diagram by remember(key) { mutableStateOf(MermaidRenderer.cached(key)) }
    LaunchedEffect(key) {
        if (diagram == null) diagram = MermaidRenderer.draw(host, key, source, scale)
    }
    val drawn = diagram as? Diagram.Drawn
    if (drawn == null) {
        CodeBlock(source)
        return
    }
    var code by rememberSaveable(key) { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        if (code) CodeBlock(source, endPad = 52.dp) else DiagramPicture(drawn) { full = true }
        CodeToggle(code, { code = it }, Modifier.align(Alignment.TopEnd))
    }
    if (full) DiagramFullScreen(drawn) { full = false }
}

@Composable
private fun DiagramPicture(d: Diagram.Drawn, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val said = stringResource(R.string.mermaid_diagram)
    val open = stringResource(R.string.mermaid_open)
    // Read when tapped: the gesture outlives a recomposition.
    val opening by rememberUpdatedState(onOpen)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HN.bg950.copy(alpha = 0.86f))
            .border(1.dp, HN.line, shape)
            .tapOnly(open) { opening() }
            .semantics { contentDescription = said },
    ) {
        val fit = diagramFit(d.width, d.height, (maxWidth - 24.dp).value, MAX_INLINE.value)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .height(fit.shown.dp)
                .clipToBounds(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Image(
                d.picture,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true).size(fit.width.dp, fit.height.dp),
            )
        }
        if (fit.cut) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, HN.bg950))),
            )
            HText(
                stringResource(R.string.mermaid_tap_expand).uppercase(), style = Type.label, color = HN.inkDim,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
        }
    }
}

/**
 * A tap, without taking the press: a long-press still reaches the message under it (the chat's
 * menu, or selecting text), and a scroll stays a scroll.
 */
private fun Modifier.tapOnly(label: String, onTap: () -> Unit): Modifier = this
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            if (up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) onTap()
        }
    }
    .semantics { role = Role.Button; onClick(label) { onTap(); true } }

/** </> over the diagram's corner: the code, and back. */
@Composable
private fun CodeToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (on) R.string.mermaid_show_diagram else R.string.mermaid_show_code)
    Box(
        modifier
            .size(48.dp)
            .toggleable(on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 36.dp, height = 26.dp).glassChoice(on, radius = 8.dp), contentAlignment = Alignment.Center) {
            HText("</>", style = Type.monoSmall, color = if (on) HN.accent else HN.inkMuted, maxLines = 1)
        }
    }
}

/** The diagram full screen, as SVG: pinch to zoom, drag to move. JavaScript off; Manrope is all it loads. */
@Composable
private fun DiagramFullScreen(d: Diagram.Drawn, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(HN.bg)) {
            AndroidView(
                factory = { ctx -> viewer(ctx, d.svg, onDismiss) },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            )
            GlyphButton(
                Glyph.Cross, stringResource(R.string.mermaid_close), onDismiss,
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(8.dp),
            )
        }
    }
}

private fun viewer(ctx: Context, svg: String, onGone: () -> Unit): WebView = WebView(ctx).apply {
    setBackgroundColor(HN.bg.toArgb())
    settings.javaScriptEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.textZoom = 100
    webViewClient = AssetsOnly(ctx, viewerPage(svg)) { onGone() }
    loadUrl("https://$HOST/mermaid/$VIEW")
}

/**
 * The full-screen page: the SVG fitted to the width (never past its own size), centred on the app's
 * dark. No scripts; the SVG keeps its own styles, and any picture it carries as data.
 */
internal fun viewerPage(svg: String) = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=8, user-scalable=yes">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; font-src https://$HOST; img-src data:">
<style>
@font-face { font-family: "Manrope"; src: url("Manrope.ttf") format("truetype"); font-weight: 200 800; }
html, body { margin: 0; background: #14110E; }
body { min-height: 100vh; display: flex; padding: 56px 16px 24px; box-sizing: border-box; }
svg { display: block; margin: auto; max-width: 100%; height: auto; }
</style></head><body>$svg</body></html>"""
