package app.ewe.data

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.roundToInt

/** The crop area as fractions of the (rotated) photo: 0 = left/top edge, 1 = right/bottom edge. */
data class CropRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val isFull: Boolean get() = l <= 0f && t <= 0f && r >= 1f && b >= 1f
}

/** What a finger is dragging: a corner, an edge, or the whole area. */
enum class CropHandle { TL, T, TR, R, BR, B, BL, L, MOVE }

object CropMath {
    /** Smallest crop, as a fraction of each side. */
    const val MIN = 0.08f

    /** Which handle is under (x, y). [slopX]/[slopY] is how near counts, as fractions of each side. */
    fun hit(rect: CropRect, x: Float, y: Float, slopX: Float, slopY: Float): CropHandle? {
        val nearL = kotlin.math.abs(x - rect.l) <= slopX
        val nearR = kotlin.math.abs(x - rect.r) <= slopX
        val nearT = kotlin.math.abs(y - rect.t) <= slopY
        val nearB = kotlin.math.abs(y - rect.b) <= slopY
        val withinX = x >= rect.l - slopX && x <= rect.r + slopX
        val withinY = y >= rect.t - slopY && y <= rect.b + slopY
        return when {
            nearT && nearL -> CropHandle.TL
            nearT && nearR -> CropHandle.TR
            nearB && nearL -> CropHandle.BL
            nearB && nearR -> CropHandle.BR
            nearT && withinX -> CropHandle.T
            nearB && withinX -> CropHandle.B
            nearL && withinY -> CropHandle.L
            nearR && withinY -> CropHandle.R
            x > rect.l && x < rect.r && y > rect.t && y < rect.b -> CropHandle.MOVE
            else -> null
        }
    }

    /** Moves [handle] by (dx, dy) — fractions of the photo — keeping the area inside it and not too small. */
    fun drag(rect: CropRect, handle: CropHandle, dx: Float, dy: Float): CropRect {
        if (handle == CropHandle.MOVE) {
            val mx = dx.coerceIn(-rect.l, 1f - rect.r)
            val my = dy.coerceIn(-rect.t, 1f - rect.b)
            return CropRect(rect.l + mx, rect.t + my, rect.r + mx, rect.b + my)
        }
        var (l, t, r, b) = rect
        if (handle in setOf(CropHandle.TL, CropHandle.L, CropHandle.BL)) l = (l + dx).coerceIn(0f, r - MIN)
        if (handle in setOf(CropHandle.TR, CropHandle.R, CropHandle.BR)) r = (r + dx).coerceIn(l + MIN, 1f)
        if (handle in setOf(CropHandle.TL, CropHandle.T, CropHandle.TR)) t = (t + dy).coerceIn(0f, b - MIN)
        if (handle in setOf(CropHandle.BL, CropHandle.B, CropHandle.BR)) b = (b + dy).coerceIn(t + MIN, 1f)
        return CropRect(l, t, r, b)
    }

    /** Turns [bitmap] by [quarterTurns] × 90° clockwise, then cuts out [rect]. */
    fun apply(bitmap: Bitmap, rect: CropRect, quarterTurns: Int): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        val turned = if (turns == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f * turns) }, true)
        if (rect.isFull) return turned
        val x = (rect.l * turned.width).roundToInt().coerceIn(0, turned.width - 1)
        val y = (rect.t * turned.height).roundToInt().coerceIn(0, turned.height - 1)
        val w = ((rect.r - rect.l) * turned.width).roundToInt().coerceIn(1, turned.width - x)
        val h = ((rect.b - rect.t) * turned.height).roundToInt().coerceIn(1, turned.height - y)
        return Bitmap.createBitmap(turned, x, y, w, h)
    }
}
