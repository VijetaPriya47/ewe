package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ewe.data.MeaningHit
import app.ewe.data.Repository
import app.ewe.data.Search
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(repo: Repository, onBack: () -> Unit, onOpen: (sessionId: String, notes: Boolean) -> Unit) {
    val snap by repo.snapshot.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val results = remember(snap, query) { Search.run(snap, query) }
    val hybrid = repo.hybridReady()
    var meaning by remember { mutableStateOf<List<MeaningHit>>(emptyList()) }
    var meaningBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focus.requestFocus() }
    // The "meaning" half of hybrid search: asked for once typing pauses.
    LaunchedEffect(query, hybrid) {
        meaning = emptyList()
        if (!hybrid || query.trim().length < 3) return@LaunchedEffect
        delay(700)
        meaningBusy = true
        meaning = runCatching { repo.searchByMeaning(query.trim()) }.getOrDefault(emptyList())
        meaningBusy = false
    }
    val keywordSections = remember(results) { results.notes.map { it.section.id }.toSet() }
    val related = remember(meaning, keywordSections, snap) {
        meaning.filter { it.sectionId !in keywordSections }.mapNotNull { h ->
            val sec = snap.section(h.sectionId) ?: return@mapNotNull null
            val ses = snap.session(sec.sessionId) ?: return@mapNotNull null
            Triple(ses, sec, snap.points(sec.id).take(2).joinToString(" · ") { it.bullet.content })
        }
    }

    Column(Modifier.fillMaxSize().background(C.White).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconBtn(Ic.Back, "Back", onClick = onBack)
            Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(C.G100).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Ic.Search, null, tint = C.G600, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search sessions and notes", style = ts(15, 400, C.G600))
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = ts(15), cursorBrush = SolidColor(C.Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
                if (query.isNotEmpty()) IconBtn(Ic.Close, "Clear", size = 28.dp, iconSize = 14.dp) { query = "" }
            }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isBlank()) {
                item { Text(if (hybrid) "Searches titles and notes by keyword and by meaning." else "Searches session titles, section titles and note text.", style = ts(14, 400, C.G600)) }
                return@LazyColumn
            }
            if (results.sessions.isEmpty() && results.notes.isEmpty() && related.isEmpty() && !meaningBusy) {
                item { Text("Nothing matches \"${query.trim()}\".", style = ts(14, 400, C.G600)) }
            }
            if (results.sessions.isNotEmpty()) {
                item { Caps("Sessions · ${results.sessions.size}") }
                items(results.sessions, key = { "s" + it.id }) { s ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, C.G200, RoundedCornerShape(12.dp)).clickable { onOpen(s.id, false) }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(highlight(s.title, query), style = ts(15, 600))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TypeTag(s); StatusTag(s)
                            Text("${plural(snap.noteCount(s.id), "note")} · ${shortDate(s.updatedAt)}", style = ts(12, 400, C.G500))
                        }
                    }
                }
            }
            if (results.notes.isNotEmpty()) {
                item { Box(Modifier.padding(top = 12.dp)) { Caps("Notes · ${results.notes.size}") } }
                items(results.notes, key = { "n" + it.section.id + it.point.bullet.id }) { n ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.G50).clickable { onOpen(n.session.id, true) }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(highlight("${n.session.title} › ${n.section.title}", query), style = ts(12, 400, C.G600))
                        Text(highlight(n.point.bullet.content, query), style = ts(15, 400, C.Ink, 1.45f))
                        Text("Added ${longDate(n.point.bullet.dateAdded)}" + (n.from?.let { " · from ${it.title}" } ?: ""), style = ts(12, 400, C.G500))
                    }
                }
            }
            if (related.isNotEmpty() || meaningBusy) {
                item { Box(Modifier.padding(top = 12.dp)) { Caps(if (related.isEmpty()) "Related by meaning · searching…" else "Related by meaning · ${related.size}") } }
                items(related, key = { "m" + it.second.id }) { (ses, sec, preview) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.G50).clickable { onOpen(ses.id, true) }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${ses.title} › ${sec.title}", style = ts(12, 400, C.G600))
                        Text(preview, style = ts(15, 400, C.Ink, 1.45f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
