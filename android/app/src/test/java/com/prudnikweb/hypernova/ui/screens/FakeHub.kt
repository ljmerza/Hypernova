package com.prudnikweb.hypernova.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Aside
import com.prudnikweb.hypernova.data.Attachment
import com.prudnikweb.hypernova.data.FileBytes
import com.prudnikweb.hypernova.data.Folders
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Link
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Listing
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.ModelList
import com.prudnikweb.hypernova.data.NewSessionRequest
import com.prudnikweb.hypernova.data.PermissionRules
import com.prudnikweb.hypernova.data.Project
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderInput
import com.prudnikweb.hypernova.data.ProviderSaved
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SlashCommand
import com.prudnikweb.hypernova.data.Upload
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.data.WApproval

/**
 * A [Hub] for screen tests: fixed sample data ([Samples]), no network. Every call a screen makes
 * that matters lands in [calls] as one line ("answerApproval(ap-build, Allow)"), so a test can
 * say "this click asked the hub for that". State is snapshot state: a test that changes it sees
 * the screen follow, as with the live hub.
 */
class FakeHub(
    sessions: List<Session> = Samples.sessions(),
    mac: Mac = Samples.mac(),
    usage: Usage = Samples.usage(),
    link: Link = Link(LinkState.Connected),
    approvals: Map<String, List<WApproval>> = Samples.approvals(),
    transcripts: Map<String, List<Item>> = Samples.transcripts(),
    override val projects: List<Project> = Samples.projects(),
    override val providers: List<Provider> = Samples.providers(),
    override val projectsRoot: String? = "/home/owner/projects",
) : Hub {
    var macState by mutableStateOf(mac)
    var linkState by mutableStateOf(link)
    var usageState by mutableStateOf(usage)
    var sessionList by mutableStateOf(sessions)
    var approvalMap by mutableStateOf(approvals)
    var transcriptMap by mutableStateOf(transcripts)
    /** What [permissionRules] answers (null: it fails, as an older Mac's). */
    var rules: PermissionRules? = Samples.rules()
    var slashCommands: List<SlashCommand> = Samples.commands()
    var models: List<ModelList> = emptyList()
    /** The answer a side question ([askAside]) comes back with, already read off the terminal. */
    var asideAnswer = "The answer."
    /** What [answerApproval] answers: true, the Mac had it. */
    var answerOk = true

    /** Every call that matters, in order, as "name(arg, arg)". */
    val calls = mutableListOf<String>()
    private fun record(name: String, vararg args: Any?) {
        calls += "$name(${args.joinToString(", ")})"
    }

    override val mac: Mac get() = macState
    override val link: Link get() = linkState
    override val usage: Usage get() = usageState
    override val sessions: List<Session> get() = sessionList

    override fun session(id: String): Session? = sessionList.firstOrNull { it.id == id }
    override fun transcript(id: String): List<Item> = transcriptMap[id].orEmpty()
    override fun consumeReveal(s: Session): Boolean = false

    override fun approval(sessionId: String): WApproval? = approvalMap[sessionId]?.firstOrNull()
    override fun waitingAsks(sessionId: String): List<WApproval> = approvalMap[sessionId].orEmpty()

    override suspend fun answerApproval(id: String, answer: Answer): Boolean {
        record("answerApproval", id, answer)
        return answerOk
    }

    override fun answer(sessionId: String, item: Item.Approval, answer: Answer) {
        record("answer", sessionId, item.approvalId, answer)
    }

    override suspend fun allowSeveral(sessionId: String, ids: List<String>): Result<Int> {
        record("allowSeveral", sessionId, ids)
        return Result.success(ids.size)
    }

    override suspend fun answerMenu(sessionId: String, menuId: String, choice: Int?, alt: Boolean): Result<Unit> {
        record("answerMenu", sessionId, menuId, choice, alt)
        return Result.success(Unit)
    }

    override suspend fun send(sessionId: String, text: String, files: List<Attachment>, replace: Boolean): Result<String> {
        record("send", sessionId, text)
        return Result.success(sessionId)
    }

    override suspend fun askAside(sessionId: String, question: String): Result<Aside> {
        record("askAside", sessionId, question)
        return Result.success(Aside("aside-${calls.size}", question, Aside.ANSWERED, asideAnswer, null, false, Samples.NOW))
    }

    override suspend fun aside(sessionId: String): Result<Aside?> = Result.success(null)

    override suspend fun permissionRules(sessionId: String): Result<PermissionRules> =
        rules?.let { Result.success(it) } ?: Result.failure(HubException("no rules"))

    override suspend fun commands(cwd: String?): Result<List<SlashCommand>> = Result.success(slashCommands)
    override suspend fun models(): Result<List<ModelList>> = Result.success(models)

    override suspend fun upload(file: Upload): Result<Attachment> = Result.failure(HubException("no uploads in tests"))

    override suspend fun setMode(sessionId: String, mode: String): Result<Boolean> {
        record("setMode", sessionId, mode)
        return Result.success(false)
    }

    override fun interrupt(sessionId: String) {
        record("interrupt", sessionId)
    }

    override suspend fun resume(sessionId: String): Result<String> {
        record("resume", sessionId)
        return Result.success(sessionId)
    }

    override suspend fun stopSession(sessionId: String): Result<Unit> {
        record("stopSession", sessionId)
        return Result.success(Unit)
    }

    override suspend fun deleteSession(sessionId: String): Result<Unit> {
        record("deleteSession", sessionId)
        return Result.success(Unit)
    }

    override suspend fun renameSession(sessionId: String, name: String): Result<Unit> {
        record("renameSession", sessionId, name)
        return Result.success(Unit)
    }

    override suspend fun start(req: NewSessionRequest): Result<String> {
        record("start", req.projectPath, req.prompt, req.model, req.permissionMode, req.effort)
        return Result.success("new-session")
    }

    override suspend fun files(path: String): Result<Listing> = Result.success(Listing(path, emptyList()))
    override suspend fun folders(path: String?, hidden: Boolean): Result<Folders> =
        Result.success(Folders(path ?: "/home/owner", "/home", "/home/owner", emptyList()))
    override suspend fun file(path: String): Result<FileBytes> = Result.failure(HubException("no files in tests"))
    override suspend fun preview(path: String?, port: Int?): Result<String> = Result.failure(HubException("no previews in tests"))
    override suspend fun saveProvider(id: String?, input: ProviderInput): Result<ProviderSaved> = Result.failure(HubException("read-only"))
    override suspend fun deleteProvider(id: String): Result<Unit> = Result.failure(HubException("read-only"))
    override suspend fun checklist(): Result<String> = Result.failure(HubException("no checklist in tests"))
    override suspend fun searchHistory(query: String) = Result.success(null)
}
