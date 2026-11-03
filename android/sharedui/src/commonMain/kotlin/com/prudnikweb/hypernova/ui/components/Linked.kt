package com.prudnikweb.hypernova.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.prudnikweb.hypernova.data.Links
import com.prudnikweb.hypernova.ui.theme.HN

/** A link in the chat: mint, underlined, so it reads as one before it's tapped. */
internal val LinkStyles = TextLinkStyles(style = SpanStyle(color = HN.accentBright, textDecoration = TextDecoration.Underline))

/** [text] with its bare web addresses as links (data/Links.kt); one that never opens stays text. */
fun AnnotatedString.Builder.appendLinked(text: String) {
    var at = 0
    for (f in Links.find(text)) {
        append(text.substring(at, f.range.first))
        if (Links.target(f.url) == Links.Target.Never) append(f.url) else withLink(LinkAnnotation.Url(f.url, LinkStyles)) { append(f.url) }
        at = f.range.last + 1
    }
    append(text.substring(at))
}

/** [text] as drawn in the chat: its web addresses tappable. */
fun linked(text: String): AnnotatedString =
    if (Links.find(text).isEmpty()) AnnotatedString(text) else buildAnnotatedString { appendLinked(text) }

/** [url] for the confirm: the scheme and host in ink, what's before an "@" and after the host dimmer. */
fun linkShown(url: String): AnnotatedString {
    val p = Links.parts(url) ?: return AnnotatedString(url)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = HN.ink)) { append(p.scheme) }
        if (p.userInfo.isNotEmpty()) withStyle(SpanStyle(color = HN.inkDim)) { append(p.userInfo) }
        withStyle(SpanStyle(color = HN.accentBright, fontWeight = FontWeight(600))) { append(p.host) }
        withStyle(SpanStyle(color = HN.inkDim)) { append(p.rest) }
    }
}
