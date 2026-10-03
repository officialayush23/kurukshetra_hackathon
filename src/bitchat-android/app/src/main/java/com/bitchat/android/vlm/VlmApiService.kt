package com.bitchat.android.vlm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

class VlmApiService(
    private val context: Context,
    private val vlmPort: Int = 8765
) : NanoHTTPD("0.0.0.0", vlmPort) {

    companion object {
        private const val TAG = "VlmApiService"
        const val ACTION_START = "com.bitchat.android.vlm.VLM_API_START"
        const val ACTION_STOP = "com.bitchat.android.vlm.VLM_API_STOP"
        const val ACTION_IP_CHANGED = "com.bitchat.android.vlm.VLM_IP_CHANGED"
        private const val SOCKET_READ_TIMEOUT = 10000
        
        fun getWifiIpAddress(context: Context): String {
            return try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ipInt = wifiInfo.ipAddress
                
                if (ipInt != 0) {
                    val ipBytes = byteArrayOf(
                        (ipInt and 0xFF).toByte(),
                        (ipInt shr 8 and 0xFF).toByte(),
                        (ipInt shr 16 and 0xFF).toByte(),
                        (ipInt shr 24 and 0xFF).toByte()
                    )
                    InetAddress.getByAddress(ipBytes).hostAddress ?: "0.0.0.0"
                } else {
                    Log.w(TAG, "WiFi not connected or no IP assigned")
                    "0.0.0.0"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get WiFi IP: ${e.message}")
                "0.0.0.0"
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var connectivityReceiver: BroadcastReceiver? = null
    private var lastKnownWifiIp: String = "0.0.0.0"

    override fun start() {
        try {
            val wifiIp = getWifiIpAddress(context)
            lastKnownWifiIp = wifiIp
            
            super.start(SOCKET_READ_TIMEOUT, true)
            
            if (wifiIp != "0.0.0.0") {
                Log.i(TAG, "VLM API server started - listening on 0.0.0.0:$vlmPort (WiFi: http://$wifiIp:$vlmPort)")
            } else {
                Log.i(TAG, "VLM API server started - listening on 0.0.0.0:$vlmPort (no WiFi, use ADB forward)")
            }
            
            registerConnectivityReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VLM API server: ${e.message}", e)
            throw e
        }
    }
    
    private fun registerConnectivityReceiver() {
        connectivityReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                handleWifiStateChange()
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        
        try {
            context.registerReceiver(connectivityReceiver, filter)
            Log.d(TAG, "Connectivity receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register connectivity receiver: ${e.message}")
        }
    }
    
    private fun handleWifiStateChange() {
        try {
            val newIp = getWifiIpAddress(context)
            
            if (newIp != lastKnownWifiIp) {
                Log.i(TAG, "WiFi IP changed: $lastKnownWifiIp -> $newIp, updating status...")
                
                val oldIp = lastKnownWifiIp
                lastKnownWifiIp = newIp
                
                val intent = Intent(ACTION_IP_CHANGED).apply {
                    putExtra("old_ip", oldIp)
                    putExtra("new_ip", newIp)
                    putExtra("port", vlmPort)
                }
                context.sendBroadcast(intent)
                
                if (newIp != "0.0.0.0") {
                    Log.i(TAG, "WiFi connected - VLM API accessible at http://$newIp:$vlmPort")
                } else {
                    Log.i(TAG, "WiFi disconnected - VLM API accessible via ADB forward tcp:$vlmPort tcp:$vlmPort")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling WiFi state change: ${e.message}")
        }
    }

    override fun stop() {
        try {
            connectivityReceiver?.let {
                context.unregisterReceiver(it)
                connectivityReceiver = null
                Log.d(TAG, "Connectivity receiver unregistered")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connectivity receiver not registered or already unregistered")
        }
        
        super.stop()
        Log.i(TAG, "VLM API server stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                method == Method.GET && uri == "/status" -> handleStatus()
                method == Method.GET && uri == "/settings" -> handleGetSettings()
                method == Method.POST && uri == "/settings" -> handleUpdateSettings(session)
                method == Method.POST && uri == "/send/text" -> handleSendText(session)
                method == Method.POST && uri == "/send/image" -> handleSendImage(session)
                method == Method.POST && uri == "/send/analysis" -> handleSendAnalysis(session)
                method == Method.POST && uri == "/silence" -> handleSetSilence(session)
                // Command centre link: read what the mesh delivered, and configure the link.
                method == Method.GET && uri == "/inbox" -> handleInbox(session)
                method == Method.GET && uri == "/gateway" -> jsonResponse(
                    Response.Status.OK, mapOf("status" to "ok") + IndradhanuGateway.settingsJson())
                method == Method.POST && uri == "/gateway" -> handleGateway(session)
                method == Method.OPTIONS -> handleCorsPreflight()
                else -> jsonResponse(Response.Status.NOT_FOUND, mapOf(
                    "status" to "error",
                    "error" to "Not found"
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request: ${e.message}", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to (e.message ?: "Internal server error")
            ))
        }
    }

    private fun handleStatus(): Response {
        val status = VlmMessageHandler.getStatus(context)
        return jsonResponse(Response.Status.OK, mapOf("status" to "ok") + status)
    }

    private fun handleGetSettings(): Response {
        val settings = VlmSettingsManager.settings.value
        return jsonResponse(Response.Status.OK, mapOf(
            "status" to "ok",
            "enabled" to settings.enabled,
            "http_port" to settings.httpPort,
            "silence_enabled" to settings.silenceEnabled,
            "rate_limit_ms" to settings.rateLimitMs,
            "default_destination" to (settings.defaultDestination?.let { dest ->
                mapOf("type" to dest.type, "id" to dest.id)
            })
        ))
    }

    private fun handleUpdateSettings(session: IHTTPSession): Response {
        val json = parseJsonBody(session)

        json?.optBoolean("enabled")?.let { VlmSettingsManager.setEnabled(it) }
        json?.optInt("http_port")?.takeIf { it in 1024..65535 }?.let { VlmSettingsManager.setHttpPort(it) }
        json?.optLong("rate_limit_ms")?.takeIf { it >= 0 }?.let { VlmSettingsManager.setRateLimitMs(it) }
        json?.optBoolean("silence_enabled")?.let { VlmSettingsManager.setSilenceEnabled(it) }

        val destObj = json?.optJSONObject("default_destination")
        if (destObj != null) {
            val type = destObj.optString("type", null)
            val id = destObj.optString("id", null)
            if (type != null && id != null) {
                VlmSettingsManager.setDefaultDestination(VlmDestination(type, id))
            }
        } else if (json?.has("default_destination") == true && json.isNull("default_destination")) {
            VlmSettingsManager.setDefaultDestination(null)
        }

        return handleGetSettings()
    }

    private fun handleSendText(session: IHTTPSession): Response {
        if (!VlmSettingsManager.isEnabled()) {
            return jsonResponse(Response.Status.FORBIDDEN, mapOf(
                "status" to "error",
                "error" to "VLM API is disabled"
            ))
        }

        val json = parseJsonBody(session)
        if (json == null) {
            return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "status" to "error",
                "error" to "Invalid JSON body"
            ))
        }

        val text = json.optString("text", null)
        if (text.isNullOrBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "status" to "error",
                "error" to "Missing required field: text"
            ))
        }

        val peerId = if (json.has("peer_id") && !json.isNull("peer_id")) json.optString("peer_id", null) else null
        val channel = if (json.has("channel") && !json.isNull("channel")) json.optString("channel", null) else null
        // Optional: where the camera is, and an explicit category (FIRE, AMBULANCE, ...).
        val lat = json.optDouble("lat", Double.NaN)
        val lon = json.optDouble("lon", Double.NaN)
        val body = if (!lat.isNaN() && !lon.isNaN() && !text.contains("geo:"))
            text + " geo:%.5f,%.5f".format(java.util.Locale.US, lat, lon) else text
        val category = json.optString("category", "").uppercase().takeIf { it.isNotBlank() }?.let {
            try { com.bitchat.android.model.Role.valueOf(it) } catch (_: Exception) { null }
        }

        val result = VlmMessageHandler.sendTextMessage(context, body, peerId, channel, category)

        return when (result) {
            is VlmResult.Success -> jsonResponse(Response.Status.OK, mapOf(
                "status" to "ok",
                "message_id" to result.messageId
            ))
            is VlmResult.Error -> jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "status" to "error",
                "error" to result.message
            ))
        }
    }

    private fun handleSendImage(session: IHTTPSession): Response {
        if (!VlmSettingsManager.isEnabled()) {
            return jsonResponse(Response.Status.FORBIDDEN, mapOf(
                "status" to "error",
                "error" to "VLM API is disabled"
            ))
        }

        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            val imageBytes = files["image"]?.let { readUploadedFile(it) }
            val params = session.parameters

            val imageBase64Param = params["image_base64"]?.firstOrNull()
            
            val json = try {
                files["postData"]?.let { JSONObject(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse JSON: ${e.message}")
                null
            }
            val imageBase64Json = json?.optString("image_base64")

            val bytes = when {
                imageBytes != null && imageBytes.isNotEmpty() -> imageBytes
                !imageBase64Param.isNullOrBlank() -> VlmMessageHandler.processImageFromBase64(imageBase64Param)
                !imageBase64Json.isNullOrBlank() -> VlmMessageHandler.processImageFromBase64(imageBase64Json)
                else -> null
            }

            if (bytes == null || bytes.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                    "status" to "error",
                    "error" to "Missing image: provide 'image' file or 'image_base64'"
                ))
            }

            val caption = params["caption"]?.firstOrNull() ?: json?.optString("caption")
            val peerId = params["peer_id"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: json?.optString("peer_id")?.takeIf { it.isNotBlank() }
            val channel = params["channel"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: json?.optString("channel")?.takeIf { it.isNotBlank() }

            serviceScope.launch {
                VlmMessageHandler.sendImageMessage(context, bytes, caption, peerId, channel)
            }

            jsonResponse(Response.Status.OK, mapOf(
                "status" to "ok",
                "message" to "Image send initiated"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error handling image upload: ${e.message}", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to "Failed to process image: ${e.message}"
            ))
        }
    }

    private fun handleSendAnalysis(session: IHTTPSession): Response {
        if (!VlmSettingsManager.isEnabled()) {
            return jsonResponse(Response.Status.FORBIDDEN, mapOf(
                "status" to "error",
                "error" to "VLM API is disabled"
            ))
        }

        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            val imageBytes = files["image"]?.let { readUploadedFile(it) }
            val params = session.parameters

            val imageBase64Param = params["image_base64"]?.firstOrNull()
            
            val json = try {
                files["postData"]?.let { JSONObject(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse JSON: ${e.message}")
                null
            }
            val imageBase64Json = json?.optString("image_base64")

            val bytes = when {
                imageBytes != null && imageBytes.isNotEmpty() -> imageBytes
                !imageBase64Param.isNullOrBlank() -> VlmMessageHandler.processImageFromBase64(imageBase64Param)
                !imageBase64Json.isNullOrBlank() -> VlmMessageHandler.processImageFromBase64(imageBase64Json)
                else -> null
            }

            val description = params["description"]?.firstOrNull() 
                ?: params["caption"]?.firstOrNull()
                ?: json?.optString("description")
                ?: json?.optString("caption")
                ?: ""

            if (bytes == null && description.isBlank()) {
                return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                    "status" to "error",
                    "error" to "Missing image or description"
                ))
            }

            val peerId = params["peer_id"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: json?.optString("peer_id")?.takeIf { it.isNotBlank() }
            val channel = params["channel"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: json?.optString("channel")?.takeIf { it.isNotBlank() }

            serviceScope.launch {
                VlmMessageHandler.sendAnalysis(context, bytes, description, peerId, channel)
            }

            jsonResponse(Response.Status.OK, mapOf(
                "status" to "ok",
                "message" to "Analysis send initiated"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error handling analysis: ${e.message}", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to "Failed to process analysis: ${e.message}"
            ))
        }
    }

    private fun handleSetSilence(session: IHTTPSession): Response {
        val json = parseJsonBody(session)
        val enabled = json?.optBoolean("enabled") ?: true

        VlmSettingsManager.setSilenceEnabled(enabled)

        return jsonResponse(Response.Status.OK, mapOf(
            "status" to "ok",
            "silence_enabled" to enabled
        ))
    }

    /**
     * `GET /inbox?since=<seq>`: IDX1 packets this phone has heard, oldest first.
     * Open while the VLM API is enabled, like every other endpoint here: it only
     * ever returns messages that were broadcast in the clear on the mesh.
     */
    private fun handleInbox(session: IHTTPSession): Response {
        if (!VlmSettingsManager.isEnabled()) {
            return jsonResponse(Response.Status.FORBIDDEN, mapOf(
                "status" to "error", "error" to "VLM API is disabled"))
        }
        val since = session.parameters["since"]?.firstOrNull()?.toLongOrNull() ?: 0L
        val items = IndradhanuGateway.inboxSince(since)
        val arr = org.json.JSONArray()
        items.forEach {
            arr.put(JSONObject()
                .put("seq", it.seq).put("text", it.text).put("sender", it.sender)
                .put("channel", it.channel ?: JSONObject.NULL).put("at_ms", it.atMs))
        }
        val body = JSONObject()
            .put("status", "ok")
            .put("messages", arr)
            .put("next", items.lastOrNull()?.seq ?: since)
            .put("latest", IndradhanuGateway.latestSeq())
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        response.addHeader("Access-Control-Allow-Private-Network", "true")
        return response
    }

    /** `POST /gateway` {enabled, api_url, gateway_key, city_id, listen}. */
    private fun handleGateway(session: IHTTPSession): Response {
        if (!VlmSettingsManager.isEnabled()) {
            return jsonResponse(Response.Status.FORBIDDEN, mapOf(
                "status" to "error", "error" to "VLM API is disabled"))
        }
        val json = parseJsonBody(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST, mapOf("status" to "error", "error" to "Invalid JSON body"))
        IndradhanuGateway.update(json)
        IndradhanuGateway.kick()
        return jsonResponse(Response.Status.OK, mapOf("status" to "ok") + IndradhanuGateway.settingsJson())
    }

    private fun handleCorsPreflight(): Response {
        return jsonResponse(Response.Status.OK, mapOf("status" to "ok"))
    }

    private fun parseJsonBody(session: IHTTPSession): JSONObject? {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            
            val body = session.queryParameterString
            if (body.isNullOrBlank()) {
                files["postData"]?.let { JSONObject(it) }
            } else {
                JSONObject(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON body: ${e.message}")
            null
        }
    }

    private fun readUploadedFile(tempPath: String): ByteArray? {
        return try {
            val file = File(tempPath)
            if (!file.exists()) return null
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read uploaded file: ${e.message}")
            null
        }
    }

    private fun jsonResponse(status: Response.Status, data: Map<String, Any?>): Response {
        val json = JSONObject(data)
        val response = newFixedLengthResponse(status, "application/json", json.toString())
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        // Lets a web app served over https on this phone call this loopback API
        // (Chrome's Private Network Access preflight).
        response.addHeader("Access-Control-Allow-Private-Network", "true")
        return response
    }
}
