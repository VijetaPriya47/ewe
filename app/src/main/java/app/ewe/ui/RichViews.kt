package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.ewe.data.Rich

/** The shared look for code blocks, tables and diagrams: a bordered box with a subtle bar underneath. */
@Composable
fun ExtraFrame(label: String, copyLabel: String, copyText: String, onLabel: (() -> Unit)?,
               barExtras: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val toast = LocalToast.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).border(1.dp, C.G200, shape).background(C.White)) {
        content()
        Row(Modifier.fillMaxWidth().background(C.G50).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ts(11, 500, C.G500), modifier = Modifier.weight(1f)
                .then(if (onLabel != null) Modifier.clickable(onClick = onLabel) else Modifier).padding(vertical = 10.dp))
            barExtras()
            Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) {
                    copyText(context, label, copyText); toast("$copyLabel copied")
                }.semantics { contentDescription = "Copy ${copyLabel.lowercase()}" }.padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Ic.Copy, null, tint = C.G500, modifier = Modifier.size(13.dp))
                Text("Copy", style = ts(11, 500, C.G500))
            }
        }
    }
}

/** A code block: monospaced, scrolls sideways, never wraps (so indentation stays readable). */
@Composable
fun CodeBlock(code: String, lang: String?, onLabel: (() -> Unit)? = null) {
    ExtraFrame(if (lang.isNullOrBlank()) "Code" else "Code · $lang", "Code", code, onLabel) {
        Box(Modifier.fillMaxWidth().background(C.G50.copy(alpha = 0.6f)).horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(code, style = ts(13, 400, C.Ink, 1.5f).copy(fontFamily = FontFamily.Monospace), softWrap = false)
        }
    }
}

/** A Markdown table drawn as a grid; wide tables scroll sideways. Falls back to the raw text. */
@Composable
fun NoteTable(markdown: String, onLabel: (() -> Unit)? = null) {
    val table = remember(markdown) { Rich.parseTable(markdown) }
    ExtraFrame("Table", "Table", markdown, onLabel) {
        if (table == null) {
            Text(markdown, style = ts(14, 400, C.Ink, 1.45f), modifier = Modifier.padding(12.dp))
            return@ExtraFrame
        }
        val cols = table.columns
        // Every column is as wide as its widest cell (within limits), so rows line up.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val widths = remember(table) {
            (0 until cols).map { c ->
                val cells = listOf(table.header.getOrNull(c).orEmpty()) + table.rows.map { it.getOrNull(c).orEmpty() }
                val widest = cells.maxOf { cell ->
                    measurer.measure(cell, ts(13, if (cell === cells[0]) 600 else 400)).size.width
                }
                with(density) { (widest.toDp() + 21.dp).coerceIn(56.dp, 240.dp) }
            }
        }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TableRow(table.header, widths, header = true)
            table.rows.forEachIndexed { i, row -> TableRow(row, widths, header = false, shaded = i % 2 == 1) }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, widths: List<androidx.compose.ui.unit.Dp>, header: Boolean, shaded: Boolean = false) {
    Row(Modifier.height(IntrinsicSize.Min).background(if (header) C.G100 else if (shaded) C.G50 else Color.Transparent)) {
        widths.forEachIndexed { c, w ->
            if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(C.G200))
            Text(cells.getOrNull(c).orEmpty(), style = ts(13, if (header) 600 else 400, C.Ink, 1.4f),
                modifier = Modifier.width(w).padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
    Box(Modifier.height(1.dp).width(widths.fold(0.dp) { a, w -> a + w } + (widths.size - 1).coerceAtLeast(0).dp).background(C.G200))
}

fun highlightColor(color: String?): Color? = when (color) {
    "yellow" -> C.HighlightYellow
    "red" -> C.HighlightRed
    else -> null
}
