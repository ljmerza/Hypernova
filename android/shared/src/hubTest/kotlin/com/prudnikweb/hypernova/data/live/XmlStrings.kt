package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's strings read straight from its res/values files, as aapt compiles them, so the tests off
 * Android compare the very words the app shows. StringsTableTest checks them against Android's own
 * Resources, every key, so this can't drift from what a phone shows.
 */
object XmlStrings : Strings {
    private val dir: File by lazy {
        listOf("src/main/res/values", "../app/src/main/res/values").map(::File).first { it.isDirectory }
    }
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (file in dir.listFiles().orEmpty().filter { it.name.startsWith("strings") && it.name.endsWith(".xml") }) {
            val root = builder.parse(file).documentElement
            val items = root.childNodes
            for (i in 0 until items.length) {
                val e = items.item(i) as? Element ?: continue
                when (e.tagName) {
                    "string" -> strings[e.getAttribute("name")] = decode(e.textContent)
                    "plurals" -> {
                        val q = e.getElementsByTagName("item")
                        plurals[e.getAttribute("name")] = (0 until q.length).map { q.item(it) as Element }.associate { it.getAttribute("quantity") to decode(it.textContent) }
                    }
                }
            }
        }
    }

    /** A resource string's text as aapt compiles it: its escapes done, spaces collapsed outside double quotes. */
    fun decode(raw: String): String {
        val out = StringBuilder()
        var quoted = false
        var space = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    if (space) { if (out.isNotEmpty()) out.append(' '); space = false }
                    i++
                    when (val n = raw[i]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> { out.append(raw.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> out.append(n)
                    }
                }
                c == '"' -> quoted = !quoted
                !quoted && c.isWhitespace() -> space = true
                else -> {
                    if (space) { if (out.isNotEmpty()) out.append(' '); space = false }
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    override fun text(key: Str): String = checkNotNull(strings[key.name]) { "no string ${key.name} in $dir" }

    override fun quantityText(key: Plural, quantity: Int): String {
        val q = checkNotNull(plurals[key.name]) { "no plurals ${key.name} in $dir" }
        return checkNotNull(q[if (quantity == 1) "one" else "other"] ?: q["other"]) { "no quantity for ${key.name}" }
    }

    override val locale: Locale get() = Locale.US

    override fun timePattern(withDay: Boolean): String = (if (withDay) "EEE " else "") + "h:mm a"
}
