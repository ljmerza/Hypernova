package com.prudnikweb.hypernova.widget

/**
 * Who's working and who needs you on a widget, as the app's count marks draw them: each count after
 * its state's point of light, the counts a gap apart ("● 2 working  ● 1 needs you"), never
 * "·"-joined. A widget can only draw text in its lines, so the point is a character, smaller than
 * the words and in its state's colour (WidgetViews). Pure, unit-tested.
 */
internal object WidgetCounts {
    /** The point of light. */
    const val POINT = "●"
    /** Its size beside the words (raised to their middle by a superscript, so it's no bullet on their baseline). */
    const val POINT_SIZE = 0.5f
    /** Between the point and its words: a thin space, so the point belongs to them. */
    const val AFTER_POINT = " "
    /** Between one count and the next, or the lead and the first count: two en spaces. */
    const val GAP = "  "

    /** The counts as plain text, the width they take: "● 2 working  ● 1 needs you". */
    fun plain(parts: List<String>): String = parts.joinToString(GAP) { POINT + AFTER_POINT + it }
}
