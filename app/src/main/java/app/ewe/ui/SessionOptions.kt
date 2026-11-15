package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.ewe.data.Labels
import app.ewe.data.Repository
import app.ewe.data.Session
import app.ewe.data.Snapshot
import app.ewe.podcast.Level
import app.ewe.podcast.Planner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionOptions(repo: Repository, snap: Snapshot, session: Session, onDismiss: () -> Unit, onOpenSession: (String) -> Unit) {
    val run = rememberRunner()
    val toast = LocalToast.current
    var renaming by remember { mutableStateOf(false) }
    var recreating by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }
    val other = if (session.isTopic) "Project" else "Topic"
    val sections = snap.sectionsOf(session.id)
    val sectionIds = sections.map { it.id }.toSet()
    val perLevel = snap.settings.revisionsPerLevel
    val levels = snap.schedule.filter { it.sectionId in sectionIds }.groupingBy { Level.of(it.revisionsDone + 1, perLevel) }.eachCount()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(session.title, style = ts(20, 700))
                Text("Created ${longDate(session.createdAt)} · ${plural(snap.noteCount(session.id), "point")} in ${plural(sections.size, "section")}", style = ts(13, 400, C.G600))
            }

            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.G50).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Type", style = ts(13, 400, C.G600))
                    Text(if (session.isTopic) "Topic" else "Project", style = ts(15, 600))
                }
                Icon(Ic.Lock, null, tint = C.G600, modifier = Modifier.size(16.dp))
                Text("  Locked", style = ts(13, 400, C.G600))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", style = ts(14, 600))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChoice("Active", session.isActive, C.Green, C.GreenBg, Modifier.weight(1f)) { if (!session.isActive) run.go { repo.setActive(session.id, true) } }
                    StatusChoice("Inactive", !session.isActive, C.Red, C.RedBg, Modifier.weight(1f)) { if (session.isActive) run.go { repo.setActive(session.id, false) } }
                }
                Text("Inactive: still readable and searchable, but gets no automatic additions and is left out of the podcast. Sessions can't be deleted.", style = ts(13, 400, C.G600, 1.45f))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Progress", style = ts(14, 600))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChoice("In progress", !session.isCompleted, C.Ink, C.G100, Modifier.weight(1f)) { if (session.isCompleted) run.go { repo.setCompleted(session.id, false) } }
                    StatusChoice("Completed", session.isCompleted, C.Blue, C.BlueBg, Modifier.weight(1f)) { if (!session.isCompleted) run.go { repo.setCompleted(session.id, true) } }
                }
                Text(if (session.isCompleted) "Marked completed ${longDate(session.completedAt.orEmpty())}. It's only a mark: revision carries on and it stays ${if (session.isActive) "Active" else "Inactive"}."
                     else "Mark it completed when you've finished studying it. Filter Home by Completed to find it.", style = ts(13, 400, C.G600, 1.45f))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Labels", style = ts(14, 600))
                if (session.labels.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    session.labels.forEach { l ->
                        Row(Modifier.clip(CircleShape).background(C.PurpleBg).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(l, style = ts(13, 500, C.Purple))
                            Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button) { run.go { repo.setLabels(session.id, session.labels - l) } }
                                .semantics { contentDescription = "Remove label $l" }, contentAlignment = Alignment.Center) {
                                Icon(Ic.Close, null, tint = C.Purple, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
                fun addLabel(text: String) { if (Labels.clean(text) != null) { run.go { repo.setLabels(session.id, Labels.add(session.labels, text)) }; newLabel = "" } }
                if (session.labels.size < Labels.MAX) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(newLabel, { newLabel = it.take(Labels.MAX_LENGTH) }, singleLine = true, textStyle = ts(15),
                        placeholder = { Text("Add a label", style = ts(15, 400, C.G400)) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addLabel(newLabel) }))
                    SmallButton("Add", enabled = Labels.clean(newLabel) != null && !run.busy) { addLabel(newLabel) }
                }
                val suggestions = Labels.counts(snap.sessions).map { it.first }.filter { s -> session.labels.none { it.equals(s, ignoreCase = true) } }
                if (suggestions.isNotEmpty() && session.labels.size < Labels.MAX) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s ->
                        Text("+ $s", style = ts(13, 500, C.G700), modifier = Modifier.clip(CircleShape).border(1.dp, C.G200, CircleShape)
                            .clickable(role = Role.Button) { addLabel(s) }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Text("Labels help you find sessions: filter and sort the list on Home by them. Up to ${Labels.MAX}.", style = ts(13, 400, C.G600, 1.45f))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Revision", style = ts(14, 600))
                Ladder(Planner.ladder(snap.settings.intervalCapDays, perLevel))
                Text(if (levels.isEmpty()) "No sections yet." else "Sections now: " + Level.entries.mapNotNull { l -> levels[l]?.let { "$it ${l.label}" } }.joinToString(" · "),
                    style = ts(13, 400, C.G600))
                OutlineButton("Restart from Day 1", Modifier.fillMaxWidth(), icon = Ic.Replay, height = 48.dp, enabled = levels.isNotEmpty(), busy = run.busy) { restarting = true }
                Text("Every section in this ${if (session.isTopic) "topic" else "project"} goes back to Day 1, Detailed.", style = ts(13, 400, C.G600, 1.45f))
            }

            Column {
                Divider()
                Box(Modifier.height(12.dp))
                ActionRow(Ic.Edit, "Rename", null) { renaming = true }
                ActionRow(Ic.Refresh, "Recreate as $other", "Copies all notes with their dates into a new $other and marks this one Inactive.") { recreating = true }
            }
        }
    }

    if (renaming) TextPrompt("Rename session", session.title, "Rename", onDismiss = { renaming = false }) { name ->
        renaming = false
        run.go { repo.renameSession(session.id, name) }
    }
    if (restarting) Confirm("Restart from Day 1?", "Every section of \"${session.title}\" is due again today and is read in full detail.", "Restart", onDismiss = { restarting = false }) {
        restarting = false
        run.go { repo.restartRevision(session.id); toast("Restarted from Day 1.") }
    }
    if (recreating) Confirm("Recreate as $other?", "All notes are copied, with their dates, into a new $other. This session becomes Inactive and keeps its chat history.", "Recreate", onDismiss = { recreating = false }) {
        recreating = false
        run.go { val id = repo.recreate(session.id); onDismiss(); onOpenSession(id) }
    }
}

@Composable
private fun StatusChoice(text: String, selected: Boolean, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.height(48.dp).clip(shape).background(if (selected) bg else C.White)
        .border(if (selected) 2.dp else 1.dp, if (selected) fg else C.G200, shape).clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center) { Text(text, style = ts(15, 600, fg)) }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, body: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = C.Ink, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = ts(15, 500))
            if (body != null) Text(body, style = ts(13, 400, C.G600, 1.4f))
        }
    }
}
