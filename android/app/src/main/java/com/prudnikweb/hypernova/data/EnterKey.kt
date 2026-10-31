package com.prudnikweb.hypernova.data

/**
 * Settings → Typing → Enter key: what the keyboard's Enter does in a box that sends (the session's
 * prompt box, the New session prompt, /btw and a command's argument in the Commands tab, a quick
 * prompt being edited). [Send], the default: Enter sends, as the box's Send does, and on a hardware
 * keyboard Shift+Enter starts a new line. [NewLine]: Enter starts a new line, as before, and only
 * the box's button sends. Kept on this phone (ui/components/EnterKeyPref.kt).
 */
enum class EnterKey {
    Send, NewLine;

    companion object {
        val DEFAULT = Send

        /** The saved choice ([name]), or [DEFAULT] for nothing saved or a name this version doesn't know. */
        fun of(saved: String?): EnterKey = entries.firstOrNull { it.name == saved } ?: DEFAULT
    }
}

/** What one press of Enter does in such a box. */
enum class EnterDoes {
    /** The box sends (its button's own path). */
    Send,
    /** The field's own Enter: a new line. */
    NewLine,
    /** Swallowed: no send (nothing to send, or not yet) and no new line either. */
    Nothing,
}

object EnterKeys {
    /**
     * An Enter with the [setting] in force: [shift] held (a hardware keyboard's Shift+Enter, always
     * a new line), [canSend] whether the box's Send would send now (an empty box: nothing).
     * The soft keyboard's Send key is the same as an Enter without Shift.
     */
    fun decide(setting: EnterKey, shift: Boolean, canSend: Boolean): EnterDoes = when {
        setting == EnterKey.NewLine || shift -> EnterDoes.NewLine
        canSend -> EnterDoes.Send
        else -> EnterDoes.Nothing
    }

    /**
     * [after] is [before] with one "\n" typed into it and nothing else: a keyboard whose Enter puts a
     * new line in rather than pressing Send (some do in a box of several lines). Pasted text is never
     * this unless it is a lone new line.
     */
    fun typedNewline(before: CharSequence, after: CharSequence): Boolean {
        if (after.length != before.length + 1) return false
        var i = 0
        while (i < before.length && before[i] == after[i]) i++
        if (after[i] != '\n') return false
        for (j in i until before.length) if (before[j] != after[j + 1]) return false
        return true
    }
}
