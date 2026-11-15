package app.ewe.data

/** Labels on sessions, and how the session list is filtered and sorted by them. */
object Labels {
    const val MAX = 10
    const val MAX_LENGTH = 24

    /** A label as it is stored: trimmed, inner spaces collapsed, at most 24 characters; null if nothing is left. */
    fun clean(raw: String): String? =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim().ifEmpty { null }

    /** [existing] plus [raw], unless it is empty, already there (any capitals) or there are already 10. */
    fun add(existing: List<String>, raw: String): List<String> {
        val l = clean(raw) ?: return existing
        if (existing.size >= MAX || existing.any { it.equals(l, ignoreCase = true) }) return existing
        return existing + l
    }

    /** Every label in use, A–Z, with how many sessions have it. */
    fun counts(sessions: List<Session>): List<Pair<String, Int>> =
        sessions.flatMap { it.labels }.groupingBy { it.lowercase() }.eachCount().let { byKey ->
            val names = sessions.flatMap { it.labels }.groupBy { it.lowercase() }.mapValues { it.value.first() }   // the first spelling seen
            byKey.map { (k, n) -> names.getValue(k) to n }.sortedBy { it.first.lowercase() }
        }

    enum class Sort(val label: String) { UPDATED("Latest updated"), LABEL("Label"), TITLE("Title A–Z"), CREATED("Newest created") }

    /** The type / status chip: All, Topics, Projects, Inactive. */
    fun kind(s: Session, filter: String) = when (filter) {
        "Topics" -> s.isTopic
        "Projects" -> !s.isTopic
        "Inactive" -> !s.isActive
        "Completed" -> s.isCompleted
        else -> true
    }

    /**
     * The list to show: [filter] by type/status, then only sessions with [label] (if set), then in [sort]
     * order. By label means grouped under each session's first label A–Z, unlabelled last; inside a group,
     * latest updated first.
     */
    fun arrange(sessions: List<Session>, filter: String, label: String?, sort: Sort): List<Session> {
        val kept = sessions.filter { kind(it, filter) && (label == null || it.labels.any { l -> l.equals(label, ignoreCase = true) }) }
        val updated = compareByDescending<Session> { instantOf(it.updatedAt) }
        return when (sort) {
            Sort.UPDATED -> kept.sortedWith(updated)
            Sort.CREATED -> kept.sortedByDescending { instantOf(it.createdAt) }
            Sort.TITLE -> kept.sortedBy { it.title.lowercase() }
            Sort.LABEL -> kept.sortedWith(compareBy<Session> { it.labels.isEmpty() }.thenBy { it.labels.firstOrNull()?.lowercase().orEmpty() }.then(updated))
        }
    }

    /** The group a session sits in when sorted by label. */
    fun group(s: Session): String? = s.labels.firstOrNull()
}
