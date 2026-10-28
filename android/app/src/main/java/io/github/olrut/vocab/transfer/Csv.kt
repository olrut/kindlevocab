package io.github.olrut.vocab.transfer

/** RFC 4180 CSV: comma-separated, `"`-quoted, `""` for a quote inside a quoted field. */
object Csv {
    class CsvException(message: String) : Exception(message)

    // Leading/trailing whitespace is quoted too, so a trimming reader cannot lose it.
    private val NEEDS_QUOTES = Regex("""[",\r\n]|^\s|\s$""")

    /** Rows to CSV text with CRLF line ends. Null becomes an empty cell. */
    fun write(rows: List<List<String?>>): String = buildString {
        for (row in rows) {
            append(row.joinToString(",") { cell -> if (cell != null && NEEDS_QUOTES.containsMatchIn(cell)) "\"" + cell.replace("\"", "\"\"") + "\"" else cell ?: "" })
            append("\r\n")
        }
    }

    /** CSV text to rows. Accepts CRLF or LF, skips a leading BOM and blank lines. Throws [CsvException]. */
    fun parse(text: String): List<List<String>> {
        val s = text.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        fun endField() {
            row.add(field.toString())
            field.clear()
        }
        fun endRow() {
            // A blank line is a single unquoted empty field.
            if (row.isNotEmpty() || field.isNotEmpty() || quoted) {
                endField()
                rows.add(row)
            }
            row = mutableListOf()
            field.clear()
            quoted = false
        }

        while (i < s.length) {
            val ch = s[i]
            if (ch == '"' && field.isEmpty() && !quoted) {
                quoted = true
                i++
                while (true) {
                    val end = s.indexOf('"', i)
                    if (end == -1) throw CsvException("unterminated quoted field")
                    field.append(s, i, end)
                    i = end + 1
                    if (i >= s.length || s[i] != '"') break
                    field.append('"')
                    i++
                }
                if (i < s.length && s[i] !in ",\r\n") throw CsvException("text after a closing quote")
            } else if (ch == ',') {
                endField()
                quoted = false
                i++
            } else if (ch == '\r' || ch == '\n') {
                endRow()
                i += if (ch == '\r' && i + 1 < s.length && s[i + 1] == '\n') 2 else 1
            } else {
                field.append(ch)
                i++
            }
        }
        endRow()
        return rows
    }
}
