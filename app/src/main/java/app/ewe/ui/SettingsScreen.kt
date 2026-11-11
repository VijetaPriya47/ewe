package app.ewe.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ewe.ai.ModelUse
import app.ewe.data.ProviderKind
import app.ewe.data.Repository
import app.ewe.data.SyncState
import app.ewe.podcast.PrepareWorker
import kotlinx.serialization.json.put
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Pick { NOTES, EMBEDDING, PLACEMENT, PODCAST }

@Composable
fun SettingsScreen(repo: Repository, onBack: () -> Unit) {
    val context = LocalContext.current
    val snap by repo.snapshot.collectAsState()
    val sync by repo.sync.collectAsState()
    val rebuild by repo.rebuild.collectAsState()
    val mediaVersion by repo.mediaVersion.collectAsState()
    val s = snap.settings
    val rag = snap.rag
    val run = rememberRunner()
    val prefs = repo.prefs
    var keysVersion by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<ProviderKind?>(null) }
    var picking by remember { mutableStateOf<Pick?>(null) }
    var pickTime by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun save(reschedule: Boolean = false, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        run.go { repo.updateSettings(build); if (reschedule) PrepareWorker.schedule(context, repo) }

    Column(Modifier.fillMaxSize().background(C.G50)) {
        TopBar("Settings", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {

            // ───────────── AI providers ─────────────
            Group("AI providers") {
                keysVersion.let { _ ->
                    ProviderKind.entries.forEach { kind ->
                        val connected = prefs.connected(kind)
                        val detail = when {
                            kind == ProviderKind.COMPATIBLE && connected -> "${prefs.compatibleUrl.removePrefix("https://")} · connected"
                            connected -> "${mask(prefs.apiKey(kind))} · connected"
                            kind == ProviderKind.COMPATIBLE -> kind.hint
                            else -> "No key yet"
                        }
                        SettingRow(kind.label, detail) { SmallButton(if (connected) "Edit" else "Add") { editing = kind } }
                        Divider()
                    }
                }
                Text("Keys are stored only on this phone and sent with each request.", style = ts(12, 400, C.G600), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }

            // ───────────── Notes ─────────────
            Group("Notes · always on") {
                SettingRow("Notes model", "Reads photos, writes the notes") { SmallButton(modelLabel(s.notesProvider, s.notesModel) + " ▾") { picking = Pick.NOTES } }
            }

            // ───────────── Linking (RAG) ─────────────
            Group("Linking (RAG)") {
                SettingRow("Link related notes", "Adds related points into existing Topics, enables smart search") {
                    Toggle(s.ragEnabled, "Link related notes", enabled = !run.busy) { on -> run.go { repo.setLinking(on) } }
                }
                val activeLabel = Repository.splitModel(rag.activeModel ?: s.ragActiveModel)?.let { modelLabel(it.first, it.second) }
                val selected = Repository.splitModel(s.ragSelectedModel)
                val placementSet = prefs.cfg(s.placementProvider, s.placementModel) != null
                val embedKeyMissing = selected != null && prefs.cfg(selected.first, selected.second) == null
                val progress = rebuild
                when {
                    !s.ragEnabled -> Banner(C.G100, C.G700, "Linking is off", "Notes stay in their own session. Search uses keywords. No embeddings are made.")
                    progress != null && progress.error == null ->
                        Banner(C.AmberBg, C.Amber, "Rebuilding the index…", "${progress.total - progress.remaining} of ${progress.total} done. Keep the app open.")
                    progress?.error != null -> Banner(C.RedBg, C.Red, "Rebuild stopped", progress.error + "\nNothing was lost; you can continue.") {
                        BannerButton("Continue", true) { repo.startRebuild() }
                        BannerButton("Dismiss", false) { repo.clearRebuildError() }
                    }
                    selected == null -> Banner(C.RedBg, C.Red, "Pick an embedding model", "Choose one below to start linking.")
                    embedKeyMissing -> Banner(C.RedBg, C.Red, "Embedding provider has no key", "Add its key under AI providers on this phone.")
                    rag.mismatch -> Banner(C.RedBg, C.Red, "Embedding model changed", "Linking and smart search are paused. Rebuild all notes with the new model, or switch back to keep everything as it was.") {
                        BannerButton("Rebuild all", true) { repo.startRebuild() }
                        BannerButton("Switch back to ${Repository.splitModel(rag.activeModel)?.second ?: "previous"}", false) { run.go { repo.switchBack() } }
                    }
                    rag.missingRows > 0 -> Banner(C.RedBg, C.Red, "${plural(rag.missingNotes, "note")} ${if (rag.missingNotes == 1) "has" else "have"} no embeddings",
                        "Added while linking was off. Rebuild to include them.") { BannerButton("Rebuild", true) { repo.startRebuild() } }
                    !placementSet -> Banner(C.AmberBg, C.Amber, "Pick a placement model", "The index is ready, but new notes are only linked once a placement model is chosen.")
                    else -> Banner(C.GreenBg, C.Green, "Index up to date", "Related points are added automatically. Hybrid search is on.")
                }
                Divider()
                Column(Modifier.alpha(if (s.ragEnabled) 1f else 0.45f)) {
                    SettingRow("Embedding model", "Index built with: ${activeLabel ?: "nothing yet"}") {
                        SmallButton((selected?.second ?: "Choose") + " ▾", enabled = s.ragEnabled && rebuild?.error == null && rebuild == null) { picking = Pick.EMBEDDING }
                    }
                    Divider()
                    SettingRow("Placement model", "Picks 1 of 3 choices — use the cheapest") {
                        SmallButton(modelLabel(s.placementProvider, s.placementModel) + " ▾", enabled = s.ragEnabled) { picking = Pick.PLACEMENT }
                    }
                }
                Divider()
                val hybrid = rag.ready && s.ragEnabled && !embedKeyMissing
                SettingRow("Search mode", null) {
                    Text(if (hybrid) "Hybrid" else "Keyword", style = ts(13, 600, if (hybrid) C.Green else C.G700),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (hybrid) C.GreenBg else C.G100).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }

            // ───────────── Daily podcast ─────────────
            Group("Daily podcast") {
                SettingRow("Make a daily revision podcast", "Works with or without linking") {
                    Toggle(s.podcastEnabled, "Daily podcast", enabled = !run.busy) { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        save(reschedule = true) { put("podcast_enabled", on) }
                    }
                }
                Column(Modifier.alpha(if (s.podcastEnabled) 1f else 0.45f)) {
                    Divider()
                    SettingRow("Podcast model", "Writes the day's script") {
                        SmallButton(modelLabel(s.podcastProvider, s.podcastModel) + " ▾", enabled = s.podcastEnabled) { picking = Pick.PODCAST }
                    }
                    Divider()
                    SettingRow("Max gap between revisions", "1 → 2 → 4 → 8 … stops growing here") {
                        Stepper("${s.intervalCapDays} days", enabled = s.podcastEnabled && !run.busy,
                            onMinus = { save { put("interval_cap_days", (s.intervalCapDays / 2).coerceAtLeast(1)) } },
                            onPlus = { save { put("interval_cap_days", (s.intervalCapDays * 2).coerceAtMost(512)) } })
                    }
                    Divider()
                    SettingRow("Prepare podcast at", "Ready offline before your run") {
                        Pill(formatTime(s.dailyJobTime), s.podcastEnabled) { pickTime = true }
                    }
                    Divider()
                    SettingRow("Podcast length", null) {
                        Stepper("${s.podcastTargetMinutes} min", enabled = s.podcastEnabled && !run.busy,
                            onMinus = { save { put("podcast_target_minutes", (s.podcastTargetMinutes - 5).coerceAtLeast(5)) } },
                            onPlus = { save { put("podcast_target_minutes", (s.podcastTargetMinutes + 5).coerceAtMost(120)) } })
                    }
                    Divider()
                    SettingRow("Revisions per detail level", "Detailed → Shorter → Key points → Headlines") {
                        Stepper("${s.revisionsPerLevel}", enabled = s.podcastEnabled && !run.busy,
                            onMinus = { save { put("revisions_per_level", (s.revisionsPerLevel - 1).coerceAtLeast(1)) } },
                            onPlus = { save { put("revisions_per_level", (s.revisionsPerLevel + 1).coerceAtMost(5)) } })
                    }
                }
            }

            // ───────────── Storage ─────────────
            Group("Storage") {
                SettingRow("Keep small copies of photos",
                    if (s.keepPhotoCopies) "Saved on this phone only, hidden inside ewe (not in the Gallery), about 50 KB each. Never uploaded. Removed if ewe is uninstalled."
                    else "Off: photos are discarded once notes are made.") {
                    Toggle(s.keepPhotoCopies, "Keep small copies of photos", enabled = !run.busy) { on -> save { put("keep_photo_copies", on) } }
                }
                val (count, bytes) = remember(mediaVersion) { repo.mediaStats() }
                if (count > 0) {
                    Divider()
                    SettingRow("${plural(count, "photo")} saved", String.format(Locale.ENGLISH, "%.1f MB on this phone", bytes / 1_000_000.0)) {
                        SmallButton("Clear saved photos") { repo.clearMedia() }
                    }
                }
            }

            // ───────────── Account ─────────────
            Group("Account") {
                SettingRow("Sync", prefs.email) {
                    val (label, bg, fg) = when (sync) {
                        is SyncState.Syncing -> Triple("Syncing…", C.G100, C.G700)
                        is SyncState.Failed -> Triple("Offline copy · tap to retry", C.AmberBg, C.Amber)
                        else -> Triple("Synced · Supabase", C.GreenBg, C.Green)
                    }
                    Text(label, style = ts(13, 600, fg), modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).clickable { repo.refreshInBackground() }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Divider()
                Text("Sign out", style = ts(14, 500), modifier = Modifier.fillMaxWidth().clickable { signingOut = true }.padding(horizontal = 14.dp, vertical = 15.dp))
            }
        }
    }

    editing?.let { kind ->
        ProviderDialog(kind, prefs.apiKey(kind), prefs.compatibleUrl, onDismiss = { editing = null }) { key, url ->
            prefs.setApiKey(kind, key)
            if (kind == ProviderKind.COMPATIBLE) prefs.compatibleUrl = url
            keysVersion++; editing = null
        }
    }
    picking?.let { which ->
        val (title, use, provider, model) = when (which) {
            Pick.NOTES -> Quad("Notes model", ModelUse.CHAT, s.notesProvider, s.notesModel)
            Pick.PLACEMENT -> Quad("Placement model", ModelUse.CHAT, s.placementProvider, s.placementModel)
            Pick.PODCAST -> Quad("Podcast model", ModelUse.CHAT, s.podcastProvider, s.podcastModel)
            Pick.EMBEDDING -> Repository.splitModel(s.ragSelectedModel).let { Quad("Embedding model", ModelUse.EMBEDDING, it?.first, it?.second) }
        }
        ModelPicker(title, prefs, use, provider, model, onDismiss = { picking = null }) { p, m ->
            picking = null
            when (which) {
                Pick.NOTES -> save { put("notes_provider", p); put("notes_model", m) }
                Pick.PLACEMENT -> save { put("placement_provider", p); put("placement_model", m) }
                Pick.PODCAST -> save { put("podcast_provider", p); put("podcast_model", m) }
                Pick.EMBEDDING -> run.go { repo.setEmbeddingModel(p, m) }
            }
        }
    }
    if (pickTime) TimeDialog(s.dailyJobTime, onDismiss = { pickTime = false }) { t ->
        pickTime = false
        save(reschedule = true) { put("daily_job_time", t) }
    }
    if (signingOut) Confirm("Sign out?", "Your notes stay in your Supabase project. The copy on this phone is removed; provider keys stay.", "Sign out", onDismiss = { signingOut = false }) {
        signingOut = false
        run.go { repo.signOut(); PrepareWorker.schedule(context, repo) }
    }
}

private data class Quad(val title: String, val use: ModelUse, val provider: String?, val model: String?)

private fun mask(key: String) = if (key.length <= 10) "••••" else key.take(7) + "…" + key.takeLast(4)

private fun formatTime(t: String): String = runCatching {
    LocalTime.parse(t).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
}.getOrDefault(t)

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caps(title)
        Card(content = content)
    }
}

@Composable
private fun SettingRow(title: String, detail: String?, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = ts(14, 500))
            if (!detail.isNullOrEmpty()) Text(detail, style = ts(12, 400, C.G600, 1.4f))
        }
        Box(Modifier.widthIn(max = 190.dp)) { trailing() }
    }
}

@Composable
private fun Pill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(text, style = ts(15, 600), modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.G100).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun Banner(bg: Color, fg: Color, title: String, text: String, buttons: (@Composable () -> Unit)? = null) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(fg))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = ts(13, 700, fg))
                Text(text, style = ts(13, 400, fg, 1.45f))
            }
        }
        if (buttons != null) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    }
}

@Composable
private fun BannerButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.heightIn(min = 40.dp).clip(shape).background(if (primary) C.Red else C.White)
        .then(if (primary) Modifier else Modifier.background(C.White)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) { Text(text, style = ts(13, 600, if (primary) C.White else C.Red)) }
}

@Composable
private fun ProviderDialog(kind: ProviderKind, key: String, url: String, onDismiss: () -> Unit, onSave: (key: String, url: String) -> Unit) {
    var k by remember { mutableStateOf(key) }
    var u by remember { mutableStateOf(url) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.White,
        title = { Text(kind.label, style = ts(18, 600)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (kind == ProviderKind.COMPATIBLE) OutlinedTextField(u, { u = it }, singleLine = true, label = { Text("Base URL") },
                    placeholder = { Text("https://openrouter.ai/api/v1", style = ts(15, 400, C.G400)) }, textStyle = ts(15), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(k, { k = it }, singleLine = true, label = { Text("API key") }, placeholder = { Text(if (kind == ProviderKind.COMPATIBLE) "Leave empty if none" else kind.hint, style = ts(15, 400, C.G400)) },
                    textStyle = ts(15), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("Stored only on this phone. It is sent with each request and never saved on a server.", style = ts(13, 400, C.G600, 1.45f))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(k, u) }) { Text("Save", style = ts(15, 600)) } },
        dismissButton = {
            Row {
                if (key.isNotEmpty() || (kind == ProviderKind.COMPATIBLE && url.isNotEmpty())) TextButton(onClick = { onSave("", "") }) { Text("Remove", style = ts(15, 500, C.Red)) }
                TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val t = runCatching { LocalTime.parse(current) }.getOrDefault(LocalTime.of(1, 0))
    val state = rememberTimePickerState(t.hour, t.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.White,
        title = { Text("Prepare podcast at", style = ts(18, 600)) },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(String.format(Locale.ENGLISH, "%02d:%02d:00", state.hour, state.minute)) }) { Text("Set", style = ts(15, 600)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) } },
    )
}
