package com.prudnikweb.hypernova.ui.share

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Picked
import com.prudnikweb.hypernova.data.Project
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.isImage
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.fx.AmbientClock
import com.prudnikweb.hypernova.ui.fx.Grain
import com.prudnikweb.hypernova.ui.fx.ProvideAmbientClock
import com.prudnikweb.hypernova.ui.home.DRAFT
import com.prudnikweb.hypernova.ui.home.PhaseChip
import com.prudnikweb.hypernova.ui.session.AttachState
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.HypernovaTheme
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** What the share sheet handed over. */
private class Shared(val text: String?, val uris: List<Uri>)

/** The staged copies of the shared files, kept while the screen turns. */
class ShareModel : ViewModel() {
    var staging: Deferred<ShareStage.Result>? = null
    var delivering by mutableStateOf(false)
}

/**
 * Share to Hypernova: "Share" in any app lists Hypernova; this screen asks where the text, link,
 * pictures or files go: a session (recent first), a project's new session, or a new session
 * where the sheet chooses. The files are put in that composer as attachments (the same upload as
 * the paperclip) and the text in its box, and the app opens there. Nothing is sent: it waits for
 * you to say what Claude should do with it.
 */
class ShareActivity : ComponentActivity() {
    private val shared by lazy { read(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        ShareStage.sweep(this)
        val model: ShareModel by viewModels()
        // Copy the files at once, while the sender's grant is alive.
        if (model.staging == null && shared.uris.isNotEmpty()) {
            model.staging = model.viewModelScope.async { ShareStage.stage(applicationContext, shared.uris.take(AttachDrafts.MAX)) }
        }
        setContent { HypernovaTheme { Screen(model) } }
    }

    private fun read(i: Intent): Shared {
        val uris = LinkedHashSet<Uri>()
        val clip = i.clipData
        if (clip != null) for (n in 0 until clip.itemCount) clip.getItemAt(n).uri?.let(uris::add)
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
            i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
        } else {
            @Suppress("DEPRECATION")
            (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(uris::add)
            @Suppress("DEPRECATION")
            i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        }
        val body = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        // A page shared from a browser: its title and then its link.
        val text = listOf(subject.takeIf { it.isNotEmpty() && !body.contains(it) }, body.takeIf { it.isNotEmpty() })
            .filterNotNull().joinToString("\n").takeIf { it.isNotBlank() }
        // Only content:// and file:// are ours to read.
        return Shared(text, uris.filter { it.scheme == "content" || it.scheme == "file" })
    }

    /** Puts the shared content in the destination's composer and opens the app there. */
    private fun deliver(model: ShareModel, sessionId: String?, projectPath: String?) {
        if (model.delivering) return
        model.delivering = true
        val text = shared.text
        val staging = model.staging
        val app = applicationContext
        Core.scope.launch {
            val staged = staging?.await()
            val hub = Core.live
            if (staged != null && hub != null && staged.uris.isNotEmpty()) {
                val key = sessionId ?: DRAFT
                AttachState(key, hub, app, Core.scope) { Core.toastSink?.invoke(it) }.add(staged.uris, temporary = true)
            }
            if (staged != null && (staged.failed > 0 || staged.tooBig > 0)) {
                Core.toastSink?.invoke(app.getString(if (staged.tooBig > 0) R.string.attach_too_big else R.string.share_unreadable))
            }
            if (sessionId != null) {
                // The session's composer takes the text the way it takes a send that failed.
                if (!text.isNullOrBlank()) AttachDrafts.giveBack(sessionId, text)
                startActivity(
                    Intent(app, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_SESSION)
                        .putExtra(MainActivity.EXTRA_SESSION, sessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            } else {
                ShareLaunch.openNewSession(projectPath, text)
                startActivity(
                    Intent(app, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
            finish()
        }
    }

    @Composable
    private fun Screen(model: ShareModel) {
        val clock = remember { AmbientClock() }
        ProvideAmbientClock(clock) {
            Box(Modifier.fillMaxSize().background(HN.bg)) {
                val hub = Core.live
                val content = shared
                val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Column(Modifier.fillMaxSize().padding(top = top + 20.dp, start = 20.dp, end = 20.dp)) {
                    // To Claude (as always), to Codex where it leads, to either while both are in use.
                    val title = stringResource(com.prudnikweb.hypernova.ui.components.byAgents(hub?.claude, hub?.codex, R.string.share_title, R.string.share_title_codex, R.string.share_title_both))
                    HText(title.uppercase(), style = Type.label, color = HN.inkDim)
                    Spacer(Modifier.height(4.dp))
                    HText(title, style = Type.title)
                    Spacer(Modifier.height(16.dp))
                    when {
                        content.text == null && content.uris.isEmpty() -> Message(stringResource(R.string.share_nothing))
                        hub == null -> Message(stringResource(R.string.share_not_paired), open = true)
                        else -> {
                            Preview(content)
                            Spacer(Modifier.height(16.dp))
                            if (model.delivering) LoadingLine(stringResource(R.string.share_reading), Modifier.padding(top = 8.dp))
                            else Destinations(hub.sessions, hub.projects, onSession = { deliver(model, it, null) }, onProject = { deliver(model, null, it) }, bottom = bottom)
                        }
                    }
                }
                Grain(Modifier.fillMaxSize())
            }
        }
    }

    @Composable
    private fun Message(text: String, open: Boolean = false) {
        HText(text, style = Type.body, color = HN.inkMuted)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = spacedBy(10.dp)) {
            if (open) Pill(stringResource(R.string.app_name), { startActivity(Intent(this@ShareActivity, MainActivity::class.java)); finish() }, kind = PillKind.Primary)
            Pill(stringResource(R.string.share_cancel), { finish() }, kind = PillKind.Ghost)
        }
    }

    /** What is being sent: pictures as thumbnails, other files by name, text as its first lines. */
    @Composable
    private fun Preview(c: Shared) {
        val context = LocalContext.current
        val thumbs by produceState<List<ImageBitmap>>(emptyList(), c) {
            value = c.uris.filter { isImage(Picked.mime(context, it), Picked.info(context, it).first) }.take(4)
                .mapNotNull { Picked.thumbnail(context, it, 240) }
        }
        val names = remember(c) { c.uris.map { Picked.info(context, it).first } }
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = spacedBy(10.dp)) {
                if (thumbs.isNotEmpty()) {
                    Row(horizontalArrangement = spacedBy(8.dp)) {
                        for (t in thumbs) Image(
                            t, null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, HN.ink.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                val others = if (thumbs.isEmpty()) names else names.drop(thumbs.size)
                if (c.uris.size > 1 && thumbs.isNotEmpty()) {
                    HText(stringResource(R.string.share_files_label, c.uris.size), style = Type.monoSmall, color = HN.inkDim)
                } else if (others.isNotEmpty()) {
                    for (n in others.take(3)) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyph.File, size = 13.dp, color = HN.accentDim)
                        Spacer(Modifier.width(8.dp))
                        HText(n, style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    if (others.size > 3) HText("+${others.size - 3}", style = Type.monoSmall, color = HN.inkDim)
                }
                if (c.text != null) {
                    HText(c.text, style = if (c.text.length < 200) Type.body else Type.small, color = HN.inkMuted, maxLines = 4)
                }
            }
        }
    }

    @Composable
    private fun Destinations(sessions: List<Session>, projects: List<Project>, onSession: (String) -> Unit, onProject: (String?) -> Unit, bottom: androidx.compose.ui.unit.Dp) {
        // Live sessions first, then the ended ones; each newest first.
        val recent = remember(sessions) {
            sessions.filter { it.phase != Phase.Ended }.sortedByDescending { it.updatedAt }.take(8)
        }
        val byActivity = remember(projects) { projects.sortedByDescending { it.lastActivityAt ?: 0L }.take(10) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 96.dp), verticalArrangement = spacedBy(10.dp)) {
            item("new") {
                GlassCard(Modifier.fillMaxWidth(), accent = HN.accent, onClick = { onProject(null) }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyph.Plus, size = 18.dp, color = HN.accent)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            HText(stringResource(R.string.share_new_session), style = Type.heading)
                            HText(stringResource(R.string.share_new_session_body), style = Type.small, color = HN.inkMuted)
                        }
                        Icon(Glyph.Chevron, size = 14.dp, color = HN.inkDim)
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item("recent-h") { Heading(stringResource(R.string.share_recent)) }
                item("recent") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        recent.forEachIndexed { i, s ->
                            if (i > 0) Hairline(Modifier.padding(start = 16.dp))
                            Row(
                                Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onSession(s.id) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    HText(s.name, style = Type.body, maxLines = 1)
                                    HText(s.project, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                                }
                                Spacer(Modifier.width(10.dp))
                                PhaseChip(s)
                            }
                        }
                    }
                }
            }
            if (byActivity.isNotEmpty()) {
                item("projects-h") { Heading(stringResource(R.string.share_projects)) }
                item("projects") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        byActivity.forEachIndexed { i, p ->
                            if (i > 0) Hairline(Modifier.padding(start = 16.dp))
                            Row(
                                Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onProject(p.path) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Glyph.Folder, size = 14.dp, color = HN.accentDim)
                                Spacer(Modifier.width(12.dp))
                                HText(p.name, style = Type.body, modifier = Modifier.weight(1f), maxLines = 1)
                                HText(pluralStringResource(R.plurals.sessions_count, p.sessionCount, p.sessionCount), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (recent.isEmpty() && byActivity.isEmpty()) item("none") { HText(stringResource(R.string.share_no_sessions), style = Type.small, color = HN.inkDim) }
        }
    }

    @Composable
    private fun Heading(text: String) = HText(text.uppercase(), style = Type.label, color = HN.inkDim, modifier = Modifier.padding(top = 6.dp))
}
