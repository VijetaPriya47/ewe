package app.ewe.data

/**
 * Keyword search over the copy on the phone, so it works offline:
 * session titles, section titles and note text. Every word of the query must appear.
 */
object Search {
    data class NoteHit(val session: Session, val section: Section, val point: Point, val from: Session?)
    data class Results(val sessions: List<Session>, val notes: List<NoteHit>)

    fun run(s: Snapshot, query: String, limit: Int = 100): Results {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Results(emptyList(), emptyList())
        fun String.hit() = lowercase().let { t -> words.all { it in t } }

        val sessions = s.sessions.filter { it.title.hit() }.sortedWith(compareByDescending<Session> { it.isActive }.thenByDescending { it.updatedAt })
        // Best first: the note itself matches, then its section title, then only its session.
        val ranked = ArrayList<Pair<Int, NoteHit>>()
        for (session in s.sessions.sortedWith(compareByDescending<Session> { it.isActive }.thenByDescending { it.updatedAt })) {
            for (section in s.sectionsOf(session.id)) {
                for (p in s.points(section.id)) {
                    val from = p.link?.let { s.session(s.section(p.bullet.sectionId)?.sessionId) }
                    val rank = when {
                        p.bullet.content.hit() -> 0
                        section.title.hit() -> 1
                        "${session.title} ${section.title} ${p.bullet.content} ${from?.title.orEmpty()}".hit() -> 2
                        else -> continue
                    }
                    ranked += rank to NoteHit(session, section, p, from)
                }
            }
        }
        val notes = ranked.sortedBy { it.first }.take(limit).map { it.second }
        return Results(sessions, notes)
    }
}
