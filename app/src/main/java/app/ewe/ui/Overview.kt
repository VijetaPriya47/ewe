package app.ewe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ewe.data.Repository
import app.ewe.data.Session
import app.ewe.data.Snapshot
import app.ewe.data.Summary
import app.ewe.data.instantOf

/** A mindmap of the session's section titles only — for trying to recall what is under each. */
fun recallMap(title: String, sections: List<String>): String {
    fun clean(s: String) = s.replace(Regex("[()\\[\\]{}\"`:;]"), " ").replace(Regex("\\s+"), " ").trim()
    val root = clean(title).ifEmpty { "Notes" }
    return buildString {
        append("mindmap\n  root((").append(root).append("))")
        sections.map(::clean).filter { it.isNotEmpty() }.distinct().take(24).forEach { append("\n    ").append(it) }
    }
}

@Composable
fun ColumnScope.OverviewTab(repo: Repository, snap: Snapshot, session: Session) {
    val run = rememberRunner()
    val sections = snap.sectionsOf(session.id).filter { snap.points(it.id).isNotEmpty() }
    val summaries = snap.summariesOf(session.id)
    val ai = summaries.firstOrNull { it.kind == "ai_overview" }
    val mine = summaries.filter { it.kind == "upload" }
    var deleting by remember { mutableStateOf<Summary?>(null) }

    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Caps("Recall map")
        if (sections.isEmpty()) {
            Text("When this session has notes, their section names appear here as a map.", style = ts(13, 400, C.G600, 1.45f))
        } else {
            Text("Only the section names. Try to recall what's under each one, then check Notes.", style = ts(13, 400, C.G600, 1.45f))
            MermaidDiagram(recallMap(session.title, sections.map { it.title }), label = "From your section titles · updates by itself")

            if (ai != null) {
                val stale = instantOf(session.updatedAt) > instantOf(ai.createdAt)
                MermaidDiagram(ai.mermaid, label = "AI overview · ${shortDate(ai.createdAt)}" + if (stale) " · notes changed since" else "",
                    fixKey = ai.id, onFixed = { repo.saveRepairedSummary(ai.id, it) }, aiFix = { c, e -> repo.fixDiagram(c, e) },
                    onLabel = { deleting = ai })
            }
            OutlineButton(if (ai == null) "Make a better overview (AI)" else "Update the AI overview", icon = Ic.Spark, height = 40.dp, busy = run.busy) {
                run.go { repo.makeOverview(session.id) }
            }
            Text("Adds the key sub-topic names under each section, still without the details. Uses your notes model once.",
                style = ts(12, 400, C.G500, 1.4f))
        }

        Box(Modifier.padding(top = 8.dp)) { Caps(if (mine.isEmpty()) "Your summaries" else "Your summaries · ${mine.size}") }
        if (mine.isEmpty()) Text("Send your own summary, or a photo of a diagram you drew, with Summary selected above the message box " +
            "(or start the message with \"summary\"). It is redrawn here and nothing is added to Notes.", style = ts(13, 400, C.G600, 1.45f))
        mine.forEach { s ->
            MermaidDiagram(s.mermaid, label = "${s.title.ifBlank { "Summary" }} · ${shortDate(s.createdAt)}",
                fixKey = s.id, onFixed = { repo.saveRepairedSummary(s.id, it) }, aiFix = { c, e -> repo.fixDiagram(c, e) },
                onLabel = { deleting = s })
            if (s.note.isNotBlank()) Text(s.note, style = ts(13, 400, C.G700, 1.45f), modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp))
        }
    }

    deleting?.let { s ->
        Confirm(if (s.kind == "ai_overview") "Delete the AI overview?" else "Delete this summary?",
            "It is removed from Overview. Your notes are not changed.", "Delete", danger = true, onDismiss = { deleting = null }) {
            deleting = null
            run.go { repo.deleteSummary(s.id) }
        }
    }
}
