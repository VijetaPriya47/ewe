package app.ewe.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ewe.data.CropHandle
import app.ewe.data.CropMath
import app.ewe.data.CropRect
import app.ewe.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen cropper. [onCropped] gets a new JPEG file; Cancel keeps the photo as it was.
 * With [onFigure], "Save as figure" keeps the cut-out as a picture for your notes and leaves the photo unchanged.
 */
@Composable
fun CropDialog(uri: Uri, onDismiss: () -> Unit, onFigure: ((Uri) -> Unit)? = null, initial: CropRect = CropRect(),
               title: String = "Crop photo", onCropped: (Uri) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CropScreen(uri, onDismiss, onFigure, onCropped, initial, title)
    }
}

@Composable
fun CropScreen(uri: Uri, onCancel: () -> Unit, onFigure: ((Uri) -> Unit)? = null, onCropped: (Uri) -> Unit,
               initial: CropRect = CropRect(), title: String = "Crop photo") {
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var original by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var turns by remember(uri) { mutableIntStateOf(0) }
    var rect by remember(uri) { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        runCatching { withContext(Dispatchers.IO) { Images.load(context, uri, Images.AI_MAX_SIDE) } }
            .onSuccess { original = it }
            .onFailure { toast("Could not open this photo."); onCancel() }
    }
    fun save(prefix: String, then: (Uri) -> Unit) {
        val bmp = original ?: return
        if (saving) return
        saving = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val out = CropMath.apply(bmp, rect, turns)
                    val dir = File(context.cacheDir, "crop").apply { mkdirs() }
                    File(dir, "${prefix}_${System.currentTimeMillis()}.jpg").also { it.writeBytes(Images.jpeg(out, 92)) }
                }
            }.onSuccess { then(Uri.fromFile(it)) }
                .onFailure { saving = false; toast("Could not crop this photo.") }
        }
    }
    // What is shown: the photo turned as chosen (the crop applies to this).
    val shown = remember(original, turns) { original?.let { CropMath.apply(it, CropRect(), turns) } }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton("Cancel", C.G300) { onCancel() }
            Text(title, style = ts(16, 600, Color.White), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            BarButton(if (saving) "Saving…" else "Done", Color.White, bold = true) {
                if (original == null || saving) return@BarButton
                if (rect == initial && turns % 4 == 0) { onCancel(); return@BarButton }
                save("crop", onCropped)
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            val bmp = shown
            if (bmp == null) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp); return@BoxWithConstraints }
            val density = LocalDensity.current
            val maxW = with(density) { maxWidth.toPx() }
            val maxH = with(density) { maxHeight.toPx() }
            val scale = minOf(maxW / bmp.width, maxH / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val slop = with(density) { 28.dp.toPx() }
            Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
                Image(bmp.asImageBitmap(), "Photo to crop", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Crop area" }.pointerInput(bmp) {
                    var handle: CropHandle? = null
                    detectDragGestures(
                        onDragStart = { p -> handle = CropMath.hit(rect, p.x / w, p.y / h, slop / w, slop / h) },
                        onDragEnd = { handle = null },
                        onDragCancel = { handle = null },
                    ) { change, drag ->
                        val hd = handle ?: return@detectDragGestures
                        change.consume()
                        rect = CropMath.drag(rect, hd, drag.x / w, drag.y / h)
                    }
                }) {
                    val l = rect.l * size.width; val t = rect.t * size.height
                    val r = rect.r * size.width; val b = rect.b * size.height
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, Offset.Zero, Size(size.width, t))
                    drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                    drawRect(dim, Offset(0f, t), Size(l, b - t))
                    drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
                    val thin = 1.dp.toPx()
                    for (k in 1..2) {
                        val x = l + (r - l) * k / 3; val y = t + (b - t) * k / 3
                        drawLine(Color.White.copy(alpha = 0.45f), Offset(x, t), Offset(x, b), thin)
                        drawLine(Color.White.copy(alpha = 0.45f), Offset(l, y), Offset(r, y), thin)
                    }
                    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
                    val len = 18.dp.toPx(); val sw = 4.dp.toPx()
                    listOf(Offset(l, t) to Offset(1f, 1f), Offset(r, t) to Offset(-1f, 1f), Offset(l, b) to Offset(1f, -1f), Offset(r, b) to Offset(-1f, -1f))
                        .forEach { (c, d) ->
                            drawLine(Color.White, c, Offset(c.x + d.x * len, c.y), sw)
                            drawLine(Color.White, c, Offset(c.x, c.y + d.y * len), sw)
                        }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            PillButton(Ic.Rotate, "Rotate") { turns = (turns + 1) % 4; rect = CropRect() }
            PillButton(Ic.Replay, "Reset") { turns = 0; rect = CropRect() }
            if (onFigure != null) PillButton(Ic.Image, "Save as figure") { save("figure", onFigure) }
        }
        Text(if (onFigure != null) "Drag the corners or edges. Done crops the photo; Save as figure keeps the cut-out as a picture in your notes."
            else "Drag the corners or edges. Drag inside to move. Only the photo sent changes.",
            style = ts(12, 400, C.G400), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp))
    }
}

@Composable
private fun BarButton(text: String, color: Color, bold: Boolean = false, onClick: () -> Unit) {
    Text(text, style = ts(15, if (bold) 600 else 500, color),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun PillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.height(40.dp).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.12f))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, style = ts(14, 500, Color.White))
    }
}
