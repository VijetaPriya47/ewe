package app.ewe.podcast

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Android's built-in text-to-speech, writing audio files. No AI, no cost. */
class Speech(private val context: Context) {
    private var tts: TextToSpeech? = null
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun start() {
        val ready = CompletableDeferred<Int>()
        val engine = withContext(Dispatchers.Main) { TextToSpeech(context) { status -> ready.complete(status) } }
        if (withTimeout(20_000) { ready.await() } != TextToSpeech.SUCCESS) {
            engine.shutdown()
            throw IllegalStateException("No text-to-speech engine is available. Install or enable one in Android settings.")
        }
        val locale = Locale.getDefault().takeIf { engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
        engine.language = locale
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { waiting.remove(utteranceId)?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { waiting.remove(utteranceId)?.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { waiting.remove(utteranceId)?.complete(false) }
        })
        tts = engine
    }

    /** Speaks [text] into WAV files inside [dir], named [name]_0.wav, [name]_1.wav … */
    suspend fun synthesize(text: String, dir: File, name: String): List<AudioFile> {
        val engine = tts ?: throw IllegalStateException("Speech engine not started")
        val max = (TextToSpeech.getMaxSpeechInputLength() - 200).coerceIn(500, 3000)
        return Script.chunks(text, max).mapIndexedNotNull { i, chunk ->
            val file = File(dir, "${name}_$i.wav")
            val id = "$name-$i-${System.nanoTime()}"
            val done = CompletableDeferred<Boolean>()
            waiting[id] = done
            if (engine.synthesizeToFile(chunk, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                waiting.remove(id)
                throw IllegalStateException("Text-to-speech could not start.")
            }
            if (!withTimeout(180_000) { done.await() } || file.length() < 64) {
                throw IllegalStateException("Text-to-speech failed part-way.")
            }
            AudioFile(file.absolutePath, durationOf(file))
        }
    }

    private fun durationOf(file: File): Long = runCatching {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            release()
            ms
        }
    }.getOrNull() ?: 0L

    fun stop() { tts?.shutdown(); tts = null }
}
