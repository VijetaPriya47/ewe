package app.ewe.data

/** Helpers for the richer note kinds (tables, code). */
object Rich {
    private val REF = Regex("\\[\\[ref:([0-9a-fA-F-]+)\\]\\]")
    private val SHOWN = Regex("\\[(\\d{1,4})\\]")

    /** Ids of the references a note cites, in order. */
    fun refIds(text: String): List<String> = REF.findAll(text).map { it.groupValues[1] }.toList()

    /** Text without its [[ref:…]] marks — for reading aloud, the podcast and previews. */
    fun stripRefs(text: String): String = text.replace(Regex("\\s*\\[\\[ref:[0-9a-fA-F-]+\\]\\]"), "").trim()

    /** [[ref:id]] → [n], for editing. A deleted reference becomes [?]. */
    fun refsToShown(text: String, snapshot: Snapshot): String =
        REF.replace(text) { m -> snapshot.reference(m.groupValues[1])?.let { "[${it.number}]" } ?: "[?]" }

    /** [n] → [[ref:id]] using the session's references; numbers that don't exist stay as typed. */
    fun shownToRefs(text: String, refs: List<Reference>): String {
        val byNumber = refs.associateBy { it.number }
        return SHOWN.replace(text.replace(" [?]", "").replace("[?]", "")) { m ->
            byNumber[m.groupValues[1].toInt()]?.let { "[[ref:${it.id}]]" } ?: m.value
        }
    }

    /** Splits a note into plain text and cited reference ids, for drawing [n] chips. */
    fun pieces(text: String): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        var last = 0
        REF.findAll(text).forEach { m ->
            if (m.range.first > last) out += text.substring(last, m.range.first) to null
            out += "" to m.groupValues[1]
            last = m.range.last + 1
        }
        if (last < text.length) out += text.substring(last) to null
        return out
    }

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
