package com.bitchat.android.services

import com.bitchat.android.model.Role
import com.bitchat.android.model.Shelter
import com.bitchat.android.ui.map.parseGeoTag
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject

/**
 * Builds the structured JSON bundle sent to civilization when internet becomes
 * available. The shape is stable so external coordinators / dashboards receiving
 * the POST can parse incidents and shelters uniformly.
 *
 * Incidents derive their location from the broadcast message content when it
 * carries a `geo:lat,lon` tag. Shelters come from [AppStateStore.shelters].
 */
object SyncBundleBuilder {

    data class Bundle(
        val json: String,
        val bytes: Int,
        val incidentCount: Int,
        val shelterCount: Int,
        val incidentIds: List<String>
    )

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun build(
        shelters: List<AppStateStore.VerifiedShelter>,
        incidents: List<BitchatIncident>,
        gatewayId: String? = null,
        cityId: String? = null
    ): Bundle {
        val incidentsJson = incidents.map { inc ->
            JsonObject().apply {
                addProperty("id", inc.id)
                addProperty("timestamp", inc.timestamp)
                addProperty("sender", inc.sender)
                addProperty("role", inc.role.name)
                addProperty("lat", inc.lat)
                addProperty("lon", inc.lon)
                addProperty("content", inc.content)
                addProperty("source", inc.source)
                inc.kind?.let { addProperty("kind", it) }
                addProperty("geotagged", inc.geotagged)
            }
        }
        val sheltersJson = shelters.map { vs ->
            val s: Shelter = vs.shelter
            JsonObject().apply {
                addProperty("id", s.id)
                addProperty("name", s.name)
                addProperty("lat", s.lat)
                addProperty("lon", s.lon)
                addProperty("capacity", s.capacity)
                addProperty("status", s.status.name)
                addProperty("role", s.role.name)
                addProperty("version", s.version)
                addProperty("originPeerID", s.originPeerID)
                addProperty("verified", vs.verified)
            }
        }
        val root = JsonObject().apply {
            addProperty("bundle_schema", "bitchat.civ.sync/v1")
            addProperty("produced_at", System.currentTimeMillis())
            gatewayId?.let { addProperty("gateway_id", it) }
            cityId?.let { addProperty("city_id", it) }
            add("incidents", gson.toJsonTree(incidentsJson))
            add("shelters", gson.toJsonTree(sheltersJson))
        }
        val json = gson.toJson(root)
        return Bundle(
            json = json,
            bytes = json.toByteArray(Charsets.UTF_8).size,
            incidentCount = incidents.size,
            shelterCount = shelters.size,
            incidentIds = incidents.map { it.id }
        )
    }

    /**
     * Every public broadcast worth reporting: SOS, geotagged posts and VLM camera briefs.
     * A message without a `geo:` tag uses [fallback] (this phone's last fix) so a VLM brief
     * or a plain "help, water rising" still lands on the command centre's map; with no
     * fallback it is kept only when geotagged. Command-centre traffic is never echoed back.
     */
    fun extractIncidents(
        messages: List<com.bitchat.android.model.BitchatMessage>,
        fallback: Pair<Double, Double>? = null,
        exclude: Set<String> = emptySet()
    ): List<BitchatIncident> {
        return messages.mapNotNull { msg ->
            if (msg.id in exclude) return@mapNotNull null
            if (msg.category == com.bitchat.android.model.Role.COMMAND) return@mapNotNull null
            if (msg.type != com.bitchat.android.model.BitchatMessageType.Message) return@mapNotNull null
            val text = msg.content.trim()
            if (text.isEmpty() || text == "[Image]") return@mapNotNull null
            if (OUTBOUND_IDX1.containsMatchIn(text)) return@mapNotNull null
            val geo = parseGeoTag(text)
            val at = geo ?: fallback ?: return@mapNotNull null
            val isVlm = msg.id.startsWith("vlm-")
            BitchatIncident(
                id = msg.id,
                timestamp = msg.timestamp.time,
                sender = msg.sender,
                role = msg.category,
                lat = at.first,
                lon = at.second,
                content = text.take(280),
                source = if (isVlm) "vlm" else "mesh",
                kind = if (isVlm) hazardKind(text) else null,
                geotagged = geo != null
            )
        }.distinctBy { it.sender + "\u0000" + it.content.replace(Regex("\\s*geo:\\S+"), "") }
    }

    /**
     * IDX1 packets travel their own way: inbound ones through the gateway queue
     * (IndradhanuGateway), outbound ones came from the control room in the first place.
     */
    private val OUTBOUND_IDX1 = Regex("IDX1\\|[A-Z]\\|")

    /** ARGUS VLM classes, read off the brief's words. */
    fun hazardKind(text: String): String? {
        val t = text.lowercase()
        return when {
            listOf("fire", "flame", "burning", "blaze").any { it in t } -> "fire"
            "smoke" in t -> "smoke"
            listOf("flood", "waterlog", "submerged", "inundat").any { it in t } -> "flood"
            listOf("collapse", "rubble", "debris", "earthquake").any { it in t } -> "collapse"
            listOf("injur", "unconscious", "bleeding", "medical", "casualt").any { it in t } -> "medical"
            listOf("assault", "fight", "violence", "attack").any { it in t } -> "assault"
            else -> null
        }
    }
}

data class BitchatIncident(
    val id: String,
    val timestamp: Long,
    val sender: String,
    val role: Role,
    val lat: Double,
    val lon: Double,
    val content: String,
    val source: String = "mesh",
    val kind: String? = null,
    val geotagged: Boolean = true
)