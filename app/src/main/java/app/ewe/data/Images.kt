package app.ewe.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

object Images {
    /** Long side sent to the AI. Vision models downscale beyond this anyway. */
    const val AI_MAX_SIDE = 2048
    /** The optional small copy kept on the phone: about 800 px / 50 KB. */
    const val COPY_MAX_SIDE = 800
    private const val COPY_MAX_BYTES = 60_000

    fun load(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Could not read the photo.")
        val rotation = runCatching {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        return fit(raw, maxSide, rotation)
    }

    fun fit(src: Bitmap, maxSide: Int, rotation: Float = 0f): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / max(src.width, src.height))
        if (scale == 1f && rotation == 0f) return src
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** JPEG for the small on-phone copy, stepping quality down until it is about 50 KB. */
    fun smallCopy(bitmap: Bitmap): ByteArray {
        val small = fit(bitmap, COPY_MAX_SIDE)
        var q = 70
        var bytes = jpeg(small, q)
        while (bytes.size > COPY_MAX_BYTES && q > 30) { q -= 10; bytes = jpeg(small, q) }
        return bytes
    }
}
