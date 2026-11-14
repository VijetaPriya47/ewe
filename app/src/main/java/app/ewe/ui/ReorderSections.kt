package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import app.ewe.data.Section

/** Pure list logic for the reorder screen, kept apart so it can be tested. */
object Reorder {
    /** Where a row dragged by [offsetPx] from [from] lands, with rows [rowPx] tall. */
    fun target(from: Int, offsetPx: Float, rowPx: Float, size: Int): Int =
        (from + Math.round(offsetPx / rowPx)).coerceIn(0, size - 1)

    fun <T> move(list: List<T>, from: Int, to: Int): List<T> =
        if (from == to) list else list.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Drag section titles into a new order. Drag the handle, or long-press anywhere on a row and drag.
 * [onSave] gets the section ids in their new order.
 */
@Composable
fun ReorderSectionsDialog(sections: List<Section>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ReorderSectionsScreen(sections, onDismiss, onSave)
    }
}

private val ROW = 56.dp
private const val GAP_DP = 8

@Composable
fun ReorderSectionsScreen(sections: List<Section>, onCancel: () -> Unit, onSave: (List<String>) -> Unit) {
    val order = remember(sections) { mutableStateListOf(*sections.toTypedArray()) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { (ROW + GAP_DP.dp).toPx() }
    val changed = order.map { it.id } != sections.map { it.id }

    fun start(id: String) { dragging = id; offset = 0f }
    fun drag(dy: Float) { offset += dy }
    fun drop() {
        val id = dragging ?: return
        val from = order.indexOfFirst { it.id == id }
        if (from >= 0) {
            val to = Reorder.target(from, offset, stepPx, order.size)
            if (to != from) order.add(to, order.removeAt(from))
        }
        dragging = null; offset = 0f
    }
    fun moveBy(i: Int, d: Int): Boolean {
        val to = i + d
        if (to !in order.indices) return false
        order.add(to, order.removeAt(i)); return true
    }

    Column(Modifier.fillMaxSize().background(C.White).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cancel", style = ts(15, 500, C.G600), modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickableRole { onCancel() }.padding(horizontal = 12.dp, vertical = 10.dp))
            Text("Reorder sections", style = ts(16, 600), modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Done", style = ts(15, 600, if (changed) C.Ink else C.G400), modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickableRole { if (changed) onSave(order.map { it.id }) else onCancel() }.padding(horizontal = 12.dp, vertical = 10.dp))
        }
        Text("Drag a section by its handle, or press and hold a title, then drag. The points inside move with it.",
            style = ts(13, 400, C.G600, 1.4f), modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState(), enabled = dragging == null)
            .padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            val from = order.indexOfFirst { it.id == dragging }
            val to = if (from >= 0) Reorder.target(from, offset, stepPx, order.size) else -1
            order.forEachIndexed { i, s ->
                val isDragged = s.id == dragging
                // rows between the old and new place slide over to make room
                val shift = when {
                    isDragged || from < 0 -> 0f
                    i in (from + 1)..to -> -stepPx
                    i in to until from -> stepPx
                    else -> 0f
                }
                val shape = RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().height(ROW)
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) offset else shift }
                    .then(if (isDragged) Modifier.shadow(8.dp, shape) else Modifier)
                    .clip(shape).background(if (isDragged) C.G50 else C.White).border(1.dp, if (isDragged) C.G300 else C.G200, shape)
                    .pointerInput(s.id) {
                        detectDragGesturesAfterLongPress(onDragStart = { start(s.id) }, onDragEnd = { drop() }, onDragCancel = { drop() }) { c, d -> c.consume(); drag(d.y) }
                    }
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Move up") { moveBy(order.indexOf(s), -1) },
                            CustomAccessibilityAction("Move down") { moveBy(order.indexOf(s), 1) },
                        )
                    }
                    .padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = ts(13, 600, C.G500), modifier = Modifier.padding(end = 12.dp))
                    Text(s.title, style = ts(15, 600), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(Modifier.size(ROW).pointerInput(s.id) {
                            detectDragGestures(onDragStart = { start(s.id) }, onDragEnd = { drop() }, onDragCancel = { drop() }) { c, d -> c.consume(); drag(d.y) }
                        }.semantics { contentDescription = "Drag ${s.title}" }, contentAlignment = Alignment.Center) {
                        Icon(Ic.Grip, null, tint = C.G500, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private fun Modifier.clickableRole(onClick: () -> Unit) =
    this.clickable(role = Role.Button, onClick = onClick)
