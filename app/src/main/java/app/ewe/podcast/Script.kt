package app.ewe.podcast

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class EpisodeScript(val intro: String, val segments: List<String>, val outro: String)

/** The podcast prompt: segments with word budgets in, one script per segment out. */
object Script {
    val SYSTEM = """
# YOUR JOB
- Write a spoken revision podcast script from the user's study notes.
- You get a list of SEGMENTS. Each has: id, topic, section, level, word_budget, notes.
- Write one script per segment, in the given order, plus a short intro and outro.

# LENGTH (most important)
- Each segment's script must be close to its word_budget (within 10%).
- Never go over the budget to fit in more detail. Cut the less important points instead.
- The intro and the outro are about the number of words given in EPISODE.

# DETAIL BY LEVEL
- DETAILED: explain every point in plain words, add a simple example or analogy, and connect the points to each other.
- SHORTER: cover every point in one or two plain sentences each. No examples.
- KEY POINTS: only the most important facts, definitions and numbers. One short sentence each.
- HEADLINES: one sentence naming what the section covers, as a reminder only.

# SPOKEN STYLE
- It will be read aloud by text-to-speech while the listener is running.
- Use short sentences and a natural, friendly tone. Say "first", "next", "so".
- No markdown, bullets, symbols, code or URLs. Write "arrow" ideas as words ("which means", "so").
- Spell out abbreviations the first time they come up ("CDN, content delivery network").
- When the topic changes from the previous segment, start with one short line announcing it ("Now, HTTP caching.").

# DO NOT
- Do not add facts that are not in the notes.
- Do not mention days, levels, budgets or ids in the script.
""".trim()

    fun segmentId(index: Int) = "seg_${index + 1}"

    fun userMessage(plan: Plan, date: LocalDate): String = buildString {
        val each = Planner.INTRO_OUTRO_WORDS / 2
        appendLine("=== EPISODE ===")
        appendLine("Date: ${date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))} · Target: ${plan.targetMinutes} min · Intro + outro: $each words each")
        appendLine()
        appendLine("=== SEGMENTS (in order) ===")
        plan.segments.forEachIndexed { i, seg ->
            val level = seg.item.level.label.uppercase(Locale.ENGLISH)
            appendLine("[${segmentId(i)}] topic: ${seg.item.topic} · section: ${seg.item.section} · level: $level · word_budget: ${seg.wordBudget}")
            appendLine("notes:")
            seg.item.notes.forEach { appendLine("- $it") }
            appendLine()
        }
        append("=== END ===")
    }

    fun schema(plan: Plan): JsonObject = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("intro") { put("type", "string") }
            putJsonObject("segments") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object"); put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                            put("enum", buildJsonArray { plan.segments.indices.forEach { add(JsonPrimitive(segmentId(it))) } })
                        }
                        putJsonObject("script") { put("type", "string") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("id")); add(JsonPrimitive("script")) }
                }
            }
            putJsonObject("outro") { put("type", "string") }
        }
        putJsonArray("required") { add(JsonPrimitive("intro")); add(JsonPrimitive("segments")); add(JsonPrimitive("outro")) }
    }

    /** A segment the model skipped is read from the notes as they are, so nothing due is ever silent. */
    fun parse(out: JsonObject, plan: Plan): EpisodeScript {
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val byId = (out["segments"] as? kotlinx.serialization.json.JsonArray)?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }.associate { str(it, "id") to str(it, "script") }
        val segments = plan.segments.mapIndexed { i, seg -> byId[segmentId(i)]?.takeIf { it.isNotBlank() } ?: fallback(seg.item) }
        return EpisodeScript(
            str(out, "intro").ifBlank { "Here is your revision for today." },
            segments,
            str(out, "outro").ifBlank { "That is everything for today. Well done." },
        )
    }

    fun fallback(item: DueItem): String =
        "${item.topic}. ${item.section}. " + item.notes.joinToString(" ") { speakable(it).trimEnd('.') + "." }

    /** Make note shorthand readable aloud. */
    fun speakable(text: String): String = text
        .replace("→", " leads to ").replace("=>", " leads to ").replace("->", " leads to ")
        .replace(" = ", " is ").replace(" vs ", " versus ").replace("e.g.", "for example").replace("i.e.", "that is")
        .replace(Regex("[*_#`>|]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Text-to-speech engines cap the input length, so long scripts are split at sentence ends. */
    fun chunks(text: String, max: Int = 3000): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (sentence in clean.split(Regex("(?<=[.!?])\\s+"))) {
            var s = sentence
            while (s.length > max) { // a single runaway sentence
                val cut = s.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
                if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                out += s.substring(0, cut); s = s.substring(cut).trim()
            }
            if (cur.isNotEmpty() && cur.length + 1 + s.length > max) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
