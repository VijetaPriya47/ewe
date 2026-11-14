package app.ewe.podcast

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** One spoken line: a section title or a note. [key] matches the line shown in the Notes tab. */
data class SpokenLine(val key: String, val text: String, val section: Int, val sectionTitle: String)

data class ReaderState(
    val sessionId: String,
    val sessionTitle: String,
    val lines: List<SpokenLine>,
    val index: Int,
    val playing: Boolean,
    val speed: Float,
) {
    val current: SpokenLine? get() = lines.getOrNull(index)
}

/**
 * Reads a session's notes aloud with Android's built-in text-to-speech: no AI, no cost,
 * works offline. One line at a time, so it can pause, resume and skip by section.
 */
class NotesReader(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingStart = false
    private val _state = MutableStateFlow<ReaderState?>(null)
    val state: StateFlow<ReaderState?> = _state
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Keeps [ReaderService] connected while reading, which gives the lock-screen and headphone controls. */
    private var controller: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private fun engine(): TextToSpeech = tts ?: TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts?.let { t ->
                val locale = Locale.getDefault().takeIf { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
                t.language = locale
                t.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
            if (pendingStart) { pendingStart = false; speakFromCurrent() }
        } else {
            _error.value = "No text-to-speech voice is available. Install or enable one in Android settings."
            _state.value = null
        }
    }.also { t ->
        tts = t
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val i = utteranceId?.substringAfter('#')?.toIntOrNull() ?: return
                _state.update { s -> s?.takeIf { it.playing && utteranceId.startsWith(it.sessionId) }?.copy(index = i) ?: s }
            }
            override fun onDone(utteranceId: String?) {
                val i = utteranceId?.substringAfter('#')?.toIntOrNull() ?: return
                if (_state.value?.let { it.playing && i == it.lines.lastIndex } == true) main.post { stop() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
            override fun onError(utteranceId: String?, errorCode: Int) {
                _error.value = "Reading stopped (speech engine error $errorCode)."
                _state.update { it?.copy(playing = false) }
            }
        })
    }

    fun play(sessionId: String, sessionTitle: String, lines: List<SpokenLine>, from: Int = 0) {
        if (lines.isEmpty()) return
        val speed = _state.value?.speed ?: 1f
        tts?.stop()
        _state.value = ReaderState(sessionId, sessionTitle, lines, from.coerceIn(0, lines.lastIndex), true, speed)
        connectService()
        val t = engine()
        if (ready) speakFromCurrent() else pendingStart = true
        t.setSpeechRate(speed)
    }

    fun pause() {
        tts?.stop()
        _state.update { it?.copy(playing = false) }
    }

    fun resume() {
        val s = _state.value ?: return
        _state.value = s.copy(playing = true)
        if (ready) speakFromCurrent() else { engine(); pendingStart = true }
    }

    /** Jumps to the next (or previous) section's title. */
    fun skipSection(forward: Boolean) {
        val s = _state.value ?: return
        val cur = s.current?.section ?: 0
        val target = if (forward) s.lines.indexOfFirst { it.section > cur }
        else {
            val startOfThis = s.lines.indexOfFirst { it.section == cur }
            // within the first line of a section, go to the previous one; otherwise restart this one
            if (s.index > startOfThis) startOfThis else s.lines.indexOfFirst { it.section == cur - 1 }.takeIf { it >= 0 } ?: 0
        }
        if (target < 0) { stop(); return }
        tts?.stop()
        _state.value = s.copy(index = target)
        if (s.playing && ready) speakFromCurrent()
    }

    /** Jump to the [index]-th section that has lines (lock-screen "skip to item"). */
    fun jumpToSectionIndex(index: Int) {
        val s = _state.value ?: return
        val section = s.lines.map { it.section }.distinct().getOrNull(index) ?: return
        val target = s.lines.indexOfFirst { it.section == section }
        tts?.stop()
        _state.value = s.copy(index = target)
        if (s.playing && ready) speakFromCurrent()
    }

    fun cycleSpeed() {
        val s = _state.value ?: return
        val next = SPEEDS[(SPEEDS.indexOf(s.speed).takeIf { it >= 0 }?.plus(1) ?: 1) % SPEEDS.size]
        tts?.setSpeechRate(next)
        _state.value = s.copy(speed = next)
        if (s.playing && ready) { tts?.stop(); speakFromCurrent() }
    }

    fun stop() {
        tts?.stop()
        pendingStart = false
        _state.value = null
        controller?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        controller = null
    }

    private fun connectService() {
        if (controller != null) return
        controller = runCatching {
            androidx.media3.session.MediaController.Builder(context,
                androidx.media3.session.SessionToken(context, android.content.ComponentName(context, ReaderService::class.java))).buildAsync()
        }.getOrNull()
    }

    fun clearError() { _error.value = null }

    fun shutdown() { stop(); tts?.shutdown(); tts = null; ready = false }

    private fun speakFromCurrent() {
        val s = _state.value ?: return
        val t = tts ?: return
        t.setSpeechRate(s.speed)
        s.lines.forEachIndexed { i, line ->
            if (i < s.index) return@forEachIndexed
            val mode = if (i == s.index) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            t.speak(line.text, mode, Bundle(), "${s.sessionId}#$i")
            // a short pause after each section title
            if (line.key.startsWith("h:")) t.playSilentUtterance(350, TextToSpeech.QUEUE_ADD, "${s.sessionId}~$i")
        }
    }

    companion object {
        val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f)
        @Volatile private var instance: NotesReader? = null
        fun get(context: Context): NotesReader =
            instance ?: synchronized(this) { instance ?: NotesReader(context.applicationContext).also { instance = it } }

        /** What gets read: each section title, then its notes and tables (row by row). Diagrams and code are skipped. */
        fun lines(snapshot: app.ewe.data.Snapshot, sessionId: String): List<SpokenLine> {
            val out = ArrayList<SpokenLine>()
            snapshot.sectionsOf(sessionId).forEachIndexed { si, section ->
                val points = snapshot.points(section.id).filter { it.bullet.isSpoken }
                if (points.isEmpty()) return@forEachIndexed
                out += SpokenLine("h:${section.id}", "${Script.speakable(section.title)}.", si, section.title)
                points.forEach { p ->
                    val text = app.ewe.data.Rich.stripRefs(if (p.bullet.isTable) app.ewe.data.Rich.tableAsText(p.bullet.content) else p.bullet.content)
                    out += SpokenLine(section.id + p.bullet.id, Script.speakable(text).trimEnd('.') + ".", si, section.title)
                }
            }
            return out
        }

        fun minutes(lines: List<SpokenLine>): Int =
            ((lines.sumOf { Planner.words(it.text) } / Planner.WORDS_PER_MINUTE.toDouble()) + 0.5).toInt().coerceAtLeast(1)
    }
}
