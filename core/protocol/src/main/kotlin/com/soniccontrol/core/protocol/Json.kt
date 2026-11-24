package com.soniccontrol.core.protocol

/**
 * Minimal JSON writer (no reflection, no dependencies) used for the diagnostics export.
 * Supports String, Number, Boolean, null, Map<String, *>, Iterable<*> and arrays of the same.
 */
object Json {
    fun stringify(value: Any?, indent: Int = 2): String = StringBuilder().also { write(it, value, indent, 0) }.toString()

    private fun write(sb: StringBuilder, value: Any?, indent: Int, level: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(value)
            is Double -> sb.append(if (value.isFinite()) value.toString() else "null")
            is Float -> sb.append(if (value.isFinite()) value.toString() else "null")
            is Number -> sb.append(value.toString())
            is Map<*, *> -> writeContainer(sb, '{', '}', value.entries.toList(), indent, level) { e ->
                quote(sb, e.key.toString()); sb.append(if (indent > 0) ": " else ":"); write(sb, e.value, indent, level + 1)
            }
            is Iterable<*> -> writeContainer(sb, '[', ']', value.toList(), indent, level) { write(sb, it, indent, level + 1) }
            is Array<*> -> write(sb, value.toList(), indent, level)
            is IntArray -> write(sb, value.toList(), indent, level)
            else -> quote(sb, value.toString())
        }
    }

    private inline fun <T> writeContainer(
        sb: StringBuilder, open: Char, close: Char, items: List<T>, indent: Int, level: Int, item: (T) -> Unit,
    ) {
        sb.append(open)
        if (items.isEmpty()) { sb.append(close); return }
        items.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            newline(sb, indent, level + 1)
            item(it)
        }
        newline(sb, indent, level)
        sb.append(close)
    }

    private fun newline(sb: StringBuilder, indent: Int, level: Int) {
        if (indent <= 0) return
        sb.append('\n')
        repeat(indent * level) { sb.append(' ') }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
