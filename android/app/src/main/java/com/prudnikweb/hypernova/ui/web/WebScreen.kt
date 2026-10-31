package com.prudnikweb.hypernova.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ActiveRoutes
import com.prudnikweb.hypernova.data.Api
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DlState
import com.prudnikweb.hypernova.data.FileFailure
import com.prudnikweb.hypernova.data.Net
import com.prudnikweb.hypernova.data.PageError
import com.prudnikweb.hypernova.data.WebTitle
import com.prudnikweb.hypernova.ui.files.DownloadDock
import com.prudnikweb.hypernova.ui.files.DownloadJob
import com.prudnikweb.hypernova.ui.files.downloadDir
import com.prudnikweb.hypernova.ui.files.rememberFileActions
import kotlinx.coroutines.CancellationException
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphButton
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * A web page from the server (an HTML preview, a dev server, the testing checklist) in a
 * WebView under the app's own top bar. Back goes back in the page's history first. File
 * inputs open the system picker, so the checklist's screenshot picker works.
 *
 * On the local route the page comes from the server's own listener (data/LanRoute.kt, [ActiveRoutes]):
 * the WebView then trusts exactly the pinned certificate and presents this phone's device
 * certificate to that listener, as the app's own requests do. A browser outside the app can't,
 * so "Open in browser" hands it the same page by the paired base URL.
 *
 * [preview]: a localhost link or a file opened as a page, titled with what was tapped ([title]) and
 * never the server's address it comes through (data/WebTitle.kt); [port]: its dev server's.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String, title: String, onBack: () -> Unit, preview: Boolean = false, port: Int? = null) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    // A failed page, in words: a string resource (data/PageError.kt), never the web view's "net::ERR_…".
    var failed by remember { mutableStateOf<Int?>(null) }
    var download by remember { mutableStateOf<DownloadJob?>(null) }
    val scope = rememberCoroutineScope()
    val fileActions = rememberFileActions()
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var pendingFiles by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val failedText = stringResource(R.string.page_failed)
    val local = remember(url) { ActiveRoutes.localPage(url) }
    var gone by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uris = if (r.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data) else null
        pendingFiles?.onReceiveValue(uris)
        pendingFiles = null
    }

    BackHandler(enabled = canGoBack) { web?.goBack() }
    DisposableEffect(Unit) {
        onDispose {
            gone = true
            pendingFiles?.onReceiveValue(null)
            web?.destroy()
        }
    }

    val address = Uri.parse(web?.url ?: url).let { u -> (u.host ?: "") + (u.port.takeIf { it > 0 }?.let { ":$it" } ?: "") }
    val bar = WebTitle.bar(title, pageTitle, address, preview)
    ScreenScaffold(
        sourceKey = "web:$url",
        title = bar.title,
        subtitle = bar.subtitle,
        onBack = onBack,
        actions = {
            GlyphButton(Glyph.Reload, stringResource(R.string.reload), { failed = null; web?.reload() })
            GlyphButton(Glyph.Globe, stringResource(R.string.open_in_browser), {
                try {
                    val here = web?.url ?: url
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(local?.outside(here) ?: here)))
                } catch (_: ActivityNotFoundException) {
                }
            })
        },
    ) { top, _ ->
        // The WebView sits below the bar (a native view can't be blurred under it).
        // …and above the gesture bar, so a page's fixed footer stays visible.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize().padding(top = top, bottom = navBottom)) {
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (progress in 0.01f..0.99f) Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(HN.accent))
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(HN.bg.toArgb())
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.allowFileAccess = false
                            settings.mediaPlaybackRequiresUserGesture = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme
                                    if (scheme == "http" || scheme == "https") return false
                                    // mailto:, intent: and friends go to the system.
                                    try { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: ActivityNotFoundException) { }
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    canGoBack = view.canGoBack()
                                    pageTitle = view.title
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) failed = PageError.text(error.errorCode)
                                }

                                // A dev server that stopped answers the server's proxy with a 502/503/504.
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                    if (request.isForMainFrame && errorResponse.statusCode in 502..504) failed = PageError.text(0, errorResponse.statusCode, port)
                                }

                                // The server's listener has a self-signed certificate: go on for the pinned one
                                // alone; anything else is refused, as the WebView would anyway.
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    if (local?.trusts(error.certificate?.x509Certificate) == true) handler.proceed() else handler.cancel()
                                }

                                // The device certificate goes to the paired server's listener only; any other server asking gets none.
                                override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                                    val id = local?.identityFor(request.host, request.port)
                                    if (id != null) request.proceed(id.key, id.chain) else super.onReceivedClientCertRequest(view, request)
                                }
                            }
                            // A page's download link goes to the same downloader as a file in the viewer: progress, Cancel, Share, Save.
                            setDownloadListener { dlUrl, _, disposition, mime, _ ->
                                val pairing = Core.pairing ?: return@setDownloadListener
                                val api = Api(pairing.baseUrl, null)
                                val fileName = URLUtil.guessFileName(dlUrl, disposition, mime)
                                val job = DownloadJob(scope, fileName, mime, downloadDir(ctx, dlUrl)) { t, r, st, p ->
                                    try { Result.success(api.download(dlUrl, t, r, st, p)) }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Throwable) { Result.failure(FileFailure.of(Net.classify(e), null)) }
                                }
                                download?.cancel()
                                download = job
                                job.run()
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress / 100f
                                }

                                override fun onReceivedTitle(view: WebView, t: String?) {
                                    pageTitle = t
                                }

                                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                                    pendingFiles?.onReceiveValue(null)
                                    pendingFiles = callback
                                    return try {
                                        picker.launch(params.createIntent())
                                        true
                                    } catch (_: ActivityNotFoundException) {
                                        pendingFiles = null
                                        callback.onReceiveValue(null)
                                        false
                                    }
                                }
                            }
                            // The WebView keeps a client-certificate answer per address for the app's
                            // life: cleared first, so a renewed certificate (or none before) isn't reused.
                            if (local != null) WebView.clearClientCertPreferences { if (!gone) loadUrl(url) } else loadUrl(url)
                            web = this
                        }
                    },
                )
                val f = failed
                if (f != null) {
                    Column(Modifier.fillMaxSize().background(HN.bg).padding(24.dp)) {
                        HText(failedText, style = Type.title, color = HN.ink)
                        Spacer(Modifier.height(8.dp))
                        HText(if (f == R.string.pg_dev_server_port && port != null) stringResource(f, port) else stringResource(f), style = Type.body, color = HN.inkMuted)
                        Spacer(Modifier.height(18.dp))
                        Pill(stringResource(R.string.reload), { failed = null; web?.reload() }, kind = PillKind.Outline, height = 44.dp)
                    }
                }
                download?.let { job ->
                    androidx.compose.runtime.LaunchedEffect(job.state) { if (job.state is DlState.Idle) download = null }
                    DownloadDock(job, fileActions, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }
    }
}

