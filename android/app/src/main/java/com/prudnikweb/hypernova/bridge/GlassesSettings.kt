package com.prudnikweb.hypernova.bridge

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the glasses do by themselves, kept in this app now (the owner: "the glasses app should be
 * simple, because all the visuals are in the app for a phone"). Settings → Glasses edits them; Lens
 * reads them through the bridge when it connects and hears each change on its live feed, and the
 * few the glasses change by voice ("mute", "moving", "eclipse") come back the same way. Lens keeps
 * its own copy as a cache. docs/PROTOCOL.md, "The phone bridge", has the JSON.
 *
 * The new-session choice ([newSession]) is the phone's own: Lens uses it and can't change it.
 *
 * Not here: the speech companion's address (Lens's own), and Lens's own memory of having offered its
 * guide. Nor the voice and speed of read-aloud: this phone plays the glasses' speech with the Mac's
 * voice and speed, which Settings → Voice → Read aloud sets for the phone and the glasses at once
 * (Settings → Glasses says so). Lens's own `voice` and `rate` stay "automatic" and 1× in the public
 * build, which means the same Mac setting when the glasses have to read by themselves; only the
 * phone's own voice, Lens's last resort, then reads at 1×.
 */
@Immutable
data class GlassesSettings(
    /** Replies are read aloud: a tap on a result, "read the reply", "status". "mute"/"unmute" flip it. */
    val readAloud: Boolean = true,
    /** A soft tone in the earbuds when a session finishes or needs you. */
    val blip: Boolean = true,
    /** "all": text shows at once; "speech": a few words at a time, at [pace]. */
    val textArrives: String = "all",
    /** Words a minute while text arrives like speech. */
    val pace: Int = PACE_DEFAULT,
    /** Which pop-ups the glasses show, by kind. */
    val popups: Map<String, Boolean> = DEFAULT_POPS,
    /** On a bike or a walk: two short lines, risky commands by voice only. */
    val moving: Boolean = false,
    /** Only what needs you gets through. */
    val eclipse: Boolean = false,
    /** Projects whose sessions the glance shows; null: every active one. */
    val projects: List<String>? = null,
    /** The language the glasses' speech is heard in: an ISO code, or "auto". */
    val language: String = "en",
    /**
     * Type on the lens: "native" (the glasses' built-in, firmware font: the default) or "hypernova" (the
     * app's own, drawn and sent as pictures). Lens's lens/look.ts. The owner, 2026-10-18, on real
     * glasses: "let's use firmware text on glasses, because responsiveness really sucks with images".
     */
    val look: String = LOOK_DEFAULT,
    /**
     * "Practise the gestures on the glasses": when it was last asked for (epoch ms, 0: never). Lens
     * plays its guide when this moves on while it's connected (not for an old ask on reconnecting).
     */
    val guideAsk: Long = 0,
    /**
     * The provider and model this phone's Start-a-session sheet last used (never its permission mode):
     * Lens starts its own new sessions on them while the Mac still lists that provider. Null: the
     * Mac's defaults. The phone's alone: a change from Lens is ignored.
     */
    val newSession: NewSession? = null,
) {
    /** A new session's provider and model ([model] null: the provider's default). */
    @Immutable
    data class NewSession(val providerId: String, val model: String?) {
        fun toJson(): JSONObject = JSONObject().put("providerId", providerId).put("model", model ?: JSONObject.NULL)

        companion object {
            private val PROVIDER = Regex("^[A-Za-z0-9][\\w.-]{0,63}$")
            private val MODEL = Regex("^[\\x21-\\x7e][\\x20-\\x7e]{0,199}$")

            /** Checked as Lens checks it (lens/src/data/bridge.ts, readNewSession): null when it isn't one. */
            fun of(providerId: String?, model: String?): NewSession? {
                val p = providerId?.takeIf { PROVIDER.matches(it) } ?: return null
                return NewSession(p, model?.trim()?.takeIf { MODEL.matches(it) })
            }

            fun parse(o: Any?): NewSession? = (o as? JSONObject)?.let { of(it.opt("providerId") as? String, it.opt("model") as? String) }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("readAloud", readAloud)
        .put("blip", blip)
        .put("textArrives", textArrives)
        .put("pace", pace)
        .put("popups", JSONObject().apply { for (k in POP_KINDS) put(k, popups[k] ?: DEFAULT_POPS.getValue(k)) })
        .put("moving", moving)
        .put("eclipse", eclipse)
        .put("projects", projects?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("language", language)
        .put("look", look)
        .put("guideAsk", guideAsk)
        .put("newSession", newSession?.toJson() ?: JSONObject.NULL)

    /**
     * This with [patch] applied: only the keys it knows, each checked (out of range is brought in,
     * a wrong type is ignored), so nothing Lens sends can put the app in a state it can't show.
     */
    fun merge(patch: JSONObject, own: Boolean = false): GlassesSettings {
        var s = this
        // The new-session choice is the phone's: read back from its own store, never taken from Lens.
        if (own && patch.has("newSession")) s = s.copy(newSession = NewSession.parse(patch.opt("newSession")))
        fun bool(k: String): Boolean? = if (patch.has(k) && patch.opt(k) is Boolean) patch.getBoolean(k) else null
        bool("readAloud")?.let { s = s.copy(readAloud = it) }
        bool("blip")?.let { s = s.copy(blip = it) }
        bool("moving")?.let { s = s.copy(moving = it) }
        bool("eclipse")?.let { s = s.copy(eclipse = it) }
        (patch.opt("textArrives") as? String)?.takeIf { it == "all" || it == "speech" }?.let { s = s.copy(textArrives = it) }
        (patch.opt("pace") as? Number)?.let { s = s.copy(pace = it.toInt().coerceIn(PACE_MIN, PACE_MAX)) }
        (patch.opt("popups") as? JSONObject)?.let { p ->
            val next = s.popups.toMutableMap()
            for (k in POP_KINDS) if (p.opt(k) is Boolean) next[k] = p.getBoolean(k)
            s = s.copy(popups = next)
        }
        if (patch.has("projects")) {
            when (val p = patch.opt("projects")) {
                JSONObject.NULL -> s = s.copy(projects = null)
                is JSONArray -> s = s.copy(projects = (0 until p.length()).mapNotNull { (p.opt(it) as? String)?.trim()?.take(80)?.takeIf { n -> n.isNotEmpty() } }.distinct().take(64))
            }
        }
        (patch.opt("language") as? String)?.trim()?.lowercase()?.takeIf { LANGUAGE.matches(it) }?.let { s = s.copy(language = it) }
        (patch.opt("look") as? String)?.takeIf { it in LOOKS }?.let { s = s.copy(look = it) }
        (patch.opt("guideAsk") as? Number)?.toLong()?.takeIf { it >= 0 }?.let { s = s.copy(guideAsk = it) }
        return s
    }

    companion object {
        const val PACE_MIN = 110
        const val PACE_MAX = 260
        const val PACE_DEFAULT = 170
        /** Lens's pop-up kinds (lens/src/core/popups.ts), in the order the phone lists them. */
        val POP_KINDS = listOf("needs", "done", "stopped", "limit", "battery", "sleep", "note")
        /** As Lens starts: what needs you, the Mac's two warnings and an automation's news on. */
        val DEFAULT_POPS = mapOf("needs" to true, "done" to false, "stopped" to false, "limit" to false, "battery" to true, "sleep" to true, "note" to true)
        /** The languages Lens offers for speech: an ISO code, or "auto". */
        val LANGUAGES = listOf("en", "auto", "ru", "de", "fr", "es", "it", "uk")
        const val LOOK_DEFAULT = "native"
        val LOOKS = listOf("hypernova", "native")
        /**
         * The version of the settings as this app keeps them (LensBridge: the prefs' `settingsVersion`).
         * 2 (2026-10-18): the glasses' own font became the default look.
         */
        const val STORE_VERSION = 2
        private val LANGUAGE = Regex("^(auto|[a-z]{2,3})$")

        fun parse(o: JSONObject?): GlassesSettings = if (o == null) GlassesSettings() else GlassesSettings().merge(o, own = true)
        fun parse(text: String?): GlassesSettings = parse(text?.let { runCatching { JSONObject(it) }.getOrNull() })

        /**
         * The settings kept as [text] by [version] of them. Before version 2 the drawn look was the
         * default and every save wrote it, chosen or not, so a look kept from then takes today's
         * default, the glasses' own font; "Type on the lens" chooses the drawn one again.
         */
        fun stored(text: String?, version: Int): GlassesSettings =
            parse(text).let { if (version < STORE_VERSION) it.copy(look = LOOK_DEFAULT) else it }
    }
}
