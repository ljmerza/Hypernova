package com.prudnikweb.hypernova.ui.components

import com.prudnikweb.hypernova.ui.chat.LinkAsk
import com.prudnikweb.hypernova.ui.chat.LinkHandler

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Links

/** The link's question ([LinkAsk]) on the phone: its browser, its clipboard. */
@Composable
fun LinkConfirmHost(links: LinkHandler, toast: (String) -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.chat_copied)
    LinkAsk(
        links, toast,
        browse = { url, done -> done(openInBrowser(context, url)) },
        copy = { url ->
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(url, url))
            // Android 13 and newer show their own "copied" preview.
            if (android.os.Build.VERSION.SDK_INT < 33) toast(copied)
        },
    )
}

/** The phone's browser (or the app that owns the address), only for http and https. */
private fun openInBrowser(context: Context, url: String): Boolean {
    if (Links.target(url) !is Links.Target.Web) return false
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try { context.startActivity(i); true } catch (_: ActivityNotFoundException) { false }
}
