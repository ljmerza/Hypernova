package com.prudnikweb.hypernova.ui.home

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.prudnikweb.hypernova.data.KeyValue
import com.prudnikweb.hypernova.data.AndroidPrefs
import com.prudnikweb.hypernova.data.PrefsKeyValue

/**
 * What one way of a swipe on a Home card does, picked in Settings → Card swipes (owner,
 * 2026-10-31). [Smart] is the swipe as first decided ([CardSwipe.of]); the rest are one action
 * each, swiping only the cards it fits ([CardSwipe.of] with a choice).
 */
enum class SwipeChoice {
    Smart, Open, Stop, Delete, Rename, Restart, MarkRead, Nothing;

    companion object {
        /** The saved choice ([name]), or [default] for nothing saved or a name this version doesn't know. */
        fun of(saved: String?, default: SwipeChoice): SwipeChoice = entries.firstOrNull { it.name == saved } ?: default
    }
}

/** Both ways' picks. By default left is [SwipeChoice.Smart] and right [SwipeChoice.Nothing], the swipe as it was. */
data class CardSwipes(val left: SwipeChoice = SwipeChoice.Smart, val right: SwipeChoice = SwipeChoice.Nothing) {
    fun save(kv: KeyValue) {
        kv.put(LEFT, left.name)
        kv.put(RIGHT, right.name)
    }

    companion object {
        val DEFAULT = CardSwipes()
        private const val LEFT = "card_swipe_left"
        private const val RIGHT = "card_swipe_right"

        fun load(kv: KeyValue): CardSwipes = CardSwipes(
            left = SwipeChoice.of(kv.get(LEFT), DEFAULT.left),
            right = SwipeChoice.of(kv.get(RIGHT), DEFAULT.right),
        )
    }
}

/**
 * Settings → Card swipes, kept on this phone. One state for the whole app, so a change reaches Home
 * at once (as TopBarPref).
 */
object CardSwipePref {
    private const val PREFS = "hypernova_ui"
    private var current: MutableState<CardSwipes>? = null

    fun state(context: Context): State<CardSwipes> = holder(context)

    fun set(context: Context, value: CardSwipes) {
        value.save(store(context))
        holder(context).value = value
    }

    private fun store(context: Context): KeyValue = PrefsKeyValue(AndroidPrefs(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

    private fun holder(context: Context): MutableState<CardSwipes> = current
        ?: mutableStateOf(CardSwipes.load(store(context))).also { current = it }
}
