package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.Role

/**
 * Who may see what on this phone.
 *
 * Camera / VLM detections are for the authorities only. They still travel the mesh as
 * IDX1 "S" (sensor) packets, because that is how a camera with no internet reaches the
 * command centre: any gateway phone that hears one forwards the raw packet. But only a
 * phone whose announced role is Gov or Command shows them; on everyone else's phone
 * they are invisible (no timeline row, no map marker, no buzz).
 *
 * The packets carry hazards only (fire, smoke, flood, collapse…), never identities, so
 * relaying them through civilian phones exposes nothing about people.
 */
object AudiencePolicy {
    private val SENSOR_PACKET = Regex("IDX1\\|S\\|")

    /** A VLM brief (local id) or a sensor packet heard on the mesh. */
    fun isGovernmentOnly(message: BitchatMessage): Boolean =
        message.id.startsWith("vlm-") || SENSOR_PACKET.containsMatchIn(message.content)

    fun canSeeGovernmentOnly(role: Role): Boolean = role == Role.GOV || role == Role.COMMAND

    fun visibleTo(role: Role, message: BitchatMessage): Boolean =
        canSeeGovernmentOnly(role) || !isGovernmentOnly(message)
}
