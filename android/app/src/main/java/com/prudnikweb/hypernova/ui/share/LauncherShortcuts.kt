// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.share

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.ui.theme.HN
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The launcher's long-press menu: "New session" (static, res/xml/shortcuts.xml) and the three
 * most recently used projects (dynamic), each opening the new-session sheet on that project. The
 * projects follow the sessions: whenever the Mac's list changes, the three are worked out again
 * and published only if they differ, so the launcher is not asked more often than it needs.
 */
object LauncherShortcuts {
    private const val MAX = 3
    private var started = false

    internal data class Recent(val path: String, val name: String)

    @OptIn(FlowPreview::class)
    fun start(app: Application) {
        if (started) return
        started = true
        Core.scope.launch {
            snapshotFlow {
                val hub = Core.live
                if (hub == null) emptyList() else recent(hub.sessions.map { Triple(it.cwd, it.project, it.updatedAt) }, hub.projects.map { Triple(it.path, it.name, it.lastActivityAt ?: 0L) })
            }
                .distinctUntilChanged()
                // A burst of session updates (a turn streaming) settles into one publish.
                .debounce(3_000)
                .collect { publish(app, it) }
        }
    }

    /** Folders worked in lately, newest first: sessions' folders, then projects no session names. */
    internal fun recent(sessions: List<Triple<String?, String, Long>>, projects: List<Triple<String, String, Long>>): List<Recent> {
        val out = LinkedHashMap<String, Recent>()
        val known = projects.associate { it.first to it.second }
        for ((cwd, project, _) in sessions.sortedByDescending { it.third }) {
            if (cwd == null || out.size >= MAX) continue
            // Only a folder the Mac lists as a project can be started in.
            if (known.isNotEmpty() && cwd !in known) continue
            out.putIfAbsent(cwd, Recent(cwd, known[cwd] ?: project))
        }
        for ((path, name, _) in projects.sortedByDescending { it.third }) if (out.size < MAX) out.putIfAbsent(path, Recent(path, name))
        return out.values.take(MAX)
    }

    internal fun publish(context: Context, projects: List<Recent>) {
        try {
            val shortcuts = projects.mapIndexed { i, p ->
                ShortcutInfoCompat.Builder(context, "project:${p.path}")
                    .setShortLabel(p.name)
                    .setLongLabel(context.getString(R.string.shortcut_project_long, p.name))
                    .setIcon(IconCompat.createWithAdaptiveBitmap(mark(context)))
                    .setRank(i)
                    .setIntent(
                        MainActivity.command(context)
                            .setAction(ShareLaunch.ACTION_NEW_SESSION)
                            .putExtra(ShareLaunch.EXTRA_PROJECT, p.path),
                    )
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (_: Exception) {
            // Rate-limited or the launcher is busy: the next change tries again.
        }
    }

    /** Atlas's working mark on the app's dark ground, drawn in the safe middle of an adaptive icon: a project's shortcut. */
    private fun mark(context: Context): Bitmap {
        val size = (108 * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(HN.bg.toArgb())
        val r = size * 0.11f
        val cx = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = HN.accent.toArgb()
        c.drawPath(Path().apply { moveTo(cx, cx - r); lineTo(cx + r, cx); lineTo(cx, cx + r); lineTo(cx - r, cx); close() }, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = size * 0.04f
        val arc = size * 0.21f
        c.drawArc(cx - arc, cx - arc, cx + arc, cx + arc, -60f, 120f, false, paint)
        c.drawArc(cx - arc, cx - arc, cx + arc, cx + arc, 120f, 120f, false, paint)
        return bmp
    }
}
