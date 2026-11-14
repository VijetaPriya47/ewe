package app.ewe.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Turns PDF pages into images on the phone, so any provider that reads photos can read them. */
object Pdf {
    /** Pages per message, the same limit as photos. */
    const val PAGES_PER_MESSAGE = 10
    /** Most pages sent in one go (it becomes several messages). */
    const val MAX_PAGES = 60
    /** Width a page is drawn at: enough for small print, well under what vision models accept. */
    private const val RENDER_WIDTH = 1600

    data class Info(val name: String, val pages: Int)

    fun info(context: Context, uri: Uri): Info {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
        return Info(name, open(context, uri).use { it.pageCount })
    }

    /** Draws pages [from]..[to] (1-based, inclusive) into JPEG files in [dir], in order. */
    fun render(context: Context, uri: Uri, from: Int, to: Int, dir: File, onPage: (Int) -> Unit = {}): List<Uri> {
        dir.mkdirs()
        return open(context, uri).use { pdf ->
            (from..to).map { n ->
                onPage(n)
                pdf.openPage(n - 1).use { page ->
                    val width = RENDER_WIDTH
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, width * 3)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE) // PDF pages are transparent; JPEG would turn that black
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val file = File(dir, "page_%03d.jpg".format(n))
                    file.writeBytes(Images.jpeg(bitmap, 85))
                    bitmap.recycle()
                    Uri.fromFile(file)
                }
            }
        }
    }

    private fun open(context: Context, uri: Uri): PdfRenderer {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Could not open the PDF.")
        return try {
            PdfRenderer(fd)
        } catch (e: SecurityException) {
            fd.close(); throw ApiException("This PDF is password-protected. Remove the password and try again.")
        } catch (e: Exception) {
            fd.close(); throw ApiException("This file could not be read as a PDF.")
        }
    }

    /** "Pages 1–10" / "Page 7" */
    fun pagesLabel(from: Int, to: Int) = if (from == to) "page $from" else "pages $from–$to"

    /** Splits [from]..[to] into runs of at most [PAGES_PER_MESSAGE]. */
    fun chunks(from: Int, to: Int): List<IntRange> =
        (from..to).chunked(PAGES_PER_MESSAGE).map { it.first()..it.last() }
}
