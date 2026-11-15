package app.ewe.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio for notes: any recording the phone can play is turned into small speech-quality pieces
 * (mono, 16 kHz) that a transcription model accepts.
 *
 * AAC is used because it is about 1/8 the size of plain WAV (1 hour ≈ 14 MB instead of 115 MB):
 * an .m4a file for OpenAI-style providers, raw AAC (ADTS) for Gemini. WAV is the fallback when the
 * phone cannot encode AAC, or a provider rejects a piece.
 */
object Audio {
    const val RATE = 16_000
    /** Longest recording accepted. */
    const val MAX_SECONDS = 3 * 60 * 60
    /** Notes are made from this much speech at a time, so each part sees the notes made before it. */
    const val NOTES_PART_SECONDS = 20 * 60

    enum class Format(val pieceSeconds: Int, val ext: String) {
        M4A(10 * 60, "m4a"), ADTS(10 * 60, "aac"), WAV(5 * 60, "wav");

        val mime: String get() = when (this) { M4A -> "audio/mp4"; ADTS -> "audio/aac"; WAV -> "audio/wav" }
    }

    data class Piece(val file: File, val format: Format, val startSec: Int, val endSec: Int)

    data class Info(val name: String, val seconds: Int?)

    fun info(context: Context, uri: Uri): Info {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        val seconds = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { (it / 1000).toInt() }
                } finally { release() }
            }
        }.getOrNull()
        return Info(name, seconds)
    }

    /** "1:05:09" / "4:07" */
    fun clock(seconds: Int): String {
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "52 min" / "1 h 05 min" / "40 s" */
    fun length(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${(seconds + 30) / 60} min"
        else -> "${seconds / 3600} h %02d min".format((seconds % 3600 + 30) / 60)
    }

    /**
     * Decodes [uri] and writes pieces of [format] into [dir], starting at [fromSec] (pieces before it
     * were already transcribed). [onProgress] gets 0–1.
     */
    fun prepare(context: Context, uri: Uri, dir: File, format: Format, fromSec: Int = 0, onProgress: (Float) -> Unit = {}): List<Piece> {
        dir.mkdirs()
        val pieces = ArrayList<Piece>()
        val perPiece = format.pieceSeconds * RATE
        var buf = ShortArray(perPiece)
        var filled = 0
        var startSec = fromSec
        val skip = fromSec.toLong() * RATE          // mono 16 kHz samples to drop before the first piece
        var seen = 0L

        fun flush() {
            if (filled == 0) return
            val end = startSec + (filled + RATE - 1) / RATE
            val file = File(dir, "piece_%05d.%s".format(startSec, format.ext))
            when (format) {
                Format.WAV -> writeWav(file, buf, filled)
                else -> encodeAac(buf, filled, file, format)
            }
            pieces += Piece(file, format, startSec, end)
            startSec = end
            filled = 0
            buf = ShortArray(perPiece)
        }

        decode(context, uri, onProgress) { mono ->
            var i = 0
            if (seen < skip) {
                val drop = minOf((skip - seen).toLong(), mono.size.toLong()).toInt()
                i = drop; seen += drop
            }
            while (i < mono.size) {
                val n = minOf(mono.size - i, perPiece - filled)
                System.arraycopy(mono, i, buf, filled, n)
                filled += n; i += n; seen += n
                if (filled == perPiece) flush()
            }
        }
        flush()
        return pieces
    }

    // ───────────── decoding: any format the phone plays → mono 16 kHz 16-bit ─────────────
    private fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit, sink: (ShortArray) -> Unit) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ApiException("This file has no audio.")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val codec = try {
                MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            } catch (e: Exception) {
                throw ApiException("This phone can't read this kind of audio. Try an MP3 or M4A file.")
            }
            try {
                codec.configure(inFormat, null, null, 0)
                codec.start()
                var channels = inFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
                var rate = inFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
                var float = false
                var resampler = AudioMath.Resampler(rate, RATE)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val ix = codec.dequeueInputBuffer(10_000)
                        if (ix >= 0) {
                            val input = codec.getInputBuffer(ix)!!
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(ix, 0, size, extractor.sampleTime, 0)
                                if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }
                    val ox = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            channels = f.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            rate = f.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                            float = f.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, 2) == 4   // AudioFormat.ENCODING_PCM_FLOAT
                            resampler = AudioMath.Resampler(rate, RATE)
                        }
                        ox >= 0 -> {
                            if (info.size > 0) {
                                val out = codec.getOutputBuffer(ox)!!.order(ByteOrder.nativeOrder())
                                out.position(info.offset); out.limit(info.offset + info.size)
                                val pcm = if (float) {
                                    val fb = out.asFloatBuffer(); ShortArray(fb.remaining()) { AudioMath.floatToShort(fb.get()) }
                                } else {
                                    val sb = out.asShortBuffer(); ShortArray(sb.remaining()).also { sb.get(it) }
                                }
                                sink(resampler.process(AudioMath.downmix(pcm, channels)))
                            }
                            codec.releaseOutputBuffer(ox, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                onProgress(1f)
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int) = if (containsKey(key)) getInteger(key) else fallback

    // ───────────── encoding: mono 16 kHz → AAC-LC 32 kbps ─────────────
    private fun encodeAac(pcm: ShortArray, count: Int, file: File, format: Format) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val frames = ArrayList<Pair<ByteArray, Long>>()   // encoded AAC frames and their times (µs)
        var outFormat: MediaFormat? = null
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val ix = codec.dequeueInputBuffer(10_000)
                    if (ix >= 0) {
                        val input = codec.getInputBuffer(ix)!!.order(ByteOrder.nativeOrder())
                        input.clear()
                        val n = minOf(count - fed, input.capacity() / 2)
                        val pts = fed * 1_000_000L / RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(ix, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            input.asShortBuffer().put(pcm, fed, n)
                            codec.queueInputBuffer(ix, 0, n * 2, pts, 0)
                            fed += n
                        }
                    }
                }
                val ox = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    ox >= 0 -> {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val out = codec.getOutputBuffer(ox)!!
                            out.position(info.offset); out.limit(info.offset + info.size)
                            frames += ByteArray(info.size).also { out.get(it) } to info.presentationTimeUs
                        }
                        codec.releaseOutputBuffer(ox, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
        }
        if (frames.isEmpty()) throw IllegalStateException("The AAC encoder produced nothing.")
        when (format) {
            Format.ADTS -> file.outputStream().buffered().use { o ->
                frames.forEach { (f, _) -> o.write(AudioMath.adtsHeader(f.size, RATE, 1)); o.write(f) }
            }
            else -> {
                val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val track = muxer.addTrack(outFormat ?: throw IllegalStateException("No AAC format from the encoder."))
                    muxer.start()
                    val bi = MediaCodec.BufferInfo()
                    frames.forEach { (f, pts) ->
                        bi.set(0, f.size, pts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(track, ByteBuffer.wrap(f), bi)
                    }
                    muxer.stop()
                } finally { muxer.release() }
            }
        }
    }

    fun writeWav(file: File, pcm: ShortArray, count: Int) {
        file.outputStream().buffered().use { o ->
            o.write(AudioMath.wavHeader(count * 2, RATE, 1))
            val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm, 0, count)
            o.write(bytes.array())
        }
    }
}

/** The arithmetic of [Audio], kept free of Android so it can be tested on the JVM. */
object AudioMath {
    /** Interleaved samples → one channel, by averaging. */
    fun downmix(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val out = ShortArray(pcm.size / channels)
        for (i in out.indices) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    fun floatToShort(f: Float): Short = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    /**
     * Linear-interpolation resampler that keeps its place between buffers. Good enough for speech
     * going into a transcription model; a simple averaging filter first, when shrinking a lot, keeps
     * high tones from folding down into noise.
     */
    class Resampler(private val from: Int, private val to: Int) {
        private val step = from.toDouble() / to
        private var pos = 0.0           // position of the next output sample, in input samples, relative to `prev`
        private var prev: Short = 0
        private var hasPrev = false
        private val taps = maxOf(1, (from / to))   // averaging width before shrinking

        fun process(input: ShortArray): ShortArray {
            if (from == to || input.isEmpty()) return input
            val src = if (taps > 1) smooth(input) else input
            // sample k of `src` sits at position k + 1 (position 0 is `prev`)
            val out = ArrayList<Short>((src.size / step).toInt() + 2)
            if (!hasPrev) { prev = src[0]; hasPrev = true; pos = 1.0 }
            while (pos <= src.size) {
                val i = pos.toInt()
                val frac = pos - i
                val a = if (i == 0) prev else src[i - 1]
                val b = if (i >= src.size) src[src.size - 1] else src[i]
                out += (a + (b - a) * frac).toInt().toShort()
                pos += step
            }
            pos -= src.size
            prev = src[src.size - 1]
            return out.toShortArray()
        }

        private var tail = ShortArray(0)
        private fun smooth(input: ShortArray): ShortArray {
            val all = tail + input
            val out = ShortArray(input.size)
            for (i in input.indices) {
                val end = i + tail.size
                var sum = 0; var n = 0
                for (k in maxOf(0, end - taps + 1)..end) { sum += all[k]; n++ }
                out[i] = (sum / n).toShort()
            }
            tail = all.copyOfRange(maxOf(0, all.size - (taps - 1)), all.size)
            return out
        }
    }

    /** 44-byte header of a PCM WAV file. */
    fun wavHeader(dataBytes: Int, rate: Int, channels: Int, bits: Int = 16): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(rate); putInt(rate * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(dataBytes)
        }.array()

    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** 7-byte ADTS header for one AAC-LC frame of [frameBytes] (without the header). */
    fun adtsHeader(frameBytes: Int, rate: Int, channels: Int): ByteArray {
        val len = frameBytes + 7
        val rateIndex = RATES.indexOf(rate).also { require(it >= 0) { "unsupported rate $rate" } }
        val profile = 2   // AAC LC
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((profile - 1) shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /** Joins transcribed pieces into notes parts of about [partSeconds] each: (start, end, text). */
    fun notesParts(pieces: List<Triple<Int, Int, String>>, partSeconds: Int): List<Triple<Int, Int, String>> {
        val parts = ArrayList<Triple<Int, Int, String>>()
        var start = -1; var end = 0; val text = StringBuilder()
        for ((s, e, t) in pieces) {
            if (start < 0) start = s
            if (t.isNotBlank()) { if (text.isNotEmpty()) text.append("\n\n"); text.append(t.trim()) }
            end = e
            if (end - start >= partSeconds) { parts += Triple(start, end, text.toString()); start = -1; text.clear() }
        }
        if (start >= 0) parts += Triple(start, end, text.toString())
        return parts.filter { it.third.isNotBlank() }
    }
}
