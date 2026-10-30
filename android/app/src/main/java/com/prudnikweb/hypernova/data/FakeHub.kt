package com.prudnikweb.hypernova.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.min
import android.content.res.Resources
import com.prudnikweb.hypernova.R

/**
 * Demo mode: believable sessions that move, with no Mac needed. It implements the same [Hub]
 * as the real client, so screens don't know the difference.
 */
class FakeHub(private val scope: CoroutineScope, private val res: Resources) : Hub {
    override val isDemo = true
    override val link = Link(LinkState.Demo)
    override val canWarp = true
    /** The demo's automations, one in every state (data/AutomationsDemo.kt); their notes are its banners. */
    override val automations = DemoAutomations(scope, res)
    override val notices: kotlinx.coroutines.flow.Flow<Notice> get() = automations.notices
    override val projectsRoot = "~/Programming"
    override var projects by mutableStateOf(
        listOf(
            Project("~/Programming/hypernova", "hypernova", System.currentTimeMillis() - 20_000, 3),
            Project("~/Programming/0stems", "0stems", System.currentTimeMillis() - 65_000, 1),
            Project("~/Documents/programming/prudnikov-site", "prudnikov-site", System.currentTimeMillis() - 6 * 60_000, 1),
            Project("~/Documents/programming/even_realities/g2-local-stt", "Hyperscribe", System.currentTimeMillis() - 42 * 60_000, 1),
            // Quiet for a day or more: the sky's belt, not planets (see seedSessions).
            Project("~/Documents/thesis-notes", "thesis-notes", System.currentTimeMillis() - 2 * 86_400_000L, 1),
            Project("~/Programming/recipes-bot", "recipes-bot", System.currentTimeMillis() - 3 * 86_400_000L, 1),
            Project("~/Programming/accessibility-audit-checklist", "accessibility-audit-checklist", System.currentTimeMillis() - 5 * 86_400_000L, 1),
            Project("~/dotfiles", "dotfiles", System.currentTimeMillis() - 6 * 86_400_000L, 1),
            Project("~/Programming/home-server", "home-server", System.currentTimeMillis() - 9 * 86_400_000L, 1),
            // A Mac of many projects, for the new-session sheet's picker (search, recent, by folder):
            // a project's worktrees, other folders, some never run from here.
            Project("~/Programming/hypernova/.claude/worktrees/new-session", "new-session", System.currentTimeMillis() - 3 * 3_600_000L, 2),
            Project("~/Programming/hypernova/.claude/worktrees/drawn-ui", "drawn-ui", System.currentTimeMillis() - 26 * 3_600_000L, 3),
            Project("~/Programming/hypernova/.claude/worktrees/phone-update", "phone-update", System.currentTimeMillis() - 2 * 86_400_000L, 1),
            Project("~/Documents/programming/even_realities/hypernavigate", "Hypernavigate", System.currentTimeMillis() - 30 * 3_600_000L, 4),
            Project("~/Documents/programming/even_realities/hypertasks", "Hypertasks", System.currentTimeMillis() - 8 * 86_400_000L, 2),
            Project("~/Programming/Sandbox/route-glow", "route-glow", System.currentTimeMillis() - 20 * 3_600_000L, 1),
            Project("~/Programming/Sandbox/note-to-self", "note-to-self", System.currentTimeMillis() - 4 * 86_400_000L, 1),
            Project("~/Programming/Sandbox/weather-glance", "weather-glance", System.currentTimeMillis() - 12 * 86_400_000L, 1),
            Project("~/Programming/0stems-site", "0stems-site", System.currentTimeMillis() - 14 * 86_400_000L, 2),
            Project("~/Programming/budget-tracker", "budget-tracker", System.currentTimeMillis() - 20 * 86_400_000L, 1),
            Project("~/Programming/kotlin-playground", "kotlin-playground", System.currentTimeMillis() - 30 * 86_400_000L, 0),
            Project("~/Programming/ml-experiments", "ml-experiments", System.currentTimeMillis() - 35 * 86_400_000L, 1),
            Project("~/Programming/photo-sorter", "photo-sorter", System.currentTimeMillis() - 41 * 86_400_000L, 0),
            Project("~/Programming/rust-cli-tools", "rust-cli-tools", System.currentTimeMillis() - 60 * 86_400_000L, 0),
            Project("~/Documents/programming/advent-of-code", "advent-of-code", System.currentTimeMillis() - 90 * 86_400_000L, 0),
            Project("~/Programming/game-of-life", "game-of-life", System.currentTimeMillis() - 120 * 86_400_000L, 0),
        ),
    )
        private set
    override var providers by mutableStateOf(
        listOf(
            Provider("anthropic", res.getString(R.string.provider_builtin_name), "anthropic", null, false, ProviderModels(), builtIn = true),
            Provider("openrouter", "OpenRouter", "openrouter", "https://openrouter.ai/api", true, ProviderModels(default = "openai/gpt-5", sonnet = "anthropic/claude-sonnet-4.5"), builtIn = false, balance = DEMO_BALANCE),
        ),
    )
        private set

    private val now get() = System.currentTimeMillis()
    private var seq = 0L
    private fun key(prefix: String) = "$prefix-${seq++}"

    // A MacBook on its battery, so the demo shows the charge in the connection chip, kept awake
    // while it works.
    override var mac by mutableStateOf(
        Mac(
            res.getString(R.string.demo_mac), online = true, via = "demo",
            battery = MacBattery(62, charging = false, onAC = false, minutesLeft = 245),
            awake = MacAwake(MacAwake.WORKING, holding = true, backpackReady = true, lidClosed = false),
            browseOutsideProjects = false,
        ),
    )
        private set
    override var usage by mutableStateOf(
        Usage(
            fiveHour = 0.42f, sevenDay = 0.18f,
            fiveHourResetAt = System.currentTimeMillis() + 134 * 60_000L,
            sevenDayResetAt = System.currentTimeMillis() + 61 * 3_600_000L,
            // The weeks /usage shows for models with their own (docs/PROTOCOL.md, "Per-model weeks").
            models = listOf(
                ModelWeek("Sonnet", 0.03f, System.currentTimeMillis() + 61 * 3_600_000L),
                ModelWeek("Fable", 0.41f, System.currentTimeMillis() + 52 * 3_600_000L),
            ),
            // Something works at launch: the busy demo's pace (see demoRate).
            rate = UsageRate(BUSY_PACE, WEEK_PACE, null, null),
        ),
    )
        private set

    // ─── Time warp (demo): scrub usage to watch the sun live its life ───────

    /** Drag the 5-hour meter (or the sun). Reaching the end hits the limit: the disk drains, then a 30s demo wait while it refills. */
    override fun warpFiveHour(f: Float) {
        if (usage.anyLimited) return
        if (f >= 0.995f) hitLimit(cooldownMs = 30_000) else usage = usage.copy(fiveHour = f.coerceIn(0f, 0.99f))
    }

    /**
     * Drag the 7-day meter: the far sky thins a little as it fills. Reaching the end hits the weekly
     * limit: the donor's stream stops and the disk drains (its low state), then a 30s demo wait,
     * then the stream returns and the disk ignites.
     */
    override fun warpSevenDay(f: Float) {
        if (usage.weekLimited) return
        if (f >= 0.995f) hitWeekLimit(cooldownMs = 30_000) else usage = usage.copy(sevenDay = f.coerceIn(0f, 0.99f))
    }

    private fun hitWeekLimit(cooldownMs: Long) {
        usage = usage.copy(sevenDay = 1f, weekLimitedAt = now, sevenDayResetAt = now + cooldownMs)
        stopEverything()
    }

    private fun hitLimit(cooldownMs: Long) {
        usage = usage.copy(fiveHour = 1f, limitedAt = now, fiveHourResetAt = now + cooldownMs)
        stopEverything()
    }

    private fun stopEverything() {
        // Every running session stops: rate-limited until the reset.
        sessions = sessions.map {
            if (it.state == SessionState.Working) it.copy(state = SessionState.Idle, activity = "rate limited", updatedAt = now) else it
        }
        for ((id, list) in jobs.toMap()) { list.toList().forEach { j -> j.cancel() }; jobs.remove(id) }
        for (slotState in transcripts.values) {
            slotState.value = slotState.value.map { if (it is Item.Terminal && it.running) it.copy(running = false, exitCode = 130, lines = it.lines + "rate limited") else it }
        }
    }
    override var sessions by mutableStateOf(seedSessions())
        private set

    // One state per session, so a screen only observes its own transcript.
    private val transcripts = HashMap<String, MutableState<List<Item>>>()
    private fun slot(id: String) = transcripts.getOrPut(id) { mutableStateOf(emptyList()) }

    /** Each session's transcript in drawing order, with the list it was made from. */
    private val ordered = HashMap<String, Triple<List<Item>, Int, List<Item>>>()

    /**
     * Prompts typed while the Mac was away (data/OfflineQueue.kt): the demo has one waiting, to show
     * the chat's bubble and Home's chip. The demo's Mac is always there, so nothing sends it.
     */
    private val offline = OfflineQueue(object : QueueStore {
        override fun read(): String? = null
        override fun write(json: String) {}
    }).also { it.add("s-hypernova", res.getString(R.string.demo_waiting_prompt), emptyList(), cwd = "~/Programming/hypernova") }

    override fun transcript(id: String): List<Item> {
        val list = slot(id).value
        val v = offline.version
        val waiting = offline.of(id)
        if (waiting.isEmpty() && list.none { it is Item.User && it.queued }) return list
        ordered[id]?.let { (src, ver, out) -> if (src === list && ver == v) return out }
        // Prompts still queued sit under Claude's live output, as the terminal draws its queue;
        // the ones waiting for the Mac come last.
        val (queued, rest) = list.partition { it is Item.User && it.queued }
        return (rest + queued + waiting.map(Waiting::item)).also { ordered[id] = Triple(list, v, it) }
    }

    override fun waiting(sessionId: String): List<Item.User> = offline.of(sessionId).map(Waiting::item)
    override fun waitingCount(sessionId: String): Int = offline.count(sessionId)
    override fun editWaiting(waitingId: String, text: String): Boolean = offline.edit(waitingId, text)
    override fun removeWaiting(waitingId: String) { offline.remove(waitingId) }
    override fun session(id: String): Session? = sessions.firstOrNull { it.id == id }

    // Simulated work per session, so interrupt can cancel it.
    private val jobs = HashMap<String, MutableList<Job>>()
    private fun launchFor(id: String, block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(block = block)
        jobs.getOrPut(id) { mutableListOf() }.add(job)
        job.invokeOnCompletion { jobs[id]?.remove(job) }
    }

    // Names that already scrambled in once; never again for the same session.
    private val revealed = HashSet<String>()

    /** True exactly once per new session: the first card to ask gets the scramble. */
    override fun consumeReveal(s: Session): Boolean = s.isNew && revealed.add(s.id)

    init {
        slot("s-hypernova").value = hypernovaTranscript()
        slot("s-haze").value = listOf(
            // Asked with a skill, and the turn's end: what it took and cost.
            Item.User(
                key("u"), "/hyper-cosmos Make the top bar blur strongest at the screen edge, like Telegram.",
                command = CommandRun("hyper-cosmos", "Make the top bar blur strongest at the screen edge, like Telegram.", "skill"),
            ),
            Item.Tool(key("t"), "Read", "/tmp/haze-src/x/commonMain/dev/chrisbanes/haze/HazeProgressive.kt"),
            Item.Assistant(key("a"), "Done: a vertical progressive blur, full intensity under the status bar, fading to sharp over the last 30dp."),
            Item.Result(key("r"), ok = true, durationMs = 72_400, costUsd = 0.42, turns = 4, error = null),
        )
        slot("s-0stems").value = ostemsTranscript()
        // What the demo's automated sessions did (data/AutomationsDemo.kt).
        slot(DemoAutomations.SESSION_TESTS).value = listOf(
            Item.User(key("u"), res.getString(R.string.au_t_plugged_prompt)),
            Item.Tool(key("t"), "Read", "package.json"),
            Item.Approval(key("ap"), "Bash", "npm test", "Run the project's tests"),
        )
        slot(DemoAutomations.SESSION_PRS).value = listOf(
            Item.User(key("u"), res.getString(R.string.au_t_prs_prompt)),
            Item.Tool(key("t"), "Bash", "gh pr list --author @me --state open"),
            Item.Tool(key("t"), "Bash", "gh pr checks 212", running = true),
        )
        slot(DemoAutomations.SESSION_BRIEF).value = listOf(
            Item.User(key("u"), res.getString(R.string.au_t_brief_prompt)),
            Item.Tool(key("t"), "Bash", "git log --since=yesterday --oneline"),
            Item.Tool(key("t"), "Bash", "git status"),
            Item.Assistant(key("a"), "3 PRs merged; the parser tests fail on main since 2a41f. The widget branch is ready to merge, and the lens scrolling work is half done. Worth doing next: fix the parser test (it expects the old error text)."),
        )
        slot("s-site").value = listOf(
            Item.User(key("u"), "Add the Hypernova card to the work strip, status «in progress»."),
            Item.Tool(key("t"), "Read", "src/content/site.ts"),
            Item.Diff(
                key("d"), "src/content/site.ts", 9, 0,
                listOf(
                    DiffLine(' ', "  {"),
                    DiffLine('+', "    slug: 'hypernova',"),
                    DiffLine('+', "    title: 'Hypernova',"),
                    DiffLine('+', "    category: 'Product',"),
                    DiffLine('+', "    status: 'in progress',"),
                    DiffLine(' ', "  },"),
                ),
            ),
            Item.Assistant(key("a"), "Added the card after **Hypernavigate**. It uses the poster frame from `public/media/projects`, so you only need to drop a `hypernova-poster.webp` there."),
        )
        slot("s-scribe").value = listOf(
            Item.User(key("u"), "Why does the lens flicker when the transcript wraps?"),
            Item.Tool(key("t"), "Grep", "drawLines · src/asr"),
            Item.Assistant(key("a"), "Each wrap rebuilt both image tiles. I now patch only the tile whose rows changed, so a wrap costs one BLE write instead of two."),
        )
        slot("s-telegram").value = listOf(
            Item.User(key("u"), "Show 5h usage in the pinned message."),
            Item.Assistant(key("a"), "Done. The pinned message now updates whenever the status line reports new rate limits."),
        )
        simulate()
    }

    // ─── Actions ────────────────────────────────────────────────────────────

    fun resolve(sessionId: String, approvalKey: String, how: Resolution) {
        val s = slot(sessionId)
        s.value = s.value.map { if (it is Item.Approval && it.key == approvalKey) it.copy(resolved = how) else it }
        update(sessionId) {
            it.copy(
                state = if (how == Resolution.Denied) SessionState.Idle else SessionState.Working,
                activity = when {
                    how == Resolution.Denied -> "denied · waiting for you"
                    sessionId == DemoAutomations.SESSION_TESTS -> "$ npm test"
                    else -> "$ git push origin main"
                },
                updatedAt = now,
            )
        }
        if (how != Resolution.Denied && sessionId == "s-0stems") launchFor(sessionId) { finishOstemsPush() }
    }

    /** After a reset, sessions that were stopped by the limit are ready again. */
    private fun wakeUp() {
        sessions = sessions.map { if (it.activity == "rate limited") it.copy(activity = "waiting for your next prompt", updatedAt = now) else it }
    }

    override suspend fun send(sessionId: String, text: String, files: List<Attachment>, replace: Boolean): Result<String> {
        sendNow(sessionId, text, files.isEmpty())
        return Result.success(sessionId)
    }

    private fun sendNow(sessionId: String, text: String, asCommand: Boolean = true) {
        if (text.isBlank()) return
        val s = slot(sessionId)
        val block = session(sessionId)?.activeBlock(account, providers, now)
        // "Switch to Sonnet" on an Opus limit: Claude Code switches, and the session can go on.
        if (asCommand && block?.kind == Blocked.MODEL && text.trim().startsWith("/model ")) {
            val m = text.trim().removePrefix("/model ").trim()
            s.value = s.value + Item.User(key("u"), text.trim(), command = CommandRun.typed(text), output = listOf(CommandOutput(key("o"), "Set model to ${m.replaceFirstChar { it.uppercase() }}", Tone.Info)))
            update(sessionId) { it.copy(model = m, blocked = null, activity = res.getString(R.string.activity_waiting), updatedAt = now) }
            return
        }
        s.value = s.value + Item.User(key("u"), text.trim(), command = if (asCommand) CommandRun.typed(text) else null)
        if (block != null) {
            // Claude Code answers at once with the same line, and the session stays where it was.
            s.value = s.value + Item.Notice(key("n"), block.text, if (block.needsYou) Tone.Error else Tone.Warn, blocker = block.kind)
            update(sessionId) { it.copy(state = SessionState.Idle, updatedAt = now) }
            return
        }
        // Rate-limited: the prompt waits, the session doesn't start (one moved to OpenRouter runs on).
        if (usage.anyLimited && session(sessionId)?.providerId.let { it == null || it == "anthropic" }) {
            update(sessionId) { it.copy(state = SessionState.Idle, activity = "rate limited", updatedAt = now) }
            return
        }
        update(sessionId) { if (it.state == SessionState.NeedsYou) it else it.copy(state = SessionState.Working, activity = "thinking…", updatedAt = now) }
        launchFor(sessionId) {
            delay(1800)
            slot(sessionId).value = slot(sessionId).value +
                Item.Assistant(key("a"), "On it. (This is milestone 1, so the reply is simulated; the real session arrives with the daemon.)")
            update(sessionId) { settle(sessionId, it, "waiting for your next prompt") }
        }
    }

    /** Esc: cancel what the session is doing, close running terminals with exit 130. */
    override fun interrupt(sessionId: String) {
        jobs.remove(sessionId)?.toList()?.forEach { it.cancel() }
        val s = slot(sessionId)
        s.value = s.value.map { if (it is Item.Terminal && it.running) it.copy(running = false, exitCode = 130, lines = it.lines + "^C") else it }
        update(sessionId) { settle(sessionId, it, "interrupted") }
    }

    // ─── Hub actions the demo can't really do ───────────────────────────────

    override suspend fun resume(sessionId: String): Result<String> {
        update(sessionId) { it.copy(stoppedForAway = false) }
        sendNow(sessionId, res.getString(R.string.continue_prompt))
        return Result.success(sessionId)
    }

    override fun answer(sessionId: String, item: Item.Approval, answer: Answer) {
        if (item.key == AGENT_ASK) return answerAgent(answer != Answer.Deny)
        resolve(
            sessionId, item.key,
            when (answer) {
                Answer.Allow -> Resolution.Allowed
                Answer.Always -> Resolution.AllowedAlways
                Answer.Session -> Resolution.AllowedSession
                Answer.Deny -> Resolution.Denied
                Answer.ApprovePlan -> Resolution.PlanApproved
                Answer.KeepPlanning -> Resolution.KeptPlanning
                is Answer.Answers -> Resolution.Answered
            },
        )
    }

    override suspend fun start(req: NewSessionRequest): Result<String> {
        val project = req.newProject?.trim() ?: projects.firstOrNull { it.path == req.projectPath }?.name ?: "project"
        if (req.newProject != null && projects.none { it.name == project }) {
            projects = listOf(Project("$projectsRoot/$project", project, now, 0)) + projects
        }
        val id = start(project, req.model ?: "default", req.permissionMode)
        sessions = sessions.map { if (it.id == id) it.copy(name = req.prompt.trim().lineSequence().first().take(48)) else it }
        sendNow(id, req.prompt)
        return Result.success(id)
    }

    /** What a Mac answers (docs/PROTOCOL.md, "Models"): Claude Code's list on a Max plan, OpenRouter's catalogue in part. */
    override suspend fun models(): Result<List<ModelList>> {
        delay(250)
        return Result.success(listOf(ModelList("anthropic", "claude", DEMO_CLAUDE), ModelList("openrouter", "openrouter", demoOpenRouter())))
    }

    private fun demoOnly(): HubException = HubException(res.getString(R.string.demo_needs_mac))
    override suspend fun files(path: String): Result<Listing> = FilesDemo.listing(path)
    override suspend fun file(path: String): Result<FileBytes> = FilesDemo.file(path)
    /** The demo's folders are its files' (FilesDemo), the folders alone. */
    override suspend fun folders(path: String?, hidden: Boolean): Result<Folders> =
        FilesDemo.listing(path?.takeIf { it != "~" } ?: projectsRoot).map { l ->
            Folders(l.path, l.path.substringBeforeLast('/', "").ifEmpty { null }, "~", l.entries.filter { it.isDir }.map { Folder(it.name, it.path) })
        }
    override suspend fun download(source: String, target: java.io.File, resume: DownloadResume?, onStart: (String?) -> Unit, onProgress: (Long, Long) -> Unit): Result<Downloaded> =
        FilesDemo.download(source, target, resume, onStart, onProgress)
    override suspend fun upload(file: Upload): Result<Attachment> = Result.failure(demoOnly())
    override suspend fun commands(cwd: String?): Result<List<SlashCommand>> = Result.success(
        listOf(
            SlashCommand("hyper-cosmos", "The \"Hyper cosmos\" design language behind Hypernova, 0stems and Hyperscribe.", "", emptyList(), false),
            SlashCommand("release-notes", "Write release notes from the commits since the last tag.", "[version]", emptyList(), false),
            SlashCommand("model", "Set the AI model for Claude Code", "<model>", emptyList(), true),
            SlashCommand("compact", "Free up context by summarizing the conversation so far", "<optional custom summarization instructions>", emptyList(), true),
            SlashCommand("context", "Show current context usage", "", emptyList(), true),
            SlashCommand("usage", "Show session cost, plan usage, and what's contributing to your limits", "", listOf("cost", "stats"), true),
            SlashCommand("effort", "Set effort level for model usage", "<low|medium|high|xhigh|max|auto>", emptyList(), true),
            SlashCommand("clear", "Start a new session with empty context", "[name]", listOf("reset", "new"), true),
        ),
    )

    /** As the Mac does: Backpack holds with the lid closed for 8 hours, Off holds nothing. */
    /** The demo's switch: nothing on a Mac changes, so it just says what it would. */
    override suspend fun setBrowseOutsideProjects(on: Boolean): Result<Unit> {
        mac = mac.copy(browseOutsideProjects = on)
        return Result.success(Unit)
    }

    override suspend fun setAwake(mode: String): Result<Unit> {
        val a = mac.awake ?: return Result.failure(demoOnly())
        mac = mac.copy(
            awake = a.copy(
                mode = mode, holding = mode != MacAwake.OFF, lidClosedOk = mode == MacAwake.BACKPACK,
                until = if (mode == MacAwake.BACKPACK) now + 8 * 3_600_000L else null, reason = null, sleepAt = null,
            ),
        )
        return Result.success(Unit)
    }

    /** As the Mac does: Backpack's end moves 2 hours on. */
    override suspend fun extendAwake(): Result<Unit> {
        val a = mac.awake ?: return Result.failure(demoOnly())
        val until = a.until ?: return Result.failure(demoOnly())
        mac = mac.copy(awake = a.copy(until = until + 2 * 3_600_000L, extendable = false))
        return Result.success(Unit)
    }

    override suspend fun setMode(sessionId: String, mode: String): Result<Boolean> {
        val running = sessions.firstOrNull { it.id == sessionId }?.state == SessionState.Working
        update(sessionId) { it.copy(mode = mode) }
        // As the Mac does: Bypass starts with the next turn when one is running.
        return Result.success(running && mode == "bypassPermissions")
    }
    /** As a shared terminal does: Claude Code's /model, its line in the transcript, the chip at once. */
    override suspend fun setModel(sessionId: String, model: String?, confirmPaid: Boolean): Result<ModelSwitch> {
        val s = session(sessionId) ?: return Result.failure(demoOnly())
        // As the Mac does: another provider bills it, so it asks first.
        val provider = providers.firstOrNull { it.id == (s.providerId ?: "anthropic") }
        if (provider != null && !provider.builtIn && !confirmPaid) return Result.failure(PaidModel(res.getString(R.string.model_paid_q)))
        delay(300)
        // As the Mac does: the session shows the option's id at once when it's known ("sonnet" runs claude-sonnet-5-5).
        val options = if (provider == null || provider.builtIn) ModelCatalog.CLAUDE else ModelCatalog.settings(provider)
        val name = options.firstOrNull { it.value == model }?.model ?: model ?: "claude-opus-5-5"
        val block = s.activeBlock(account, providers, now)
        val typed = "/model ${model ?: "default"}"
        slot(sessionId).value = slot(sessionId).value + Item.User(
            key("u"), typed, command = CommandRun.typed(typed),
            output = listOf(CommandOutput(key("o"), "Set model to ${ModelNames.chip(name).replaceFirstChar { it.uppercase() }}", Tone.Info)),
        )
        // "Switch to Sonnet" on an Opus limit: the session can go on.
        update(sessionId) { if (block?.kind == Blocked.MODEL) it.copy(model = name, blocked = null, activity = res.getString(R.string.activity_waiting), updatedAt = now) else it.copy(model = name, updatedAt = now) }
        return Result.success(ModelSwitch(name, nextTurn = s.state == SessionState.Working))
    }
    override suspend fun preview(path: String?, port: Int?): Result<String> = Result.failure(demoOnly())
    override suspend fun checklist(): Result<String> = Result.failure(demoOnly())

    // Voice: a dictation that needs no microphone (data/Voice.kt, FakeDictation).
    override fun dictate(): Dictation =
        FakeDictation(scope, android.os.SystemClock.elapsedRealtime(), android.os.SystemClock::elapsedRealtime)

    override suspend fun saveProvider(id: String?, input: ProviderInput): Result<ProviderSaved> {
        DemoProviders.refusal(res, input.kind, input.baseUrl, input.apiKey)?.let { return Result.failure(it) }
        delay(500)
        val old = providers.firstOrNull { it.id == id }
        val check = input.apiKey?.takeIf { it.isNotBlank() }?.let { DemoProviders.check(input.kind, it, input.baseUrl) }
        // Names are unique, as the Mac makes them ("OpenRouter 2").
        val taken = providers.filter { it.id != id }.map { it.name }.toSet()
        val name = input.name.trim().let { n -> if (n !in taken) n else (2..99).map { "$n $it" }.first { it !in taken } }
        val p = Provider(
            id = id ?: "p-${seq++}", name = name, kind = input.kind, baseUrl = input.baseUrl,
            hasKey = old?.hasKey == true || !input.apiKey.isNullOrBlank(), models = input.models, builtIn = false,
            balance = check?.balance ?: old?.balance, paid = input.paid ?: old?.paid ?: true,
        )
        providers = providers.filter { it.id != p.id } + p
        return Result.success(ProviderSaved(p, check, keyDropped = false))
    }

    override suspend fun checkNewProvider(kind: String, baseUrl: String?, apiKey: String?, id: String?): Result<ProviderCheck> {
        DemoProviders.refusal(res, kind, baseUrl, apiKey)?.let { return Result.failure(it) }
        delay(700)
        val key = apiKey?.takeIf { it.isNotBlank() } ?: if (providers.firstOrNull { it.id == id }?.hasKey == true) "saved" else ""
        return Result.success(DemoProviders.check(kind, key, baseUrl))
    }

    override suspend fun deleteProvider(id: String): Result<Unit> {
        providers = providers.filter { it.id != id || it.builtIn }
        return Result.success(Unit)
    }

    /** A new session ignites in the cosmos: scramble-in name, ignition ring, first prompt pending. */
    fun start(project: String, model: String, mode: String): String {
        val hex = "%02x".format(Random.nextInt(256))
        val id = key("s-$hex")
        sessions = sessions + Session(
            hex = hex, id = id, name = "new session", project = project,
            state = SessionState.Idle, model = model, mode = mode,
            activity = "waiting for your first prompt", updatedAt = now, isNew = true,
        )
        slot(id).value = emptyList()
        return id
    }

    // ─── Simulation ─────────────────────────────────────────────────────────

    /** The state a session falls back to: needs you if an approval is open, working if a terminal runs. */
    private fun settle(id: String, s: Session, idleActivity: String): Session {
        val items = slot(id).value
        return when {
            items.any { it is Item.Approval && it.resolved == null } -> s.copy(state = SessionState.NeedsYou, updatedAt = now)
            items.any { it is Item.Terminal && it.running } -> {
                val term = items.last { it is Item.Terminal && it.running } as Item.Terminal
                s.copy(state = SessionState.Working, activity = "$ ${term.command}", updatedAt = now)
            }
            else -> s.copy(state = SessionState.Idle, activity = idleActivity, updatedAt = now)
        }
    }

    private fun update(id: String, f: (Session) -> Session) {
        sessions = sessions.map {
            if (it.id != id) return@map it
            val next = f(it)
            // `phase` defaults from `state` only at construction: a copy that changes the state alone
            // kept the old phase (a NEEDS YOU bar after the answer). Follow the state unless set too.
            if (next.state != it.state && next.phase == it.phase) next.copy(phase = Phase.of(next.state)) else next
        }
    }

    private fun simulate() {
        // The build in "hypernova" streams into its mini terminal, then loops (not in the tour's calm scenes).
        scope.launch {
            delay(800)
            while (isActive) {
                if (usage.limited || session("s-hypernova")?.blocked != null || scene != Scene.Busy) { delay(1000); continue }
                val job = scope.launch { runGradle() }
                jobs.getOrPut("s-hypernova") { mutableListOf() }.add(job)
                job.join()
                jobs["s-hypernova"]?.remove(job)
                delay(9000)
            }
        }
        // Usage creeps up a little while things are working. It never reaches the limit on its
        // own (that's for the time warp, or the real daemon), and never pulls a warped value down.
        scope.launch {
            while (isActive) {
                delay(4000)
                val u = usage
                val working = sessions.count { it.state == SessionState.Working }
                // The spend pace shows only while something works, as the real daemon sends it.
                val rate = demoRate(u, working)
                if (rate != u.rate) usage = u.copy(rate = rate)
                if (u.anyLimited || u.fiveHour >= 0.9f) continue
                usage = usage.copy(fiveHour = min(0.9f, u.fiveHour + 0.002f * working))
            }
        }
        // Resets: the disk ignites again (5h), or the stream returns after the week's low state (7 days).
        scope.launch {
            while (isActive) {
                delay(250)
                val u = usage
                if (u.weekLimited && now >= u.sevenDayResetAt) {
                    usage = u.copy(
                        sevenDay = 0.02f, weekLimitedAt = null, sevenDayResetAt = now + 7 * 24 * 3_600_000L,
                        fiveHour = 0.03f, limitedAt = null, fiveHourResetAt = now + 5 * 3_600_000L,
                    )
                    wakeUp()
                    limitOver()
                } else if (u.limited && !u.weekLimited && now >= u.fiveHourResetAt) {
                    usage = u.copy(fiveHour = 0.03f, limitedAt = null, fiveHourResetAt = now + 5 * 3_600_000L)
                    wakeUp()
                    limitOver()
                }
            }
        }
        // A new session appears a few seconds in (it ignites, and its name scrambles in once).
        scope.launch {
            delay(7000)
            slot("s-nav").value = listOf(
                Item.User(key("u"), "Make the route glow pulse once when a turn is under 30 m."),
                Item.Tool(key("t"), "Read", "src/render/lens.ts"),
            )
            sessions = sessions + Session(
                hex = "5e", id = "s-nav", name = "lens route glow", project = "Hypernavigate",
                state = SessionState.Working, model = "sonnet", mode = "acceptEdits",
                activity = "Read src/render/lens.ts", updatedAt = now, isNew = true,
            )
        }
    }

    private suspend fun runGradle() {
        val id = "s-hypernova"
        val cmd = "./gradlew :app:assembleDebug"
        val output = listOf(
            "> Task :app:preBuild UP-TO-DATE",
            "> Task :app:generateDebugResources",
            "> Task :app:processDebugResources",
            "> Task :app:compileDebugKotlin",
            "w: Cosmos.kt:212 'alpha' is never used",
            "> Task :app:dexBuilderDebug",
            "> Task :app:mergeDebugDexes",
            "> Task :app:packageDebug",
            "> Task :app:assembleDebug",
            "",
            "BUILD SUCCESSFUL in 41s",
            "38 actionable tasks: 12 executed, 26 up-to-date",
        )
        // Keep just the latest finished run of this command, so the transcript doesn't pile up.
        val s = slot(id)
        val runs = s.value.filter { it is Item.Terminal && it.command == cmd }
        if (runs.size > 1) s.value = s.value - runs.dropLast(1).toSet()

        val termKey = key("term")
        val started = now
        var lines = emptyList<String>()
        s.value = s.value + Item.Terminal(termKey, cmd, lines, running = true, exitCode = null, elapsedMs = 0)
        update(id) { it.copy(state = SessionState.Working, activity = "$ $cmd", updatedAt = now) }
        for (l in output) {
            delay(if (l.startsWith("> Task :app:compile")) 2600 else 520)
            lines = lines + l
            replaceTerminal(id, termKey) { it.copy(lines = lines, elapsedMs = now - started) }
        }
        replaceTerminal(id, termKey) { it.copy(running = false, exitCode = 0, elapsedMs = now - started) }
        deliverQueued(id)
        update(id) { it.copy(activity = "BUILD SUCCESSFUL in 41s", updatedAt = now) }
        delay(1200)
        update(id) { settle(id, it, "waiting for your next prompt") }
    }

    /** The step finished: Claude takes what was queued, which becomes a message where it was drawn, last. */
    private fun deliverQueued(id: String) {
        val s = slot(id)
        val queued = s.value.filter { it is Item.User && it.queued }
        if (queued.isEmpty()) return
        s.value = s.value - queued.toSet() + queued.map { (it as Item.User).copy(status = null) }
        update(id) { it.copy(queued = 0) }
    }

    private fun replaceTerminal(id: String, key: String, f: (Item.Terminal) -> Item.Terminal) {
        val s = slot(id)
        s.value = s.value.map { if (it is Item.Terminal && it.key == key) f(it) else it }
    }

    private suspend fun finishOstemsPush() {
        val id = "s-0stems"
        val termKey = key("push")
        val out = listOf(
            "Enumerating objects: 23, done.",
            "Counting objects: 100% (23/23), done.",
            "Writing objects: 100% (12/12), 3.91 KiB | 3.91 MiB/s, done.",
            "To github.com:prudnik/0stems.git",
            "   8c1f2e4..b7a90d1  main -> main",
        )
        slot(id).value = slot(id).value + Item.Terminal(termKey, "git push origin main", emptyList(), true, null, 0)
        var lines = emptyList<String>()
        val started = now
        for (l in out) {
            delay(420)
            lines = lines + l
            replaceTerminal(id, termKey) { it.copy(lines = lines, elapsedMs = now - started) }
        }
        replaceTerminal(id, termKey) { it.copy(running = false, exitCode = 0, elapsedMs = now - started) }
        delay(600)
        slot(id).value = slot(id).value + Item.Assistant(key("a"), "Pushed. `main` is at **b7a90d1**, and the Vercel preview should be up in about a minute.")
        update(id) { settle(id, it, "pushed b7a90d1") }
    }

    /**
     * The demo's spend pace while something works (%/min), as the daemon sends it: the busy demo
     * spends fast (the 5-hour meters' faint fill runs long, and as the demo's usage creeps up the
     * limit comes before the reset: the red notch), the tour's "one at work" scene slowly; with the
     * time to the limit only when that comes before the reset, to the minute.
     */
    private fun demoRate(u: Usage, working: Int): UsageRate? {
        if (working == 0 || u.anyLimited) return null
        val five = if (scene == Scene.OneLive) SLOW_PACE else BUSY_PACE
        fun eta(used: Float, pace: Float, resetAt: Long): Long? {
            val ms = ((1f - used) * 100f / pace * 60_000f).toLong()
            return if (now + ms < resetAt) maxOf(MIN, ms / MIN * MIN) else null
        }
        return UsageRate(five, WEEK_PACE, eta(u.fiveHour, five, u.fiveHourResetAt), eta(u.sevenDay, WEEK_PACE, u.sevenDayResetAt))
    }

    // ─── Account (demo) ─────────────────────────────────────────────────────
    // Long-press the sun to walk through what can happen to the account, one state a press:
    // signed out (Sign in works here: paste any code of six characters or more; a shorter one is
    // turned down), no plan, on hold, out of usage credits, an Opus limit, the 5-hour limit (the
    // disk drains), the weekly limit (the disk's low state), OpenRouter rate limited, out of credits,
    // its key refused (a card on Home with Fix), then the calm sky's two scenes (one session at
    // work among many quiet projects; nothing at work at all), then Codex in use next to Claude
    // Code (its own star, its sessions round it, "Claude usage" and "Codex usage") and Codex's
    // window spent, then fine again. Each trouble shows
    // its home card, stops a session (a card above its composer, a slim notice in its chat) and,
    // signed out, eclipses the sun. OpenRouter is set up, so "Continue on OpenRouter" works too.

    override var account by mutableStateOf(Account.Ok)
        private set

    /** Codex in the demo: only in the tour's two Codex scenes (a Mac without it otherwise, as most are). */
    override var codex by mutableStateOf<CodexState?>(null)
        private set

    override suspend fun setCodexEnabled(on: Boolean): Result<CodexState?> {
        val c = codex ?: return Result.failure(HubException(res.getString(R.string.cx_err_turn_on)))
        codex = c.copy(enabled = on)
        return Result.success(codex)
    }

    /**
     * The tour's Codex scenes: Codex on next to Claude Code, two sessions of its own round its star
     * (one at work), its window at 38% or ([limited]) spent; [on] false takes it all away again.
     */
    private fun demoCodex(on: Boolean, limited: Boolean = false) {
        sessions = sessions.filter { !it.codex }
        if (!on) { codex = null; return }
        val t = now
        codex = CodexState(
            available = true, enabled = true, connected = true, version = "0.170.0", min = "0.163.0",
            account = CodexAccount(true, "chatgpt", "plus"),
            usage = CodexUsage(Window(if (limited) 100.0 else 38.0, t + if (limited) 72 * MIN else 2 * HOUR + 10 * MIN), Window(52.0, t + 3 * DAY), limited, t),
        )
        sessions = listOf(
            Session("6d", CX_WORK, "tidy the release notes", "0stems", if (limited) SessionState.Idle else SessionState.Working, "gpt-5-codex", AgentKind.CAN_EDIT,
                if (limited) res.getString(R.string.activity_waiting) else "$ npm test", t - 40_000, providerId = AgentKind.CODEX_PROVIDER, createdAt = t - 25 * MIN, agent = AgentKind.CODEX),
            Session("3a", CX_IDLE, "review the pairing flow", "hypernova", SessionState.Idle, "gpt-5-codex", AgentKind.READ_ONLY,
                res.getString(R.string.activity_waiting), t - 7 * MIN, providerId = AgentKind.CODEX_PROVIDER, createdAt = t - 50 * MIN, agent = AgentKind.CODEX),
        ) + sessions
    }

    private val tour = listOf(
        Account.LOGGED_OUT, Account.NO_SUBSCRIPTION, Account.ON_HOLD, Account.NO_CREDIT,
        TOUR_MODEL, TOUR_SESSION, TOUR_WEEK, TOUR_OR_RATE, TOUR_OR_CREDITS, TOUR_OR_KEY,
        TOUR_ONE_LIVE, TOUR_QUIET, TOUR_CODEX, TOUR_CODEX_LIMITED,
    )

    /** How busy the demo is: as designed, one session at work and the rest resting, or nothing at all. */
    private enum class Scene { Busy, OneLive, Quiet }
    private var scene = Scene.Busy
    private var tourAt = -1
    private val context get() = Core.app

    /** The next account state of the tour (after the last, fine again). */
    fun nextAccountState() {
        tourAt = (tourAt + 1) % (tour.size + 1)
        val step = tour.getOrNull(tourAt)
        val t = now
        unblockAll()
        setOpenRouter(null, DEMO_BALANCE)
        scene = Scene.Busy
        demoCodex(step == TOUR_CODEX || step == TOUR_CODEX_LIMITED, limited = step == TOUR_CODEX_LIMITED)
        when (step) {
            null -> { endLimits(); account = Account.Ok }
            Account.LOGGED_OUT -> stopOn(
                Account(Account.LOGGED_OUT, reason = "expired", text = "Login expired · Please run /login", since = t, sessionId = HIT, checkedAt = t),
                Blocked.LOGGED_OUT,
            )
            Account.NO_SUBSCRIPTION -> stopOn(
                Account(Account.NO_SUBSCRIPTION, reason = "rejected", text = "Please run /login · API Error: 403 Request not allowed", since = t, sessionId = HIT, checkedAt = t),
                Blocked.NO_SUBSCRIPTION,
            )
            Account.ON_HOLD -> stopOn(
                Account(Account.ON_HOLD, text = "Your account is on hold and can't use Claude Code. View details or appeal: https://claude.ai/restricted", since = t, sessionId = HIT),
                Blocked.ON_HOLD,
            )
            Account.NO_CREDIT -> {
                val at = t + 3 * HOUR + 20 * MIN
                stopOn(
                    Account(Account.NO_CREDIT, reason = "out_of_credits", limit = Account.Limit("credits", "usage credit limit", at, null, false), text = "You're out of usage credits · resets ${clockOf(at)}", since = t, sessionId = HIT),
                    Blocked.NO_CREDIT, at,
                )
            }
            TOUR_MODEL -> {
                val at = t + 2 * DAY + 3 * HOUR
                val text = "You've hit your Opus limit · resets ${clockOf(at)}"
                account = Account(Account.LIMITED, plan = "max", limit = Account.Limit("model", "Opus limit", at, null, false), text = text, since = t, sessionId = OPUS)
                block(OPUS, Blocked(Blocked.MODEL, text, at, null))
            }
            // The sessions stop first (each with its card and notice), then the sun goes.
            TOUR_SESSION -> {
                val at = t + 72 * MIN
                stopOn(
                    Account(Account.LIMITED, plan = "max", limit = Account.Limit("five_hour", "session limit", at, at, false), text = "You've hit your session limit · resets ${clockOf(at)}", since = t, sessionId = HIT),
                    Blocked.LIMIT, at, auto = true,
                )
                hitLimit(cooldownMs = at - t)
            }
            TOUR_WEEK -> {
                val at = t + 2 * DAY + 4 * HOUR
                stopOn(
                    Account(Account.LIMITED, plan = "max", limit = Account.Limit("seven_day", "weekly limit", at, null, false), text = "You've hit your weekly limit · resets ${clockOf(at)}", since = t, sessionId = HIT),
                    Blocked.LIMIT, at,
                )
                hitWeekLimit(cooldownMs = at - t)
            }
            // OpenRouter's own trouble: the subscription is fine, the companion star's sessions aren't.
            TOUR_OR_RATE -> {
                endLimits()
                account = Account.Ok
                val at = t + 14 * MIN
                setOpenRouter(ProviderStatus(ProviderStatus.LIMITED, "API Error: Request rejected (429) · Rate limit exceeded: free-models-per-min.", t, at), DEMO_BALANCE)
                block(OR_IDLE, Blocked(Blocked.PROVIDER, "API Error: Request rejected (429) · Rate limit exceeded: free-models-per-min.", null, at + 12_000, Resume(Resume.ARMED, at + 12_000, byClaude = false, canCancel = true, canArm = false, why = null)))
            }
            TOUR_OR_CREDITS -> {
                endLimits()
                account = Account.Ok
                val text = "API Error: 402 Insufficient credits. Add more using https://openrouter.ai/settings/credits"
                setOpenRouter(ProviderStatus(ProviderStatus.NO_CREDIT, text, t), DEMO_BALANCE.copy(remaining = 0.0, checkedAt = t))
                for (id in listOf(OR_WORK, OR_IDLE)) block(id, Blocked(Blocked.PROVIDER, text))
            }
            // OpenRouter turns the key down: a card on Home says so, and Fix opens its settings.
            TOUR_OR_KEY -> {
                endLimits()
                account = Account.Ok
                val text = "API Error: 401 No auth credentials found · the key was refused"
                setOpenRouter(ProviderStatus(ProviderStatus.BAD_KEY, text, t), DEMO_BALANCE.copy(error = "bad_key", checkedAt = t))
                for (id in listOf(OR_WORK, OR_IDLE)) block(id, Blocked(Blocked.PROVIDER, text))
            }
            // A calm evening: many projects, one session at work (the calm sky's everyday case)…
            TOUR_ONE_LIVE -> {
                endLimits()
                account = Account.Ok
                calm(keep = HIT)
                scene = Scene.OneLive
                update(HIT) { it.copy(state = SessionState.Working, activity = "$ ./gradlew :app:testDebugUnitTest", updatedAt = t) }
            }
            // …and nothing at work at all: nothing in the sky moves.
            TOUR_QUIET -> {
                endLimits()
                account = Account.Ok
                calm(keep = null)
                scene = Scene.Quiet
            }
            // Codex next to Claude Code: a second star, its sessions round it; then its window spent.
            TOUR_CODEX, TOUR_CODEX_LIMITED -> {
                endLimits()
                account = Account.Ok
            }
        }
        // Signed out or stopped, the numbers stand still: the usage card says it isn't measuring.
        usage = usage.copy(stale = account.eclipsed)
    }

    /** Claude Code's way of writing a reset: "3:40pm", or "Fri 10am" further off. */
    private fun clockOf(at: Long): String {
        val far = at - now > 20 * HOUR
        val f = java.text.SimpleDateFormat(if (far) "EEE h:mma" else "h:mma", java.util.Locale.US).format(java.util.Date(at)).lowercase()
        return f.replace(":00", "").replaceFirstChar { it.uppercase() }.let { if (far) it else it.lowercase() }
    }

    /**
     * Every session but [keep] stops working and stops waiting for an answer (agents finish, the
     * build loop pauses), as on an evening when nothing runs: the calm sky's scenes in the tour.
     */
    private fun calm(keep: String?) {
        // Every job stops, the one kept included: a build running now would settle it to idle when done.
        for (id in jobs.keys.toList()) jobs.remove(id)?.toList()?.forEach { it.cancel() }
        for (a in board.agents.values.filter { it.running }) changeAgent(a.id) { it.copy(status = "done", activity = null, endedAt = now, lastActivityAt = now) }
        showAgents()
        sessions = sessions.map { s ->
            if (s.id == keep || s.phase == Phase.Ended || (s.state == SessionState.Idle && s.agentsRunning == 0)) s
            else s.copy(state = SessionState.Idle, phase = Phase.Idle, agentsRunning = 0, activity = res.getString(R.string.activity_waiting))
        }
    }

    /** [a] stops the demo's busy session and any that work: idle, with the card and the notice. */
    private fun stopOn(a: Account, kind: String, resetsAt: Long? = null, auto: Boolean = false) {
        account = a
        // Claude Code carries on by itself in its terminal, a minute after the reset; Don't stops it.
        val resume = if (auto && resetsAt != null) Resume(Resume.ARMED, resetsAt + MIN, byClaude = true, canCancel = true, canArm = false, why = null) else null
        val b = Blocked(kind, a.text.orEmpty(), resetsAt, resume?.at, resume)
        for (id in (sessions.filter { it.state == SessionState.Working && it.id != AGENTS }.map { it.id } + HIT).distinct()) block(id, b)
    }

    private fun block(id: String, b: Blocked) {
        jobs.remove(id)?.toList()?.forEach { it.cancel() }
        val s = slot(id)
        // A limit stops the next request to Claude, not a command that runs: the build finishes,
        // and Claude can't answer it.
        s.value = s.value.map { if (it is Item.Terminal && it.running) it.copy(running = false, exitCode = 0, lines = it.lines + "BUILD SUCCESSFUL in 38s") else it } +
            Item.Notice(key("n"), b.text, if (b.needsYou) Tone.Error else Tone.Warn, blocker = b.kind)
        update(id) { it.copy(state = SessionState.Idle, blocked = b, updatedAt = now) }
        val p = providers.firstOrNull { it.id == session(id)?.providerId && !it.builtIn }
        update(id) { it.copy(activity = AccountWords.activity(context, b, account, p?.name, p = p)) }
    }

    /** OpenRouter's state in the demo: its trouble, and what's left on its key. */
    private fun setOpenRouter(status: ProviderStatus?, balance: Balance) {
        providers = providers.map { if (it.id == "openrouter") it.copy(status = status, balance = balance) else it }
    }

    // Carrying on after a limit (demo): the switch flips, Continue carries on, a check reads what's left.
    override suspend fun autoContinue(sessionId: String, on: Boolean): Result<Unit> {
        delay(600)
        update(sessionId) { s ->
            val b = s.blocked ?: return@update s
            val r = b.resume ?: return@update s
            val next = if (on) r.copy(state = Resume.ARMED, at = (b.resetsAt ?: r.at ?: now) + MIN, canCancel = true, canArm = false, why = null)
            else r.copy(state = Resume.OFF, at = b.resetsAt ?: r.at, canCancel = false, canArm = true, why = "cancelled")
            s.copy(blocked = b.copy(resume = next, autoResumeAt = next.at.takeIf { on }))
        }
        return Result.success(Unit)
    }

    override suspend fun continueAfterLimit(sessionId: String): Result<String> {
        delay(400)
        update(sessionId) { it.copy(blocked = null, activity = res.getString(R.string.activity_waiting), updatedAt = now) }
        val s = slot(sessionId)
        s.value = s.value + Item.Notice(key("n"), "Continued after the limit", Tone.Info)
        return Result.success(sessionId)
    }

    override suspend fun checkProvider(id: String): Result<Provider> =
        providers.firstOrNull { it.id == id }?.let { Result.success(it) } ?: Result.failure(demoOnly())

    /** Every block lifts (the next state, signed in, a session moved on). */
    private fun unblockAll() {
        sessions = sessions.map { if (it.blocked != null) it.copy(blocked = null, activity = res.getString(R.string.activity_waiting), updatedAt = now) else it }
    }

    /** Leaving a limit: its reset comes now, so the disk ignites (after the week, the stream returns first) as it would. */
    private fun endLimits() {
        val u = usage
        if (u.anyLimited) usage = u.copy(
            fiveHourResetAt = if (u.limited) now else u.fiveHourResetAt,
            sevenDayResetAt = if (u.weekLimited) now else u.sevenDayResetAt,
        )
        usage = usage.copy(stale = false)
    }

    /** A limit reset on its own: the account is fine again. */
    private fun limitOver() {
        if (account.state != Account.LIMITED || account.limit?.window == "model") return
        account = Account.Ok
        unblockAll()
    }

    override suspend fun checkAccount(): Result<Unit> {
        delay(700)
        return Result.success(Unit)
    }

    override suspend fun signIn(onMac: Boolean): Result<Unit> {
        if (!account.needsYou) return Result.success(Unit)
        account = account.copy(login = Account.Login("starting", null, onMac, null))
        scope.launch {
            delay(900)
            if (account.login == null) return@launch
            if (onMac) {
                // The Mac's terminal does the rest: the demo signs in there after a few seconds.
                account = account.copy(login = Account.Login("waiting_code", null, true, null))
                delay(5000)
                if (account.login?.state == "waiting_code") signedIn()
            } else {
                account = account.copy(login = Account.Login("waiting_code", DEMO_SIGN_IN, false, null))
            }
        }
        return Result.success(Unit)
    }

    override suspend fun sendSignInCode(code: String): Result<Unit> {
        val l = account.login ?: return Result.failure(HubException(res.getString(R.string.signin_gone)))
        account = account.copy(login = l.copy(state = "finishing"))
        scope.launch {
            delay(1400)
            if (account.login?.state != "finishing") return@launch
            // As on a Mac: a code it turns down leaves the sign-in waiting for the right one.
            if (code.trim().length >= 6) signedIn()
            else account = account.copy(login = l.copy(state = "waiting_code", error = res.getString(R.string.signin_code_refused)))
        }
        return Result.success(Unit)
    }

    override suspend fun cancelSignIn(): Result<Unit> {
        account = account.copy(login = null)
        return Result.success(Unit)
    }

    private fun signedIn() {
        account = Account.Ok
        unblockAll()
        usage = usage.copy(stale = false)
    }

    override suspend fun switchProvider(sessionId: String, providerId: String): Result<String> {
        val p = providers.firstOrNull { it.id == providerId } ?: return Result.failure(HubException(res.getString(R.string.err_switch_provider)))
        delay(500)
        update(sessionId) {
            it.copy(
                providerId = p.id, blocked = null, model = p.models.default ?: it.model,
                activity = res.getString(R.string.activity_waiting), updatedAt = now,
            )
        }
        val s = slot(sessionId)
        s.value = s.value + Item.Notice(key("n"), res.getString(R.string.demo_moved_to, p.name), Tone.Info)
        return Result.success(sessionId)
    }

    // ─── Agents (demo) ──────────────────────────────────────────────────────
    // "agent cards": its main agent is idle while two agents it started work (AGENTS · 2 running).
    // A background Explore agent checks the build; a two-phase workflow surveyed the code and now
    // fixes it. Opening the session plays the rest: the fix agent asks to run the tests (a row
    // asking, a NEEDS YOU band naming it), the Explore agent finishes with a three-line report, and
    // once the owner answers and both are done, the main agent reports back.

    private var board by mutableStateOf(AgentBoard.Empty)
    private var agentAsk by mutableStateOf<Item.Approval?>(null)
    private var agentsPlayed = false
    private var agentsReported = false
    private val agentsPrompt = Item.User(key("u"), "Agents should show on the phone. Check the build, then survey the agent code and fix what's missing.")
    private val agentsReply = Item.Assistant(key("a"), "I've started an **Explore** agent on the build, and a workflow that surveys the agent code and then fixes it. I'll report back when they're done.")
    private var agentsTail = emptyList<Item>()

    // After the properties above (an init block runs where it stands).
    init {
        showAgents()
    }

    private fun seedBoard(): AgentBoard {
        val t = now
        fun agent(id: String, label: String, type: String, status: String, startedAgo: Long, endedAgo: Long? = null, activity: String? = null, tokens: Long? = null, tools: Int? = null, result: String? = null, phase: String? = null) = WAgent(
            id = id, sessionId = AGENTS, toolUseId = if (phase == null) "toolu-$id" else null, parentAgentId = null, workflowId = if (phase == null) null else RUN,
            phase = phase, label = label, type = type, model = "opus", background = true, status = status, activity = activity,
            startedAt = t - startedAgo, endedAt = endedAgo?.let { t - it }, lastActivityAt = t - (endedAgo ?: 0), tokens = tokens, toolUses = tools, result = result,
        )
        val agents = listOf(
            agent(EXPLORE, "Check the Android build", "Explore", "running", 95_000, activity = "Bash: ./gradlew build", tokens = 18_400, tools = 6),
            agent("a-survey-daemon", "survey:daemon", "workflow-subagent", "done", 170_000, 120_000, tokens = 41_000, tools = 22, result = "Workflows end only on their notification.", phase = "Survey"),
            agent("a-survey-phone", "survey:phone", "workflow-subagent", "done", 170_000, 112_000, tokens = 37_500, tools = 18, result = "The phone draws what the daemon sends.", phase = "Survey"),
            agent("a-survey-mac", "survey:mac", "workflow-subagent", "done", 170_000, 125_000, tokens = 29_800, tools = 12, result = "The menu bar ignores agents.", phase = "Survey"),
            agent("a-implement-phone", "implement:phone", "workflow-subagent", "done", 105_000, 30_000, tokens = 52_000, tools = 31, result = "Home cards show running agents.", phase = "Implement"),
            agent(FIX, "implement:fix", "workflow-subagent", "running", 105_000, activity = "Edit: daemon/src/agents.ts", tokens = 61_300, tools = 27, phase = "Implement"),
        )
        val wf = WWorkflow(
            id = RUN, sessionId = AGENTS, toolUseId = "toolu-$RUN", name = "agent-cards", summary = "Survey how agents are shown, then fix the gaps",
            status = "running", phases = listOf(WPhase("Survey", "Read the daemon, phone and Mac code"), WPhase("Implement", "Fix what the survey found")),
            agentCount = 5, done = 4, failed = 0, startedAt = t - 172_000, endedAt = null, tokens = null, toolUses = null,
        )
        return AgentBoard(agents.associateBy { it.id }, mapOf(RUN to wf))
    }

    private fun changeAgent(id: String, f: (WAgent) -> WAgent) {
        board.agents[id]?.let { board = board.with(f(it)) }
    }

    /** The session's chat and status from the board: its cards, the band while an agent asks, and the main agent's report. */
    private fun showAgents() {
        if (board.isEmpty()) board = seedBoard()
        val b = board
        val asking = if (agentAsk?.resolved == null && agentAsk != null) setOf(FIX) else emptySet()
        slot(AGENTS).value = listOfNotNull(
            agentsPrompt,
            agentsReply,
            b.agents[EXPLORE]?.let { Item.AgentCard("card-$EXPLORE", it.view()) },
            b.workflows[RUN]?.let { Item.WorkflowCard("card-$RUN", b.workflowView(it, asking)) },
            agentAsk,
        ) + agentsTail
        val running = b.agents.values.count { it.running }
        update(AGENTS) {
            when {
                asking.isNotEmpty() -> it.copy(state = SessionState.NeedsYou, phase = Phase.NeedsYou, activity = "Bash: npm test --prefix daemon", agentsRunning = running)
                it.state == SessionState.Working -> it.copy(agentsRunning = running)
                // The main agent's last words, as one plain line (the card draws it in mono, marks and all).
                else -> it.copy(state = SessionState.Idle, phase = Phase.Idle, activity = ((agentsTail.lastOrNull() as? Item.Assistant) ?: agentsReply).text.replace("**", ""), agentsRunning = running)
            }
        }
    }

    /** The start of the hypernova session, "further back" until Show earlier brings it. */
    private var earlierHypernova by mutableStateOf(true)
    private var loadingEarlierNow by mutableStateOf(false)
    override fun hasEarlier(id: String): Boolean = id == "s-hypernova" && earlierHypernova
    override fun loadingEarlier(id: String): Boolean = id == "s-hypernova" && loadingEarlierNow
    override fun loadEarlier(id: String) {
        if (!hasEarlier(id) || loadingEarlierNow) return
        loadingEarlierNow = true
        scope.launch {
            delay(600)
            slot(id).value = listOf(
                Item.User(key("u"), "Start the Android app: a home screen with the session cards, in the Hyper cosmos look."),
                Item.Tool(key("t"), "Read", "docs/DESIGN.md"),
                Item.Assistant(key("a"), "Set up the Compose project with the design tokens from 0stems: warm near-black, one mint accent, Manrope and JetBrains Mono. The home screen lists sessions as glass cards."),
            ) + slot(id).value
            earlierHypernova = false
            loadingEarlierNow = false
        }
    }

    override fun opened(id: String) {
        if (id != AGENTS || agentsPlayed) return
        agentsPlayed = true
        // Background work: Esc in the session doesn't stop it.
        scope.launch {
            delay(2500)
            changeAgent(FIX) { it.copy(activity = "Bash: npm test --prefix daemon", lastActivityAt = now) }
            agentAsk = Item.Approval(AGENT_ASK, "Bash", "npm test --prefix daemon", "Run the daemon's tests after the fix", canAlways = false, agent = "implement:fix")
            showAgents()
            delay(4000)
            changeAgent(EXPLORE) {
                it.copy(
                    status = "done", activity = null, endedAt = now, lastActivityAt = now, tokens = 23_900, toolUses = 9,
                    result = "The build is green in 41s.\nTwo warnings, both in Cosmos.kt (an unused alpha).\nNo lint errors; the APK is in android/dist.",
                )
            }
            showAgents()
            reportWhenDone()
        }
    }

    /** The fix agent's question, answered here or on its own screen: it runs the tests, or stops. */
    private fun answerAgent(allowed: Boolean) {
        val ask = agentAsk ?: return
        if (ask.resolved != null) return
        agentAsk = ask.copy(resolved = if (allowed) Resolution.Allowed else Resolution.Denied)
        if (!allowed) {
            changeAgent(FIX) { it.copy(status = "stopped", activity = null, endedAt = now, lastActivityAt = now) }
            board.workflows[RUN]?.let { board = board.with(it.copy(status = "stopped", endedAt = now)) }
            showAgents()
            reportWhenDone()
            return
        }
        showAgents()
        scope.launch {
            delay(3500)
            changeAgent(FIX) {
                it.copy(status = "done", activity = null, endedAt = now, lastActivityAt = now, tokens = 74_200, toolUses = 34, result = "A workflow whose session died now ends as stopped.\n6 new tests pass.")
            }
            board.workflows[RUN]?.let { board = board.with(it.copy(status = "done", endedAt = now, done = 5, tokens = 248_000)) }
            showAgents()
            reportWhenDone()
        }
    }

    /** Once no agent works any more, the last one's report starts a turn of the main agent's: it sums up. */
    private fun reportWhenDone() {
        if (agentsReported || board.agents.values.any { it.running }) return
        agentsReported = true
        scope.launch {
            update(AGENTS) { it.copy(state = SessionState.Working, phase = Phase.Working, activity = "Thinking", updatedAt = now) }
            delay(1800)
            val fixed = board.agents[FIX]?.status == "done"
            agentsTail = listOf(
                Item.Assistant(
                    key("a"),
                    if (fixed) "Both are done. The build is green, and a workflow whose session died now ends as **stopped**, with 6 new tests passing."
                    else "The build is green. The fix agent stopped before running its tests, so the workflow is unfinished: say if it should try again.",
                ),
            )
            update(AGENTS) { it.copy(state = SessionState.Idle, phase = Phase.Idle, updatedAt = now) }
            showAgents()
        }
    }

    override fun agents(sessionId: String): AgentBoard = if (sessionId == AGENTS) board else AgentBoard.Empty

    // An agent's own transcript is drawn from [agentTranscript] here: the demo has no events to fetch.
    override suspend fun agentEvents(sessionId: String, agentId: String): Result<List<WEvent>> =
        if (sessionId == AGENTS && agentId in board.agents) Result.success(emptyList()) else super.agentEvents(sessionId, agentId)

    override fun agentTranscript(sessionId: String, agentId: String, events: List<WEvent>): List<Item> {
        val a = board.agents[agentId] ?: return emptyList()
        val report = a.result?.let { Item.Assistant("r-$agentId", it) }
        return when (agentId) {
            EXPLORE -> listOfNotNull(
                Item.User("u-$agentId", "Run the Android build and say whether it's green: warnings, lint, where the APK lands. Don't change anything."),
                Item.Tool("t1-$agentId", "Read", "android/app/build.gradle.kts"),
                Item.Terminal("b-$agentId", "./gradlew build", listOf("> Task :app:compileDebugKotlin", "w: Cosmos.kt:212 'alpha' is never used", "> Task :app:lintDebug", "BUILD SUCCESSFUL in 41s").let { if (a.running) it.take(2) else it }, a.running, if (a.running) null else 0, if (a.running) 52_000 else 41_000),
                report,
            )
            FIX -> listOfNotNull(
                Item.User("u-$agentId", "The survey found that a workflow whose session died stays running. Make it end as stopped, with tests."),
                Item.Tool("t1-$agentId", "Read", "daemon/src/agents.ts"),
                Item.Diff(
                    "d-$agentId", "daemon/src/agents.ts", 3, 0,
                    listOf(
                        DiffLine(' ', "export function endSessionAgents(sessionId: string): void {"),
                        DiffLine('+', "  for (const w of b.workflows.values())"),
                        DiffLine('+', "    if (w.status === 'running') endWorkflow(b, w, 'stopped', Date.now())"),
                        DiffLine('+', "  publishRunning(b)"),
                    ),
                ),
                agentAsk,
                report,
            )
            else -> listOfNotNull(Item.User("u-$agentId", "${a.label}: read the code and say what's missing."), report)
        }
    }

    // ─── Seed data ──────────────────────────────────────────────────────────

    private fun seedSessions(): List<Session> = (listOf(
        Session("a3", "s-hypernova", "cosmos renderer", "hypernova", SessionState.Working, "opus", "acceptEdits",
            "$ ./gradlew :app:assembleDebug", now - 20_000, queued = 1),
        Session("b8", "s-haze", "haze edges", "hypernova", SessionState.Idle, "sonnet", "acceptEdits",
            "waiting for your next prompt", now - 4 * 60_000),
        Session("c7", "s-0stems", "release landing copy", "0stems", SessionState.NeedsYou, "opus", "default",
            "wants to run git push origin main", now - 65_000),
        Session("1f", "s-site", "work strip card", "prudnikov-site", SessionState.Idle, "sonnet", "acceptEdits",
            "waiting for your next prompt", now - 6 * 60_000),
        Session("9b", "s-scribe", "lens flicker", "Hyperscribe", SessionState.Idle, "opus", "plan",
            "waiting for your next prompt", now - 42 * 60_000),
        // The demo's terminal session that wasn't started with bypass allowed: Bypass shows dimmed.
        Session("e2", "s-telegram", "usage in pin", "claude-telegram", SessionState.Idle, "sonnet", "default",
            "waiting for your next prompt", now - 3 * 3600_000, bypassAvailable = false),
        // Its main agent is idle; agents it started work on (see "Agents (demo)").
        Session("d4", AGENTS, "agent cards", "hypernova", SessionState.Idle, "opus", "acceptEdits",
            "", now - 2 * 60_000, agentsRunning = 2),
        // Two on OpenRouter: ringed moons of the companion star, which shines on while the sun is dead.
        Session("5e", OR_WORK, "port the widget", "0stems", SessionState.Working, "openai/gpt-5", "acceptEdits",
            "Edit: widget/Widget.tsx", now - 30_000, providerId = "openrouter"),
        Session("7a", OR_IDLE, "translate the docs", "prudnikov-site", SessionState.Idle, "openai/gpt-5", "default",
            "waiting for your next prompt", now - 9 * 60_000, providerId = "openrouter"),
        // Projects left alone for a day or more: quiet, so the sky's belt rather than planets.
        Session("4b", "s-thesis", "sources chapter", "thesis-notes", SessionState.Idle, "opus", "default",
            "waiting for your next prompt", now - 2 * DAY - 3 * HOUR),
        Session("2c", "s-recipes", "parse the menus", "recipes-bot", SessionState.Idle, "sonnet", "acceptEdits",
            "waiting for your next prompt", now - 3 * DAY),
        Session("8e", "s-audit", "contrast pass", "accessibility-audit-checklist", SessionState.Idle, "sonnet", "default",
            "waiting for your next prompt", now - 5 * DAY),
        Session("f0", "s-dotfiles", "zsh prompt", "dotfiles", SessionState.Idle, "sonnet", "acceptEdits",
            "waiting for your next prompt", now - 6 * DAY),
        Session("31", "s-homelab", "backup cron", "home-server", SessionState.Idle, "opus", "default",
            "waiting for your next prompt", now - 9 * DAY),
    ) + autoSessions()).map { s ->
        // How long each has run, so the planets in the sky differ in size.
        val ran = mapOf("a3" to 190, "b8" to 50, "c7" to 95, "1f" to 18, "9b" to 360, "e2" to 9, "d4" to 75, "5e" to 40, "7a" to 25, "4b" to 120, "2c" to 35)[s.hex] ?: 30
        s.copy(createdAt = s.updatedAt - ran * 60_000L)
    }

    /**
     * Sessions the demo's automations started (data/AutomationsDemo.kt): the tests asking to run, the
     * pull request watch at work, and this morning's brief, ended, which History shows under its automation.
     */
    private fun autoSessions(): List<Session> {
        val zone = java.time.ZoneId.systemDefault()
        // "Tests when plugged in, Sun 18 Oct", as the Mac names a run's session (never "·").
        fun title(name: String, at: Long) = name + ", " + java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()).format(java.time.Instant.ofEpochMilli(at).atZone(zone))
        val st = automations.state
        fun run(id: String) = st?.byId(id)?.lastRun
        val tests = run(DemoAutomations.PLUGGED)
        val prs = run(DemoAutomations.PRS)
        val brief = st?.byId(DemoAutomations.BRIEF)
        val out = mutableListOf<Session>()
        if (tests != null) out += Session(
            "a9", DemoAutomations.SESSION_TESTS, title(res.getString(R.string.au_t_plugged), tests.firedAt), "0stems", SessionState.NeedsYou, "sonnet", "default",
            "Bash: npm test", now - 60_000, automation = SessionAutomation(DemoAutomations.PLUGGED, res.getString(R.string.au_t_plugged), tests.id),
        )
        if (prs != null) out += Session(
            "6c", DemoAutomations.SESSION_PRS, title(res.getString(R.string.au_t_prs), prs.firedAt), "hypernova", SessionState.Working, "sonnet", "dontAsk",
            "$ gh pr view 212 --comments", now - 15_000, automation = SessionAutomation(DemoAutomations.PRS, res.getString(R.string.au_t_prs), prs.id),
        )
        // The last brief, ended: History shows the automation's runs as one row.
        val r = brief?.lastRun
        val end = r?.endedAt
        if (brief != null && r != null && end != null) out += Session(
            "3d", DemoAutomations.SESSION_BRIEF, title(brief.name, r.firedAt), "hypernova", SessionState.Idle, "opus", "dontAsk",
            r.result.orEmpty(), end, phase = Phase.Ended, automation = SessionAutomation(brief.id, brief.name, r.id),
        )
        return out
    }

    private fun hypernovaTranscript() = listOf(
        Item.User(key("u"), "Port DigitalCosmos from 0stems to Compose. Session stars should be tappable."),
        Item.Tool(key("t"), "Read", "~/Programming/0stems/components/DigitalCosmos.tsx"),
        Item.Tool(key("t"), "Read", "~/Programming/0stems/components/GrainField.tsx"),
        Item.Assistant(key("a"), "Ported. Ambient stars keep the 0stems tiers (7% anchors, 28% mid). **Sessions are the anchors now**: projects become clusters, and a star waiting on you turns amber with a bracket."),
        Item.Diff(
            key("d"), "ui/fx/Cosmos.kt", 14, 3,
            listOf(
                DiffLine(' ', "for (s in sessions) {"),
                DiffLine('-', "  drawCircle(HN.star, s.size, s.pos)"),
                DiffLine('+', "  val tint = if (s.waiting) HN.amber else HN.star"),
                DiffLine('+', "  halo(tint, s.size, s.pos, alpha)"),
                DiffLine('+', "  if (s.waiting) bracket(s.pos, focus)"),
                DiffLine(' ', "}"),
            ),
        ),
        Item.User(key("u"), "Build it."),
        // Typed while the build runs: Claude reads it when that step finishes.
        Item.User(key("u"), "Then run the unit tests too.", status = SendStatus.QueuedStep),
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val MIN = 60_000L
        const val DAY = 24 * HOUR
        /** The demo's spend paces (%/min): busy, the "one at work" scene, and the week's. */
        const val BUSY_PACE = 0.35f
        const val SLOW_PACE = 0.12f
        const val WEEK_PACE = 0.006f
        /** The tour's limits, beside the account's own states. */
        const val TOUR_MODEL = "tour:model"
        const val TOUR_SESSION = "tour:session"
        const val TOUR_WEEK = "tour:week"
        /** OpenRouter's own trouble: rate limited (it carries on by itself), out of credits. */
        const val TOUR_OR_RATE = "tour:or-rate"
        const val TOUR_OR_CREDITS = "tour:or-credits"
        const val TOUR_OR_KEY = "tour:or-key"
        /** The calm sky's scenes: one session at work among many quiet projects, then none. */
        const val TOUR_ONE_LIVE = "tour:one-live"
        const val TOUR_QUIET = "tour:quiet"
        /** Codex in use next to Claude Code, then its window spent. */
        const val TOUR_CODEX = "tour:codex"
        const val TOUR_CODEX_LIMITED = "tour:codex-limited"
        /** The demo's Codex sessions: one at work, one idle. */
        const val CX_WORK = "s-cx-notes"
        const val CX_IDLE = "s-cx-bridge"
        /** The sessions on OpenRouter: one at work, one idle. */
        const val OR_WORK = "s-or-widget"
        const val OR_IDLE = "s-or-docs"
        /** What's left on the demo's OpenRouter key. */
        val DEMO_BALANCE = Balance(4.2, 0.31, 37, 50, System.currentTimeMillis(), null)
        /** The session that hits the account's trouble, and the one on Opus. */
        const val HIT = "s-hypernova"
        const val OPUS = "s-scribe"
        /** Where the demo's sign-in page would be: Claude's own sign-in. */
        const val DEMO_SIGN_IN = "https://claude.ai/login"
        const val AGENTS = "s-agents"
        const val EXPLORE = "a-explore-build"
        const val FIX = "a-implement-fix"
        const val RUN = "wf-agent-cards"
        const val AGENT_ASK = "ap-implement-fix"
    }

    private fun ostemsTranscript() = listOf(
        Item.User(key("u"), "Tighten the hero copy and push when the build is green."),
        Item.Tool(key("t"), "Read", "app/page.tsx"),
        Item.Diff(
            key("d"), "app/page.tsx", 2, 2,
            listOf(
                DiffLine('-', "Split royalties without trusting anyone."),
                DiffLine('+', "Royalties that split themselves."),
                DiffLine('-', "Every stem, every payout, on-chain."),
                DiffLine('+', "Every stem paid, every time."),
            ),
        ),
        Item.Terminal(key("term"), "npm run build", listOf("▲ Next.js 15.5", "Creating an optimized production build ...", "✓ Compiled successfully in 8.2s", "✓ Generating static pages (14/14)"), false, 0, 11_400),
        Item.Approval(key("ap"), "Bash", "git push origin main", "Push the hero copy change to main"),
    )
}
