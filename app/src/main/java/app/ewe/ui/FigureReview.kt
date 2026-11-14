package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ewe.data.FigureCandidate
import app.ewe.data.FigureReview
import coil.compose.AsyncImage

/**
 * Figures found in a PDF, shown in the chat while the notes are being made: remove the wrong ones,
 * tap one to fix its cut, then add them. They go into the notes once those are done.
 */
@Composable
fun FigureReviewCard(
    review: FigureReview, notesRunning: Boolean,
    onRemove: (FigureCandidate) -> Unit, onFix: (FigureCandidate) -> Unit,
    onAdd: () -> Unit, onSkip: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, C.G200, shape).background(C.White).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val n = review.candidates.size
        val busy = review.finding || review.adding || review.queued
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Ic.Image, null, tint = C.Purple, modifier = Modifier.size(18.dp))
            Text(when {
                review.finding -> "Figures in ${review.name}"
                n == 0 -> "No figures to add"
                else -> "Found ${if (n == 1) "1 figure" else "$n figures"} in ${review.name}"
            }, style = ts(14, 600), modifier = Modifier.weight(1f))
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), color = C.G500, strokeWidth = 2.dp)
            Text(when {
                review.queued -> "Will be added as soon as the notes are done."
                else -> review.stage
            }, style = ts(13, 400, C.G600))
        } else if (n > 0) Text(
            "Remove any that aren't figures; tap one to fix its cut." + if (notesRunning) " Notes are being made meanwhile." else "",
            style = ts(13, 400, C.G600, 1.4f))
        review.note?.let { Text(it, style = ts(12, 400, C.Amber, 1.4f)) }
        review.error?.let { Text("Figures were not added: $it", style = ts(13, 400, C.Red, 1.4f)) }

        if (!review.finding && n > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            review.candidates.forEach { c ->
                Box {
                    AsyncImage(c.crop, c.label.ifBlank { "Figure from page ${c.page}" }, contentScale = ContentScale.Fit,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(C.G50).border(1.dp, C.G200, RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Fix the cut") { onFix(c) })
                    Text("p. ${c.page}", style = ts(10, 600, C.White), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .clip(RoundedCornerShape(6.dp)).background(C.Ink.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 1.dp))
                    if (!busy) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(24.dp).clip(CircleShape).background(C.Ink)
                        .clickable(role = Role.Button) { onRemove(c) }.semantics { contentDescription = "Remove figure from page ${c.page}" },
                        contentAlignment = Alignment.Center) {
                        Icon(Ic.Close, null, tint = C.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }

        if (!review.adding) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!review.finding && !review.queued && n > 0)
                Pill(if (review.error != null) "Try again" else "Add ${if (n == 1) "1 figure" else "$n figures"}", C.Ink, C.White, onAdd)
            Pill(if (n == 0 && !review.finding) "OK" else "Skip figures", C.White, C.Ink, onSkip)
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, C.G300, RoundedCornerShape(10.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = ts(13, 600, fg))
    }
}
