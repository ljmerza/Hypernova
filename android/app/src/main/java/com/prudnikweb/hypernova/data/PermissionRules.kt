package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Claude Code permission rules in effect for a session (docs/PROTOCOL.md, "Rules in effect";
 * IDEAS SS5), read-only, highest precedence first: the managed policy, the session's own (from
 * Hypernova), the project's `settings.local.json` and `settings.json`, the user's. The Mac sends
 * only the permissions lists, never the files. [managedOnly]: the managed policy keeps rules to
 * itself, so the others don't apply.
 */
@Immutable
data class PermissionRules(val sources: List<RuleSource>, val managedOnly: Boolean)

/**
 * One source's rules. [state]: [OK], or why it has none: [MISSING], [UNREADABLE], [INVALID] (not
 * JSON), [TOO_BIG]. [clipped]: the Mac left some out, or cut them. [path]: null for the session's own.
 */
@Immutable
data class RuleSource(
    val source: String,
    val path: String?,
    val state: String,
    val allow: List<String>,
    val ask: List<String>,
    val deny: List<String>,
    val defaultMode: String?,
    val additionalDirectories: List<String>,
    val clipped: Boolean,
) {
    val ok: Boolean get() = state == OK
    val count: Int get() = allow.size + ask.size + deny.size

    companion object {
        const val MANAGED = "managed"
        const val SESSION = "session"
        const val LOCAL = "local"
        const val PROJECT = "project"
        const val USER = "user"

        const val OK = "ok"
        const val MISSING = "missing"
        const val UNREADABLE = "unreadable"
        const val INVALID = "invalid"
        const val TOO_BIG = "too_big"
    }
}

object PermissionRulesWire {
    /** `{permissions: {sources, managedOnly}}`; an unknown source is skipped. */
    fun of(o: JSONObject): PermissionRules {
        val p = o.obj("permissions") ?: return PermissionRules(emptyList(), false)
        val list = p.arr("sources")
        val sources = buildList {
            for (i in 0 until (list?.length() ?: 0)) list?.optJSONObject(i)?.let(::source)?.let(::add)
        }
        return PermissionRules(sources, p.bool("managedOnly"))
    }

    private val KNOWN = setOf(RuleSource.MANAGED, RuleSource.SESSION, RuleSource.LOCAL, RuleSource.PROJECT, RuleSource.USER)

    private fun source(o: JSONObject): RuleSource? {
        val source = o.str("source")?.takeIf { it in KNOWN } ?: return null
        return RuleSource(
            source = source,
            path = o.str("path")?.takeIf { it.isNotBlank() },
            state = o.str("state") ?: RuleSource.UNREADABLE,
            allow = strings(o.arr("allow")),
            ask = strings(o.arr("ask")),
            deny = strings(o.arr("deny")),
            defaultMode = o.str("defaultMode")?.takeIf { it.isNotBlank() },
            additionalDirectories = strings(o.arr("additionalDirectories")),
            clipped = o.bool("clipped"),
        )
    }

    private fun strings(a: JSONArray?): List<String> = buildList {
        for (i in 0 until (a?.length() ?: 0)) (a?.opt(i) as? String)?.takeIf { it.isNotBlank() }?.let(::add)
    }
}
