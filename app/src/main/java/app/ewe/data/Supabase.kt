package app.ewe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int = 0) : Exception(message)

/** Thin client for Supabase: sign-in, tables (PostgREST), database functions and edge functions. */
class Supabase(private val prefs: LocalPrefs, private val http: OkHttpClient = defaultHttp) {
    private val jsonType = "application/json".toMediaType()
    private val refreshLock = Mutex()

    // ───────────── auth ─────────────
    suspend fun signIn(email: String, password: String) {
        val res = raw("POST", "/auth/v1/token?grant_type=password", buildJsonObject {
            put("email", email); put("password", password)
        }, auth = false)
        storeSession(res.jsonObject)
    }

    /** Returns true when signed in right away, false when the project wants the email confirmed first. */
    suspend fun signUp(email: String, password: String): Boolean {
        val res = raw("POST", "/auth/v1/signup", buildJsonObject {
            put("email", email); put("password", password)
        }, auth = false).jsonObject
        if (res["access_token"] == null) return false
        storeSession(res)
        return true
    }

    suspend fun signOut() {
        runCatching { raw("POST", "/auth/v1/logout", JsonObject(emptyMap())) }
        prefs.clearAuth()
    }

    private fun storeSession(o: JsonObject) {
        prefs.accessToken = o["access_token"]?.jsonPrimitive?.contentOrNull
        prefs.refreshToken = o["refresh_token"]?.jsonPrimitive?.contentOrNull
        val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        prefs.expiresAt = System.currentTimeMillis() + expiresIn * 1000
        val user = o["user"]?.jsonObject
        prefs.userId = user?.get("id")?.jsonPrimitive?.contentOrNull
        prefs.email = user?.get("email")?.jsonPrimitive?.contentOrNull
    }

    private suspend fun freshToken(force: Boolean = false): String = refreshLock.withLock {
        val token = prefs.accessToken
        if (!force && token != null && prefs.expiresAt - System.currentTimeMillis() > 60_000) return token
        val refresh = prefs.refreshToken ?: throw ApiException("Please sign in again.", 401)
        val res = try {
            raw("POST", "/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", refresh) }, auth = false)
        } catch (e: ApiException) {
            if (e.status in 400..499) { prefs.clearAuth(); throw ApiException("Your sign-in expired. Please sign in again.", 401) }
            throw e
        }
        storeSession(res.jsonObject)
        prefs.accessToken ?: throw ApiException("Please sign in again.", 401)
    }

    // ───────────── tables ─────────────
    /** Reads every row (PostgREST returns at most 1000 per request, so this pages). */
    suspend fun selectAll(table: String, columns: String, filter: String = ""): JsonArray {
        val out = ArrayList<JsonElement>()
        var offset = 0
        while (true) {
            val page = raw("GET", "/rest/v1/$table?select=$columns${if (filter.isEmpty()) "" else "&$filter"}&order=id&limit=$PAGE&offset=$offset", null)
            val rows = page as? JsonArray ?: break
            out.addAll(rows)
            if (rows.size < PAGE) break
            offset += PAGE
        }
        return JsonArray(out)
    }

    suspend fun insert(table: String, row: JsonObject): JsonObject {
        val res = raw("POST", "/rest/v1/$table", row, prefer = "return=representation")
        return (res as JsonArray).first().jsonObject
    }

    suspend fun upsert(table: String, row: JsonObject) {
        raw("POST", "/rest/v1/$table", row, prefer = "resolution=merge-duplicates,return=minimal")
    }

    suspend fun update(table: String, filter: String, patch: JsonObject) {
        raw("PATCH", "/rest/v1/$table?$filter", patch, prefer = "return=minimal")
    }

    suspend fun delete(table: String, filter: String) {
        raw("DELETE", "/rest/v1/$table?$filter", null, prefer = "return=minimal")
    }

    suspend fun rpc(fn: String, args: JsonObject = JsonObject(emptyMap())): JsonElement =
        raw("POST", "/rest/v1/rpc/$fn", args)

    /** Edge function call. These wait for an AI provider, so the timeout is long. */
    suspend fun function(name: String, body: JsonObject): JsonObject =
        raw("POST", "/functions/v1/$name", body, client = slowHttp).jsonObject

    // ───────────── plumbing ─────────────
    private suspend fun raw(
        method: String, path: String, body: JsonElement?, auth: Boolean = true,
        prefer: String? = null, client: OkHttpClient = http, retried: Boolean = false,
    ): JsonElement = withContext(Dispatchers.IO) {
        val base = prefs.supabaseUrl
        if (base.isEmpty() || prefs.anonKey.isEmpty()) throw ApiException("Add your Supabase project URL and key first.")
        val token = if (auth) freshToken() else null
        val req = Request.Builder().url(base + path)
            .header("apikey", prefs.anonKey)
            .header("Authorization", "Bearer ${token ?: prefs.anonKey}")
            .apply { if (prefer != null) header("Prefer", prefer) }
            .method(method, body?.let { AppJson.encodeToString(JsonElement.serializer(), it).toRequestBody(jsonType) }
                ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(jsonType))
            .build()
        val (code, text) = try {
            client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            throw ApiException(if (e is java.net.SocketTimeoutException) "The server took too long to answer. Try again."
            else "No internet connection.")
        }
        if (code == 401 && auth && !retried) {
            freshToken(force = true)
            return@withContext raw(method, path, body, auth, prefer, client, retried = true)
        }
        if (code !in 200..299) throw ApiException(errorMessage(text, code), code)
        if (text.isBlank()) JsonNull else runCatching { AppJson.parseToJsonElement(text) }.getOrDefault(JsonNull)
    }

    private fun errorMessage(text: String, code: Int): String {
        val o = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
        val msg = listOf("error", "message", "msg", "error_description")
            .firstNotNullOfOrNull { k -> (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        val detail = (o?.get("detail") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        return when {
            msg != null && detail != null -> "$msg\n${detail.take(300)}"
            msg != null -> msg
            code == 404 -> "Not found (404). Is the database set up and are the functions deployed?"
            else -> "Request failed ($code)."
        }
    }

    companion object {
        private const val PAGE = 1000
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val slowHttp: OkHttpClient = defaultHttp.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS).build()
    }
}
