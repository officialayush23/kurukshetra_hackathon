package com.bitchat.android.ui.map

import android.content.Context
import android.location.Location
import android.util.Log
import com.bitchat.android.model.Shelter
import com.bitchat.android.net.CivilizationSyncClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Walking routes to centres, fetched while there is signal and kept on disk so the
 * offline map can still draw a real path (not just a straight line) after the network
 * is gone. A cached route is reused when the person is within [REUSE_RADIUS_M] of where
 * it started; otherwise the map falls back to a straight line with a compass bearing.
 */
object RouteCache {
    private const val TAG = "RouteCache"
    private const val PREFS = "bitchat_route_cache_v1"
    private const val MAX_ROUTES = 30
    private const val MAX_POINTS = 400
    const val REUSE_RADIUS_M = 400f

    data class Route(
        val shelterId: String,
        val fromLat: Double,
        val fromLon: Double,
        val points: List<Pair<Double, Double>>,
        val metres: Double,
        val seconds: Double,
        val profile: String,
        val savedAt: Long
    )

    fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, out)
        return out[0]
    }

    fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): String {
        val out = FloatArray(2)
        Location.distanceBetween(aLat, aLon, bLat, bLon, out)
        val deg = ((out[1] % 360) + 360) % 360
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[((deg + 22.5f) / 45f).toInt() % 8]
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lookup(ctx: Context, shelterId: String, lat: Double, lon: Double): Route? {
        val raw = prefs(ctx).getString("r_$shelterId", null) ?: return null
        val r = try { decode(JSONObject(raw)) } catch (_: Exception) { null } ?: return null
        return if (metres(lat, lon, r.fromLat, r.fromLon) <= REUSE_RADIUS_M) r else null
    }

    /** Blocking network call; run off the main thread. Returns the stored route or null. */
    fun fetchAndStore(ctx: Context, lat: Double, lon: Double, shelter: Shelter): Route? {
        val client = CivilizationSyncClient(ctx)
        val coords = "%.6f,%.6f;%.6f,%.6f".format(java.util.Locale.US, lon, lat, shelter.lon, shelter.lat)
        val attempts = listOf(
            "foot" to "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords?overview=full&geometries=geojson",
            "car" to "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson"
        )
        for ((profile, url) in attempts) {
            val (code, text) = client.getAbsolute(url)
            if (code !in 200..299) continue
            try {
                val route = JSONObject(text).optJSONArray("routes")?.optJSONObject(0) ?: continue
                val line = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                val pts = ArrayList<Pair<Double, Double>>(line.length())
                for (i in 0 until line.length()) {
                    val c = line.getJSONArray(i)
                    pts += c.getDouble(1) to c.getDouble(0)
                }
                val step = maxOf(1, pts.size / MAX_POINTS)
                val slim = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }
                val r = Route(
                    shelterId = shelter.id, fromLat = lat, fromLon = lon, points = slim,
                    metres = route.optDouble("distance"), seconds = route.optDouble("duration"),
                    profile = profile, savedAt = System.currentTimeMillis()
                )
                store(ctx, r)
                return r
            } catch (e: Exception) {
                Log.w(TAG, "route parse failed: ${e.message}")
            }
        }
        return null
    }

    private fun store(ctx: Context, r: Route) {
        val p = prefs(ctx)
        val order = (p.getString("order", "") ?: "").split(',').filter { it.isNotBlank() }.toMutableList()
        order.remove(r.shelterId)
        order.add(r.shelterId)
        val e = p.edit()
        while (order.size > MAX_ROUTES) e.remove("r_" + order.removeAt(0))
        e.putString("r_${r.shelterId}", encode(r).toString())
        e.putString("order", order.joinToString(","))
        e.apply()
    }

    private fun encode(r: Route): JSONObject {
        val arr = JSONArray()
        r.points.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        return JSONObject()
            .put("id", r.shelterId).put("fa", r.fromLat).put("fo", r.fromLon)
            .put("m", r.metres).put("s", r.seconds).put("p", r.profile)
            .put("at", r.savedAt).put("pts", arr)
    }

    private fun decode(o: JSONObject): Route {
        val arr = o.getJSONArray("pts")
        val pts = ArrayList<Pair<Double, Double>>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.getJSONArray(i)
            pts += c.getDouble(0) to c.getDouble(1)
        }
        return Route(
            shelterId = o.getString("id"), fromLat = o.getDouble("fa"), fromLon = o.getDouble("fo"),
            points = pts, metres = o.optDouble("m"), seconds = o.optDouble("s"),
            profile = o.optString("p", "foot"), savedAt = o.optLong("at")
        )
    }
}
