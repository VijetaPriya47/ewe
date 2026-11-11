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

    /** "Mark as listened" pressed while offline: "scheduleId|yyyy-MM-dd", sent on the next sync. */
    var pendingListened: Set<String>
        get() = sp.getStringSet("pending_listened", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("pending_listened", v).apply()
}
