package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

/**
 * A figure cut out of one of your photos. The picture is fetched from your private storage once and kept
 * on the phone. Tap it to see it full screen.
 */
@Composable
fun FigureView(path: String, caption: String?, load: suspend (String) -> File, onLabel: (() -> Unit)? = null) {
    var file by remember(path) { mutableStateOf<File?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var attempt by remember(path) { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(path, attempt) {
        failed = false
        runCatching { load(path) }.onSuccess { file = it }.onFailure { failed = true }
    }
    val label = if (caption.isNullOrBlank()) "Figure" else "$caption · Figure"
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).border(1.dp, C.G200, shape).background(C.White)) {
        val f = file
        when {
            f != null -> AsyncImage(f, caption ?: "Figure", contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clickable(role = Role.Image) { open = true })
            failed -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Could not load this figure.", style = ts(13, 400, C.G600))
                Text("Try again", style = ts(13, 600, C.Ink), modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { attempt++ }.padding(vertical = 6.dp))
            }
            else -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), color = C.G500, strokeWidth = 2.dp)
            }
        }
        Row(Modifier.fillMaxWidth().background(C.G50).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ts(11, 500, C.G500), modifier = Modifier.weight(1f)
                .then(if (onLabel != null) Modifier.clickable(onClick = onLabel) else Modifier).padding(vertical = 10.dp))
        }
    }
    if (open) file?.let { FigureViewer(it, caption) { open = false } }
}

/** Full screen, pinch to zoom, drag to move, double-tap to reset. */
@Composable
fun FigureViewer(file: File, caption: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            AsyncImage(file, caption ?: "Figure", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.15f))
                .clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
                Icon(Ic.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (!caption.isNullOrBlank()) Text(caption, style = ts(14, 400, Color.White, 1.4f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(16.dp))
        }
    }
}

/** Edit a diagram's caption and its Mermaid code together. */
@Composable
fun DiagramEditDialog(caption: String, code: String, onDismiss: () -> Unit, onSave: (caption: String, code: String) -> Unit) {
    var c by remember { mutableStateOf(caption) }
    var k by remember { mutableStateOf(code) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit diagram", style = ts(18, 600)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(c, { c = it }, singleLine = true, label = { Text("Caption") }, textStyle = ts(15), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(k, { k = it }, label = { Text("Mermaid code") }, textStyle = ts(13).copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(c, k) }, enabled = k.isNotBlank()) { Text("Save", style = ts(15, 600)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) } },
        containerColor = C.White,
    )
}
