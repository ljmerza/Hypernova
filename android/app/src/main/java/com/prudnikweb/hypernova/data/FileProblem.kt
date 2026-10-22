package com.prudnikweb.hypernova.data

/** What went wrong opening a folder or file on the Mac, in the terms the screen explains (docs/PROTOCOL.md, "Files"). */
enum class FileKind {
    /** Outside the folders the phone may open (a session's folder outside the projects folder). */
    Outside,
    /** A file with keys or passwords. */
    Secret,
    /** A key place of the home folder (~/.ssh, ~/Library/Keychains, browsers' profiles…), or inside one. */
    LockedFolder,
    /** Past the limit for viewing (or downloading). */
    TooLarge,
    /** Gone from the Mac. */
    NotFound,
    /** An attachment of the phone's, removed after 30 days. */
    AttachmentGone,
    /** macOS' Files and Folders protection keeps the service out. */
    MacPrivacy,
    /** The service isn't allowed to read it. */
    Unreadable,
    /** A file asked for as a folder or the other way round. */
    WrongKind,
    /** The Mac couldn't be reached (no network, a timeout, Tailscale, a 5xx): the only kind worth "Try again". */
    Network,
    /** Anything else, with the Mac's own words. */
    Other,
}

/**
 * A failed files call. It carries what the Mac said (status, code, the fields some errors add) so
 * [FileProblem.of] can pick the explanation and the next step; [message] is the Mac's, or ours.
 */
class FileFailure(
    message: String,
    val problem: Problem = Problem.Server,
    val status: Int = 0,
    val code: String? = null,
    val size: Long? = null,
    val max: Long? = null,
    val projectsRoot: String? = null,
    /** The setting that would open it ("browseOutsideProjects"), when the Mac says one would. */
    val setting: String? = null,
) : HubException(message) {
    companion object {
        fun of(e: ApiException, message: String?): FileFailure = FileFailure(
            message ?: e.message.orEmpty(), e.problem, e.status, e.code,
            e.error?.long("size"), e.error?.long("max"), e.error?.str("projectsRoot"), e.error?.str("setting"),
        )
    }
}

/** The explanation chosen for a failure: what kind, whether "Try again" can work, and the numbers it mentions. */
data class FileProblem(
    val kind: FileKind,
    val retryable: Boolean,
    val message: String,
    val size: Long? = null,
    val max: Long? = null,
    val projectsRoot: String? = null,
    /** [FileKind.WrongKind]: the folder was asked for as a file ("is_dir") or the file as a folder ("not_a_dir"). */
    val asFolder: Boolean = false,
    val problem: Problem? = null,
    /** [FileKind.Outside]: Browse outside projects would open it ([BROWSE_SETTING]), so Settings is a real next step. */
    val setting: String? = null,
) {
    /** Whether "Turn on in Settings" can open it: the Mac said Browse outside projects would. */
    val settingOpens: Boolean get() = kind == FileKind.Outside && setting == BROWSE_SETTING

    companion object {
        /** The Mac's codes win over the status (an old Mac answers `no_dir` for a path outside the folders). */
        fun of(e: Throwable?): FileProblem {
            if (e !is FileFailure) return FileProblem(FileKind.Other, retryable = true, message = e?.message.orEmpty())
            val kind = when (e.code) {
                "outside_projects", "no_dir" -> FileKind.Outside
                "secret_file", "secret" -> FileKind.Secret
                "locked_folder" -> FileKind.LockedFolder
                "too_large" -> FileKind.TooLarge
                "not_found" -> FileKind.NotFound
                "attachment_gone" -> FileKind.AttachmentGone
                "mac_privacy" -> FileKind.MacPrivacy
                "unreadable" -> FileKind.Unreadable
                "not_a_dir", "is_dir" -> FileKind.WrongKind
                else -> when {
                    e.problem != Problem.Server && e.problem != Problem.Unauthorized -> FileKind.Network
                    e.status == 413 -> FileKind.TooLarge
                    e.status == 410 -> FileKind.AttachmentGone
                    e.status == 404 -> FileKind.NotFound
                    e.status in 500..599 -> FileKind.Network
                    else -> FileKind.Other
                }
            }
            return FileProblem(
                kind, retryable = kind == FileKind.Network, message = e.message.orEmpty(),
                size = e.size, max = e.max, projectsRoot = e.projectsRoot, asFolder = e.code == "is_dir", problem = e.problem,
                setting = e.setting,
            )
        }

        /** The setting `outside_projects` names when it would open the path (docs/PROTOCOL.md, "Files"). */
        const val BROWSE_SETTING = "browseOutsideProjects"
    }
}
