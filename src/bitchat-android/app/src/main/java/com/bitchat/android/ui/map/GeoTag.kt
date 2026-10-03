package com.bitchat.android.ui.map

import com.bitchat.android.model.ShelterStatus

/** Parse a `geo:lat,lon` tag from arbitrary message content. */
fun parseGeoTag(content: String): Pair<Double, Double>? {
    val regex = Regex("geo:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)")
    val m = regex.find(content) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lon = m.groupValues[2].toDoubleOrNull() ?: return null
    return lat to lon
}

/** Status dot colour for a place: open, full, closed. */
fun shelterStatusColorArgb(status: ShelterStatus): Int = when (status) {
    ShelterStatus.OPEN -> 0xFF34C759.toInt()
    ShelterStatus.FULL -> 0xFFFF9F0A.toInt()
    ShelterStatus.CLOSED -> 0xFF8E8E93.toInt()
}

/** Format a great-circle distance in metres into a compact label. */
fun formatDistance(meters: Float): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    meters < 10000 -> "${"%.1f".format(meters / 1000)} km"
    else -> "${(meters / 1000).toInt()} km"
}
