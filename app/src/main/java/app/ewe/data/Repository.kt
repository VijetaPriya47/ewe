package app.ewe.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** One chat message to send: text and up to 10 images. */
data class Batch(val text: String, val images: List<Uri>, val mode: String = "notes", val figures: List<Uri> = emptyList())

/**
 * An upload that is being turned into notes, or one that failed and can be retried.
 * A PDF becomes several batches (10 pages each); [batches] holds those still to send.
 */
data class Upload(
    val batches: List<Batch>,
    val stage: String,
    val error: String? = null,
    val total: Int = batches.size,
    val pdfDir: File? = null,
) {
    val text: String get() = batches.firstOrNull()?.text.orEmpty()
    val images: List<Uri> get() = batches.flatMap { it.images }
    /** Only a single plain message can go back into the composer to be edited. */
    val editable: Boolean get() = pdfDir == null && batches.size == 1 && batches[0].figures.isEmpty()
    val done: Int get() = total - batches.size
}

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
    private val _authMessage = MutableStateFlow<String?>(null)
    /** Result of a sign-in that finished outside the app (browser → back to ewe). */
    val authMessage: StateFlow<String?> = _authMessage
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
            if (!api.signUp(email, password)) return "Account created. Tap the link in the confirmation email — it opens ewe and signs you in."
        } else {
            api.signIn(email, password)
        }
        _signedIn.value = true
        refresh()
        return null
    }

    /** "Continue with Google": the page to open in the browser. */
    fun googleSignInUrl(url: String, anonKey: String): String {
        prefs.supabaseUrl = url
        prefs.anonKey = anonKey
        return api.googleSignInUrl()
    }

    /** The browser came back to ewe://auth-callback (Google sign-in or an email link). */
    fun handleAuthCallback(link: Uri) {
        scope.launch {
            try {
                val fragment = link.encodedFragment.orEmpty().split('&').mapNotNull {
                    val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to Uri.decode(it.substring(i + 1).replace('+', ' '))
                }.toMap()
                fun param(name: String) = link.getQueryParameter(name) ?: fragment[name]
                val code = param("code")
                val access = param("access_token")
                val refresh = param("refresh_token")
                when {
                    code != null -> api.exchangeCode(code)
                    access != null && refresh != null -> api.adoptSession(access, refresh, param("expires_in")?.toLongOrNull() ?: 3600)
                    param("error_code") == "otp_expired" ->
                        throw ApiException("That email link was already used or has expired. If you tapped it before, your account is confirmed — just sign in.")
                    else -> throw ApiException(param("error_description") ?: "Sign-in was cancelled.")
                }
                _signedIn.value = true
                _authMessage.value = null
                refresh()
            } catch (e: Exception) {
                _authMessage.value = e.message ?: "Sign-in failed."
            }
        }
    }

    fun clearAuthMessage() { _authMessage.value = null }

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
                val sections = async { api.selectAll("sections", "id,session_id,title,position,important") }
                val bullets = async { api.selectAll("bullets", "id,section_id,content,kind,lang,important,highlight,caption,level,position,date_added,origin_session_id,origin_message_id") }
                val links = async { api.selectAll("bullet_links", "id,bullet_id,section_id,position,created_at") }
                val messages = async { api.selectAll("messages", "id,session_id,role,content,image_count,created_at,mode") }
                val references = async { api.selectAll("session_references", "id,session_id,number,url,title,description,date_added,origin_message_id") }
                val summaries = async { api.selectAll("session_summaries", "id,session_id,kind,title,mermaid,note,created_at") }
                val schedule = async { api.selectAll("revision_schedule", "id,section_id,added_date,interval_days,revisions_done,next_due_date,last_revised") }
                val settings = async { loadSettings() }
                val st = settings.await()
                val rag = if (st.ragEnabled) runCatching { AppJson.decodeFromJsonElement<RagStatus>(api.rpc("rag_status")) }
                    .getOrDefault(_snapshot.value.rag) else RagStatus(selectedModel = st.ragSelectedModel, activeModel = st.ragActiveModel)
                Snapshot(
                    settings = st,
                    sessions = decode(sessions.await()), sections = decode(sections.await()),
                    bullets = decode(bullets.await()), links = decode(links.await()),
                    messages = decode<Message>(messages.await()).sortedBy { instantOf(it.createdAt) },
                    schedule = decode(schedule.await()), references = decode(references.await()), summaries = decode(summaries.await()),
                    rag = rag, syncedAt = System.currentTimeMillis(),
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
        var rows = api.selectAll("settings", SETTINGS_COLUMNS, order = "user_id")
        if (rows.isEmpty()) {
            api.upsert("settings", buildJsonObject { put("user_id", prefs.userId) })
            rows = api.selectAll("settings", SETTINGS_COLUMNS, order = "user_id")
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
        // trimEnd only at the start of lines: code keeps its indentation
        api.update("bullets", "id=eq.$id", buildJsonObject { put("content", text.trimEnd().trimStart('\n', '\r')) })
        refresh(); refreshEmbeddingsQuietly()
    }

    /** Last resort for a diagram that cannot be drawn: the notes model corrects the Mermaid code. */
    suspend fun fixDiagram(code: String, error: String): String {
        val cfg = prefs.cfg(settings.notesProvider, settings.notesModel) ?: throw ApiException("Pick a notes model in Settings first.")
        val res = api.function("fix-diagram", buildJsonObject {
            put("code", code); put("error", error); putProvider("provider", cfg)
        })
        return res["code"]?.jsonPrimitive?.contentOrNull ?: throw ApiException("The model returned no diagram.")
    }

    /** Saves a repaired diagram quietly; if it can't be saved now, it is simply repaired again next time. */
    fun saveRepairedDiagram(id: String, code: String) {
        scope.launch { runCatching { editBullet(id, code) } }
    }

    // ───────────── references and overview ─────────────
    suspend fun updateReference(id: String, title: String, url: String, description: String) {
        val clean = url.trim()
        if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(clean)) throw ApiException("A link must start with http:// or https://.")
        try {
            api.update("session_references", "id=eq.$id", buildJsonObject {
                put("title", title.trim()); put("url", clean); put("description", description.trim())
            })
        } catch (e: ApiException) {
            if (e.message.orEmpty().contains("duplicate key")) throw ApiException("That link is already saved in this session.")
            throw e
        }
        refresh()
    }

    /** The reference goes; notes that cited it show [?]. The other references keep their numbers. */
    suspend fun deleteReference(id: String) { api.delete("session_references", "id=eq.$id"); refresh() }

    suspend fun deleteSummary(id: String) { api.delete("session_summaries", "id=eq.$id"); refresh() }

    fun saveRepairedSummary(id: String, mermaid: String) {
        scope.launch { runCatching { api.update("session_summaries", "id=eq.$id", buildJsonObject { put("mermaid", mermaid) }); refresh() } }
    }

    /** "Make a better overview": one AI call → a recall map (names only), replacing the previous one. */
    suspend fun makeOverview(sessionId: String) {
        val cfg = prefs.cfg(settings.notesProvider, settings.notesModel) ?: throw ApiException("Pick a notes model in Settings first.")
        api.function("make-overview", buildJsonObject { put("session_id", sessionId); putProvider("provider", cfg) })
        refresh()
    }

    /** Yellow or red highlight on a point, or null to clear it. */
    suspend fun setHighlight(id: String, color: String?) {
        api.update("bullets", "id=eq.$id", buildJsonObject { put("highlight", color) }); refresh()
    }

    /** Underline (important) on or off. The AI sets it; you can change it. */
    suspend fun setImportant(id: String, important: Boolean) {
        api.update("bullets", "id=eq.$id", buildJsonObject { put("important", important) }); refresh()
    }

    suspend fun deleteBullet(id: String) {
        val b = _snapshot.value.bullet(id)
        api.delete("bullets", "id=eq.$id")
        // A figure's picture goes too, unless another note (e.g. a recreated session) still shows it.
        if (b != null && b.isFigure && _snapshot.value.bullets.none { it.id != id && it.isFigure && it.content == b.content }) {
            runCatching { api.deleteObject(FIGURES, b.content) }
            figureFile(b.content).delete()
        }
        refresh(); refreshEmbeddingsQuietly()
    }

    /** Sections in the order given; any section not listed (one with no points) keeps its place after them. */
    suspend fun reorderSections(sessionId: String, ids: List<String>) {
        val current = _snapshot.value.sectionsOf(sessionId)
        val order = ids + current.map { it.id }.filter { it !in ids }
        val changed = order.mapIndexedNotNull { i, id ->
            val pos = (i + 1).toDouble()
            current.firstOrNull { it.id == id }?.takeIf { it.position != pos }?.let { id to pos }
        }
        if (changed.isEmpty()) return
        // show the new order straight away; the server then confirms it
        val newPos = changed.toMap()
        _snapshot.value = _snapshot.value.copy(sections = _snapshot.value.sections.map { s -> newPos[s.id]?.let { s.copy(position = it) } ?: s })
        try {
            coroutineScope {
                changed.map { (id, pos) -> async { api.update("sections", "id=eq.$id", buildJsonObject { put("position", pos) }) } }.awaitAll()
            }
        } finally { refresh() }
    }

    suspend fun setLevel(id: String, level: Int) {
        api.update("bullets", "id=eq.$id", buildJsonObject { put("level", level.coerceIn(0, Nesting.MAX)) }); refresh()
    }

    suspend fun editCaption(id: String, caption: String) {
        api.update("bullets", "id=eq.$id", buildJsonObject { put("caption", caption.trim().take(200)) }); refresh()
    }

    suspend fun editDiagram(id: String, code: String, caption: String) {
        api.update("bullets", "id=eq.$id", buildJsonObject {
            put("content", code.trimEnd().trimStart('\n', '\r')); put("caption", caption.trim().take(200))
        })
        refresh(); refreshEmbeddingsQuietly()
    }

    // ───────────── figures: kept on the phone after the first download, so they show offline ─────────────
    fun figureFile(path: String): File = File(context.filesDir, "figures/" +
        java.security.MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg")

    suspend fun loadFigure(path: String): File {
        val f = figureFile(path)
        if (f.exists() && f.length() > 0) return f
        val bytes = api.downloadObject(FIGURES, path)
        withContext(Dispatchers.IO) { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
        return f
    }

    /** Removes a linked point from a Topic; the point itself stays in its own session. */
    /** Takes every point linked in from other sessions out of [sessionId]; the points stay where they came from. */
    suspend fun removeLinkedPoints(sessionId: String) {
        val sectionIds = _snapshot.value.sectionsOf(sessionId).map { it.id }
        if (sectionIds.isEmpty()) return
        sectionIds.chunked(50).forEach { ids -> api.delete("bullet_links", "section_id=in.(${ids.joinToString(",")})") }
        refresh(); refreshEmbeddingsQuietly()
    }

    suspend fun unlink(linkId: String) {
        api.delete("bullet_links", "id=eq.$linkId"); refresh(); refreshEmbeddingsQuietly()
    }

    // ───────────── uploads → notes ─────────────
    fun dismissUpload(sessionId: String) {
        _uploads.value[sessionId]?.pdfDir?.deleteRecursively()
        _uploads.update { it - sessionId }
    }

    private fun busy(sessionId: String) = _uploads.value[sessionId]?.let { it.error == null } == true

    /** Runs outside the screen's lifetime, so leaving the chat does not cancel it. */
    fun send(sessionId: String, text: String, images: List<Uri>, mode: String = "notes", figures: List<Uri> = emptyList()) {
        if (busy(sessionId)) return
        run(sessionId, Upload(listOf(Batch(text, images, mode, if (mode == "notes") figures else emptyList())), "Reading…"))
    }

    /** Sends whatever is left of a failed upload (for a PDF: the remaining pages). */
    fun retry(sessionId: String) {
        val u = _uploads.value[sessionId]?.takeIf { it.error != null } ?: return
        run(sessionId, u.copy(error = null, stage = "Reading…"))
    }

    /**
     * Pages [from]..[to] of a PDF: drawn as images on the phone, then sent 10 pages per message,
     * one after another, so each message sees the notes made from the pages before it.
     */
    fun sendPdf(sessionId: String, uri: Uri, name: String, from: Int, to: Int, note: String, mode: String = "notes") {
        if (busy(sessionId)) return
        val dir = File(context.cacheDir, "pdf/${System.currentTimeMillis()}")
        val placeholder = Batch("PDF: $name, ${Pdf.pagesLabel(from, to)}", emptyList())
        _uploads.update { it + (sessionId to Upload(listOf(placeholder), "Reading the PDF…", pdfDir = dir)) }
        scope.launch {
            val batches = try {
                val chunks = Pdf.chunks(from, to)
                val pages = withContext(Dispatchers.IO) {
                    Pdf.render(context, uri, from, to, dir) { n ->
                        _uploads.update { it + (sessionId to Upload(listOf(placeholder), "Reading page $n of $to…", pdfDir = dir)) }
                    }
                }
                var i = 0
                chunks.mapIndexed { c, range ->
                    val label = "PDF: $name, ${Pdf.pagesLabel(range.first, range.last)}"
                    Batch(if (c == 0 && note.isNotBlank()) "$label\n\n${note.trim()}" else label, pages.subList(i, i + range.count()).also { i += range.count() }, mode)
                }
            } catch (e: Exception) {
                dir.deleteRecursively()
                _uploads.update { it + (sessionId to Upload(listOf(placeholder), "", e.message ?: "Could not read the PDF.")) }
                return@launch
            }
            run(sessionId, Upload(batches, "Reading…", pdfDir = dir))
        }
    }

    private fun run(sessionId: String, start: Upload) {
        _uploads.update { it + (sessionId to start) }
        val job: suspend () -> Unit = job@{
            var u = start
            while (u.batches.isNotEmpty()) {
                val batch = u.batches.first()
                val part = if (u.total > 1) " (part ${u.done + 1} of ${u.total})" else ""
                try {
                    sendOne(sessionId, batch) { stage -> _uploads.update { it + (sessionId to u.copy(stage = stage + part)) } }
                } catch (e: Exception) {
                    val sent = if (u.done > 0) "\n${u.done} of ${u.total} parts were saved; Try again sends the rest." else ""
                    _uploads.update { it + (sessionId to u.copy(stage = "", error = (e.message ?: "Something went wrong.") + sent)) }
                    return@job
                }
                u = u.copy(batches = u.batches.drop(1))
            }
            u.pdfDir?.deleteRecursively()
            _uploads.update { it - sessionId }
        }
        scope.launch { job() }
    }

    /** One chat message → notes (→ linking). Throws on failure; nothing is half-saved. */
    private suspend fun sendOne(sessionId: String, batch: Batch, stage: (String) -> Unit) {
        val cfg = prefs.cfg(settings.notesProvider, settings.notesModel)
            ?: throw ApiException("Pick a notes model in Settings first.")
        stage("Reading…")
        val bitmaps = withContext(Dispatchers.IO) { batch.images.map { Images.load(context, it, Images.AI_MAX_SIDE) } }
        val encoded = withContext(Dispatchers.Default) {
            bitmaps.map { Base64.encodeToString(Images.jpeg(it, 88), Base64.NO_WRAP) }
        }
        // Figures go to your private storage folder first; the AI then places and captions them.
        val uploaded = ArrayList<String>()
        val figureParts = if (batch.figures.isEmpty()) emptyList() else {
            stage("Saving figures…")
            batch.figures.mapIndexed { i, uri ->
                val (stored, small) = withContext(Dispatchers.Default) {
                    val bmp = Images.load(context, uri, FIGURE_MAX_SIDE)
                    Images.jpeg(bmp, 85) to Base64.encodeToString(Images.jpeg(Images.fit(bmp, 1024), 80), Base64.NO_WRAP)
                }
                val path = "${prefs.userId}/$sessionId/${java.util.UUID.randomUUID()}.jpg"
                api.uploadObject(FIGURES, path, stored)
                uploaded += path
                withContext(Dispatchers.IO) { figureFile(path).apply { parentFile?.mkdirs() }.writeBytes(stored) }
                buildJsonObject { put("key", "F${i + 1}"); put("path", path); put("mime", "image/jpeg"); put("data", small) }
            }
        }
        stage("Making notes…")
        val res = try { api.function("generate-notes", buildJsonObject {
            put("session_id", sessionId)
            put("text", batch.text)
            put("mode", batch.mode)
            put("local_date", today().toString())
            put("images", buildJsonArray { encoded.forEach { add(buildJsonObject { put("mime", "image/jpeg"); put("data", it) }) } })
            if (figureParts.isNotEmpty()) put("figures", buildJsonArray { figureParts.forEach { add(it) } })
            putProvider("provider", cfg)
        }) } catch (e: Exception) {
            // nothing was saved, so the pictures are not needed
            uploaded.forEach { p -> runCatching { api.deleteObject(FIGURES, p) }; figureFile(p).delete() }
            throw e
        }
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
            stage("Linking related notes…")
            runCatching { link(sessionId, messageId) }
            refresh()
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
        const val FIGURES = "figures"
        /** Long side a figure is stored at: sharp on a phone, about 100–300 KB. */
        const val FIGURE_MAX_SIDE = 1600
        private const val SETTINGS_COLUMNS = "notes_provider,notes_model,placement_provider,placement_model,podcast_provider,podcast_model," +
            "rag_enabled,rag_selected_model,rag_active_model,podcast_enabled,interval_cap_days,daily_job_time," +
            "podcast_target_minutes,revisions_per_level,keep_photo_copies"

        /** "provider:model" → (provider, model) */
        fun splitModel(key: String?): Pair<String, String>? {
            if (key.isNullOrBlank() || !key.contains(':')) return null
            return key.substringBefore(':') to key.substringAfter(':')
        }
    }
}
