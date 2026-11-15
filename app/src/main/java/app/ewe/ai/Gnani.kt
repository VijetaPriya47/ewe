package app.ewe.ai

import app.ewe.data.ApiException
import app.ewe.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the Gnani voice is set to: kept on this phone, with the key. */
data class GnaniCfg(val apiKey: String, val model: String, val voice: String, val language: String)

/** One voice from Gnani's catalog. */
data class GnaniVoice(val name: String, val gender: String, val language: String, val persona: String)

/**
 * Gnani Vachana text-to-speech (https://docs.gnani.ai/api/TTS/tts-inference): one POST returns the whole
 * audio. The key goes in the X-API-Key-ID header. Only one request may be active at a time on a
 * standard account, so everything here is sequential.
 */
class GnaniTts(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build(),
    /** Overridable so tests can point it at a local server. */
    private val baseUrl: String = BASE_URL,
    /** Waits before a retry; tests make it instant. */
    private val pause: suspend (Int) -> Unit = { attempt -> delay(1500L * attempt) },
) {
    /** The request body for [text]. MP3 at 24 kHz mono: speech-quality and about a third the size of WAV. */
    fun body(cfg: GnaniCfg, text: String): JsonObject = buildJsonObject {
        put("text", text)
        put("voice", cfg.voice)
        put("model", cfg.model)
        put("language", cfg.language)
        put("speed", 1.0)
        putJsonObject("audio_config") {
            put("sample_rate", 24_000)
            put("num_channels", 1)
            put("container", "mp3")
            put("bitrate", "64k")
        }
    }

    /** One piece of text → MP3 bytes. Busy servers and dropped connections are retried a few times. */
    suspend fun synthesize(cfg: GnaniCfg, text: String): ByteArray = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "nothing to say" }
        val request = Request.Builder().url("$baseUrl/api/v1/tts/inference")
            .header("X-API-Key-ID", cfg.apiKey)
            .post(AppJson.encodeToString(JsonObject.serializer(), body(cfg, text)).toRequestBody("application/json".toMediaType()))
            .build()
        var last: ApiException? = null
        for (attempt in 1..ATTEMPTS) {
            val (code, bytes) = try {
                http.newCall(request).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
            } catch (e: IllegalArgumentException) {
                throw ApiException("The Gnani address is not valid.")
            } catch (e: IOException) {
                last = ApiException("No internet connection."); if (attempt < ATTEMPTS) { pause(attempt); continue }
                throw last
            }
            if (code in 200..299) {
                if (bytes.size < 200) throw ApiException("Gnani returned no audio.")
                return@withContext bytes
            }
            val error = ApiException(describe(code, bytes), code)
            // rate limit, busy server: wait and try again; everything else is the user's to fix
            if ((code == 429 || code in 500..599) && attempt < ATTEMPTS) { last = error; pause(attempt); continue }
            throw error
        }
        throw last ?: ApiException("Gnani could not be reached.")
    }

    /**
     * Text of any length → MP3 files in [dir] ([name]_0.mp3, …), one request per chunk of at most
     * [CHUNK] characters, in order. [onChunk] gets the 1-based number of the chunk being made.
     */
    suspend fun synthesizeFiles(cfg: GnaniCfg, text: String, dir: File, name: String, onChunk: (Int, Int) -> Unit = { _, _ -> }): List<File> {
        dir.mkdirs()
        val chunks = app.ewe.podcast.Script.chunks(text, CHUNK).filter { it.isNotBlank() }
        return chunks.mapIndexed { i, chunk ->
            onChunk(i + 1, chunks.size)
            val file = File(dir, "${name}_$i.mp3")
            file.writeBytes(synthesize(cfg, chunk))
            file
        }
    }

    companion object {
        const val BASE_URL = "https://api.vachana.ai"
        const val ATTEMPTS = 3
        /** Characters per request: well inside what one request handles (longer text can fail with a 500). */
        const val CHUNK = 600
        const val SAMPLE = "Hello! This is how your notes will sound."

        val MODELS = listOf("timbre-v2.5" to "Timbre v2.5", "timbre-v3.0" to "Timbre v3.0")

        /** `language` values, with what to call them. "auto" reads the script of the text. */
        val LANGUAGES = listOf(
            "auto" to "Auto-detect", "en-IN" to "English", "hi-IN" to "Hindi", "hi-en" to "Hinglish", "ta-IN" to "Tamil",
            "te-IN" to "Telugu", "kn-IN" to "Kannada", "ml-IN" to "Malayalam", "mr-IN" to "Marathi", "pa-IN" to "Punjabi",
            "bn-IN" to "Bengali", "gu-IN" to "Gujarati",
        )

        fun languageName(code: String) = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

        /** The flat `{"success":false,"message":…}` body, or `{"detail":{"error_code","message"}}` for key and rate-limit errors. */
        fun describe(code: Int, body: ByteArray): String {
            val root = runCatching { AppJson.parseToJsonElement(String(body)).jsonObject }.getOrNull()
            val detail = root?.get("detail") as? JsonObject
            val message = ((detail ?: root)?.get("message") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            return when (code) {
                401 -> "Gnani rejected the API key. Check it in Settings › Voice."
                402 -> "Your Gnani account is out of credit."
                403 -> "This Gnani key can't use text-to-speech" + if (message.isNotEmpty()) " ($message)." else "."
                429 -> "Gnani is busy: only one request can run at a time on this account. Try again in a moment."
                else -> "Gnani error $code" + if (message.isNotEmpty()) ": ${message.take(200)}" else "."
            }
        }

        /** Timbre v2.5: 42 voices, each with one preferred language (the `language` value to use with it). */
        val V25 = listOf(
            GnaniVoice("Kaveri", "Female", "en-IN", "Professional"), GnaniVoice("Trupti", "Female", "en-IN", "Professional"),
            GnaniVoice("Devika", "Female", "en-IN", "Professional"), GnaniVoice("Pranav", "Male", "en-IN", "Friendly"),
            GnaniVoice("Shlok", "Male", "en-IN", "Conversational"), GnaniVoice("Girish", "Male", "en-IN", "Expressive"),
            GnaniVoice("Nalini", "Female", "hi-IN", "Friendly"), GnaniVoice("Bhavna", "Female", "hi-IN", "Friendly"),
            GnaniVoice("Yashvi", "Female", "hi-IN", "Warm"), GnaniVoice("Urmila", "Female", "hi-IN", "Professional"),
            GnaniVoice("Jwala", "Female", "hi-IN", "Friendly"), GnaniVoice("Chitra", "Female", "hi-IN", "Warm"),
            GnaniVoice("Ambuja", "Female", "hi-IN", "Professional"), GnaniVoice("Deepak", "Male", "hi-IN", "Professional"),
            GnaniVoice("Roopesh", "Male", "hi-IN", "Professional"), GnaniVoice("Vikrant", "Male", "hi-IN", "Conversational"),
            GnaniVoice("Hemraj", "Male", "hi-IN", "Confident"), GnaniVoice("Jalaj", "Male", "hi-IN", "Expressive"),
            GnaniVoice("Omkar", "Male", "hi-IN", "Confident"),
            GnaniVoice("Poorvi", "Female", "hi-en", "Conversational"),
            GnaniVoice("Asmita", "Female", "ta-IN", "Professional"), GnaniVoice("Trisha", "Female", "ta-IN", "Friendly"),
            GnaniVoice("Brinda", "Female", "ta-IN", "Professional"), GnaniVoice("Vedika", "Female", "ta-IN", "Professional"),
            GnaniVoice("Noopur", "Female", "ta-IN", "Friendly"),
            GnaniVoice("Suhana", "Female", "te-IN", "Warm"), GnaniVoice("Lehara", "Female", "te-IN", "Warm"),
            GnaniVoice("Lavanya", "Female", "te-IN", "Warm"), GnaniVoice("Yukti", "Female", "te-IN", "Friendly"),
            GnaniVoice("Varuni", "Female", "te-IN", "Conversational"),
            GnaniVoice("Saanvi", "Female", "kn-IN", "Expressive"), GnaniVoice("Kavin", "Male", "kn-IN", "Professional"),
            GnaniVoice("Reshma", "Female", "ml-IN", "Friendly"), GnaniVoice("Riyaan", "Male", "ml-IN", "Conversational"),
            GnaniVoice("Zahira", "Female", "mr-IN", "Expressive"), GnaniVoice("Ishaan", "Male", "mr-IN", "Professional"),
            GnaniVoice("Kirra", "Female", "bn-IN", "Conversational"), GnaniVoice("Dhruva", "Male", "bn-IN", "Conversational"),
            GnaniVoice("Falak", "Female", "gu-IN", "Conversational"), GnaniVoice("Veera", "Male", "gu-IN", "Friendly"),
            GnaniVoice("Mehuli", "Female", "pa-IN", "Expressive"), GnaniVoice("Zayan", "Male", "pa-IN", "Warm"),
        )

        /** Timbre v3.0: 13 voices that each speak all the languages (the language is chosen separately). */
        val V30 = listOf("Shivani", "Lakshmi", "Kalpana", "Chetana", "Vasudha", "Riyaa", "Tarika").map { GnaniVoice(it, "Female", "auto", "") } +
            listOf("Avinash", "Shubham", "Prakash", "Devansh", "Yashraj", "Harshad").map { GnaniVoice(it, "Male", "auto", "") }

        fun voices(model: String): List<GnaniVoice> = if (model == "timbre-v3.0") V30 else V25

        /** The voice to start with when the model changes: a v2.5 voice stays on v2.5 only. */
        fun defaultVoice(model: String): GnaniVoice = if (model == "timbre-v3.0") V30.first { it.name == "Shivani" } else V25.first { it.name == "Kaveri" }

        /** v2.5 voices work best with their own language; v3.0 voices take whichever is asked for. */
        fun languageFor(model: String, voice: String, current: String): String =
            if (model == "timbre-v3.0") current else V25.firstOrNull { it.name == voice }?.language ?: current
    }
}
