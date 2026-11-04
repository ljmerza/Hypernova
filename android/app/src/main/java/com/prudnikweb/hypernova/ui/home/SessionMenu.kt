package com.prudnikweb.hypernova.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.OutdatedJobs
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.AttentionAction
import com.prudnikweb.hypernova.ui.components.AttentionCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.theme.HN
import kotlinx.coroutines.launch

/** Home's notice about jobs on an older Claude Code: its key in the list and for its dismissal. */
internal const val OUTDATED_CARD = "jobs:outdated"

/**
 * Home's quiet notice while `claude agents` jobs run an older Claude Code (data/OutdatedJobs.kt):
 * how many, the version installed, and Restart all (Restart for one), asked first since a job at
 * work is interrupted. Each is `claude respawn <job>`, never `--all`. Claude Code's words come back
 * for any it refused. Swiped away, it stays away until another job falls behind.
 */
@Composable
internal fun OutdatedJobsCard(hub: Hub, jobs: List<Session>, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val confirm = LocalConfirm.current
    var busy by remember { mutableStateOf(false) }
    val n = jobs.size
    val ids = jobs.map { it.id }
    val restart = stringResource(if (n == 1) R.string.session_restart else R.string.jobs_restart_all)
    val installed = jobs.firstNotNullOfOrNull { it.agentOutdated?.installed }
    val context = LocalContext.current
    AttentionCard(
        id = OUTDATED_CARD, state = OutdatedJobs.attention(jobs), tone = HN.accentDim, glyph = Glyph.Reload,
        title = pluralStringResource(R.plurals.jobs_outdated_title, n, n),
        line = installed?.let { stringResource(R.string.jobs_outdated_line, it) },
        // What the restart brings: straight to the browser, a row chosen on purpose ("Links out of Settings").
        links = listOfNotNull(
            com.prudnikweb.hypernova.data.OutdatedJobs.releaseUrl(installed)?.let { url ->
                AttentionAction(stringResource(R.string.jobs_release_notes, installed.orEmpty()), {
                    val page = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    runCatching { context.startActivity(page) }.onFailure { toast(context.getString(R.string.about_github_no_browser)) }
                })
            },
        ),
        action = AttentionAction(restart, {
            if (!busy) confirm(
                Confirm(res.getQuantityString(R.plurals.jobs_restart_q, n, n), res.getString(R.string.jobs_restart_body), restart, danger = false) {
                    busy = true
                    appScope.launch {
                        hub.respawnOutdated(ids)
                            .onSuccess { r ->
                                val refused = r.filter { !it.ok }
                                if (refused.isNotEmpty()) toast(res.getQuantityString(R.plurals.jobs_restart_refused, refused.size, refused.size, refused.first().said).trim())
                            }
                            .onFailure { toast(it.message ?: res.getString(R.string.err_restart_jobs)) }
                        busy = false
                    }
                },
            )
        }),
        modifier = modifier,
    )
}

