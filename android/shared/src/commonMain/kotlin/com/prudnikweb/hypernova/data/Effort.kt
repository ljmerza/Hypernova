package com.prudnikweb.hypernova.data

/**
 * Claude Code's reasoning effort (docs/PROTOCOL.md, "A session's effort"): `--effort` when a session
 * starts (the new-session sheet's Effort chip), `/effort` typed in while it runs (the session sheet's
 * Overview). Codex's efforts are its models' own (data/Codex.kt) and stay apart.
 */
object ClaudeEffort {
    /** Its levels, lowest first (`claude --help` of 2.1.314). */
    val LEVELS = listOf("low", "medium", "high", "xhigh", "max")

    /** A remembered level this build knows, else null: Claude Code's own default (nothing sent). */
    fun known(level: String?): String? = level?.takeIf { it in LEVELS }

    /**
     * Session [s]'s effort can be changed from the phone: a Claude Code session the permission mode
     * can be changed in ([modeChangeable]: open, and in a terminal the Mac holds, run by Hypernova, or
     * a `claude agents` job), since the Mac types `/effort` the way it switches the mode.
     */
    fun changeable(s: Session, modeChangeable: Boolean): Boolean = !s.codex && s.phase != Phase.Ended && modeChangeable

    /** The new-session sheet's Effort chip: on the Claude subscription only ([provider] null or the built-in one), never OpenRouter or a custom provider. */
    fun offered(provider: Provider?): Boolean = provider == null || provider.builtIn

    /** The session sheet's Effort part: a Claude Code session on the subscription (Codex's goes with its model). */
    fun shown(s: Session): Boolean = !s.codex && !s.onOtherProvider
}
