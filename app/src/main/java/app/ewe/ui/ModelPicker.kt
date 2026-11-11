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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.ewe.ai.ModelUse
import app.ewe.ai.Providers
import app.ewe.data.LocalPrefs
import app.ewe.data.ProviderKind

fun ProviderKind.short() = when (this) {
    ProviderKind.OPENAI -> "OpenAI"
    ProviderKind.ANTHROPIC -> "Claude"
    ProviderKind.GEMINI -> "Gemini"
    ProviderKind.COMPATIBLE -> "Custom"
}

fun modelLabel(provider: String?, model: String?): String =
    if (model.isNullOrBlank()) "Choose" else "${ProviderKind.of(provider)?.short() ?: provider} · $model"

/**
 * Pick a provider and one of its models. The list is fetched from the provider with
 * your key, so new models show up by themselves; any model name can also be typed.
 */
@Composable
fun ModelPicker(title: String, prefs: LocalPrefs, use: ModelUse, currentProvider: String?, currentModel: String?,
                onDismiss: () -> Unit, onPick: (provider: String, model: String) -> Unit) {
    val kinds = remember { ProviderKind.entries.filter { prefs.connected(it) && !(use == ModelUse.EMBEDDING && it == ProviderKind.ANTHROPIC) } }
    var kind by remember { mutableStateOf(kinds.firstOrNull { it.id == currentProvider } ?: kinds.firstOrNull()) }
    var typed by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(kind) {
        val k = kind ?: return@LaunchedEffect
        loading = true; error = null; models = emptyList()
        runCatching { Providers().listModels(k, prefs.apiKey(k), prefs.compatibleUrl.takeIf { k == ProviderKind.COMPATIBLE }, use) }
            .onSuccess { models = it }.onFailure { error = it.message }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.White,
        title = { Text(title, style = ts(18, 600)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (kinds.isEmpty()) {
                    Text(if (use == ModelUse.EMBEDDING) "Add an OpenAI, Gemini or OpenAI-compatible key first. Claude has no embeddings API."
                         else "Add a provider key first, under AI providers.", style = ts(15, 400, C.G600, 1.45f))
                    return@Column
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { k ->
                        val on = k == kind
                        Box(Modifier.height(32.dp).clip(CircleShape).background(if (on) C.Ink else C.White)
                            .then(if (on) Modifier else Modifier.border(1.dp, C.G200, CircleShape)).clickable { kind = k }.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center) { Text(k.short(), style = ts(13, 500, if (on) C.White else C.G700)) }
                    }
                }
                OutlinedTextField(typed, { typed = it }, singleLine = true, textStyle = ts(15), shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("Filter, or type any model name", style = ts(15, 400, C.G400)) }, modifier = Modifier.fillMaxWidth())
                val shown = models.filter { typed.isBlank() || it.contains(typed.trim(), ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    if (typed.isNotBlank() && typed.trim() !in models) item {
                        ModelRow("Use \"${typed.trim()}\"", false) { kind?.let { onPick(it.id, typed.trim()) } }
                    }
                    if (loading) item { Text("Loading models…", style = ts(14, 400, C.G600), modifier = Modifier.padding(vertical = 12.dp)) }
                    error?.let { item { Text("Could not load the list: $it\nYou can still type a model name.", style = ts(13, 400, C.Red, 1.45f), modifier = Modifier.padding(vertical = 8.dp)) } }
                    items(shown) { m -> ModelRow(m, m == currentModel && kind?.id == currentProvider) { kind?.let { onPick(it.id, m) } } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) } },
    )
}

@Composable
private fun ModelRow(text: String, current: Boolean, onClick: () -> Unit) {
    Text(text, style = ts(15, if (current) 600 else 400), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (current) C.G100 else androidx.compose.ui.graphics.Color.Transparent).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp))
}
