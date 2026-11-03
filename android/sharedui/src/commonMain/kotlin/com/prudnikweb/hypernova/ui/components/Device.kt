package com.prudnikweb.hypernova.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape

/*
 * What the shared drawing asks of the device it's on, each in the words of what it's for. On the phone each is the call the
 * screen always made; a desktop has no haptics and its own clipboard, and it has a pointer and a keyboard to show a button to.
 */

/** A small physical answer to a press. The phone's vibrations; nothing on a desktop. */
interface Haptics {
    /** A long press took. */
    fun longPress()
    /** A button's light tick. */
    fun tick()
    /** Something weighty went: an Allow, a plan approved. */
    fun confirm()
}

@Composable
expect fun rememberHaptics(): Haptics

/** The system's clipboard. */
interface Copier {
    fun copy(label: String, text: String)
    /** [words] where the system doesn't show its own word for it. */
    fun sayCopied(words: String)
}

@Composable
expect fun rememberCopier(): Copier

/** How tall the screen or the window is: the share an approval may take of it goes by this. */
@Composable
expect fun windowHeightDp(): Int

/** A pill under a pointer: a hand over it. Nothing on the phone. */
@Composable
expect fun Modifier.pillPointer(interaction: MutableInteractionSource, enabled: Boolean): Modifier

/** A pill with the keyboard on it: a ring. Nothing on the phone, which has no such focus to show. */
@Composable
expect fun pillFocusRing(interaction: MutableInteractionSource, shape: Shape): Modifier

/** The glow round a filled capsule: its own shape, blurred, outside it. */
internal expect fun Paint.blurredOutside(radius: Float)

/** A code block's long press that copies it whole. A desktop's mouse is for selecting: there a held press is the start of a selection. */
expect fun Modifier.longPressCopies(key: Any, copy: () -> Unit): Modifier

/** A closed ```mermaid block: the phone draws the diagram. */
@Composable
internal expect fun DiagramBlock(source: String)

/** Output or a plan a mouse can select, on a desktop. The phone's chat leaves these to a long press on the row, so there it's only [content]. */
@Composable
internal expect fun SelectableOutput(content: @Composable () -> Unit)
