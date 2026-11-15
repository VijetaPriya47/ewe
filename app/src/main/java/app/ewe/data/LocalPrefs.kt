package app.ewe.data

import android.content.Context
import app.ewe.BuildConfig

/**
 * Things that live only on this phone: the Supabase sign-in and the AI provider keys.
 * Kept in app-private storage that is excluded from backups.
 */
class LocalPrefs(context: Context) {
    private val sp = context.getSharedPreferences("ewe_local", Context.MODE_PRIVATE)

    var supabaseUrl: String
        get() = sp.getString("supabase_url", null) ?: BuildConfig.SUPABASE_URL
        set(v) = sp.edit().putString("supabase_url", v.trim().trimEnd('/')).apply()
    var anonKey: String
        get() = sp.getString("anon_key", null) ?: BuildConfig.SUPABASE_ANON_KEY
        set(v) = sp.edit().putString("anon_key", v.trim()).apply()

    var accessToken: String?
        get() = sp.getString("access_token", null)
        set(v) = sp.edit().putString("access_token", v).apply()
    var refreshToken: String?
        get() = sp.getString("refresh_token", null)
        set(v) = sp.edit().putString("refresh_token", v).apply()
    var expiresAt: Long
        get() = sp.getLong("expires_at", 0)
        set(v) = sp.edit().putLong("expires_at", v).apply()
    /** Look for diagrams in PDFs and offer them as figures (Settings › Notes). */
    var autoFigures: Boolean
        get() = sp.getBoolean("auto_figures", true)
        set(v) = sp.edit().putBoolean("auto_figures", v).apply()

    var userId: String?
        get() = sp.getString("user_id", null)
        set(v) = sp.edit().putString("user_id", v).apply()
    var email: String?
        get() = sp.getString("email", null)
        set(v) = sp.edit().putString("email", v).apply()

    /** One-time secret for a Google sign-in that is in progress (PKCE). */
    var pkceVerifier: String?
        get() = sp.getString("pkce_verifier", null)
        set(v) = sp.edit().putString("pkce_verifier", v).apply()

    val signedIn get() = !refreshToken.isNullOrEmpty() && !userId.isNullOrEmpty()

    fun clearAuth() = sp.edit().remove("access_token").remove("refresh_token").remove("expires_at")
        .remove("user_id").remove("email").apply()

    fun apiKey(kind: ProviderKind): String = sp.getString("key_${kind.id}", "") ?: ""
    fun setApiKey(kind: ProviderKind, key: String) = sp.edit().putString("key_${kind.id}", key.trim()).apply()

    /** Base URL of the OpenAI-compatible provider, e.g. https://openrouter.ai/api/v1 */
    var compatibleUrl: String
        get() = sp.getString("compatible_url", "") ?: ""
        set(v) = sp.edit().putString("compatible_url", v.trim().trimEnd('/')).apply()

    fun connected(kind: ProviderKind): Boolean =
        if (kind == ProviderKind.COMPATIBLE) compatibleUrl.isNotEmpty() else apiKey(kind).isNotEmpty()

    fun cfg(providerId: String?, model: String?): ProviderCfg? {
        val kind = ProviderKind.of(providerId) ?: return null
        if (model.isNullOrBlank() || !connected(kind)) return null
        return ProviderCfg(kind, apiKey(kind), model, compatibleUrl.takeIf { kind == ProviderKind.COMPATIBLE })
    }

    // ───────────── voice: Gnani text-to-speech (read-aloud and the podcast). Phone only, like the AI keys. ─────────────
    /** Which voice reads: "phone" (Android's own, free, offline) or "gnani". */
    var speechEngine: String
        get() = sp.getString("speech_engine", "phone") ?: "phone"
        set(v) = sp.edit().putString("speech_engine", v).apply()
    /** Several keys, one per line: the first is used, the rest take over when it is rejected or out of credit. */
    var gnaniKeys: List<String>
        get() = (sp.getString("gnani_key", "") ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("gnani_key", v.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")).apply()
    val gnaniKey: String get() = gnaniKeys.firstOrNull().orEmpty()
    var gnaniModel: String
        get() = sp.getString("gnani_model", "timbre-v2.5") ?: "timbre-v2.5"
        set(v) = sp.edit().putString("gnani_model", v).apply()
    var gnaniVoice: String
        get() = sp.getString("gnani_voice", "Kaveri") ?: "Kaveri"
        set(v) = sp.edit().putString("gnani_voice", v).apply()
    var gnaniLanguage: String
        get() = sp.getString("gnani_language", "en-IN") ?: "en-IN"
        set(v) = sp.edit().putString("gnani_language", v).apply()

    /** The Gnani settings when it is the chosen voice and has a key; null means "use the phone's voice". */
    fun gnani(): app.ewe.ai.GnaniCfg? =
        if (speechEngine == "gnani" && gnaniKey.isNotBlank()) app.ewe.ai.GnaniCfg(gnaniKey, gnaniModel, gnaniVoice, gnaniLanguage, gnaniKeys.drop(1)) else null

    /** "Mark as listened" pressed while offline: "scheduleId|yyyy-MM-dd", sent on the next sync. */
    var pendingListened: Set<String>
        get() = sp.getStringSet("pending_listened", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("pending_listened", v).apply()
}
