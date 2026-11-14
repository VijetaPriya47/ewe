package app.ewe.ui

import android.content.ComponentName
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.ewe.data.Repository
import app.ewe.podcast.Chapter
import app.ewe.podcast.Episode
import app.ewe.podcast.EpisodeStore
import app.ewe.podcast.Planner
import app.ewe.podcast.PlaybackService
import app.ewe.podcast.PrepareWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private fun minutes(words: Int): String = minutesOf(words / Planner.WORDS_PER_MINUTE.toDouble())
private fun minutesOf(m: Double): String {
    val half = (m * 2).roundToInt() / 2.0
    return when {
        half < 1 -> "under 1 min"
        half < 10 && half % 1.0 != 0.0 -> "$half min"
        else -> "${half.roundToInt()} min"
    }
}
private fun clock(ms: Long): String = (ms / 1000).let { String.format(Locale.ENGLISH, "%02d:%02d", it / 60, it % 60) }

@Composable
fun RevisionScreen(repo: Repository, store: EpisodeStore, onBack: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val snap by repo.snapshot.collectAsState()
    val stored by store.episode.collectAsState()
    val today = repo.today()
    val episode = stored?.takeIf { it.date == today.toString() }
    val s = snap.settings
    val run = rememberRunner()
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(PrepareWorker.NOW) }.collectAsState(emptyList())
    val job = work.firstOrNull()
    val preparing = job != null && !job.state.isFinished
    val failure = job?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString(PrepareWorker.KEY_ERROR)

    val plan = remember(snap, episode) {
        Planner.plan(Planner.due(snap, today, s.revisionsPerLevel, episode?.marked.orEmpty() + repo.pendingListenedIds()), s.podcastTargetMinutes)
    }
    val hasAudio = episode != null && episode.sections.isNotEmpty()
    val dueCount = if (hasAudio) episode!!.sections.size else plan.segments.size

    Column(Modifier.fillMaxSize().background(C.White)) {
        TopBar("Today's Revision", onBack, subtitle = "${weekCommaDate(today)} · " + if (dueCount == 0) "nothing due" else "${plural(dueCount, "section")} ${if (hasAudio) "in today's episode" else "due"}")

        when {
            !s.podcastEnabled -> Info("The daily podcast is off",
                "Turn it on in Settings and ewe prepares one spoken revision of everything due each night, ready to play offline.", "Open Settings", onSettings)
            hasAudio -> Player(repo, store, episode!!, plan.segments.count { seg -> episode.chapters.none { it.scheduleId == seg.item.scheduleId } }, preparing)
            else -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when {
                                preparing -> job?.progress?.getString(PrepareWorker.KEY_STAGE) ?: "Starting…"
                                plan.segments.isEmpty() -> "Nothing is due today"
                                else -> "Not prepared yet"
                            }, style = ts(17, 600, C.White))
                            Text(when {
                                preparing -> "The script is written once, then read by your phone's own voice. Keep the app open."
                                plan.segments.isEmpty() -> "New notes come back the day after you add them, then after 2, 4, 8 … days."
                                else -> "About ${plan.minutes} min. It is prepared automatically at ${timeLabel(s.dailyJobTime)}, or you can make it now."
                            }, style = ts(13, 400, C.G300, 1.45f))
                            if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = C.White, trackColor = C.G700)
                        }
                    }
                    if (failure != null && !preparing) item {
                        Text(failure, style = ts(13, 500, C.Red, 1.45f), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.RedBg).padding(12.dp))
                    }
                    if (plan.segments.isNotEmpty()) {
                        item { Box(Modifier.padding(top = 10.dp, bottom = 4.dp)) { Caps("Due today") } }
                        itemsIndexed(plan.segments) { _, seg ->
                            EpisodeRow(seg.item.section, "${seg.item.topic} · added ${weekDate(seg.item.addedDate)}", seg.item.intervalDays,
                                "Rev ${seg.item.revisionNo} · ${seg.item.level.label} · ${minutes(seg.wordBudget)}", playing = false, state = RowState.NONE, onToggle = null)
                        }
                    }
                    if (plan.deferred.isNotEmpty()) item { Text("${plural(plan.deferred.size, "section")} will move to tomorrow to fit ${s.podcastTargetMinutes} minutes.", style = ts(13, 400, C.G600)) }
                }
                if (plan.segments.isNotEmpty()) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                    PrimaryButton("Prepare now", busy = preparing) { PrepareWorker.prepareNow(context) }
                }
            }
        }
    }
}

@Composable
private fun Info(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(C.G50).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = ts(17, 600))
        Text(body, style = ts(14, 400, C.G600, 1.45f))
        OutlineButton(action, height = 48.dp, onClick = onAction)
    }
}

private fun timeLabel(t: String) = runCatching {
    java.time.LocalTime.parse(t).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
}.getOrDefault(t)

private enum class RowState { NONE, HEARD, MARKED }

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Player(repo: Repository, store: EpisodeStore, episode: Episode, newlyDue: Int, preparing: Boolean) {
    val context = LocalContext.current
    val run = rememberRunner()
    val toast = LocalToast.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playing by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0L) }
    val items = remember(episode.preparedAt) { PlaybackService.items(episode) }
    val starts = remember(episode.preparedAt) {
        var t = 0L
        episode.chapters.flatMap { it.files }.map { f -> t.also { t += f.durationMs } }
    }

    LaunchedEffect(Unit) {
        controller = runCatching {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().await()
        }.getOrNull()
    }
    DisposableEffect(controller) { val c = controller; onDispose { c?.release() } }

    // Load today's episode into the player (once per prepared episode).
    LaunchedEffect(controller, episode.preparedAt) {
        val c = controller ?: return@LaunchedEffect
        if (items.isEmpty()) return@LaunchedEffect
        val loaded = c.mediaItemCount == items.size && c.getMediaItemAt(0).mediaId == items[0].mediaId
        if (!loaded) { c.setMediaItems(items); c.prepare() }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                playing = c.isPlaying
                index = c.currentMediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                position = c.currentPosition.coerceAtLeast(0)
            }
            delay(400)
        }
    }

    val currentChapter = items.getOrNull(index)?.let { PlaybackService.chapterOf(it) } ?: 0
    val elapsed = (starts.getOrElse(index) { 0L } + position).coerceAtMost(episode.durationMs)
    val total = episode.durationMs.coerceAtLeast(1)

    // ── dark player card ──
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Prepared " + Instant.ofEpochMilli(episode.preparedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)), style = ts(13, 400, C.G300))
            if (episode.plain) Tag("Plain reading", C.AmberBg, C.Amber, 600) else Tag("Ready offline", C.GreenBg, C.Green, 600)
        }
        Text("Now: ${episode.chapters.getOrNull(currentChapter)?.title.orEmpty()}", style = ts(17, 600, C.White, 1.35f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(C.G700).semantics { contentDescription = "Playback progress" }) {
                Box(Modifier.fillMaxWidth(elapsed / total.toFloat()).height(4.dp).clip(CircleShape).background(C.White))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(elapsed), style = ts(12, 400, C.G300)); Text(clock(episode.durationMs), style = ts(12, 400, C.G300))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Replay, "Back 15 seconds", tint = C.White, iconSize = 24.dp) { controller?.seekBack() }
            Box(Modifier.size(64.dp).clip(CircleShape).background(C.White).clickable(role = Role.Button) {
                val c = controller
                when {
                    c == null -> toast("The player is still starting.")
                    c.isPlaying -> c.pause()
                    else -> { if (c.playbackState == androidx.media3.common.Player.STATE_ENDED) c.seekTo(0, 0); c.play() }
                }
            }.semantics { contentDescription = if (playing) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
                Icon(if (playing) Ic.Pause else Ic.Play, null, tint = C.Ink, modifier = Modifier.size(26.dp))
            }
            IconBtn(Ic.SkipNext, "Skip to next section", tint = C.White, iconSize = 24.dp) {
                // jump to the first file of the next chapter
                val next = items.indexOfFirst { PlaybackService.chapterOf(it) > currentChapter }
                if (next >= 0) controller?.seekTo(next, 0) else toast("This is the last section.")
            }
        }
    }

    // ── what is in the episode ──
    val heard = episode.heard
    val marked = episode.marked
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (episode.plain) item {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.AmberBg).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Read from your notes, without AI", style = ts(13, 700, C.Amber))
                Text((episode.plainReason ?: "The AI script could not be made.") + " Your notes are read as they are, shortened for older sections.",
                    style = ts(13, 400, C.Amber, 1.45f))
                Text(if (preparing) "Trying again…" else "Try the AI script again", style = ts(13, 600, C.Blue),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !preparing) {
                        controller?.run { stop(); clearMediaItems() }
                        PrepareWorker.prepareNow(context)
                    }.padding(vertical = 4.dp))
            }
        }
        item { Box(Modifier.padding(bottom = 4.dp)) { Caps("In today's episode") } }
        itemsIndexed(episode.chapters) { i, ch ->
            if (ch.isSection) EpisodeRow(ch.section, "${ch.topic} · added ${weekDate(ch.addedDate)}", ch.intervalDays,
                "Rev ${ch.revisionNo} · ${ch.level} · ${minutesOf(ch.durationMs / 60000.0)}", playing = i == currentChapter,
                state = when (ch.scheduleId) { in marked -> RowState.MARKED; in heard -> RowState.HEARD; else -> RowState.NONE },
                onToggle = if (ch.scheduleId in marked) null else {
                    { store.update { e -> e.copy(heard = if (ch.scheduleId!! in e.heard) e.heard - ch.scheduleId else e.heard + ch.scheduleId) } }
                },
                onClick = { items.indexOfFirst { PlaybackService.chapterOf(it) == i }.takeIf { it >= 0 }?.let { controller?.seekTo(it, 0) } })
        }
        if (episode.deferred > 0) item { Text("${plural(episode.deferred, "section")} moved to tomorrow to fit ${episode.targetMinutes} minutes.", style = ts(13, 400, C.G600)) }
        if (newlyDue > 0) item {
            Text(if (preparing) "Making a new episode…" else "${plural(newlyDue, "more section")} became due since this was prepared. Tap to make a new episode.",
                style = ts(13, 600, C.Blue), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !preparing) {
                    controller?.run { stop(); clearMediaItems() }
                    PrepareWorker.prepareNow(context)
                }.padding(vertical = 6.dp))
        }
    }

    val open = episode.sections.mapNotNull { it.scheduleId }.filter { it !in marked }
    val chosen = open.filter { it in heard }
    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
        OutlineButton(
            when {
                open.isEmpty() -> "All marked as listened"
                chosen.isEmpty() || chosen.size == open.size -> "Mark as listened · schedule next"
                else -> "Mark ${chosen.size} as listened · schedule next"
            },
            Modifier.fillMaxWidth(), enabled = open.isNotEmpty(), busy = run.busy,
        ) {
            val ids = chosen.ifEmpty { open }
            run.go {
                store.update { it.copy(marked = it.marked + ids) }
                repo.markListened(ids)
                toast("${plural(ids.size, "section")} scheduled for the next revision.")
            }
        }
    }
}

@Composable
private fun EpisodeRow(section: String, sub: String, day: Int, detail: String, playing: Boolean, state: RowState,
                       onToggle: (() -> Unit)?, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (playing) C.G100 else androidx.compose.ui.graphics.Color.Transparent)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onToggle != null || state != RowState.NONE) {
            val done = state != RowState.NONE
            Box(Modifier.size(24.dp).clip(CircleShape).background(when (state) { RowState.MARKED -> C.Green; RowState.HEARD -> C.Ink; else -> C.White })
                .then(if (done) Modifier else Modifier.border(1.5.dp, C.G300, CircleShape))
                .then(if (onToggle != null) Modifier.clickable(role = Role.Checkbox, onClick = onToggle) else Modifier)
                .semantics { contentDescription = when (state) { RowState.MARKED -> "Marked as listened"; RowState.HEARD -> "Heard"; else -> "Not heard yet" } },
                contentAlignment = Alignment.Center) {
                if (done) Icon(Ic.Check, null, tint = C.White, modifier = Modifier.size(14.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(section, style = ts(15, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = ts(12, 400, C.G600, 1.35f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state == RowState.MARKED) Tag("Listened", C.GreenBg, C.Green, 600) else Tag("Day $day", C.BlueBg, C.Blue, 600)
            Text(detail, style = ts(11, 400, C.G600))
        }
    }
}
