package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.CodexAccount
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.claudeAvailable
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ModelList
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderCheck
import com.prudnikweb.hypernova.data.ProviderField
import com.prudnikweb.hypernova.data.ProviderForm
import com.prudnikweb.hypernova.data.ProviderInput
import com.prudnikweb.hypernova.data.ProviderModels
import com.prudnikweb.hypernova.data.ProviderRefused
import com.prudnikweb.hypernova.data.ProviderStatus
import com.prudnikweb.hypernova.data.ProviderWords
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.FieldLabel
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.ModelChip
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.StatusChip
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.home.SearchField
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

// ─── Providers ──────────────────────────────────────────────────────────────

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    "anthropic" -> stringResource(R.string.kind_anthropic)
    "openrouter" -> stringResource(R.string.kind_openrouter)
    else -> stringResource(R.string.kind_custom)
}

/** Every provider, built-in first; add OpenRouter or a custom endpoint. Keys are never shown. */
@Composable
fun ProvidersScreen(hub: Hub, onBack: () -> Unit, onEdit: (id: String?, kind: String) -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // What's left on each OpenRouter key, read again as the screen opens (quietly: a failure keeps the last reading).
    LaunchedEffect(Unit) {
        for (p in hub.providers) if (p.kind == "openrouter" && p.hasKey) hub.checkProvider(p.id)
    }
    ScreenScaffold(sourceKey = "providers", title = stringResource(R.string.providers), onBack = onBack) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The agents on the Mac first (Claude Code, Codex), then the providers Claude Code runs through.
            item(key = "agents-seam") {
                Seam(stringResource(R.string.ag_agents), modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp, bottom = 2.dp))
            }
            item(key = "agent-claude") { ClaudeAgentCard(hub) }
            item(key = "agent-codex") { CodexAgentCard(hub) }
            item(key = "providers-seam") {
                Seam(stringResource(R.string.ag_claude_providers), modifier = Modifier.padding(horizontal = 4.dp).padding(top = 14.dp, bottom = 2.dp))
            }
            // Without Claude Code on the Mac its providers have nothing to run: one quiet line, nothing to add.
            if (!hub.claudeAvailable) {
                item(key = "intro") {
                    HText(stringResource(R.string.ag_providers_without_claude), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                }
                return@LazyColumn
            }
            item(key = "intro") {
                HText(stringResource(R.string.providers_intro), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            }
            items(hub.providers, key = { it.id }) { p ->
                ProviderCard(p, hub.account, onClick = if (p.builtIn) null else ({ onEdit(p.id, p.kind) }))
            }
            item(key = "add") {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(R.string.add_openrouter), { onEdit(null, "openrouter") }, Modifier.weight(1f), kind = PillKind.Primary, height = 48.dp, leading = Glyph.Plus)
                    Pill(stringResource(R.string.add_custom), { onEdit(null, "custom") }, Modifier.weight(1f), kind = PillKind.Outline, height = 48.dp, leading = Glyph.Plus)
                }
            }
        }
    }
}

// ─── Agents (Claude Code, Codex) ────────────────────────────────────────────

/**
 * Claude Code on the Mac: its version, or that it isn't installed (data/AgentLead.kt), and its
 * account in one line. Claude Code is always the one sessions use where it's there; its switch is
 * where they run: **Sessions in claude agents** (`POST /v1/claude {agents}`), from a Mac that says.
 */
@Composable
private fun ClaudeAgentCard(hub: Hub) {
    val installed = hub.claudeAvailable
    val version = (hub.claude?.version ?: hub.mac.claudeVersion)?.takeIf { installed }
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // The switch follows a tap at once; the Mac's answer (or a failure) settles it.
    var asked by remember { mutableStateOf<Boolean?>(null) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Seam(stringResource(R.string.ag_vendor_anthropic), trail = version)
            Spacer(Modifier.height(12.dp))
            HText(stringResource(R.string.cx_agent_claude), style = Type.heading, color = if (installed) HN.ink else HN.inkMuted, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            if (!installed) {
                HText(stringResource(R.string.ag_not_installed, hub.mac.name), style = Type.small, color = HN.inkDim)
                return@Column
            }
            val (line, trouble) = claudeAccountLine(hub.account, hub.mac.name)
            HText(line, style = Type.small, color = if (trouble) HN.amber else HN.inkMuted)
            val agents = hub.claude?.agents ?: return@Column
            Spacer(Modifier.height(12.dp))
            Hairline(Modifier.fillMaxWidth())
            ToggleRow(
                stringResource(R.string.ag_claude_agents),
                stringResource(R.string.ag_claude_agents_body, hub.mac.name),
                checked = asked ?: agents,
                onChange = { on ->
                    if (!busy) {
                        busy = true
                        asked = on
                        scope.launch {
                            hub.setClaudeAgents(on).onFailure { e ->
                                toast(e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.ag_claude_agents_err))
                            }
                            asked = null
                            busy = false
                        }
                    }
                },
                // While the Mac answers, taps wait: dimmed, and said so to TalkBack.
                modifier = Modifier.alpha(if (busy) 0.6f else 1f).semantics { if (busy) disabled() },
            )
        }
    }
}

/** Claude Code's account in a line, and whether it needs the owner (amber). */
@Composable
private fun claudeAccountLine(a: Account, mac: String): Pair<String, Boolean> = when (a.state) {
    Account.LOGGED_OUT -> stringResource(R.string.ag_claude_signed_out, mac) to true
    Account.NO_SUBSCRIPTION -> stringResource(R.string.ag_claude_no_plan) to true
    Account.ON_HOLD -> stringResource(R.string.ag_claude_on_hold) to true
    Account.NO_CREDIT -> stringResource(R.string.ag_claude_no_credit) to true
    Account.LIMITED -> if (a.resting(System.currentTimeMillis()) != null) stringResource(R.string.ag_claude_resting) to false else signedIn(a)
    else -> signedIn(a)
}

@Composable
private fun signedIn(a: Account): Pair<String, Boolean> =
    (planWord(a.plan)?.let { stringResource(R.string.ag_claude_signed_in_plan, it) } ?: stringResource(R.string.ag_claude_signed_in)) to false

/** A plan as its name is written: "max" → "Max", "plus" → "Plus". */
/** ChatGPT's plan as OpenAI names it ("prolite" is "Pro Lite"); one it doesn't know, capitalised. */
internal fun planWord(plan: String?): String? = plan?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
    PLAN_WORDS[p.lowercase()] ?: p.replaceFirstChar { it.uppercaseChar() }
}
private val PLAN_WORDS = mapOf("prolite" to "Pro Lite", "pro" to "Pro", "plus" to "Plus", "go" to "Go", "free" to "Free", "team" to "Team", "business" to "Business", "enterprise" to "Enterprise", "edu" to "Edu")

/**
 * Codex on the Mac (data/Codex.kt): its version (against the oldest Hypernova works with), how it's
 * signed in, what's wrong, and **Use Codex with Hypernova** (`POST /v1/codex`). Not installed, or
 * too old, or signed out: the one line to run on the Mac, to copy. It never installs anything.
 */
@Composable
private fun CodexAgentCard(hub: Hub) {
    val c = hub.codex
    val mac = hub.mac.name
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // The switch follows a tap at once; the Mac's answer (or a failure) settles it.
    var asked by remember { mutableStateOf<Boolean?>(null) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Seam(stringResource(R.string.ag_vendor_openai), trail = c?.version)
            Spacer(Modifier.height(12.dp))
            HText(stringResource(R.string.cx_agent_codex), style = Type.heading, color = if (c != null) HN.ink else HN.inkMuted, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            if (c == null) {
                HText(stringResource(R.string.ag_not_installed, mac), style = Type.small, color = HN.inkDim)
                ShellLine(stringResource(R.string.ag_install), CODEX_INSTALL)
                return@Column
            }
            val old = codexTooOld(c)
            val signedOut = c.enabled && !old && c.account?.signedIn == false
            when {
                old -> {
                    val v = c.version
                    val min = c.min
                    HText(
                        if (v != null && min != null) stringResource(R.string.ag_codex_too_old, v, min) else stringResource(R.string.ag_codex_too_old_plain),
                        style = Type.small, color = HN.amber,
                    )
                    ShellLine(stringResource(R.string.ag_update), CODEX_UPDATE)
                }
                !c.enabled -> HText(stringResource(R.string.ag_codex_off), style = Type.small, color = HN.inkDim)
                signedOut -> {
                    HText(stringResource(R.string.ag_codex_signed_out, mac), style = Type.small, color = HN.amber)
                    ShellLine(stringResource(R.string.ag_sign_in), CODEX_LOGIN)
                }
                else -> c.account?.takeIf { it.signedIn }?.let { HText(codexAccountLine(it), style = Type.small, color = HN.inkMuted) }
            }
            // What's wrong now, in the Mac's words, unless a line above already says it.
            val problem = c.problem?.takeIf { !old && !signedOut && (c.enabled || !c.available) }
            if (problem != null) {
                Spacer(Modifier.height(6.dp))
                HText(problem, style = Type.small, color = HN.amber, maxLines = 3, overflow = TextOverflow.Ellipsis)
            } else if (c.enabled && c.available && !c.connected) {
                Spacer(Modifier.height(6.dp))
                HText(stringResource(R.string.ag_codex_reaching, mac), style = Type.small, color = HN.inkDim)
            }
            // The switch: only for a Codex that can be used (or to turn off one that's on).
            if (c.available || c.enabled) {
                Spacer(Modifier.height(12.dp))
                Hairline(Modifier.fillMaxWidth())
                ToggleRow(
                    stringResource(R.string.ag_codex_use),
                    stringResource(if (hub.claudeAvailable) R.string.ag_codex_use_body else R.string.ag_codex_use_body_alone),
                    checked = asked ?: c.enabled,
                    onChange = { on ->
                        if (!busy) {
                            busy = true
                            asked = on
                            scope.launch {
                                hub.setCodexEnabled(on).onFailure { e ->
                                    toast(e.message?.takeIf { it.isNotBlank() } ?: context.getString(if (on) R.string.cx_err_turn_on else R.string.cx_err_turn_off))
                                }
                                asked = null
                                busy = false
                            }
                        }
                    },
                    // While the Mac answers, taps wait: dimmed, and said so to TalkBack.
                    modifier = Modifier.alpha(if (busy) 0.6f else 1f).semantics { if (busy) disabled() },
                )
            }
        }
    }
}

/** How Codex is signed in, in a line: ChatGPT and its plan, or an API key (which costs money). */
@Composable
private fun codexAccountLine(a: CodexAccount): String = when {
    a.paid -> stringResource(R.string.ag_codex_api_key)
    else -> planWord(a.planType)?.let { stringResource(R.string.ag_codex_chatgpt_plan, it) } ?: stringResource(R.string.ag_codex_chatgpt)
}

/** A line to run in a terminal on the Mac: what it's for, the command in mono, and Copy. */
@Composable
private fun ShellLine(key: String, command: String) {
    val context = LocalContext.current
    val toast = LocalToast.current
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HText(key.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        HText(command, style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Pill(stringResource(R.string.wf_copy), {
            com.prudnikweb.hypernova.ui.automations.copy(context, command, sensitive = false)
            toast(context.getString(R.string.wf_copied))
        }, kind = PillKind.Outline, height = 32.dp)
    }
}

private const val CODEX_INSTALL = "npm i -g @openai/codex"
private const val CODEX_UPDATE = "codex update"
private const val CODEX_LOGIN = "codex login"

/**
 * Codex is there but too old for Hypernova: not [CodexState.available], with a version below [CodexState.min]
 * (the Mac sends no minimum: any version it couldn't use).
 */
internal fun codexTooOld(c: CodexState): Boolean {
    val v = c.version ?: return false
    if (c.available) return false
    val min = c.min ?: return true
    return versionBelow(v, min)
}

/** Whether version [v] is older than [min], by their numbers ("0.150.2" < "0.163.0"; "codex-cli 0.170.0" reads as 0.170.0). */
internal fun versionBelow(v: String, min: String): Boolean {
    val re = Regex("""\d+(?:\.\d+)*""")
    val a = re.find(v)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: return false
    val b = re.find(min)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

/** A provider's trouble in a chip's words: its own, or for the built-in one the account's. */
@Composable
private fun troubleLabel(p: Provider, account: Account): String? {
    // Its own trouble, with when a rate limit lifts when it said (data/Providers.kt).
    if (p.status != null) return ProviderWords.trail(LocalContext.current, p)?.first
    if (!p.builtIn || account.ok) return null
    return when (account.state) {
        Account.LOGGED_OUT -> stringResource(R.string.state_signed_out)
        Account.NO_SUBSCRIPTION -> stringResource(R.string.state_no_plan)
        Account.ON_HOLD -> stringResource(R.string.state_on_hold)
        Account.NO_CREDIT -> stringResource(R.string.state_no_credit)
        Account.LIMITED -> if (account.resting(System.currentTimeMillis()) != null) stringResource(R.string.provider_status_resting) else null
        else -> stringResource(R.string.provider_status_other)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(p: Provider, account: Account, onClick: (() -> Unit)?) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick, onClickLabel = stringResource(R.string.edit)) {
        Column(Modifier.padding(16.dp)) {
            Seam(kindLabel(p.kind), trail = p.baseUrl?.removePrefix("https://"))
            Spacer(Modifier.height(12.dp))
            HText(p.name, style = Type.heading, color = HN.ink, maxLines = 1)
            if (p.builtIn) {
                Spacer(Modifier.height(4.dp))
                HText(stringResource(R.string.provider_builtin_desc), style = Type.small, color = HN.inkMuted)
            }
            // Its models as the chips the session cards draw them with ("GPT-5", "SONNET-4.5"), not
            // "openai/gpt-5 · anthropic/claude-sonnet-4.5"; TalkBack hears their full ids.
            val models = listOfNotNull(p.models.default, p.models.opus, p.models.sonnet, p.models.haiku).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (models.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val said = stringResource(R.string.provider_models_said, com.prudnikweb.hypernova.data.Phrases.list(models, stringResource(R.string.list_and)))
                FlowRow(
                    Modifier.clearAndSetSemantics { contentDescription = said },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (m in models.map { com.prudnikweb.hypernova.data.ModelNames.chip(it) }.distinct()) ModelChip(m)
                }
            }
            // What went wrong with it lately, in its own words (out of credits, the key turned down).
            val detail = p.status?.text?.takeIf { it.isNotBlank() }
            if (detail != null) {
                Spacer(Modifier.height(8.dp))
                HText(detail, style = Type.small, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    p.builtIn -> StatusChip(stringResource(R.string.built_in), HN.accent)
                    p.hasKey -> StatusChip(stringResource(R.string.key_saved), HN.accent)
                    else -> StatusChip(stringResource(R.string.no_key), HN.amber)
                }
                val trouble = troubleLabel(p, account)
                if (trouble != null) {
                    Spacer(Modifier.width(6.dp))
                    // Amber when it needs the owner; a built-in account that only rests at a limit is quiet.
                    StatusChip(trouble, if (p.status == null && !account.needsYou) HN.inkMuted else HN.amber)
                }
                // Said free by the owner (a model on this computer): automations run on it without asking.
                if (!p.builtIn && !p.paid) {
                    Spacer(Modifier.width(6.dp))
                    StatusChip(stringResource(R.string.pv_free_chip), HN.inkMuted)
                }
                // What's left on it, where its API says (OpenRouter): "$4.20 left", "$1.10 today".
                // After a banknote, as the chat's strip draws what a session cost.
                val left = ProviderWords.balance(LocalContext.current, p.balance, p.status)
                if (left != null) {
                    Spacer(Modifier.weight(1f))
                    GlyphFigure(Glyph.Note, left, color = if (p.status?.state == ProviderStatus.NO_CREDIT) HN.amber else HN.inkMuted)
                }
            }
            // The one thing to do about it: top up OpenRouter, or put in a key that works.
            val context = LocalContext.current
            when {
                p.status?.state == ProviderStatus.NO_CREDIT && p.kind == "openrouter" -> {
                    Spacer(Modifier.height(12.dp))
                    Pill(stringResource(R.string.provider_add_credits), { com.prudnikweb.hypernova.data.openLink(context, OPENROUTER_CREDITS) }, kind = PillKind.Outline, height = 40.dp)
                }
                p.status?.state == ProviderStatus.BAD_KEY && onClick != null -> {
                    Spacer(Modifier.height(12.dp))
                    Pill(stringResource(R.string.provider_edit_key), onClick, kind = PillKind.Outline, height = 40.dp)
                }
            }
        }
    }
}

private const val OPENROUTER_CREDITS = "https://openrouter.ai/settings/credits"

/** A slot of the provider's models, as its field and its list call it. */
private enum class Slot { Default, Opus, Sonnet, Haiku }

/**
 * Add or edit a provider. The key field always starts empty: a saved key is never shown. **Check**
 * tries the address and the key on the Mac without saving them, and says what it found under the
 * key ("Key works. $4.20 left.", "OpenRouter refused this key."); Save tries them again and stays
 * here when the key was refused or dropped, so the verdict is in view rather than in a toast. The
 * Mac's refusals go under their field. Model slots take any id, or one from the provider's list
 * (OpenRouter's catalogue, or what a custom endpoint listed when checked).
 */
@Composable
fun ProviderEditScreen(hub: Hub, id: String?, kind: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val res = context.resources
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Saved here once (a new one whose key was refused stays open): further saves edit it.
    var savedId by rememberSaveable { mutableStateOf(id) }
    val existing = hub.providers.firstOrNull { it.id == savedId }
    val openRouter = kind == "openrouter"
    var name by rememberSaveable { mutableStateOf(existing?.name ?: if (openRouter) res.getString(R.string.kind_openrouter) else "") }
    var baseUrl by rememberSaveable { mutableStateOf(existing?.baseUrl ?: if (openRouter) "https://openrouter.ai/api" else "") }
    var key by rememberSaveable { mutableStateOf("") }
    var mDefault by rememberSaveable { mutableStateOf(existing?.models?.default.orEmpty()) }
    var mOpus by rememberSaveable { mutableStateOf(existing?.models?.opus.orEmpty()) }
    var mSonnet by rememberSaveable { mutableStateOf(existing?.models?.sonnet.orEmpty()) }
    var mHaiku by rememberSaveable { mutableStateOf(existing?.models?.haiku.orEmpty()) }
    var paid by rememberSaveable { mutableStateOf(existing?.paid ?: true) }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    // What Check (or Save) found; it goes stale as soon as the address or the key is typed over.
    var check by remember { mutableStateOf<ProviderCheck?>(null) }
    var keyDropped by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<Pair<ProviderField, String>?>(null) }
    var tried by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Slot?>(null) }
    val keyFocus = remember { FocusRequester() }

    val urlOk = ProviderForm.addressOk(baseUrl)
    val insecure = ProviderForm.insecure(baseUrl)
    val keyNeeded = openRouter && existing?.hasKey != true
    val problem = when {
        name.isBlank() -> stringResource(R.string.provider_needs_name)
        !urlOk -> stringResource(R.string.provider_needs_url)
        keyNeeded && key.isBlank() -> stringResource(R.string.provider_needs_key)
        else -> null
    }
    val canCheck = urlOk && (key.isNotBlank() || existing?.hasKey == true) && !checking
    val title = when {
        existing != null -> stringResource(R.string.provider_edit_title, existing.name)
        openRouter -> stringResource(R.string.provider_new_openrouter)
        else -> stringResource(R.string.provider_new_custom)
    }
    val text = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next)

    // The lists the model slots offer: this provider's (or another OpenRouter's: the catalogue is
    // the same), or what a custom endpoint listed when it was checked.
    var lists by remember { mutableStateOf<List<ModelList>?>(null) }
    LaunchedEffect(Unit) { hub.models().onSuccess { lists = it } }
    val catalogue = remember(lists, check, savedId) { modelIds(lists, hub.providers, savedId, kind, check) }

    fun runCheck() {
        if (!canCheck) return
        checking = true
        refused = null
        scope.launch {
            hub.checkNewProvider(kind, baseUrl, key, savedId)
                .onSuccess { check = it }
                .onFailure { e -> refused = ProviderForm.field((e as? ProviderRefused)?.code) to e.message.orEmpty() }
            checking = false
        }
    }

    fun save() {
        tried = true
        if (problem != null || busy) return
        busy = true
        refused = null
        val typedKey = key.isNotBlank()
        scope.launch {
            hub.saveProvider(
                savedId,
                ProviderInput(
                    name = name, kind = kind, baseUrl = baseUrl, apiKey = key.takeIf { it.isNotBlank() },
                    models = ProviderModels(mDefault, mOpus, mSonnet, mHaiku), paid = paid,
                ),
            ).onSuccess { saved ->
                savedId = saved.provider.id
                key = ""
                keyDropped = saved.keyDropped
                // A new key the Mac tried at once: whether it works, and what's left ("Key works. $4.20 left.").
                val verdict = saved.check?.let { checkWords(context, it) }
                    ?: if (typedKey) ProviderWords.keyVerdict(context, saved.provider) else null
                if (ProviderForm.staysOpen(saved)) {
                    // The verdict stays in view, where the key goes.
                    check = saved.check
                    if (saved.keyDropped) runCatching { keyFocus.requestFocus() }
                } else {
                    toast(verdict?.let { res.getString(R.string.pv_saved_verdict, it) } ?: res.getString(R.string.pv_saved))
                    onBack()
                }
            }.onFailure { e -> refused = ProviderForm.field((e as? ProviderRefused)?.code) to e.message.orEmpty() }
            busy = false
        }
    }

    ScreenScaffold(sourceKey = "provider:${id ?: kind}", title = title, onBack = onBack) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 20.dp, end = 20.dp),
        ) {
            item(key = "form") {
                Column {
                    if (!openRouter) {
                        HText(stringResource(R.string.provider_custom_help), style = Type.small, color = HN.inkMuted)
                        Spacer(Modifier.height(18.dp))
                    }
                    FieldLabel(stringResource(R.string.provider_name))
                    Field(name, { name = it.take(NAME_MAX) }, label = stringResource(R.string.provider_name), keyboard = text, error = (tried && name.isBlank()) || refused?.first == ProviderField.Name)
                    Refusal(refused, ProviderField.Name)
                    Spacer(Modifier.height(16.dp))
                    FieldLabel(stringResource(R.string.provider_base_url))
                    Field(
                        baseUrl, { baseUrl = it; check = null; if (refused?.first == ProviderField.Address) refused = null }, placeholder = "https://", mono = true, label = stringResource(R.string.provider_base_url),
                        keyboard = text.copy(keyboardType = KeyboardType.Uri), error = (tried && !urlOk) || refused?.first == ProviderField.Address,
                    )
                    if (insecure && refused?.first != ProviderField.Address) {
                        Spacer(Modifier.height(8.dp))
                        HText(stringResource(R.string.pv_insecure_typed), style = Type.small, color = HN.amber)
                    }
                    Refusal(refused, ProviderField.Address)
                    Spacer(Modifier.height(16.dp))
                    FieldLabel(stringResource(R.string.provider_api_key), trail = if (existing?.hasKey == true && !keyDropped) stringResource(R.string.key_saved) else null)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            Field(
                                key, { key = it; check = null; keyDropped = false; if (refused?.first == ProviderField.Key) refused = null },
                                modifier = Modifier.focusRequester(keyFocus),
                                placeholder = if (existing?.hasKey == true && !keyDropped) stringResource(R.string.provider_key_keep) else stringResource(R.string.provider_key_hint),
                                mono = true, secret = true, label = stringResource(R.string.provider_api_key), keyboard = text,
                                error = (tried && keyNeeded && key.isBlank()) || refused?.first == ProviderField.Key || keyDropped,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Pill(
                            if (checking) stringResource(R.string.provider_checking) else stringResource(R.string.provider_check),
                            ::runCheck, kind = PillKind.Outline, height = 44.dp, enabled = canCheck,
                        )
                    }
                    Refusal(refused, ProviderField.Key)
                    if (keyDropped) {
                        Spacer(Modifier.height(8.dp))
                        HText(stringResource(R.string.pv_key_dropped), style = Type.small, color = HN.amber)
                    }
                    check?.let { CheckVerdict(it, openRouter) }
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(stringResource(R.string.pv_paid), stringResource(if (paid) R.string.pv_paid_on else R.string.pv_paid_off), paid, { paid = it })
                    Spacer(Modifier.height(18.dp))
                    Seam(stringResource(R.string.provider_models))
                    Spacer(Modifier.height(10.dp))
                    HText(stringResource(R.string.provider_models_help), style = Type.small, color = HN.inkDim)
                    Spacer(Modifier.height(14.dp))
                    val listed = catalogue.isNotEmpty()
                    SlotField(stringResource(R.string.provider_model_default), mDefault, { mDefault = it }, stringResource(R.string.provider_model_hint_default), listed, { picking = Slot.Default })
                    Spacer(Modifier.height(12.dp))
                    SlotField(stringResource(R.string.provider_model_opus), mOpus, { mOpus = it }, stringResource(R.string.provider_model_hint_any), listed, { picking = Slot.Opus })
                    Spacer(Modifier.height(12.dp))
                    SlotField(stringResource(R.string.provider_model_sonnet), mSonnet, { mSonnet = it }, stringResource(R.string.provider_model_hint_sonnet), listed, { picking = Slot.Sonnet })
                    Spacer(Modifier.height(12.dp))
                    SlotField(stringResource(R.string.provider_model_haiku), mHaiku, { mHaiku = it }, stringResource(R.string.provider_model_hint_any), listed, { picking = Slot.Haiku }, last = true)

                    val shownError = refused?.takeIf { it.first == ProviderField.Other }?.second ?: problem?.takeIf { tried }
                    if (shownError != null) {
                        Spacer(Modifier.height(14.dp))
                        HText(shownError, style = Type.small, color = HN.danger)
                    }
                    Spacer(Modifier.height(24.dp))
                    Pill(
                        if (busy) stringResource(R.string.saving) else stringResource(R.string.save), ::save,
                        Modifier.fillMaxWidth(),
                        kind = PillKind.Primary, height = 50.dp,
                        // Faded only once Save was tried with something missing: the reason is said above.
                        enabled = !busy && (problem == null || !tried),
                    )
                    if (existing != null && !existing.builtIn) {
                        Spacer(Modifier.height(12.dp))
                        Hairline(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Pill(
                            stringResource(R.string.delete),
                            {
                                confirm(
                                    Confirm(
                                        res.getString(R.string.delete_provider_q, existing.name),
                                        deleteLine(res, existing.id, hub),
                                        res.getString(R.string.delete),
                                        danger = true,
                                    ) {
                                        scope.launch {
                                            hub.deleteProvider(existing.id)
                                                .onSuccess { toast(res.getString(R.string.pv_deleted)); onBack() }
                                                .onFailure { refused = ProviderField.Other to it.message.orEmpty() }
                                        }
                                    },
                                )
                            },
                            Modifier.fillMaxWidth(), kind = PillKind.Danger, height = 48.dp,
                        )
                    }
                }
            }
        }
    }

    val slot = picking
    if (slot != null) {
        ModelIdSheet(
            providerName = existing?.name ?: name.ifBlank { kindLabel(kind) },
            ids = catalogue,
            loading = lists == null,
            onPick = { v ->
                when (slot) {
                    Slot.Default -> mDefault = v
                    Slot.Opus -> mOpus = v
                    Slot.Sonnet -> mSonnet = v
                    Slot.Haiku -> mHaiku = v
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

private const val NAME_MAX = 40

/** The Mac's refusal under its field, when it belongs there. */
@Composable
private fun Refusal(refused: Pair<ProviderField, String>?, field: ProviderField) {
    if (refused == null || refused.first != field) return
    Spacer(Modifier.height(8.dp))
    HText(refused.second, style = Type.small, color = HN.danger)
}

/** "Key works. $4.20 left.": the Mac's words, or ours for a state it said nothing about. */
private fun checkWords(context: android.content.Context, c: ProviderCheck): String {
    if (c.text.isNotBlank()) return c.text
    val res = context.resources
    return when (c.state) {
        ProviderCheck.OK -> ProviderWords.balance(context, c.balance)?.let { res.getString(R.string.balance_key_works, it) } ?: res.getString(R.string.pv_check_ok)
        ProviderCheck.BAD_KEY -> res.getString(R.string.pv_check_bad_key)
        ProviderCheck.NO_CREDIT -> res.getString(R.string.pv_check_no_credit)
        ProviderCheck.LIMITED -> res.getString(R.string.pv_check_limited)
        ProviderCheck.NOT_FOUND -> res.getString(R.string.pv_check_not_found)
        else -> res.getString(R.string.pv_check_unreachable)
    }
}

/**
 * Check's answer, under the key: a check mark and its words in mint when the key works, a cross in
 * red when it was refused, amber otherwise; out of credits on OpenRouter offers Add credits.
 */
@Composable
private fun CheckVerdict(c: ProviderCheck, openRouter: Boolean) {
    val context = LocalContext.current
    val words = checkWords(context, c)
    val tone: Color = when (c.state) {
        ProviderCheck.OK -> HN.accent
        ProviderCheck.BAD_KEY -> HN.danger
        else -> HN.amber
    }
    val said = stringResource(R.string.pv_check_said, words)
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = said; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 3.dp).size(14.dp), contentAlignment = Alignment.Center) {
            Icon(if (c.state == ProviderCheck.OK) Glyph.Check else if (c.state == ProviderCheck.BAD_KEY) Glyph.Cross else Glyph.Key, size = 12.dp, color = tone, stroke = 1.6.dp)
        }
        Spacer(Modifier.width(8.dp))
        HText(words, style = Type.small, color = tone, modifier = Modifier.weight(1f))
    }
    if (c.state == ProviderCheck.NO_CREDIT && openRouter) {
        Spacer(Modifier.height(8.dp))
        Pill(stringResource(R.string.provider_add_credits), { com.prudnikweb.hypernova.data.openLink(context, OPENROUTER_CREDITS) }, kind = PillKind.Outline, height = 40.dp)
    }
}

/** A model slot: its field, and **List** beside it when the provider has a list to pick from. */
@Composable
private fun SlotField(label: String, value: String, onChange: (String) -> Unit, hint: String, listed: Boolean, onList: () -> Unit, last: Boolean = false) {
    FieldLabel(label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Field(
                value, onChange, placeholder = hint, mono = true, label = label,
                keyboard = KeyboardOptions(autoCorrectEnabled = false, imeAction = if (last) ImeAction.Done else ImeAction.Next),
            )
        }
        if (listed) {
            Spacer(Modifier.width(8.dp))
            val said = stringResource(R.string.pv_models_pick_said, label)
            Box(Modifier.semantics(mergeDescendants = true) { contentDescription = said }) {
                Pill(stringResource(R.string.pv_models_pick), onList, kind = PillKind.Ghost, height = 44.dp, leading = Glyph.Search)
            }
        }
    }
}

/** A provider's model ids to pick from, with a name where its list gives one. */
private data class ModelId(val id: String, val name: String?)

/**
 * The ids a slot can pick: this provider's list from the Mac (or another OpenRouter provider's,
 * the catalogue being the same), else what a custom endpoint listed when checked.
 */
private fun modelIds(lists: List<ModelList>?, providers: List<Provider>, id: String?, kind: String, check: ProviderCheck?): List<ModelId> {
    val own = lists?.firstOrNull { it.providerId == id }
    val sameKind = if (kind == "openrouter") lists?.firstOrNull { l -> providers.any { it.id == l.providerId && it.kind == "openrouter" } } else null
    val from = (own ?: sameKind)?.models.orEmpty()
        .mapNotNull { o -> (o.model ?: o.value)?.takeIf { it.isNotBlank() && it !in setOf("opus", "sonnet", "haiku", "fable") }?.let { ModelId(it, o.name.takeIf { n -> n != it }) } }
    val checked = check?.models.orEmpty().map { ModelId(it, null) }
    return (from + checked).distinctBy { it.id }
}

/** The provider's models, searchable; a tap puts the id in the slot. Any other id can still be typed. */
@Composable
private fun ModelIdSheet(providerName: String, ids: List<ModelId>, loading: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.pv_models_title, providerName)
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(ids, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) ids else ids.filter { it.id.lowercase().contains(q) || it.name?.lowercase()?.contains(q) == true }
    }
    GlassSheet(title, onDismiss, height = 600.dp, scroll = false) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(4.dp))
        HText(stringResource(R.string.pv_models_typed), style = Type.small, color = HN.inkDim)
        Spacer(Modifier.height(12.dp))
        SearchField(query, { query = it }, stringResource(R.string.pv_models_search))
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 14.dp)) {
            if (loading && ids.isEmpty()) item(key = "loading") { LoadingLine(stringResource(R.string.pv_models_loading), Modifier.padding(10.dp)) }
            if (!loading && found.isEmpty() && query.isNotBlank()) {
                item(key = "none") { HText(stringResource(R.string.pv_models_none, query.trim()), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp)) }
            }
            items(found, key = { it.id }) { m ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onPick(m.id) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    HText(m.name ?: m.id, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (m.name != null) HText(m.id, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                Hairline(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Delete's one line: the sessions and automations still on it, else what running ones do. */
private fun deleteLine(res: android.content.res.Resources, id: String, hub: Hub): String {
    val sessions = ProviderForm.sessionsOn(id, hub.sessions)
    val automations = ProviderForm.automationsOn(id, hub.automations.state?.list.orEmpty())
    val parts = listOfNotNull(
        sessions.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.pv_delete_sessions, it, it) },
        automations.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.pv_delete_automations, it, it) },
    )
    return parts.joinToString(" ").ifEmpty { res.getString(R.string.delete_provider_body) }
}
