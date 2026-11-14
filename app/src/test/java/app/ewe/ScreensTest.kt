package app.ewe

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.geometry.Offset
import app.ewe.ui.CropScreen
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.ewe.data.AppJson
import app.ewe.data.Bullet
import app.ewe.data.BulletLink
import app.ewe.data.LocalPrefs
import app.ewe.data.Message
import app.ewe.data.ProviderKind
import app.ewe.data.RagStatus
import app.ewe.data.Repository
import app.ewe.data.Schedule
import app.ewe.data.Section
import app.ewe.data.Session
import app.ewe.data.Settings
import app.ewe.data.Snapshot
import app.ewe.data.Supabase
import app.ewe.podcast.AudioFile
import app.ewe.podcast.Chapter
import app.ewe.podcast.Episode
import app.ewe.podcast.EpisodeStore
import app.ewe.ui.AuthScreen
import app.ewe.ui.C
import app.ewe.ui.EweTheme
import app.ewe.ui.HomeScreen
import app.ewe.ui.NewSessionScreen
import app.ewe.ui.RevisionScreen
import app.ewe.ui.SearchScreen
import app.ewe.ui.SessionScreen
import app.ewe.ui.SettingsScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Renders every screen with realistic data and saves a picture to app/build/screens.
 * Catches crashes in the real UI code without needing a phone or emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.now()
    private fun d(daysAgo: Long) = today.minusDays(daysAgo).toString()
    private fun t(daysAgo: Long, hour: Int = 10) = "${d(daysAgo)}T${"%02d".format(hour)}:00:00+00:00"

    private fun snapshot(settings: Settings = Settings(), rag: RagStatus = RagStatus()): Snapshot {
        val sessions = listOf(
            Session("cdn", "CDN", "topic", true, null, t(2), t(0)),
            Session("sud", "Making a Sudoku", "project", true, null, t(3), t(1)),
            Session("http", "HTTP Caching", "topic", true, null, t(9), t(2)),
            Session("sd", "System Design · Ch 4", "topic", true, null, t(20), t(8)),
            Session("lb", "Load Balancers", "topic", true, null, t(40), t(13)),
            Session("trip", "Trip Planning", "project", false, null, t(60), t(21)),
        )
        val sections = listOf(
            Section("basics", "cdn", "Basics", 1.0), Section("edge", "cdn", "Edge servers", 2.0), Section("caching", "cdn", "Caching", 3.0, important = true),
            Section("grid", "sud", "Grid rules", 1.0), Section("gen", "sud", "Generator", 2.0),
            Section("cc", "http", "Cache-Control headers", 1.0), Section("shard", "sd", "Sharding", 1.0), Section("hc", "lb", "Health checks", 1.0),
            Section("trip1", "trip", "Packing", 1.0),
        )
        var n = 0
        fun b(sec: String, text: String, ago: Long, origin: String, pos: Double) = Bullet("b${n++}", sec, text, pos, d(ago), origin, "m1")
        val bullets = listOf(
            b("basics", "CDN = network of servers near users → serves content faster", 2, "cdn", 1.0),
            b("basics", "Lower latency + less load on origin server", 2, "cdn", 2.0),
            b("basics", "Static assets (images, JS, CSS) are the usual content", 2, "cdn", 3.0),
            b("edge", "Edge server / PoP = CDN location close to users [[ref:aaaa-0001]]", 9, "cdn", 1.0),
            b("edge", "Request routed to nearest PoP via DNS or Anycast", 0, "cdn", 1.5).copy(important = true),
            b("edge", "Anycast: same IP announced from many PoPs → network picks closest", 0, "cdn", 1.75),
            b("edge", "Cache miss → edge fetches from origin, then stores copy", 9, "cdn", 2.0),
            Bullet("code", "caching", "location /static/ {\n    expires 30d;\n    add_header Cache-Control \"public\";\n}", 4.0, d(0), "cdn", "m3", kind = "code", lang = "nginx"),
            Bullet("tbl", "caching", "| Header | Meaning |\n| --- | --- |\n| max-age | seconds fresh |\n| no-store | never cache |", 5.0, d(0), "cdn", "m3", kind = "table", highlight = "red"),
            Bullet("diag", "edge", "flowchart TD\n  U[User] --> D[DNS]\n  D --> P[Nearest PoP]\n  P -->|hit| C[Cached copy]\n  P -->|miss| O[Origin]", 2.5, d(0), "cdn", "m3", kind = "mermaid"),
            b("caching", "TTL = how long edge keeps a copy before re-checking origin [[ref:aaaa-0004]]", 0, "cdn", 1.0).copy(important = true, highlight = "yellow"),
            b("caching", "Purge by path to remove stale content before TTL ends", 0, "cdn", 2.0),
            b("grid", "9×9 grid; each row, column and 3×3 box holds 1–9 once", 1, "sud", 1.0),
            b("gen", "Backtracking fills a full grid, then remove cells while the solution stays unique", 1, "sud", 1.0),
            b("gen", "Cache only static puzzle assets; generate grids on client", 1, "sud", 2.0),
            b("cc", "max-age sets freshness lifetime in seconds", 8, "http", 1.0),
            b("shard", "Shard key decides which node stores a row", 16, "sd", 1.0),
            b("hc", "Unhealthy targets are taken out of rotation", 32, "lb", 1.0),
            b("trip1", "Passport, charger, rain jacket", 60, "trip", 1.0),
        )
        val links = listOf(BulletLink("l1", "b11", "caching", 3.0, t(1)))
        val refs = listOf(
            app.ewe.data.Reference("aaaa-0001", "cdn", 1, "https://www.cloudflare.com/learning/cdn/", "Cloudflare Learning — CDN basics", "Intro to edge servers and PoPs.", d(5)),
            app.ewe.data.Reference("aaaa-0002", "cdn", 2, "https://www.rfc-editor.org/rfc/rfc7234", "RFC 7234 — HTTP caching", "The spec behind Cache-Control.", d(4)),
            app.ewe.data.Reference("aaaa-0004", "cdn", 4, "https://developer.mozilla.org/en-US/docs/Web/HTTP/Caching", "MDN — HTTP caching", "Covers TTL and validation.", d(0)),
        )
        val summaries = listOf(app.ewe.data.Summary("bbbb-0001", "cdn", "upload", "Request path", "flowchart LR\n  U[User] --> E[Edge]\n  E -->|hit| C[Cached copy]\n  E -->|miss| O[Origin]", "Hits are served at the edge; misses go to the origin.", t(0)))
        val messages = listOf(
            Message("m1", "cdn", "user", "", 3, t(2)),
            Message("m2", "cdn", "assistant", "Added **12 points** in 2 new sections: **Basics**, **Edge servers**.", 0, t(2, 11)),
            Message("m3", "cdn", "user", "Subtitles from \"CDN caching explained\": cache hit ratio, TTL, purging by path…", 0, t(0, 8)),
            Message("m5", "cdn", "user", "https://developer.mozilla.org/en-US/docs/Web/HTTP/Caching", 0, t(0, 9), mode = "reference"),
            Message("m6", "cdn", "assistant", "Saved **1 reference**: [4] MDN — HTTP caching.\nNothing was added to Notes.", 0, t(0, 9), mode = "reference"),
            Message("m4", "cdn", "assistant", "Added **5 points**: 3 inline in **Edge servers**, 2 in a new section **Caching**.\n1 point skipped: already in your notes.", 0, t(0, 9)),
        )
        val schedule = listOf(
            Schedule("r1", "edge", d(0), 1, 0, d(-1)), Schedule("r2", "caching", d(0), 1, 0, d(-1)),
            Schedule("r3", "basics", d(2), 2, 1, d(0)), Schedule("r4", "gen", d(1), 1, 0, d(0)), Schedule("r5", "grid", d(1), 1, 0, d(0)),
            Schedule("r6", "edge", d(9), 4, 2, d(0)), Schedule("r7", "cc", d(8), 8, 3, d(0)),
            Schedule("r8", "shard", d(16), 16, 4, d(0)), Schedule("r9", "hc", d(32), 32, 5, d(0)),
        )
        return Snapshot(settings, sessions, sections, bullets, links, messages, schedule, refs, summaries, rag, System.currentTimeMillis())
    }

    private fun repo(snap: Snapshot, keys: Boolean = true): Repository {
        val prefs = LocalPrefs(context)
        prefs.supabaseUrl = "https://example.supabase.co"; prefs.anonKey = "anon"
        prefs.refreshToken = "r"; prefs.accessToken = "a"; prefs.userId = "u"; prefs.email = "you@example.com"
        if (keys) {
            prefs.setApiKey(ProviderKind.OPENAI, "sk-proj-abcdefgh12345678a91f")
            prefs.setApiKey(ProviderKind.ANTHROPIC, "sk-ant-abcdefgh1234567877c2")
            prefs.setApiKey(ProviderKind.GEMINI, "AIzaSyabcdefgh12345678k3Q")
        }
        File(context.filesDir, "snapshot.json").writeText(AppJson.encodeToString(Snapshot.serializer(), snap))
        return Repository(context, prefs, Supabase(prefs))
    }

    private val configured = Settings(notesProvider = "anthropic", notesModel = "claude-sonnet-5-5", placementProvider = "gemini",
        placementModel = "gemini-flash-lite", podcastProvider = "anthropic", podcastModel = "claude-haiku-5-5", podcastEnabled = true)

    private fun show(name: String, content: @Composable () -> Unit) {
        compose.setContent { EweTheme { Box(Modifier.fillMaxSize().background(C.White)) { content() } } }
        compose.waitForIdle()
        shot(name)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // No media services in this harness: screens must work without a player connection.
        val shadow = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        listOf(app.ewe.podcast.PlaybackService::class.java, app.ewe.podcast.ReaderService::class.java)
            .forEach { shadow.declareComponentUnbindable(android.content.ComponentName(context, it)) }
    }

    @Test fun auth() = show("0_sign_in") { AuthScreen(repo(Snapshot()).also { it.prefs.clearAuth(); it.prefs.supabaseUrl = ""; it.prefs.anonKey = "" }) }

    @Test fun home() {
        val r = repo(snapshot(configured))
        show("1_home") { HomeScreen(r, EpisodeStore(context), {}, {}, {}, {}, {}) }
        compose.onAllNodesWithText("Inactive")[0].performClick()
        shot("1_home_inactive_filter")
    }

    @Test fun homeEmpty() = show("1_home_empty") { HomeScreen(repo(Snapshot()), EpisodeStore(context), {}, {}, {}, {}, {}) }

    @Test fun newSession() {
        val r = repo(snapshot(configured))
        show("2_new_session") { NewSessionScreen(r, {}, {}) }
        compose.onNodeWithText("Project").performClick()
        shot("2_new_session_project")
    }

    @Test fun chat() {
        val r = repo(snapshot(configured))
        show("3_session_chat") { SessionScreen(r, "cdn", false, {}, {}, {}) }
    }

    @Test fun chatNoModel() = show("3_session_chat_no_model") { SessionScreen(repo(snapshot(), keys = false), "sud", false, {}, {}, {}) }

    @Test fun notes() {
        val r = repo(snapshot(configured))
        show("4_session_notes") { SessionScreen(r, "cdn", true, {}, {}, {}) }
        compose.onNodeWithText("Request routed to nearest PoP via DNS or Anycast").performClick()
        shot("4_session_notes_selected")
        compose.onNodeWithContentDescription("Delete point").performClick()
        shot("4_session_notes_delete")
    }

    @Test fun listening() {
        val r = repo(snapshot(configured))
        show("4_session_notes_listen_button") { SessionScreen(r, "cdn", true, {}, {}, {}) }
        compose.onNodeWithText("Listen to notes").performClick()
        shot("4_session_notes_listening")
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Next section").performClick()
        shot("4_session_notes_listening_paused")
        compose.onNodeWithContentDescription("Stop reading").performClick()
        compose.onNodeWithText("Listen to notes").assertExists()
        app.ewe.podcast.NotesReader.get(context).shutdown()
    }

    private fun testPhoto(): android.net.Uri {
        // left half red, right half blue, 400×200
        val bmp = android.graphics.Bitmap.createBitmap(400, 200, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).apply {
            drawRect(0f, 0f, 200f, 200f, android.graphics.Paint().apply { color = android.graphics.Color.RED })
            drawRect(200f, 0f, 400f, 200f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
        }
        val f = File(context.cacheDir, "test_photo.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return android.net.Uri.fromFile(f)
    }

    @Test fun cropMathOnRealPixels() {
        val m = app.ewe.data.CropMath
        val bmp = app.ewe.data.Images.load(context, testPhoto(), 2048)
        val right = m.apply(bmp, app.ewe.data.CropRect(0.5f, 0f, 1f, 1f), 0)
        org.junit.Assert.assertEquals(200, right.width); org.junit.Assert.assertEquals(200, right.height)
        org.junit.Assert.assertEquals(android.graphics.Color.BLUE, right.getPixel(100, 100))
        val turned = m.apply(bmp, app.ewe.data.CropRect(), 1)          // 90° clockwise: red ends up on top
        org.junit.Assert.assertEquals(200, turned.width); org.junit.Assert.assertEquals(400, turned.height)
        org.junit.Assert.assertEquals(android.graphics.Color.RED, turned.getPixel(100, 50))
        org.junit.Assert.assertEquals(android.graphics.Color.BLUE, turned.getPixel(100, 350))
    }

    @Test fun cropScreen() {
        var result: android.net.Uri? = null
        var cancelled = false
        show("10_crop") { CropScreen(testPhoto(), onCancel = { cancelled = true }, onCropped = { result = it }) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Crop area").fetchSemanticsNodes().isNotEmpty() }
        // drag the top-left corner to the middle
        compose.onNodeWithContentDescription("Crop area").performTouchInput {
            swipe(Offset(3f, 3f), Offset(width / 2f, height / 3f), durationMillis = 400)
        }
        shot("10_crop_dragged")
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { result != null }
        val out = android.graphics.BitmapFactory.decodeFile(result!!.path)
        org.junit.Assert.assertTrue("cropped ${out.width}×${out.height}", out.width in 180..220 && out.height in 120..145)
        org.junit.Assert.assertFalse(cancelled)
    }

    @Test fun referencesAndOverview() {
        val r = repo(snapshot(configured))
        show("9_references") { SessionScreen(r, "cdn", false, {}, {}, {}) }
        compose.onNodeWithText("References").performClick()
        compose.onNodeWithText("3 references · each link is kept once").assertExists()
        shot("9_references_tab")
        compose.onNodeWithText("Overview").performClick()
        compose.onNodeWithText("Make a better overview (AI)").assertExists()
        shot("9_overview_tab")
        compose.onNodeWithText("Notes").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Edge server / PoP", substring = true))
        shot("9_notes_chips")
        compose.onNodeWithContentDescription("Reference 1", useUnmergedTree = true).performClick()
        compose.onNodeWithText("All references").assertExists()
        shot("9_notes_reference_card")
        compose.onNodeWithText("All references").performClick()
        compose.onNodeWithText("3 references · each link is kept once").assertExists()
    }

    @Test fun modeChip() {
        val r = repo(snapshot(configured))
        show("9_chat_modes") { SessionScreen(r, "cdn", false, {}, {}, {}) }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("reference https://web.dev/articles/http-cache")
        compose.onNodeWithText("Goes to References only — nothing is added to Notes.").assertExists()
        shot("9_chat_mode_reference")
    }

    @Test fun richNotes() {
        val r = repo(snapshot(configured))
        show("4_session_notes_rich") { SessionScreen(r, "cdn", true, {}, {}, {}) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Code · nginx"))
        shot("4_session_notes_rich_code_table")
        compose.onNodeWithContentDescription("Copy code").performClick()
        val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        org.junit.Assert.assertTrue(clip.startsWith("location /static/ {\n    expires 30d;"))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("TTL = how long edge keeps a copy before re-checking origin", substring = true))
        // tap the start of the line (the [4] chip at its end opens the reference instead)
        compose.onNodeWithText("TTL = how long edge keeps a copy before re-checking origin", substring = true)
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(60f, 20f)) }
        compose.onNodeWithContentDescription("Highlight red").assertExists()
        compose.onNodeWithContentDescription("Remove underline").assertExists()
        shot("4_session_notes_rich_selected")
    }

    @Test fun plainEpisode() {
        val r = repo(snapshot(configured))
        val store = EpisodeStore(context)
        store.save(Episode(today.toString(), System.currentTimeMillis(), 30, listOf(
            Chapter(null, null, "Intro", "", "", 0, 0, "", "hi", listOf(AudioFile("/x/i.wav", 20_000))),
            Chapter("r3", "cdn", "CDN", "Basics", d(2), 2, 2, "Detailed", "s", listOf(AudioFile("/x/a.wav", 240_000))),
        ), plain = true, plainReason = "The AI script could not be written: No internet connection."))
        show("8_revision_plain") { RevisionScreen(r, store, {}, {}) }
        compose.onNodeWithText("Try the AI script again").assertExists()
    }

    @Test fun diagram() {
        val r = repo(snapshot(configured))
        show("4_session_notes_diagram") { SessionScreen(r, "cdn", true, {}, {}, {}) }
        compose.onNodeWithContentDescription("Copy diagram code").performScrollTo().performClick()
        val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        org.junit.Assert.assertTrue(clip.startsWith("flowchart TD"))
        compose.onNodeWithText("Code").performClick()
        shot("4_session_notes_diagram_code")
    }

    @Test fun options() {
        val r = repo(snapshot(configured))
        show("6_session_options") { SessionScreen(r, "cdn", false, {}, {}, {}) }
        compose.onNodeWithContentDescription("Session options").performClick()
        shot("6_session_options_open")
    }

    @Test fun search() {
        val r = repo(snapshot(configured))
        show("5_search_empty") { SearchScreen(r, {}, { _, _ -> }) }
        compose.onNodeWithText("Search sessions and notes").assertExists()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("sudoku")
        shot("5_search")
    }

    @Test fun settingsOff() = show("7_settings_default") { SettingsScreen(repo(snapshot(), keys = false), {}) }

    @Test fun settingsStates() {
        val on = configured.copy(ragEnabled = true, ragSelectedModel = "openai:text-embedding-3-small", ragActiveModel = "openai:text-embedding-3-small", keepPhotoCopies = true)
        val r = repo(snapshot(on, RagStatus(true, on.ragSelectedModel, on.ragActiveModel, 14, 0, 0, 0, 14, true)))
        show("7_settings_ready") { SettingsScreen(r, {}) }
        compose.onNodeWithText("Search mode").performScrollTo()
        shot("7_settings_ready_linking")
        compose.onNodeWithText("Sign out").performScrollTo()
        shot("7_settings_ready_bottom")
    }

    @Test fun settingsMissing() {
        val on = configured.copy(ragEnabled = true, ragSelectedModel = "openai:text-embedding-3-small", ragActiveModel = "openai:text-embedding-3-small")
        val r = repo(snapshot(on, RagStatus(true, on.ragSelectedModel, on.ragActiveModel, 14, 5, 12, 0, 9, false)))
        show("7_settings_missing") { SettingsScreen(r, {}) }
        compose.onNodeWithText("Search mode").performScrollTo()
        shot("7_settings_missing_linking")
    }

    @Test fun settingsMismatch() {
        val on = configured.copy(ragEnabled = true, ragSelectedModel = "gemini:gemini-embedding-001", ragActiveModel = "openai:text-embedding-3-small")
        val r = repo(snapshot(on, RagStatus(true, on.ragSelectedModel, on.ragActiveModel, 14, 0, 0, 0, 14, false)))
        show("7_settings_mismatch") { SettingsScreen(r, {}) }
        compose.onNodeWithText("Search mode").performScrollTo()
        shot("7_settings_mismatch_linking")
    }

    @Test fun revisionPlanned() = show("8_revision_not_prepared") { RevisionScreen(repo(snapshot(configured)), EpisodeStore(context), {}, {}) }

    @Test fun revisionOff() = show("8_revision_off") { RevisionScreen(repo(snapshot()), EpisodeStore(context), {}, {}) }

    @Test fun revisionReady() {
        val r = repo(snapshot(configured))
        val store = EpisodeStore(context)
        fun ch(id: String?, topic: String, section: String, ago: Long, day: Int, rev: Int, level: String, secs: Long) =
            Chapter(id, topic, topic, section, d(ago), day, rev, level, "script", listOf(AudioFile("/x/$section.wav", secs * 1000)))
        store.save(Episode(today.toString(), System.currentTimeMillis(), 30, listOf(
            ch(null, "Intro", "", 0, 0, 0, "", 30),
            ch("r4", "Making a Sudoku", "Generator", 1, 1, 1, "Detailed", 420), ch("r3", "CDN", "Basics", 2, 2, 2, "Detailed", 360),
            ch("r6", "CDN", "Edge servers", 9, 4, 3, "Shorter", 240), ch("r7", "HTTP Caching", "Cache-Control headers", 8, 8, 4, "Shorter", 240),
            ch("r8", "System Design · Ch 4", "Sharding", 16, 16, 5, "Key points", 120), ch("r9", "Load Balancers", "Health checks", 32, 32, 6, "Key points", 90),
            ch(null, "Outro", "", 0, 0, 0, "", 30),
        ), deferred = 1, heard = setOf("r4"), marked = setOf("r3")))
        show("8_revision_ready") { RevisionScreen(r, store, {}, {}) }
    }
}
