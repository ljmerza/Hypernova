package com.prudnikweb.hypernova.ui.components

import com.prudnikweb.hypernova.ui.chat.linkShown
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Links
import com.prudnikweb.hypernova.ui.Opener
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Where a tapped link goes (the app's [androidx.compose.ui.platform.LocalUriHandler], so every
 * [LinkAnnotation.Url] comes here): the web asks first ([LinkConfirmHost]); a dev server on the Mac
 * opens its preview at once, as its pill does; anything else nowhere.
 */
@Stable
class LinkHandler(private val opener: Opener) : UriHandler {
    /** The address waiting for Open or Cancel. */
    var asking by mutableStateOf<String?>(null)

    override fun openUri(uri: String) {
        when (val t = Links.target(uri)) {
            is Links.Target.Web -> asking = t.url
            is Links.Target.Local -> opener.preview(null, t.port, "localhost:${t.port}")
            Links.Target.Never -> Unit
        }
    }
}

/**
 * "Open this link?" with the whole address, its scheme and host bright and the rest quieter, so a
 * lookalike shows (anything before an "@" is dim: the browser goes to what follows it). Open hands
 * it to the browser; Copy; Cancel.
 */
@Composable
fun LinkConfirmHost(links: LinkHandler, toast: (String) -> Unit) {
    val url = links.asking ?: return
    val context = LocalContext.current
    val dismiss = { links.asking = null }
    val noBrowser = stringResource(R.string.link_no_browser)
    val copied = stringResource(R.string.chat_copied)
    GlassDialog(
        onDismiss = dismiss,
        title = stringResource(R.string.link_open_title),
        secondary = DialogButton(stringResource(R.string.cancel), dismiss, PillKind.Ghost),
        primary = DialogButton(stringResource(R.string.link_open), {
            dismiss()
            if (!openInBrowser(context, url)) toast(noBrowser)
        }, PillKind.Primary),
        body = {
            SelectionContainer { HText(linkShown(url), style = Type.mono, color = HN.inkMuted) }
            Spacer(Modifier.height(12.dp))
            Pill(stringResource(R.string.link_copy), {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(url, url))
                dismiss()
                // Android 13 and newer show their own "copied" preview.
                if (android.os.Build.VERSION.SDK_INT < 33) toast(copied)
            }, kind = PillKind.Outline, height = 40.dp)
        },
    )
}

/** The phone's browser (or the app that owns the address), only for http and https. */
private fun openInBrowser(context: Context, url: String): Boolean {
    if (Links.target(url) !is Links.Target.Web) return false
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try { context.startActivity(i); true } catch (_: ActivityNotFoundException) { false }
}
