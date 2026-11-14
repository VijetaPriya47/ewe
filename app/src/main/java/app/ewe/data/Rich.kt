package app.ewe.data

/** Helpers for the richer note kinds (tables, code). */
object Rich {
    data class Table(val header: List<String>, val rows: List<List<String>>) {
        val columns: Int get() = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    }

    private fun cells(line: String): List<String> {
        var l = line.trim()
        if (l.startsWith("|")) l = l.drop(1)
        if (l.endsWith("|") && !l.endsWith("\\|")) l = l.dropLast(1)
        return l.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
    }

    private val separator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    /** Reads a Markdown table. Returns null if the text is not one. */
    fun parseTable(markdown: String): Table? {
        val lines = markdown.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains('|') }
        if (lines.size < 2) return null
        val header = cells(lines[0])
        val body = lines.drop(1).filterNot { separator.matches(it) }.map(::cells)
        return Table(header, body)
    }

    /** A table spoken row by row: "Easy: clues 36. Hard: clues 22." */
    fun tableAsText(markdown: String): String {
        val t = parseTable(markdown) ?: return markdown
        return t.rows.joinToString(" ") { row ->
            val first = row.firstOrNull().orEmpty()
            val rest = row.drop(1).mapIndexedNotNull { i, cell ->
                val name = t.header.getOrNull(i + 1).orEmpty()
                if (cell.isBlank()) null else if (name.isBlank()) cell else "$name $cell"
            }
            (if (rest.isEmpty()) first else "$first: ${rest.joinToString(", ")}").trimEnd('.') + "."
        }
    }
}
