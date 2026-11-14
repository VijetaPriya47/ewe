package app.ewe

import app.ewe.ai.ModelUse
import app.ewe.ai.Providers
import app.ewe.data.AppJson
import app.ewe.data.Bullet
import app.ewe.data.BulletLink
import app.ewe.data.ProviderCfg
import app.ewe.data.ProviderKind
import app.ewe.data.Schedule
import app.ewe.data.Search
import app.ewe.data.Section
import app.ewe.data.Session
import app.ewe.data.Snapshot
import app.ewe.podcast.DueItem
import app.ewe.podcast.Level
import app.ewe.podcast.Planner
import app.ewe.podcast.Script
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    private fun item(id: String, level: Level, words: Int, topic: String = "T", rev: Int = 1) =
        DueItem(id, topic, topic, "S$id", 0.0, "2026-11-01", 1, rev, level, listOf((1..words).joinToString(" ") { "w" }))

    // ───────────── detail ladder ─────────────
    @Test fun `two revisions per level`() {
        assertEquals(listOf(Level.DETAILED, Level.DETAILED, Level.SHORTER, Level.SHORTER, Level.KEY_POINTS, Level.KEY_POINTS, Level.HEADLINES, Level.HEADLINES),
            (1..8).map { Level.of(it, 2) })
        assertEquals(Level.HEADLINES, Level.of(4, 1))
    }

    @Test fun `ladder labels match the spec table`() {
        assertEquals(listOf("Day 1, 2", "Day 4, 8", "Day 16, 32", "Day 64+"), Planner.ladder(64, 2).map { it.second })
        assertEquals(listOf("Day 1, 2", "Day 4, 8", "Day 8", "Day 8"), Planner.ladder(8, 2).map { it.second })
    }

    // ───────────── fitting into 30 minutes ─────────────
    @Test fun `light day uses ideal lengths`() {
        val plan = Planner.plan(listOf(item("a", Level.DETAILED, 100), item("b", Level.SHORTER, 100), item("c", Level.HEADLINES, 100)), 30)
        assertEquals(listOf(150, 80, 15), plan.segments.map { it.wordBudget })
        assertTrue(plan.deferred.isEmpty())
        assertEquals(3, plan.minutes) // (245 + 150) / 150 → 3 min: a short episode
    }

    @Test fun `heavy day shrinks the least important level first`() {
        // budget 4350. ideals: detailed 3000, shorter 1600, key 400 → 5000 (650 over)
        val items = listOf(item("d", Level.DETAILED, 2000), item("s", Level.SHORTER, 2000), item("k", Level.KEY_POINTS, 1000))
        val plan = Planner.plan(items, 30)
        val by = plan.segments.associate { it.item.scheduleId to it.wordBudget }
        assertEquals(3000, by["d"])            // untouched
        assertEquals(200, by["k"])             // key points at its minimum (0.2×)
        assertEquals(1150, by["s"])            // shorter gives up the remaining 450
        assertEquals(4350, plan.segments.sumOf { it.wordBudget })
        assertEquals(30, plan.minutes)
    }

    @Test fun `still too long moves lowest-detail sections to tomorrow`() {
        val items = listOf(item("d", Level.DETAILED, 5000), item("h1", Level.HEADLINES, 50), item("k", Level.KEY_POINTS, 3000))
        val plan = Planner.plan(items, 30)   // minimums: 4000 + 10 + 600 > 4350
        assertEquals(listOf("h1", "k"), plan.deferred.map { it.scheduleId }) // headlines first, then key points
        assertTrue(plan.segments.sumOf { it.wordBudget } <= 4350)
    }

    @Test fun `topics with detailed sections come first and stay together`() {
        val items = listOf(item("1", Level.HEADLINES, 50, "Zed"), item("2", Level.SHORTER, 50, "Alpha"), item("3", Level.DETAILED, 50, "Zed"), item("4", Level.KEY_POINTS, 50, "Alpha"))
        assertEquals(listOf("3", "1", "2", "4"), Planner.plan(items, 30).segments.map { it.item.scheduleId })
    }

    private val snap = Snapshot(
        sessions = listOf(Session("cdn", "CDN", "topic"), Session("sud", "Making a Sudoku", "project"), Session("old", "Old", "topic", isActive = false)),
        sections = listOf(Section("c1", "cdn", "Caching", 1.0), Section("s1", "sud", "Grid rules", 1.0), Section("o1", "old", "Stuff", 1.0)),
        bullets = listOf(
            Bullet("b1", "c1", "TTL = how long edge keeps a copy", 1.0, "2026-11-08", "cdn"),
            Bullet("b2", "c1", "Purge by path", 2.0, "2026-11-10", "cdn"),
            Bullet("b3", "s1", "Cache only static puzzle assets", 1.0, "2026-11-09", "sud"),
            Bullet("b4", "o1", "Old fact", 1.0, "2026-11-01", "old"),
        ),
        links = listOf(BulletLink("l1", "b3", "c1", 3.0)),
        schedule = listOf(
            Schedule("r1", "c1", "2026-11-08", 2, 1, "2026-11-10"),
            Schedule("r2", "c1", "2026-11-10", 1, 0, "2026-11-11"),
            Schedule("r3", "s1", "2026-11-09", 1, 0, "2026-11-10"),
            Schedule("r4", "o1", "2026-11-01", 4, 2, "2026-11-05"),
        ),
    )

    @Test fun `due means on or before today, active sessions only, one batch per date`() {
        val due = Planner.due(snap, LocalDate.parse("2026-11-10"), 2)
        assertEquals(setOf("r1", "r3"), due.map { it.scheduleId }.toSet())
        val r1 = due.first { it.scheduleId == "r1" }
        assertEquals(listOf("TTL = how long edge keeps a copy"), r1.notes) // only that day's batch, not the linked point
        assertEquals(2, r1.revisionNo); assertEquals(Level.DETAILED, r1.level)
        assertEquals(3, Planner.due(snap, LocalDate.parse("2026-11-12"), 2).size) // skipped days carry over
    }

    @Test fun `references - chips, editing as numbers, speech without marks`() {
        val r1 = app.ewe.data.Reference("aa-1", "cdn", 1, "https://web.dev/a", "web.dev")
        val r4 = app.ewe.data.Reference("dd-4", "cdn", 4, "https://mdn.org/b", "MDN")
        val s = snap.copy(references = listOf(r1, r4),
            bullets = snap.bullets + Bullet("cite", "c1", "TTL keeps a copy [[ref:aa-1]][[ref:dd-4]] and [[ref:ee-9]]", 9.0, "2026-11-08", "cdn"))
        val text = s.bullet("cite")!!.content
        assertEquals(listOf("aa-1", "dd-4", "ee-9"), app.ewe.data.Rich.refIds(text))
        assertEquals("TTL keeps a copy and", app.ewe.data.Rich.stripRefs(text))
        assertEquals("TTL keeps a copy [1][4] and [?]", app.ewe.data.Rich.refsToShown(text, s))
        assertEquals("TTL keeps a copy [[ref:aa-1]][[ref:dd-4]] and", app.ewe.data.Rich.shownToRefs("TTL keeps a copy [1][4] and [?]", s.referencesOf("cdn")))
        assertEquals("see [7]", app.ewe.data.Rich.shownToRefs("see [7]", s.referencesOf("cdn")))   // no reference 7: left as typed
        assertEquals(listOf("TTL keeps a copy " to null, "" to "aa-1", "" to "dd-4", " and " to null, "" to "ee-9"), app.ewe.data.Rich.pieces(text))
        assertEquals(1, s.citations["aa-1"]); assertEquals(null, s.citations["zz"])
        assertEquals(listOf(1, 4), s.referencesOf("cdn").map { it.number })
        // the podcast and read-aloud never say the marks
        assertTrue(app.ewe.podcast.NotesReader.lines(s, "cdn").none { "ref:" in it.text })
    }

    @Test fun `typing reference or summary first picks the mode`() {
        assertEquals("reference", app.ewe.ui.ModeWord.of("Reference: https://web.dev"))
        assertEquals("summary", app.ewe.ui.ModeWord.of("  summary\nmy own notes"))
        assertEquals(null, app.ewe.ui.ModeWord.of("Summary of chapter 3".replace("Summary", "Summaries")))
        assertEquals(null, app.ewe.ui.ModeWord.of("a reference to x"))
        assertEquals("https://web.dev", app.ewe.ui.ModeWord.strip("reference: https://web.dev", "reference"))
        assertEquals("reference: x", app.ewe.ui.ModeWord.strip("reference: x", "notes"))   // chip changed back: keep the word
    }

    @Test fun `recall map lists section names only`() {
        assertEquals("mindmap\n  root((CDN Ch 4))\n    Basics\n    Edge servers PoP\n    Caching", app.ewe.ui.recallMap("CDN (Ch 4)", listOf("Basics", "Edge servers [PoP]", "", "Caching", "Basics")))
    }

    @Test fun `tables are read row by row, code is skipped`() {
        val md = "| Level | Clues | Time |\n| --- | :---: | ---: |\n| Easy | 36 | 5 min |\n| Hard | 22 | |"
        val t = app.ewe.data.Rich.parseTable(md)!!
        assertEquals(listOf("Level", "Clues", "Time"), t.header)
        assertEquals(listOf(listOf("Easy", "36", "5 min"), listOf("Hard", "22", "")), t.rows)
        assertEquals("Easy: Clues 36, Time 5 min. Hard: Clues 22.", app.ewe.data.Rich.tableAsText(md))
        assertEquals(null, app.ewe.data.Rich.parseTable("just | one line"))
        assertEquals(listOf("a", "b|c"), app.ewe.data.Rich.parseTable("| a | b\\|c |\n|---|---|")!!.header)

        val rich = snap.copy(bullets = snap.bullets +
            Bullet("t1", "c1", md, 1.6, "2026-11-08", "cdn", kind = "table") +
            Bullet("k1", "c1", "curl -I https://cdn.example", 1.7, "2026-11-08", "cdn", kind = "code", lang = "bash"))
        val notes = Planner.due(rich, LocalDate.parse("2026-11-10"), 2).first { it.scheduleId == "r1" }.notes
        assertEquals(listOf("TTL = how long edge keeps a copy", "Easy: Clues 36, Time 5 min. Hard: Clues 22."), notes)
        val lines = app.ewe.podcast.NotesReader.lines(rich, "cdn").map { it.key }
        assertTrue("c1t1" in lines && "c1k1" !in lines)
    }

    @Test fun `PDF pages are split into messages of ten`() {
        assertEquals(listOf(1..10, 11..20, 21..23), app.ewe.data.Pdf.chunks(1, 23))
        assertEquals(listOf(5..5), app.ewe.data.Pdf.chunks(5, 5))
        assertEquals("pages 11–20", app.ewe.data.Pdf.pagesLabel(11, 20))
        assertEquals("page 7", app.ewe.data.Pdf.pagesLabel(7, 7))
    }

    @Test fun `plain episode without AI fades detail and announces topics`() {
        val items = listOf(
            DueItem("a", "cdn", "CDN", "Basics", 1.0, "2026-11-01", 1, 1, Level.DETAILED, listOf("CDN = servers near users", "Lower latency")),
            DueItem("b", "cdn", "CDN", "Edge", 2.0, "2026-11-01", 1, 7, Level.HEADLINES, listOf("one two three four five six seven eight nine ten", "eleven twelve", "never read again ok")),
            DueItem("c", "http", "HTTP Caching", "Headers", 1.0, "2026-11-01", 1, 3, Level.SHORTER, listOf("max-age → freshness")),
        )
        val plan = Planner.plan(items, 30)
        val s = Script.plain(plan)
        assertEquals(3, s.segments.size)
        assertTrue(s.intro.contains("3 sections"))
        assertEquals("Now, CDN. Basics. CDN is servers near users. Lower latency.", s.segments[0])
        assertTrue(s.segments[1].startsWith("Edge. one two") && !s.segments[1].contains("Now,") && !s.segments[1].contains("never read"))
        assertEquals("Now, HTTP Caching. Headers. max-age leads to freshness.", s.segments[2])
    }

    @Test fun `diagrams stay out of the podcast and the read-aloud`() {
        val withDiagram = snap.copy(bullets = snap.bullets + Bullet("d1", "c1", "flowchart TD\n  A --> B", 1.5, "2026-11-08", "cdn", kind = "mermaid"))
        val r1 = Planner.due(withDiagram, LocalDate.parse("2026-11-10"), 2).first { it.scheduleId == "r1" }
        assertEquals(listOf("TTL = how long edge keeps a copy"), r1.notes)
        val lines = app.ewe.podcast.NotesReader.lines(withDiagram, "cdn")
        assertEquals(listOf("h:c1", "c1b1", "c1b2", "c1b3"), lines.map { it.key })
        assertEquals("Caching.", lines[0].text)
        assertEquals("TTL is how long edge keeps a copy.", lines[1].text)
        assertTrue(lines.none { it.text.contains("flowchart") })
        assertEquals(1, app.ewe.podcast.NotesReader.minutes(lines))
        assertTrue(app.ewe.podcast.NotesReader.lines(withDiagram, "nope").isEmpty())
    }

    @Test fun `snapshot shows a linked point in both places and survives the offline cache`() {
        assertEquals(listOf("b1", "b2", "b3"), snap.points("c1").map { it.bullet.id })
        assertEquals("l1", snap.points("c1").last().link?.id)
        assertEquals(3, snap.noteCount("cdn")); assertEquals(1, snap.noteCount("sud"))
        val again = AppJson.decodeFromString(Snapshot.serializer(), AppJson.encodeToString(Snapshot.serializer(), snap))
        assertEquals(snap, again)
        assertEquals(3, again.points("c1").size)
    }

    // ───────────── keyword search ─────────────
    @Test fun `search finds sessions, notes and linked notes`() {
        val r = Search.run(snap, "sudoku")
        assertEquals(listOf("sud"), r.sessions.map { it.id })
        // its own note, and the copy shown in CDN "from Making a Sudoku"
        assertEquals(setOf("sud" to "b3", "cdn" to "b3"), r.notes.map { it.session.id to it.point.bullet.id }.toSet())
        assertEquals(listOf("b1"), Search.run(snap, "ttl edge").notes.map { it.point.bullet.id })
        assertEquals(listOf("old"), Search.run(snap, "old").sessions.map { it.id }) // inactive stays searchable
        assertTrue(Search.run(snap, "  ").notes.isEmpty())
    }

    // ───────────── podcast prompt ─────────────
    @Test fun `script prompt lists segments with budgets and parses the answer`() {
        val plan = Planner.plan(Planner.due(snap, LocalDate.parse("2026-11-10"), 2), 30)
        val msg = Script.userMessage(plan, LocalDate.parse("2026-11-10"))
        assertTrue(msg.contains("Date: Tue 10 Nov · Target: 30 min · Intro + outro: 75 words each"))
        assertTrue(msg.contains("[seg_1] topic: CDN · section: Caching · level: DETAILED · word_budget:"))
        assertTrue(Script.schema(plan).toString().contains("\"enum\":[\"seg_1\",\"seg_2\"]"))

        val out = AppJson.parseToJsonElement("""{"intro":"Hi","segments":[{"id":"seg_2","script":"Two."}],"outro":""}""").jsonObject
        val script = Script.parse(out, plan)
        assertEquals("Two.", script.segments[1])
        assertTrue(script.segments[0].startsWith("CDN. Caching. TTL is how long")) // skipped segment is read from the notes
        assertTrue(script.outro.isNotBlank())
    }

    @Test fun `long scripts are split at sentence ends`() {
        val text = (1..400).joinToString(" ") { "Sentence number $it is here." }
        val chunks = Script.chunks(text, 1000)
        assertTrue(chunks.size > 5 && chunks.all { it.length <= 1000 && it.endsWith(".") })
        assertEquals(text, chunks.joinToString(" "))
        assertEquals("CDN leads to faster", Script.speakable("CDN → faster"))
    }

    // ───────────── provider adapters, against a local stand-in for each API ─────────────
    @Test fun `providers speak each wire format`() = runBlocking {
        val seen = ArrayList<RecordedRequest>()
        val answer = """{"intro":"a","segments":[],"outro":"b"}"""
        val quoted = AppJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(answer))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path.orEmpty()
                fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                return when {
                    path == "/oai/models" -> json("""{"data":[{"id":"gpt-b"},{"id":"text-embedding-3-small"},{"id":"gpt-a"}]}""")
                    path == "/oai/chat/completions" -> json("""{"choices":[{"message":{"content":$quoted}}]}""")
                    path == "/weak/chat/completions" ->
                        if (request.body.clone().readUtf8().contains("json_schema")) MockResponse().setResponseCode(400).setBody("""{"error":{"message":"no json_schema"}}""")
                        else json("""{"choices":[{"message":{"content":$quoted}}]}""")
                    path.startsWith("/gem/models?") -> json("""{"models":[{"name":"models/gemini-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-embedding-001","supportedGenerationMethods":["embedContent"]}]}""")
                    path == "/gem/models/gemini-flash:generateContent" -> json("""{"candidates":[{"content":{"parts":[{"text":$quoted}]}}]}""")
                    path.startsWith("/v1/models") -> json("""{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","created_at":"2026-01-01T00:00:00Z"}],"has_more":false,"first_id":"claude-opus-5-5","last_id":"claude-opus-5-5"}""")
                    path.startsWith("/v1/messages") -> {
                        fun ev(type: String, data: String) = "event: $type\ndata: {\"type\":\"$type\"$data}\n\n"
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            ev("message_start", ""","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""") +
                            ev("content_block_start", ""","index":0,"content_block":{"type":"text","text":""}""") +
                            ev("content_block_delta", ""","index":0,"delta":{"type":"text_delta","text":$quoted}""") +
                            ev("content_block_stop", ""","index":0""") +
                            ev("message_delta", ""","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}""") +
                            ev("message_stop", ""))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val p = Providers(baseUrls = mapOf(ProviderKind.OPENAI to "$base/oai", ProviderKind.GEMINI to "$base/gem", ProviderKind.ANTHROPIC to base))
        val schema = buildJsonObject { put("type", "object"); put("additionalProperties", false) }

        assertEquals(listOf("gpt-a", "gpt-b"), p.listModels(ProviderKind.OPENAI, "sk-x", null, ModelUse.CHAT))
        assertEquals(listOf("text-embedding-3-small"), p.listModels(ProviderKind.OPENAI, "sk-x", null, ModelUse.EMBEDDING))
        assertEquals("Bearer sk-x", seen.last().getHeader("Authorization"))
        assertEquals(listOf("gemini-flash"), p.listModels(ProviderKind.GEMINI, "AIza", null, ModelUse.CHAT))
        assertEquals(listOf("gemini-embedding-001"), p.listModels(ProviderKind.GEMINI, "AIza", null, ModelUse.EMBEDDING))
        assertEquals("AIza", seen.last().getHeader("x-goog-api-key"))
        assertEquals(listOf("claude-opus-5-5"), p.listModels(ProviderKind.ANTHROPIC, "sk-ant", null, ModelUse.CHAT))
        assertTrue(p.listModels(ProviderKind.ANTHROPIC, "sk-ant", null, ModelUse.EMBEDDING).isEmpty())

        val oai = p.generateJson(ProviderCfg(ProviderKind.OPENAI, "sk-x", "gpt-a", null), "sys", "user", schema, "episode")
        assertEquals("a", oai["intro"]!!.jsonPrimitive.content)
        val oaiBody = AppJson.parseToJsonElement(seen.last().body.readUtf8()).jsonObject
        assertEquals("json_schema", oaiBody["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val weak = p.generateJson(ProviderCfg(ProviderKind.COMPATIBLE, "", "llama", "$base/weak"), "sys", "user", schema, "episode")
        assertEquals("b", weak["outro"]!!.jsonPrimitive.content)
        assertTrue(seen.last().body.readUtf8().contains("OUTPUT FORMAT")) // fell back to JSON mode + schema in the prompt

        val gem = p.generateJson(ProviderCfg(ProviderKind.GEMINI, "AIza", "gemini-flash", null), "sys", "user", schema, "episode")
        assertEquals("a", gem["intro"]!!.jsonPrimitive.content)
        val gemBody = AppJson.parseToJsonElement(seen.last().body.readUtf8()).jsonObject
        assertFalse(gemBody["generationConfig"]!!.jsonObject["responseSchema"]!!.jsonObject.containsKey("additionalProperties"))

        val claude = p.generateJson(ProviderCfg(ProviderKind.ANTHROPIC, "sk-ant", "claude-opus-5-5", null), "sys", "user", schema, "episode")
        assertEquals("b", claude["outro"]!!.jsonPrimitive.content)
        val claudeBody = AppJson.parseToJsonElement(seen.last().body.readUtf8()).jsonObject
        assertEquals("json_schema", claudeBody["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sys", claudeBody["system"]!!.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content })
        server.shutdown()
    }
}
