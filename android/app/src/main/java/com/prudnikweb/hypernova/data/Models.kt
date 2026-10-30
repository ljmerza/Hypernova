package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable

/** What the sky draws: three states. The full lifecycle is in [Phase]. */
enum class SessionState { Working, NeedsYou, Idle }

/** The daemon's session states, plus a catch-all for values this build doesn't know. */
enum class Phase { Starting, Working, NeedsYou, Idle, Error, Ended, Unknown;
    companion object {
        fun of(state: SessionState) = when (state) {
            SessionState.Working -> Working
            SessionState.NeedsYou -> NeedsYou
            SessionState.Idle -> Idle
        }
    }
}

/** Session.endedBy: a restart of the Mac ended it. */
const val MAC_RESTART = "mac_restart"

@Immutable
data class Session(
    /** Short hex id shown next to the star, e.g. "a3". */
    val hex: String,
    val id: String,
    val name: String,
    val project: String,
    val state: SessionState,
    val model: String,
    val mode: String,
    /** One line of what it's doing right now, shown in mono. */
    val activity: String,
    val updatedAt: Long,
    /** Set when the session appeared after launch, so its name scrambles in once. */
    val isNew: Boolean = false,
    val phase: Phase = Phase.of(state),
    /** Working directory on the Mac (real sessions only). */
    val cwd: String? = null,
    /** "hypernova" (driven by the daemon) or "terminal" (observed through hooks). */
    val origin: String? = null,
    /** Auto-denied after 10 minutes without an answer: show Continue. */
    val stoppedForAway: Boolean = false,
    /** Prompts waiting behind the current one. */
    val queued: Int = 0,
    val providerId: String? = null,
    val contextPct: Float? = null,
    val costUsd: Double? = null,
    /** Running in a shared terminal: the phone can open it live, and prompts are typed into it. */
    val term: TermLink? = null,
    /** A menu its terminal shows, answered here with buttons. */
    val menu: TermMenu? = null,
    /** When it started (0 if unknown): with [updatedAt], how long it has run. */
    val createdAt: Long = 0L,
    /** Agents its main agent started that are running now. */
    val agentsRunning: Int = 0,
    /** Stopped by a limit, a sign-out or its provider, until a real reply clears it. */
    val blocked: Blocked? = null,
    /** It can switch to Bypass: false for a terminal session on the Mac that wasn't started with bypass allowed. */
    val bypassAvailable: Boolean = true,
    /** Why it ended when nobody ended it (docs/PROTOCOL.md, Session.endedBy); null otherwise. */
    val endedBy: String? = null,
    /** The automation that started it (data/Automations.kt): its card and status line say AUTO. */
    val automation: SessionAutomation? = null,
    /** The Mac lets the phone browse its folder (docs/PROTOCOL.md, Session.browsable): the Folder button and file chips show. */
    val browsable: Boolean = true,
    /**
     * Its folder is in the home folder, outside the projects folder (Session.outsideProjects): while it
     * isn't [browsable], Settings → Browse outside projects would open it, so the Folder button says so.
     */
    val outsideProjects: Boolean = false,
    /** Which agent runs it: [AgentKind.CLAUDE], or [AgentKind.CODEX] (data/Codex.kt). */
    val agent: String = AgentKind.CLAUDE,
    /** The session this conversation came from when it switched agents (docs/PROTOCOL.md, "Switching agents"). */
    val switchedFrom: String? = null,
    /** The session it went on in: Home leaves this one off, under its successor. */
    val switchedTo: String? = null,
    /** Calls refused without the owner, not yet retried or dismissed: its Blocked tab (data/Blocked.kt). */
    val blockedCalls: Int = 0,
) {
    /** A Codex session: no rewind, no other provider, no carrying on by itself, Codex's two modes. */
    val codex: Boolean get() = agent == AgentKind.CODEX

    /**
     * A restart of the Mac ended it (its terminal went with the Mac), recently: Home keeps it, to
     * pick up where it left off, rather than sending it to History.
     */
    val cutByRestart: Boolean get() = phase == Phase.Ended && endedBy == MAC_RESTART

    /**
     * Working, by the one rule every surface uses (docs/PROTOCOL.md, Session.state; owner,
     * 2026-10-18): a live session is working while Claude works on it (or is starting) or any agent
     * it started still runs; needing you outranks it. The sky, Home's counts, chips and order, the
     * background notification, the widgets and the chat's status bar all ask this.
     */
    val busy: Boolean get() = phase != Phase.Ended && state != SessionState.NeedsYou && (state == SessionState.Working || agentsRunning > 0)

    /** How long it has run, start to last activity, in minutes (0 if unknown). */
    val lengthMin: Float get() = if (createdAt <= 0L) 0f else ((updatedAt - createdAt).coerceAtLeast(0L) / 60_000f)

    /**
     * What stops Claude replying here now, or null. A block outlives its cause until Claude replies
     * again, so one whose cause is over (the limit has reset, the account signed back in, the
     * provider recovered) no longer counts: the next prompt goes through.
     */
    fun activeBlock(account: Account, providers: List<Provider>, now: Long): Blocked? {
        val b = blocked ?: return null
        if (phase == Phase.Ended || state != SessionState.Idle && phase != Phase.Error) return null
        // The Mac keeps a block on past its cause while the session carries on by itself at its
        // time, or waits for Continue (docs/PROTOCOL.md, "Auto-continue").
        if (b.resume?.armed == true || b.resume?.ready == true) return b
        return when (b.kind) {
            Blocked.LIMIT, Blocked.MODEL -> b.takeIf { b.resetsAt == null || b.resetsAt > now }
            // Its provider still in trouble, or removed altogether ("Its provider was removed…"):
            // a removed one never comes back, so the block stands until the session is moved.
            Blocked.PROVIDER -> b.takeIf { providers.firstOrNull { it.id == providerId }?.let { it.status != null } ?: (providerId != null && providers.isNotEmpty()) }
            else -> b.takeIf { !account.ok }
        }
    }
}

/**
 * Why a session can't reply (docs/PROTOCOL.md, Session.blocked): [kind] is one of the constants
 * below (an unknown one draws like a provider problem); [text] is Claude Code's own line;
 * [autoResumeAt]: Claude Code carries on by itself then.
 */
@Immutable
data class Blocked(val kind: String, val text: String, val resetsAt: Long? = null, val autoResumeAt: Long? = null, val resume: Resume? = null) {
    /** The owner has to do something (sign in, renew, add funds), rather than wait. */
    val needsYou: Boolean get() = kind != LIMIT && kind != MODEL

    companion object {
        const val LIMIT = "limit"
        const val MODEL = "model"
        const val NO_CREDIT = "no_credit"
        const val LOGGED_OUT = "logged_out"
        const val NO_SUBSCRIPTION = "no_subscription"
        const val ON_HOLD = "on_hold"
        const val PROVIDER = "provider"
    }
}

/**
 * Claude Code's account on the Mac (docs/PROTOCOL.md, "Account"): fine, resting at a limit, or
 * stopped until the owner does something. [state] is "ok", "limited", "no_credit", "logged_out",
 * "no_subscription", "on_hold" or "unknown"; [text] is Claude Code's own line; [sessionId] is the
 * session that hit it first; [login] is a sign-in under way.
 */
@Immutable
data class Account(
    val state: String = OK,
    val providerId: String = "anthropic",
    val plan: String? = null,
    val limit: Limit? = null,
    val reason: String? = null,
    val text: String? = null,
    val since: Long? = null,
    val sessionId: String? = null,
    val checkedAt: Long? = null,
    val login: Login? = null,
) {
    /**
     * [window]: "five_hour", "seven_day", "model" (one model, named in [label]) or "credits".
     * [label] is Claude Code's own words ("session limit", "Opus limit"), null when it gave none.
     */
    @Immutable data class Limit(val window: String, val label: String?, val resetsAt: Long?, val autoResumeAt: Long?, val usingCredits: Boolean)

    /** A sign-in: [state] "starting", "waiting_code", "finishing" or "failed"; [url] the page to open; on the Mac's terminal or this phone. */
    @Immutable data class Login(val state: String, val url: String?, val onMac: Boolean, val error: String?)

    val ok: Boolean get() = state == OK
    /** Sessions on the subscription can't reply until the owner acts: amber, and a notification. */
    val needsYou: Boolean get() = state in NEEDS_YOU
    /** The app can't follow usage: the sun is eclipsed. */
    val eclipsed: Boolean get() = state == LOGGED_OUT || state == NO_SUBSCRIPTION || state == ON_HOLD

    /** The limit Claude rests at right now, if any (one whose reset has passed is over). */
    fun resting(now: Long): Limit? = limit?.takeIf { (state == LIMITED || state == NO_CREDIT) && (it.resetsAt == null || it.resetsAt > now) }

    /** The subscription can't take a new session now: not a one-model limit, nor running on credits. */
    fun stopsNewSessions(now: Long): Boolean = needsYou || resting(now)?.let { it.window != "model" && !it.usingCredits } == true

    /**
     * One account episode. The Mac gives each its own [since] and keeps it while later sessions
     * only add what it didn't know (the reset), so that's the key; a sign-in moving on is the same
     * one too. Without a [since], the same state, window and reset.
     */
    val episode: String get() = since?.let { "$state:$it" } ?: "$state:${limit?.window}:${limit?.resetsAt}:$reason"

    companion object {
        const val OK = "ok"
        const val LIMITED = "limited"
        const val NO_CREDIT = "no_credit"
        const val LOGGED_OUT = "logged_out"
        const val NO_SUBSCRIPTION = "no_subscription"
        const val ON_HOLD = "on_hold"
        const val UNKNOWN = "unknown"
        private val NEEDS_YOU = setOf(NO_CREDIT, LOGGED_OUT, NO_SUBSCRIPTION, ON_HOLD)
        val Ok = Account()
    }
}

/**
 * The spend pace in % per minute, with the minutes until 100% at it ([fiveHourEtaMs],
 * [sevenDayEtaMs]: null when the pace is unknown, ~0, or the window resets first).
 */
@Immutable
data class UsageRate(val fiveHour: Float?, val sevenDay: Float?, val fiveHourEtaMs: Long?, val sevenDayEtaMs: Long?)

/**
 * Claude plan usage, from the status line's rate_limits.
 * [limitedAt] is set while the 5-hour limit is hit (sessions are rate-limited until [fiveHourResetAt]).
 */
@Immutable
data class Usage(
    val fiveHour: Float,
    val sevenDay: Float,
    val fiveHourResetAt: Long,
    val sevenDayResetAt: Long,
    val limitedAt: Long? = null,
    /** Set while the 7-day limit is hit: the sun's disk is in its low state until [sevenDayResetAt]. */
    val weekLimitedAt: Long? = null,
    /** False until the Mac has seen official numbers (or after a reset, until it sees new ones). */
    val known: Boolean = true,
    /** Signed out or stopped: the last numbers known, no longer measured. */
    val stale: Boolean = false,
    /** The weeks some models have of their own (Fable, Sonnet), from /usage on the Mac; none until it has looked. */
    val models: List<ModelWeek> = emptyList(),
    /** How fast the windows are being spent; null while nothing works (and from an older Mac). */
    val rate: UsageRate? = null,
) {
    val limited: Boolean get() = limitedAt != null
    val weekLimited: Boolean get() = weekLimitedAt != null
    val anyLimited: Boolean get() = limited || weekLimited
}

/**
 * A provider a conversation (now on [except]) could go on with right now: set up, not in trouble
 * itself, and for the built-in one, an account that can take it. Null when there's none, so the
 * app offers "Add a provider" instead.
 */
fun List<Provider>.healthyOther(account: Account, except: String?, now: Long): Provider? = firstOrNull { p ->
    p.id != (except ?: "anthropic") && p.status == null &&
        (if (p.builtIn) !account.stopsNewSessions(now) else p.hasKey || p.kind == "custom")
}

@Immutable
data class Mac(
    val name: String,
    val online: Boolean,
    val via: String,
    val url: String? = null,
    val daemonVersion: String? = null,
    val claudeVersion: String? = null,
    /** Null for a Mac without a battery (a Mac mini, an iMac, a Studio), which shows nothing about power. */
    val battery: MacBattery? = null,
    /** Stay awake (data/Awake.kt); null from a Mac too old to say. */
    val awake: MacAwake? = null,
    /** The computer's system: [DARWIN] (a Mac, or a daemon too old to say) or [LINUX] (a server or a PC). */
    val platform: String = DARWIN,
    /**
     * Settings → Browse outside projects (docs/PROTOCOL.md, "Files"): sessions' folders in the home folder
     * open here, read-only. Null from a Mac too old to have it: Settings doesn't offer it.
     */
    val browseOutsideProjects: Boolean? = null,
    /**
     * The Mac's features this computer lacks ([FEATURE_OPEN_WINDOW] and the rest), each with the
     * sentence that says why (docs/PROTOCOL.md, "Mac-only features"): empty on a Mac.
     */
    val unavailable: Map<String, String> = emptyMap(),
) {
    /** A Mac: its menu bar app, its windows, its voice and its battery are there. */
    val isMac: Boolean get() = platform != LINUX

    /** Whether this computer has [feature] (a Mac has them all). */
    fun has(feature: String): Boolean = feature !in unavailable

    companion object {
        const val DARWIN = "darwin"
        const val LINUX = "linux"
        /** A terminal window on the computer's own screen ("Sign in on the Mac", "Open on the Mac"). */
        const val FEATURE_OPEN_WINDOW = "openWindow"
        const val FEATURE_AWAKE = "awake"
        const val FEATURE_MAC_VOICE = "macVoice"

        /** [via]: the Mac's own address on this network (data/LanRoute.kt). */
        const val VIA_LOCAL = "local"
        /** [via]: the paired base URL as it is (the Tailscale app on the phone, or a public address). */
        const val VIA_TAILSCALE = "tailscale"
        /** A plain remote base URL (no Tailscale of either kind), as the bridge says "remote" to Lens. */
        const val VIA_REMOTE = "remote"
        /** [via]: the app's own Tailscale node (data/Tsnet.kt). */
        const val VIA_BUILT_IN = "tsnet"
    }
}

/** A MacBook's battery, as `pmset -g batt` on the Mac reads it (docs/PROTOCOL.md, Snapshot.mac.battery). */
@Immutable
data class MacBattery(
    /** 0–100. */
    val percent: Int,
    val charging: Boolean,
    /** On the charger, charging or not (macOS holds a full or 80%-managed battery without charging). */
    val onAC: Boolean,
    /** macOS's estimate of what's left on battery; null while it works one out, or on the charger. */
    val minutesLeft: Int? = null,
) {
    /** Running on the battery: it only goes down from here. */
    val draining: Boolean get() = !charging && !onAC

    /** At 20% or less and draining: worth a glance (amber). The one low-battery rule on the Mac, the phone and the glasses. */
    val low: Boolean get() = draining && percent <= 20

    /** Under 10% and draining: the Mac may sleep soon, and its sessions with it (red). */
    val critical: Boolean get() = draining && percent < 10
}

enum class ApprovalKind { Tool, Question, Plan }

enum class Tone { Info, Warn, Error }

/**
 * How a command draws in the transcript. Claude Code's own ([Local]) and shell lines ([Bash])
 * are slim rows, not messages; skills and the owner's other commands ([Prompt]: prompt, custom
 * and plugin commands) are prompts, so they draw as messages led by a chip with their name.
 */
enum class CommandKind { Local, Skill, Prompt, Bash }

/** Where a prompt is before Claude has it: on its way, or queued until Claude can take it. */
enum class SendStatus {
    /** Sent from this phone: the request is out, or the Mac should log it any moment. */
    Sending,
    /** In Claude Code's queue: Claude gets it when the step it's on finishes (a shared or terminal session). */
    QueuedStep,
    /** Behind the prompt running now: it waits for the whole reply (an SDK session runs one at a time). */
    QueuedReply,
    /** Typed while the Mac was away: waits on this phone and sends itself when the Mac is back (data/OfflineQueue.kt). */
    WaitingMac,
    /** The same, but the session it was for is gone: it stays, to start a new session with. */
    SessionGone,
}

sealed interface Item {
    val key: String

    /**
     * [status]: not Claude's yet (sent from this phone and not logged by the Mac, or queued there),
     * null once it is. [files]: attached on a phone.
     */
    @Immutable data class User(
        override val key: String,
        val text: String,
        val status: SendStatus? = null,
        val files: List<Attachment> = emptyList(),
        /**
         * A slash command, skill or "!" shell line: drawn as its [CommandRun.type] (its name and
         * args, not [text]), with what it printed ([output]) under it.
         */
        val command: CommandRun? = null,
        val output: List<CommandOutput> = emptyList(),
        /** When this phone sent it (its own clock), for the copy shown until the Mac's arrives; 0 for the Mac's. */
        val sentAt: Long = 0L,
        /** The offline queue's id for it ([SendStatus.WaitingMac], [SendStatus.SessionGone]): what Edit and Remove act on. */
        val waitingId: String? = null,
    ) : Item {
        /** Not the Mac's own copy of it yet: drawn quieter. */
        val pending: Boolean get() = status != null
        val queued: Boolean get() = status == SendStatus.QueuedStep || status == SendStatus.QueuedReply
        /** Held on this phone for the Mac (not in the Mac's queue). */
        val waiting: Boolean get() = status == SendStatus.WaitingMac || status == SendStatus.SessionGone
    }
    @Immutable data class Assistant(
        override val key: String,
        val text: String,
        val streaming: Boolean = false,
        /** localhost ports mentioned in the text: each gets an "Open preview" pill. */
        val ports: List<Int> = emptyList(),
    ) : Item
    /** A quiet one-line tool call: Read, Grep, Glob, WebFetch… */
    @Immutable data class Tool(
        override val key: String,
        val verb: String,
        val target: String,
        /** The file this tool read or wrote, so the row can open it. */
        val path: String? = null,
        val running: Boolean = false,
        val failed: Boolean = false,
        /** The first lines of the output when the tool failed. */
        val error: String? = null,
        val ports: List<Int> = emptyList(),
    ) : Item
    /** A Bash call, rendered as a mini terminal. */
    @Immutable data class Terminal(
        override val key: String,
        val command: String,
        val lines: List<String>,
        val running: Boolean,
        val exitCode: Int?,
        val elapsedMs: Long,
        /** When it started, so a running terminal's clock can tick on its own. */
        val startedAt: Long? = null,
        val ports: List<Int> = emptyList(),
        /** Never finished: the session stopped while it ran. */
        val stopped: Boolean = false,
    ) : Item
    @Immutable data class Diff(
        override val key: String,
        val file: String,
        val added: Int,
        val removed: Int,
        val lines: List<DiffLine>,
        /** Absolute path, so the card can open the file. */
        val path: String? = null,
        val verb: String? = null,
    ) : Item
    @Immutable data class Approval(
        override val key: String,
        val tool: String,
        val detail: String,
        val reason: String,
        val resolved: Resolution? = null,
        /** The daemon's approval id (null in demo data). */
        val approvalId: String? = null,
        val kind: ApprovalKind = ApprovalKind.Tool,
        val canAlways: Boolean = true,
        val canSession: Boolean = false,
        val questions: List<Question> = emptyList(),
        val plan: String? = null,
        val expiresAt: Long? = null,
        /** Where it was answered: "phone", "mac", "terminal" or "timeout". */
        val by: String? = null,
        /** True while the details haven't arrived yet (only the summary is known). */
        val partial: Boolean = false,
        /** An agent the main agent started asks: what it's called ("Review the parser"). */
        val agent: String? = null,
        /**
         * What an Edit, MultiEdit or Write would change, as diff lines (− old, + new, ' ' the same),
         * whole: the card shows its start and the rest on "Show all". Null for other tools.
         */
        val change: List<DiffLine>? = null,
    ) : Item
    /**
     * Claude thought before answering; the text isn't shown, only its size. [ongoing]: it is still
     * going (the newest thing in a running session), so its label shimmers; [tookMs]: how long it
     * took, when the prompt before it says so ("Thought for 12s").
     */
    @Immutable data class Thinking(override val key: String, val chars: Int, val ongoing: Boolean = false, val tookMs: Long? = null) : Item
    /** The end of a turn. */
    @Immutable data class Result(
        override val key: String,
        val ok: Boolean,
        val durationMs: Long?,
        val costUsd: Double?,
        val turns: Int?,
        val error: String?,
    ) : Item
    /**
     * [command]: the output of a command that isn't in the transcript (cut off at its start), drawn as
     * output. [blocker]: Claude Code's API-error line that stopped the session ([Blocked.kind]).
     */
    @Immutable data class Notice(override val key: String, val text: String, val tone: Tone, val command: String? = null, val blocker: String? = null) : Item
    /**
     * An agent, under the call that started it: what it does now, or what it found. [nested]:
     * agents it started itself, indented under it.
     */
    @Immutable data class AgentCard(override val key: String, val agent: AgentView, val nested: List<AgentView> = emptyList()) : Item
    /** A workflow run, under its call: its phases, and its agents grouped by phase. */
    @Immutable data class WorkflowCard(override val key: String, val workflow: WorkflowView) : Item
}

enum class AgentState { Running, Done, Failed, Stopped }

/**
 * An agent as its card or row draws it. [asking]: one of its approvals waits for the owner.
 * [depth]: how far down it was started (an agent's agent is 1). [result]: its report.
 */
@Immutable
data class AgentView(
    val id: String,
    val label: String,
    val type: String,
    val state: AgentState,
    val activity: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val tokens: Long?,
    val toolUses: Int?,
    val result: String?,
    val asking: Boolean = false,
    val depth: Int = 0,
    val phase: String? = null,
) {
    val running: Boolean get() = state == AgentState.Running
}

/** Where a workflow's phase is: not started, under way, all done, or one of its agents failed. */
enum class PhaseState { Waiting, Live, Done, Failed }

/** A workflow phase: its agents (the most recent of them, as far as this phone knows) and their tally. */
@Immutable
data class PhaseView(val title: String, val detail: String?, val agents: List<AgentView>, val done: Int, val state: PhaseState)

@Immutable
data class WorkflowView(
    val id: String,
    val name: String,
    val summary: String?,
    val state: AgentState,
    val startedAt: Long,
    val endedAt: Long?,
    val tokens: Long?,
    val agentCount: Int,
    val running: Int,
    val phases: List<PhaseView>,
    /** Agents whose phase isn't one of [phases] (an older daemon, a script with none). */
    val loose: List<AgentView> = emptyList(),
)

/** What a command printed ("Ultracode on (this session only)…"), drawn under the command. */
@Immutable
data class CommandOutput(val key: String, val text: String, val tone: Tone)

@Immutable
data class DiffLine(val kind: Char, val text: String) // '+', '-', ' '

@Immutable
data class Question(val question: String, val header: String, val multiSelect: Boolean, val options: List<Option>) {
    @Immutable data class Option(val label: String, val description: String?)
}

enum class Resolution {
    Allowed, AllowedAlways, AllowedSession, Denied, TimedOut, Answered, PlanApproved, KeptPlanning,
    /** Answered in the terminal on the Mac; the daemon can't tell how. */
    AnsweredOnMac,
    /** Answered first on another device (the Mac, the glasses, another phone); how isn't known yet. */
    AnsweredElsewhere,
    /** No longer pending, but we never heard how it ended (e.g. the daemon restarted). */
    Closed,
}
