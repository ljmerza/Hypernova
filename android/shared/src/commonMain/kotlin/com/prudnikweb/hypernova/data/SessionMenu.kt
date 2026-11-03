package com.prudnikweb.hypernova.data

/*
 * What a session's menu offers and does (docs/PROTOCOL.md, "Session menu"), without the screen: the phone's
 * ui/home/SessionMenu.kt and the desktop's pane draw it.
 */

/** The Mac keeps a name to 80 characters (daemon/src/http.ts, "Session menu"). */
const val SESSION_NAME_MAX = 80

/**
 * What the menu's Remove from list does to a [Session], and so what its confirm says (DECISIONS,
 * "Delete takes a session off Home, History brings it back", 2026-11-02). A job and a Codex session on
 * Home have it always. Any other session has it once it's at rest (Home's Completed, one a restart cut
 * short too): at work or waiting on the owner it has none. A session already in History has none.
 */
enum class DeleteKind {
    /** Already in History; or at work or waiting on the owner, and neither a job nor a Codex session: not offered. */
    None,
    /** A job with no process: `claude rm`. */
    Job,
    /** A job whose process still runs: `claude rm` stops it, and a terminal open on it closes. */
    RunningJob,
    /** A Codex session: a turn running stops, a terminal closes, and the thread stays in Codex. */
    Codex,
    /** At rest in a terminal Hypernova holds: the terminal closes, and it goes to History. */
    Terminal,
    /** Nothing runs: it goes to History. */
    Forget,
    /** Idle in a terminal Hypernova doesn't hold: it can't close that one, and the dialog says so. */
    OpenElsewhere;

    companion object {
        fun of(s: Session): DeleteKind = when {
            s.agentJob == null && s.phase == Phase.Ended && !s.cutByRestart -> None
            s.codex -> Codex
            s.agentJob != null -> if (s.agentRunning == true && s.phase != Phase.Ended) RunningJob else Job
            s.state == SessionState.NeedsYou || s.busy -> None
            s.term != null -> Terminal
            s.origin == "terminal" && s.phase != Phase.Ended -> OpenElsewhere
            else -> Forget
        }
    }
}

/**
 * What the menu's Restart session does to a [Session] (DECISIONS, "Restart session", 2026-11-01): a
 * new claude on the same conversation, so its MCP servers and settings load afresh. Where something
 * runs that Hypernova can restart; nothing running needs none, its next prompt loads them.
 */
enum class RestartKind {
    /** Nothing to restart: ended, no process, or a Codex session. */
    None,
    /** A `claude agents` job whose process runs: `claude respawn`. */
    Job,
    /** A shared terminal Hypernova holds: it closes and the conversation resumes in a new one. */
    Terminal,
    /** A terminal Hypernova doesn't hold: it can't restart that one, and the dialog says so. */
    OpenElsewhere;

    companion object {
        fun of(s: Session): RestartKind = when {
            s.codex || s.phase == Phase.Ended -> None
            s.agentJob != null -> if (s.agentOutdated != null || s.state != SessionState.Idle || s.agentRunning == true) Job else None
            s.term != null -> Terminal
            s.origin == "terminal" -> OpenElsewhere
            else -> None
        }

        /**
         * Whether it asks first: a turn at work, agents running or an approval waiting would stop, and
         * a terminal open on it on the server would close. At rest and unwatched it just goes.
         */
        fun asksFirst(s: Session): Boolean = s.state != SessionState.Idle || s.agentsRunning > 0 || (s.term?.mac ?: 0) > 0
    }
}

/**
 * What a Home card's long-press menu offers for [Session] (docs/PROTOCOL.md, "Session menu"): Rename
 * for any session; Remove from list as [DeleteKind] says, Restart session as [RestartKind] does; Stop for a `claude agents` job ([Session.agentJob]) or
 * a Codex session (its turn's interrupt; 2026-10-31). Stop while it works or waits on the owner,
 * or, for a job, idles with its process still running ([Session.agentRunning]; owner, 2026-10-30:
 * idle jobs couldn't be stopped). Not for one stopped or done already, nor, from an older Mac that
 * doesn't say, for an idle one. MCP servers for a Claude session with a folder (owner, 2026-11-01):
 * Codex keeps a project's in its repository's own file, which this doesn't read.
 */
data class MenuOffer(val rename: Boolean, val stop: Boolean, val delete: Boolean, val restart: Boolean = false, val mcp: Boolean = false) {
    val any: Boolean get() = rename || stop || delete || restart || mcp

    companion object {
        fun of(s: Session): MenuOffer {
            val job = s.agentJob != null
            val live = s.phase != Phase.Ended && (s.state != SessionState.Idle || s.agentRunning == true)
            return MenuOffer(rename = true, stop = (job || s.codex) && live, delete = DeleteKind.of(s) != DeleteKind.None, restart = RestartKind.of(s) != RestartKind.None, mcp = !s.codex && !s.cwd.isNullOrBlank())
        }
    }
}
