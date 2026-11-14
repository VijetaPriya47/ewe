package app.ewe.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The line icons from the mockup, as 24×24 vectors. Tint with Icon(tint = …). */
object Ic {
    private fun stroke(d: String, width: Float = 2f) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

    private fun fill(d: String) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)).build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    val Back = stroke("M15 18l-6-6 6-6")
    val Chevron = stroke("M9 6l6 6-6 6")
    val Settings = stroke("M4 7h9M17 7h3M4 17h3M11 17h9" + circle(15f, 7f, 2f) + circle(9f, 17f, 2f))
    val Search = stroke(circle(11f, 11f, 7f) + "M20 20l-3.5-3.5")
    val Headphones = stroke("M4 14v-2a8 8 0 0 1 16 0v2M4.5 14h1A1.5 1.5 0 0 1 7 15.5v3A1.5 1.5 0 0 1 5.500 20h-1A1.5 1.5 0 0 1 3 18.500v-3A1.5 1.5 0 0 1 4.500 14zM18.500 14h1a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5z")
    val Plus = stroke("M12 5v14M5 12h14")
    val More = stroke("M12 5h.01M12 12h.01M12 19h.01", 3f)
    val Attach = stroke("M21 12l-8.5 8.5a5 5 0 0 1-7-7L14 5a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L15 8")
    val Camera = stroke("M4 8h3l2-3h6l2 3h3v11H4z" + circle(12f, 13f, 3.5f))
    val Send = stroke("M12 19V5M5 12l7-7 7 7", 2.2f)
    val Edit = stroke("M4 20h4L19 9l-4-4L4 16z")
    val Trash = stroke("M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13")
    val Link = stroke("M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1", 2.2f)
    val Lock = stroke("M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2zM8 11V8a4 4 0 0 1 8 0v3")
    val Refresh = stroke("M4 12a8 8 0 0 1 14-5.3M20 4v4h-4M20 12a8 8 0 0 1-14 5.3M4 20v-4h4")
    val Info = stroke(circle(12f, 12f, 9f) + "M12 8v5M12 16h.01")
    val Replay = stroke("M4 12a8 8 0 1 0 3-6.2M4 4v4h4")
    val SkipNext = stroke("M5 5l10 7-10 7zM19 5v14")
    val Close = stroke("M6 6l12 12M18 6L6 18")
    val Check = stroke("M5 12.5l4.5 4.5L19 7.500", 2.4f)
    val Open = stroke("M14 4h6v6M20 4l-9 9M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5")
    val Spark = stroke("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z")
    val Crop = stroke("M6 2v14a2 2 0 0 0 2 2h14M2 6h14a2 2 0 0 1 2 2v14")
    val Rotate = stroke("M20 12a8 8 0 1 1-2.3-5.7M20 4v4h-4")
    val Copy = stroke("M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1zM5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1")
    val Speaker = stroke("M4 9h4l5-4v14l-5-4H4zM16.5 8.5a5 5 0 0 1 0 7M19 6a8.500 8.500 0 0 1 0 12")
    val Image = stroke("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM4 16l5-5 4 4 2-2 5 5" + circle(15.5f, 8.5f, 1.5f))
    val Grip = stroke("M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01", 3f)
    val Expand = stroke("M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7")
    val Indent = stroke("M4 6h16M10 12h10M10 18h10M4 10l3 2-3 2")
    val Outdent = stroke("M4 6h16M10 12h10M10 18h10M7 10l-3 2 3 2")
    val Stop = fill("M7 6h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z")
    val Play = fill("M8 5l11 7-11 7z")
    val Pause = fill("M7 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM15 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z")
}
