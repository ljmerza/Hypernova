package com.prudnikweb.hypernova.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Automations on the paired Mac (docs/PROTOCOL.md, "Automations"): the snapshot's list kept up to
 * date by the socket ([LiveHub] hands its messages here), the REST calls, and the notes the Mac's
 * automations send, as a banner while the app is open and a notification while it isn't.
 */
class LiveAutomations(
    context: Context,
    private val api: Api,
    private val isVisible: () -> Boolean,
    /** A banner in the open app. */
    private val banner: (Notice) -> Unit,
    /** LiveHub's own handling of a failure that isn't the daemon refusing (offline, unpaired). */
    private val fail: (Throwable, Int) -> HubException,
    /** A session the phone knows, to open a note's run from it. */
    private val sessionOf: (String) -> Session?,
    /** The paired computer these are on (data/Servers.kt): its notifications open and answer there. */
    server: String? = null,
) : AutomationBook(), Automations {
    private val res = context.resources
    private val notes = AutomationNotes(context.applicationContext, server)

    override val state: AutomationsState? get() = current
    override fun runs(id: String): RunPage? = page(id)

    override fun applySnapshot(s: AutomationsState?) {
        super.applySnapshot(s)
        // A phone that was off (or out of reach) finds what its automations said meanwhile.
        if (s != null) catchUp(s.notes)
    }

    override fun apply(m: AutoMsg) {
        super.apply(m)
        when (m) {
            is AutoMsg.Note -> arrived(m.note)
            // Answered (here, on the Mac) or taken as its default: its notification goes.
            is AutoMsg.RunChanged -> if (m.run.ask == null) notes.cancelAsk(m.run.id)
            else -> Unit
        }
    }

    private suspend fun <T> call(fallback: Int, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(error(e, fallback))
    }

    /**
     * The daemon's refusal in the app's own words where it has them ("Pick a time in the
     * future."), its code kept so the editor can put it under the right block; Run now's 409s
     * keep the Mac's own reason. Anything else (offline, unpaired) as LiveHub says it.
     */
    private fun error(e: Throwable, fallback: Int): HubException {
        val ex = Net.classify(e)
        if (ex.problem != Problem.Server) return fail(e, fallback)
        val own = ex.code?.let { AutomationErrors.text(res, it) }
        val until = ex.error?.long("until")?.takeIf { it > 0 }
        // A workflow's refusals name the field ("steps[2].command"), and say it best themselves
        // ("bad_cron": what's wrong with the expression).
        val field = ex.error?.str("field")?.takeIf { it.isNotBlank() }
        val run = ex.error?.obj("run")?.let(AutomationWire::run)
        val said = ex.serverMessage?.takeIf { it.isNotBlank() }
        val text = if (field != null && ex.code in AutomationErrors.OWN_WORDS) said ?: own else own ?: said
        return AutomationError(ex.code, text ?: res.getString(fallback), until, field, run)
    }

    private fun changed(a: Automation) {
        val s = current ?: return
        current = s.copy(list = upsert(s.list, a))
    }

    override suspend fun loadRuns(id: String, more: Boolean): Result<Unit> {
        val p = page(id) ?: RunPage()
        if (p.loading) return Result.success(Unit)
        val before = if (more) p.runs.lastOrNull()?.firedAt else null
        setPage(id, p.copy(loading = true, error = null))
        return call(R.string.au_err_runs) {
            val o = api.get("/v1/automations/${Api.q(id)}/runs?limit=$PAGE" + (before?.let { "&before=$it" } ?: ""))
            val got = o.arr("runs").mapObjects(AutomationWire::run)
            val now = page(id) ?: RunPage()
            val runs = if (more) (now.runs + got).distinctBy { it.id } else got
            // The Mac's own counts of every run it keeps; an older Mac's answer has none.
            setPage(id, RunPage(runs, more = got.size >= PAGE, total = o.int("total"), skipped = o.int("skipped")))
        }.onFailure { setPage(id, (page(id) ?: RunPage()).copy(loading = false, error = it.message)) }
    }

    override suspend fun save(id: String?, input: AutomationInput): Result<Automation> = call(R.string.au_err_save) {
        val body = AutomationWire.json(input)
        val o = if (id == null) api.post("/v1/automations", body) else api.put("/v1/automations/${Api.q(id)}", body)
        val a = AutomationWire.automation(o.obj("automation") ?: o) ?: throw HubException(res.getString(R.string.au_err_save))
        // A webhook trigger's token, made by this save: shown once, kept only in memory.
        o.str("token")?.takeIf { it.isNotBlank() }?.let { keepToken(a.id, it) }
        changed(a)
        a
    }

    override suspend fun newToken(id: String): Result<Automation> = call(R.string.au_err_save) {
        val o = api.post("/v1/automations/${Api.q(id)}/token", JSONObject())
        val a = AutomationWire.automation(o.obj("automation") ?: o) ?: throw HubException(res.getString(R.string.au_err_save))
        o.str("token")?.takeIf { it.isNotBlank() }?.let { keepToken(a.id, it) }
        changed(a)
        a
    }

    override fun freshToken(id: String): String? = tokenOf(id)
    override fun forgetToken(id: String) = dropToken(id)

    override suspend fun check(trigger: Trigger): Result<TriggerCheck> = call(R.string.au_err_save) {
        val o = api.post("/v1/automations/check", JSONObject().put("trigger", AutomationWire.json(trigger)))
        TriggerCheck(AutomationWire.trigger(o.obj("trigger")), o.arr("next").let { a -> (0 until (a?.length() ?: 0)).mapNotNull { a!!.optLong(it).takeIf { t -> t > 0 } } })
    }

    override suspend fun answer(runId: String, stepId: String, answer: String): Result<Run> {
        val r = call(R.string.au_err_answer) {
            val o = api.post("/v1/automations/runs/${Api.q(runId)}/answer", JSONObject().put("stepId", stepId).put("answer", answer))
            val r = AutomationWire.run(o.obj("run") ?: o) ?: throw HubException(res.getString(R.string.au_err_answer))
            apply(AutoMsg.RunChanged(r))
            r
        }
        // Someone else answered first (or its time ran out): the run as it is now, so the card goes.
        (r.exceptionOrNull() as? AutomationError)?.run?.let { apply(AutoMsg.RunChanged(it)) }
        return r
    }

    override suspend fun delete(id: String): Result<Unit> = call(R.string.au_err_delete) {
        api.delete("/v1/automations/${Api.q(id)}")
        apply(AutoMsg.Removed(id))
    }

    override suspend fun setEnabled(id: String, on: Boolean): Result<Automation> = call(R.string.au_err_save) {
        val o = api.post("/v1/automations/${Api.q(id)}/enabled", JSONObject().put("enabled", on))
        val a = AutomationWire.automation(o.obj("automation") ?: o) ?: throw HubException(res.getString(R.string.au_err_save))
        changed(a)
        a
    }

    override suspend fun runNow(id: String): Result<Run> = call(R.string.au_err_run) {
        val o = api.post("/v1/automations/${Api.q(id)}/run", JSONObject())
        val r = AutomationWire.run(o.obj("run") ?: o) ?: throw HubException(res.getString(R.string.au_err_run))
        apply(AutoMsg.RunChanged(r))
        r
    }

    override suspend fun stop(runId: String): Result<Run> = call(R.string.au_err_stop) {
        val o = api.post("/v1/automations/runs/${Api.q(runId)}/stop", JSONObject())
        val r = AutomationWire.run(o.obj("run") ?: o) ?: throw HubException(res.getString(R.string.au_err_stop))
        apply(AutoMsg.RunChanged(r))
        r
    }

    override suspend fun retry(runId: String, fromStep: String?): Result<Run> = call(R.string.au_err_run) {
        val o = api.post("/v1/automations/runs/${Api.q(runId)}/retry", JSONObject().apply { if (fromStep != null) put("fromStep", fromStep) })
        val r = AutomationWire.run(o.obj("run") ?: o) ?: throw HubException(res.getString(R.string.au_err_run))
        apply(AutoMsg.RunChanged(r))
        r
    }

    override suspend fun setPaused(paused: Boolean): Result<Unit> = call(R.string.au_err_pause) {
        val o = api.post("/v1/automations/paused", JSONObject().put("paused", paused))
        val now = if (o.has("paused")) o.bool("paused") else paused
        current?.let { current = it.copy(paused = now) }
    }

    // ─── Notes ─────────────────────────────────────────────────────────────

    private fun arrived(n: AutomationNote) {
        if (n.to != AutomationNote.PHONE || !notes.firstTime(listOf(n.id))) return
        // A question: answered from the notification's own buttons while the app is closed; open, the
        // attention card asks (and the banner says so).
        val ask = n.ask
        if (ask != null && n.runId != null && !isVisible()) {
            notes.postAsk(n.runId, n.automationId, n.name, ask)
            return
        }
        show(listOf(n))
    }

    /** Notes from the last 12 hours this phone hasn't shown: one as it is, several folded into one. */
    private fun catchUp(list: List<AutomationNote>) {
        val now = System.currentTimeMillis()
        val fresh = list.filter { it.to == AutomationNote.PHONE && now - it.at < AutomationNote.KEPT && !notes.shown(it.id) }
        if (fresh.isEmpty() || !notes.firstTime(fresh.map { it.id })) return
        // A question still waiting gets its own notification with the answers (one that was answered
        // meanwhile isn't asked again); the rest fold as before.
        val waiting = current?.asks.orEmpty().mapNotNull { it.ask?.let { a -> it.id to a } }.toMap()
        val (asks, rest) = fresh.partition { it.ask != null }
        if (!isVisible()) for (n in asks) {
            val a = n.runId?.let(waiting::get) ?: continue
            notes.postAsk(n.runId, n.automationId, n.name, a)
        }
        if (rest.isNotEmpty()) show(rest)
    }

    private fun show(list: List<AutomationNote>) {
        val one = list.singleOrNull()
        if (one != null) {
            val sid = sessionFor(one)
            if (isVisible()) banner(Notice("note:${one.id}", Notice.Kind.Automation, sid, one.name, one.text, automationId = one.automationId))
            else notes.post(one.id, one.name, one.text, sid, one.automationId)
            return
        }
        val title = res.getQuantityString(R.plurals.au_away_title, list.size, list.size)
        val text = AutomationNotes.names(res, list.map { it.name }.distinct())
        // A tap on the folded one opens the Automations tab (an empty automation id).
        if (isVisible()) banner(Notice("note:away", Notice.Kind.Automation, null, title, text, automationId = ""))
        else notes.post("away", title, text, null, "")
    }

    /** The session the note's run worked in, when the phone knows it: a tap opens it. */
    private fun sessionFor(n: AutomationNote): String? {
        val runId = n.runId ?: return null
        val aid = n.automationId ?: return null
        val run = current?.byId(aid)?.lastRun?.takeIf { it.id == runId } ?: page(aid)?.runs?.firstOrNull { it.id == runId }
        return run?.sessionIds?.firstOrNull { sessionOf(it) != null }
    }

    private companion object {
        const val PAGE = 30
    }
}

/** The daemon's refusals in the app's words. Null: say what the Mac said. */
object AutomationErrors {
    fun text(res: Resources, code: String): String? = when (code) {
        "bad_name" -> res.getString(R.string.au_err_name)
        "bad_trigger" -> res.getString(R.string.au_err_trigger)
        "bad_time" -> res.getString(R.string.au_err_time)
        "bad_days" -> res.getString(R.string.au_err_days)
        "past_time" -> res.getString(R.string.au_err_past)
        "bad_action" -> res.getString(R.string.au_err_action)
        "no_prompt" -> res.getString(R.string.au_err_prompt)
        "no_project" -> res.getString(R.string.au_err_project)
        "no_session" -> res.getString(R.string.au_err_session)
        "no_provider" -> res.getString(R.string.au_err_provider)
        "bad_mode" -> res.getString(R.string.au_err_mode)
        "confirm_bypass" -> res.getString(R.string.au_err_confirm)
        "bad_rule" -> res.getString(R.string.au_err_rule)
        "rule_too_broad" -> res.getString(R.string.au_err_rule_broad)
        "no_battery" -> res.getString(R.string.au_err_battery)
        "too_many" -> res.getString(R.string.au_err_too_many)
        "confirm_paid" -> res.getString(R.string.au_err_paid)
        "bad_template" -> res.getString(R.string.au_err_template)
        "no_automation" -> res.getString(R.string.au_err_gone)
        "no_run" -> res.getString(R.string.au_err_no_run)
        "running" -> res.getString(R.string.au_err_running)
        "outside_projects" -> res.getString(R.string.au_err_outside)
        "no_model" -> res.getString(R.string.au_err_no_model)
        "bad_placeholder" -> res.getString(R.string.au_err_placeholder)
        "bad_target" -> res.getString(R.string.au_err_target)
        "active" -> res.getString(R.string.au_err_active)
        // Workflows (docs/PROTOCOL.md, "Workflows", Errors).
        "bad_steps" -> res.getString(R.string.wf_err_steps)
        "bad_step" -> res.getString(R.string.wf_err_step)
        "bad_guard" -> res.getString(R.string.wf_err_guard)
        "bad_question" -> res.getString(R.string.wf_err_question)
        "bad_answers" -> res.getString(R.string.wf_err_answers)
        "bad_timeout" -> res.getString(R.string.wf_err_timeout)
        "no_command" -> res.getString(R.string.wf_err_command)
        "confirm_shell" -> res.getString(R.string.wf_err_confirm_shell)
        "bad_outputs" -> res.getString(R.string.wf_err_outputs)
        "bad_output" -> res.getString(R.string.wf_err_output)
        "bad_text" -> res.getString(R.string.wf_err_text)
        "bad_url" -> res.getString(R.string.wf_err_url)
        "insecure_url" -> res.getString(R.string.wf_err_insecure)
        "bad_bot_token" -> res.getString(R.string.wf_err_bot_token)
        "bad_chat" -> res.getString(R.string.wf_err_chat)
        "bad_path" -> res.getString(R.string.wf_err_path)
        "secret_file" -> res.getString(R.string.wf_err_secret_file)
        "bad_budget" -> res.getString(R.string.wf_err_budget)
        "bad_cron" -> res.getString(R.string.wf_err_cron)
        "too_often" -> res.getString(R.string.wf_err_too_often)
        "not_github" -> res.getString(R.string.wf_err_not_github)
        "no_gh" -> res.getString(R.string.wf_err_no_gh)
        "has_steps" -> res.getString(R.string.wf_err_has_steps)
        "steps_changed" -> res.getString(R.string.wf_err_steps_changed)
        "not_asking" -> res.getString(R.string.wf_err_not_asking)
        "bad_answer" -> res.getString(R.string.wf_err_bad_answer)
        "not_webhook" -> res.getString(R.string.wf_err_not_webhook)
        // Run now and Retry refused for the account or the provider: the run line's words.
        "daily_cap" -> res.getString(R.string.au_now_daily_cap)
        "limited" -> res.getString(R.string.au_now_limited_plain)
        "signed_out" -> res.getString(R.string.au_now_signed_out)
        "no_plan" -> res.getString(R.string.au_now_no_plan)
        "on_hold" -> res.getString(R.string.au_now_on_hold)
        "no_credit" -> res.getString(R.string.au_now_no_credit)
        else -> null
    }

    /**
     * Codes whose own message says it best when it names a field ("bad_cron": which part of the
     * expression is wrong); the app's words stand in when the Mac says nothing.
     */
    val OWN_WORDS = setOf("bad_cron", "bad_url", "bad_path", "bad_placeholder", "bad_guard", "bad_step", "bad_answers")

    /** Which editor block a refusal belongs under. */
    enum class Block { When, Do, Permissions, Name, Other }

    fun block(code: String?): Block = when (code) {
        "bad_name" -> Block.Name
        "bad_trigger", "bad_time", "bad_days", "past_time", "no_battery", "bad_cron", "too_often", "not_github", "no_gh" -> Block.When
        "bad_action", "no_prompt", "no_project", "no_session", "no_provider", "confirm_paid",
        "outside_projects", "no_model", "bad_placeholder", "bad_target" -> Block.Do
        "bad_mode", "confirm_bypass", "bad_rule", "rule_too_broad" -> Block.Permissions
        else -> Block.Other
    }
}

/**
 * Automations' notifications on the phone: their own channel ("What your automations found, and
 * runs that couldn't start"), a tap that opens the run's session or the automation, and the notes
 * already shown (the last 50 ids, remembered), so a reconnect or a restart never repeats one.
 */
class AutomationNotes(private val context: Context, private val server: String? = null) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.getSharedPreferences("hypernova", Context.MODE_PRIVATE)

    init {
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.au_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.au_channel_desc)
            },
        )
    }

    private fun seen(): List<String> = prefs.getString(SEEN, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    fun shown(id: String): Boolean = id in seen()

    /** Marks [ids] shown; false when they all were already. */
    fun firstTime(ids: List<String>): Boolean {
        val had = seen()
        val new = ids.filter { it !in had }
        if (new.isEmpty()) return false
        prefs.edit().putString(SEEN, (had + new).takeLast(50).joinToString(",")).apply()
        return true
    }

    private fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Automations on for this server (data/NotificationPrefs.kt); the note stays in the app's list either way. */
    private fun allowed(): Boolean = NotificationPrefsStore.get(context).allows(server, Notice.Kind.Automation)

    /** [automationId] "" opens the Automations tab (several folded into one). */
    fun post(key: String, title: String, text: String, sessionId: String?, automationId: String?) {
        if (!canPost() || nm == null || !allowed()) return
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when {
            sessionId != null -> i.setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SESSION, sessionId)
            automationId != null -> i.setAction(ACTION_OPEN).putExtra(EXTRA_ID, automationId)
        }
        if (server != null) i.putExtra(MainActivity.EXTRA_SERVER, server)
        val open = PendingIntent.getActivity(context, ("auto:${server.orEmpty()}:$key").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7FFFA0.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addExtras(Notifier.silenceable(server, Notice.Kind.Automation))
            .build()
        // One per automation: its next note takes the place of the last.
        runCatching { nm.notify(("automation:${scope()}${automationId ?: key}").hashCode(), n) }
    }

    /**
     * An ask step's question as a notification whose buttons are its answers (answered after the
     * phone is unlocked, data/NotifyActions.kt); a tap opens the automation. One per run, gone once
     * it's answered anywhere ([cancelAsk]).
     */
    fun postAsk(runId: String, automationId: String?, name: String, ask: RunAsk) {
        if (!canPost() || nm == null || !allowed()) return
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction(ACTION_OPEN).putExtra(EXTRA_ID, automationId.orEmpty())
        if (server != null) i.putExtra(MainActivity.EXTRA_SERVER, server)
        val open = PendingIntent.getActivity(context, ("ask:$runId").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val until = context.getString(R.string.wf_ask_notif_default, ask.default)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFE8B341.toInt())
            .setContentTitle(ask.question)
            .setContentText(name)
            .setSubText(until)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ask.question).setSummaryText(until))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addExtras(Notifier.silenceable(server, Notice.Kind.Automation))
            .setTimeoutAfter((ask.until - System.currentTimeMillis()).coerceAtLeast(60_000L))
        for ((k, answer) in ask.answers.take(3).withIndex()) {
            val act = Intent(context, NotifyActions::class.java).setAction(NotifyActions.ACTION_ANSWER)
                .putExtra(NotifyActions.EXTRA_RUN, runId).putExtra(NotifyActions.EXTRA_STEP, ask.stepId).putExtra(NotifyActions.EXTRA_ANSWER, answer)
            if (server != null) act.putExtra(NotifyActions.EXTRA_SERVER, server)
            val pi = PendingIntent.getBroadcast(context, ("ask:$runId:$k").hashCode(), act, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(NotificationCompat.Action.Builder(0, answer, pi).setAuthenticationRequired(true).build())
        }
        runCatching { nm.notify(askKey(runId), b.build()) }
    }

    fun cancelAsk(runId: String) {
        runCatching { nm?.cancel(askKey(runId)) }
    }

    private fun askKey(runId: String) = ("ask:$runId").hashCode()

    /** Another computer's automation (not the first one's) gets notifications of its own: "<id>:". */
    private fun scope(): String = server?.takeIf { it != Servers.LEGACY_ID }?.let { "$it:" }.orEmpty()

    companion object {
        const val CHANNEL = "automations"
        private const val SEEN = "automationNotesShown"
        /** A notification tap: open this automation ("" for the Automations tab). */
        const val ACTION_OPEN = "com.prudnikweb.hypernova.OPEN_AUTOMATION"
        const val EXTRA_ID = "automation"

        /**
         * Automations' notes go nowhere on this phone: its notifications are off for the app (or not
         * allowed yet), or the Automations channel is (A16).
         */
        fun blocked(context: Context): Boolean {
            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
            val ch = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)
            return ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE
        }

        /** The system's page for the Automations channel. */
        fun openChannel(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** "Morning brief", "Morning brief and PR watch", "A, B and C". */
        fun names(res: Resources, names: List<String>): String = when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> res.getString(R.string.au_and, names.dropLast(1).joinToString(", "), names.last())
        }
    }
}
