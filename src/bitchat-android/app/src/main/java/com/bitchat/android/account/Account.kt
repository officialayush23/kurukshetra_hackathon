package com.bitchat.android.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.model.Role
import com.bitchat.android.services.SyncPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A person signed in with the **same account** they use in the Indradhanu PWA.
 *
 * Supabase issues the identity; the command centre's `/auth/me` says what that identity is
 * (citizen, field operator of an agency, staff). The phone keeps the last verified answer, so
 * a person who signed in yesterday is still "Sana Shaikh, Fire Brigade" with no network today,
 * marked as last verified at a given time rather than presented as live.
 */
data class Session(
    val userId: String,
    val email: String,
    val fullName: String,
    /** `citizen`, `field_operator`, `ward_officer`, `commissioner`, `admin`. */
    val role: String,
    /** The agency a field operator works for ("PMC Fire Brigade"), when there is one. */
    val operator: String?,
    val wardId: String?,
    val accessToken: String,
    val refreshToken: String,
    /** Epoch ms when [accessToken] expires. */
    val expiresAt: Long,
    /** Epoch ms of the last successful `/auth/me`. */
    val verifiedAt: Long,
) {
    val isStaff: Boolean get() = role in STAFF_ROLES
    val isFieldCrew: Boolean get() = role == "field_operator"

    /** What the person is called on screen: their name, or the part of the email before @. */
    val displayName: String get() = fullName.ifBlank { email.substringBefore('@') }

    /** "Citizen", "Field crew · PMC Fire Brigade", "Ward officer". */
    val roleLabel: String
        get() = when (role) {
            "citizen" -> "Citizen"
            "field_operator" -> "Field crew" + (operator?.let { " · $it" } ?: "")
            "ward_officer" -> "Ward officer"
            "commissioner" -> "Commissioner"
            "admin" -> "Administrator"
            else -> role.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    /** Which PWA screen this person belongs on. */
    val pwaPath: String get() = if (isFieldCrew || isStaff) "/field" else "/citizen"

    /**
     * The role this phone announces on the mesh. A field operator takes their service from
     * the agency name; staff are government. Never COMMAND: that role marks broadcasts the
     * command centre itself sent through a gateway, and a person's phone must not wear it.
     */
    val meshRole: Role
        get() = when {
            isStaff -> Role.GOV
            isFieldCrew -> serviceFor(operator)
            else -> Role.CIVILIAN
        }

    companion object {
        val STAFF_ROLES = setOf("ward_officer", "commissioner", "admin")

        fun serviceFor(operator: String?): Role {
            val o = operator.orEmpty().lowercase()
            return when {
                "fire" in o -> Role.FIRE
                listOf("ambulance", "medical", "hospital", "health", "ems", "108").any { it in o } ->
                    Role.AMBULANCE
                else -> Role.GOV
            }
        }
    }
}

sealed interface SignInResult {
    data class Success(val session: Session) : SignInResult
    data class Failure(val message: String) : SignInResult
}

/** Public settings the command centre hands out (`/api/v1/auth/client-config`). */
data class ClientConfig(val supabaseUrl: String, val anonKey: String, val appUrl: String) {
    val signInAvailable: Boolean get() = supabaseUrl.startsWith("http") && anonKey.isNotBlank()
}

object Account {
    private const val TAG = "Account"
    private const val PREFS = "bitchat_account_v1"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _config = MutableStateFlow<ClientConfig?>(null)
    /** Last config fetched from the command centre (cached, so it is known offline too). */
    val config: StateFlow<ClientConfig?> = _config.asStateFlow()

    /** True once the person chose "Continue without an account" (citizens only). */
    private val _skipped = MutableStateFlow(false)
    val skipped: StateFlow<Boolean> = _skipped.asStateFlow()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = try {
            val key = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREFS, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // No silent downgrade to plain storage for tokens: without the keystore the person
            // simply signs in again each launch.
            Log.w(TAG, "Encrypted storage unavailable: ${e.message}")
            null
        }
        _session.value = load()
        _config.value = loadConfig()
        _skipped.value = prefs?.getBoolean("skipped", false) ?: false
    }

    /** The command centre address the person typed in sign-in or link settings. */
    private fun apiBase(): String =
        appContext?.let { SyncPreferences.getInstance(it).apiBaseUrl }.orEmpty()

    fun setApiBase(url: String) {
        val ctx = appContext ?: return
        val clean = url.trim().trimEnd('/')
        if (clean.isNotEmpty()) SyncPreferences.getInstance(ctx).endpointUrl = clean
    }

    val commandCentreUrl: String get() = apiBase()

    /** Fetch (and cache) the public sign-in settings from the command centre. */
    suspend fun fetchConfig(): ClientConfig? = withContext(Dispatchers.IO) {
        val base = apiBase()
        if (!base.startsWith("http")) return@withContext _config.value
        try {
            http.newCall(Request.Builder().url("$base/api/v1/auth/client-config").get().build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@withContext _config.value
                    val j = JSONObject(r.body?.string().orEmpty())
                    val cfg = ClientConfig(
                        supabaseUrl = j.optString("supabaseUrl").trimEnd('/'),
                        anonKey = j.optString("supabaseAnonKey"),
                        appUrl = j.optString("appUrl").trimEnd('/'),
                    )
                    _config.value = cfg
                    prefs?.edit()
                        ?.putString("cfg_supabase", cfg.supabaseUrl)
                        ?.putString("cfg_anon", cfg.anonKey)
                        ?.putString("cfg_app", cfg.appUrl)
                        ?.apply()
                    cfg
                }
        } catch (e: Exception) {
            Log.i(TAG, "client-config unavailable: ${e.message}")
            _config.value
        }
    }

    suspend fun signIn(email: String, password: String): SignInResult = withContext(Dispatchers.IO) {
        if (!apiBase().startsWith("http")) {
            return@withContext SignInResult.Failure("Enter the command centre address first.")
        }
        val cfg = fetchConfig()
            ?: return@withContext SignInResult.Failure(
                "Can't reach the command centre. Check the address and your connection, or continue offline."
            )
        if (!cfg.signInAvailable) {
            return@withContext SignInResult.Failure("Sign-in isn't set up on this command centre.")
        }
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val token = try {
            http.newCall(
                Request.Builder()
                    .url("${cfg.supabaseUrl}/auth/v1/token?grant_type=password")
                    .header("apikey", cfg.anonKey)
                    .post(body.toRequestBody(JSON))
                    .build()
            ).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 400 || r.code == 401) {
                    return@withContext SignInResult.Failure("That email and password don't match an account.")
                }
                if (!r.isSuccessful) {
                    return@withContext SignInResult.Failure("Sign-in failed (${r.code}). Try again.")
                }
                JSONObject(text)
            }
        } catch (e: Exception) {
            return@withContext SignInResult.Failure("No connection. Sign in when you're back online.")
        }
        val session = resolve(token, email.trim())
            ?: return@withContext SignInResult.Failure(
                "Signed in, but the command centre didn't say what your role is. Try again in a moment."
            )
        save(session)
        setSkipped(false)
        SignInResult.Success(session)
    }

    /** Turn a Supabase token response into a session by asking `/auth/me` who this is. */
    private fun resolve(token: JSONObject, emailHint: String): Session? {
        val access = token.optString("access_token").ifBlank { return null }
        val refresh = token.optString("refresh_token")
        val expiresIn = token.optLong("expires_in", 3600)
        val user = token.optJSONObject("user")
        val me = try {
            http.newCall(
                Request.Builder().url("${apiBase()}/api/v1/auth/me")
                    .header("Authorization", "Bearer $access").get().build()
            ).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null }
        } catch (_: Exception) {
            null
        } ?: return null
        if (!me.optBoolean("authenticated")) return null
        return Session(
            userId = me.optString("userId").ifBlank { user?.optString("id").orEmpty() },
            email = user?.optString("email")?.ifBlank { null } ?: emailHint,
            fullName = me.optString("fullName"),
            role = me.optString("role", "citizen"),
            operator = me.optString("operator").ifBlank { null }?.takeIf { it != "null" },
            wardId = me.optString("wardId").ifBlank { null }?.takeIf { it != "null" },
            accessToken = access,
            refreshToken = refresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            verifiedAt = System.currentTimeMillis(),
        )
    }

    /**
     * A valid access token, refreshed when it is within two minutes of expiry. Null when
     * signed out, or when offline with an expired token (the session itself is kept).
     */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val s = _session.value ?: return@withContext null
        if (s.expiresAt - System.currentTimeMillis() > 120_000) return@withContext s.accessToken
        val cfg = _config.value ?: fetchConfig() ?: return@withContext null
        if (s.refreshToken.isBlank() || !cfg.signInAvailable) return@withContext null
        try {
            http.newCall(
                Request.Builder()
                    .url("${cfg.supabaseUrl}/auth/v1/token?grant_type=refresh_token")
                    .header("apikey", cfg.anonKey)
                    .post(JSONObject().put("refresh_token", s.refreshToken).toString().toRequestBody(JSON))
                    .build()
            ).execute().use { r ->
                if (r.code == 400 || r.code == 401) {
                    // The refresh token was revoked (signed out elsewhere, password changed).
                    signOut()
                    return@withContext null
                }
                if (!r.isSuccessful) return@withContext null
                val j = JSONObject(r.body?.string().orEmpty())
                val next = resolve(j, s.email) ?: s.copy(
                    accessToken = j.optString("access_token", s.accessToken),
                    refreshToken = j.optString("refresh_token", s.refreshToken),
                    expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000,
                )
                save(next)
                next.accessToken
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Re-check the role with the command centre (e.g. after a promotion). Quiet on failure. */
    suspend fun reverify() {
        accessToken()
    }

    fun signOut() {
        _session.value = null
        prefs?.edit()?.remove("session")?.apply()
    }

    fun setSkipped(value: Boolean) {
        _skipped.value = value
        prefs?.edit()?.putBoolean("skipped", value)?.apply()
    }

    // ---------------------------------------------------------------- storage ---

    private fun save(s: Session) {
        _session.value = s
        val j = JSONObject()
            .put("userId", s.userId).put("email", s.email).put("fullName", s.fullName)
            .put("role", s.role).put("operator", s.operator ?: "").put("wardId", s.wardId ?: "")
            .put("access", s.accessToken).put("refresh", s.refreshToken)
            .put("expiresAt", s.expiresAt).put("verifiedAt", s.verifiedAt)
        prefs?.edit()?.putString("session", j.toString())?.apply()
    }

    private fun load(): Session? = try {
        prefs?.getString("session", null)?.let { raw ->
            val j = JSONObject(raw)
            Session(
                userId = j.getString("userId"),
                email = j.optString("email"),
                fullName = j.optString("fullName"),
                role = j.optString("role", "citizen"),
                operator = j.optString("operator").ifBlank { null },
                wardId = j.optString("wardId").ifBlank { null },
                accessToken = j.optString("access"),
                refreshToken = j.optString("refresh"),
                expiresAt = j.optLong("expiresAt"),
                verifiedAt = j.optLong("verifiedAt"),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun loadConfig(): ClientConfig? {
        val p = prefs ?: return null
        val url = p.getString("cfg_supabase", null) ?: return null
        return ClientConfig(url, p.getString("cfg_anon", "").orEmpty(), p.getString("cfg_app", "").orEmpty())
    }
}
