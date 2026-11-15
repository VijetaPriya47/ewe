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

    @Test fun `crop handles - which one is touched, and dragging stays inside the photo`() {
        val m = app.ewe.data.CropMath
        val full = app.ewe.data.CropRect()
        val r = app.ewe.data.CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(app.ewe.data.CropHandle.TL, m.hit(r, 0.21f, 0.19f, 0.05f, 0.05f))
        assertEquals(app.ewe.data.CropHandle.R, m.hit(r, 0.82f, 0.5f, 0.05f, 0.05f))
        assertEquals(app.ewe.data.CropHandle.MOVE, m.hit(r, 0.5f, 0.5f, 0.05f, 0.05f))
        assertEquals(null, m.hit(r, 0.05f, 0.5f, 0.05f, 0.05f))
        assertEquals(app.ewe.data.CropHandle.BR, m.hit(full, 0.99f, 0.98f, 0.05f, 0.05f))
        // corners stop at the photo's edge and never make the area smaller than the minimum
        assertEquals(app.ewe.data.CropRect(0f, 0f, 0.8f, 0.8f), m.drag(r, app.ewe.data.CropHandle.TL, -0.5f, -0.5f))
        val small = m.drag(r, app.ewe.data.CropHandle.BR, -0.9f, -0.9f)
        assertEquals(0.2f + m.MIN, small.r, 1e-6f); assertEquals(0.2f + m.MIN, small.b, 1e-6f)
        assertEquals(app.ewe.data.CropRect(0.2f, 0.1f, 0.8f, 0.8f), m.drag(r, app.ewe.data.CropHandle.T, 0.3f, -0.1f))  // an edge moves one way only
        // moving keeps the size and stays inside
        val moved = m.drag(r, app.ewe.data.CropHandle.MOVE, 0.5f, -0.5f)
        assertEquals(0.4f, moved.l, 1e-5f); assertEquals(0f, moved.t, 1e-5f); assertEquals(1f, moved.r, 1e-5f); assertEquals(0.6f, moved.b, 1e-5f)
        assertTrue(full.isFull); assertFalse(r.isFull)
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

    @Test fun reorderSectionsMath() {
        val r = app.ewe.ui.Reorder
        // rows 100 px apart: half a row or more moves one place; never past either end
        assertEquals(0, r.target(0, 40f, 100f, 3))
        assertEquals(1, r.target(0, 60f, 100f, 3))
        assertEquals(2, r.target(0, 900f, 100f, 3))
        assertEquals(0, r.target(2, -250f, 100f, 3))
        assertEquals(listOf("b", "c", "a"), r.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), r.move(listOf("a", "b", "c"), 2, 0))
    }

    @Test fun nestingNeverSkipsALevel() {
        fun pt(level: Int, kind: String = "text") = app.ewe.data.Point(Bullet("x$level$kind", "s", "t", 0.0, "2026-11-14", "o", "m", kind = kind, level = level), 0.0, null)
        val n = app.ewe.data.Nesting
        // first point is always top level; each at most one deeper than the one before
        assertEquals(listOf(0, 1, 2, 2, 0), n.levels(listOf(pt(1), pt(1), pt(2), pt(2), pt(0))))
        assertEquals(listOf(0, 1, 0, 1), n.levels(listOf(pt(0), pt(2), pt(1, "mermaid"), pt(2))))
        assertEquals(listOf(0, 0), n.levels(listOf(pt(0), pt(-1))))
        assertEquals(0, n.maxLevel(listOf(0, 1), 0))
        assertEquals(2, n.maxLevel(listOf(0, 1, 2), 2))
        assertEquals(2, n.maxLevel(listOf(0, 2, 2), 2))
    }

    @Test fun figureBoxesFromTheFinder() {
        val f = app.ewe.data.FigureFinder
        val res = AppJson.parseToJsonElement("""{"figures":[
            {"page":2,"top":100,"left":200,"bottom":500,"right":800,"label":"Chart"},
            {"page":9,"top":0,"left":0,"bottom":10,"right":10,"label":"no such page"},
            {"page":1,"top":500,"left":0,"bottom":100,"right":10,"label":"upside down"},
            {"page":1,"top":"x","left":0,"bottom":10,"right":10,"label":"junk"}]}""").jsonObject
        val boxes = f.parse(res, 3)
        assertEquals(listOf("Chart"), boxes.map { it.label })
        val r = f.rect(boxes[0])
        assertEquals(0.185f, r.l, 1e-4f); assertEquals(0.085f, r.t, 1e-4f); assertEquals(0.815f, r.r, 1e-4f); assertEquals(0.515f, r.b, 1e-4f)
        // the margin never goes past the page
        val edge = f.rect(app.ewe.data.FigureFinder.Box(1, 0, 0, 1000, 1000, ""))
        assertEquals(app.ewe.data.CropRect(0f, 0f, 1f, 1f), edge)
        assertEquals(emptyList<Any>(), f.parse(AppJson.parseToJsonElement("{}").jsonObject, 3))
    }

    // ───────────── audio ─────────────
    @Test fun audioMath() {
        val m = app.ewe.data.AudioMath
        assertEquals(listOf<Short>(150, -100), m.downmix(shortArrayOf(100, 200, -100, -100), 2).toList())
        assertEquals(32767.toShort(), m.floatToShort(2f)); assertEquals((-32767).toShort(), m.floatToShort(-1f))
        // 48 kHz → 16 kHz in uneven buffers: a third as many samples, and a 440 Hz tone stays 440 Hz
        val r = app.ewe.data.AudioMath.Resampler(48_000, 16_000)
        val tone = ShortArray(48_000) { (8000 * kotlin.math.sin(2 * Math.PI * 440 * it / 48_000.0)).toInt().toShort() }
        val out = listOf(0..9_999, 10_000..10_000, 10_001..33_332, 33_333..47_999).flatMap { r.process(tone.sliceArray(it)).toList() }
        assertTrue("got ${out.size}", out.size in 15_998..16_002)
        val crossings = out.zipWithNext().count { (a, b) -> a < 0 && b >= 0 }
        assertTrue("crossings $crossings", crossings in 438..442)
        assertEquals(16_000, app.ewe.data.AudioMath.Resampler(16_000, 16_000).process(ShortArray(16_000)).size)
        // pieces → notes parts of about 20 minutes, empty ones left out
        val parts = m.notesParts(listOf(Triple(0, 600, "a"), Triple(600, 1200, "b"), Triple(1200, 1800, " "), Triple(1800, 1830, "c")), 1200)
        assertEquals(listOf(Triple(0, 1200, "a\n\nb"), Triple(1200, 1830, "c")), parts)
        assertEquals("1:05:09", app.ewe.data.Audio.clock(3909)); assertEquals("4:07", app.ewe.data.Audio.clock(247))
        assertEquals("52 min", app.ewe.data.Audio.length(3120)); assertEquals("1 h 05 min", app.ewe.data.Audio.length(3900))
    }

    private fun tool(name: String) = listOf("/usr/bin/$name", "/usr/local/bin/$name").firstOrNull { java.io.File(it).canExecute() }

    @Test fun audioFilesAreReadable() {
        val ffprobe = tool("ffprobe"); val ffmpeg = tool("ffmpeg")
        org.junit.Assume.assumeTrue("ffmpeg not installed", ffprobe != null && ffmpeg != null)
        val dir = kotlin.io.path.createTempDirectory("audio").toFile()
        // our WAV: ffprobe sees 16 kHz mono 16-bit, 2 seconds
        val wav = java.io.File(dir, "a.wav")
        val pcm = ShortArray(32_000) { (3000 * kotlin.math.sin(it / 10.0)).toInt().toShort() }
        val bytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array()
        wav.writeBytes(app.ewe.data.AudioMath.wavHeader(bytes.size, 16_000, 1) + bytes)
        fun probe(f: java.io.File) = ProcessBuilder(ffprobe, "-v", "error", "-show_entries", "stream=codec_name,sample_rate,channels:format=duration",
            "-of", "default=nw=1", f.path).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        val w = probe(wav)
        assertTrue(w, w.contains("codec_name=pcm_s16le") && w.contains("sample_rate=16000") && w.contains("channels=1") && w.contains("duration=2.0"))
        // our ADTS headers match the ones ffmpeg writes for 16 kHz mono AAC-LC, frame for frame
        val aac = java.io.File(dir, "ref.aac")
        ProcessBuilder(ffmpeg, "-v", "error", "-f", "lavfi", "-i", "sine=frequency=300:duration=2:sample_rate=16000", "-ac", "1",
            "-c:a", "aac", "-b:a", "32k", "-f", "adts", aac.path).start().waitFor()
        val ref = aac.readBytes()
        var at = 0; var frames = 0
        while (at + 7 <= ref.size) {
            val len = ((ref[at + 3].toInt() and 3) shl 11) or ((ref[at + 4].toInt() and 0xFF) shl 3) or ((ref[at + 5].toInt() and 0xFF) ushr 5)
            assertEquals("frame $frames", ref.copyOfRange(at, at + 7).toList(), app.ewe.data.AudioMath.adtsHeader(len - 7, 16_000, 1).toList())
            at += len; frames++
        }
        assertTrue(frames > 20 && at == ref.size)
        dir.deleteRecursively()
    }

    @Test fun `transcription speaks each wire format`(): Unit = runBlocking {
        val seen = ArrayList<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                return when (request.path) {
                    "/oai/audio/transcriptions" -> json("""{"text":" so um, CDN means servers near users "}""")
                    "/oai/models" -> json("""{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini-transcribe"},{"id":"whisper-1"}]}""")
                    "/gem/models/gemini-flash:generateContent" -> json("""{"candidates":[{"content":{"parts":[{"text":"hello "},{"text":"there"}]}}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val p = Providers(baseUrls = mapOf(ProviderKind.OPENAI to "$base/oai", ProviderKind.GEMINI to "$base/gem"))
        val file = kotlin.io.path.createTempFile("piece", ".m4a").toFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        assertEquals(listOf("gpt-4o-mini-transcribe", "whisper-1"), p.listModels(ProviderKind.OPENAI, "sk", null, ModelUse.TRANSCRIBE))
        assertEquals("so um, CDN means servers near users", p.transcribe(ProviderCfg(ProviderKind.OPENAI, "sk", "whisper-1", null), file, "audio/mp4", "CDN basics"))
        val form = seen.last().body.readUtf8()
        assertTrue(form.contains("name=\"model\"") && form.contains("whisper-1") && form.contains("name=\"prompt\"") && form.contains("CDN basics"))
        assertTrue(form.contains("filename=\"${file.name}\"") && form.contains("Content-Type: audio/mp4"))
        assertEquals("Bearer sk", seen.last().getHeader("Authorization"))

        assertEquals("hello there", p.transcribe(ProviderCfg(ProviderKind.GEMINI, "AIza", "gemini-flash", null), file, "audio/aac", ""))
        val gem = AppJson.parseToJsonElement(seen.last().body.readUtf8()).jsonObject
        val inline = gem["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[1].jsonObject["inlineData"]!!.jsonObject
        assertEquals("audio/aac", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AQIDBA==", inline["data"]!!.jsonPrimitive.content)

        val claude = runCatching { p.transcribe(ProviderCfg(ProviderKind.ANTHROPIC, "sk-ant", "claude", null), file, "audio/mp4", "") }
        assertTrue(claude.exceptionOrNull()?.message.orEmpty().contains("Claude can't transcribe"))
        server.shutdown(); file.delete()
        Unit
    }

    @Test fun figureZoomAndScroll() {
        val z = app.ewe.ui.Zoom
        val box = 1000f to 2000f
        val fitted = z.fit(2000f, 1000f, box.first, box.second)          // a wide figure on a tall screen
        assertEquals(1000f to 500f, fitted)
        // at normal size there is nothing to scroll
        assertEquals(app.ewe.ui.Zoom.View(), z.transform(app.ewe.ui.Zoom.View(), 0f, 0f, 300f, 300f, 1f, fitted, box))
        // double-tap on the right edge: zoomed in, that point stays under the finger, can't scroll past the edge
        val v = z.doubleTap(app.ewe.ui.Zoom.View(), 400f, 0f, fitted, box)
        assertEquals(2.5f, v.scale)
        assertEquals(-600f, v.x, 0.01f)                                   // 400 - 400 × 2.5
        // it is 2500 wide on a 1000 screen: up to 750 either way; 625 tall on 2000: no vertical scroll
        assertEquals(750f, z.transform(v, 0f, 0f, 5000f, 400f, 1f, fitted, box).x, 0.01f)
        assertEquals(0f, z.transform(v, 0f, 0f, 0f, 400f, 1f, fitted, box).y, 0.01f)
        // pinch: the point between the fingers stays put; never below 1× or above 8×
        val p = z.transform(app.ewe.ui.Zoom.View(2f, 100f, 0f), 50f, 0f, 0f, 0f, 1.5f, fitted, box)
        assertEquals(3f, p.scale); assertEquals(50f - (50f - 100f) * 1.5f, p.x, 0.01f)
        assertEquals(1f, z.transform(v, 0f, 0f, 0f, 0f, 0.01f, fitted, box).scale)
        assertEquals(8f, z.transform(v, 0f, 0f, 0f, 0f, 100f, fitted, box).scale)
        // double-tap again: whole figure
        assertEquals(app.ewe.ui.Zoom.View(), z.doubleTap(v, 0f, 0f, fitted, box))
    }

    // ───────────── Gnani text-to-speech ─────────────
    @Test fun `gnani speaks its wire format and reports errors`(): Unit = runBlocking {
        val seen = ArrayList<RecordedRequest>()
        var busy = 1
        val audio = okio.Buffer().write(ByteArray(400) { it.toByte() })
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val text = request.body.clone().readUtf8()
                return when {
                    request.getHeader("X-API-Key-ID") == "bad" -> MockResponse().setResponseCode(401).setBody("""{"detail":{"error_code":"INVALID_API_KEY","message":"Unauthorized","status_code":401}}""")
                    request.getHeader("X-API-Key-ID") == "broke" -> MockResponse().setResponseCode(402).setBody("{}")
                    text.contains("\"voice\":\"Nope\"") -> MockResponse().setResponseCode(400).setBody("""{"success":false,"message":"Voice 'Nope' is not available for model timbre-v2.5","status_code":400}""")
                    text.contains("busy-once") && busy-- > 0 -> MockResponse().setResponseCode(429).setBody("""{"detail":{"error_code":"RATE_LIMITED","message":"Only 1 active request","status_code":429}}""")
                    text.contains("always-busy") -> MockResponse().setResponseCode(429).setBody("{}")
                    else -> MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(audio.clone())
                }
            }
        }
        server.start()
        val g = app.ewe.ai.GnaniTts(baseUrl = server.url("/").toString().trimEnd('/'), pause = {})
        val cfg = app.ewe.ai.GnaniCfg("k-123", "timbre-v2.5", "Kaveri", "en-IN")

        assertEquals(400, g.synthesize(cfg, "Hello there").size)
        val r = seen.last()
        assertEquals("/api/v1/tts/inference", r.path); assertEquals("POST", r.method); assertEquals("k-123", r.getHeader("X-API-Key-ID"))
        val body = AppJson.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("Hello there", body["text"]!!.jsonPrimitive.content)
        assertEquals("Kaveri", body["voice"]!!.jsonPrimitive.content); assertEquals("timbre-v2.5", body["model"]!!.jsonPrimitive.content)
        assertEquals("en-IN", body["language"]!!.jsonPrimitive.content); assertEquals("1.0", body["speed"]!!.jsonPrimitive.content)
        val ac = body["audio_config"]!!.jsonObject
        assertEquals("mp3", ac["container"]!!.jsonPrimitive.content); assertEquals("64k", ac["bitrate"]!!.jsonPrimitive.content)
        assertEquals("24000", ac["sample_rate"]!!.jsonPrimitive.content); assertEquals("1", ac["num_channels"]!!.jsonPrimitive.content)

        suspend fun fail(c: app.ewe.ai.GnaniCfg, text: String) = runCatching { g.synthesize(c, text) }.exceptionOrNull()!!.message.orEmpty()
        assertTrue(fail(cfg.copy(apiKey = "bad"), "hi").contains("rejected the API key"))
        assertTrue(fail(cfg.copy(apiKey = "broke"), "hi").contains("out of credit"))
        assertTrue(fail(cfg.copy(voice = "Nope"), "hi").contains("Voice 'Nope' is not available"))
        val before = seen.size
        assertEquals(400, g.synthesize(cfg, "busy-once please").size)          // one 429, then success
        assertEquals(2, seen.size - before)
        val before2 = seen.size
        assertTrue(fail(cfg, "always-busy").contains("only one request can run at a time"))
        assertEquals(3, seen.size - before2)                                    // gave up after three tries

        // long text → one request per chunk, files in order
        val dir = kotlin.io.path.createTempDirectory("gnani").toFile()
        val long = (1..120).joinToString(" ") { "Sentence number $it is here." }
        val files = g.synthesizeFiles(cfg, long, dir, "seg0")
        assertTrue(files.size > 3 && files.all { it.length() == 400L && it.extension == "mp3" })
        assertEquals((0 until files.size).map { "seg0_$it.mp3" }, files.map { it.name })
        dir.deleteRecursively(); server.shutdown()
    }

    @Test fun gnaniVoiceCatalog() {
        val g = app.ewe.ai.GnaniTts
        assertEquals(42, g.V25.size); assertEquals(13, g.V30.size)
        assertEquals(42, g.V25.map { it.name }.distinct().size)
        // every v2.5 voice has a language the API accepts, and a gender to filter by
        val codes = g.LANGUAGES.map { it.first }.toSet()
        assertTrue(g.V25.all { it.language in codes && it.gender in setOf("Female", "Male") })
        assertEquals(setOf("Female", "Male"), g.V30.map { it.gender }.toSet())
        assertEquals(7, g.V30.count { it.gender == "Female" }); assertEquals(6, g.V30.count { it.gender == "Male" })
        // a voice only belongs to its own model
        assertTrue(g.voices("timbre-v2.5").none { it.name == "Shivani" } && g.voices("timbre-v3.0").none { it.name == "Kaveri" })
        assertEquals("Kaveri", g.defaultVoice("timbre-v2.5").name); assertEquals("Shivani", g.defaultVoice("timbre-v3.0").name)
        assertEquals("hi-IN", g.languageFor("timbre-v2.5", "Nalini", "en-IN"))      // v2.5: the voice's own language
        assertEquals("ta-IN", g.languageFor("timbre-v3.0", "Shivani", "ta-IN"))     // v3.0: as chosen
        assertEquals("Hindi", g.languageName("hi-IN"))
        assertTrue(g.describe(500, """{"success":false,"message":"We are facing technical difficulties.","status_code":500}""".toByteArray()).contains("technical difficulties"))
    }

    @Test fun `gnani falls back to the next key`(): Unit = runBlocking {
        val seen = ArrayList<String>()
        val audio = okio.Buffer().write(ByteArray(400))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = request.getHeader("X-API-Key-ID").orEmpty(); seen += key
                return when (key) {
                    "fb-empty" -> MockResponse().setResponseCode(402).setBody("{}")
                    "fb-bad" -> MockResponse().setResponseCode(401).setBody("{}")
                    "fb-good" -> MockResponse().setBody(audio.clone())
                    else -> MockResponse().setResponseCode(400).setBody("""{"success":false,"message":"bad voice","status_code":400}""")
                }
            }
        }
        server.start()
        val g = app.ewe.ai.GnaniTts(baseUrl = server.url("/").toString().trimEnd('/'), pause = {})
        val cfg = app.ewe.ai.GnaniCfg("fb-empty", "timbre-v2.5", "Kaveri", "en-IN", listOf("fb-bad", "fb-good"))
        assertEquals(400, g.synthesize(cfg, "one").size)
        assertEquals(listOf("fb-empty", "fb-bad", "fb-good"), seen)
        seen.clear()
        assertEquals(400, g.synthesize(cfg, "two").size)                  // dead keys are not tried again
        assertEquals(listOf("fb-good"), seen)
        // a problem that isn't the key's (bad voice) stops at once instead of burning through the keys
        seen.clear()
        val other = app.ewe.ai.GnaniCfg("fb-other1", "timbre-v2.5", "Nope", "en-IN", listOf("fb-other2"))
        assertTrue(runCatching { g.synthesize(other, "x") }.exceptionOrNull()!!.message.orEmpty().contains("bad voice"))
        assertEquals(listOf("fb-other1"), seen)
        // all keys dead: the last error is reported
        val dead = app.ewe.ai.GnaniCfg("fb-empty", "timbre-v2.5", "Kaveri", "en-IN", listOf("fb-bad"))
        assertTrue(runCatching { g.synthesize(dead, "y") }.exceptionOrNull()!!.message.orEmpty().isNotEmpty())
        // pasting a whole table finds every key
        val table = "| 1 | `vach_aB1_c-d` |\n| 2 | `vach_Z9y8` |\n| 3 | `vach_aB1_c-d` |"
        assertEquals(listOf("vach_aB1_c-d", "vach_Z9y8"), app.ewe.ai.GnaniTts.parseKeys(table))
        server.shutdown()
    }
}
