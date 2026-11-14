package app.ewe.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.ewe.data.Pdf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import app.ewe.podcast.ReaderState
import app.ewe.podcast.NotesReader
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.ewe.data.Message
import app.ewe.data.Point
import app.ewe.data.Repository
import app.ewe.data.Rich
import app.ewe.data.Session
import app.ewe.data.Snapshot
import app.ewe.data.Upload
import coil.compose.AsyncImage
import java.io.File
import java.time.LocalDate

private const val MAX_PHOTOS = 10
private const val MAX_FIGURES = 6

@Composable
fun SessionScreen(repo: Repository, sessionId: String, startOnNotes: Boolean, onBack: () -> Unit,
                  onOpenSession: (String) -> Unit, onSettings: () -> Unit) {
    val snap by repo.snapshot.collectAsState()
    val session = snap.session(sessionId)
    // 0 Session · 1 Notes · 2 Overview · 3 References
    var tab by rememberSaveable { mutableStateOf(if (startOnNotes) 1 else 0) }
    var showOptions by rememberSaveable { mutableStateOf(false) }

    if (session == null) {
        Column(Modifier.fillMaxSize().background(C.White)) {
            TopBar("Session", onBack)
            Text("This session is not on this phone yet. Check your connection and try again.", style = ts(15, 400, C.G600, 1.45f), modifier = Modifier.padding(16.dp))
        }
        return
    }

    val context = LocalContext.current
    val toast = LocalToast.current
    val reader = remember { NotesReader.get(context) }
    val reading by reader.state.collectAsState()
    val readerError by reader.error.collectAsState()
    LaunchedEffect(readerError) { readerError?.let { toast(it); reader.clearError() } }
    // Reading stops when you leave this session (it keeps going if the phone is locked or you switch apps).
    DisposableEffect(sessionId) { onDispose { if (reader.state.value?.sessionId == sessionId) reader.stop() } }

    Column(Modifier.fillMaxSize().background(C.White).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, "Back to sessions", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(session.title, style = ts(17, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TypeTag(session, 11); StatusTag(session, 11) }
            }
            IconBtn(Ic.More, "Session options") { showOptions = true }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            listOf("Session", "Notes", "Overview", "References").forEachIndexed { i, label ->
                Tab(label, tab == i, Modifier.weight(1f)) { tab = i }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.G200))

        when (tab) {
            1 -> NotesTab(repo, snap, session, onOpenSession, reader, reading?.takeIf { it.sessionId == session.id }, onOpenTab = { tab = it })
            2 -> OverviewTab(repo, snap, session)
            3 -> ReferencesTab(repo, snap, session)
            else -> ChatTab(repo, snap, session, onOpenTab = { tab = it }, onSettings = onSettings)
        }
        reading?.takeIf { it.sessionId == session.id }?.let { ReaderBar(reader, it) }
    }

    if (showOptions) SessionOptions(repo, snap, session, onDismiss = { showOptions = false }, onOpenSession = onOpenSession)
}

@Composable
private fun Tab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(44.dp).clickable(role = Role.Tab, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(text, style = ts(14, if (selected) 600 else 500, if (selected) C.Ink else C.G500), maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) C.Ink else androidx.compose.ui.graphics.Color.Transparent))
    }
}

// ═══════════════════════════ Session tab: the chat ═══════════════════════════

private sealed interface ChatRow {
    data class Day(val label: String) : ChatRow
    data class Msg(val m: Message) : ChatRow
}

@Composable
private fun ColumnScope.ChatTab(repo: Repository, snap: Snapshot, session: Session, onOpenTab: (Int) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val uploads by repo.uploads.collectAsState()
    val mediaVersion by repo.mediaVersion.collectAsState()
    val upload = uploads[session.id]
    var draft by rememberSaveable(session.id) { mutableStateOf("") }
    val picked = remember(session.id) { mutableStateListOf<Uri>() }
    val figures = remember(session.id) { mutableStateListOf<Uri>() }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        val room = MAX_PHOTOS - picked.size
        if (uris.size > room) toast("Up to $MAX_PHOTOS photos per message.")
        picked.addAll(uris.take(room))
    }
    // The photo being cropped (its place in the picked list).
    var cropping by remember { mutableStateOf<Int?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        // A new camera photo opens the cropper straight away; Cancel keeps it as taken.
        cameraUri?.let { if (ok) { picked.add(it); cropping = picked.lastIndex } }
    }
    var attachMenu by remember { mutableStateOf(false) }
    // Where the next message goes: Notes, References only, or Overview only (a summary).
    var mode by rememberSaveable(session.id) { mutableStateOf("notes") }
    var pdf by remember { mutableStateOf<Uri?>(null) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pdf = uri }

    val rows = remember(snap.messages, session.id) {
        val today = LocalDate.now()
        val out = ArrayList<ChatRow>()
        var lastDay: LocalDate? = null
        snap.messages.filter { it.sessionId == session.id }.forEach { m ->
            val d = localDate(m.createdAt)
            if (d != null && d != lastDay) {
                out += ChatRow.Day(if (d == today) "Today" else weekCommaDate(d)); lastDay = d
            }
            out += ChatRow.Msg(m)
        }
        out
    }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size, upload) {
        val n = listState.layoutInfo.totalItemsCount
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (rows.isEmpty() && upload == null) item {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Add what you read", style = ts(16, 600))
                Text("Send photos of book pages or handwritten notes, or paste text or subtitles. ewe turns each upload into short bullet notes.",
                    style = ts(14, 400, C.G600, 1.45f), textAlign = TextAlign.Center)
            }
        }
        items(rows) { row ->
            when (row) {
                is ChatRow.Day -> Text(row.label, style = ts(12, 500, C.G500), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                is ChatRow.Msg -> if (row.m.role == "user") {
                    val files = remember(row.m.id, mediaVersion) { repo.photos(row.m.id) }
                    UserMessage(row.m.content, row.m.imageCount, files.map { it }, emptyList(), row.m.mode,
                        when {
                            row.m.imageCount == 0 -> null
                            files.isNotEmpty() -> "${plural(row.m.imageCount, "photo")} · small copies saved on this phone"
                            else -> "${plural(row.m.imageCount, "photo")} · not saved (turn on in Settings)"
                        })
                } else AssistantMessage(row.m.content, when {
                    row.m.mode == "reference" && row.m.content.startsWith("Saved") -> "Open References" to { onOpenTab(3) }
                    row.m.mode == "summary" && row.m.content.startsWith("Saved") -> "Open Overview" to { onOpenTab(2) }
                    row.m.mode == "notes" && row.m.content.startsWith("Added") -> "Open Notes" to { onOpenTab(1) }
                    else -> null
                })
            }
        }
        if (upload != null) {
            item {
                val figs = upload.batches.firstOrNull()?.figures.orEmpty()
                UserMessage(upload.text, upload.images.size + figs.size, emptyList(), upload.images + figs, upload.batches.firstOrNull()?.mode ?: "notes",
                    if (figs.isNotEmpty()) "${figs.size} figure${if (figs.size == 1) "" else "s"}" else null)
            }
            item {
                if (upload.error == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar()
                    CircularProgressIndicator(Modifier.size(16.dp), color = C.G500, strokeWidth = 2.dp)
                    Text(upload.stage, style = ts(15, 400, C.G600))
                } else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.RedBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Notes were not made", style = ts(14, 600, C.Red))
                    Text(upload.error, style = ts(13, 400, C.Red, 1.45f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniButton("Try again", C.Red, C.White) { repo.retry(session.id) }
                        if (upload.editable) MiniButton("Edit", C.White, C.Red) {
                            draft = upload.text; picked.clear(); picked.addAll(upload.images); repo.dismissUpload(session.id)
                        }
                        MiniButton("Discard", C.White, C.Red) { repo.dismissUpload(session.id) }
                    }
                }
            }
        }
    }

    // ── composer ──
    if (!session.isActive) {
        Text("This session is inactive. Mark it Active in the options to add notes.", style = ts(13, 400, C.G600, 1.45f),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().background(C.G50).padding(16.dp))
        return
    }
    val notesModelSet = repo.prefs.cfg(snap.settings.notesProvider, snap.settings.notesModel) != null
    if (!notesModelSet) {
        Text("Add an AI key and pick a notes model in Settings to start.", style = ts(13, 600, C.Amber),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(C.AmberBg).clickable(onClick = onSettings).padding(12.dp))
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("notes" to "Notes", "reference" to "Reference", "summary" to "Summary").forEach { (m, label) ->
                val on = mode == m
                Box(Modifier.height(30.dp).clip(CircleShape).background(if (on) C.Ink else C.White)
                    .then(if (on) Modifier else Modifier.border(1.dp, C.G200, CircleShape))
                    .clickable(role = Role.RadioButton) { mode = m }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = ts(13, 500, if (on) C.White else C.G700))
                }
            }
        }
        if (mode != "notes") Text(
            if (mode == "reference") "Goes to References only — nothing is added to Notes."
            else "Goes to Overview as a diagram — nothing is added to Notes." +
                if (figures.isNotEmpty()) " Figures are only added in Notes mode." else "",
            style = ts(12, 400, C.G500), modifier = Modifier.padding(start = 4.dp))
        if (figures.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(figures.toList()) { i, uri ->
                Box {
                    AsyncImage(uri, "Figure ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp, 80.dp).clip(RoundedCornerShape(10.dp))
                        .background(C.G200).border(2.dp, C.Purple, RoundedCornerShape(10.dp)))
                    Text("Figure", style = ts(10, 600, C.White), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .clip(RoundedCornerShape(6.dp)).background(C.Purple).padding(horizontal = 5.dp, vertical = 1.dp))
                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).clip(CircleShape).background(C.Ink).clickable { figures.remove(uri) }, contentAlignment = Alignment.Center) {
                        Icon(Ic.Close, "Remove figure", tint = C.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        if (picked.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(picked.toList()) { i, uri ->
                Box {
                    AsyncImage(uri, "Selected photo", contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp, 80.dp).clip(RoundedCornerShape(10.dp))
                        .background(C.G200).clickable(role = Role.Button) { cropping = i })
                    Box(Modifier.align(Alignment.BottomStart).padding(3.dp).size(22.dp).clip(CircleShape).background(C.Ink.copy(alpha = 0.75f))
                        .clickable(role = Role.Button) { cropping = i }.semantics { contentDescription = "Crop photo ${i + 1}" }, contentAlignment = Alignment.Center) {
                        Icon(Ic.Crop, null, tint = C.White, modifier = Modifier.size(12.dp))
                    }
                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).clip(CircleShape).background(C.Ink).clickable { picked.remove(uri) }, contentAlignment = Alignment.Center) {
                        Icon(Ic.Close, "Remove photo", tint = C.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        val busy = upload != null && upload.error == null
        val canSend = !busy && notesModelSet && (draft.isNotBlank() || picked.isNotEmpty() || (mode == "notes" && figures.isNotEmpty()))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).border(1.dp, C.G300, RoundedCornerShape(24.dp)).padding(6.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                IconBtn(Ic.Attach, "Attach photos or a PDF", enabled = !busy) { attachMenu = true }
                DropdownMenu(attachMenu, onDismissRequest = { attachMenu = false }, containerColor = C.White) {
                    DropdownMenuItem(text = { Text("Photos", style = ts(15)) }, onClick = {
                        attachMenu = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("PDF pages", style = ts(15)) }, onClick = {
                        attachMenu = false
                        runCatching { pdfPicker.launch(arrayOf("application/pdf")) }.onFailure { toast("No file picker found.") }
                    })
                }
            }
            IconBtn(Ic.Camera, "Take photo", enabled = !busy) {
                if (picked.size >= MAX_PHOTOS) toast("Up to $MAX_PHOTOS photos per message.") else runCatching {
                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "photo_${System.currentTimeMillis()}.jpg"))
                    cameraUri = uri
                    camera.launch(uri)
                }.onFailure { toast("No camera app found.") }
            }
            Box(Modifier.weight(1f).heightIn(min = 44.dp, max = 140.dp).padding(horizontal = 4.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) Text("Paste text or subtitles…", style = ts(15, 400, C.G500))
                BasicTextField(draft, { draft = it; ModeWord.of(it)?.let { m -> mode = m } }, textStyle = ts(15, 400, C.Ink, 1.4f), cursorBrush = SolidColor(C.Ink), enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
            }
            IconBtn(Ic.Send, "Send", tint = C.White, bg = if (canSend) C.Ink else C.G400, iconSize = 20.dp, enabled = canSend) {
                repo.send(session.id, ModeWord.strip(draft, mode).trim(), picked.toList(), mode, if (mode == "notes") figures.toList() else emptyList())
                draft = ""; picked.clear(); if (mode == "notes") figures.clear(); mode = "notes"
            }
        }
    }
    cropping?.let { i ->
        picked.getOrNull(i)?.let { uri ->
            CropDialog(uri, onDismiss = { cropping = null }, onFigure = { fig ->
                if (figures.size >= MAX_FIGURES) toast("Up to $MAX_FIGURES figures per message.") else figures += fig
                cropping = null
            }) { cropped ->
                if (i < picked.size) picked[i] = cropped
                cropping = null
            }
        } ?: run { cropping = null }
    }
    pdf?.let { uri ->
        PdfDialog(uri, onDismiss = { pdf = null }) { info, from, to ->
            pdf = null
            repo.sendPdf(session.id, uri, info.name, from, to, ModeWord.strip(draft, mode).trim(), mode)
            draft = ""; mode = "notes"
        }
    }
}

@Composable
private fun MiniButton(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, C.RedLine, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = ts(13, 600, fg))
    }
}

@Composable
private fun Avatar() {
    Box(Modifier.size(28.dp).clip(CircleShape).background(C.Ink), contentAlignment = Alignment.Center) { Text("e", style = ts(12, 700, C.White)) }
}

@Composable
private fun UserMessage(text: String, imageCount: Int, saved: List<File>, pending: List<Uri>, mode: String, caption: String?) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (mode) {
            "reference" -> Tag("→ References", C.BlueBg, C.Blue, 600, 11)
            "summary" -> Tag("→ Overview", C.PurpleBg, C.Purple, 600, 11)
        }
        if (imageCount > 0) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), reverseLayout = true) {
                val shape = RoundedCornerShape(12.dp)
                val thumb = Modifier.size(84.dp, 108.dp).clip(shape).background(C.G200)
                when {
                    saved.isNotEmpty() -> items(saved.reversed()) { AsyncImage(it, "Saved photo", contentScale = ContentScale.Crop, modifier = thumb) }
                    pending.isNotEmpty() -> items(pending.reversed()) { AsyncImage(it, "Photo", contentScale = ContentScale.Crop, modifier = thumb) }
                    else -> items(minOf(imageCount, 4)) { i ->
                        Box(thumb, contentAlignment = Alignment.Center) {
                            Text(if (i == 0 && imageCount > 4) "+${imageCount - 3}" else "Photo", style = ts(11, 400, C.G600))
                        }
                    }
                }
            }
            if (caption != null) Text(caption, style = ts(12, 400, C.G500))
        }
        if (text.isNotBlank()) {
            Text(text, style = ts(15, 400, C.Ink, 1.45f), maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(C.G100).clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun AssistantMessage(text: String, action: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(end = 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val lines = text.split('\n')
            Text(boldMarkup(lines.first()), style = ts(15, 400, C.Ink, 1.5f))
            lines.drop(1).filter { it.isNotBlank() }.forEach { Text(boldMarkup(it), style = ts(14, 400, C.G600, 1.45f)) }
            if (action != null) Text(action.first, style = ts(14, 600, C.Blue), modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = action.second).padding(vertical = 4.dp))
        }
    }
}

// ═══════════════════════════ Notes tab ═══════════════════════════

@Composable
private fun ColumnScope.NotesTab(repo: Repository, snap: Snapshot, session: Session, onOpenSession: (String) -> Unit,
                                 reader: NotesReader, reading: ReaderState?, onOpenTab: (Int) -> Unit) {
    val run = rememberRunner()
    val sections = remember(snap, session.id) { snap.sectionsOf(session.id).map { it to snap.points(it.id) }.filter { it.second.isNotEmpty() } }
    val linkedElsewhere = remember(snap) { snap.links.groupBy { it.bulletId } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Point?>(null) }
    // The [n] chip that was tapped: note key → reference id.
    var openRef by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleting by remember { mutableStateOf<Point?>(null) }
    var reordering by remember { mutableStateOf(false) }
    var removingLinked by remember { mutableStateOf(false) }
    val today = repo.today()
    val weekAgo = today.minusDays(7).toString()
    val total = sections.sumOf { it.second.size }
    // points brought in from other sessions (they live there; here they are only links)
    val linkedCount = sections.sumOf { (_, pts) -> pts.count { it.link != null } }

    if (total == 0) {
        Column(Modifier.fillMaxWidth().weight(1f).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No notes yet", style = ts(16, 600))
            Spacer(Modifier.height(6.dp))
            Text("Send photos or text in the Session tab and the short notes appear here.", style = ts(14, 400, C.G600, 1.45f), textAlign = TextAlign.Center)
        }
        return
    }

    val spoken = remember(snap, session.id) { NotesReader.lines(snap, session.id) }
    val nowKey = reading?.current?.key
    // Lazy-list index of every line, so the list can follow the voice.
    val indexOf = remember(sections) {
        val m = HashMap<String, Int>(); var i = 2
        sections.forEach { (sec, pts) -> m["h:" + sec.id] = i++; pts.forEach { m[sec.id + it.bullet.id] = i++ } }
        m
    }
    val listState = rememberLazyListState()
    LaunchedEffect(nowKey) {
        val target = nowKey?.let { indexOf[it] } ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == target } || visible.lastOrNull()?.index == target) listState.animateScrollToItem((target - 1).coerceAtLeast(0))
    }

    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
        item { androidx.compose.foundation.layout.FlowRow(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (spoken.isNotEmpty() && reading == null) Row(
                Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, C.G200, RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button) { reader.play(session.id, session.title, spoken) }.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.Speaker, null, tint = C.Ink, modifier = Modifier.size(18.dp))
                Text("Listen to notes", style = ts(14, 600))
                Text("~${NotesReader.minutes(spoken)} min", style = ts(13, 400, C.G500))
            }
            if (sections.size > 1 && reading == null) Row(
                Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, C.G200, RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button) { reordering = true }.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.Grip, null, tint = C.Ink, modifier = Modifier.size(18.dp))
                Text("Reorder", style = ts(14, 600))
            }
            if (linkedCount > 0 && reading == null) Row(
                Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, C.G200, RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button) { removingLinked = true }.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.Link, null, tint = C.Purple, modifier = Modifier.size(16.dp))
                Text("Remove linked points", style = ts(14, 600))
                Text("$linkedCount", style = ts(13, 400, C.G500))
            }
        } }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${plural(total, "point")} · updated ${relativeDay(session.updatedAt, today).lowercase()}", style = ts(12, 400, C.G600))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(C.BlueDot))
                    Text("Added in last 7 days", style = ts(12, 400, C.G600))
                }
            }
        }
        sections.forEach { (section, points) ->
            item(key = "h" + section.id) {
                val now = nowKey == "h:" + section.id
                Text(section.title, style = ts(16, 700).copy(textDecoration = if (section.important) TextDecoration.Underline else null),
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(if (now) C.BlueBg else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (reading != null) Modifier.clickable { spoken.indexOfFirst { it.key == "h:" + section.id }.takeIf { it >= 0 }?.let { reader.play(session.id, session.title, spoken, it) } }
                        // press and hold a title to reorder sections
                        else if (sections.size > 1) Modifier.combinedClickable(onLongClickLabel = "Reorder sections", onLongClick = { reordering = true }, onClick = {})
                        else Modifier)
                    .padding(horizontal = if (now) 6.dp else 0.dp))
            }
            items(points, key = { section.id + it.bullet.id }) { p ->
                val key = section.id + p.bullet.id
                val isSel = selected == key
                val from = if (p.link != null) snap.sessions.firstOrNull { s -> snap.section(p.bullet.sectionId)?.sessionId == s.id } else null
                val now = nowKey == key
                val mark = highlightColor(p.bullet.highlight)
                val toggle = { selected = if (isSel) null else key }
                Column(Modifier.fillMaxWidth()) {
                    if (p.bullet.isDiagram || p.bullet.isCode || p.bullet.isTable || p.bullet.isFigure) Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(mark ?: androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = if (mark != null) 6.dp else 0.dp)) {
                        when {
                            p.bullet.isFigure -> FigureView(p.bullet.content, p.bullet.caption, load = { repo.loadFigure(it) }, onLabel = toggle)
                            p.bullet.isDiagram -> MermaidDiagram(p.bullet.content, onLabel = toggle, fixKey = p.bullet.id,
                                label = p.bullet.caption?.takeIf { it.isNotBlank() }?.let { "$it · Mermaid" } ?: "Diagram · Mermaid",
                                onFixed = { repo.saveRepairedDiagram(p.bullet.id, it) },
                                aiFix = { code, error -> repo.fixDiagram(code, error) })
                            p.bullet.isCode -> CodeBlock(p.bullet.content, p.bullet.lang, onLabel = toggle)
                            else -> NoteTable(Rich.refsToShown(p.bullet.content, snap), onLabel = toggle)
                        }
                    }
                    else Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (now) C.BlueBg else mark ?: if (isSel) C.G100 else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { selected = if (isSel) null else key }.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 8.dp).size(7.dp).clip(CircleShape).background(if (p.bullet.dateAdded >= weekAgo) C.BlueDot else C.G300))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            NoteText(p.bullet.content, ts(15, 400, C.Ink, 1.45f).copy(textDecoration = if (p.bullet.important) TextDecoration.Underline else null), snap) { id ->
                                openRef = if (openRef == key to id) null else key to id
                            }
                            if (from != null) Row(Modifier.clip(RoundedCornerShape(6.dp)).background(C.PurpleBg).clickable { onOpenSession(from.id) }.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Ic.Link, null, tint = C.Purple, modifier = Modifier.size(12.dp))
                                Text("from ${from.title}", style = ts(11, 500, C.Purple), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    openRef?.takeIf { it.first == key }?.let { (_, id) ->
                        RefCard(snap.reference(id), onAll = { openRef = null; onOpenTab(3) }, onClose = { openRef = null })
                    }
                    if (isSel) Column(Modifier.fillMaxWidth().padding(start = 25.dp, end = 0.dp)) {
                        val origin = when {
                            from != null -> "from ${from.title}"
                            p.bullet.originSessionId == session.id || p.bullet.originSessionId.isEmpty() -> "this session"
                            else -> snap.session(p.bullet.originSessionId)?.let { "from ${it.title}" } ?: "this session"
                        }
                        val also = if (p.link == null) linkedElsewhere[p.bullet.id].orEmpty()
                            .mapNotNull { l -> snap.session(snap.section(l.sectionId)?.sessionId)?.title }.distinct() else emptyList()
                        Text("Added ${weekDate(p.bullet.dateAdded)} · $origin" + if (also.isEmpty()) "" else " · also in ${also.joinToString(", ")}",
                            style = ts(12, 400, C.G500), modifier = Modifier.padding(top = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Swatch(C.HighlightYellow, "Highlight yellow", p.bullet.highlight == "yellow") {
                                run.go { repo.setHighlight(p.bullet.id, if (p.bullet.highlight == "yellow") null else "yellow") }
                            }
                            Swatch(C.HighlightRed, "Highlight red", p.bullet.highlight == "red") {
                                run.go { repo.setHighlight(p.bullet.id, if (p.bullet.highlight == "red") null else "red") }
                            }
                            if (p.bullet.kind == "text") Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Switch) { run.go { repo.setImportant(p.bullet.id, !p.bullet.important) } }
                                    .semantics { contentDescription = if (p.bullet.important) "Remove underline" else "Underline as important" },
                                contentAlignment = Alignment.Center) {
                                Text("U", style = ts(15, if (p.bullet.important) 700 else 500, if (p.bullet.important) C.Ink else C.G500)
                                    .copy(textDecoration = TextDecoration.Underline))
                            }
                            Spacer(Modifier.weight(1f))
                            IconBtn(Ic.Edit, "Edit point", tint = C.G500, size = 40.dp, iconSize = 16.dp) { editing = p }
                            IconBtn(Ic.Trash, if (p.link != null) "Remove from this topic" else "Delete point", tint = C.G500, size = 40.dp, iconSize = 16.dp) { deleting = p }
                        }
                    }
                }
            }
        }
    }

    editing?.takeIf { it.bullet.isFigure }?.let { p ->
        TextPrompt("Edit caption", p.bullet.caption.orEmpty(), "Save", onDismiss = { editing = null }) { text ->
            editing = null
            run.go { repo.editCaption(p.bullet.id, text) }
        }
    }
    editing?.takeIf { it.bullet.isDiagram }?.let { p ->
        DiagramEditDialog(p.bullet.caption.orEmpty(), p.bullet.content, onDismiss = { editing = null }) { caption, code ->
            editing = null
            run.go { repo.editDiagram(p.bullet.id, code, caption) }
        }
    }
    editing?.takeIf { !it.bullet.isFigure && !it.bullet.isDiagram }?.let { p ->
        TextPrompt(when { p.bullet.isDiagram -> "Edit diagram code"; p.bullet.isCode -> "Edit code"; p.bullet.isTable -> "Edit table (Markdown)"; else -> "Edit point" }, Rich.refsToShown(p.bullet.content, snap), "Save", singleLine = false, onDismiss = { editing = null }) { text ->
            editing = null
            // [n] typed in the text cites reference n of the session the point belongs to
            val own = snap.section(p.bullet.sectionId)?.sessionId.orEmpty()
            run.go { repo.editBullet(p.bullet.id, if (p.bullet.isCode || p.bullet.isDiagram) text else Rich.shownToRefs(text, snap.referencesOf(own))) }
        }
    }
    if (removingLinked) Confirm("Remove ${plural(linkedCount, "linked point")}?",
        "Every point brought in from other sessions is taken out of ${session.title}. They stay in the sessions they came from. " +
            "Sections left empty are removed.", "Remove", danger = true, onDismiss = { removingLinked = false }) {
        removingLinked = false; selected = null
        run.go { repo.removeLinkedPoints(session.id) }
    }
    if (reordering) ReorderSectionsDialog(sections.map { it.first }, onDismiss = { reordering = false }) { ids ->
        reordering = false
        run.go { repo.reorderSections(session.id, ids) }
    }
    deleting?.let { p ->
        if (p.link != null) Confirm("Remove from this topic?", "The point stays in the session it came from.", "Remove", onDismiss = { deleting = null }) {
            deleting = null; selected = null
            run.go { repo.unlink(p.link.id) }
        } else Confirm(when { p.bullet.isFigure -> "Delete this figure?"; p.bullet.isDiagram -> "Delete this diagram?"; p.bullet.isCode -> "Delete this code block?"; p.bullet.isTable -> "Delete this table?"; else -> "Delete this point?" }, "It is removed for good; no history is kept.", "Delete", danger = true, onDismiss = { deleting = null }) {
            deleting = null; selected = null
            run.go { repo.deleteBullet(p.bullet.id) }
        }
    }
}


/** The read-aloud controls, pinned under the notes while a session is being read. */
@Composable
private fun ReaderBar(reader: NotesReader, state: ReaderState) {
    Column(Modifier.fillMaxWidth().background(C.Ink).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (state.playing) "Reading" else "Paused", style = ts(11, 500, C.G300))
                Text(state.current?.sectionTitle.orEmpty(), style = ts(14, 600, C.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${state.speed.toString().removeSuffix(".0")}×", style = ts(13, 600, C.White),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { reader.cycleSpeed() }
                    .semantics { contentDescription = "Reading speed" }.padding(horizontal = 10.dp, vertical = 12.dp))
            IconBtn(Ic.Replay, "Previous section", tint = C.White, iconSize = 20.dp) { reader.skipSection(false) }
            IconBtn(if (state.playing) Ic.Pause else Ic.Play, if (state.playing) "Pause" else "Resume", tint = C.Ink, bg = C.White, iconSize = 20.dp) {
                if (state.playing) reader.pause() else reader.resume()
            }
            IconBtn(Ic.SkipNext, "Next section", tint = C.White, iconSize = 20.dp) { reader.skipSection(true) }
            IconBtn(Ic.Stop, "Stop reading", tint = C.White, iconSize = 18.dp) { reader.stop() }
        }
        val progress = if (state.lines.size <= 1) 0f else state.index / (state.lines.size - 1).toFloat()
        Box(Modifier.padding(top = 8.dp, end = 8.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(C.G700)) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).clip(CircleShape).background(C.White))
        }
    }
}


/** Pick which pages of a PDF become notes. They are sent 10 pages per message. */
@Composable
private fun PdfDialog(uri: Uri, onDismiss: () -> Unit, onSend: (Pdf.Info, Int, Int) -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<Pdf.Info?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fromText by remember { mutableStateOf("1") }
    var toText by remember { mutableStateOf("") }
    LaunchedEffect(uri) {
        runCatching { withContext(Dispatchers.IO) { Pdf.info(context, uri) } }
            .onSuccess { info = it; toText = minOf(it.pages, Pdf.PAGES_PER_MESSAGE).toString() }
            .onFailure { error = it.message ?: "This file could not be read as a PDF." }
    }
    val pages = info?.pages ?: 0
    val from = fromText.toIntOrNull()
    val to = toText.toIntOrNull()
    val problem = when {
        info == null -> null
        from == null || to == null || from < 1 || to > pages || from > to -> "Choose pages between 1 and $pages."
        to - from + 1 > Pdf.MAX_PAGES -> "Up to ${Pdf.MAX_PAGES} pages at a time."
        else -> null
    }
    val count = if (problem == null && from != null && to != null) to - from + 1 else 0
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.White,
        title = { Text(info?.name ?: "PDF", style = ts(18, 600), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    error != null -> Text(error!!, style = ts(14, 400, C.Red, 1.45f))
                    info == null -> Text("Opening…", style = ts(14, 400, C.G600))
                    else -> {
                        Text("${plural(pages, "page")}. Choose the pages to make notes from.", style = ts(14, 400, C.G600, 1.45f))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(fromText, { fromText = it.filter(Char::isDigit).take(4) }, label = { Text("From") }, singleLine = true,
                                textStyle = ts(16), modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                            OutlinedTextField(toText, { toText = it.filter(Char::isDigit).take(4) }, label = { Text("To") }, singleLine = true,
                                textStyle = ts(16), modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                        }
                        Text(problem ?: if (count <= Pdf.PAGES_PER_MESSAGE) "Sent as one message."
                            else "Sent as ${(count + Pdf.PAGES_PER_MESSAGE - 1) / Pdf.PAGES_PER_MESSAGE} messages of up to ${Pdf.PAGES_PER_MESSAGE} pages, one after another.",
                            style = ts(13, 400, if (problem != null) C.Red else C.G600, 1.45f))
                        Text("Pages are turned into images on this phone and read by your notes model.", style = ts(12, 400, C.G500, 1.4f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(info!!, from!!, to!!) }, enabled = info != null && problem == null && count > 0) {
                Text(if (count > 0) "Make notes from ${plural(count, "page")}" else "Make notes", style = ts(15, 600))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) } },
    )
}


/** A highlight colour choice; a ring shows the one that is on. Tap again to clear. */
@Composable
private fun Swatch(color: androidx.compose.ui.graphics.Color, label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Switch, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(color)
            .then(if (on) Modifier.border(2.dp, C.Ink, CircleShape) else Modifier.border(1.dp, C.G300, CircleShape)))
    }
}


/** Typing "reference" or "summary" as the first word picks that mode; the word itself is not sent. */
object ModeWord {
    private val WORD = Regex("^\\s*(reference|summary)\\b[\\s:,.-]*", RegexOption.IGNORE_CASE)
    fun of(text: String): String? = WORD.find(text)?.groupValues?.get(1)?.lowercase()
    fun strip(text: String, mode: String): String = WORD.find(text)?.takeIf { it.groupValues[1].lowercase() == mode }
        ?.let { text.removeRange(it.range) } ?: text
}
