package com.bitchat.android.shell

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.bitchat.android.account.Account
import com.bitchat.android.vlm.IndradhanuGateway
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * `window.BiChatNative` inside the online app.
 *
 * Lets the Indradhanu PWA, when it runs inside BiChat, do what a browser cannot: sign in with
 * the phone's session, hand its place over before the switch to the mesh, and send or read
 * mesh packets directly (instead of through bitchat's loopback HTTP API, which a page served
 * over HTTPS cannot reach from a WebView).
 *
 * Every method answers only to pages from the command centre's own app origin: the session
 * token is never handed to anything else the WebView might end up showing.
 */
class BiChatBridge(
    private val allowedOrigin: String,
    private val currentUrl: () -> String?,
    private val onSwitchToMesh: () -> Unit,
    private val onSendText: (String) -> Unit,
    private val peerCount: () -> Int,
    private val onSignedOut: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    private fun trusted(): Boolean {
        val url = currentUrl() ?: return false
        return originOf(url) == allowedOrigin
    }

    @JavascriptInterface
    fun isNative(): Boolean = true

    /** `{accessToken, refreshToken, expiresAt}` for supabase-js `setSession`, or "". */
    @JavascriptInterface
    fun session(): String {
        if (!trusted()) return ""
        val token = runBlocking { Account.accessToken() } ?: return ""
        val s = Account.session.value ?: return ""
        return JSONObject()
            .put("accessToken", token)
            .put("refreshToken", s.refreshToken)
            .put("expiresAt", s.expiresAt / 1000)
            .put("email", s.email)
            .toString()
    }

    @JavascriptInterface
    fun getState(): String = if (trusted()) Handoff.state.value.toJson().toString() else "{}"

    /** Partial update: only the keys present change. */
    @JavascriptInterface
    fun saveState(json: String) {
        if (!trusted()) return
        try { Handoff.merge(JSONObject(json)) } catch (_: Exception) { }
    }

    @JavascriptInterface
    fun switchToMesh() {
        if (!trusted()) return
        main.post(onSwitchToMesh)
    }

    @JavascriptInterface
    fun signedOut() {
        if (!trusted()) return
        main.post(onSignedOut)
    }

    @JavascriptInterface
    fun meshStatus(): String = JSONObject()
        .put("api_enabled", true)
        .put("peers_count", peerCount())
        .toString()

    /** Broadcast [text] on the mesh (and queue it for the gateway). */
    @JavascriptInterface
    fun sendText(text: String): Boolean {
        if (!trusted() || text.isBlank() || text.length > 4000) return false
        main.post {
            onSendText(text)
            try { IndradhanuGateway.onLocalSend(text) } catch (_: Exception) { }
        }
        return true
    }

    /** What the mesh delivered since [since]: `{messages: [{seq, text, at_ms}], next}`. */
    @JavascriptInterface
    fun inbox(since: Long): String {
        if (!trusted()) return """{"messages":[],"next":$since}"""
        val items = IndradhanuGateway.inboxSince(since)
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("seq", it.seq).put("text", it.text).put("at_ms", it.atMs)) }
        return JSONObject().put("messages", arr).put("next", items.lastOrNull()?.seq ?: since).toString()
    }

    companion object {
        fun originOf(url: String): String = try {
            val u = Uri.parse(url)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme}://${u.host}$port".lowercase()
        } catch (_: Exception) {
            ""
        }
    }
}
