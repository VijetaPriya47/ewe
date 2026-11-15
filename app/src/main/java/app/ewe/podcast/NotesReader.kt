package app.ewe.podcast

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
 * Reads a session's notes aloud, one line at a time so it can pause, resume and skip by section.
 * With Android's built-in text-to-speech: no AI, no cost, works offline. With Gnani chosen in Settings,
 * each line is fetched from Gnani as audio, one request at a time, and the next line is made ready while
 * the current one plays.
 */
class NotesReader(private val context: Context) {
    private val gnani = app.ewe.ai.GnaniTts()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var gnaniJob: kotlinx.coroutines.Job? = null
    private var gnaniActive = false
    private var mediaPlayer: android.media.MediaPlayer? = null
    private val gnaniDir get() = java.io.File(context.cacheDir, "gnani_reader")
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
        stopSpeaking()
        _state.value = ReaderState(sessionId, sessionTitle, lines, from.coerceIn(0, lines.lastIndex), true, speed)
        connectService()
        val cfg = app.ewe.data.LocalPrefs(context).gnani()
        gnaniActive = cfg != null
        if (cfg != null) { speakGnani(cfg); return }
        val t = engine()
        if (ready) speakFromCurrent() else pendingStart = true
        t.setSpeechRate(speed)
    }

    /** Stops whatever is speaking, with either voice. */
    private fun stopSpeaking() {
        gnaniJob?.cancel(); gnaniJob = null
        tts?.stop()
    }

    /** Starts the current line again (or continues from it), with whichever voice is active. */
    private fun speakAgain() {
        val cfg = app.ewe.data.LocalPrefs(context).gnani()
        gnaniActive = cfg != null
        if (cfg != null) speakGnani(cfg) else if (ready) speakFromCurrent() else { engine(); pendingStart = true }
    }

    fun pause() {
        stopSpeaking()
        _state.update { it?.copy(playing = false) }
    }

    fun resume() {
        val s = _state.value ?: return
        _state.value = s.copy(playing = true)
        speakAgain()
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
        stopSpeaking()
        _state.value = s.copy(index = target)
        if (s.playing && (ready || gnaniActive)) speakAgain()
    }

    /** Jump to the [index]-th section that has lines (lock-screen "skip to item"). */
    fun jumpToSectionIndex(index: Int) {
        val s = _state.value ?: return
        val section = s.lines.map { it.section }.distinct().getOrNull(index) ?: return
        val target = s.lines.indexOfFirst { it.section == section }
        stopSpeaking()
        _state.value = s.copy(index = target)
        if (s.playing && (ready || gnaniActive)) speakAgain()
    }

    fun cycleSpeed() {
        val s = _state.value ?: return
        val next = SPEEDS[(SPEEDS.indexOf(s.speed).takeIf { it >= 0 }?.plus(1) ?: 1) % SPEEDS.size]
        tts?.setSpeechRate(next)
        _state.value = s.copy(speed = next)
        if (gnaniActive) { setPlayerSpeed(next); return }          // changes the line that is playing, no restart
        if (s.playing && ready) { tts?.stop(); speakFromCurrent() }
    }

    fun stop() {
        stopSpeaking()
        gnaniDir.deleteRecursively()
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

    fun shutdown() { stop(); scope.cancel(); tts?.shutdown(); tts = null; ready = false }

    // ───────────── Gnani voice ─────────────
    private fun setPlayerSpeed(speed: Float) {
        mediaPlayer?.let { p -> runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) } }
    }

    private fun speakGnani(cfg: app.ewe.ai.GnaniCfg) {
        val s = _state.value ?: return
        gnaniJob?.cancel()
        gnaniJob = scope.launch {
            try {
                var i = s.index
                var upcoming: kotlinx.coroutines.Deferred<List<java.io.File>>? = null
                while (true) {
                    val lines = _state.value?.lines ?: return@launch
                    if (i > lines.lastIndex) break
                    val files = (upcoming ?: lineFiles(cfg, lines[i].text)).await()
                    // only after this line is in hand does the next one start loading: one request at a time
                    upcoming = lines.getOrNull(i + 1)?.let { lineFiles(cfg, it.text) }
                    _state.update { st -> st?.takeIf { it.playing }?.copy(index = i) ?: st }
                    for (f in files) playFile(f)
                    if (lines[i].key.startsWith("h:")) kotlinx.coroutines.delay(350)     // a short pause after a section title
                    i++
                }
                stop()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Reading stopped."
                _state.update { it?.copy(playing = false) }
            }
        }
    }

    /** The audio for one line, as files (a long line is several requests). Cached, so going back costs nothing. */
    private fun lineFiles(cfg: app.ewe.ai.GnaniCfg, text: String) = scope.async(kotlinx.coroutines.Dispatchers.IO) {
        app.ewe.podcast.Script.chunks(text, app.ewe.ai.GnaniTts.CHUNK).filter { it.isNotBlank() }.map { chunk ->
            val key = java.security.MessageDigest.getInstance("SHA-1").digest("${cfg.model}|${cfg.voice}|${cfg.language}|$chunk".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val file = java.io.File(gnaniDir, "$key.mp3")
            if (!file.exists() || file.length() == 0L) {
                gnaniDir.mkdirs()
                val tmp = java.io.File(gnaniDir, "$key.part")
                tmp.writeBytes(gnani.synthesize(cfg, chunk))
                tmp.renameTo(file)
            }
            file
        }
    }

    private suspend fun playFile(file: java.io.File) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        val speed = _state.value?.speed ?: 1f
        val mp = android.media.MediaPlayer()
        mediaPlayer = mp
        fun release() { runCatching { mp.stop() }; mp.release(); if (mediaPlayer === mp) mediaPlayer = null }
        try {
            mp.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { release(); if (cont.isActive) cont.resume(Unit) {} }
            mp.setOnErrorListener { _, what, extra ->
                release(); if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("The audio could not be played ($what/$extra)."))); true
            }
            mp.setOnPreparedListener { p ->
                p.start()
                // set after start: changing the speed of a player that isn't started would start it
                runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            release(); cont.resumeWith(Result.failure(e))
        }
        cont.invokeOnCancellation { release() }
    }

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
