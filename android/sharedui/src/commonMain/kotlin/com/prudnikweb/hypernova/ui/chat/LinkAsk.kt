package com.prudnikweb.hypernova.ui.chat

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Links
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.Opener
import com.prudnikweb.hypernova.ui.components.DialogButton
import com.prudnikweb.hypernova.ui.components.GlassDialog
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Where a tapped link goes (the app's [androidx.compose.ui.platform.LocalUriHandler], so every link in the drawing comes here): the web asks first ([LinkAsk]);
 * a dev server on the server opens its preview at once, as its pill does, where this system has one; anything else nowhere.
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
 * "Open this link?" with the whole address, its scheme and host bright and the rest quieter, so a lookalike shows (anything before an "@" is dim: the browser goes
 * to what follows it). Open hands it to [browse], which says whether it could; Copy goes to [copy]; Cancel.
 */
@Composable
fun LinkAsk(links: LinkHandler, toast: (String) -> Unit, browse: (url: String, done: (Boolean) -> Unit) -> Unit, copy: (String) -> Unit) {
    val url = links.asking ?: return
    val dismiss = { links.asking = null }
    val noBrowser = stringResource(Str.link_no_browser)
    GlassDialog(
        onDismiss = dismiss,
        title = stringResource(Str.link_open_title),
        secondary = DialogButton(stringResource(Str.cancel), dismiss, PillKind.Ghost),
        primary = DialogButton(stringResource(Str.link_open), {
            dismiss()
            browse(url) { opened -> if (!opened) toast(noBrowser) }
        }, PillKind.Primary),
        body = {
            SelectionContainer { HText(linkShown(url), style = Type.mono, color = HN.inkMuted) }
            Spacer(Modifier.height(12.dp))
            Pill(stringResource(Str.link_copy), {
                copy(url)
                dismiss()
            }, kind = PillKind.Outline, height = 40.dp)
        },
    )
}
