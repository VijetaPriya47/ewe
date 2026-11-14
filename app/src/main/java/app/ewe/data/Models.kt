package app.ewe.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

@Serializable
data class Session(
    val id: String,
    val title: String,
    val type: String, // "topic" | "project" — locked at creation
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("recreated_from") val recreatedFrom: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    val isTopic get() = type == "topic"
}

@Serializable
data class Section(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val title: String,
    val position: Double = 0.0,
    /** Marked by the AI as central to the material; shown underlined. */
    val important: Boolean = false,
)

@Serializable
data class Bullet(
    val id: String,
    @SerialName("section_id") val sectionId: String,
    val content: String,
    val position: Double = 0.0,
    @SerialName("date_added") val dateAdded: String = "",
    @SerialName("origin_session_id") val originSessionId: String = "",
    @SerialName("origin_message_id") val originMessageId: String? = null,
    /** "text", "mermaid" (diagram), "code" (code block) or "table" (Markdown table). */
    val kind: String = "text",
    /** Language of a code block, e.g. "python". */
    val lang: String? = null,
    /** Marked by the AI as a key point; shown underlined. */
    val important: Boolean = false,
    /** Set by you: "yellow" or "red". */
    val highlight: String? = null,
) {
    val isDiagram: Boolean get() = kind == "mermaid"
    val isCode: Boolean get() = kind == "code"
    val isTable: Boolean get() = kind == "table"
    /** Text and tables carry the points; diagrams and code are extras that are not read aloud. */
    val isSpoken: Boolean get() = kind == "text" || kind == "table"
}

/** The same bullet row, also shown inside a Topic section. */
@Serializable
data class BulletLink(
    val id: String,
    @SerialName("bullet_id") val bulletId: String,
    @SerialName("section_id") val sectionId: String,
    val position: Double = 0.0,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class Message(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val role: String, // "user" | "assistant"
    val content: String = "",
    @SerialName("image_count") val imageCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    /** Where it went: "notes", "reference" (References only) or "summary" (Overview only). */
    val mode: String = "notes",
)

/** A link saved for a session. Its number never changes; notes cite it as [[ref:id]], shown as [n]. */
@Serializable
data class Reference(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val number: Int,
    val url: String,
    val title: String = "",
    val description: String = "",
    @SerialName("date_added") val dateAdded: String = "",
    @SerialName("origin_message_id") val originMessageId: String? = null,
)

/** A diagram in the Overview tab: your own summary ("upload") or the AI overview ("ai_overview"). */
@Serializable
data class Summary(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val kind: String,
    val title: String = "",
    val mermaid: String = "",
    val note: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class Schedule(
    val id: String,
    @SerialName("section_id") val sectionId: String,
    @SerialName("added_date") val addedDate: String,
    @SerialName("interval_days") val intervalDays: Int = 1,
    @SerialName("revisions_done") val revisionsDone: Int = 0,
    @SerialName("next_due_date") val nextDueDate: String,
    @SerialName("last_revised") val lastRevised: String? = null,
)

@Serializable
data class Settings(
    @SerialName("notes_provider") val notesProvider: String? = null,
    @SerialName("notes_model") val notesModel: String? = null,
    @SerialName("placement_provider") val placementProvider: String? = null,
    @SerialName("placement_model") val placementModel: String? = null,
    @SerialName("podcast_provider") val podcastProvider: String? = null,
    @SerialName("podcast_model") val podcastModel: String? = null,
    @SerialName("rag_enabled") val ragEnabled: Boolean = false,
    @SerialName("rag_selected_model") val ragSelectedModel: String? = null,
    @SerialName("rag_active_model") val ragActiveModel: String? = null,
    @SerialName("podcast_enabled") val podcastEnabled: Boolean = false,
    @SerialName("interval_cap_days") val intervalCapDays: Int = 64,
    @SerialName("daily_job_time") val dailyJobTime: String = "01:00:00",
    @SerialName("podcast_target_minutes") val podcastTargetMinutes: Int = 30,
    @SerialName("revisions_per_level") val revisionsPerLevel: Int = 2,
    @SerialName("keep_photo_copies") val keepPhotoCopies: Boolean = false,
)

@Serializable
data class RagStatus(
    val enabled: Boolean = false,
    @SerialName("selected_model") val selectedModel: String? = null,
    @SerialName("active_model") val activeModel: String? = null,
    @SerialName("total_rows") val totalRows: Int = 0,
    @SerialName("missing_rows") val missingRows: Int = 0,
    @SerialName("missing_notes") val missingNotes: Int = 0,
    @SerialName("staged_rows") val stagedRows: Int = 0,
    @SerialName("embedded_rows") val embeddedRows: Int = 0,
    val ready: Boolean = false,
) {
    val mismatch get() = enabled && activeModel != null && selectedModel != activeModel
}

/** Everything the phone keeps so notes stay readable offline. */
@Serializable
data class Snapshot(
    val settings: Settings = Settings(),
    val sessions: List<Session> = emptyList(),
    val sections: List<Section> = emptyList(),
    val bullets: List<Bullet> = emptyList(),
    val links: List<BulletLink> = emptyList(),
    val messages: List<Message> = emptyList(),
    val schedule: List<Schedule> = emptyList(),
    val references: List<Reference> = emptyList(),
    val summaries: List<Summary> = emptyList(),
    val rag: RagStatus = RagStatus(),
    val syncedAt: Long = 0,
) {
    private val sessionById by lazy { sessions.associateBy { it.id } }
    private val sectionById by lazy { sections.associateBy { it.id } }
    private val bulletById by lazy { bullets.associateBy { it.id } }
    private val sectionsBySession by lazy {
        sections.sortedBy { it.position }.groupBy { it.sessionId }
    }
    private val bulletsBySection by lazy { bullets.groupBy { it.sectionId } }
    private val linksBySection by lazy { links.groupBy { it.sectionId } }
    private val referenceById by lazy { references.associateBy { it.id } }
    private val referencesBySession by lazy { references.sortedBy { it.number }.groupBy { it.sessionId } }

    fun session(id: String?) = sessionById[id]
    fun section(id: String?) = sectionById[id]
    fun bullet(id: String?) = bulletById[id]
    fun sectionsOf(sessionId: String) = sectionsBySession[sessionId].orEmpty()
    fun ownBullets(sectionId: String) = bulletsBySection[sectionId].orEmpty()

    /** A section's points in reading order: its own plus the ones linked in from other sessions. */
    fun points(sectionId: String): List<Point> {
        val own = bulletsBySection[sectionId].orEmpty().map { Point(it, it.position, null) }
        val linked = linksBySection[sectionId].orEmpty().mapNotNull { l ->
            bulletById[l.bulletId]?.let { Point(it, l.position, l) }
        }
        return (own + linked).sortedBy { it.position }
    }

    fun reference(id: String?) = referenceById[id]
    fun referencesOf(sessionId: String) = referencesBySession[sessionId].orEmpty()
    fun summariesOf(sessionId: String) = summaries.filter { it.sessionId == sessionId }.sortedByDescending { instantOf(it.createdAt) }

    /** How many notes cite each reference. */
    val citations: Map<String, Int> by lazy {
        val counts = HashMap<String, Int>()
        bullets.forEach { b -> Rich.refIds(b.content).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        counts
    }

    fun noteCount(sessionId: String) = sectionsOf(sessionId).sumOf {
        (bulletsBySection[it.id]?.size ?: 0) + (linksBySection[it.id]?.size ?: 0)
    }
}

/** One line in the Notes tab. `link` is set when the point lives in another session. */
data class Point(val bullet: Bullet, val position: Double, val link: BulletLink?)

enum class ProviderKind(val id: String, val label: String, val hint: String) {
    OPENAI("openai", "OpenAI", "sk-…"),
    ANTHROPIC("anthropic", "Claude (Anthropic)", "sk-ant-…"),
    GEMINI("gemini", "Gemini (Google)", "AIza…"),
    COMPATIBLE("compatible", "OpenAI-compatible", "OpenRouter, Groq, DeepSeek, Ollama…");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** What is sent along with a request; the key never goes anywhere else. */
data class ProviderCfg(val kind: ProviderKind, val apiKey: String, val model: String, val baseUrl: String?)

/** Postgres timestamps vary in how many fractional digits they print, so compare them as instants. */
fun instantOf(iso: String): java.time.Instant =
    runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrElse { runCatching { java.time.Instant.parse(iso) }.getOrDefault(java.time.Instant.EPOCH) }
