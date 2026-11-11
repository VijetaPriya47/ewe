package app.ewe.podcast

import android.content.Context
import app.ewe.data.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

@Serializable
data class AudioFile(val path: String, val durationMs: Long)

/** One audio chapter: a section's script, split into files the speech engine can handle. */
@Serializable
data class Chapter(
    val scheduleId: String? = null,     // null for the intro and outro
    val sessionId: String? = null,
    val topic: String = "",
    val section: String = "",
    val addedDate: String = "",
    val intervalDays: Int = 0,
    val revisionNo: Int = 0,
    val level: String = "",
    val script: String = "",
    val files: List<AudioFile> = emptyList(),
) {
    val durationMs: Long get() = files.sumOf { it.durationMs }
    val isSection: Boolean get() = scheduleId != null
    val title: String get() = if (isSection) "$topic › $section" else topic
}

@Serializable
data class Episode(
    val date: String,
    val preparedAt: Long,
    val targetMinutes: Int,
    val chapters: List<Chapter>,
    val deferred: Int = 0,
    val heard: Set<String> = emptySet(),     // schedule ids played to the end
    val marked: Set<String> = emptySet(),    // schedule ids already "marked as listened"
) {
    val durationMs: Long get() = chapters.sumOf { it.durationMs }
    val sections: List<Chapter> get() = chapters.filter { it.isSection }
}

/** The prepared episode lives on the phone so it plays offline. Only today's is kept. */
class EpisodeStore(context: Context) {
    private val root = File(context.filesDir, "episodes")
    private val _episode = MutableStateFlow(load())
    val episode: StateFlow<Episode?> = _episode

    fun dir(date: LocalDate) = File(root, date.toString()).apply { mkdirs() }

    private fun load(): Episode? = root.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.let { d ->
        runCatching { AppJson.decodeFromString(Episode.serializer(), File(d, "episode.json").readText()) }.getOrNull()
    }

    @Synchronized
    fun save(e: Episode) {
        val d = dir(LocalDate.parse(e.date))
        File(d, "episode.json").writeText(AppJson.encodeToString(Episode.serializer(), e))
        root.listFiles()?.filter { it.isDirectory && it.name != e.date }?.forEach { it.deleteRecursively() }
        _episode.value = e
    }

    @Synchronized
    fun update(change: (Episode) -> Episode) { _episode.value?.let { save(change(it)) } }

    fun today(date: LocalDate): Episode? = _episode.value?.takeIf { it.date == date.toString() }

    companion object {
        @Volatile private var instance: EpisodeStore? = null
        fun get(context: Context): EpisodeStore =
            instance ?: synchronized(this) { instance ?: EpisodeStore(context.applicationContext).also { instance = it } }
    }
}
