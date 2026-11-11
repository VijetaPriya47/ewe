package app.ewe.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.LocalDate

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Failed(val message: String) : SyncState
}

/** An upload that is being turned into notes, or one that failed and can be retried. */
data class Upload(val text: String, val images: List<Uri>, val stage: String, val error: String? = null)

data class RebuildProgress(val remaining: Int, val total: Int, val error: String? = null)

/** A section found by meaning (the second half of hybrid search). */
data class MeaningHit(val sectionId: String, val sessionId: String, val similarity: Double)

class Repository(private val context: Context, val prefs: LocalPrefs, private val api: Supabase) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheFile = File(context.filesDir, "snapshot.json")
    private val mediaDir = File(context.filesDir, "media")   // hidden inside the app, never in the Gallery
    private val syncLock = Mutex()

    private val _snapshot = MutableStateFlow(loadCache())
    val snapshot: StateFlow<Snapshot> = _snapshot
    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync
    private val _signedIn = MutableStateFlow(prefs.signedIn)
    val signedIn: StateFlow<Boolean> = _signedIn
    private val _uploads = MutableStateFlow<Map<String, Upload>>(emptyMap())
    val uploads: StateFlow<Map<String, Upload>> = _uploads
    private val _rebuild = MutableStateFlow<RebuildProgress?>(null)
    val rebuild: StateFlow<RebuildProgress?> = _rebuild
    private val _mediaVersion = MutableStateFlow(0)
    val mediaVersion: StateFlow<Int> = _mediaVersion

    val settings get() = _snapshot.value.settings
    fun today(): LocalDate = LocalDate.now()

    private fun loadCache(): Snapshot = runCatching {
        if (prefs.signedIn && cacheFile.exists()) AppJson.decodeFromString(Snapshot.serializer(), cacheFile.readText()) else Snapshot()
    }.getOrDefault(Snapshot())

    private fun publish(s: Snapshot, persist: Boolean = true) {
        _snapshot.value = s
        if (persist) runCatching {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(AppJson.encodeToString(Snapshot.serializer(), s))
            tmp.renameTo(cacheFile)
        }
    }

    // ───────────── account ─────────────
    suspend fun signIn(url: String, anonKey: String, email: String, password: String, create: Boolean): String? {
        prefs.supabaseUrl = url
        prefs.anonKey = anonKey
        if (create) {
            if (!api.signUp(email, password)) return "Account created. Open the confirmation email, then sign in."
        } else {
            api.signIn(email, password)
        }
        _signedIn.value = true
        refresh()
        return null
    }

    suspend fun signOut() {
        api.signOut()
        cacheFile.delete()
        _snapshot.value = Snapshot()
        _signedIn.value = false
    }

    // ───────────── sync: pull everything, keep a copy for offline ─────────────
    /** Never throws; the result is in [sync]. */
    suspend fun refresh(): Boolean = syncLock.withLock {
        if (!prefs.signedIn) return false
        _sync.value = SyncState.Syncing
        try {
            flushPendingListened()
            val s = coroutineScope {
                val sessions = async { api.selectAll("sessions", "id,title,type,is_active,recreated_from,created_at,updated_at") }
                val sections = async { api.selectAll("sections", "id,session_id,title,position") }
                val bullets = async { api.selectAll("bullets", "id,section_id,content,position,date_added,origin_session_id,origin_message_id") }
                val links = async { api.selectAll("bullet_links", "id,bullet_id,section_id,position,created_at") }
                val messages = async { api.selectAll("messages", "id,session_id,role,content,image_count,created_at") }
                val schedule = async { api.selectAll("revision_schedule", "id,section_id,added_date,interval_days,revisions_done,next_due_date,last_revised") }
                val settings = async { loadSettings() }
                val st = settings.await()
                val rag = if (st.ragEnabled) runCatching { AppJson.decodeFromJsonElement<RagStatus>(api.rpc("rag_status")) }
                    .getOrDefault(_snapshot.value.rag) else RagStatus(selectedModel = st.ragSelectedModel, activeModel = st.ragActiveModel)
                Snapshot(
                    settings = st,
                    sessions = decode(sessions.await()), sections = decode(sections.await()),
                    bullets = decode(bullets.await()), links = decode(links.await()),
                    messages = decode<Message>(messages.await()).sortedBy { it.createdAt },
                    schedule = decode(schedule.await()), rag = rag, syncedAt = System.currentTimeMillis(),
                )
            }
            publish(s)
            _sync.value = SyncState.Idle
            true
        } catch (e: Exception) {
            if (e is ApiException && e.status == 401) _signedIn.value = prefs.signedIn
            _sync.value = SyncState.Failed(e.message ?: "Sync failed")
            false
        }
    }

    fun refreshInBackground() { scope.launch { refresh() } }

    private inline fun <reified T> decode(rows: JsonArray): List<T> =
        AppJson.decodeFromJsonElement(ListSerializer(kotlinx.serialization.serializer<T>()), rows)

    private suspend fun loadSettings(): Settings {
        var rows = api.selectAll("settings", "*")
        if (rows.isEmpty()) {
            api.upsert("settings", buildJsonObject { put("user_id", prefs.userId) })
            rows = api.selectAll("settings", "*")
        }
        return rows.firstOrNull()?.let { AppJson.decodeFromJsonElement<Settings>(it) } ?: Settings()
    }

    private fun mine() = "user_id=eq.${prefs.userId}"

    /** Change settings. The UI updates at once; the server is the source of truth. */
    suspend fun updateSettings(build: JsonObjectBuilder.() -> Unit) {
        val patch = buildJsonObject(build)
        api.update("settings", mine(), patch)
        refresh()
    }

    // ───────────── sessions ─────────────
    suspend fun createSession(title: String, type: String): String {
        val row = api.insert("sessions", buildJsonObject { put("title", title.trim()); put("type", type) })
        refresh()
        return row["id"]!!.jsonPrimitive.content
    }

    suspend fun renameSession(id: String, title: String) {
        api.update("sessions", "id=eq.$id", buildJsonObject { put("title", title.trim()) }); refresh()
    }

    suspend fun setActive(id: String, active: Boolean) {
        api.update("sessions", "id=eq.$id", buildJsonObject { put("is_active", active) }); refresh()
    }

    /** "Recreate as Topic/Project": returns the new session's id. */
    suspend fun recreate(id: String): String {
        val newId = api.rpc("recreate_session", buildJsonObject { put("p_session_id", id) }).jsonPrimitive.content
        refresh()
        refreshEmbeddingsQuietly()
        return newId
    }

    suspend fun restartRevision(id: String) {
        api.rpc("restart_revision", buildJsonObject { put("p_session_id", id); put("p_today", today().toString()) })
        refresh()
    }

    // ───────────── notes: edit and delete freely, no history ─────────────
    suspend fun editBullet(id: String, text: String) {
        api.update("bullets", "id=eq.$id", buildJsonObject { put("content", text.trim()) })
        refresh(); refreshEmbeddingsQuietly()
    }

    suspend fun deleteBullet(id: String) {
        api.delete("bullets", "id=eq.$id"); refresh(); refreshEmbeddingsQuietly()
    }

    /** Removes a linked point from a Topic; the point itself stays in its own session. */
    suspend fun unlink(linkId: String) {
        api.delete("bullet_links", "id=eq.$linkId"); refresh(); refreshEmbeddingsQuietly()
    }

    // ───────────── uploads → notes ─────────────
    fun dismissUpload(sessionId: String) = _uploads.update { it - sessionId }

    /** Runs outside the screen's lifetime, so leaving the chat does not cancel it. */
    fun send(sessionId: String, text: String, images: List<Uri>) {
        if (_uploads.value[sessionId]?.error == null && _uploads.value.containsKey(sessionId)) return
        _uploads.update { it + (sessionId to Upload(text, images, "Reading…")) }
        scope.launch {
            try {
                val cfg = prefs.cfg(settings.notesProvider, settings.notesModel)
                    ?: throw ApiException("Pick a notes model in Settings first.")
                val bitmaps = withContext(Dispatchers.IO) { images.map { Images.load(context, it, Images.AI_MAX_SIDE) } }
                val encoded = withContext(Dispatchers.Default) {
                    bitmaps.map { Base64.encodeToString(Images.jpeg(it, 88), Base64.NO_WRAP) }
                }
                _uploads.update { it + (sessionId to Upload(text, images, "Making notes…")) }
                val res = api.function("generate-notes", buildJsonObject {
                    put("session_id", sessionId)
                    put("text", text)
                    put("local_date", today().toString())
                    put("images", buildJsonArray { encoded.forEach { add(buildJsonObject { put("mime", "image/jpeg"); put("data", it) }) } })
                    putProvider("provider", cfg)
                })
                val messageId = res["user_message_id"]?.jsonPrimitive?.contentOrNull
                if (messageId != null && settings.keepPhotoCopies && bitmaps.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        mediaDir.mkdirs()
                        bitmaps.forEachIndexed { i, b -> File(mediaDir, "${messageId}_$i.jpg").writeBytes(Images.smallCopy(b)) }
                    }
                    _mediaVersion.update { it + 1 }
                }
                refresh()
                val added = res["new_bullets"]?.jsonPrimitive?.intOrNull ?: 0
                if (messageId != null && added > 0 && linkingReady()) {
                    _uploads.update { it + (sessionId to Upload(text, images, "Linking related notes…")) }
                    runCatching { link(sessionId, messageId) }
                    refresh()
                }
                _uploads.update { it - sessionId }
            } catch (e: Exception) {
                _uploads.update { it + (sessionId to Upload(text, images, "", e.message ?: "Something went wrong.")) }
            }
        }
    }

    private fun JsonObjectBuilder.putProvider(name: String, cfg: ProviderCfg) = putJsonObject(name) {
        put("kind", cfg.kind.id); put("api_key", cfg.apiKey); put("model", cfg.model)
        cfg.baseUrl?.let { put("base_url", it) }
    }

    // ───────────── saved photo copies (this phone only) ─────────────
    fun photos(messageId: String): List<File> =
        mediaDir.listFiles { f -> f.name.startsWith("${messageId}_") }?.sortedBy { it.name }.orEmpty()

    fun mediaStats(): Pair<Int, Long> = mediaDir.listFiles().orEmpty().let { it.size to it.sumOf { f -> f.length() } }

    fun clearMedia() { mediaDir.listFiles()?.forEach { it.delete() }; _mediaVersion.update { it + 1 } }

    // ───────────── linking (RAG) ─────────────
    private fun embeddingCfg(model: String? = settings.ragSelectedModel): ProviderCfg? {
        val (provider, name) = splitModel(model) ?: return null
        return prefs.cfg(provider, name)
    }

    private fun linkingReady(): Boolean {
        val s = settings
        return s.ragEnabled && s.ragActiveModel != null && s.ragSelectedModel == s.ragActiveModel &&
            embeddingCfg() != null && prefs.cfg(s.placementProvider, s.placementModel) != null
    }

    /** Hybrid search is on only when every note is embedded with the active model. */
    fun hybridReady(): Boolean = _snapshot.value.rag.ready && settings.ragEnabled && embeddingCfg() != null

    private suspend fun link(sessionId: String, messageId: String) {
        api.function("linking", buildJsonObject {
            put("action", "link"); put("session_id", sessionId); put("message_id", messageId)
            putProvider("embedding", embeddingCfg()!!)
            putProvider("placement", prefs.cfg(settings.placementProvider, settings.placementModel)!!)
        })
    }

    suspend fun setLinking(on: Boolean) = updateSettings { put("rag_enabled", on) }

    suspend fun setEmbeddingModel(provider: String, model: String) {
        api.rpc("rag_set_model", buildJsonObject { put("p_model", "$provider:$model") }); refresh()
    }

    suspend fun switchBack() { api.rpc("rag_switch_back"); refresh() }

    /** "Rebuild" (fill in what is missing) or "Rebuild all" (new model, staged then swapped in). */
    fun startRebuild() {
        if (_rebuild.value != null && _rebuild.value?.error == null) return
        val all = _snapshot.value.rag.mismatch
        val total = _snapshot.value.rag.let { if (all) it.totalRows else it.missingRows }.coerceAtLeast(1)
        _rebuild.value = RebuildProgress(total, total)
        scope.launch {
            try {
                val cfg = embeddingCfg() ?: throw ApiException("Add a key for the embedding provider first.")
                while (true) {
                    val res = api.function("linking", buildJsonObject {
                        put("action", "sync"); put("mode", if (all) "rebuild" else "refresh")
                        putProvider("embedding", cfg)
                    })
                    val left = res["remaining"]?.jsonPrimitive?.intOrNull ?: 0
                    _rebuild.value = RebuildProgress(left, maxOf(total, left))
                    if (left <= 0) break
                }
                refresh()
                _rebuild.value = null
            } catch (e: Exception) {
                refresh()
                _rebuild.value = RebuildProgress(0, total, e.message ?: "Rebuild failed.")
            }
        }
    }

    fun clearRebuildError() { _rebuild.value = null }

    /** After notes change, keep the index fresh. Best effort; the banner shows anything left over. */
    private fun refreshEmbeddingsQuietly() {
        val s = settings
        if (!s.ragEnabled || s.ragActiveModel == null || s.ragSelectedModel != s.ragActiveModel) return
        val cfg = embeddingCfg() ?: return
        scope.launch {
            runCatching {
                api.function("linking", buildJsonObject { put("action", "sync"); put("mode", "refresh"); putProvider("embedding", cfg) })
                refresh()
            }
        }
    }

    suspend fun searchByMeaning(query: String): List<MeaningHit> {
        val cfg = embeddingCfg() ?: return emptyList()
        val res = api.function("linking", buildJsonObject { put("action", "search"); put("query", query); putProvider("embedding", cfg) })
        return res["results"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            MeaningHit(o["section_id"]!!.jsonPrimitive.content, o["session_id"]!!.jsonPrimitive.content,
                o["similarity"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0)
        }
    }

    // ───────────── revision ─────────────
    /** Works offline too: the press is remembered and sent on the next sync. */
    suspend fun markListened(scheduleIds: List<String>) {
        if (scheduleIds.isEmpty()) return
        val day = today().toString()
        prefs.pendingListened = prefs.pendingListened + scheduleIds.map { "$it|$day" }
        refresh()
    }

    private suspend fun flushPendingListened() {
        val pending = prefs.pendingListened
        if (pending.isEmpty()) return
        pending.groupBy({ it.substringAfter('|') }, { it.substringBefore('|') }).forEach { (day, ids) ->
            api.rpc("mark_listened", buildJsonObject {
                put("p_schedule_ids", buildJsonArray { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("p_today", day)
            })
        }
        prefs.pendingListened = emptySet()
    }

    fun pendingListenedIds(): Set<String> = prefs.pendingListened.map { it.substringBefore('|') }.toSet()

    companion object {
        /** "provider:model" → (provider, model) */
        fun splitModel(key: String?): Pair<String, String>? {
            if (key.isNullOrBlank() || !key.contains(':')) return null
            return key.substringBefore(':') to key.substringAfter(':')
        }
    }
}
