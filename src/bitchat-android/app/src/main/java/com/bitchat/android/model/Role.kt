package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Emergency-response node role announced in the IdentityAnnouncement TLV 0x06
 * and optionally echoed as a 1-byte category prefix on broadcast MESSAGE
 * payloads (gated by the BitchatPacket [com.bitchat.android.protocol.BinaryProtocol.Flags.HAS_CATEGORY] flag bit).
 *
 * Wire value is a single UByte. [UNSET] (0x00) keeps old peers and plain-text
 * broadcasts byte-for-byte backward compatible with prior clients: no flag is
 * set and no category byte is prepended.
 */
@Parcelize
enum class Role(val value: UByte) : Parcelable {
    UNSET(0x00u),
    CIVILIAN(0x01u),
    AMBULANCE(0x02u),
    FIRE(0x03u),
    GOV(0x04u),
    /** The control room (DisruptionOps / Indradhanu) speaking through a gateway phone. */
    COMMAND(0x05u);

    companion object {
        fun fromValue(value: UByte): Role =
            values().firstOrNull { it.value == value } ?: UNSET

        /** Visible label used by the role-chip filter row and profile selector. */
        fun displayLabel(role: Role): String = when (role) {
            UNSET -> "All"
            CIVILIAN -> "Civilian"
            AMBULANCE -> "Ambulance"
            FIRE -> "Fire"
            GOV -> "Gov"
            COMMAND -> "Command"
        }

        /** Order of the alert filter chips: command centre first, then responders. */
        val filterOrder: List<Role> = listOf(COMMAND, AMBULANCE, FIRE, GOV, CIVILIAN)

        /** Roles whose shelters/centres are shown as verified. */
        fun isAuthority(role: Role): Boolean = role == GOV || role == COMMAND
    }
}