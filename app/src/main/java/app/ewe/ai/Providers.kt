package app.ewe.ai

import app.ewe.data.ApiException
import app.ewe.data.AppJson
import app.ewe.data.ProviderCfg
import app.ewe.data.ProviderKind
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

enum class ModelUse { CHAT, EMBEDDING }

/**
 * AI calls made straight from the phone: listing a provider's models and writing the
 * daily podcast script. (Notes and linking go through the edge functions.)
 * Three adapters: OpenAI-style, Claude, Gemini.
 */
class Providers(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).callTimeout(600, TimeUnit.SECONDS).build(),
    /** Overridable so tests can point each provider at a local server. */
    private val baseUrls: Map<ProviderKind, String> = emptyMap(),
) {
    private val jsonType = "application/json".toMediaType()

    private fun base(kind: ProviderKind, custom: String?): String = (baseUrls[kind] ?: when (kind) {
        ProviderKind.OPENAI -> "https://api.openai.com/v1"
        ProviderKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        ProviderKind.COMPATIBLE -> custom.orEmpty()
        ProviderKind.ANTHROPIC -> ""
    }).trimEnd('/')

    private fun claude(key: String): AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(key).timeout(Duration.ofMinutes(10)).maxRetries(2)
        .apply { baseUrls[ProviderKind.ANTHROPIC]?.let { baseUrl(it) } }
        .build()

    // ───────────── model lists, fetched with the user's key ─────────────
    suspend fun listModels(kind: ProviderKind, apiKey: String, baseUrl: String?, use: ModelUse): List<String> =
        withContext(Dispatchers.IO) {
            when (kind) {
                ProviderKind.ANTHROPIC -> {
                    if (use == ModelUse.EMBEDDING) return@withContext emptyList()
                    claudeErrors { claude(apiKey).models().list().autoPager().map { it.id() }.toList() }
                }
                ProviderKind.GEMINI -> {
                    val want = if (use == ModelUse.EMBEDDING) "embedContent" else "generateContent"
                    val o = get("${base(kind, baseUrl)}/models?pageSize=1000", "x-goog-api-key" to apiKey)
                    o["models"]?.jsonArray.orEmpty().map { it.jsonObject }
                        .filter { m -> m["supportedGenerationMethods"]?.jsonArray.orEmpty().any { it.jsonPrimitive.content == want } }
                        .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") }
                }
                else -> {
                    val o = get("${base(kind, baseUrl)}/models", "Authorization" to "Bearer $apiKey")
                    val all = o["data"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.sorted()
                    val embed = all.filter { it.contains("embed", ignoreCase = true) }
                    when {
                        use == ModelUse.EMBEDDING -> embed.ifEmpty { all }
                        else -> all.filterNot { it in embed }
                    }
                }
            }
        }

    // ───────────── one text call that returns JSON matching a schema ─────────────
    suspend fun generateJson(cfg: ProviderCfg, system: String, user: String, schema: JsonObject, schemaName: String): JsonObject =
        withContext(Dispatchers.IO) {
            val text = when (cfg.kind) {
                ProviderKind.ANTHROPIC -> claudeJson(cfg, system, user, schema)
                ProviderKind.GEMINI -> geminiJson(cfg, system, user, schema)
                else -> openAiJson(cfg, system, user, schema, schemaName)
            }
            parseJson(text)
        }

    private fun openAiJson(cfg: ProviderCfg, system: String, user: String, schema: JsonObject, name: String): String {
        fun body(strict: Boolean) = buildJsonObject {
            put("model", cfg.model)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", if (strict) system else system + schemaHint(schema)) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
            putJsonObject("response_format") {
                if (strict) {
                    put("type", "json_schema")
                    putJsonObject("json_schema") { put("name", name); put("strict", true); put("schema", schema) }
                } else put("type", "json_object")
            }
        }
        val url = "${base(cfg.kind, cfg.baseUrl)}/chat/completions"
        val auth = "Authorization" to "Bearer ${cfg.apiKey}"
        val res = try {
            post(url, body(true), auth)
        } catch (e: ApiException) {
            // Some OpenAI-compatible servers only support plain JSON mode.
            if (e.status != 400 && e.status != 422) throw e
            post(url, body(false), auth)
        }
        val msg = res["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        (msg?.get("refusal") as? JsonPrimitive)?.contentOrNull?.let { throw ApiException("The model refused: $it") }
        return (msg?.get("content") as? JsonPrimitive)?.contentOrNull ?: throw ApiException("The model returned nothing.")
    }

    private fun geminiJson(cfg: ProviderCfg, system: String, user: String, schema: JsonObject): String {
        val model = cfg.model.removePrefix("models/")
        val res = post("${base(cfg.kind, cfg.baseUrl)}/models/$model:generateContent", buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") {
                add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", user) }) } })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", toGeminiSchema(schema))
            }
        }, "x-goog-api-key" to cfg.apiKey)
        (res["promptFeedback"] as? JsonObject)?.get("blockReason")?.let { throw ApiException("Gemini blocked this content ($it).") }
        val parts = res["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
        return parts.orEmpty().joinToString("") { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
            .ifEmpty { throw ApiException("Gemini returned nothing.") }
    }

    private fun claudeJson(cfg: ProviderCfg, system: String, user: String, schema: JsonObject): String = claudeErrors {
        val client = claude(cfg.apiKey)
        fun run(structured: Boolean, maxTokens: Long): String {
            val params = MessageCreateParams.builder()
                .model(cfg.model)
                .maxTokens(maxTokens)
                .system(if (structured) system else system + schemaHint(schema))
                .addUserMessage(user)
                .apply {
                    if (structured) outputConfig(
                        OutputConfig.builder().format(
                            JsonOutputFormat.builder().schema(
                                JsonOutputFormat.Schema.builder()
                                    .putAllAdditionalProperties(schema.mapValues { JsonValue.from(plain(it.value)) })
                                    .build()
                            ).build()
                        ).build()
                    )
                }
                .build()
            // Streamed, so a long script does not hit an HTTP timeout.
            val acc = MessageAccumulator.create()
            client.messages().createStreaming(params).use { stream -> stream.stream().forEach { acc.accumulate(it) } }
            val message = acc.message()
            val stop = message.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) throw ApiException("Claude declined this content.")
            if (stop == StopReason.MAX_TOKENS) throw ApiException("The script was too long for this model. Shorten the podcast or pick another model.")
            return message.content().joinToString("") { b -> b.text().map { it.text() }.orElse("") }
        }
        try {
            run(true, 32000)
        } catch (e: BadRequestException) {
            // Older Claude models: no structured outputs, or a smaller output limit.
            try { run(false, 32000) } catch (e2: BadRequestException) { run(false, 8192) }
        }
    }

    private inline fun <T> claudeErrors(block: () -> T): T = try {
        block()
    } catch (e: UnauthorizedException) {
        throw ApiException("Claude rejected the API key.", 401)
    } catch (e: RateLimitException) {
        throw ApiException("Claude rate limit reached. Try again in a minute.", 429)
    } catch (e: AnthropicServiceException) {
        throw ApiException("Claude error ${e.statusCode()}: ${e.message.orEmpty().take(300)}", e.statusCode())
    } catch (e: AnthropicIoException) {
        throw ApiException("No internet connection.")
    }

    // ───────────── plumbing ─────────────
    private fun get(url: String, header: Pair<String, String>): JsonObject =
        call(Request.Builder().url(url).header(header.first, header.second).build())

    private fun post(url: String, body: JsonObject, header: Pair<String, String>): JsonObject =
        call(Request.Builder().url(url).header(header.first, header.second)
            .post(AppJson.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonType)).build())

    private fun call(req: Request): JsonObject {
        val (code, text) = try {
            http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IllegalArgumentException) {
            throw ApiException("That provider URL is not valid.")
        } catch (e: IOException) {
            throw ApiException("No internet connection.")
        }
        if (code !in 200..299) {
            val detail = runCatching {
                val err = AppJson.parseToJsonElement(text).jsonObject["error"]
                ((err as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: (err as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
            throw ApiException(
                when (code) {
                    401, 403 -> "The provider rejected the API key."
                    429 -> "Rate limit or quota reached at the provider."
                    else -> "Provider error $code"
                } + (detail?.let { "\n${it.take(300)}" } ?: ""), code)
        }
        return runCatching { AppJson.parseToJsonElement(text).jsonObject }
            .getOrElse { throw ApiException("The provider sent an unreadable answer.") }
    }

    companion object {
        fun schemaHint(schema: JsonObject) =
            "\n\n# OUTPUT FORMAT\nReturn ONLY one JSON object matching this JSON Schema. No prose, no code fences.\n$schema"

        fun parseJson(text: String): JsonObject {
            val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            runCatching { return AppJson.parseToJsonElement(t).jsonObject }
            val a = t.indexOf('{'); val b = t.lastIndexOf('}')
            if (a >= 0 && b > a) runCatching { return AppJson.parseToJsonElement(t.substring(a, b + 1)).jsonObject }
            throw ApiException("The model did not return valid JSON. Try again or pick another model.")
        }

        /** Gemini's responseSchema is an OpenAPI subset: drop the keys it rejects. */
        fun toGeminiSchema(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.filterKeys { it != "additionalProperties" && it != "\$schema" }.mapValues { (k, v) ->
                if (k == "properties" && v is JsonObject) JsonObject(v.mapValues { toGeminiSchema(it.value) }) else toGeminiSchema(v)
            })
            is JsonArray -> JsonArray(e.map { toGeminiSchema(it) })
            else -> e
        }

        /** kotlinx JSON → plain Kotlin values, for the Claude SDK. */
        fun plain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> e.mapValues { plain(it.value) }
            is JsonArray -> e.map { plain(it) }
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
        }
    }
}
