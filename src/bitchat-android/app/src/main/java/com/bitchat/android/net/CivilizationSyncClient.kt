package com.bitchat.android.net

import android.content.Context
import android.util.Log
import com.bitchat.android.services.SyncPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Posts the structured "civilization bridge" bundle to the user-configured REST
 * endpoint when internet becomes available. Honors the Tor preference by
 * routing through the local SOCKS5 proxy when ArtiTor is enabled.
 */
class CivilizationSyncClient(context: Context) {

    companion object {
        private const val TAG = "CivilizationSyncClient"
        private const val JSON = "application/json; charset=utf-8"
        private const val TOR_SOCKS_PORT = 39060

        private fun torListening(): Boolean = try {
            java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT), 300); true }
        } catch (_: Exception) {
            false
        }
    }

    private val appContext = context.applicationContext
    private val prefs = SyncPreferences.getInstance(context)

    private fun client(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        // "When available" means exactly that: only use the Arti SOCKS port if something is
        // listening on it. Before, the proxy was set whenever the switch was on, so with Tor
        // not running every call failed with "failed to connect to /127.0.0.1 (port 39060)".
        if (prefs.useTor && torListening()) {
            try {
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT)))
            } catch (_: Exception) { }
        }
        return builder.build()
    }

    /**
     * Returns true on HTTP 2xx. Updates [SyncPreferences.lastSyncedAt] and
     * [SyncPreferences.lastSyncError] accordingly.
     */
    fun postBundle(json: String): Boolean {
        val url = prefs.bundleUrl
        if (url.isEmpty() || !url.startsWith("http")) {
            prefs.lastSyncError = "endpoint disabled or malformed"
            return false
        }
        return try {
            val builder = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON.toMediaType()))
                .addHeader("Content-Type", JSON)
            if (prefs.gatewayKey.isNotBlank()) builder.addHeader("X-Mesh-Gateway-Key", prefs.gatewayKey)
            val request = builder.build()
            client().newCall(request).execute().use { response ->
                val ok = response.code in 200..299
                prefs.lastSyncedAt = System.currentTimeMillis()
                prefs.lastSyncError = if (ok) null else "HTTP ${response.code}"
                Log.i(TAG, "Sync post $url -> ${response.code}")
                ok
            }
        } catch (e: Exception) {
            prefs.lastSyncError = e.message?.take(200)
            Log.w(TAG, "Sync post failed: ${e.message}")
            false
        }
    }

    /**
     * Plain request against the command centre API (`<base><path>`), with the gateway key.
     * Returns (HTTP code, body); code -1 when the request never completed.
     */
    fun call(method: String, path: String, body: String? = null, auth: Boolean = true): Pair<Int, String> {
        val base = prefs.apiBaseUrl
        if (base.isEmpty() || !base.startsWith("http")) return -1 to "no command centre configured"
        return try {
            val builder = Request.Builder().url(base + path).addHeader("Accept", "application/json")
            if (auth && prefs.gatewayKey.isNotBlank()) builder.addHeader("X-Mesh-Gateway-Key", prefs.gatewayKey)
            if (method == "POST") builder.post((body ?: "{}").toRequestBody(JSON.toMediaType())) else builder.get()
            client().newCall(builder.build()).execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed: ${e.message}")
            -1 to (e.message ?: "error")
        }
    }

    /** Any absolute URL (used for route lookups while online). */
    fun getAbsolute(url: String): Pair<Int, String> = try {
        client().newCall(Request.Builder().url(url).get().build()).execute()
            .use { r -> r.code to (r.body?.string() ?: "") }
    } catch (e: Exception) {
        -1 to (e.message ?: "error")
    }
}