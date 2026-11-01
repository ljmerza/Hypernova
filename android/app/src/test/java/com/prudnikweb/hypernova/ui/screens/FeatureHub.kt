package com.prudnikweb.hypernova.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.CodexModel
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.FileBytes
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Listing
import com.prudnikweb.hypernova.data.ModelSwitch
import com.prudnikweb.hypernova.data.SwitchPreview
import com.prudnikweb.hypernova.data.WEvent
import com.prudnikweb.hypernova.data.Wire
import com.prudnikweb.hypernova.data.mapObjects
import org.json.JSONObject
import java.io.File

/**
 * [base] for everything, with Codex, agents and files of a test's own: what each screen of those
 * asks for is scripted here and recorded in [calls] ("switchPreview(s-build, codex, recent)").
 */
class FeatureHub(val base: FakeHub = FakeHub()) : Hub by base {
    val calls = mutableListOf<String>()
    private fun record(name: String, vararg args: Any?) {
        calls += "$name(${args.joinToString(", ")})"
    }

    // ─── Codex ───
    var codexState by mutableStateOf<CodexState?>(CodexState(available = true, enabled = true, connected = true, version = "0.163.0"))
    override val codex: CodexState? get() = codexState
    var codexModelList = listOf(
        CodexModel("gpt-test", "GPT-Test", "The default", listOf("low", "high"), "low", true),
        CodexModel("gpt-other", "GPT-Other", null, listOf("medium"), "medium", false),
    )
    override suspend fun codexModels(): Result<List<CodexModel>> = Result.success(codexModelList)
    /** What a preview answers, by how much it carries. */
    var previews: (String) -> Result<SwitchPreview> = { c -> Result.success(SwitchPreview(if (c == "none") "" else "User: fix the parser\nClaude: Trimmed its input.", if (c == "none") 0 else 2, 47, 96 * 1024)) }
    override suspend fun switchPreview(sessionId: String, to: String, context: String): Result<SwitchPreview> {
        record("switchPreview", sessionId, to, context)
        return previews(context)
    }
    var switchAnswer: Result<String> = Result.success("s-new")
    override suspend fun switchAgent(sessionId: String, to: String, text: String, model: String?, effort: String?, mode: String?): Result<String> {
        record("switchAgent", sessionId, to, text.length, model, effort, mode)
        return switchAnswer
    }
    override suspend fun setCodexModel(sessionId: String, model: String?, effort: String?): Result<ModelSwitch> {
        record("setCodexModel", sessionId, model, effort)
        return Result.success(ModelSwitch(model, nextTurn = false))
    }
    override fun interrupt(sessionId: String) {
        record("interrupt", sessionId)
        base.interrupt(sessionId)
    }

    // ─── Agents ───
    var board by mutableStateOf(AgentBoard.Empty)
    override fun agents(sessionId: String): AgentBoard = board
    /** Each call to [agentEvents] takes the next answer; the last one repeats. */
    val agentAnswers = ArrayDeque<Result<List<WEvent>>>()
    override suspend fun agentEvents(sessionId: String, agentId: String): Result<List<WEvent>> {
        record("agentEvents", sessionId, agentId)
        return if (agentAnswers.size > 1) agentAnswers.removeFirst() else agentAnswers.firstOrNull() ?: Result.success(emptyList())
    }

    // ─── Files ───
    var listings: (String) -> Result<Listing> = { Result.success(Listing(it, emptyList())) }
    override suspend fun files(path: String): Result<Listing> {
        record("files", path)
        return listings(path)
    }
    var fileAnswer: (String) -> Result<FileBytes> = { Result.success(FileBytes("one\ntwo\n".toByteArray(), "text/plain")) }
    override suspend fun file(path: String): Result<FileBytes> {
        record("file", path)
        return fileAnswer(path)
    }
    var downloadBytes: ByteArray = ByteArray(0)
    override suspend fun download(source: String, target: File, resume: com.prudnikweb.hypernova.data.DownloadResume?, onStart: (String?) -> Unit, onProgress: (Long, Long) -> Unit): Result<com.prudnikweb.hypernova.data.Downloaded> {
        record("download", source)
        target.parentFile?.mkdirs()
        target.writeBytes(downloadBytes)
        onStart("\"v1\"")
        onProgress(downloadBytes.size.toLong(), downloadBytes.size.toLong())
        return Result.success(com.prudnikweb.hypernova.data.Downloaded(downloadBytes.size.toLong(), "\"v1\""))
    }
}

/** The contract fixtures (protocol/fixtures/), as the screens' data. */
object ScreenFixtures {
    private val dir: File by lazy { File(ScreenFixtures::class.java.classLoader!!.getResource("fixtures")!!.toURI()) }
    fun json(name: String): JSONObject = JSONObject(File(dir, "$name.json").readText())
    fun events(name: String): List<WEvent> = json(name).optJSONArray("events").mapObjects(Wire::event)
}
