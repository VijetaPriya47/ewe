package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ewe.data.Labels
import app.ewe.data.Repository
import app.ewe.data.Session
import app.ewe.data.SyncState
import app.ewe.podcast.EpisodeStore
import app.ewe.podcast.Planner

@Composable
fun HomeScreen(repo: Repository, store: EpisodeStore, onOpen: (String) -> Unit, onNew: () -> Unit, onSearch: () -> Unit,
               onSettings: () -> Unit, onRevision: () -> Unit) {
    val snap by repo.snapshot.collectAsState()
    val sync by repo.sync.collectAsState()
    val episode by store.episode.collectAsState()
    var filter by rememberSaveable { mutableStateOf("All") }
    val today = repo.today()

    var sortName by rememberSaveable { mutableStateOf(Labels.Sort.UPDATED.name) }
    var label by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val sort = Labels.Sort.valueOf(sortName)
    val labelCounts = remember(snap) { Labels.counts(snap.sessions) }
    // a label that no session has any more is no longer a filter
    val activeLabel = label?.takeIf { l -> labelCounts.any { it.first.equals(l, ignoreCase = true) } }
    val sessions = remember(snap, filter, activeLabel, sort) { Labels.arrange(snap.sessions, filter, activeLabel, sort) }

    Column(Modifier.fillMaxSize().background(C.White)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ewe", style = ts(22, 700).copy(letterSpacing = androidx.compose.ui.unit.TextUnit(-0.4f, androidx.compose.ui.unit.TextUnitType.Sp)), modifier = Modifier.weight(1f))
            IconBtn(Ic.Settings, "Settings", onClick = onSettings)
        }

        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(C.G100).clickable(onClick = onSearch).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Ic.Search, null, tint = C.G600, modifier = Modifier.size(18.dp))
                Text("Search sessions and notes", style = ts(15, 400, C.G600))
            }

            if (snap.settings.podcastEnabled) {
                val prepared = episode?.takeIf { it.date == today.toString() }
                val line = remember(snap, prepared) {
                    if (prepared != null && prepared.sections.isNotEmpty()) {
                        val left = prepared.sections.count { it.scheduleId !in prepared.marked }
                        if (left == 0) "All done for today" else "${plural(left, "section")} ready · ~${(prepared.durationMs / 60000).coerceAtLeast(1)} min"
                    } else {
                        val plan = Planner.plan(Planner.due(snap, today, snap.settings.revisionsPerLevel), snap.settings.podcastTargetMinutes)
                        if (plan.segments.isEmpty()) "Nothing due today" else "${plural(plan.segments.size, "section")} due · ~${plan.minutes} min"
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Ink).clickable(onClick = onRevision).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(C.G700), contentAlignment = Alignment.Center) {
                        Icon(Ic.Headphones, null, tint = C.White, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Today's Revision", style = ts(15, 600, C.White))
                        Text(line, style = ts(13, 400, C.G300))
                    }
                    Icon(Ic.Chevron, null, tint = C.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Topics", "Projects", "Inactive", "Completed").forEach { f ->
                val on = f == filter
                Box(Modifier.height(32.dp).clip(CircleShape).background(if (on) C.Ink else C.White)
                    .then(if (on) Modifier else Modifier.border(1.dp, C.G200, CircleShape))
                    .clickable { filter = f }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(f, style = ts(13, 500, if (on) C.White else C.G700))
                }
            }
        }

        Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Row(Modifier.height(32.dp).clip(CircleShape).border(1.dp, C.G200, CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { sortMenu = true }
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sort: ${sort.label}", style = ts(13, 500, C.G700))
                    Text("▾", style = ts(11, 500, C.G500))
                }
                androidx.compose.material3.DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, containerColor = C.White) {
                    Labels.Sort.entries.forEach { o ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(o.label, style = ts(15, if (o == sort) 600 else 400)) },
                            onClick = { sortName = o.name; sortMenu = false })
                    }
                }
            }
            if (labelCounts.isNotEmpty()) Row(Modifier.weight(1f).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labelCounts.forEach { (l, n) ->
                    val on = activeLabel.equals(l, ignoreCase = true)
                    Row(Modifier.height(32.dp).clip(CircleShape).background(if (on) C.Purple else C.PurpleBg)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { label = if (on) null else l }
                        .semantics { contentDescription = "Filter by label $l" }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(l, style = ts(13, 500, if (on) C.White else C.Purple))
                        Text("$n", style = ts(12, 400, if (on) C.White.copy(alpha = 0.8f) else C.Purple.copy(alpha = 0.7f)))
                    }
                }
            }
        }

        (sync as? SyncState.Failed)?.let {
            Text("Showing your saved copy · ${it.message}", style = ts(12, 500, C.Amber), maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                    .background(C.AmberBg).clickable { repo.refreshInBackground() }.padding(horizontal = 12.dp, vertical = 8.dp))
        }

        if (sessions.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        sync is SyncState.Syncing && snap.sessions.isEmpty() -> "Loading your sessions…"
                        snap.sessions.isEmpty() -> "No sessions yet"
                        else -> "Nothing here"
                    }, style = ts(16, 600))
                Spacer(Modifier.height(6.dp))
                if (snap.sessions.isEmpty() && sync !is SyncState.Syncing) Text(
                    "A session is one subject you are studying. Create one, then send photos of pages or paste text.",
                    style = ts(14, 400, C.G600, 1.45f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                var last: String? = null
                sessions.forEach { s ->
                    if (sort == Labels.Sort.LABEL) {
                        val g = Labels.group(s)?.lowercase() ?: ""
                        if (g != last) {
                            last = g
                            item(key = "group-$g") { Text(Labels.group(s) ?: "No label", style = ts(12, 600, C.G600), modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp)) }
                        }
                    }
                    item(key = s.id) { SessionRow(s, snap.noteCount(s.id), onOpen) }
                }
            }
        }

        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            OutlineButton("New session", Modifier.fillMaxWidth(), icon = Ic.Plus, onClick = onNew)
        }
    }
}

@Composable
private fun SessionRow(s: Session, notes: Int, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(s.id) }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.title, style = ts(15, 600), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(relativeDay(s.updatedAt), style = ts(12, 400, C.G500))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TypeTag(s); StatusTag(s)
            if (s.isCompleted) Tag("Completed", C.BlueBg, C.Blue, 600, 12)
            Spacer(Modifier.width(0.dp))
            Text(plural(notes, "note"), style = ts(12, 400, C.G500))
        }
        if (s.labels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            s.labels.take(3).forEach { Text(it, style = ts(11, 500, C.Purple), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.PurpleBg).padding(horizontal = 6.dp, vertical = 2.dp)) }
            if (s.labels.size > 3) Text("+${s.labels.size - 3}", style = ts(11, 500, C.G500), modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
