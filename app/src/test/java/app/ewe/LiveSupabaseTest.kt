package app.ewe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ewe.data.LocalPrefs
import app.ewe.data.ProviderKind
import app.ewe.data.Repository
import app.ewe.data.Supabase
import app.ewe.data.SyncState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end check of the app's data layer against a real Supabase project.
 * Skipped unless these are set: EWE_E2E_URL, EWE_E2E_KEY, EWE_E2E_EMAIL, EWE_E2E_PASSWORD,
 * EWE_E2E_AI_URL, EWE_E2E_AI_KEY (an OpenAI-compatible endpoint; a scripted stand-in is fine).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSupabaseTest {
    private fun env(n: String) = System.getenv(n).orEmpty()

    private suspend fun Repository.sendAndWait(sessionId: String, text: String) {
        send(sessionId, text, emptyList())
        withTimeout(120_000) { while (uploads.value[sessionId]?.error == null && uploads.value.containsKey(sessionId)) delay(200) }
        assertEquals("upload failed", null, uploads.value[sessionId]?.error)
    }

    private suspend fun Repository.rebuildAndWait() {
        startRebuild()
        withTimeout(120_000) { while (rebuild.value != null && rebuild.value?.error == null) delay(200) }
        assertEquals("rebuild failed", null, rebuild.value?.error)
    }

    @Test fun `whole flow against the live project`() = runBlocking {
        assumeTrue(env("EWE_E2E_URL").isNotEmpty())
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = LocalPrefs(context)
        val repo = Repository(context, prefs, Supabase(prefs))
        val tag = System.currentTimeMillis().toString().takeLast(6)

        // sign in → first sync creates the settings row
        assertEquals(null, repo.signIn(env("EWE_E2E_URL"), env("EWE_E2E_KEY"), env("EWE_E2E_EMAIL"), env("EWE_E2E_PASSWORD"), create = false))
        assertEquals(SyncState.Idle, repo.sync.value)
        assertTrue(repo.signedIn.value)
        assertEquals(64, repo.settings.intervalCapDays)

        prefs.compatibleUrl = env("EWE_E2E_AI_URL"); prefs.setApiKey(ProviderKind.COMPATIBLE, env("EWE_E2E_AI_KEY"))
        repo.updateSettings {
            put("notes_provider", "compatible"); put("notes_model", "mock-chat")
            put("placement_provider", "compatible"); put("placement_model", "mock-chat")
            put("keep_photo_copies", true); put("revisions_per_level", 2)
        }
        assertEquals("mock-chat", repo.settings.notesModel)
        assertTrue(repo.settings.keepPhotoCopies)

        // ── Notes: topic, first upload makes a section, second goes inline + new section ──
        val cdn = repo.createSession("CDN $tag", "topic")
        repo.sendAndWait(cdn, "ttl and purging")
        var s = repo.snapshot.value
        val caching = s.sectionsOf(cdn).single()
        assertEquals("Caching", caching.title)
        assertEquals(listOf("TTL = how long edge keeps a copy", "Purge by path"), s.points(caching.id).map { it.bullet.content })
        val msgs = s.messages.filter { it.sessionId == cdn }
        assertEquals(listOf("user", "assistant"), msgs.map { it.role })
        assertEquals("Added **2 points** in a new section: **Caching**.", msgs[1].content)
        assertEquals(repo.today().toString(), s.ownBullets(caching.id).first().dateAdded)
        assertEquals(1, s.schedule.count { it.sectionId == caching.id })

        repo.sendAndWait(cdn, "more")
        s = repo.snapshot.value
        assertEquals(listOf("TTL = how long edge keeps a copy", "Inline A", "Inline B", "Purge by path"), s.points(caching.id).map { it.bullet.content })
        assertEquals(listOf("Caching", "Edge servers"), s.sectionsOf(cdn).map { it.title })
        assertEquals("Added **3 points**: 2 inline in **Caching**, 1 in a new section **Edge servers**.\n1 point skipped: already in your notes.",
            s.messages.last { it.sessionId == cdn }.content)

        // edit + delete a point
        val inlineA = s.points(caching.id)[1].bullet
        repo.editBullet(inlineA.id, "Inline A (edited)")
        repo.deleteBullet(s.points(caching.id)[2].bullet.id)
        s = repo.snapshot.value
        assertEquals(listOf("TTL = how long edge keeps a copy", "Inline A (edited)", "Purge by path"), s.points(caching.id).map { it.bullet.content })

        // session rules
        repo.renameSession(cdn, "CDN $tag renamed"); assertEquals("CDN $tag renamed", repo.snapshot.value.session(cdn)!!.title)
        repo.setActive(cdn, false); assertFalse(repo.snapshot.value.session(cdn)!!.isActive)
        repo.setActive(cdn, true)

        // ── Linking: on → model → rebuild → ready ──
        repo.setLinking(true)
        repo.setEmbeddingModel("compatible", "mock-embed")
        var rag = repo.snapshot.value.rag
        assertEquals("compatible:mock-embed", rag.activeModel)
        assertTrue("rows missing before rebuild: $rag", rag.missingRows > 0 && !rag.ready)
        repo.rebuildAndWait()
        rag = repo.snapshot.value.rag
        assertTrue("ready after rebuild: $rag", rag.ready && rag.missingRows == 0)
        assertTrue(repo.hybridReady())

        // a project's point about caching lands in the CDN topic, shown in both places
        val sud = repo.createSession("Making a Sudoku $tag", "project")
        repo.sendAndWait(sud, "puzzle assets MARK:$tag")
        s = repo.snapshot.value
        val linked = s.points(caching.id).last()
        assertNotNull("linked into CDN › Caching", linked.link)
        assertEquals("Cache static puzzle assets $tag", linked.bullet.content)
        assertEquals(sud, s.section(linked.bullet.sectionId)!!.sessionId)
        assertTrue(s.messages.last { it.sessionId == sud }.content.startsWith("Also added to **CDN $tag renamed › Caching**"))
        assertTrue("index refreshed after linking: ${s.rag}", s.rag.ready)

        // search by meaning
        val hits = repo.searchByMeaning("cache")
        assertTrue("meaning hits: $hits", hits.any { it.sectionId == caching.id })

        // model change → paused → switch back → change again → rebuild all → swapped
        repo.setEmbeddingModel("compatible", "mock-embed-v2")
        assertTrue(repo.snapshot.value.rag.mismatch); assertFalse(repo.hybridReady())
        repo.switchBack()
        assertTrue(repo.snapshot.value.rag.ready)
        repo.setEmbeddingModel("compatible", "mock-embed-v2")
        repo.rebuildAndWait()
        rag = repo.snapshot.value.rag
        assertEquals("compatible:mock-embed-v2", rag.activeModel); assertTrue("$rag", rag.ready)

        // unlink: the point stays in its own session
        repo.unlink(linked.link!!.id)
        s = repo.snapshot.value
        assertTrue(s.points(caching.id).none { it.link != null })
        assertEquals(1, s.noteCount(sud))

        // ── Revision ──
        val row = s.schedule.first { it.sectionId == caching.id }
        assertEquals(1, row.intervalDays); assertEquals(repo.today().plusDays(1).toString(), row.nextDueDate)
        repo.markListened(listOf(row.id))
        var after = repo.snapshot.value.schedule.first { it.id == row.id }
        assertEquals(2, after.intervalDays); assertEquals(1, after.revisionsDone)
        assertEquals(repo.today().plusDays(2).toString(), after.nextDueDate)
        repo.restartRevision(cdn)
        after = repo.snapshot.value.schedule.first { it.id == row.id }
        assertEquals(1, after.intervalDays); assertEquals(0, after.revisionsDone); assertEquals(repo.today().toString(), after.nextDueDate)

        // recreate the project as a topic: notes and dates copied, old one inactive
        val sud2 = repo.recreate(sud)
        s = repo.snapshot.value
        assertEquals("topic", s.session(sud2)!!.type); assertEquals(sud, s.session(sud2)!!.recreatedFrom)
        assertFalse(s.session(sud)!!.isActive)
        assertEquals(1, s.noteCount(sud2))

        // the offline copy survives a restart
        val again = Repository(context, prefs, Supabase(prefs))
        assertEquals(s.sessions.size, again.snapshot.value.sessions.size)
        assertEquals(s.bullets.size, again.snapshot.value.bullets.size)

        repo.setLinking(false)
        repo.signOut()
        assertFalse(repo.signedIn.value)
    }
}
