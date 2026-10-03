package com.bitchat.android.shell

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Where the person is in their task, kept across the switch between the online app and the
 * mesh so neither side starts from nothing:
 *
 *  - the place they are being guided to (and why: "shelter", "hospital"),
 *  - the incident they had open,
 *  - the report or message they were half-way through writing,
 *  - their last known position.
 *
 * Both sides write here (the PWA through [BiChatBridge], the native screens directly) and
 * both read it when they come to the front. Stored on the device only.
 */
data class HandoffState(
    val destName: String? = null,
    val destKind: String? = null,
    val destLat: Double? = null,
    val destLng: Double? = null,
    /** The guidance intent the PWA asked for ("shelter", "hospital"...), to re-ask online. */
    val intent: String? = null,
    val navigating: Boolean = false,
    val selectedIncidentId: String? = null,
    val draft: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    /** A field crew's resource id (from `/field/state`), so mesh status updates name the unit. */
    val unitId: String? = null,
    val updatedAt: Long = 0L,
) {
    val hasDestination: Boolean get() = destLat != null && destLng != null

    fun toJson(): JSONObject = JSONObject().apply {
        destName?.let { put("destName", it) }
        destKind?.let { put("destKind", it) }
        destLat?.let { put("destLat", it) }
        destLng?.let { put("destLng", it) }
        intent?.let { put("intent", it) }
        put("navigating", navigating)
        selectedIncidentId?.let { put("selectedIncidentId", it) }
        draft?.let { put("draft", it) }
        lat?.let { put("lat", it) }
        lng?.let { put("lng", it) }
        unitId?.let { put("unitId", it) }
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(j: JSONObject): HandoffState {
            fun d(k: String) = if (j.has(k) && !j.isNull(k)) j.optDouble(k).takeIf { it.isFinite() } else null
            fun s(k: String) = if (j.has(k) && !j.isNull(k)) j.optString(k).ifBlank { null } else null
            return HandoffState(
                destName = s("destName"), destKind = s("destKind"),
                destLat = d("destLat"), destLng = d("destLng"),
                intent = s("intent"), navigating = j.optBoolean("navigating"),
                selectedIncidentId = s("selectedIncidentId"), draft = s("draft"),
                lat = d("lat"), lng = d("lng"), unitId = s("unitId"), updatedAt = j.optLong("updatedAt"),
            )
        }
    }
}

object Handoff {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(HandoffState())
    val state: StateFlow<HandoffState> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("bitchat_handoff", Context.MODE_PRIVATE)
        _state.value = try {
            prefs?.getString("state", null)?.let { HandoffState.fromJson(JSONObject(it)) } ?: HandoffState()
        } catch (_: Exception) {
            HandoffState()
        }
    }

    /** Merge a partial update (only keys present in [patch] change). */
    fun merge(patch: JSONObject) {
        val merged = _state.value.toJson()
        patch.keys().forEach { k -> merged.put(k, patch.get(k)) }
        set(HandoffState.fromJson(merged))
    }

    fun update(transform: (HandoffState) -> HandoffState) = set(transform(_state.value))

    fun clearDestination() = update {
        it.copy(destName = null, destKind = null, destLat = null, destLng = null, intent = null, navigating = false)
    }

    private fun set(s: HandoffState) {
        val stamped = s.copy(updatedAt = System.currentTimeMillis())
        _state.value = stamped
        prefs?.edit()?.putString("state", stamped.toJson().toString())?.apply()
    }
}
