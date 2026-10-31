package com.prudnikweb.hypernova.ui.changes

import androidx.compose.runtime.Immutable
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Api
import com.prudnikweb.hypernova.data.ApiException
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One file the session's folder has changed since the last commit (docs/PROTOCOL.md, "What changed"). */
@Immutable
data class ChangedFile(
    /** Relative to the session's folder. */
    val path: String,
    /** M changed, A new, D deleted. */
    val status: Char,
    val untracked: Boolean,
    /** Null for a picture, a binary or a file too big to count. */
    val add: Int?,
    val del: Int?,
    val binary: Boolean,
    /** The session edited it with its own tools. */
    val touched: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/', "")
}

@Immutable
data class WorkChanges(
    /** False: the folder isn't in a git repository. */
    val git: Boolean,
    val branch: String?,
    /** Only what this session's own tools edited (true) or everything changed in the folder. */
    val scoped: Boolean,
    val files: List<ChangedFile>,
    /** Changed files this session didn't touch, left out of a scoped list. */
    val others: Int,
    /** Credential files that changed: counted, never listed. */
    val hidden: Int,
    val more: Boolean,
    val totalAdd: Int,
    val totalDel: Int,
    /** The branch's upstream ("origin/main"); null with none (or from an older Mac). */
    val upstream: String? = null,
    /** Commits ahead of and behind [upstream]; null when not known (none, gone, git too slow). */
    val ahead: Int? = null,
    val behind: Int? = null,
    /** Files with uncommitted changes in the folder, whatever the scope; null from an older Mac. */
    val dirty: Int? = null,
    /** The linked git worktree's name when the folder is in one. */
    val worktree: String? = null,
)

enum class LineKind { Hunk, Add, Del, Ctx, Note }

/** A line of a unified diff; [oldNo] / [newNo] are the line numbers in each version (0: none). */
@Immutable
data class DiffLine(val kind: LineKind, val text: String, val oldNo: Int = 0, val newNo: Int = 0)

@Immutable
data class FileDiff(val path: String, val lines: List<DiffLine>, val truncated: Boolean, val binary: Boolean)

/** The calls behind the "What changed" screen. Demo sessions get a believable sample. */
object ChangesRepo {
    private fun api(): Api {
        val p = Core.pairing ?: throw HubException(Core.app.getString(R.string.changes_err))
        return Api(p.baseUrl, p.token)
    }

    private fun fail(e: Throwable): Throwable = when {
        e is CancellationException -> e
        e is ApiException && e.serverMessage != null -> HubException(e.serverMessage)
        e is HubException -> e
        else -> HubException(Core.app.getString(R.string.changes_err))
    }

    suspend fun load(hub: Hub, sessionId: String, all: Boolean = false): Result<WorkChanges> = try {
        if (hub.isDemo) Result.success(DemoChanges.changes(all))
        else {
            val o = api().get("/v1/sessions/$sessionId/changes" + if (all) "?all=1" else "")
            Result.success(withContext(Dispatchers.Default) { parseChanges(o.getJSONObject("changes")) })
        }
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Result.failure(fail(e))
    }

    suspend fun diff(hub: Hub, sessionId: String, path: String): Result<FileDiff> = try {
        if (hub.isDemo) Result.success(DemoChanges.diff(path))
        else {
            val o = api().get("/v1/sessions/$sessionId/changes/diff?path=${Api.q(path)}", max = 2L * 1024 * 1024)
            Result.success(withContext(Dispatchers.Default) {
                FileDiff(path, parseDiff(o.optString("diff")), o.optBoolean("truncated"), o.optBoolean("binary"))
            })
        }
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Result.failure(fail(e))
    }

    internal fun parseChanges(o: JSONObject): WorkChanges {
        val arr = o.optJSONArray("files")
        val files = (0 until (arr?.length() ?: 0)).map { i ->
            val f = arr!!.getJSONObject(i)
            ChangedFile(
                path = f.getString("path"),
                status = f.optString("status", "M").firstOrNull() ?: 'M',
                untracked = f.optBoolean("untracked"),
                add = if (f.isNull("add")) null else f.optInt("add"),
                del = if (f.isNull("del")) null else f.optInt("del"),
                binary = f.optBoolean("binary"),
                touched = f.optBoolean("touched"),
            )
        }
        return WorkChanges(
            git = o.optBoolean("git"),
            branch = if (o.isNull("branch")) null else o.optString("branch"),
            scoped = o.optString("scope") == "session",
            files = files,
            others = o.optInt("others"),
            hidden = o.optInt("hidden"),
            more = o.optBoolean("more"),
            totalAdd = o.optInt("totalAdd"),
            totalDel = o.optInt("totalDel"),
            upstream = if (o.isNull("upstream")) null else o.optString("upstream").takeIf { it.isNotBlank() },
            ahead = if (o.isNull("ahead")) null else o.optInt("ahead"),
            behind = if (o.isNull("behind")) null else o.optInt("behind"),
            dirty = if (o.isNull("dirty")) null else o.optInt("dirty"),
            worktree = if (o.isNull("worktree")) null else o.optString("worktree").takeIf { it.isNotBlank() },
        )
    }

    private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /** A unified diff as lines with their numbers; git's own header lines (diff, index, ---, +++) are left out. */
    internal fun parseDiff(text: String): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        var oldNo = 0
        var newNo = 0
        var inHunk = false
        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            val h = HUNK.find(line)
            when {
                h != null -> {
                    inHunk = true
                    oldNo = h.groupValues[1].toInt()
                    newNo = h.groupValues[2].toInt()
                    out += DiffLine(LineKind.Hunk, line)
                }
                !inHunk -> Unit
                line.startsWith("+") -> out += DiffLine(LineKind.Add, line.substring(1), newNo = newNo++)
                line.startsWith("-") -> out += DiffLine(LineKind.Del, line.substring(1), oldNo = oldNo++)
                line.startsWith("\\") -> out += DiffLine(LineKind.Note, line.removePrefix("\\ "))
                line.startsWith(" ") -> out += DiffLine(LineKind.Ctx, line.substring(1), oldNo++, newNo++)
                // The empty string after the last newline.
                line.isEmpty() -> Unit
                else -> out += DiffLine(LineKind.Ctx, line, oldNo++, newNo++)
            }
        }
        return out
    }
}

/** What demo mode shows: a small change set with a diff, so the screen can be seen without a Mac. */
internal object DemoChanges {
    private val all = listOf(
        ChangedFile("src/session/Composer.kt", 'M', false, 24, 6, false, true),
        ChangedFile("src/session/Attachments.kt", 'A', true, 58, 0, false, true),
        ChangedFile("docs/PROTOCOL.md", 'M', false, 9, 2, false, true),
        ChangedFile("notes/scratch.txt", 'D', false, 0, 14, false, false),
    )

    fun changes(everything: Boolean): WorkChanges {
        val shown = if (everything) all else all.filter { it.touched }
        return WorkChanges(
            git = true, branch = "main", scoped = !everything, files = shown, others = if (everything) 0 else all.size - shown.size,
            hidden = 0, more = false, totalAdd = shown.sumOf { it.add ?: 0 }, totalDel = shown.sumOf { it.del ?: 0 },
            upstream = "origin/main", ahead = 2, behind = 0, dirty = all.size,
        )
    }

    fun diff(path: String): FileDiff = FileDiff(
        path,
        ChangesRepo.parseDiff(
            """
            @@ -41,9 +41,12 @@ fun Composer(state: ComposerState) {
                 val draft = state.draft
            -    // Send on the keyboard's action.
            -    val send = { state.send(draft) }
            +    // Send on the keyboard's action, but never an empty box.
            +    val send = {
            +        if (draft.isNotBlank() || state.files.isNotEmpty()) state.send(draft)
            +    }
                 BasicTextField(
                     value = draft,
                     onValueChange = state::onChange,
            @@ -88,6 +91,7 @@ fun Composer(state: ComposerState) {
                     keyboardActions = KeyboardActions(onSend = { send() }),
            +        maxLines = 8,
                 )
            """.trimIndent(),
        ),
        truncated = false, binary = false,
    )
}
