package com.bitchat.android.ui.map

/** Parse a `geo:lat,lon` tag from arbitrary message content. */
fun parseGeoTag(content: String): Pair<Double, Double>? {
    val regex = Regex("geo:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)")
    val m = regex.find(content) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lon = m.groupValues[2].toDoubleOrNull() ?: return null
    return lat to lon
}
