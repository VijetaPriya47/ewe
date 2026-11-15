package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ewe.ai.GnaniTts
import app.ewe.ai.GnaniVoice

/** A row of chips where one is selected. */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            Box(Modifier.height(32.dp).clip(CircleShape).background(if (on) C.Ink else C.White)
                .then(if (on) Modifier else Modifier.border(1.dp, C.G200, CircleShape))
                .clickable(role = Role.RadioButton) { onSelect(id) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(label, style = ts(13, 500, if (on) C.White else C.G700))
            }
        }
    }
}

/** The Gnani API key: kept on this phone only. */
@Composable
fun GnaniKeyDialog(keys: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var k by remember { mutableStateOf(keys.joinToString("\n")) }
    val found = GnaniTts.parseKeys(k)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.White,
        title = { Text("Gnani API keys", style = ts(18, 600)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(k, { k = it }, label = { Text("API keys") }, textStyle = ts(13), minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth())
                Text(if (found.isEmpty()) "Paste one key, or several (one per line, or a whole list)."
                     else "${found.size} key${if (found.size == 1) "" else "s"} found. The first is used; if it's rejected or out of credit, the next one takes over.",
                    style = ts(13, 400, C.G600, 1.45f))
                Text("Stored only on this phone. Each is sent to Gnani with requests and never saved on a server.", style = ts(12, 400, C.G500, 1.4f))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(found) }, enabled = found.isNotEmpty()) { Text("Save", style = ts(15, 600)) } },
        dismissButton = {
            Row {
                if (keys.isNotEmpty()) TextButton(onClick = { onSave(emptyList()) }) { Text("Remove", style = ts(15, 500, C.Red)) }
                TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) }
            }
        },
    )
}

/** Pick Gnani's model and a voice from its catalog. A voice only works with its own model. */
@Composable
fun GnaniVoiceDialog(model: String, voice: String, onDismiss: () -> Unit, onPick: (model: String, voice: GnaniVoice) -> Unit) {
    var m by remember { mutableStateOf(model) }
    var gender by remember { mutableStateOf("all") }
    var lang by remember { mutableStateOf("all") }
    val all = GnaniTts.voices(m)
    // v2.5 voices each belong to one language; v3.0 voices speak them all, so there is nothing to filter by
    val languages = if (m == "timbre-v3.0") emptyList() else all.map { it.language }.distinct()
    val voices = all.filter { (gender == "all" || it.gender == gender) && (lang == "all" || it.language == lang) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.White,
        title = { Text("Gnani voice", style = ts(18, 600)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Segmented(GnaniTts.MODELS, m) { m = it; lang = "all" }
                Segmented(listOf("all" to "All", "Female" to "Female", "Male" to "Male"), gender) { gender = it }
                if (languages.isNotEmpty()) Segmented(listOf("all" to "Any language") + languages.map { it to GnaniTts.languageName(it) }, lang) { lang = it }
                Text((if (m == "timbre-v3.0") "13 voices, each speaks every language; pick the language separately. " else "42 voices, each for one language. ") +
                    "Showing ${voices.size}.", style = ts(13, 400, C.G600, 1.4f))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(voices) { v ->
                        val current = v.name == voice && m == model
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (current) C.G100 else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onPick(m, v) }.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(v.name, style = ts(15, if (current) 600 else 400), modifier = Modifier.weight(1f))
                            Text(if (m == "timbre-v3.0") v.gender else "${GnaniTts.languageName(v.language)} · ${v.gender}", style = ts(13, 400, C.G500))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) } },
    )
}

/** Pick the language the text is in (Gnani's timbre-v3.0 voices speak all of them). */
@Composable
fun GnaniLanguageDialog(language: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.White,
        title = { Text("Language", style = ts(18, 600)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(GnaniTts.LANGUAGES) { (code, name) ->
                    Text(name, style = ts(15, if (code == language) 600 else 400), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (code == language) C.G100 else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(code) }.padding(horizontal = 8.dp, vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) } },
    )
}

/** "…ab12" — enough to recognise a key without showing it. */
fun maskedKey(key: String) = if (key.length <= 6) "set" else "…" + key.takeLast(4)
