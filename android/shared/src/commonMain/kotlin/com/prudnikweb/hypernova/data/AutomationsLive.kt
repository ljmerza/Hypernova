package com.prudnikweb.hypernova.data


import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Automations on the paired Mac (docs/PROTOCOL.md, "Automations"): the snapshot's list kept up to
 * date by the socket ([LiveHub] hands its messages here), the REST calls, and the notes the Mac's
 * automations send, as a banner while the app is open and a notification while it isn't.
 */
class LiveAutomations(
    /** The app's words as this hub was made. */
    strings: Strings,
    private val notes: AutomationNotifier,
    private val api: Api,
    private val isVisible: () -> Boolean,
    /** A banner in the open app. */
    private val banner: (Notice) -> Unit,
    /** LiveHub's own handling of a failure that isn't the daemon refusing (offline, unpaired). */
    private val fail: (Throwable, Str) -> HubException,
    /** A session the phone knows, to open a note's run from it. */
    private val sessionOf: (String) -> Session?,
) : AutomationBook(), Automations {
    private val res = strings

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

    private suspend fun <T> call(fallback: Str, block: suspend () -> T): Result<T> = try {
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
    private fun error(e: Throwable, fallback: Str): HubException {
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
        return call(Str.au_err_runs) {
            val o = api.get("/v1/automations/${Api.q(id)}/runs?limit=$PAGE" + (before?.let { "&before=$it" } ?: ""))
            val got = o.arr("runs").mapObjects(AutomationWire::run)
            val now = page(id) ?: RunPage()
            val runs = if (more) (now.runs + got).distinctBy { it.id } else got
            // The Mac's own counts of every run it keeps; an older Mac's answer has none.
            setPage(id, RunPage(runs, more = got.size >= PAGE, total = o.int("total"), skipped = o.int("skipped")))
        }.onFailure { setPage(id, (page(id) ?: RunPage()).copy(loading = false, error = it.message)) }
    }

    override suspend fun save(id: String?, input: AutomationInput): Result<Automation> = call(Str.au_err_save) {
        val body = AutomationWire.json(input)
        val o = if (id == null) api.post("/v1/automations", body) else api.put("/v1/automations/${Api.q(id)}", body)
        val a = AutomationWire.automation(o.obj("automation") ?: o) ?: throw HubException(res.getString(Str.au_err_save))
        // A webhook trigger's token, made by this save: shown once, kept only in memory.
        o.str("token")?.takeIf { it.isNotBlank() }?.let { keepToken(a.id, it) }
        changed(a)
        a
    }

    override suspend fun newToken(id: String): Result<Automation> = call(Str.au_err_save) {
        val o = api.post("/v1/automations/${Api.q(id)}/token", JSONObject())
        val a = AutomationWire.automation(o.obj("automation") ?: o) ?: throw HubException(res.getString(Str.au_err_save))
        o.str("token")?.takeIf { it.isNotBlank() }?.let { keepToken(a.id, it) }
        changed(a)
        a
    }

    override fun freshToken(id: String): String? = tokenOf(id)
    override fun forgetToken(id: String) = dropToken(id)

    override suspend fun check(trigger: Trigger): Result<TriggerCheck> = call(Str.au_err_save) {
        val o = api.post("/v1/automations/check", JSONObject().put("trigger", AutomationWire.json(trigger)))
        TriggerCheck(AutomationWire.trigger(o.obj("trigger")), o.arr("next").let { a -> (0 until (a?.length() ?: 0)).mapNotNull { a!!.optLong(it).takeIf { t -> t > 0 } } })
    }

    override suspend fun answer(runId: String, stepId: String, answer: String): Result<Run> {
        val r = call(Str.au_err_answer) {
            val o = api.post("/v1/automations/runs/${Api.q(runId)}/answer", JSONObject().put("stepId", stepId).put("answer", answer))
            val r = AutomationWire.run(o.obj("run") ?: o) ?: throw HubException(res.getString(Str.au_err_answer))
            apply(AutoMsg.RunChanged(r))
            r
        }
        // Someone else answered first (or its time ran out): the run as it is now, so the card goes.
        (r.exceptionOrNull() as? AutomationError)?.run?.let { apply(AutoMsg.RunChanged(it)) }
        return r
    }

    override suspend fun delete(id: String): Result<Unit> = call(Str.au_err_delete) {
        api.delete("/v1/automations/${Api.q(id)}")
        apply(AutoMsg.Removed(id))
    }

    override suspend fun setEnabled(id: String, on: Boolean): Result<Automation> = call(Str.au_err_save) {
        val o = api.post("/v1/automations/${Api.q(id)}/enabled", JSONObject().put("enabled", on))
        val a = AutomationWire.automation(o.obj("automation") ?: o) ?: throw HubException(res.getString(Str.au_err_save))
        changed(a)
        a
    }

    override suspend fun runNow(id: String): Result<Run> = call(Str.au_err_run) {
        val o = api.post("/v1/automations/${Api.q(id)}/run", JSONObject())
        val r = AutomationWire.run(o.obj("run") ?: o) ?: throw HubException(res.getString(Str.au_err_run))
        apply(AutoMsg.RunChanged(r))
        r
    }

    override suspend fun stop(runId: String): Result<Run> = call(Str.au_err_stop) {
        val o = api.post("/v1/automations/runs/${Api.q(runId)}/stop", JSONObject())
        val r = AutomationWire.run(o.obj("run") ?: o) ?: throw HubException(res.getString(Str.au_err_stop))
        apply(AutoMsg.RunChanged(r))
        r
    }

    override suspend fun retry(runId: String, fromStep: String?): Result<Run> = call(Str.au_err_run) {
        val o = api.post("/v1/automations/runs/${Api.q(runId)}/retry", JSONObject().apply { if (fromStep != null) put("fromStep", fromStep) })
        val r = AutomationWire.run(o.obj("run") ?: o) ?: throw HubException(res.getString(Str.au_err_run))
        apply(AutoMsg.RunChanged(r))
        r
    }

    override suspend fun setPaused(paused: Boolean): Result<Unit> = call(Str.au_err_pause) {
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
        val title = res.getQuantityString(Plural.au_away_title, list.size, list.size)
        val text = automationNames(res, list.map { it.name }.distinct())
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
    fun text(res: Strings, code: String): String? = when (code) {
        "bad_name" -> res.getString(Str.au_err_name)
        "bad_trigger" -> res.getString(Str.au_err_trigger)
        "bad_time" -> res.getString(Str.au_err_time)
        "bad_days" -> res.getString(Str.au_err_days)
        "past_time" -> res.getString(Str.au_err_past)
        "bad_action" -> res.getString(Str.au_err_action)
        "no_prompt" -> res.getString(Str.au_err_prompt)
        "no_project" -> res.getString(Str.au_err_project)
        "no_session" -> res.getString(Str.au_err_session)
        "no_provider" -> res.getString(Str.au_err_provider)
        "bad_mode" -> res.getString(Str.au_err_mode)
        "confirm_bypass" -> res.getString(Str.au_err_confirm)
        "bad_rule" -> res.getString(Str.au_err_rule)
        "rule_too_broad" -> res.getString(Str.au_err_rule_broad)
        "no_battery" -> res.getString(Str.au_err_battery)
        "too_many" -> res.getString(Str.au_err_too_many)
        "confirm_paid" -> res.getString(Str.au_err_paid)
        "bad_template" -> res.getString(Str.au_err_template)
        "no_automation" -> res.getString(Str.au_err_gone)
        "no_run" -> res.getString(Str.au_err_no_run)
        "running" -> res.getString(Str.au_err_running)
        "outside_projects" -> res.getString(Str.au_err_outside)
        "no_model" -> res.getString(Str.au_err_no_model)
        "bad_placeholder" -> res.getString(Str.au_err_placeholder)
        "bad_target" -> res.getString(Str.au_err_target)
        "active" -> res.getString(Str.au_err_active)
        // Workflows (docs/PROTOCOL.md, "Workflows", Errors).
        "bad_steps" -> res.getString(Str.wf_err_steps)
        "bad_step" -> res.getString(Str.wf_err_step)
        "bad_guard" -> res.getString(Str.wf_err_guard)
        "bad_question" -> res.getString(Str.wf_err_question)
        "bad_answers" -> res.getString(Str.wf_err_answers)
        "bad_timeout" -> res.getString(Str.wf_err_timeout)
        "no_command" -> res.getString(Str.wf_err_command)
        "confirm_shell" -> res.getString(Str.wf_err_confirm_shell)
        "bad_outputs" -> res.getString(Str.wf_err_outputs)
        "bad_output" -> res.getString(Str.wf_err_output)
        "bad_text" -> res.getString(Str.wf_err_text)
        "bad_url" -> res.getString(Str.wf_err_url)
        "insecure_url" -> res.getString(Str.wf_err_insecure)
        "bad_bot_token" -> res.getString(Str.wf_err_bot_token)
        "bad_chat" -> res.getString(Str.wf_err_chat)
        "bad_path" -> res.getString(Str.wf_err_path)
        "secret_file" -> res.getString(Str.wf_err_secret_file)
        "bad_budget" -> res.getString(Str.wf_err_budget)
        "bad_cron" -> res.getString(Str.wf_err_cron)
        "too_often" -> res.getString(Str.wf_err_too_often)
        "not_github" -> res.getString(Str.wf_err_not_github)
        "no_gh" -> res.getString(Str.wf_err_no_gh)
        "has_steps" -> res.getString(Str.wf_err_has_steps)
        "steps_changed" -> res.getString(Str.wf_err_steps_changed)
        "not_asking" -> res.getString(Str.wf_err_not_asking)
        "bad_answer" -> res.getString(Str.wf_err_bad_answer)
        "not_webhook" -> res.getString(Str.wf_err_not_webhook)
        // Run now and Retry refused for the account or the provider: the run line's words.
        "daily_cap" -> res.getString(Str.au_now_daily_cap)
        "limited" -> res.getString(Str.au_now_limited_plain)
        "signed_out" -> res.getString(Str.au_now_signed_out)
        "no_plan" -> res.getString(Str.au_now_no_plan)
        "on_hold" -> res.getString(Str.au_now_on_hold)
        "no_credit" -> res.getString(Str.au_now_no_credit)
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


/** "Morning brief", "Morning brief and PR watch", "A, B and C". */
fun automationNames(res: Strings, names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> res.getString(Str.au_and, names.dropLast(1).joinToString(", "), names.last())
}
