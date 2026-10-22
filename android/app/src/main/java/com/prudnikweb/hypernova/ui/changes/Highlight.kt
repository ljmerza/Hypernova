package com.prudnikweb.hypernova.ui.changes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.prudnikweb.hypernova.ui.theme.HN

/**
 * A light syntax colouring for one line of code: comments, strings, numbers and the keywords of
 * the usual languages. It reads a line at a time, so a block comment or a template string that
 * runs over lines is coloured only on the lines that show its start or end; good enough for a
 * diff, which is read in small hunks anyway.
 */
object Highlight {
    private val KEYWORD = HN.accentBright
    private val STRING = Color(0xFFE8B341).copy(alpha = 0.95f)
    private val NUMBER = Color(0xFFF2C6A0)
    private val COMMENT = HN.inkDim

    private val WORDS = setOf(
        "abstract", "as", "async", "await", "break", "case", "catch", "class", "companion", "const", "constructor", "continue",
        "data", "def", "default", "defer", "do", "else", "enum", "export", "extends", "extension", "false", "final", "finally",
        "fn", "for", "from", "fun", "func", "function", "guard", "if", "impl", "implements", "import", "in", "init", "interface",
        "internal", "is", "lambda", "let", "match", "mod", "mut", "new", "null", "nil", "object", "open", "operator", "override",
        "package", "private", "protected", "pub", "public", "return", "sealed", "self", "static", "struct", "super", "suspend",
        "switch", "this", "throw", "throws", "trait", "true", "try", "type", "typealias", "typeof", "undefined", "use", "val",
        "var", "void", "when", "where", "while", "with", "yield", "None", "True", "False", "elif", "except", "pass", "raise",
        "echo", "fi", "then", "done", "esac", "select", "insert", "update", "delete", "create", "table", "values", "set",
    )

    private val HASH_COMMENT = setOf("py", "sh", "bash", "zsh", "rb", "yml", "yaml", "toml", "conf", "ini", "env", "pl", "r", "mk", "dockerfile")
    private val SLASH_COMMENT = setOf(
        "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "swift", "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs",
        "gradle", "dart", "scala", "php", "css", "scss", "json5", "vue", "svelte", "m", "mm",
    )
    private val PLAIN = setOf("md", "txt", "csv", "log", "rst", "")

    private val regexes = HashMap<String, Regex>()

    private fun regexFor(ext: String): Regex? {
        if (ext in PLAIN) return null
        val comment = when {
            ext in HASH_COMMENT -> """#.*$"""
            ext in SLASH_COMMENT -> """//.*$|/\*.*?(?:\*/|$)|^\s*\*.*$"""
            ext == "sql" || ext == "lua" -> """--.*$"""
            ext == "html" || ext == "xml" || ext == "svg" -> """<!--.*?(?:-->|$)"""
            else -> """//.*$|#.*$"""
        }
        return regexes.getOrPut(comment) {
            Regex("""($comment)|("(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|`(?:\\.|[^`\\])*`?)|(\b0x[0-9a-fA-F]+\b|\b\d[\d_]*(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)""")
        }
    }

    fun extOf(path: String): String = path.substringAfterLast('/').let { n ->
        if (n.equals("Dockerfile", true)) "dockerfile" else n.substringAfterLast('.', "").lowercase()
    }

    /** [text] coloured for a file with extension [ext]. */
    fun line(text: String, ext: String): AnnotatedString {
        val re = regexFor(ext) ?: return AnnotatedString(text)
        // A very long line (minified code) is not worth scanning.
        if (text.length > 600) return AnnotatedString(text)
        return buildAnnotatedString {
            var at = 0
            for (m in re.findAll(text)) {
                val color = when {
                    m.groups[1] != null -> COMMENT
                    m.groups[2] != null -> STRING
                    m.groups[3] != null -> NUMBER
                    m.groups[4]!!.value in WORDS -> KEYWORD
                    else -> null
                } ?: continue
                if (m.range.first > at) append(text.substring(at, m.range.first))
                withStyle(SpanStyle(color = color)) { append(m.value) }
                at = m.range.last + 1
            }
            if (at < text.length) append(text.substring(at))
        }
    }
}
