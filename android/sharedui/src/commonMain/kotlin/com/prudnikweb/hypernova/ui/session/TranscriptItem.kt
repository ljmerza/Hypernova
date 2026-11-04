package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.platform.wordsNow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.chat.AgentCard
import com.prudnikweb.hypernova.ui.chat.WorkflowCard
import com.prudnikweb.hypernova.ui.chat.ApprovalBlock
import com.prudnikweb.hypernova.ui.chat.AssistantMessage
import com.prudnikweb.hypernova.ui.chat.CommandOutputRow
import com.prudnikweb.hypernova.ui.chat.DiffCard
import com.prudnikweb.hypernova.ui.chat.MiniTerminal
import com.prudnikweb.hypernova.ui.chat.NoticeRow
import com.prudnikweb.hypernova.ui.chat.ResultRow
import com.prudnikweb.hypernova.ui.chat.ThinkingRow
import com.prudnikweb.hypernova.ui.chat.ToolRow
import com.prudnikweb.hypernova.ui.chat.UserMessage
import kotlinx.coroutines.launch
import com.prudnikweb.hypernova.ui.platform.SharedUi

/* One item of a session's chat, on both systems. */

/**
 * One transcript item, drawn as what it is (a session's own, or an agent's on its screen). Files
 * and previews open in the app; an agent's card opens that agent.
 */
/** What may be a plan in an agent's folder, wherever that folder has been moved to. Only says a tap is worth trying: a wrong guess gets the server's own answer. */
internal fun mayBeAgentPlan(path: String): Boolean = "/plans/" in path && path.endsWith(".md", ignoreCase = true)

@Composable
internal fun TranscriptItem(hub: Hub, sessionId: String, item: Item, modifier: Modifier, known: List<com.prudnikweb.hypernova.data.SlashCommand>? = null, onMenu: ((Item) -> Unit)? = null, named: Boolean = true) {
    val opener = LocalOpener.current
    val preview: (Int) -> Unit = { port -> opener.preview(null, port, "localhost:$port") }
    // Read rows and diff cards open their file only where the Mac lets the phone browse the folder.
    val openFile: ((String) -> Unit)? = if (!opener.opensFiles || hub.session(sessionId)?.browsable == false) null else opener.file
    // An agent's plan may open where the session's folder doesn't: the server decides (docs/PROTOCOL.md, "Agents' plans").
    val openOrPlan: (String?) -> ((String) -> Unit)? = { path -> openFile ?: opener.file.takeIf { opener.opensFiles && path != null && mayBeAgentPlan(path) } }
    val openAgent: (String) -> Unit = { id -> opener.agent(sessionId, id) }
    when (item) {
        // [onMenu]: the session's chat gives its messages a long-press menu (ChatExtras.kt).
        is Item.User -> WithMessageMenu(item, onMenu) { menu ->
            val appScope = LocalAppScope.current
            val toast = LocalToast.current
            UserMessage(
                item, modifier.then(menu), known = known,
                codex = hub.session(sessionId)?.codex == true,
                onStartNew = item.waitingId?.let { w -> { appScope.launch { hub.startFromWaiting(w).onSuccess { opener.switchSession(it) }.onFailure { toast(it.message.orEmpty()) } } } },
                // Attachments are the Mac's own copies (its attachments folder), wherever the session is.
                files = { SentFiles(item.files, hub, opener.file.takeIf { opener.opensFiles }) },
            )
        }
        is Item.Assistant -> WithMessageMenu(item, onMenu) { menu -> AssistantMessage(item, modifier.then(menu), onPreview = preview, codex = hub.session(sessionId)?.codex == true, named = named) }
        is Item.Tool -> ToolRow(item, modifier, onOpen = openOrPlan(item.path), onPreview = preview)
        is Item.Terminal -> MiniTerminal(item, modifier, onPreview = preview)
        is Item.Diff -> DiffCard(item, modifier, onOpen = openOrPlan(item.path), onPreviewFile = { path -> opener.preview(path, null, path.substringAfterLast('/')) })
        is Item.Approval -> {
            // "Always" with no rule to show on the sheet: a confirm saying what it does, beside the whole command.
            val confirm = com.prudnikweb.hypernova.ui.LocalConfirm.current
            val title = stringResource(Str.always_confirm_title)
            val means = stringResource(Str.always_means)
            val action = stringResource(Str.always_save)
            // The card's own Always opens the Always sheet where Claude Code suggested a rule: the
            // rule, editable, and where it goes (AlwaysSheet.kt). The card itself is unchanged.
            var alwaysSheet by remember(item.key) { mutableStateOf(false) }
            ApprovalBlock(item, onAnswer = { a ->
                if (a == Answer.Always && item.alwaysRules.isNotEmpty()) {
                    alwaysSheet = true
                } else if (a == Answer.Always) {
                    confirm(com.prudnikweb.hypernova.data.Confirm(title, "$means\n\n${item.detail}", action, danger = true) { hub.answer(sessionId, item, a) })
                } else hub.answer(sessionId, item, a)
            }, modifier = modifier)
            if (alwaysSheet && item.resolved == null) {
                AlwaysSheet(item, onDismiss = { alwaysSheet = false }, onSave = { a -> alwaysSheet = false; hub.answer(sessionId, item, a) })
            }
        }
        is Item.Thinking -> ThinkingRow(item, modifier)
        is Item.Result -> ResultRow(item, modifier)
        is Item.Notice -> if (item.command != null) CommandOutputRow(item.key, item.text, item.tone, modifier) else NoticeRow(item, modifier)
        is Item.AgentCard -> AgentCard(item, openAgent, modifier)
        is Item.WorkflowCard -> WorkflowCard(item, openAgent, modifier)
    }
}

