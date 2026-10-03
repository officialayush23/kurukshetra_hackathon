package com.bitchat.android.ui.map

import android.location.Location
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.Role
import com.bitchat.android.model.ShelterStatus
import com.bitchat.android.services.AppStateStore
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything the map can draw, derived only from data the app already holds.
 *
 * Two sources feed it, and nothing else:
 *  - the gossiped node registry ([AppStateStore.shelters]): responder staging points, SOS
 *    beacons and plain shelters, each with the role its publisher announced;
 *  - broadcast messages carrying a `geo:lat,lon` tag: an SOS when it was sent through the SOS
 *    path (🆘 prefix), a field report otherwise.
 *
 * The map never invents an entity, a status or a distance it cannot compute from these.
 */
enum class MapEntityKind(val label: String, val pluralLabel: String) {
    SOS("SOS", "SOS"),
    INCIDENT("Report", "Reports"),
    AMBULANCE("Ambulance", "Ambulance"),
    FIRE("Fire", "Fire"),
    GOV("Gov", "Gov"),
    SHELTER("Shelter", "Shelters");

    val isResponder: Boolean get() = this == AMBULANCE || this == FIRE || this == GOV
}

/** The contextual filters offered on the map. Each one maps onto real entity kinds. */
enum class MapFilter(val label: String, val kinds: Set<MapEntityKind>) {
    ALL("All", MapEntityKind.entries.toSet()),
    SOS("SOS", setOf(MapEntityKind.SOS)),
    REPORTS("Reports", setOf(MapEntityKind.INCIDENT)),
    AMBULANCE("Ambulance", setOf(MapEntityKind.AMBULANCE)),
    FIRE("Fire", setOf(MapEntityKind.FIRE)),
    GOV("Gov", setOf(MapEntityKind.GOV)),
    SHELTERS("Shelters", setOf(MapEntityKind.SHELTER));

    fun matches(kind: MapEntityKind) = kind in kinds
}

data class MapEntity(
    /** Stable across recompositions: `msg:<id>` or `node:<id>`. */
    val id: String,
    val kind: MapEntityKind,
    val lat: Double,
    val lon: Double,
    val title: String,
    /** Message text with the machine tags stripped, or null for registry nodes. */
    val body: String?,
    /** Who published it: a nickname for messages, a short peer id for registry nodes. */
    val reporter: String?,
    val reporterRole: Role,
    val timestampMs: Long?,
    /** Registry nodes only: signed by an announced GOV peer. */
    val verified: Boolean?,
    val status: ShelterStatus?,
    val capacity: Int?,
    /** The peer that can be messaged about this entity, when one is known. */
    val peerID: String?,
    val messageId: String?,
    val isOwn: Boolean,
) {
    val isCritical: Boolean get() = kind == MapEntityKind.SOS

    /** A short reference a person can read out over a radio: "#4F2A". */
    val shortRef: String
        get() = "#" + (messageId ?: id.substringAfter(':')).filter { it.isLetterOrDigit() }
            .take(4).uppercase()

    /** "SOS #4F2A", "Ambulance · RS Puram staging". */
    val headline: String
        get() = when (kind) {
            MapEntityKind.SOS, MapEntityKind.INCIDENT ->
                if (messageId != null) "${kind.label} $shortRef" else title
            else -> title
        }
}

private val GEO_TAG = Regex("\\s*geo:-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?")

/** Message text as a person should read it: no `geo:` tag, no leading SOS glyph. */
fun displayBody(content: String): String =
    content.replace(GEO_TAG, "").removePrefix("🆘").trim()

fun isSosMessage(content: String): Boolean = content.trimStart().startsWith("🆘")

/**
 * Build the map's entity list from the registry and the public timeline.
 *
 * Registry nodes keep the role their publisher announced. A CLOSED node is still drawn —
 * it is information ("that staging point is shut") — but the sheet says so.
 */
fun buildMapEntities(
    shelters: List<AppStateStore.VerifiedShelter>,
    messages: List<BitchatMessage>,
    myNickname: String?,
): List<MapEntity> {
    val out = ArrayList<MapEntity>(shelters.size + 32)
    shelters.forEach { vs ->
        val s = vs.shelter
        if (!s.lat.isFinite() || !s.lon.isFinite()) return@forEach
        if (abs(s.lat) > 90 || abs(s.lon) > 180) return@forEach
        val kind = when (s.role) {
            Role.AMBULANCE -> MapEntityKind.AMBULANCE
            Role.FIRE -> MapEntityKind.FIRE
            // The control room's own centres sit with the authorities.
            Role.GOV, Role.COMMAND -> MapEntityKind.GOV
            Role.CIVILIAN -> MapEntityKind.SOS
            Role.UNSET -> MapEntityKind.SHELTER
        }
        out += MapEntity(
            id = "node:${s.id}",
            kind = kind,
            lat = s.lat,
            lon = s.lon,
            title = s.name.ifBlank { kind.label },
            body = null,
            reporter = s.originPeerID.take(8),
            reporterRole = s.role,
            timestampMs = null,
            verified = vs.verified,
            status = s.status,
            capacity = s.capacity.takeIf { it > 0 },
            peerID = s.originPeerID.takeIf { it.isNotBlank() },
            messageId = null,
            isOwn = false,
        )
    }
    messages.forEach { msg ->
        val geo = parseGeoTag(msg.content) ?: return@forEach
        if (abs(geo.first) > 90 || abs(geo.second) > 180) return@forEach
        val sos = isSosMessage(msg.content)
        val body = displayBody(msg.content)
        val kind = if (sos) MapEntityKind.SOS else MapEntityKind.INCIDENT
        out += MapEntity(
            id = "msg:${msg.id}",
            kind = kind,
            lat = geo.first,
            lon = geo.second,
            title = body.ifBlank { kind.label }.lineSequence().first().take(80),
            body = body.ifBlank { null },
            reporter = msg.sender,
            reporterRole = msg.category,
            timestampMs = msg.timestamp.time,
            verified = null,
            status = null,
            capacity = null,
            peerID = msg.senderPeerID,
            messageId = msg.id,
            isOwn = myNickname != null && msg.sender == myNickname,
        )
    }
    return out
}

// ---------------------------------------------------------------- geometry ---

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val out = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, out)
    return out[0]
}

/** Initial bearing in degrees, 0 = north, clockwise. */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val out = FloatArray(2)
    Location.distanceBetween(lat1, lon1, lat2, lon2, out)
    return (out[1] + 360f) % 360f
}

fun compassWord(deg: Float): String {
    val words = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return words[(((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]
}

/**
 * Distance from point P to the straight segment A→B, in metres, on a local flat projection.
 * Good to a metre or two over the few kilometres this is used for.
 */
fun distanceToSegmentMeters(
    pLat: Double, pLon: Double,
    aLat: Double, aLon: Double,
    bLat: Double, bLon: Double,
): Double {
    val k = cos(Math.toRadians((aLat + bLat) / 2))
    val ax = aLon * 111_320.0 * k
    val ay = aLat * 110_540.0
    val bx = bLon * 111_320.0 * k
    val by = bLat * 110_540.0
    val px = pLon * 111_320.0 * k
    val py = pLat * 110_540.0
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    val fx = ax + t * dx
    val fy = ay + t * dy
    return kotlin.math.sqrt((px - fx) * (px - fx) + (py - fy) * (py - fy))
}

/** How far along A→B the closest point to P sits, in metres from A. */
fun alongSegmentMeters(
    pLat: Double, pLon: Double,
    aLat: Double, aLon: Double,
    bLat: Double, bLon: Double,
): Double {
    val k = cos(Math.toRadians((aLat + bLat) / 2))
    val dx = (bLon - aLon) * 111_320.0 * k
    val dy = (bLat - aLat) * 110_540.0
    val px = (pLon - aLon) * 111_320.0 * k
    val py = (pLat - aLat) * 110_540.0
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    if (len == 0.0) return 0.0
    return ((px * dx + py * dy) / len).coerceIn(0.0, len)
}

// --------------------------------------------------------------- wording ---

fun formatMeters(meters: Float): String = when {
    meters < 50 -> "${max(1, meters.roundToInt())} m"
    meters < 1000 -> "${(meters / 10).roundToInt() * 10} m"
    meters < 10_000 -> "${"%.1f".format(meters / 1000)} km"
    else -> "${(meters / 1000).roundToInt()} km"
}

/** "just now", "4 min ago", "2 h ago". Never claims precision the timestamp lacks. */
fun formatAge(ageMs: Long): String {
    val s = max(0L, ageMs) / 1000
    return when {
        s < 45 -> "just now"
        s < 90 -> "1 min ago"
        s < 3600 -> "${(s / 60.0).roundToInt()} min ago"
        s < 86_400 -> "${(s / 3600.0).roundToInt()} h ago"
        else -> "${(s / 86_400.0).roundToInt()} d ago"
    }
}

fun statusLabel(status: ShelterStatus?): String? = when (status) {
    ShelterStatus.OPEN -> "Open"
    ShelterStatus.FULL -> "Full"
    ShelterStatus.CLOSED -> "Closed"
    null -> null
}

/** Who the viewer is, for "Ambulance → SOS #4F2A". Null for an unannounced role. */
fun roleActorLabel(role: Role): String? = when (role) {
    Role.AMBULANCE -> "Ambulance"
    Role.FIRE -> "Fire"
    Role.GOV -> "Gov"
    Role.COMMAND -> "Command"
    Role.CIVILIAN -> "You"
    Role.UNSET -> null
}

/** Bounding box helper that tolerates a single point. Returns [north, east, south, west]. */
fun boundsOf(points: List<Pair<Double, Double>>, padDeg: Double = 0.002): DoubleArray? {
    if (points.isEmpty()) return null
    var n = -90.0; var s = 90.0; var e = -180.0; var w = 180.0
    points.forEach { (la, lo) ->
        n = max(n, la); s = min(s, la); e = max(e, lo); w = min(w, lo)
    }
    return doubleArrayOf(n + padDeg, e + padDeg, s - padDeg, w - padDeg)
}
