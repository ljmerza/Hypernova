package com.prudnikweb.hypernova.ui.share

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Phase
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

    private data class Recent(val path: String, val name: String)

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
    private fun recent(sessions: List<Triple<String?, String, Long>>, projects: List<Triple<String, String, Long>>): List<Recent> {
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

    private fun publish(context: Context, projects: List<Recent>) {
        try {
            val shortcuts = projects.mapIndexed { i, p ->
                ShortcutInfoCompat.Builder(context, "project:${p.path}")
                    .setShortLabel(p.name)
                    .setLongLabel(context.getString(R.string.shortcut_project_long, p.name))
                    .setIcon(IconCompat.createWithAdaptiveBitmap(planet(context)))
                    .setRank(i)
                    .setIntent(
                        Intent(context, MainActivity::class.java)
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

    /** A small mint planet on the app's dark ground, drawn in the safe middle of an adaptive icon. */
    private fun planet(context: Context): Bitmap {
        val size = (108 * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF1B1814.toInt())
        val r = size * 0.19f
        val cx = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(cx - r * 0.35f, cx - r * 0.4f, r * 1.5f, intArrayOf(0xFFB6FFC9.toInt(), 0xFF7FFFA0.toInt(), 0xFF2A5A38.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.012f
        paint.color = 0x667FFFA0
        c.save()
        c.rotate(-18f, cx, cx)
        c.drawOval(cx - r * 1.75f, cx - r * 0.5f, cx + r * 1.75f, cx + r * 0.5f, paint)
        c.restore()
        return bmp
    }
}
