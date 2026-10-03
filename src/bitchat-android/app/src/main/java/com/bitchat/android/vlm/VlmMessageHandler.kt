package com.bitchat.android.vlm

import android.content.Context
import android.util.Log
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

object VlmMessageHandler {
    private const val TAG = "VlmMessageHandler"
    private const val MAX_TEXT_LENGTH = 16000
    private val MAX_FILE_SIZE = AppConstants.Media.MAX_FILE_SIZE_BYTES

    private val lastSendTime = AtomicLong(0)

    fun initialize(context: Context) {
        VlmSettingsManager.initialize(context)
    }

    fun isRateLimited(): Boolean {
        val settings = VlmSettingsManager.settings.value
        if (settings.rateLimitMs <= 0) return false

        val now = System.currentTimeMillis()
        val last = lastSendTime.get()
        val elapsed = now - last

        return elapsed < settings.rateLimitMs
    }

    fun updateLastSendTime() {
        lastSendTime.set(System.currentTimeMillis())
    }

    fun validateText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (text.length > MAX_TEXT_LENGTH) return false
        return true
    }

    fun validateImage(imageBytes: ByteArray?): Boolean {
        if (imageBytes == null || imageBytes.isEmpty()) return false
        if (imageBytes.size > MAX_FILE_SIZE) return false
        return true
    }

    fun resolveDestination(
        peerId: String?,
        channel: String?
    ): Pair<String?, String?> {
        val settings = VlmSettingsManager.settings.value
        val defaultDest = settings.defaultDestination

        return when {
            peerId != null -> Pair(peerId, null)
            channel != null -> Pair(null, channel)
            defaultDest != null -> {
                if (defaultDest.type == "peer") {
                    Pair(defaultDest.id, null)
                } else {
                    Pair(null, defaultDest.id)
                }
            }
            else -> Pair(null, null)
        }
    }

    /**
     * Category a VLM brief is broadcast under, so the alert chips can sort it: fire and
     * smoke go to Fire, casualties to Ambulance, collapse/flood/violence to Gov.
     */
    fun categoryFor(text: String): com.bitchat.android.model.Role =
        when (com.bitchat.android.services.SyncBundleBuilder.hazardKind(text)) {
            "fire", "smoke" -> com.bitchat.android.model.Role.FIRE
            "medical" -> com.bitchat.android.model.Role.AMBULANCE
            "collapse", "flood", "assault" -> com.bitchat.android.model.Role.GOV
            else -> com.bitchat.android.model.Role.UNSET
        }

    fun sendTextMessage(
        context: Context,
        text: String,
        peerId: String? = null,
        channel: String? = null,
        category: com.bitchat.android.model.Role? = null
    ): VlmResult {
        VlmSettingsManager.initialize(context)

        if (!VlmSettingsManager.isEnabled()) {
            return VlmResult.Error("VLM API is disabled")
        }

        if (VlmSettingsManager.isSilenceEnabled()) {
            return VlmResult.Error("Silence mode is enabled")
        }

        if (!validateText(text)) {
            return VlmResult.Error("Invalid text: empty or too long")
        }

        if (isRateLimited()) {
            return VlmResult.Error("Rate limited - please wait")
        }

        val (resolvedPeerId, resolvedChannel) = resolveDestination(peerId, channel)

        return try {
            val mesh = MeshServiceHolder.getUnifiedOrCreate(context)
            val messageId = "vlm-${System.currentTimeMillis()}"
            val nickname = try { 
                com.bitchat.android.services.AppStateStore.nickname.value 
            } catch (_: Exception) { 
                "VLM" 
            }

            if (resolvedPeerId != null) {
                val peerNickname = mesh.getPeerNicknames()[resolvedPeerId] ?: resolvedPeerId
                mesh.sendPrivateMessage(text, resolvedPeerId, peerNickname, messageId)
                updateLastSendTime()
                
                val message = BitchatMessage(
                    id = messageId,
                    sender = nickname,
                    senderPeerID = null,
                    content = text,
                    timestamp = Date(),
                    isRelay = false,
                    deliveryStatus = DeliveryStatus.Sent
                )
                AppStateStore.addPrivateMessage(resolvedPeerId, message)
                
                Log.i(TAG, "Sent private message to $resolvedPeerId")
                VlmResult.Success(messageId, null)
            } else {
                val cat = category ?: categoryFor(text)
                mesh.sendMessage(text, emptyList(), resolvedChannel, cat)
                updateLastSendTime()
                
                val message = BitchatMessage(
                    id = messageId,
                    sender = nickname,
                    senderPeerID = null,
                    content = text,
                    timestamp = Date(),
                    isRelay = false,
                    deliveryStatus = DeliveryStatus.Sent,
                    category = cat
                )
                if (resolvedChannel != null) {
                    AppStateStore.addChannelMessage(resolvedChannel, message)
                } else {
                    AppStateStore.addPublicMessage(message)
                }
                
                Log.i(TAG, "Sent broadcast message${resolvedChannel?.let { " to channel $it" } ?: ""}")
                // A report this phone's user sent should be forwarded by this phone too,
                // not only by the neighbours (the mesh does not echo our own messages).
                try { IndradhanuGateway.onLocalSend(text) } catch (_: Exception) { }
                VlmResult.Success(messageId, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message: ${e.message}", e)
            VlmResult.Error("Failed to send: ${e.message}")
        }
    }

    suspend fun sendImageMessage(
        context: Context,
        imageBytes: ByteArray,
        caption: String? = null,
        peerId: String? = null,
        channel: String? = null
    ): VlmResult = withContext(Dispatchers.IO) {
        VlmSettingsManager.initialize(context)

        if (!VlmSettingsManager.isEnabled()) {
            return@withContext VlmResult.Error("VLM API is disabled")
        }

        if (VlmSettingsManager.isSilenceEnabled()) {
            return@withContext VlmResult.Error("Silence mode is enabled")
        }

        if (!validateImage(imageBytes)) {
            return@withContext VlmResult.Error("Invalid image: empty or too large")
        }

        if (isRateLimited()) {
            return@withContext VlmResult.Error("Rate limited - please wait")
        }

        val (resolvedPeerId, resolvedChannel) = resolveDestination(peerId, channel)

        return@withContext try {
            val mesh = MeshServiceHolder.getUnifiedOrCreate(context)
            val messageId = "vlm-${System.currentTimeMillis()}"
            val nickname = try { 
                com.bitchat.android.services.AppStateStore.nickname.value 
            } catch (_: Exception) { 
                "VLM" 
            }

            val fileName = "vlm_image_${System.currentTimeMillis()}.jpg"
            val mimeType = detectMimeType(imageBytes)

            val filePacket = BitchatFilePacket(
                fileName = fileName,
                fileSize = imageBytes.size.toLong(),
                mimeType = mimeType,
                content = imageBytes
            )

            val transferId = sha256Hex(filePacket.encode() ?: imageBytes)

            if (resolvedPeerId != null) {
                sendPrivateImage(mesh, resolvedPeerId, filePacket, transferId)
                
                val message = BitchatMessage(
                    id = messageId,
                    sender = nickname,
                    senderPeerID = null,
                    content = caption ?: "[Image]",
                    timestamp = Date(),
                    isRelay = false,
                    deliveryStatus = DeliveryStatus.Sent
                )
                AppStateStore.addPrivateMessage(resolvedPeerId, message)
            } else {
                mesh.sendFileBroadcast(filePacket)
                
                val message = BitchatMessage(
                    id = messageId,
                    sender = nickname,
                    senderPeerID = null,
                    content = caption ?: "[Image]",
                    timestamp = Date(),
                    isRelay = false,
                    deliveryStatus = DeliveryStatus.Sent
                )
                if (resolvedChannel != null) {
                    AppStateStore.addChannelMessage(resolvedChannel, message)
                } else {
                    AppStateStore.addPublicMessage(message)
                }
            }

            updateLastSendTime()
            Log.i(TAG, "Sent image${resolvedPeerId?.let { " to $it" } ?: " broadcast"}")

            VlmResult.Success(messageId, transferId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send image: ${e.message}", e)
            VlmResult.Error("Failed to send image: ${e.message}")
        }
    }

    private suspend fun sendPrivateImage(
        mesh: MeshService,
        peerId: String,
        filePacket: BitchatFilePacket,
        transferId: String
    ) {
        var retries = 0
        val maxRetries = 30
        val retryDelay = 500L

        while (retries < maxRetries) {
            when (val prep = mesh.prepareFilePrivate(peerId, filePacket, transferId, allowLegacyFallback = false)) {
                is PrivateMediaPreparation.Ready -> {
                    val committed = prep.transfer.commit()
                    if (!committed) {
                        throw Exception("Failed to commit file transfer")
                    }
                    return
                }
                PrivateMediaPreparation.AwaitingPeerState,
                PrivateMediaPreparation.NeedsHandshake -> {
                    if (prep == PrivateMediaPreparation.NeedsHandshake) {
                        mesh.initiateNoiseHandshake(peerId)
                    }
                    Thread.sleep(retryDelay)
                    retries++
                }
                is PrivateMediaPreparation.RequiresLegacyConsent -> {
                    throw Exception("Legacy consent required - not supported for VLM")
                }
                is PrivateMediaPreparation.Rejected -> {
                    throw Exception("File preparation rejected: ${prep.reason}")
                }
                else -> {
                    Thread.sleep(retryDelay)
                    retries++
                }
            }
        }
        throw Exception("Timeout waiting for peer state")
    }

    suspend fun sendAnalysis(
        context: Context,
        imageBytes: ByteArray?,
        description: String,
        peerId: String? = null,
        channel: String? = null
    ): VlmResult {
        VlmSettingsManager.initialize(context)

        if (!VlmSettingsManager.isEnabled()) {
            return VlmResult.Error("VLM API is disabled")
        }

        if (VlmSettingsManager.isSilenceEnabled()) {
            return VlmResult.Error("Silence mode is enabled")
        }

        return if (imageBytes != null && imageBytes.isNotEmpty()) {
            sendImageMessage(context, imageBytes, description, peerId, channel)
        } else {
            sendTextMessage(context, description, peerId, channel)
        }
    }

    fun getStatus(context: Context): Map<String, Any?> {
        VlmSettingsManager.initialize(context)
        val settings = VlmSettingsManager.settings.value
        val mesh = try {
            MeshServiceHolder.unifiedMeshService
        } catch (e: Exception) {
            null
        }
        
        val wifiIp = VlmApiService.getWifiIpAddress(context)

        return mapOf(
            "api_enabled" to settings.enabled,
            "http_port" to settings.httpPort,
            "silence_enabled" to settings.silenceEnabled,
            "default_destination" to (settings.defaultDestination?.let { dest ->
                mapOf("type" to dest.type, "id" to dest.id)
            }),
            "mesh_running" to (mesh != null),
            "peers_count" to (mesh?.getActivePeerCount() ?: 0),
            "rate_limit_ms" to settings.rateLimitMs,
            "wifi_ip" to wifiIp,
            "wifi_access_url" to if (wifiIp != "0.0.0.0") {
                "http://$wifiIp:${settings.httpPort}"
            } else null
        )
    }

    private fun detectMimeType(bytes: ByteArray): String {
        if (bytes.size < 4) return "image/jpeg"

        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "image/gif"
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun processImageFromPath(path: String): ByteArray? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read image from path: ${e.message}")
            null
        }
    }

    fun processImageFromBase64(base64: String): ByteArray? {
        return try {
            val cleanBase64 = if (base64.contains(",")) {
                base64.substringAfter(",")
            } else {
                base64
            }
            android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64 image: ${e.message}")
            null
        }
    }
}

sealed class VlmResult {
    data class Success(
        val messageId: String,
        val transferId: String?
    ) : VlmResult()

    data class Error(
        val message: String
    ) : VlmResult()
}
