package com.prudnikweb.hypernova.ui.mcp

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Mcp
import com.prudnikweb.hypernova.data.McpLiveTool
import com.prudnikweb.hypernova.data.McpLogin
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/*
 * What Settings' MCP screens and the session sheet's MCP tab say the same way, in one place: a status's word and colour, a scope's word,
 * a tool's row and sheet, and the sign-in sheet. Nothing here talks to a hub.
 */

/** The status's word. Null: not asked yet, or it isn't in the answer. */
@StringRes
internal fun mcpStatusWord(status: String?): Int = when (status) {
    Mcp.CONNECTED -> R.string.mcp_s_connected
    Mcp.FAILED -> R.string.mcp_s_failed
    Mcp.NEEDS_AUTH -> R.string.mcp_s_needs_auth
    Mcp.PENDING -> R.string.mcp_s_pending
    Mcp.DISABLED -> R.string.mcp_s_disabled
    Mcp.WAITING -> R.string.mcp_s_waiting
    Mcp.REJECTED -> R.string.mcp_s_rejected
    else -> R.string.mcp_s_unknown
}

@Composable
internal fun mcpStatusColor(status: String?): Color = when (status) {
    Mcp.CONNECTED -> HN.accent
    Mcp.FAILED, Mcp.NEEDS_AUTH, Mcp.WAITING -> HN.amber
    else -> HN.inkDim
}

@StringRes
internal fun mcpScopeWord(scope: String): Int = when (scope) {
    Mcp.USER -> R.string.mcp_scope_user
    Mcp.LOCAL -> R.string.mcp_scope_local
    Mcp.PROJECT -> R.string.mcp_scope_project
    Mcp.SETTINGS -> R.string.mcp_scope_settings
    Mcp.PLUGIN -> R.string.mcp_scope_plugin
    "managed", "enterprise" -> R.string.mcp_scope_managed
    "claudeai" -> R.string.mcp_scope_claudeai
    else -> R.string.mcp_scope_plugin
}

@Composable
internal fun mcpToolMark(readOnly: Boolean?, destructive: Boolean?): String? = when {
    destructive == true -> stringResource(R.string.mcp_a_destructive)
    readOnly == true -> stringResource(R.string.mcp_a_read_only)
    else -> null
}

@Composable
internal fun McpToolRow(name: String, description: String?, mark: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HText(name, style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            description?.let {
                Spacer(Modifier.height(2.dp))
                HText(it, style = Type.small, color = HN.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        mark?.let {
            Spacer(Modifier.width(10.dp))
            HText(it, style = Type.small, color = HN.inkDim, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}

/** What each hint says, for a flag the server gave. Its own claim: nothing checks it. */
@Composable
internal fun McpAnnotationLines(readOnly: Boolean?, destructive: Boolean?, idempotent: Boolean?, openWorld: Boolean?) {
    val lines = listOfNotNull(
        readOnly?.let { stringResource(if (it) R.string.mcp_h_read_only else R.string.mcp_h_writes) },
        destructive?.let { stringResource(if (it) R.string.mcp_h_destructive else R.string.mcp_h_not_destructive) },
        idempotent?.let { stringResource(if (it) R.string.mcp_h_idempotent else R.string.mcp_h_not_idempotent) },
        openWorld?.let { stringResource(if (it) R.string.mcp_h_open else R.string.mcp_h_closed) },
    )
    if (lines.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    HText(stringResource(R.string.mcp_tool_says).uppercase(), style = Type.label, color = HN.inkDim)
    Spacer(Modifier.height(4.dp))
    lines.forEach { HText(it, style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(vertical = 1.dp)) }
}

/** All that's known of a tool the server itself couldn't be asked about. [why]: why that's all. */
@Composable
internal fun McpLiveToolSheet(t: McpLiveTool, why: String, onDismiss: () -> Unit) {
    GlassSheet(t.name, onDismiss) {
        HText(t.name, style = Type.title, color = HN.ink)
        McpAnnotationLines(t.readOnly.takeIf { it }, t.destructive.takeIf { it }, null, t.openWorld.takeIf { it })
        Spacer(Modifier.height(8.dp))
        HText(why, style = Type.small, color = HN.inkDim)
    }
}

/**
 * A sign-in from the phone: the page opens in the browser, which at the end goes to an address on localhost that
 * a phone can't open. That address, pasted here, finishes it on the server.
 */
@Composable
internal fun McpLoginSheet(l: McpLogin, onCancel: () -> Unit, onFinish: (String, (String?) -> Unit) -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    GlassSheet(stringResource(R.string.mcp_sign_in), onCancel) {
        HText(stringResource(R.string.mcp_sign_in), style = Type.title, color = HN.ink)
        Spacer(Modifier.height(6.dp))
        HText(stringResource(R.string.mcp_login_body), style = Type.small, color = HN.inkMuted)
        Spacer(Modifier.height(10.dp))
        Pill(stringResource(R.string.mcp_login_open), {
            // The browser itself: it holds any sign-in the owner already has there.
            l.url?.let { u -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) }.onFailure { toast(context.getString(R.string.about_github_no_browser)) } }
        }, kind = PillKind.Primary, height = 44.dp)
        Spacer(Modifier.height(12.dp))
        Field(address, { address = it }, placeholder = "http://localhost:…", mono = true, singleLine = false, label = stringResource(R.string.mcp_login_address))
        trouble?.let { HText(it, style = Type.small, color = HN.amber, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.mcp_login_finish), {
                busy = true
                trouble = null
                onFinish(address) { err -> trouble = err; busy = false }
            }, kind = PillKind.Outline, height = 44.dp, enabled = address.isNotBlank() && !busy)
            Pill(stringResource(R.string.cancel), onCancel, kind = PillKind.Ghost, height = 44.dp)
        }
    }
}
