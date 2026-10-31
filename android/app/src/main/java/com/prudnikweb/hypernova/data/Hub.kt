package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable

/** How the app is talking to the Mac right now. */
enum class LinkState { Connecting, Connected, Reconnecting, Offline, Unpaired }

@Immutable
data class Link(
    val state: LinkState,
    /** Why the last attempt failed (while Reconnecting or Offline). */
    val problem: Problem? = null,
    /** When the next attempt starts (epoch ms), for "retrying in 12s". */
    val retryAt: Long? = null,
    /** Offline, with an attempt in flight right now. */
    val retrying: Boolean = false,
    /** What the last round of probes tried when no route answered (data/LanRoute.kt): the offline card names it. */
    val tried: RouteReport? = null,
    /** The route a failure in use came over (a route answered the probes, then failed): the card blames that one. */
    val failedOver: RouteKind? = null,
)

/** An answer to an approval. */
sealed interface Answer {
    data object Allow : Answer
    data object Always : Answer
    /**
     * Always, with where the rule goes and the rules as the owner left them on the Always sheet
     * ([AlwaysDraft]): Claude Code saves them there (docs/PROTOCOL.md, "Approvals").
     */
    data class AlwaysAs(val scope: AlwaysScope, val rules: List<String>) : Answer
    /** This tool (all web pages, all searches, all reads) for the rest of the session. */
    data object Session : Answer
    data object Deny : Answer
    /** AskUserQuestion: question text → chosen labels (multi-select joined by ", "). */
    data class Answers(val answers: Map<String, String>) : Answer
    data object ApprovePlan : Answer
    data object KeepPlanning : Answer
}

/** A new session, as the sheet asks for it. Exactly one of [projectPath] and [newProject] is set. */
data class NewSessionRequest(
    val projectPath: String?,
    val newProject: String?,
    val prompt: String,
    val providerId: String?,
    val model: String?,
    val permissionMode: String,
    /** Files attached on the phone, already on the Mac. */
    val files: List<Attachment> = emptyList(),
    /**
     * [AgentKind.CODEX]: Codex runs it (data/Codex.kt), [model] one of its models (null: its default),
     * [effort] one that model offers (null: its default), [permissionMode] one of [AgentKind.CODEX_MODES];
     * [providerId] isn't sent.
     */
    val agent: String = AgentKind.CLAUDE,
    /** The reasoning effort: Codex's (above), or one of Claude Code's [ClaudeEffort.LEVELS]; null: the agent's own default. */
    val effort: String? = null,
)

/**
 * Everything the screens read and do. [LiveHub] talks to the Mac's daemon; tests have small
 * hubs of their own.
 */
interface Hub {
    val mac: Mac
    val link: Link
    val usage: Usage
    /** Every session the Mac knows about, most recent first (including ended ones). */
    val sessions: List<Session>
    val projects: List<Project>
    val projectsRoot: String?
    val providers: List<Provider>

    fun session(id: String): Session?
    fun transcript(id: String): List<Item>
    /** True while a session's history is loading for the first time. */
    fun loadingTranscript(id: String): Boolean = false
    /** Why the history couldn't load (null if it did, or hasn't tried). */
    fun transcriptError(id: String): String? = null
    fun reloadTranscript(id: String) {}
    /** The Mac has more of this session's history than the phone loaded (its start is further back). */
    fun hasEarlier(id: String): Boolean = false
    /** True while [loadEarlier] is fetching. */
    fun loadingEarlier(id: String): Boolean = false
    /** Load the part of the history before what's shown (the transcript grows at its top). */
    fun loadEarlier(id: String) {}
    /**
     * True while a prompt from this phone is on its way to the session: its request hasn't come
     * back yet, or it has and the Mac hasn't logged it for a few seconds. Never longer. One the
     * Mac queued until Claude can take it isn't sending: its bubble says it's queued.
     */
    fun sending(id: String): Boolean = false
    /** True exactly once per new session: the first card to ask gets the scramble. */
    fun consumeReveal(s: Session): Boolean

    /** A session screen opened: load its full history. */
    fun opened(id: String) {}

    /** The agents a session's main agent started, and the workflows of them (empty until known). */
    fun agents(sessionId: String): AgentBoard = AgentBoard.Empty
    /** An agent's own transcript, read-only, as the Mac has it now. */
    suspend fun agentEvents(sessionId: String, agentId: String): Result<List<WEvent>> = Result.failure(UnsupportedOperationException())
    /** [events] of agent [agentId] as its screen draws them, with its approvals still waiting at the end. */
    fun agentTranscript(sessionId: String, agentId: String, events: List<WEvent>): List<Item> =
        Items.build(events, emptyMap(), emptyMap(), emptyMap(), sessionId, session(sessionId)?.cwd, System.currentTimeMillis(), active = agents(sessionId).agents[agentId]?.running == true, board = agents(sessionId), orphans = false)

    /**
     * Sends a prompt. Succeeds with the id of the session that continues the conversation:
     * usually [sessionId], but a terminal session that's still open continues as a new one.
     * A failure carries a human message (the composer puts the text back). [files] were
     * uploaded with [upload] first.
     */
    suspend fun send(sessionId: String, text: String, files: List<Attachment> = emptyList(), replace: Boolean = false): Result<String>
    // ─── The offline queue (data/OfflineQueue.kt; docs/DESIGN.md, "The offline queue") ───
    // [send] while the Mac is away keeps the prompt on the phone and succeeds; it goes by itself,
    // in order, once each, when the link is back.

    /** Prompts waiting for the Mac, per session, as the chat draws them (empty when none). */
    fun waiting(sessionId: String): List<Item.User> = emptyList()
    /** How many prompts wait for the Mac in this session (not counting ones whose session is gone). */
    fun waitingCount(sessionId: String): Int = 0
    /** Prompts whose session is gone from the Mac: Home keeps them with a way to start a new session. */
    fun orphanedPrompts(): List<Item.User> = emptyList()
    /** New words for a waiting prompt ([Item.User.waitingId]). False when it is on its way or empty. */
    fun editWaiting(waitingId: String, text: String): Boolean = false
    fun removeWaiting(waitingId: String) {}
    /** A waiting prompt as a new session (its session is gone): succeeds with the new session's id. */
    suspend fun startFromWaiting(waitingId: String): Result<String> = Result.failure(UnsupportedOperationException())
    // Fork: held prompts (data/Held.kt; docs/PROTOCOL.md, "Held prompts"): the Mac holds them while Claude waits for an answer.
    /** New words for a held prompt ([Item.User.heldId]), its files kept. */
    suspend fun editHeld(sessionId: String, heldId: String, text: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    /** How many prompts the Mac holds for this session. */
    fun heldCount(sessionId: String): Int = 0
    /** Takes a held prompt back: it's never sent. */
    suspend fun removeHeld(sessionId: String, heldId: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Puts a picked file on the Mac, to attach to a prompt. */
    suspend fun upload(file: Upload): Result<Attachment>
    /** The "/" list: Claude Code's commands and skills in the project at [cwd] (cached a few minutes). */
    suspend fun commands(cwd: String?): Result<List<SlashCommand>> = Result.success(emptyList())
    /**
     * Ask a side question (Claude Code's `/btw`, data/Aside.kt): typed in at once, while Claude works
     * too, never held. Succeeds with it [Aside.asking]; [aside] has its answer once the Mac read it.
     */
    suspend fun askAside(sessionId: String, question: String): Result<Aside> = Result.failure(UnsupportedOperationException())
    /** The session's last side question as the Mac has it now (null when none). */
    suspend fun aside(sessionId: String): Result<Aside?> = Result.success(null)
    /** The Claude Code permission rules in effect for the session, read-only (data/PermissionRules.kt). */
    suspend fun permissionRules(sessionId: String): Result<PermissionRules> = Result.failure(UnsupportedOperationException())
    /**
     * Answer the menu a session's terminal shows: [choice] is an option's index (null closes it
     * with Esc); [alt] picks it with the footer's other key instead of Enter.
     */
    suspend fun answerMenu(sessionId: String, menuId: String, choice: Int?, alt: Boolean = false): Result<Unit> = Result.failure(UnsupportedOperationException())
    /**
     * Move between the questions of a question form in a session's terminal ([TermMenu.steps]):
     * [move] -1 or 1 (← or →), or [step] straight to one (0 the first question, the last + 1 Submit).
     */
    suspend fun menuStep(sessionId: String, menuId: String, move: Int? = null, step: Int? = null): Result<Unit> = Result.failure(UnsupportedOperationException())
    /**
     * Change a session's permission mode (a shared terminal presses Shift+Tab until it shows).
     * Succeeds with true when it only starts with the session's next turn (Bypass while one runs).
     */
    suspend fun setMode(sessionId: String, mode: String): Result<Boolean>
    /**
     * Switch a session to another model of its provider ([model]: a ModelOption's value, or the id
     * it runs; null: the provider's default; docs/PROTOCOL.md, "A session's model"). [confirmPaid]:
     * the owner agreed that another provider bills it. Succeeds with the model the session now has
     * and when it applies; fails with [PaidModel] when the Mac wants that agreement first.
     */
    suspend fun setModel(sessionId: String, model: String?, confirmPaid: Boolean = false): Result<ModelSwitch> = Result.failure(UnsupportedOperationException())
    /**
     * Change a Claude Code session's reasoning effort to one of [ClaudeEffort.LEVELS] (the Mac types
     * `/effort` into its terminal; docs/PROTOCOL.md, "A session's effort"). Succeeds with true when
     * it only applies from the session's next turn.
     */
    suspend fun setEffort(sessionId: String, effort: String): Result<Boolean> = Result.failure(UnsupportedOperationException())
    /** Banners for the open app: a session needs you, finished, failed; a newer app on the Mac. */
    val notices: kotlinx.coroutines.flow.Flow<Notice> get() = kotlinx.coroutines.flow.emptyFlow()
    /** The Mac has a newer Android app than this one (null when this one is current). */
    val appUpdate: AppUpdate? get() = null
    fun interrupt(sessionId: String)
    /**
     * "I'm back": continue a session that stopped because nobody answered. Succeeds with the
     * session that continues (a new one when a still-open terminal session forks).
     */
    suspend fun resume(sessionId: String): Result<String>
    fun answer(sessionId: String, item: Item.Approval, answer: Answer)
    /** The approval a session waits on, as its Home card answers it (null when none). */
    fun approval(sessionId: String): WApproval? = null
    /** [answer] approval [id] from its Home card: true once the Mac has it, false when it didn't get through (said in a toast). */
    suspend fun answerApproval(id: String, answer: Answer): Boolean = false
    /** Every ask session [sessionId] waits on that this phone hasn't answered, oldest first. */
    fun waitingAsks(sessionId: String): List<WApproval> = emptyList()
    /**
     * "Allow these N" (IDEAS PA6): asks [ids] of session [sessionId] allowed with one answer, each
     * as its own Allow; never a deny. Succeeds with how many the Mac allowed (some may have been
     * answered elsewhere a moment before).
     */
    suspend fun allowSeveral(sessionId: String, ids: List<String>): Result<Int> = Result.failure(UnsupportedOperationException())

    // ─── The Home card's menu (docs/PROTOCOL.md, "Session menu") ───

    /** Claude Code's /rename, typed into the session. Fails with the Mac's words when it can't. */
    suspend fun renameSession(sessionId: String, name: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    /** `claude stop` for a `claude agents` job ([Session.agentJob]); the conversation is kept. */
    suspend fun stopSession(sessionId: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    /** `claude rm` for a `claude agents` job: gone from claude agents and from Home. */
    suspend fun deleteSession(sessionId: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    /** `claude respawn` for a job on an older Claude Code ([Session.agentOutdated]): restarted on the installed one. */
    suspend fun respawnSession(sessionId: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    /** `claude respawn` for each of [sessionIds] still on an older Claude Code, one after another: what each one did. */
    suspend fun respawnOutdated(sessionIds: List<String>): Result<List<JobRestart>> = Result.failure(UnsupportedOperationException())

    suspend fun start(req: NewSessionRequest): Result<String>
    /**
     * The models each provider offers (docs/PROTOCOL.md, "Models"), for the new-session sheet. An
     * older Mac has no list: empty, and the sheet offers what it knows (ModelCatalog.of).
     */
    suspend fun models(): Result<List<ModelList>> = Result.success(emptyList())
    suspend fun files(path: String): Result<Listing>
    /**
     * A folder's own folders, for the new-session sheet's folder browser (`GET /v1/folders`): [path] a
     * full path, `~` or `~/…` (null: the home folder); dot-folders with [hidden]. Failures are [FileFailure]s.
     */
    suspend fun folders(path: String?, hidden: Boolean = false): Result<Folders> = Result.failure(UnsupportedOperationException())
    suspend fun file(path: String): Result<FileBytes>
    /**
     * Streams a file ([source]: a Mac path, or a full page URL the Mac handed out) into [target], carrying on from
     * [resume] when the Mac still has the same file. Failures are [FileFailure]s; a cancel stops the call.
     */
    suspend fun download(source: String, target: java.io.File, resume: DownloadResume?, onStart: (etag: String?) -> Unit, onProgress: (done: Long, total: Long) -> Unit): Result<Downloaded> =
        Result.failure(UnsupportedOperationException())
    /** A signed URL a WebView can load: an HTML file ([path]) or a dev server ([port]). */
    suspend fun preview(path: String? = null, port: Int? = null): Result<String>
    /**
     * Adds ([id] null) or edits a provider. Succeeds with what the Mac found when it tried a new key
     * or address, and whether the key was dropped; fails with [ProviderRefused] for an address, key
     * or name it won't take.
     */
    suspend fun saveProvider(id: String?, input: ProviderInput): Result<ProviderSaved>
    suspend fun deleteProvider(id: String): Result<Unit>
    /** The testing checklist page (a signed URL). */
    suspend fun checklist(): Result<String>
    /** A single-use WebSocket link to this session's live terminal, on the route in use. */
    suspend fun terminal(sessionId: String): Result<TermTicket> = Result.failure(UnsupportedOperationException())
    /** Open the session's terminal in a window on the Mac: the app's name, and whether it was open already. */
    suspend fun openOnMac(sessionId: String): Result<Pair<String, Boolean>> = Result.failure(UnsupportedOperationException())

    // ─── Voice (data/VoiceApi.kt) ──────────────────────────────────────────────

    /** Read aloud with the Mac's voices, played here; null where there are none (SpeechLink.kt). */
    val speech: PhoneSpeech? get() = null

    // ─── Account (docs/PROTOCOL.md, "Account") ───────────────────────────────

    /** Claude Code's account on the Mac: fine, resting at a limit, or stopped until the owner acts. */
    val account: Account get() = Account.Ok
    /** Ask the Mac to check the account now (it runs `claude auth status`). */
    suspend fun checkAccount(): Result<Unit> = Result.success(Unit)
    /**
     * Sign Claude Code in again: on this phone (the sign-in page opens here and its code is pasted
     * back, see [sendSignInCode]) or in a terminal on the Mac ([onMac]). [account] follows it.
     */
    suspend fun signIn(onMac: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException())
    /** The code the sign-in page shows, typed into the sign-in on the Mac. Never logged. */
    suspend fun sendSignInCode(code: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun cancelSignIn(): Result<Unit> = Result.success(Unit)
    /**
     * Continue a session's conversation on another provider. Succeeds with the session that
     * carries on: a new one when a live terminal session forks.
     */
    suspend fun switchProvider(sessionId: String, providerId: String): Result<String> = Result.failure(UnsupportedOperationException())

    // ─── Carrying on after a limit, other providers (docs/PROTOCOL.md, "Auto-continue") ───

    /** Turn a blocked session's carrying on by itself on or off. Succeeds once the Mac (or Claude Code) has. */
    suspend fun autoContinue(sessionId: String, on: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException())
    /** Carry a session on after a limit it didn't continue from by itself. Succeeds with the session that carries on. */
    suspend fun continueAfterLimit(sessionId: String): Result<String> = Result.failure(UnsupportedOperationException())
    /** Read a provider's balance now (OpenRouter); [providers] follows. */
    suspend fun checkProvider(id: String): Result<Provider> = Result.failure(UnsupportedOperationException())
    /**
     * The provider editor's Check: an address and a key tried without saving them. [id]: an existing
     * provider, whose saved key is used when [apiKey] is blank (only at its own host). Fails with
     * [ProviderRefused] for an address or key the Mac won't take.
     */
    suspend fun checkNewProvider(kind: String, baseUrl: String?, apiKey: String?, id: String?): Result<ProviderCheck> = Result.failure(UnsupportedOperationException())

    /**
     * Stay awake on the Mac: [MacAwake.WORKING], [MacAwake.BACKPACK] or [MacAwake.OFF]. Backpack
     * fails until its one-time setup is done on the Mac ([Mac.awake]'s backpackReady says).
     */
    suspend fun setAwake(mode: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    /**
     * Backpack's "+2 h": its end moves 2 hours on. Only in its last 10 minutes ([MacAwake.extendable]);
     * the Mac's other guards (battery, heat) still apply.
     */
    suspend fun extendAwake(): Result<Unit> = Result.failure(UnsupportedOperationException())

    /**
     * Settings → Browse outside projects on the Mac ([Mac.browseOutsideProjects]). The screen asks for
     * the fingerprint or the screen lock before turning it on (ui/components/OwnerCheck.kt).
     */
    suspend fun setBrowseOutsideProjects(on: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Settings → the server → Update this server automatically ([Mac.update]; data/ServerUpdate.kt). */
    suspend fun setAutoUpdate(on: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Automations on the Mac (data/Automations.kt): [Automations.None] where there are none. */
    val automations: Automations get() = Automations.None

    // ─── Codex, a second agent (docs/PROTOCOL.md, "Codex (a second agent)"; data/Codex.kt) ───

    /** Codex on the Mac; null where it isn't installed (and from an older Mac): everything as before. */
    val codex: CodexState? get() = null
    /**
     * Claude Code on the Mac (data/AgentLead.kt): null from an older Mac, where it always was. Where it
     * isn't installed and Codex is, Codex leads ([codexLeads]).
     */
    val claude: ClaudeState? get() = null
    /**
     * Sessions in `claude agents` on or off on the Mac (`POST /v1/claude {agents}`, Settings → Agents &
     * providers → Claude Code). Succeeds with Claude Code as the Mac has it now.
     */
    suspend fun setClaudeAgents(on: Boolean): Result<ClaudeState?> = Result.failure(UnsupportedOperationException())
    /**
     * Codex on or off for Hypernova, from the phone (`POST /v1/codex {enabled}`). Succeeds with Codex
     * as the Mac has it now; fails with the Mac's words (a Codex that isn't installed, or too old).
     */
    suspend fun setCodexEnabled(on: Boolean): Result<CodexState?> = Result.failure(UnsupportedOperationException())
    /** Codex's models for the pickers, asked for as a sheet opens (the Mac reads them at most once an hour). */
    suspend fun codexModels(): Result<List<CodexModel>> = Result.success(emptyList())
    /** A Codex session's model ([model] null: Codex's default) and reasoning [effort] (null: the model's own). */
    suspend fun setCodexModel(sessionId: String, model: String?, effort: String?): Result<ModelSwitch> = Result.failure(UnsupportedOperationException())
    /** What a switch of [sessionId] to [to] would carry over ([context]: "recent", "all" or "none"), to read and edit. */
    suspend fun switchPreview(sessionId: String, to: String, context: String): Result<SwitchPreview> = Result.failure(UnsupportedOperationException())
    /**
     * The conversation goes on with [to] ([AgentKind.CLAUDE] or [AgentKind.CODEX]) in a new session in
     * the same folder, [text] as its background; [model], [effort] and [mode] for it (null: the defaults).
     * Succeeds with the new session's id; fails with [SwitchRefused] when the Mac turns it down.
     */
    suspend fun switchAgent(sessionId: String, to: String, text: String, model: String?, effort: String?, mode: String?): Result<String> = Result.failure(UnsupportedOperationException())

    /** A session's calls refused without the owner (data/Blocked.kt), newest first. */
    suspend fun blocked(sessionId: String): Result<List<BlockedCall>> = Result.success(emptyList())
    /** Allow & retry: the owner's approval of that call typed into the session. The list as it's left. */
    suspend fun retryBlocked(sessionId: String, id: String): Result<List<BlockedCall>> = Result.failure(UnsupportedOperationException())
    /** Dismiss: off the list. The list as it's left. */
    suspend fun dismissBlocked(sessionId: String, id: String): Result<List<BlockedCall>> = Result.failure(UnsupportedOperationException())

    /**
     * History's full-text search (data/HistorySearch.kt; docs/PROTOCOL.md, "History search"): the
     * conversations with every word of [query] inside. Succeeds with null when this Mac can't (an
     * older daemon): History then filters on the phone alone. Fails quietly when offline.
     */
    suspend fun searchHistory(query: String): Result<TextHits?> = Result.success(null)

    /** Try to reconnect now instead of waiting for the backoff. */
    fun retry() {}
    /** The app came to the foreground (true) or left it (false). */
    fun foreground(visible: Boolean) {}
    fun close() {}
}

/** A failure the UI can show as-is (the message is already human). */
open class HubException(message: String) : Exception(message)

/** A prompt didn't go: the session's terminal has unsent text ([pending]) in its input box. */
class TerminalBusy(message: String, val pending: String) : HubException(message)

/** [Hub.agentEvents]: the agent has only just started and its transcript isn't written yet (`starting`): ask again in a moment. */
class AgentStarting : HubException("starting")

/** A model switch done: the model the session has now, and whether it applies [nextTurn] (a turn runs, or it has ended). */
data class ModelSwitch(val model: String?, val nextTurn: Boolean)

/** A model switch on a provider that bills it: the Mac wants the owner's yes first (409 `confirm_paid`). */
class PaidModel(message: String) : HubException(message)
