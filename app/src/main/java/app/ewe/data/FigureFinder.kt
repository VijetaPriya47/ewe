package app.ewe.data

import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A figure the AI found on a PDF page, cut out and waiting for you to keep, fix or remove it. */
data class FigureCandidate(
    val id: String,
    /** Page number in the PDF (1-based). */
    val page: Int,
    /** Which part (10-page message) it goes with. */
    val part: Int,
    /** The whole page as drawn on the phone; re-cropping starts from it. */
    val pageImage: Uri,
    /** Where the figure is on the page (0–1). */
    val rect: CropRect,
    /** The cut-out that will be sent. */
    val crop: Uri,
    val label: String,
)

/** The figures found in one PDF and where they are in being checked and added. */
data class FigureReview(
    val name: String,
    val dir: java.io.File,
    val finding: Boolean = false,
    val stage: String = "",
    val candidates: List<FigureCandidate> = emptyList(),
    /** Pages that could not be searched. */
    val note: String? = null,
    /** You pressed Add while the notes were still being made. */
    val queued: Boolean = false,
    val adding: Boolean = false,
    val error: String? = null,
)

/** Turns the find-figures answer into crop areas on the page. */
object FigureFinder {
    data class Box(val page: Int, val top: Int, val left: Int, val bottom: Int, val right: Int, val label: String)

    /** Room left around the AI's box, as a share of the page: its edges are often a little tight. */
    const val MARGIN = 0.015f

    fun parse(res: JsonObject, pageCount: Int): List<Box> = runCatching {
        res["figures"]!!.jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            fun n(k: String) = o[k]?.jsonPrimitive?.doubleOrNull?.toInt()
            val page = n("page") ?: return@mapNotNull null
            val box = Box(page, n("top") ?: return@mapNotNull null, n("left") ?: return@mapNotNull null,
                n("bottom") ?: return@mapNotNull null, n("right") ?: return@mapNotNull null,
                o["label"]?.jsonPrimitive?.contentOrNull.orEmpty())
            box.takeIf { page in 1..pageCount && it.bottom > it.top && it.right > it.left }
        }
    }.getOrDefault(emptyList())

    fun rect(b: Box, margin: Float = MARGIN): CropRect = CropRect(
        (b.left / 1000f - margin).coerceIn(0f, 1f), (b.top / 1000f - margin).coerceIn(0f, 1f),
        (b.right / 1000f + margin).coerceIn(0f, 1f), (b.bottom / 1000f + margin).coerceIn(0f, 1f),
    )
}
