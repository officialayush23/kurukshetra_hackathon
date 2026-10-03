package com.bitchat.android.vlm

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.Role
import com.bitchat.android.model.Shelter
import com.bitchat.android.model.ShelterStatus
import com.bitchat.android.net.CivilizationSyncClient
import com.bitchat.android.net.LastFix
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ShelterRegistry
import com.bitchat.android.services.SyncPreferences
import com.bitchat.android.ui.map.RouteCache
import com.bitchat.android.util.SyncFlushWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's link to the command centre (DisruptionOps / Indradhanu control room).
 *
 * Settings live in [SyncPreferences] (the "Civilization Bridge" sheet): one URL, the
 * gateway key, the city. While the phone has validated internet and sync is on, every
 * [LOOP_MS] it:
 *
 * 1. PUSH. Forwards IDX1 packets heard on the mesh to `/api/v1/mesh/inbound`
 *    (store-and-forward queue that survives restarts), and posts the civ-sync bundle
 *    (SOS, geotagged broadcasts, VLM camera briefs, shelters) to `/api/v1/mesh/civ-sync`
 *    via [SyncFlushWorker.flushNow]. Both are idempotent on the server.
 * 2. LISTEN. Pulls `/api/v1/mesh/outbox` (alerts, dispatches, cancellations, road
 *    blocks), broadcasts each on the mesh tagged [Role.COMMAND] so every phone can
 *    filter by it, shows it locally, and acks it.
 * 3. CACHE. Every [PLACES_MS] pulls the centres (shelters, relief centres, hospitals,
 *    medical camps, food and water points) into the shelter registry, which is persisted
 *    and gossiped to the mesh, so phones that never get signal still see the nearest
 *    centre; then precomputes walking routes from here to the closest few ([RouteCache])
 *    for offline navigation.
 *
 * The local VLM API also exposes the IDX1 inbox (`GET /inbox`) for the Indradhanu web
 * app running on this phone, and `GET|POST /gateway` for these settings.
 *
 * Nothing here interprets a packet. Verification (HMAC), deduplication and what a
 * report becomes are decided by the command centre.
 */
object IndradhanuGateway {
    private const val TAG = "IndradhanuGateway"
    private const val PREFS = "indradhanu_gateway"
    private const val KEY_QUEUE = "queue"
    private const val MARKER = "IDX1|"
    /** Packet types that travel towards the control room. */
    private val INBOUND = listOf("IDX1|R|", "IDX1|S|", "IDX1|F|", "IDX1|H|", "IDX1|K|")
    private const val INBOX_SIZE = 200
    private const val QUEUE_MAX = 500
    private const val LOOP_MS = 10_000L
    private const val FLUSH_MS = 30_000L
    private const val PLACES_MS = 10 * 60_000L
    private const val MESH_GOSSIP_LIMIT = 15
    private const val ROUTE_PRECOMPUTE = 3
    const val COMMAND_SENDER = "Command Centre"

    /** Centre kinds a person in trouble can walk to. */
    private val PLACE_KINDS = mapOf(
        "shelter" to "🏠", "relief_centre" to "⛺", "hospital" to "🏥",
        "medical_camp" to "⚕", "food_kitchen" to "🍲", "water_point" to "🚰"
    )

    data class InboxItem(
        val seq: Long,
        val text: String,
        val sender: String,
        val channel: String?,
        val atMs: Long
    )

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private val seq = AtomicLong(0)
    private val inbox = ArrayDeque<InboxItem>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var lastFlushAt = 0L
    private var lastPlacesAt = 0L
    private val tickLock = Mutex()

    @Volatile var lastForwardOk: Long = 0
        private set
    @Volatile var lastPullOk: Long = 0
        private set
    @Volatile var lastPlacesOk: Long = 0
        private set
    @Volatile var lastError: String? = null
        private set
    /** Last successful heartbeat: the command centre answered with this phone's key. */
    @Volatile var lastHeartbeatOk: Long = 0
        private set
    /** One line for the settings sheet: what the last cycle did, or why it could not. */
    @Volatile var lastResult: String = ""
        private set
    @Volatile var lastForwardCount: Int = 0
        private set

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hydrateShelters(context.applicationContext)
        startLoop()
    }

    /**
     * The registry is persisted but nothing loaded it back into the in-memory store, so
     * after a restart the offline map had no centres. Load them once at start.
     */
    private fun hydrateShelters(ctx: Context) {
        try {
            val saved = ShelterRegistry.getInstance(ctx).snapshot()
            saved.filter { it.status != ShelterStatus.CLOSED }.forEach {
                AppStateStore.recordShelter(it, verified = Role.isAuthority(it.role))
            }
        } catch (e: Exception) {
            Log.w(TAG, "shelter hydrate failed: ${e.message}")
        }
    }

    private fun sync(): SyncPreferences? = appContext?.let { SyncPreferences.getInstance(it) }

    fun isConfigured(): Boolean {
        val p = sync() ?: return false
        return p.enabled && p.apiBaseUrl.startsWith("http")
    }

    fun update(json: JSONObject) {
        val p = sync() ?: return
        if (json.has("enabled")) p.enabled = json.optBoolean("enabled")
        if (json.has("api_url")) p.endpointUrl = json.optString("api_url").trim().trimEnd('/')
        if (json.has("gateway_key")) p.gatewayKey = json.optString("gateway_key")
        if (json.has("city_id")) p.cityId = json.optString("city_id")
        if (json.has("listen")) p.listenToCommand = json.optBoolean("listen")
    }

    fun settingsJson(): Map<String, Any?> {
        val p = sync()
        return mapOf(
            "enabled" to (p?.enabled ?: false),
            "api_url" to (p?.apiBaseUrl ?: ""),
            "gateway_id" to (p?.gatewayId ?: ""),
            "gateway_key_set" to (p?.gatewayKey?.isNotBlank() ?: false),
            "city_id" to (p?.cityId ?: "pune"),
            "listen" to (p?.listenToCommand ?: true),
            "queued" to queueSize(),
            "online" to isOnline(),
            "last_forward_ok_ms" to lastForwardOk,
            "last_pull_ok_ms" to lastPullOk,
            "last_places_ok_ms" to lastPlacesOk,
            "last_sync_ms" to (p?.lastSyncedAt ?: 0L),
            "last_heartbeat_ms" to lastHeartbeatOk,
            "last_result" to lastResult,
            "last_error" to (lastError ?: p?.lastSyncError)
        )
    }

    /** Called for every message the mesh delivers to this phone. */
    fun onMessage(message: BitchatMessage) {
        val text = message.content
        if (!text.contains(MARKER)) return
        val item = InboxItem(
            seq = seq.incrementAndGet(),
            text = text,
            sender = message.sender,
            channel = message.channel,
            atMs = message.timestamp.time
        )
        synchronized(lock) {
            inbox.addLast(item)
            while (inbox.size > INBOX_SIZE) inbox.removeFirst()
        }
        if (INBOUND.any { text.contains(it) }) enqueue(text)
    }

    /**
     * Also keep what THIS phone sends through the VLM API (e.g. the Indradhanu
     * web app's mesh-mode reports). The mesh does not echo a sender's own
     * message back to it, so without this a gateway phone would forward
     * everyone's reports except its own user's.
     */
    fun onLocalSend(text: String) {
        if (!INBOUND.any { text.contains(it) }) return
        enqueue(text)
    }

    fun inboxSince(since: Long, limit: Int = 100): List<InboxItem> =
        synchronized(lock) { inbox.filter { it.seq > since }.take(limit) }

    fun latestSeq(): Long = seq.get()

    /** Run one full cycle now (the "Send now" button). */
    fun kick() {
        scope.launch { syncNow() }
    }

    /**
     * The "Sync now" button. Runs a full cycle immediately (heartbeat, forward the queue,
     * pull the outbox, flush the bundle, refresh centres) and returns a sentence saying
     * what happened, or exactly what is missing. Safe to call from the UI thread: the
     * network work runs on IO.
     */
    suspend fun syncNow(): String = kotlinx.coroutines.withContext(Dispatchers.IO) { syncNowIo() }

    private suspend fun syncNowIo(): String {
        appContext ?: return "App is still starting. Try again in a moment."
        val p = sync() ?: return "App is still starting. Try again in a moment."
        val why = when {
            !p.enabled -> "Turn on \"Enable sync on reconnect\" first."
            !p.apiBaseUrl.startsWith("http") -> "Enter the command centre URL, starting with https://"
            p.gatewayKey.isBlank() -> "Enter the gateway key (MESH_GATEWAY_KEY)."
            !isOnline() -> "This phone has no internet right now. Reports stay queued until it does."
            else -> null
        }
        if (why != null) {
            lastResult = why
            return why
        }
        lastFlushAt = 0L
        lastPlacesAt = 0L
        tick()
        return lastResult
    }

    // ------------------------------------------------------------ queue ---
    // Queued even while sync is off: a phone may walk into signal long after it heard
    // the packet, and the user may switch the link on only then.
    private fun readQueue(): JSONArray =
        try { JSONArray(prefs?.getString(KEY_QUEUE, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun writeQueue(q: JSONArray) {
        prefs?.edit()?.putString(KEY_QUEUE, q.toString())?.apply()
    }

    private fun enqueue(text: String) = synchronized(lock) {
        val q = readQueue()
        for (i in 0 until q.length()) if (q.optString(i) == text) return@synchronized
        q.put(text)
        while (q.length() > QUEUE_MAX) q.remove(0)
        writeQueue(q)
    }

    private fun queueSize(): Int = synchronized(lock) { readQueue().length() }

    // ------------------------------------------------------------ loop ----
    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            delay(3_000)
            while (isActive) {
                tick()
                delay(LOOP_MS)
            }
        }
    }

    private suspend fun tick(): Unit = tickLock.withLock {
        val ctx = appContext ?: return@withLock
        val p = sync() ?: return@withLock
        if (!isConfigured() || !isOnline()) return@withLock
        val client = CivilizationSyncClient(ctx)
        val now = System.currentTimeMillis()
        // Heartbeat first: it is what tells the console this phone is linked, and it
        // is the cheapest proof that the URL and the key are right.
        val linked = try { heartbeat(ctx, client, p) } catch (e: Exception) {
            lastResult = "Heartbeat failed: ${e.message}"; false
        }
        lastForwardCount = 0
        try { forward(client, p) } catch (e: Exception) { lastError = "forward: ${e.message}" }
        if (p.listenToCommand) {
            try { pullOutbox(ctx, client, p) } catch (e: Exception) { lastError = "outbox: ${e.message}" }
        }
        if (now - lastFlushAt >= FLUSH_MS) {
            lastFlushAt = now
            try { SyncFlushWorker.flushNow(ctx) } catch (e: Exception) { lastError = "sync: ${e.message}" }
        }
        if (now - lastPlacesAt >= PLACES_MS) {
            lastPlacesAt = now
            try { refreshPlaces(ctx, client) } catch (e: Exception) { lastError = "places: ${e.message}" }
        }
        if (linked) {
            val t = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(Date())
            lastResult = "Linked to command centre at $t · ${lastForwardCount} packet(s) sent · " +
                "${queueSize()} queued" + (lastError?.let { " · last problem: $it" } ?: "")
        }
    }

    private suspend fun heartbeat(ctx: Context, client: CivilizationSyncClient, p: SyncPreferences): Boolean {
        val mesh = try { MeshServiceHolder.getUnifiedOrCreate(ctx) } catch (_: Exception) { null }
        val body = JSONObject()
            .put("gatewayId", p.gatewayId)
            .put("cityId", p.cityId)
            .put("label", (try { mesh?.getNickname() } catch (_: Exception) { null })
                ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL)
            .put("peers", try { mesh?.getActivePeerCount() ?: 0 } catch (_: Exception) { 0 })
            .put("queued", queueSize())
            .put("appVersion", com.bitchat.android.BuildConfig.VERSION_NAME)
            .put("listen", p.listenToCommand)
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 0..100) body.put("battery", pct)
        } catch (_: Exception) { }
        try {
            LastFix.get(ctx)?.let { body.put("lat", it.first).put("lon", it.second) }
        } catch (_: Exception) { }
        val (code, text) = client.call("POST", "/api/v1/mesh/gateway/heartbeat", body.toString())
        if (code in 200..299) {
            val now = System.currentTimeMillis()
            lastHeartbeatOk = now
            p.lastSyncedAt = now
            p.lastSyncError = null
            return true
        }
        val msg = when (code) {
            -1 -> "Cannot reach ${p.apiBaseUrl}: $text"
            401, 403 -> "Command centre refused the gateway key (HTTP $code). Check MESH_GATEWAY_KEY."
            404 -> "Command centre is running an older build without /mesh/gateway/heartbeat (HTTP 404). Redeploy the backend."
            in 500..599 -> "Command centre error (HTTP $code). It may be waking up; try again in 30 s."
            else -> "Command centre answered HTTP $code"
        }
        p.lastSyncError = msg
        lastResult = msg
        return false
    }

    private fun isOnline(): Boolean {
        val ctx = appContext ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        // INTERNET only. VALIDATED is false on many hotspots and campus networks that
        // work perfectly well; the HTTP call itself is the real test.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun forward(client: CivilizationSyncClient, p: SyncPreferences) {
        val batch = synchronized(lock) { readQueue() }
        if (batch.length() == 0) return
        val take = JSONArray()
        for (i in 0 until minOf(batch.length(), 50)) take.put(batch.optString(i))
        val body = JSONObject().put("gatewayId", p.gatewayId).put("packets", take).put("cityId", p.cityId)
        val (code, _) = client.call("POST", "/api/v1/mesh/inbound", body.toString())
        if (code in 200..299) {
            synchronized(lock) {
                val q = readQueue()
                val rest = JSONArray()
                val sent = (0 until take.length()).map { take.optString(it) }.toSet()
                for (i in 0 until q.length()) if (q.optString(i) !in sent) rest.put(q.optString(i))
                writeQueue(rest)
            }
            lastForwardOk = System.currentTimeMillis()
            lastForwardCount += take.length()
            lastError = null
        } else {
            lastError = "inbound HTTP $code"
        }
    }

    /** Put a `geo:` tag in front of the IDX1 packet so every phone can pin it on the map. */
    private fun withGeo(text: String, lat: Double?, lon: Double?): String {
        if (lat == null || lon == null || text.contains("geo:")) return text
        val tag = "geo:%.5f,%.5f".format(java.util.Locale.US, lat, lon)
        val at = text.indexOf(MARKER)
        return if (at <= 0) "$text $tag" else text.substring(0, at).trimEnd() + " " + tag + " " + text.substring(at)
    }

    private suspend fun pullOutbox(ctx: Context, client: CivilizationSyncClient, p: SyncPreferences) {
        val (code, text) = client.call(
            "GET", "/api/v1/mesh/outbox?gateway_id=${p.gatewayId}&limit=10&city_id=${p.cityId}"
        )
        if (code !in 200..299 || text.isBlank()) {
            if (code != -1) lastError = "outbox HTTP $code"
            return
        }
        lastPullOk = System.currentTimeMillis()
        val messages = JSONObject(text).optJSONArray("messages") ?: return
        if (messages.length() == 0) return
        val mesh = MeshServiceHolder.getUnifiedOrCreate(ctx)
        val acked = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val lat = if (m.isNull("lat")) null else m.optDouble("lat").takeIf { !it.isNaN() }
            val lon = if (m.isNull("lon")) null else m.optDouble("lon").takeIf { !it.isNaN() }
            val body = withGeo(m.getString("text"), lat, lon)
            val ts = System.currentTimeMillis().toULong()
            try {
                // Public on purpose: every phone should see command traffic and can use the
                // "Command" chip to show only that.
                mesh.sendMessage(body, emptyList(), null, Role.COMMAND, ts)
                val id = com.bitchat.android.sync.PacketIdUtil.computeBroadcastMessageIdHex(
                    senderPeerIDHex = mesh.myPeerID, timestamp = ts,
                    payload = body.toByteArray(Charsets.UTF_8)
                ).uppercase()
                AppStateStore.addPublicMessage(
                    BitchatMessage(
                        id = id,
                        sender = COMMAND_SENDER,
                        content = body,
                        timestamp = Date(ts.toLong()),
                        isRelay = false,
                        senderPeerID = mesh.myPeerID,
                        category = Role.COMMAND,
                        deliveryStatus = DeliveryStatus.Sent
                    )
                )
                acked.put(m.getString("id"))
            } catch (e: Exception) {
                Log.w(TAG, "broadcast of ${m.optString("id")} failed: ${e.message}")
            }
            delay(400) // let BLE drain between messages
        }
        if (acked.length() > 0) {
            client.call(
                "POST", "/api/v1/mesh/outbox/ack",
                JSONObject().put("gatewayId", p.gatewayId).put("ids", acked).toString()
            )
        }
    }

    // ---------------------------------------------------------- places ----
    private suspend fun refreshPlaces(ctx: Context, client: CivilizationSyncClient) {
        val (lc, ltext) = client.call("GET", "/api/v1/lifelines", auth = false)
        if (lc !in 200..299) {
            if (lc != -1) lastError = "lifelines HTTP $lc"
            return
        }
        val occupancy = HashMap<String, Pair<Int, Int>>()
        val (sc, stext) = client.call("GET", "/api/v1/shelters", auth = false)
        if (sc in 200..299) {
            val arr = JSONArray(stext)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                occupancy[o.optString("id")] = o.optInt("capacity") to o.optInt("occupancy")
            }
        }
        val mesh = try { MeshServiceHolder.getUnifiedOrCreate(ctx) } catch (_: Exception) { null }
        val origin = mesh?.myPeerID ?: "command"
        val registry = ShelterRegistry.getInstance(ctx)
        val existing = registry.snapshot().associateBy { it.id }
        val places = ArrayList<Shelter>()
        val arr = JSONArray(ltext)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val kind = o.optString("kind")
            val emoji = PLACE_KINDS[kind] ?: continue
            val loc = o.optJSONArray("location") ?: continue
            val lon = loc.optDouble(0)
            val lat = loc.optDouble(1)
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) continue
            val rawId = o.optString("id")
            val (cap, occ) = occupancy[rawId] ?: ((if (o.isNull("capacity")) 0 else o.optInt("capacity")) to 0)
            val status = if (cap > 0 && occ >= cap) ShelterStatus.FULL else ShelterStatus.OPEN
            val id = "cc-$rawId".take(60)
            val name = "$emoji ${o.optString("name")}".take(80)
            val prev = existing[id]
            // Peer IDs rotate, and peers only accept a shelter from its origin, so a
            // record re-announced under a new ID gets a new version.
            val same = prev != null && prev.name == name && prev.status == status &&
                prev.capacity == cap && prev.lat == lat && prev.lon == lon &&
                prev.originPeerID == origin
            places += Shelter(
                id = id, name = name, lat = lat, lon = lon, capacity = cap,
                role = Role.COMMAND, status = status,
                version = if (same) prev!!.version else System.currentTimeMillis(),
                originPeerID = origin
            )
        }
        places.forEach {
            registry.ingest(it)
            AppStateStore.recordShelter(it, verified = true)
        }
        lastPlacesOk = System.currentTimeMillis()

        // Gossip the nearest ones so phones without signal get them too, then cache routes.
        val here = LastFix.get(ctx)
        val sorted = if (here != null) places.sortedBy { RouteCache.metres(here.first, here.second, it.lat, it.lon) } else places
        if (mesh != null) {
            sorted.take(MESH_GOSSIP_LIMIT).forEach {
                try { mesh.broadcastShelter(it) } catch (_: Exception) { }
                delay(200)
            }
        }
        if (here != null) {
            sorted.filter { it.status == ShelterStatus.OPEN }.take(ROUTE_PRECOMPUTE).forEach {
                try { RouteCache.fetchAndStore(ctx, here.first, here.second, it) } catch (_: Exception) { }
            }
        }
        Log.i(TAG, "places: ${places.size} centres cached")
    }
}
