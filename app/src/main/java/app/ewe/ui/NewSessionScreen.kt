package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.ewe.data.Repository
import app.ewe.podcast.Planner

@Composable
fun NewSessionScreen(repo: Repository, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val snap by repo.snapshot.collectAsState()
    val run = rememberRunner()
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("topic") }

    Column(Modifier.fillMaxSize().background(C.G50).imePadding()) {
        TopBar("New session", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Name", style = ts(14, 600))
                OutlinedTextField(
                    name, { name = it }, singleLine = true, textStyle = ts(16), shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("e.g. CDN", style = ts(16, 400, C.G400)) }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = C.White, focusedContainerColor = C.White, unfocusedBorderColor = C.G300),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Type", style = ts(14, 600))
                TypeOption("Topic", "Something you learn — CDN, HTTP Caching. Related points from any session get added here automatically.", type == "topic") { type = "topic" }
                TypeOption("Project", "Something you do — Making a Sudoku. Grows only when you add to it. Nothing is added from other sessions.", type == "project") { type = "project" }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.AmberBg).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Ic.Info, null, tint = C.Amber, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                    Text("The type can't be changed later. Picked wrong? Use \"Recreate as ${if (type == "topic") "Project" else "Topic"}\" — your notes are copied over.",
                        style = ts(13, 400, C.Amber, 1.45f))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Revision", style = ts(14, 600))
                Ladder(Planner.ladder(snap.settings.intervalCapDays, snap.settings.revisionsPerLevel))
                Text("Detail fades automatically as the gap grows. Restart any time from session options.", style = ts(13, 400, C.G600, 1.45f))
            }
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            PrimaryButton("Create session", enabled = name.isNotBlank(), busy = run.busy) {
                run.go { onCreated(repo.createSession(name, type)) }
            }
        }
    }
}

@Composable
private fun TypeOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(C.White)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.Blue else C.G200, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape).border(2.dp, if (selected) C.Blue else C.G400, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(C.Blue))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = ts(16, 600))
            Text(body, style = ts(14, 400, C.G600, 1.45f))
        }
    }
}
