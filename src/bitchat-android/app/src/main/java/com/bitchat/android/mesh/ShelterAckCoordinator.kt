package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.model.AckPayload
import com.bitchat.android.model.AckStatus
import com.bitchat.android.model.Role
import com.bitchat.android.model.Shelter
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.model.ShelterStatus
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ShelterRegistry
import com.bitchat.android.sync.PacketIdUtil
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Transport-agnostic coordinator that owns the broadcast-ack path and the
 * shelter-registry gossip path. Both [MeshCore] (Wi-Fi Aware) and
 * [BluetoothMeshService] (BLE) hold an instance so behavior stays identical
 * regardless of which transport carries a given packet.
 */
class ShelterAckCoordinator(
    private val context: Context,
    private val myPeerID: String,
    private val maxTtl: UByte,
    private val signBeforeBroadcast: (BitchatPacket) -> BitchatPacket,
    private val broadcastPacket: (BitchatPacket) -> Unit,
    private val sendToPeer: (peerID: String, packet: BitchatPacket) -> Boolean,
    private val transportIdForBridge: String,
    private val bridgeSendToPeer: (transportId: String, peerID: String, packet: BitchatPacket) -> Unit,
    private val subscribedRolesProvider: () -> Set<Role>,
    private val lookupPeerInfo: (String) -> PeerInfo?,
    private val onShelterReceived: (Shelter, Boolean) -> Unit,
    private val onBroadcastAck: (String, String, Int, Boolean) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val shelterRegistry: ShelterRegistry? by lazy {
        try { ShelterRegistry.getInstance(context.applicationContext) } catch (_: Exception) { null }
    }

    /**
     * Record that we received a broadcast MESSAGE from [routed.peerID] and emit
     * a source-routed ACK back to that peer so the original sender can visualize
     * the message's progress through the mesh.
     */
    fun handleMessageIngress(routed: RoutedPacket) {
        val pkt = routed.packet
        val fromPeerID = routed.peerID ?: return
        if (fromPeerID == myPeerID) return
        if (pkt.type != MessageType.MESSAGE.value) return
        val isBroadcast = pkt.recipientID == null || pkt.recipientID.contentEquals(SpecialRecipients.BROADCAST)
        if (!isBroadcast) return
        try {
            val messageID = PacketIdUtil.computeIdHex(pkt)
            AckPathCache.record(messageID, fromPeerID)
            val category = pkt.category
            val subscribed = subscribedRolesProvider()
            val reachedResponder = category != Role.UNSET && category in subscribed
            val maxTtlInt = maxTtl.toInt().coerceAtLeast(1)
            val hopCount = (maxTtlInt - pkt.ttl.toInt() + 1).coerceIn(1, 255)
            sendBroadcastAck(messageID, fromPeerID, hopCount, reachedResponder)
        } catch (_: Exception) { }
    }

    fun sendBroadcastAck(
        messageID: String,
        upstreamPeerID: String,
        hopCount: Int,
        reachedResponder: Boolean
    ) {
        try {
            val status = if (reachedResponder) AckStatus.REACHED_RESPONDER else AckStatus.RELAYED
            val payload = AckPayload(status, hopCount, messageID).encode() ?: return
            val ackPacket = BitchatPacket(
                version = 2u,
                type = MessageType.ACK.value,
                senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                recipientID = MeshPacketUtils.hexStringToByteArray(upstreamPeerID),
                timestamp = System.currentTimeMillis().toULong(),
                payload = payload,
                signature = null,
                ttl = 1u
            )
            val signed = signBeforeBroadcast(ackPacket)
            val sent = sendToPeer(upstreamPeerID, signed)
            bridgeSendToPeer(transportIdForBridge, upstreamPeerID, signed)
            if (!sent) {
                broadcastPacket(signed.copy(ttl = 0u))
            }
        } catch (_: Exception) { }
    }

    fun handleAckPacket(routed: RoutedPacket) {
        val pkt = routed.packet
        val ackerPeerID = routed.peerID ?: return
        if (ackerPeerID == myPeerID) return
        val ack = AckPayload.decode(pkt.payload) ?: return
        AckPathCache.touch(ack.messageID)
        val upstream = AckPathCache.lookup(ack.messageID)
        if (upstream == null) {
            try {
                AppStateStore.recordBroadcastAck(
                    ack.messageID, ackerPeerID, ack.hopCount,
                    ack.status == AckStatus.REACHED_RESPONDER
                )
            } catch (_: Exception) { }
            onBroadcastAck(ack.messageID, ackerPeerID, ack.hopCount, ack.status == AckStatus.REACHED_RESPONDER)
        } else {
            try {
                val forwarded = pkt.copy(
                    ttl = 1u,
                    recipientID = MeshPacketUtils.hexStringToByteArray(upstream)
                )
                val sent = sendToPeer(upstream, forwarded)
                bridgeSendToPeer(transportIdForBridge, upstream, forwarded)
                if (!sent) broadcastPacket(forwarded.copy(ttl = 0u))
            } catch (_: Exception) { }
        }
    }

    fun handleShelterPacket(routed: RoutedPacket) {
        val pkt = routed.packet
        val fromPeerID = routed.peerID ?: return
        if (fromPeerID == myPeerID) return
        val shelter = Shelter.decode(pkt.payload) ?: return
        if (shelter.originPeerID != fromPeerID) {
            Log.w("ShelterAckCoord", "Dropping shelter spoof attempt: origin=${shelter.originPeerID.take(8)} from=${fromPeerID.take(8)}")
            return
        }
        val peerInfo = lookupPeerInfo(fromPeerID)
        val verified = peerInfo != null && peerInfo.isVerifiedNickname && Role.isAuthority(peerInfo.role)
        try {
            if (shelter.status == ShelterStatus.CLOSED && shelter.originPeerID == fromPeerID) {
                // Soft-delete: remove the entry from the registry and AppStateStore
                shelterRegistry?.remove(shelter.id)
                try { AppStateStore.removeShelter(shelter.id) } catch (_: Exception) { }
                onShelterReceived(shelter, verified)
            } else {
                val accepted = shelterRegistry?.ingest(shelter) ?: false
                if (accepted) {
                    AppStateStore.recordShelter(shelter, verified)
                }
                onShelterReceived(shelter, verified)
            }
        } catch (_: Exception) { }
    }

    fun broadcastShelter(shelter: Shelter) {
        try {
            val payload = shelter.encode() ?: return
            val packet = BitchatPacket(
                version = 2u,
                type = MessageType.SHELTER.value,
                senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = payload,
                signature = null,
                ttl = maxTtl
            )
            val signed = signBeforeBroadcast(packet)
            broadcastPacket(signed)
        } catch (e: Exception) {
            Log.e("ShelterAckCoord", "broadcastShelter failed: ${e.message}")
        }
    }

    fun broadcastShelterRemoval(shelter: Shelter) {
        try {
            val tombstone = shelter.copy(status = ShelterStatus.CLOSED, version = System.currentTimeMillis())
            shelterRegistry?.ingest(tombstone)
            val payload = tombstone.encode() ?: return
            val packet = BitchatPacket(
                version = 2u,
                type = MessageType.SHELTER.value,
                senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = payload,
                signature = null,
                ttl = maxTtl
            )
            val signed = signBeforeBroadcast(packet)
            broadcastPacket(signed)
        } catch (e: Exception) {
            Log.e("ShelterAckCoord", "broadcastShelterRemoval failed: ${e.message}")
        }
    }

    fun gossipSheltersToPeer(peerID: String) {
        try {
            val registry = shelterRegistry ?: return
            // Receivers drop a shelter whose origin is not the peer that sent it, so only
            // our own records are worth sending; others reach that peer from their origin.
            // Capped so a gateway holding the command centre's centres cannot flood BLE.
            val snapshot = registry.snapshot()
                .filter { it.originPeerID == myPeerID && it.status != com.bitchat.android.model.ShelterStatus.CLOSED }
                .take(40)
            for (shelter in snapshot) {
                val payload = shelter.encode() ?: continue
                val packet = BitchatPacket(
                    version = 2u,
                    type = MessageType.SHELTER.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = payload,
                    signature = null,
                    ttl = 2u
                )
                val signed = signBeforeBroadcast(packet)
                sendToPeer(peerID, signed)
                bridgeSendToPeer(transportIdForBridge, peerID, signed)
            }
        } catch (_: Exception) { }
    }

    fun shutdown() {
        scope.cancel()
    }
}