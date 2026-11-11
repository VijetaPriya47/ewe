package app.ewe.podcast

import app.ewe.data.Snapshot
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Detail fades as the gap grows: [perLevel] revisions at each level. */
enum class Level(val label: String) {
    DETAILED("Detailed"), SHORTER("Shorter"), KEY_POINTS("Key points"), HEADLINES("Headlines");

    companion object {
        /** revisionNo is 1-based: the 1st and 2nd revisions are Detailed when perLevel = 2. */
        fun of(revisionNo: Int, perLevel: Int): Level =
            entries[((revisionNo - 1).coerceAtLeast(0) / perLevel.coerceAtLeast(1)).coerceAtMost(entries.lastIndex)]
    }
}

/** One batch of notes that is due: a section plus the day its notes were added. */
data class DueItem(
    val scheduleId: String,
    val sessionId: String,
    val topic: String,
    val section: String,
    val sectionOrder: Double,
    val addedDate: String,
    val intervalDays: Int,
    val revisionNo: Int,
    val level: Level,
    val notes: List<String>,
) {
    val noteWords: Int get() = notes.sumOf { Planner.words(it) }
}

data class Segment(val item: DueItem, val wordBudget: Int) {
    val minutes: Double get() = wordBudget / Planner.WORDS_PER_MINUTE.toDouble()
}

data class Plan(val segments: List<Segment>, val deferred: List<DueItem>, val targetMinutes: Int) {
    val totalWords: Int get() = if (segments.isEmpty()) 0 else segments.sumOf { it.wordBudget } + Planner.INTRO_OUTRO_WORDS
    val minutes: Int get() = ceil(totalWords / Planner.WORDS_PER_MINUTE.toDouble()).toInt()
}

/**
 * Fits everything due today into one episode. Plain arithmetic, no AI:
 * the model is only told how many words each section gets.
 */
object Planner {
    const val WORDS_PER_MINUTE = 150
    const val INTRO_OUTRO_WORDS = 150
    private const val HEADLINE_IDEAL = 15
    private const val HEADLINE_MIN = 10
    private val IDEAL = mapOf(Level.DETAILED to 1.5, Level.SHORTER to 0.8, Level.KEY_POINTS to 0.4)
    private val MIN = mapOf(Level.DETAILED to 0.8, Level.SHORTER to 0.4, Level.KEY_POINTS to 0.2)

    fun words(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    fun ideal(item: DueItem): Int =
        if (item.level == Level.HEADLINES) HEADLINE_IDEAL else maxOf(HEADLINE_IDEAL, (item.noteWords * IDEAL.getValue(item.level)).roundToInt())

    fun minimum(item: DueItem): Int =
        if (item.level == Level.HEADLINES) HEADLINE_MIN else maxOf(HEADLINE_MIN, (item.noteWords * MIN.getValue(item.level)).roundToInt())

    /** Everything with next_due_date on or before today, from Active sessions, that still has notes. */
    fun due(s: Snapshot, today: LocalDate, perLevel: Int, exclude: Set<String> = emptySet()): List<DueItem> =
        s.schedule.mapNotNull { r ->
            if (r.id in exclude || r.nextDueDate > today.toString()) return@mapNotNull null
            val section = s.section(r.sectionId) ?: return@mapNotNull null
            val session = s.session(section.sessionId)?.takeIf { it.isActive } ?: return@mapNotNull null
            val notes = s.ownBullets(section.id).filter { it.dateAdded == r.addedDate }.sortedBy { it.position }.map { it.content }
            if (notes.isEmpty()) return@mapNotNull null
            val rev = r.revisionsDone + 1
            DueItem(r.id, session.id, session.title, section.title, section.position, r.addedDate, r.intervalDays, rev, Level.of(rev, perLevel), notes)
        }

    fun plan(items: List<DueItem>, targetMinutes: Int): Plan {
        val budget = (targetMinutes * WORDS_PER_MINUTE - INTRO_OUTRO_WORDS).coerceAtLeast(HEADLINE_MIN)
        var kept = items
        val deferred = ArrayList<DueItem>()
        // Still too long even at the minimums → the lowest-detail sections move to tomorrow.
        while (kept.size > 1 && kept.sumOf { minimum(it) } > budget) {
            val drop = kept.maxWith(compareBy<DueItem> { it.level.ordinal }.thenBy { it.revisionNo }.thenBy { it.noteWords })
            deferred += drop
            kept = kept - drop
        }

        val words = kept.associateWith { ideal(it) }.toMutableMap()
        var over = words.values.sum() - budget
        // Shrink the least important level first: Headlines → Key points → Shorter → Detailed.
        for (level in Level.entries.reversed()) {
            if (over <= 0) break
            val group = kept.filter { it.level == level }
            val slack = group.sumOf { ideal(it) - minimum(it) }
            if (slack <= 0) continue
            val factor = minOf(1.0, over / slack.toDouble())
            for (it in group) words[it] = ideal(it) - ceil((ideal(it) - minimum(it)) * factor).toInt()
            over = words.values.sum() - budget
        }

        // Order: grouped by topic, topics with Detailed sections first.
        val topicRank = kept.groupBy { it.sessionId }.mapValues { (_, v) -> v.minOf { it.level.ordinal } }
        val ordered = kept.sortedWith(
            compareBy<DueItem> { topicRank.getValue(it.sessionId) }.thenBy { it.topic.lowercase() }.thenBy { it.sessionId }
                .thenBy { it.level.ordinal }.thenBy { it.sectionOrder }.thenBy { it.addedDate }
        )
        return Plan(ordered.map { Segment(it, words.getValue(it)) }, deferred, targetMinutes)
    }

    /** The ladder shown in the app: 1 → 2 → 4 … up to the cap, [perLevel] revisions per level. */
    fun ladder(cap: Int, perLevel: Int): List<Pair<Level, String>> {
        val days = generateSequence(1) { it * 2 }.takeWhile { it <= cap }.toList().ifEmpty { listOf(1) }
        return Level.entries.mapIndexed { i, level ->
            val mine = days.drop(i * perLevel).take(perLevel)
            level to when {
                mine.isEmpty() -> "Day ${days.last()}"          // the gap has stopped growing by then
                level == Level.HEADLINES -> "Day ${mine.first()}+"
                else -> "Day ${mine.joinToString(", ")}"
            }
        }
    }
}
