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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
            Section("basics", "cdn", "Basics", 1.0), Section("edge", "cdn", "Edge servers", 2.0), Section("caching", "cdn", "Caching", 3.0),
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
            b("edge", "Edge server / PoP = CDN location close to users", 9, "cdn", 1.0),
            b("edge", "Request routed to nearest PoP via DNS or Anycast", 0, "cdn", 1.5),
            b("edge", "Anycast: same IP announced from many PoPs → network picks closest", 0, "cdn", 1.75),
            b("edge", "Cache miss → edge fetches from origin, then stores copy", 9, "cdn", 2.0),
            b("caching", "TTL = how long edge keeps a copy before re-checking origin", 0, "cdn", 1.0),
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
        val messages = listOf(
            Message("m1", "cdn", "user", "", 3, t(2)),
            Message("m2", "cdn", "assistant", "Added **12 points** in 2 new sections: **Basics**, **Edge servers**.", 0, t(2, 11)),
            Message("m3", "cdn", "user", "Subtitles from \"CDN caching explained\": cache hit ratio, TTL, purging by path…", 0, t(0, 8)),
            Message("m4", "cdn", "assistant", "Added **5 points**: 3 inline in **Edge servers**, 2 in a new section **Caching**.\n1 point skipped: already in your notes.", 0, t(0, 9)),
        )
        val schedule = listOf(
            Schedule("r1", "edge", d(0), 1, 0, d(-1)), Schedule("r2", "caching", d(0), 1, 0, d(-1)),
            Schedule("r3", "basics", d(2), 2, 1, d(0)), Schedule("r4", "gen", d(1), 1, 0, d(0)), Schedule("r5", "grid", d(1), 1, 0, d(0)),
            Schedule("r6", "edge", d(9), 4, 2, d(0)), Schedule("r7", "cc", d(8), 8, 3, d(0)),
            Schedule("r8", "shard", d(16), 16, 4, d(0)), Schedule("r9", "hc", d(32), 32, 5, d(0)),
        )
        return Snapshot(settings, sessions, sections, bullets, links, messages, schedule, rag, System.currentTimeMillis())
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

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(context) }

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
        // No media service in this harness: the screen must still render without a player connection.
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .declareComponentUnbindable(android.content.ComponentName(context, app.ewe.podcast.PlaybackService::class.java))
        show("8_revision_ready") { RevisionScreen(r, store, {}, {}) }
    }
}
