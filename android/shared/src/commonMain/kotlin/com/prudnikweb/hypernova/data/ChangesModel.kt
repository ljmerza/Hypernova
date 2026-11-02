package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
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
data class ChangeLine(val kind: LineKind, val text: String, val oldNo: Int = 0, val newNo: Int = 0)

@Immutable
data class FileDiff(val path: String, val lines: List<ChangeLine>, val truncated: Boolean, val binary: Boolean)

/** The calls behind the "What changed" screen. */
object ChangesRepo {
    private fun api(): Api {
        val p = Core.pairing ?: throw HubException(Core.platform.strings.getString(Str.changes_err))
        return Api(p.baseUrl, p.token)
    }

    private fun fail(e: Throwable): Throwable = when {
        e is CancellationException -> e
        e is ApiException && e.serverMessage != null -> HubException(e.serverMessage)
        e is HubException -> e
        else -> HubException(Core.platform.strings.getString(Str.changes_err))
    }

    suspend fun load(sessionId: String, all: Boolean = false): Result<WorkChanges> = try {
        val o = api().get("/v1/sessions/$sessionId/changes" + if (all) "?all=1" else "")
        Result.success(withContext(Dispatchers.Default) { parseChanges(o.getJSONObject("changes")) })
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Result.failure(fail(e))
    }

    suspend fun diff(sessionId: String, path: String): Result<FileDiff> = try {
        val o = api().get("/v1/sessions/$sessionId/changes/diff?path=${Api.q(path)}", max = 2L * 1024 * 1024)
        Result.success(withContext(Dispatchers.Default) {
            FileDiff(path, parseDiff(o.optString("diff")), o.optBoolean("truncated"), o.optBoolean("binary"))
        })
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Result.failure(fail(e))
    }

    fun parseChanges(o: JSONObject): WorkChanges {
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
    fun parseDiff(text: String): List<ChangeLine> {
        val out = ArrayList<ChangeLine>()
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
                    out += ChangeLine(LineKind.Hunk, line)
                }
                !inHunk -> Unit
                line.startsWith("+") -> out += ChangeLine(LineKind.Add, line.substring(1), newNo = newNo++)
                line.startsWith("-") -> out += ChangeLine(LineKind.Del, line.substring(1), oldNo = oldNo++)
                line.startsWith("\\") -> out += ChangeLine(LineKind.Note, line.removePrefix("\\ "))
                line.startsWith(" ") -> out += ChangeLine(LineKind.Ctx, line.substring(1), oldNo++, newNo++)
                // The empty string after the last newline.
                line.isEmpty() -> Unit
                else -> out += ChangeLine(LineKind.Ctx, line, oldNo++, newNo++)
            }
        }
        return out
    }
}
