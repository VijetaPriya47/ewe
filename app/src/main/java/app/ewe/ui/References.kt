package app.ewe.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ewe.data.Reference
import app.ewe.data.Repository
import app.ewe.data.Rich
import app.ewe.data.Session
import app.ewe.data.Snapshot

fun openLink(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
}.getOrDefault(false)

/** A note with its [[ref:…]] marks drawn as small tappable [n] chips. */
@Composable
fun NoteText(content: String, style: TextStyle, snapshot: Snapshot, onRef: (String) -> Unit) {
    val pieces = remember(content) { Rich.pieces(content) }
    if (pieces.none { it.second != null }) { Text(content, style = style); return }
    val inline = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        pieces.forEachIndexed { i, (plain, refId) ->
            if (refId == null) append(plain) else {
                val ref = snapshot.reference(refId)
                val label = ref?.number?.toString() ?: "?"
                val key = "ref$i"
                append(" ")
                appendInlineContent(key, "[$label]")
                inline[key] = InlineTextContent(
                    Placeholder(width = (12 + 7 * label.length).sp, height = 17.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
                ) { RefChip(label, ref != null) { onRef(refId) } }
            }
        }
    }
    Text(text, style = style, inlineContent = inline)
}

@Composable
fun RefChip(label: String, exists: Boolean = true, onClick: (() -> Unit)? = null) {
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(if (exists) C.BlueBg else C.G100)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics { contentDescription = "Reference $label" }.padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = ts(11, 600, if (exists) C.Blue else C.G500), maxLines = 1)
    }
}

/** Shown under a note when one of its [n] chips is tapped. */
@Composable
fun RefCard(ref: Reference?, onAll: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.padding(start = 17.dp, top = 2.dp, bottom = 6.dp).fillMaxWidth().clip(shape).border(1.dp, C.G200, shape).background(C.White)
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ref == null) {
            Text("This reference was deleted.", style = ts(13, 400, C.G600))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CardButton("All references", false, onClick = onAll); CardButton("Close", false, onClick = onClose) }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RefChip(ref.number.toString())
            Text(ref.title, style = ts(14, 600), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(ref.url, style = ts(12, 400, C.Blue), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (ref.description.isNotBlank()) Text(ref.description, style = ts(12, 400, C.G600, 1.4f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Open", true, Ic.Open) { if (!openLink(context, ref.url)) toast("No app can open this link.") }
            CardButton("Copy", false, Ic.Copy) { copyText(context, "Link", ref.url); toast("Link copied") }
            CardButton("All references", false, onClick = onAll)
        }
    }
}

@Composable
private fun CardButton(text: String, dark: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(Modifier.height(34.dp).clip(shape).background(if (dark) C.Ink else C.White).then(if (dark) Modifier else Modifier.border(1.dp, C.G200, shape))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, tint = if (dark) C.White else C.Ink, modifier = Modifier.size(14.dp))
        Text(text, style = ts(13, 600, if (dark) C.White else C.Ink))
    }
}

// ═══════════════════════════ References tab ═══════════════════════════

@Composable
fun ColumnScope.ReferencesTab(repo: Repository, snap: Snapshot, session: Session) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val run = rememberRunner()
    val refs = snap.referencesOf(session.id)
    var editing by remember { mutableStateOf<Reference?>(null) }
    var deleting by remember { mutableStateOf<Reference?>(null) }

    if (refs.isEmpty()) {
        Column(Modifier.fillMaxWidth().weight(1f).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No references yet", style = ts(16, 600))
            Text("Links in what you send are saved here and cited in your notes as [1], [2] …\n\n" +
                "To save links only, choose Reference above the message box, or start the message with \"reference\".",
                style = ts(14, 400, C.G600, 1.45f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 24.dp)) {
        item { Text("${plural(refs.size, "reference")} · each link is kept once", style = ts(12, 400, C.G600), modifier = Modifier.padding(bottom = 4.dp)) }
        items(refs, key = { it.id }) { r ->
            val cited = snap.citations[r.id] ?: 0
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { editing = r }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(C.BlueBg), contentAlignment = Alignment.Center) {
                    Text("${r.number}", style = ts(13, 700, C.Blue))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(r.title, style = ts(15, 600), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(r.url.removePrefix("https://").removePrefix("http://"), style = ts(12, 400, C.Blue), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.description.isNotBlank()) Text(r.description, style = ts(13, 400, C.G600, 1.4f))
                    Text("Added ${relativeDay(r.dateAdded).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }}" +
                        if (cited > 0) " · cited ${cited}×" else " · not cited yet", style = ts(11, 400, C.G500))
                }
                Column {
                    IconBtn(Ic.Open, "Open link", tint = C.G500, size = 36.dp, iconSize = 18.dp) { if (!openLink(context, r.url)) toast("No app can open this link.") }
                    IconBtn(Ic.Copy, "Copy link", tint = C.G500, size = 36.dp, iconSize = 18.dp) { copyText(context, "Link", r.url); toast("Link copied") }
                }
            }
            Divider()
        }
        item { Text("Numbers never change. Tap a reference to edit or delete it.", style = ts(12, 400, C.G500), modifier = Modifier.padding(top = 10.dp)) }
    }

    editing?.let { r ->
        ReferenceDialog(r, onDismiss = { editing = null }, onDelete = { editing = null; deleting = r }) { title, url, desc ->
            editing = null
            run.go { repo.updateReference(r.id, title, url, desc) }
        }
    }
    deleting?.let { r ->
        val cited = snap.citations[r.id] ?: 0
        Confirm("Delete reference [${r.number}]?",
            (if (cited > 0) "${plural(cited, "note")} cite it; they will show [?]. " else "") + "The other references keep their numbers.",
            "Delete", danger = true, onDismiss = { deleting = null }) {
            deleting = null
            run.go { repo.deleteReference(r.id) }
        }
    }
}

@Composable
private fun ReferenceDialog(r: Reference, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf(r.title) }
    var url by remember { mutableStateOf(r.url) }
    var desc by remember { mutableStateOf(r.description) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.White,
        title = { Text("Reference [${r.number}]", style = ts(18, 600)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, textStyle = ts(15), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Link") }, singleLine = true, textStyle = ts(14), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, textStyle = ts(14), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, url, desc) }, enabled = url.isNotBlank()) { Text("Save", style = ts(15, 600)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", style = ts(15, 500, C.Red)) }
                TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) }
            }
        },
    )
}
