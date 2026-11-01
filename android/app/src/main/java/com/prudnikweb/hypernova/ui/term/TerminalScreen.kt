package com.prudnikweb.hypernova.ui.term

import android.annotation.SuppressLint
import com.prudnikweb.hypernova.ui.components.glassChoice
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphButton
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException

private enum class Status { Connecting, Live, Lost, Ended }
private data class Info(val cols: Int, val rows: Int, val mac: Int)

/**
 * A session's live terminal: the real Claude Code screen from the Mac, drawn by xterm.js
 * (bundled in the app) over a WebSocket from the daemon. The key bar adds what a phone
 * keyboard lacks. The terminal takes the phone's size while you use it here and goes back to
 * the Mac's when you type there. A finger scrolls its history (assets/term/index.html), and
 * "Jump to latest" brings it back. Settings → Terminal engine (testing) draws it with Ghostty's
 * terminal core instead, as a trial (TermEngine, assets/term-ghostty/).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun TerminalScreen(hub: Hub, sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val session = hub.session(sessionId)
    // The page is picked as the terminal opens: switching engines takes effect on the next one.
    val engine = remember { TermEnginePref.state(context).value }
    var web by remember { mutableStateOf<WebView?>(null) }
    // Sticky Ctrl: the next letter typed goes as its control code (Ctrl+R, O, T, B in Claude Code).
    var ctrl by remember { mutableStateOf(false) }
    LaunchedEffect(ctrl, web) {
        (web as? TermWebView)?.let { w ->
            w.ctrlOnce = ctrl
            w.onCtrlUsed = { ctrl = false }
        }
    }
    var ready by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(Status.Connecting) }
    var problem by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<Info?>(null) }
    // Bumped to (re)connect; the screen also reconnects when the app comes back.
    var attempt by remember { mutableIntStateOf(0) }
    var retries by remember { mutableIntStateOf(0) }
    // When this connection first came alive: only one that lasted resets the retry count.
    var liveSince by remember { mutableStateOf(0L) }
    var visible by remember { mutableStateOf(true) }
    // Scrolled back through the history: "Jump to latest" shows.
    var away by remember { mutableStateOf(false) }
    val term = session?.term

    // On the local route the app holds the socket and passes its frames to the page (AppSocket).
    val socket = remember {
        AppSocket { js -> web?.evaluateJavascript(js, null) }
    }
    val bridge = remember {
        TermBridge(
            socket = socket,
            onReady = { ready = true },
            onState = { json ->
                runCatching { JSONObject(json) }.getOrNull()?.let { o ->
                    info = Info(o.optInt("cols"), o.optInt("rows"), o.optInt("mac"))
                }
                status = Status.Live
                if (liveSince == 0L) liveSince = System.currentTimeMillis()
            },
            onClosed = {
                if (liveSince > 0L && System.currentTimeMillis() - liveSince > 10_000) retries = 0
                liveSince = 0L
                if (status != Status.Ended && visible) {
                    // A dropped link (Wi-Fi to cellular, a slow moment) comes back by itself a few times.
                    if (retries < 3) { retries++; status = Status.Connecting; attempt++ } else status = Status.Lost
                }
            },
            onExited = { status = Status.Ended },
            onTapped = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) },
            onScrolled = { away = it },
        )
    }

    // The session left its terminal (it ended, or the terminal closed).
    LaunchedEffect(term == null, session == null) {
        if (session == null || (term == null && status != Status.Connecting)) status = Status.Ended
    }

    LaunchedEffect(ready, attempt, visible) {
        if (!ready || !visible || status == Status.Ended) return@LaunchedEffect
        if (retries > 0) delay(700L * retries)
        status = Status.Connecting
        hub.terminal(sessionId)
            .onSuccess { ticket ->
                val client = ticket.client
                // No URL for the page: it asks the app for the socket prepared here.
                if (client != null) {
                    socket.prepare(ticket.url, client)
                    web?.evaluateJavascript("hn.connect(null)", null)
                } else {
                    socket.forget()
                    web?.evaluateJavascript("hn.connect(${JSONObject.quote(ticket.url)})", null)
                }
            }
            .onFailure {
                problem = it.message
                status = if (hub.session(sessionId)?.term == null) Status.Ended else Status.Lost
            }
    }

    // In the background the socket closes; it reopens (with a fresh screen) when you come back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { visible = false; web?.evaluateJavascript("hn.close()", null); socket.closeAll() }
                Lifecycle.Event.ON_START -> if (!visible) { visible = true; retries = 0; attempt++ }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) {
        onDispose {
            web?.evaluateJavascript("hn.close()", null)
            // The page's own socket goes with the WebView; the app's must be closed by the app.
            socket.closeAll()
            web?.destroy()
        }
    }

    val firstHint = remember { firstTime(context) }
    LaunchedEffect(Unit) { if (firstHint) toast(context.getString(R.string.terminal_hint)) }

    fun key(name: String) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (name == "paste") {
            val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (text.isNotEmpty()) web?.evaluateJavascript("hn.paste(${JSONObject.quote(text)})", null)
            return
        }
        web?.evaluateJavascript("hn.key('$name')", null)
    }
    fun keyboard() {
        val w = web ?: return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (androidx.core.view.ViewCompat.getRootWindowInsets(w)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) {
            imm?.hideSoftInputFromWindow(w.windowToken, 0)
        } else {
            w.requestFocus()
            w.evaluateJavascript("hn.focus()", null)
            imm?.showSoftInput(w, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // A phrase, not "120 × 40 · Also open on your Mac".
    val size = info?.let { stringResource(R.string.terminal_size, it.cols, it.rows) }
    val onMac = (info?.mac ?: term?.mac ?: 0) > 0
    val subtitle = when {
        size != null && onMac -> stringResource(R.string.terminal_subtitle_mac, size)
        size != null -> size
        onMac -> stringResource(R.string.terminal_also_mac)
        else -> session?.project ?: ""
    }

    ScreenScaffold(
        sourceKey = "terminal:$sessionId",
        title = session?.name ?: stringResource(R.string.terminal),
        subtitle = subtitle,
        onBack = onBack,
        actions = {
            // A window on the computer's own screen: a Mac's (a Linux server has none to open).
            if (term != null && hub.mac.has(com.prudnikweb.hypernova.data.Mac.FEATURE_OPEN_WINDOW)) {
                GlyphButton(Glyph.Laptop, stringResource(R.string.open_on_mac), {
                    scope.launch {
                        hub.openOnMac(sessionId)
                            .onSuccess { (app, already) -> toast(context.getString(if (already) R.string.already_on_mac else R.string.opened_on_mac, app)) }
                            .onFailure { toast(it.message.orEmpty()) }
                    }
                })
            }
        },
    ) { top, _ ->
        // Native views can't sit under the blurred bar, so the terminal starts below it.
        Column(
            Modifier
                .fillMaxSize()
                .background(HN.bg)
                .padding(top = top + 10.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> terminalWebView(ctx, bridge, engine).also { web = it } },
                )
                // The plain one: inside the Column, ColumnScope's would be picked.
                androidx.compose.animation.AnimatedVisibility(
                    away && status == Status.Live,
                    Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp),
                    enter = fadeIn(tween(Motion.FAST)),
                    exit = fadeOut(tween(Motion.FAST)),
                ) {
                    Pill(stringResource(R.string.jump_latest), { web?.evaluateJavascript("hn.latest()", null) }, kind = PillKind.Outline, height = 40.dp)
                }
                when (status) {
                    Status.Connecting -> if (info == null) LoadingLine(stringResource(R.string.terminal_connecting), Modifier.padding(20.dp))
                    Status.Lost -> Notice(problem ?: stringResource(R.string.terminal_lost)) {
                        Pill(stringResource(R.string.terminal_reconnect), { problem = null; retries = 0; attempt++ }, kind = PillKind.Primary, height = 44.dp, leading = Glyph.Reload)
                    }
                    Status.Ended -> Notice(stringResource(R.string.terminal_ended)) {
                        Pill(stringResource(R.string.back), onBack, kind = PillKind.Outline, height = 44.dp)
                    }
                    Status.Live -> {}
                }
            }
            KeyBar(enabled = status == Status.Live, ctrl = ctrl, onCtrl = { ctrl = !ctrl }, onKey = ::key, onKeyboard = ::keyboard)
        }
    }
}

@Composable
private fun Notice(text: String, action: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(HN.bg.copy(alpha = 0.72f)).padding(20.dp), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                HText(text, style = Type.body, color = HN.ink)
                Spacer(Modifier.height(14.dp))
                action()
            }
        }
    }
}

/**
 * What a phone keyboard lacks for Claude Code: Esc, Tab, Shift+Tab (modes), a sticky Ctrl, arrows,
 * ^C, Paste, and Enter. The rest scrolls sideways; Enter and the keyboard stay at the end, always
 * in reach on a narrow phone. The keys are drawn 36dp tall in a 48dp bar, and the bar is all touch
 * target: a key takes the taps around it up to its neighbours'.
 */
@Composable
private fun KeyBar(enabled: Boolean, ctrl: Boolean, onCtrl: () -> Unit, onKey: (String) -> Unit, onKeyboard: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(HN.bg950),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 7.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cap(stringResource(R.string.key_esc), null, enabled) { onKey("esc") }
            Cap(stringResource(R.string.key_tab), null, enabled) { onKey("tab") }
            Cap(stringResource(R.string.key_stab), null, enabled) { onKey("stab") }
            Cap(stringResource(R.string.key_ctrl), stringResource(if (ctrl) R.string.key_ctrl_on else R.string.key_ctrl_said), enabled, accent = ctrl, onClick = onCtrl)
            Cap("↑", stringResource(R.string.key_up), enabled) { onKey("up") }
            Cap("↓", stringResource(R.string.key_down), enabled) { onKey("down") }
            Cap("←", stringResource(R.string.key_left), enabled) { onKey("left") }
            Cap("→", stringResource(R.string.key_right), enabled) { onKey("right") }
            Cap(stringResource(R.string.key_ctrlc), null, enabled) { onKey("ctrlc") }
            Cap("/", null, enabled) { onKey("slash") }
            Cap(stringResource(R.string.key_paste), null, enabled) { onKey("paste") }
        }
        Row(Modifier.padding(end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Cap(stringResource(R.string.key_enter), null, enabled, accent = true) { onKey("enter") }
            Cap(null, stringResource(R.string.key_keyboard), true, glyph = Glyph.Keyboard) { onKeyboard() }
        }
    }
}

@Composable
private fun Cap(text: String?, label: String?, enabled: Boolean, accent: Boolean = false, glyph: Glyph? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val fg = when {
        !enabled -> HN.inkGhost
        accent -> HN.accent
        else -> HN.ink
    }
    // The touch target: the bar's whole 48dp height, and half the 6dp gap on each side.
    Box(
        Modifier
            .height(48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(36.dp)
                .widthIn(min = 38.dp)
                .clip(shape)
                .glassChoice(accent && enabled, 10.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (glyph != null) Icon(glyph, size = 16.dp, color = fg) else HText(text.orEmpty(), style = Type.monoSmall, color = fg, maxLines = 1)
        }
    }
}

/**
 * The terminal's WebView takes the keyboard itself: xterm.js's hidden text box drops and
 * repeats letters with Android keyboards. Like Termux, it asks for a plain keyboard (no
 * suggestions or autocorrect) and turns every key into the bytes a terminal sends.
 */
@SuppressLint("ViewConstructor")
private class TermWebView(ctx: Context) : WebView(ctx) {
    /** The key bar's sticky Ctrl is on: the next letter typed goes as its control code. */
    var ctrlOnce = false
    var onCtrlUsed: (() -> Unit)? = null

    fun send(text: String) {
        if (text.isEmpty()) return
        var t = text
        if (ctrlOnce) {
            ctrlOnce = false
            onCtrlUsed?.invoke()
            ctrlCode(t[0])?.let { t = it + t.substring(1) }
        }
        evaluateJavascript("hn.text(${JSONObject.quote(t)})", null)
    }
    private fun named(name: String) = evaluateJavascript("hn.key('$name')", null)

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                // A committed word replaces whatever was being composed.
                composing = ""
                send(text.toString().replace("\r\n", "\r").replace('\n', '\r'))
                return true
            }
            // Some keyboards compose anyway: the word goes out when it's finished.
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                return true
            }
            override fun finishComposingText(): Boolean {
                send(composing)
                composing = ""
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) composing = composing.dropLast(beforeLength.coerceAtLeast(1))
                else repeat(beforeLength.coerceAtLeast(1)) { send("\u007f") }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchKeyEvent(event)
            override fun performEditorAction(actionCode: Int): Boolean {
                send("\r")
                return true
            }
            private var composing = ""
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || KeyEvent.isModifierKey(code) || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
            send(event.characters)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (code) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> send("\r")
            KeyEvent.KEYCODE_DEL -> send("\u007f")
            KeyEvent.KEYCODE_FORWARD_DEL -> send("\u001b[3~")
            KeyEvent.KEYCODE_TAB -> send(if (event.isShiftPressed) "\u001b[Z" else "\t")
            KeyEvent.KEYCODE_ESCAPE -> send("\u001b")
            KeyEvent.KEYCODE_DPAD_UP -> named("up")
            KeyEvent.KEYCODE_DPAD_DOWN -> named("down")
            KeyEvent.KEYCODE_DPAD_LEFT -> named("left")
            KeyEvent.KEYCODE_DPAD_RIGHT -> named("right")
            KeyEvent.KEYCODE_MOVE_HOME -> send("\u001b[H")
            KeyEvent.KEYCODE_MOVE_END -> send("\u001b[F")
            KeyEvent.KEYCODE_PAGE_UP -> send("\u001b[5~")
            KeyEvent.KEYCODE_PAGE_DOWN -> send("\u001b[6~")
            else -> {
                val c = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
                if (c == 0) return super.dispatchKeyEvent(event)
                var s = String(Character.toChars(c))
                // Ctrl+letter is its control code; Alt is an Esc prefix.
                if (event.isCtrlPressed && c in 0x40..0x7f) s = (c and 0x1f).toChar().toString()
                if (event.isAltPressed) s = "\u001b$s"
                send(s)
            }
        }
        return true
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun terminalWebView(ctx: Context, bridge: TermBridge, engine: TermEngine): WebView = TermWebView(ctx).apply {
    // Without this a WebView in Compose wraps its content, and this page's content is all positioned: 0 px tall.
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(HN.bg.toArgb())
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.textZoom = 100
    // Debug builds talk to the emulator's Mac over plain http.
    if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    overScrollMode = WebView.OVER_SCROLL_NEVER
    addJavascriptInterface(bridge, "HN")
    webViewClient = object : WebViewClient() {
        // The page and its files come from the app's own assets, nothing else.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            if (u.host != TERM_HOST) return null
            val asset = termAsset(u.path) ?: return notFound()
            val mime = when (asset.substringAfterLast('.')) {
                "html" -> "text/html"
                "js" -> "text/javascript"
                "css" -> "text/css"
                "ttf" -> "font/ttf"
                else -> "application/octet-stream"
            }
            return try {
                WebResourceResponse(mime, "utf-8", view.context.assets.open(asset))
            } catch (_: IOException) {
                notFound()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
    }
    loadUrl(engine.page)
}

/** Ctrl with [c]: a letter or one of @[\\]^_ as its control code (Ctrl+R is 0x12); null for anything else. */
internal fun ctrlCode(c: Char): Char? {
    val u = c.uppercaseChar()
    return if (u in '@'..'_') (u.code and 0x1f).toChar() else null
}

private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun firstTime(ctx: Context): Boolean {
    val p = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
    if (p.getBoolean("terminalHinted", false)) return false
    p.edit().putBoolean("terminalHinted", true).apply()
    return true
}

/**
 * Calls from the page: its events, moved to the main thread, and, on the local route, its socket
 * (the app's, [AppSocket]), straight from the bridge's thread so a keystroke doesn't wait for the
 * main thread.
 */
private class TermBridge(
    private val socket: AppSocket,
    private val onReady: () -> Unit,
    private val onState: (String) -> Unit,
    private val onClosed: (Int) -> Unit,
    private val onExited: (Int) -> Unit,
    private val onTapped: () -> Unit,
    private val onScrolled: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun ready() { main.post(onReady) }
    @JavascriptInterface fun state(json: String) { main.post { onState(json) } }
    @JavascriptInterface fun closed(code: Int) { main.post { onClosed(code) } }
    @JavascriptInterface fun exited(code: Int) { main.post { onExited(code) } }
    @JavascriptInterface fun tapped() { main.post(onTapped) }
    @JavascriptInterface fun scrolled(away: Boolean) { main.post { onScrolled(away) } }
    @JavascriptInterface fun open(cols: Int, rows: Int): Int = socket.open(cols, rows)
    @JavascriptInterface fun send(id: Int, base64: String) = socket.send(id, base64)
    @JavascriptInterface fun sendText(id: Int, text: String) = socket.sendText(id, text)
    @JavascriptInterface fun shut(id: Int) = socket.shut(id)
    @JavascriptInterface fun took() = socket.took()
}
