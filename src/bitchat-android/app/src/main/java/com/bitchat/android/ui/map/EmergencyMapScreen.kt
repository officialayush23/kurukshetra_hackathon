package com.bitchat.android.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationSearching
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import com.bitchat.android.model.Role
import com.bitchat.android.model.ShelterStatus
import com.bitchat.android.services.AppStateStore
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The BiChat map: a full-screen Common Operating Picture over the mesh.
 *
 * Layout, top to bottom: a floating top bar (or, while guiding, a compact navigation panel), the
 * map itself, a column of small floating controls riding just above the sheet, and a draggable
 * sheet. Collapsed, the sheet answers "what is this, how far, what do I do"; expanded it holds
 * the full record, related reports, nearby responders and every action. The sheet stops short of
 * the top so the map stays visible behind it.
 *
 * What this screen will not do: it has no road network, no traffic and no blockage feed, so it
 * never shows an ETA, never calls a line a route, and says so wherever a person might assume
 * otherwise. Turn-by-turn on real roads is handed to an external maps app.
 */

private const val SCENE_CENTER_LAT = 11.0168
private const val SCENE_CENTER_LON = 76.9558
private const val PREFS = "bitchat_map"
private const val KEY_LAT = "last_lat"
private const val KEY_LON = "last_lon"
private const val KEY_ACC = "last_acc"
private const val KEY_TIME = "last_time"
private const val KEY_DARK = "dark_basemap"
private const val KEY_RINGS = "distance_rings"

/** A fix older than this is called stale on screen. */
private const val STALE_FIX_MS = 2 * 60_000L
/** Within this of the destination, guidance says you have arrived. */
private const val ARRIVED_M = 30f
/** A report this close to the direct line is called out as on the way. */
private const val CORRIDOR_M = 150.0
/** Reports this close to an incident are listed as related to it. */
private const val RELATED_M = 300f

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyMapScreen(
    entities: List<MapEntity>,
    localRole: Role,
    meshPeerCount: Int,
    initialFocusId: String?,
    guidanceTargetId: String?,
    onGuidanceTargetChange: (String?) -> Unit,
    onOpenInChat: (MapEntity) -> Unit,
    onMessagePeer: (MapEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    remember { configureOsmdroid(context); true }
    // The composer may still hold focus (and the keyboard) from the chat underneath.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus() }

    // ------------------------------------------------------------ view state
    var filter by rememberSaveable { mutableStateOf(MapFilter.ALL) }
    var selectedId by rememberSaveable { mutableStateOf(initialFocusId) }
    var following by rememberSaveable { mutableStateOf(initialFocusId == null) }
    var darkBasemap by remember { mutableStateOf(prefs.getBoolean(KEY_DARK, true)) }
    var showRings by remember { mutableStateOf(prefs.getBoolean(KEY_RINGS, false)) }
    var filtersOpen by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf(restoreFix(prefs)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasInternet by rememberInternetState(context)

    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------- location
    var hasLocationPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms -> hasLocationPermission = perms.values.any { it } }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(4000L)
            .setMinUpdateDistanceMeters(3f)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()
        var lastPersist = 0L
        fun accept(loc: android.location.Location) {
            val f = MapFix(
                lat = loc.latitude,
                lon = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                timeMs = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                restored = false,
            )
            fix = f
            com.bitchat.android.net.LastFix.remember(f.lat, f.lon)
            // The last known position outlives the screen, so reopening the map with no
            // signal still shows where you were — marked as restored, never as live.
            if (f.timeMs - lastPersist > 15_000) {
                lastPersist = f.timeMs
                persistFix(prefs, f)
            }
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(::accept)
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            client.lastLocation.addOnSuccessListener { loc -> if (loc != null && fix?.restored != false) accept(loc) }
        } catch (_: Exception) { }
        onDispose { try { client.removeLocationUpdates(callback) } catch (_: Exception) { } }
    }

    // ------------------------------------------------------------- derived
    val selected = entities.firstOrNull { it.id == selectedId }
    val guidanceTarget = entities.firstOrNull { it.id == guidanceTargetId }
    val shown = remember(entities, filter, selectedId, guidanceTargetId) {
        entities.filter { filter.matches(it.kind) || it.id == selectedId || it.id == guidanceTargetId }
    }
    val counts = remember(entities) { entities.groupingBy { it.kind }.eachCount() }

    // ---- A real route to the guidance target, from RouteCache: the one saved for this
    // place when the person is still near where it started, else fetched once while there
    // is signal (and saved for offline). Without either, guidance stays the direct line.
    var guideRoute by remember { mutableStateOf<RouteCache.Route?>(null) }
    var routeLoading by remember { mutableStateOf(false) }
    val routeKey = guidanceTarget?.let { routeCacheKey(it) }
    // Re-checked when the person has moved far enough that a saved route no longer starts here.
    val fixCell = fix?.let { "%.3f,%.3f".format(it.lat, it.lon) }
    val currentFix = fix
    val fixStale = currentFix != null && (currentFix.restored || now - currentFix.timeMs > STALE_FIX_MS)
    fun distanceTo(e: MapEntity): Float? = currentFix?.let { distanceMeters(it.lat, it.lon, e.lat, e.lon) }

    // Something got deselected underneath us (deleted, filtered): drop the selection.
    LaunchedEffect(selectedId, entities) {
        if (selectedId != null && entities.none { it.id == selectedId }) selectedId = null
    }
    LaunchedEffect(guidanceTargetId, entities) {
        if (guidanceTargetId != null && entities.none { it.id == guidanceTargetId }) onGuidanceTargetChange(null)
    }

    // --------------------------------------------------------- critical alerts
    // A new SOS while the map is open: a banner, a haptic, and the marker pulses. The camera
    // is not moved — the person may be in the middle of something else on this map.
    val seenCritical = remember { mutableStateOf(entities.filter { it.isCritical }.map { it.id }.toSet()) }
    var banner by remember { mutableStateOf<MapEntity?>(null) }
    LaunchedEffect(entities) {
        val fresh = entities.filter { it.isCritical && it.id !in seenCritical.value }
        if (fresh.isNotEmpty()) {
            seenCritical.value = seenCritical.value + fresh.map { it.id }
            val foreign = fresh.filter { !it.isOwn }
            if (foreign.isNotEmpty()) {
                banner = foreign.maxByOrNull { it.timestampMs ?: 0L }
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    // ---------------------------------------------------------------- sheet
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val inspecting = selected != null && selected.id != guidanceTargetId
    val peek: Dp = when {
        inspecting -> 176.dp
        guidanceTarget != null -> 92.dp
        else -> 138.dp
    } + navBarBottom

    // ------------------------------------------------------------------ map
    // The overlay is created once; it reaches the current handlers through this holder, which
    // is refreshed after every composition (see the SideEffect below).
    val taps = remember { TapHandlers() }
    val overlay = remember {
        EmergencyMapOverlay(
            context = context,
            onEntityTap = { id -> taps.onEntity(id) },
            onClusterTap = { members -> taps.onCluster(members) },
            onUserGesture = { following = false },
        )
    }
    val mapView = remember {
        val start = initialFocusId?.let { id -> entities.firstOrNull { it.id == id } }
        val startFix = fix
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTilesScaledToDpi(true)
            setHorizontalMapRepetitionEnabled(false)
            setVerticalMapRepetitionEnabled(false)
            setMinZoomLevel(3.0)
            setMaxZoomLevel(19.5)
            overlayManager.tilesOverlay.setLoadingBackgroundColor(MAP_LOADING_BG)
            overlayManager.tilesOverlay.setLoadingLineColor(MAP_LOADING_LINE)
            overlays.add(overlay)
            when {
                start != null -> {
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(start.lat, start.lon))
                }
                startFix != null -> {
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(startFix.lat, startFix.lon))
                }
                else -> {
                    controller.setZoom(13.0)
                    controller.setCenter(GeoPoint(SCENE_CENTER_LAT, SCENE_CENTER_LON))
                }
            }
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    fun animateTo(lat: Double, lon: Double, zoom: Double? = null, ms: Long = 650L) {
        mapView.controller.animateTo(GeoPoint(lat, lon), zoom, ms)
    }

    fun fitTo(points: List<Pair<Double, Double>>, maxZoom: Double = 17.0) {
        val b = boundsOf(points) ?: return
        if (mapView.width == 0 || mapView.height == 0) return
        val box = BoundingBox(b[0], b[1], b[2], b[3])
        val border = with(density) { 72.dp.roundToPx() }
        runCatching { mapView.zoomToBoundingBox(box, true, border, maxZoom, 750L) }
    }

    /** Tap on a marker (or on nothing, `null`): select it, or clear the selection. */
    fun onEntityTap(id: String?) {
        layersOpen = false
        filtersOpen = false
        if (id == null) {
            // While guiding, "nothing selected" means the destination is.
            if (selectedId != guidanceTargetId) selectedId = guidanceTargetId
        } else {
            selectedId = id
            following = false
        }
        scope.launch { runCatching { sheetState.partialExpand() } }
    }

    fun select(e: MapEntity) {
        onEntityTap(e.id)
        animateTo(e.lat, e.lon, max(mapView.zoomLevelDouble, 15.5))
    }

    /** Opening a cluster zooms to exactly the area its members cover. */
    fun onClusterTap(members: List<MapEntity>) {
        following = false
        layersOpen = false
        val pts = members.map { it.lat to it.lon }
        val spread = boundsOf(pts, padDeg = 0.0)
        if (spread == null || (spread[0] - spread[2] < 1e-4 && spread[1] - spread[3] < 1e-4)) {
            members.firstOrNull()?.let { animateTo(it.lat, it.lon, mapView.zoomLevelDouble + 2.0) }
        } else {
            fitTo(pts, maxZoom = 18.0)
        }
    }

    SideEffect {
        taps.onEntity = ::onEntityTap
        taps.onCluster = ::onClusterTap
    }

    // Push state into the overlay only when it changes; a redraw repaints every tile.
    LaunchedEffect(shown, selectedId, guidanceTargetId, currentFix, showRings, guideRoute) {
        overlay.guidanceRoute = guideRoute?.points
        overlay.entities = shown
        overlay.selectedId = selectedId
        overlay.guidanceTargetId = guidanceTargetId
        overlay.fix = currentFix
        overlay.showRings = showRings
        mapView.invalidate()
    }
    LaunchedEffect(routeKey, fixCell, hasInternet) {
        val target = guidanceTarget
        val f = fix
        if (routeKey == null || target == null || f == null) {
            guideRoute = null
            routeLoading = false
            return@LaunchedEffect
        }
        val cached = withContext(Dispatchers.IO) { RouteCache.lookup(context, routeKey, f.lat, f.lon) }
        if (cached != null) {
            guideRoute = cached
            routeLoading = false
            return@LaunchedEffect
        }
        // A route saved from somewhere else no longer applies from here.
        if (guideRoute?.let { distanceMeters(f.lat, f.lon, it.fromLat, it.fromLon) > RouteCache.REUSE_RADIUS_M } == true) {
            guideRoute = null
        }
        if (!hasInternet || guideRoute != null) return@LaunchedEffect
        routeLoading = true
        val fetched = withContext(Dispatchers.IO) {
            runCatching { RouteCache.fetchAndStore(context, f.lat, f.lon, target.asRouteTarget()) }.getOrNull()
        }
        routeLoading = false
        if (fetched != null && guidanceTargetId == target.id) guideRoute = fetched
    }

    LaunchedEffect(darkBasemap) {
        mapView.overlayManager.tilesOverlay.setColorFilter(if (darkBasemap) darkTileFilter() else null)
        mapView.invalidate()
        prefs.edit().putBoolean(KEY_DARK, darkBasemap).apply()
    }
    LaunchedEffect(showRings) { prefs.edit().putBoolean(KEY_RINGS, showRings).apply() }

    // Keep the point the camera centres on in the visible band between the top bar and the
    // sheet, so a selected marker is never parked underneath the sheet.
    LaunchedEffect(peek) {
        val topPx = with(density) { (statusTop + 72.dp).roundToPx() }
        val peekPx = with(density) { peek.roundToPx() }
        mapView.setMapCenterOffset(0, (topPx - peekPx) / 2)
        mapView.invalidate()
    }

    // Follow the person while they let it; never drag the camera back after they pan.
    LaunchedEffect(currentFix?.lat, currentFix?.lon, following) {
        val f = currentFix ?: return@LaunchedEffect
        if (following && !f.restored) animateTo(f.lat, f.lon, null, 500L)
    }

    // First open with nothing to centre on but entities: frame them all.
    LaunchedEffect(Unit) {
        if (initialFocusId == null && fix == null && entities.isNotEmpty()) {
            delay(120)
            fitTo(entities.map { it.lat to it.lon }, maxZoom = 15.5)
        }
    }

    // Switching to a filter whose matches are all off-screen brings them into view.
    var filterInitialised by remember { mutableStateOf(false) }
    LaunchedEffect(filter) {
        if (!filterInitialised) {
            filterInitialised = true
            return@LaunchedEffect
        }
        if (selected != null && !filter.matches(selected.kind) && selected.id != guidanceTargetId) {
            selectedId = null
        }
        if (filter == MapFilter.ALL) return@LaunchedEffect
        val matches = entities.filter { filter.matches(it.kind) }
        if (matches.isEmpty()) return@LaunchedEffect
        val box = mapView.projection.boundingBox
        if (matches.none { box.contains(it.lat, it.lon) }) {
            following = false
            fitTo(matches.map { it.lat to it.lon }, maxZoom = 16.0)
        }
    }

    // ------------------------------------------------------------- actions
    val actions = remember(context) { MapActions(context) }

    fun startGuidance(e: MapEntity) {
        onGuidanceTargetChange(e.id)
        selectedId = e.id
        val f = fix
        // Frame both ends first, so the person sees the whole line before setting off. Following
        // stays off until they ask for it ("Me"); turning it on here would immediately pull the
        // camera back to them and undo the framing.
        following = false
        if (f != null) {
            fitTo(listOf(f.lat to f.lon, e.lat to e.lon), maxZoom = 17.0)
        } else {
            animateTo(e.lat, e.lon, max(mapView.zoomLevelDouble, 15.0))
        }
        scope.launch { runCatching { sheetState.partialExpand() } }
    }

    fun stopGuidance() {
        onGuidanceTargetChange(null)
    }

    fun recenter() {
        val f = fix
        if (f == null) {
            if (!hasLocationPermission) {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            } else {
                Toast.makeText(context, "Waiting for a location fix…", Toast.LENGTH_SHORT).show()
            }
            return
        }
        following = true
        animateTo(f.lat, f.lon, max(mapView.zoomLevelDouble, 15.0), 600L)
    }

    BackHandler {
        if (layersOpen || filtersOpen) {
            layersOpen = false
            filtersOpen = false
        } else if (sheetState.currentValue == SheetValue.Expanded) {
            scope.launch { runCatching { sheetState.partialExpand() } }
        } else if (selectedId != null && selectedId != guidanceTargetId) {
            selectedId = guidanceTargetId
        } else {
            onDismiss()
        }
    }

    // --------------------------------------------------------------- layout
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peek,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContainerColor = MapPalette.Surface,
        sheetContentColor = MapPalette.TextPrimary,
        sheetTonalElevation = 0.dp,
        sheetShadowElevation = 0.dp,
        sheetDragHandle = { SheetHandle() },
        containerColor = Color(0xFF0B0F17),
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = screenHeight * 0.66f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = navBarBottom + 12.dp)
            ) {
                when {
                    selected != null && inspecting -> EntitySheet(
                        entity = selected,
                        distanceM = distanceTo(selected),
                        fix = currentFix,
                        now = now,
                        entities = entities,
                        localRole = localRole,
                        onNavigate = { startGuidance(selected) },
                        onExternal = { actions.openExternal(selected) },
                        onShare = { actions.share(selected) },
                        onCopy = { actions.copy(selected) },
                        onOpenInChat = if (selected.messageId != null) ({ onOpenInChat(selected) }) else null,
                        onMessagePeer = if (selected.peerID != null && !selected.isOwn) ({ onMessagePeer(selected) }) else null,
                        onSelect = { select(it) },
                        onClose = { onEntityTap(null) },
                    )
                    guidanceTarget != null -> GuidanceSheet(
                        target = guidanceTarget,
                        route = guideRoute,
                        fix = currentFix,
                        entities = entities,
                        onExit = { stopGuidance() },
                        onExternal = { actions.openExternal(guidanceTarget) },
                        onRecenter = { recenter() },
                        onSelect = { select(it) },
                    )
                    else -> OverviewSheet(
                        entities = entities.filter { filter.matches(it.kind) },
                        allCount = entities.size,
                        filter = filter,
                        fix = currentFix,
                        now = now,
                        localRole = localRole,
                        onSelect = { select(it) },
                    )
                }
            }
        },
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Map" },
                factory = { mapView },
            )

            // Floating controls, riding just above the sheet. Read in the layout phase so
            // dragging the sheet does not recompose (and repaint) the map on every frame.
            var controlsHeight by remember { mutableIntStateOf(0) }
            val marginPx = with(density) { 12.dp.roundToPx() }
            val fallbackTop = with(density) { (screenHeight - peek).roundToPx() }
            val hideBelowPx = with(density) { (screenHeight * 0.42f).roundToPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        val sheetTop = runCatching { sheetState.requireOffset() }.getOrDefault(fallbackTop.toFloat())
                        val y = (sheetTop - controlsHeight - marginPx).roundToInt()
                        // Tuck the controls away once the sheet is pulled most of the way up.
                        IntOffset(if (sheetTop < hideBelowPx) 4000 else 0, y)
                    }
                    .padding(end = 12.dp)
                    .onSizeChanged { controlsHeight = it.height }
            ) {
                MapControls(
                    following = following && currentFix != null,
                    hasFix = currentFix != null,
                    layersOpen = layersOpen,
                    onLayers = { layersOpen = !layersOpen; filtersOpen = false },
                    onZoomIn = { mapView.controller.zoomIn(250L) },
                    onZoomOut = { mapView.controller.zoomOut(250L) },
                    onLocate = { recenter() },
                )
            }

            // Layers & legend: a small floating card beside the controls, not a full sheet.
            AnimatedVisibility(
                visible = layersOpen,
                enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 8 },
                exit = fadeOut(tween(120)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        val sheetTop = runCatching { sheetState.requireOffset() }.getOrDefault(fallbackTop.toFloat())
                        IntOffset(0, (sheetTop - with(density) { 340.dp.toPx() }).roundToInt().coerceAtLeast(with(density) { (statusTop + 72.dp).roundToPx() }))
                    }
                    .padding(end = 68.dp)
            ) {
                LayersCard(
                    dark = darkBasemap,
                    rings = showRings,
                    counts = counts,
                    onDark = { darkBasemap = it },
                    onRings = { showRings = it },
                )
            }

            // Top: the navigation panel while guiding, otherwise the bar + filters.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = statusTop + 8.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (guidanceTarget != null) {
                    NavigationPanel(
                        target = guidanceTarget,
                        route = guideRoute,
                        routeLoading = routeLoading,
                        hasInternet = hasInternet,
                        fix = currentFix,
                        fixStale = fixStale,
                        now = now,
                        localRole = localRole,
                        entities = entities,
                        onExit = { stopGuidance() },
                    )
                } else {
                    TopBar(
                        filter = filter,
                        hasInternet = hasInternet,
                        meshPeerCount = meshPeerCount,
                        fix = currentFix,
                        fixStale = fixStale,
                        hasLocationPermission = hasLocationPermission,
                        now = now,
                        onBack = onDismiss,
                        onFilters = { filtersOpen = !filtersOpen; layersOpen = false },
                    )
                    AnimatedVisibility(
                        visible = filtersOpen,
                        enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(120)) + shrinkVertically(tween(140)),
                    ) {
                        FilterRow(
                            filter = filter,
                            counts = counts,
                            onPick = {
                                filter = it
                                filtersOpen = false
                            },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = banner != null,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 2 },
                ) {
                    banner?.let { b ->
                        SosBanner(
                            entity = b,
                            distanceM = distanceTo(b),
                            onView = {
                                banner = null
                                if (!filter.matches(b.kind)) filter = MapFilter.ALL
                                select(b)
                            },
                            onDismiss = { banner = null },
                        )
                    }
                }
            }
        }
    }
}

/** Indirection between the long-lived overlay and the handlers of the latest composition. */
private class TapHandlers {
    var onEntity: (String?) -> Unit = {}
    var onCluster: (List<MapEntity>) -> Unit = {}
}

// ===================================================================== pieces

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(Color(0x40FFFFFF))
        )
    }
}

/** The rounded dark surface every floating element sits on. */
@Composable
private fun FloatingSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MapPalette.Surface,
        contentColor = MapPalette.TextPrimary,
        border = BorderStroke(1.dp, MapPalette.Stroke),
        shadowElevation = 6.dp,
    ) { content() }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MapPalette.TextPrimary,
    active: Boolean = false,
    size: Dp = 44.dp,
) {
    FloatingSurface(modifier = modifier.size(size), shape = CircleShape) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (active) Color(0x1FFFFFFF) else Color.Transparent)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TopBar(
    filter: MapFilter,
    hasInternet: Boolean,
    meshPeerCount: Int,
    fix: MapFix?,
    fixStale: Boolean,
    hasLocationPermission: Boolean,
    now: Long,
    onBack: () -> Unit,
    onFilters: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to chat", onBack)
        FloatingSurface(modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(22.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                ConnectivityLine(
                    hasInternet = hasInternet,
                    meshPeerCount = meshPeerCount,
                    fix = fix,
                    fixStale = fixStale,
                    hasLocationPermission = hasLocationPermission,
                    now = now,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        FloatingSurface(shape = RoundedCornerShape(22.dp), modifier = Modifier.height(44.dp)) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onFilters)
                    .padding(horizontal = 12.dp)
                    .semantics { contentDescription = "Filter map, showing ${filter.label}" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                if (filter != MapFilter.ALL) {
                    Spacer(Modifier.width(6.dp))
                    Text(filter.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Connectivity in one quiet line. Offline is a state, not an error: the mesh keeps delivering
 * SOS and reports without internet, and the map keeps drawing from its tile cache.
 */
@Composable
private fun ConnectivityLine(
    hasInternet: Boolean,
    meshPeerCount: Int,
    fix: MapFix?,
    fixStale: Boolean,
    hasLocationPermission: Boolean,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val (dot, text) = when {
        !hasInternet -> MapPalette.Stale to buildString {
            append("Offline · cached map")
            if (meshPeerCount > 0) append(" · mesh $meshPeerCount")
        }
        else -> MapPalette.Live to buildString {
            append("Live")
            if (meshPeerCount > 0) append(" · mesh $meshPeerCount")
        }
    }
    val locationNote = when {
        !hasLocationPermission -> "location off"
        fix == null -> "locating…"
        fix.restored -> "last known ${formatAge(now - fix.timeMs)}"
        fixStale -> "location ${formatAge(now - fix.timeMs)}"
        else -> null
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (locationNote != null) "$text · $locationNote" else text,
            style = MaterialTheme.typography.labelSmall,
            color = MapPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FilterRow(filter: MapFilter, counts: Map<MapEntityKind, Int>, onPick: (MapFilter) -> Unit) {
    FloatingSurface(shape = RoundedCornerShape(22.dp)) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MapFilter.entries.forEach { f ->
                val n = if (f == MapFilter.ALL) counts.values.sum() else f.kinds.sumOf { counts[it] ?: 0 }
                val active = f == filter
                val accent = f.kinds.singleOrNull()?.let { MapPalette.of(it) } ?: MapPalette.TextPrimary
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) Color(0x26FFFFFF) else Color.Transparent)
                        .border(
                            1.dp,
                            if (active) accent.copy(alpha = 0.7f) else Color(0x1AFFFFFF),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { onPick(f) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (f != MapFilter.ALL) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        f.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (n == 0 && !active) MapPalette.TextTertiary else MapPalette.TextPrimary,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("$n", style = MaterialTheme.typography.labelMedium, color = MapPalette.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun MapControls(
    following: Boolean,
    hasFix: Boolean,
    layersOpen: Boolean,
    onLayers: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onLocate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
        RoundIconButton(Icons.Rounded.Layers, "Map layers and legend", onLayers, active = layersOpen)
        FloatingSurface(shape = RoundedCornerShape(22.dp), modifier = Modifier.width(44.dp)) {
            Column {
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable(onClick = onZoomIn)
                        .semantics { contentDescription = "Zoom in" },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(20.dp)) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MapPalette.Stroke))
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable(onClick = onZoomOut)
                        .semantics { contentDescription = "Zoom out" },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Remove, null, modifier = Modifier.size(20.dp)) }
            }
        }
        RoundIconButton(
            icon = if (following) Icons.Rounded.MyLocation else Icons.Rounded.LocationSearching,
            description = if (following) "Following your location" else "Recenter on my location",
            onClick = onLocate,
            tint = if (following) MapPalette.You else if (hasFix) MapPalette.TextPrimary else MapPalette.TextTertiary,
            size = 48.dp,
        )
    }
}

@Composable
private fun LayersCard(
    dark: Boolean,
    rings: Boolean,
    counts: Map<MapEntityKind, Int>,
    onDark: (Boolean) -> Unit,
    onRings: (Boolean) -> Unit,
) {
    FloatingSurface(modifier = Modifier.widthIn(max = 260.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Basemap")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill("Dark", dark) { onDark(true) }
                ChoicePill("Standard", !dark) { onDark(false) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Distance rings", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = rings,
                    onCheckedChange = onRings,
                    colors = SwitchDefaults.colors(checkedTrackColor = MapPalette.You),
                )
            }
            SectionLabel("On this map")
            MapEntityKind.entries.forEach { kind ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindBadge(kind, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(kind.pluralLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${counts[kind] ?: 0}", style = MaterialTheme.typography.labelMedium, color = MapPalette.TextSecondary)
                }
            }
            Text(
                "Only SOS pulses. A faded marker is unverified or closed.",
                style = MaterialTheme.typography.labelSmall,
                color = MapPalette.TextTertiary,
            )
        }
    }
}

@Composable
private fun ChoicePill(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Color(0x26FFFFFF) else Color.Transparent)
            .border(1.dp, if (active) Color(0x66FFFFFF) else Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MapPalette.TextTertiary,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
    )
}

/** The map marker, as a Compose element: the same badge the overlay draws. */
@Composable
fun KindBadge(kind: MapEntityKind, size: Dp = 36.dp, muted: Boolean = false) {
    val color = MapPalette.of(kind)
    val glyphTint = when (kind) {
        MapEntityKind.INCIDENT, MapEntityKind.AMBULANCE, MapEntityKind.SHELTER ->
            if (muted) Color.White else Color(0xFF0B0F17)
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (muted) color.copy(alpha = 0.45f) else color),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = glyphFor(kind),
            contentDescription = kind.label,
            tint = glyphTint,
            modifier = Modifier.size(size * 0.58f)
        )
    }
}

@Composable
private fun SosBanner(entity: MapEntity, distanceM: Float?, onView: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xF2331013),
        contentColor = MapPalette.TextPrimary,
        border = BorderStroke(1.dp, MapPalette.Sos.copy(alpha = 0.6f)),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KindBadge(MapEntityKind.SOS, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("New SOS ${entity.shortRef}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(distanceM?.let { formatMeters(it) + " away" }, entity.reporter?.let { "from $it" })
                        .joinToString(" · ").ifBlank { entity.title },
                    style = MaterialTheme.typography.labelSmall,
                    color = MapPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "View",
                style = MaterialTheme.typography.labelLarge,
                color = MapPalette.Sos,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onView)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .semantics { contentDescription = "Dismiss" },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp), tint = MapPalette.TextSecondary) }
        }
    }
}

// ================================================================ navigation

private data class Hazard(val entity: MapEntity, val aheadM: Double, val offLineM: Double)

private fun hazardsAlong(
    fix: MapFix,
    target: MapEntity,
    entities: List<MapEntity>,
    route: RouteCache.Route? = null,
): List<Hazard> {
    val pts = route?.points
    if (pts != null && pts.size >= 2) {
        val from = positionOnLine(pts, fix.lat, fix.lon)?.alongM ?: 0.0
        return entities.asSequence()
            .filter { it.id != target.id && (it.kind == MapEntityKind.SOS || it.kind == MapEntityKind.INCIDENT) }
            .mapNotNull { e ->
                val p = positionOnLine(pts, e.lat, e.lon) ?: return@mapNotNull null
                if (p.offM > CORRIDOR_M || p.alongM < from) null else Hazard(e, p.alongM - from, p.offM)
            }
            .sortedBy { it.aheadM }
            .toList()
    }
    return entities.asSequence()
        .filter { it.id != target.id && (it.kind == MapEntityKind.SOS || it.kind == MapEntityKind.INCIDENT) }
        .mapNotNull { e ->
            val off = distanceToSegmentMeters(e.lat, e.lon, fix.lat, fix.lon, target.lat, target.lon)
            if (off > CORRIDOR_M) null
            else Hazard(e, alongSegmentMeters(e.lat, e.lon, fix.lat, fix.lon, target.lat, target.lon), off)
        }
        .sortedBy { it.aheadM }
        .toList()
}

/** RouteCache stores by shelter id for registry nodes, and by entity id for messages. */
private fun routeCacheKey(e: MapEntity): String = if (e.messageId == null) e.id.removePrefix("node:") else e.id

/** RouteCache fetches to a Shelter; only its id and position are used. */
private fun MapEntity.asRouteTarget() = com.bitchat.android.model.Shelter(
    id = routeCacheKey(this), name = title, lat = lat, lon = lon,
    capacity = capacity ?: 0, role = reporterRole, status = status ?: ShelterStatus.OPEN,
    version = 0L, originPeerID = peerID ?: "",
)

@Composable
private fun NavigationPanel(
    target: MapEntity,
    route: RouteCache.Route?,
    routeLoading: Boolean,
    hasInternet: Boolean,
    fix: MapFix?,
    fixStale: Boolean,
    now: Long,
    localRole: Role,
    entities: List<MapEntity>,
    onExit: () -> Unit,
) {
    val straight = fix?.let { distanceMeters(it.lat, it.lon, target.lat, target.lon) }
    val bearing = fix?.let { bearingDegrees(it.lat, it.lon, target.lat, target.lon) }
    // Along the saved route when there is one, measured from the nearest point on it.
    val onRoute = if (fix != null && route != null) positionOnLine(route.points, fix.lat, fix.lon) else null
    val routeLeft = onRoute?.let { (it.totalM - it.alongM).coerceAtLeast(0.0).toFloat() }
    val distance = routeLeft ?: straight
    val minutesLeft = if (route != null && onRoute != null && onRoute.totalM > 0 && route.seconds.isFinite()) {
        ((route.seconds / 60.0) * (onRoute.totalM - onRoute.alongM) / onRoute.totalM).roundToInt().coerceAtLeast(1)
    } else null
    val hazards = remember(fix?.lat, fix?.lon, target.id, entities, route) {
        if (fix == null) emptyList() else hazardsAlong(fix, target, entities, route)
    }
    val arrived = straight != null && straight < ARRIVED_M
    val actor = roleActorLabel(localRole) ?: "You"

    FloatingSurface(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(target.kind, size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$actor → ${target.headline}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MapPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onExit)
                        .semantics { contentDescription = "End navigation" },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Close, null, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(4.dp))
            if (arrived) {
                Text("You have arrived", style = MaterialTheme.typography.titleLarge, color = MapPalette.Live, fontWeight = FontWeight.SemiBold)
            } else if (distance != null && bearing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Navigation,
                        contentDescription = null,
                        tint = MapPalette.You,
                        modifier = Modifier.size(22.dp).rotate(bearing)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(formatMeters(distance), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (route != null) {
                            listOfNotNull(
                                if (route.profile == "foot") "walk" else "by road",
                                minutesLeft?.let { "~$it min" },
                            ).joinToString(" · ")
                        } else "${compassWord(bearing)} · direct",
                        style = MaterialTheme.typography.bodySmall,
                        color = MapPalette.TextSecondary
                    )
                }
            } else {
                Text("Waiting for your location…", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            when {
                route != null -> StatusLine(
                    color = MapPalette.Route,
                    text = "Saved ${if (route.profile == "foot") "walking" else "road"} route from " +
                        "${formatAge(System.currentTimeMillis() - route.savedAt)} · works offline · " +
                        "closures since then are not known",
                )
                routeLoading -> StatusLine(
                    color = MapPalette.Uncertain,
                    text = "Finding a route… showing the direct line meanwhile",
                )
                else -> StatusLine(
                    color = MapPalette.Uncertain,
                    text = if (hasInternet) "No route found · direct line, not a road · road conditions unknown"
                    else "Offline, no saved route · direct line, not a road · road conditions unknown",
                )
            }
            if (onRoute != null && onRoute.offM > 120) {
                StatusLine(color = MapPalette.Stale, text = "About ${formatMeters(onRoute.offM.toFloat())} off the saved route")
            }
            if (fix != null && fixStale) {
                StatusLine(color = MapPalette.Stale, text = "Your location is ${formatAge(now - fix.timeMs)} old")
            }
            hazards.firstOrNull()?.let { h ->
                StatusLine(
                    color = if (h.entity.isCritical) MapPalette.Sos else MapPalette.Report,
                    text = "${h.entity.headline} ${formatMeters(h.aheadM.toFloat())} ahead, " +
                        "${formatMeters(h.offLineM.toFloat())} off the way" +
                        if (hazards.size > 1) " · +${hazards.size - 1} more" else "",
                )
            }
        }
    }
}

@Composable
private fun StatusLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, end = 6.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MapPalette.TextSecondary)
    }
}

@Composable
private fun ColumnScope.GuidanceSheet(
    target: MapEntity,
    route: RouteCache.Route?,
    fix: MapFix?,
    entities: List<MapEntity>,
    onExit: () -> Unit,
    onExternal: () -> Unit,
    onRecenter: () -> Unit,
    onSelect: (MapEntity) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PillButton("End", Icons.Rounded.Close, onExit, tone = PillTone.Danger, modifier = Modifier.weight(1f))
        PillButton("Open in Maps", Icons.AutoMirrored.Rounded.OpenInNew, onExternal, modifier = Modifier.weight(1.4f))
        PillButton("Me", Icons.Rounded.MyLocation, onRecenter, modifier = Modifier.weight(0.9f))
    }
    Spacer(Modifier.height(16.dp))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("About this guidance")
        Text(
            if (route != null) {
                "This is a ${if (route.profile == "foot") "walking" else "road"} route to " +
                    "${target.headline}, fetched while there was signal and saved on this phone, so " +
                    "it keeps working offline. It does not know about closures reported since " +
                    "it was saved — check the reports along it below."
            } else {
                "No route is saved for this place from here. This shows the straight line and " +
                    "distance to ${target.headline}, updated as you move. A route is fetched and " +
                    "saved automatically next time there is signal; for live directions, open it " +
                    "in a maps app."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MapPalette.TextSecondary,
        )
        val hazards = if (fix == null) emptyList() else hazardsAlong(fix, target, entities, route)
        val along = if (route != null) "Along the route" else "Along the line"
        SectionLabel(if (hazards.isEmpty()) along else "$along · ${hazards.size}")
        if (fix == null) {
            Text("Needs your location.", style = MaterialTheme.typography.bodySmall, color = MapPalette.TextTertiary)
        } else if (hazards.isEmpty()) {
            Text(
                "No SOS or reports within ${CORRIDOR_M.toInt()} m of it. That is only what " +
                    "has reached this phone — it is not a safety check.",
                style = MaterialTheme.typography.bodySmall,
                color = MapPalette.TextTertiary,
            )
        } else {
            hazards.take(6).forEach { h ->
                EntityRow(
                    entity = h.entity,
                    trailing = "${formatMeters(h.aheadM.toFloat())} ahead",
                    subtitle = "${formatMeters(h.offLineM.toFloat())} off it",
                    onClick = { onSelect(h.entity) },
                )
            }
        }
        SectionLabel("Destination")
        EntityRow(entity = target, trailing = null, subtitle = target.kind.label, onClick = { onSelect(target) })
    }
}

// ================================================================== entity

@Composable
private fun ColumnScope.EntitySheet(
    entity: MapEntity,
    distanceM: Float?,
    fix: MapFix?,
    now: Long,
    entities: List<MapEntity>,
    localRole: Role,
    onNavigate: () -> Unit,
    onExternal: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onOpenInChat: (() -> Unit)?,
    onMessagePeer: (() -> Unit)?,
    onSelect: (MapEntity) -> Unit,
    onClose: () -> Unit,
) {
    val muted = entity.verified == false || entity.status == ShelterStatus.CLOSED
    // ---- collapsed: what, how far, how serious, one primary action
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KindBadge(entity.kind, size = 40.dp, muted = muted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entity.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val facts = listOfNotNull(
                distanceM?.let { d ->
                    fix?.let { formatMeters(d) + " " + compassWord(bearingDegrees(it.lat, it.lon, entity.lat, entity.lon)) }
                },
                entity.timestampMs?.let { formatAge(now - it) },
                statusLabel(entity.status),
                if (entity.verified == false) "unverified" else null,
            )
            Text(
                facts.joinToString(" · ").ifBlank { entity.kind.label },
                style = MaterialTheme.typography.labelMedium,
                color = MapPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .semantics { contentDescription = "Close details" },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Close, null, modifier = Modifier.size(18.dp), tint = MapPalette.TextSecondary) }
    }
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PillButton(
            label = "Navigate",
            icon = Icons.Rounded.Navigation,
            onClick = onNavigate,
            tone = if (entity.isCritical) PillTone.Critical else PillTone.Primary,
            modifier = Modifier.weight(1f),
        )
        if (onOpenInChat != null || onMessagePeer != null) {
            RoundAction(Icons.Rounded.Forum, if (onOpenInChat != null) "Open conversation" else "Message") {
                (onOpenInChat ?: onMessagePeer)?.invoke()
            }
        }
        RoundAction(Icons.Rounded.Share, "Share location", onShare)
        RoundAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open in external maps", onExternal)
    }

    // ---- expanded: the full record
    Spacer(Modifier.height(18.dp))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (entity.body != null) {
            SectionLabel(if (entity.isCritical) "SOS message" else "Report")
            Text(entity.body, style = MaterialTheme.typography.bodyMedium)
        }
        SectionLabel("Status")
        DetailRow("Type", entity.kind.label)
        entity.reporter?.let { DetailRow(if (entity.messageId != null) "From" else "Published by", it + roleSuffix(entity.reporterRole)) }
        entity.timestampMs?.let { DetailRow("Received", formatAge(now - it)) }
        statusLabel(entity.status)?.let { DetailRow("State", it) }
        entity.capacity?.let { DetailRow("Capacity", "$it people") }
        entity.verified?.let { v ->
            DetailRow(
                "Trust",
                if (v) "Verified — signed by an announced Gov node" else "Unverified — publisher is not an announced Gov node"
            )
        }
        if (entity.isOwn && entity.messageId != null) {
            val acks = AppStateStore.acksFor(entity.messageId)
            DetailRow(
                "Reach",
                if (acks.isEmpty()) "No acknowledgements yet"
                else "${acks.size} peer${if (acks.size == 1) "" else "s"}" +
                    if (acks.any { it.reachedResponder }) " · reached a responder" else ""
            )
        }
        DetailRow("Position", "%.5f, %.5f".format(entity.lat, entity.lon), onClick = onCopy, trailingIcon = Icons.Rounded.ContentCopy)

        // Reports near this one: the same place, seen by other people.
        val related = remember(entity.id, entities) {
            entities.filter {
                it.id != entity.id && (it.kind == MapEntityKind.INCIDENT || it.kind == MapEntityKind.SOS) &&
                    distanceMeters(entity.lat, entity.lon, it.lat, it.lon) <= RELATED_M
            }.sortedByDescending { it.timestampMs ?: 0L }
        }
        if (related.isNotEmpty()) {
            SectionLabel("Reports within ${RELATED_M.toInt()} m · ${related.size}")
            related.take(5).forEach { r ->
                EntityRow(
                    entity = r,
                    trailing = formatMeters(distanceMeters(entity.lat, entity.lon, r.lat, r.lon)),
                    subtitle = listOfNotNull(r.reporter, r.timestampMs?.let { formatAge(now - it) }).joinToString(" · "),
                    onClick = { onSelect(r) },
                )
            }
        }

        // Who could help here: responder nodes on the registry, nearest first.
        if (!entity.kind.isResponder) {
            val responders = remember(entity.id, entities) {
                entities.filter { it.kind.isResponder && it.status != ShelterStatus.CLOSED }
                    .sortedBy { distanceMeters(entity.lat, entity.lon, it.lat, it.lon) }
            }
            SectionLabel("Responder nodes nearby")
            if (responders.isEmpty()) {
                Text(
                    "No responder node has published a position to this phone yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MapPalette.TextTertiary,
                )
            } else {
                responders.take(3).forEach { r ->
                    EntityRow(
                        entity = r,
                        trailing = formatMeters(distanceMeters(entity.lat, entity.lon, r.lat, r.lon)),
                        subtitle = listOfNotNull(r.kind.label, statusLabel(r.status), if (r.verified == false) "unverified" else null)
                            .joinToString(" · "),
                        onClick = { onSelect(r) },
                    )
                }
            }
        } else {
            val needs = remember(entity.id, entities) {
                entities.filter { it.kind == MapEntityKind.SOS }
                    .sortedBy { distanceMeters(entity.lat, entity.lon, it.lat, it.lon) }
            }
            if (needs.isNotEmpty()) {
                SectionLabel("Nearest SOS to this node")
                needs.take(3).forEach { s ->
                    EntityRow(
                        entity = s,
                        trailing = formatMeters(distanceMeters(entity.lat, entity.lon, s.lat, s.lon)),
                        subtitle = listOfNotNull(s.reporter, s.timestampMs?.let { formatAge(now - it) }).joinToString(" · "),
                        onClick = { onSelect(s) },
                    )
                }
            }
        }

        SectionLabel("Navigation")
        Text(
            "Navigate uses a walking route saved on this phone when there is one (fetched while " +
                "online, kept for offline). Without one it shows the direct line and distance, which " +
                "is guidance, not a route. Neither knows about closures reported since.",
            style = MaterialTheme.typography.bodySmall,
            color = MapPalette.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Open in Maps", Icons.AutoMirrored.Rounded.OpenInNew, onExternal, modifier = Modifier.weight(1f))
            PillButton("Share", Icons.Rounded.Share, onShare, modifier = Modifier.weight(1f))
        }
        if (onOpenInChat != null || onMessagePeer != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onOpenInChat?.let { PillButton("Open in chat", Icons.Rounded.Forum, it, modifier = Modifier.weight(1f)) }
                onMessagePeer?.let {
                    PillButton(
                        if (entity.messageId != null) "Message sender" else "Message node",
                        Icons.Rounded.Forum,
                        it,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (localRole == Role.UNSET && entity.isCritical) {
            Text(
                "Set a responder role in settings to appear as a responder on other people's maps.",
                style = MaterialTheme.typography.labelSmall,
                color = MapPalette.TextTertiary,
            )
        }
    }
}

private fun roleSuffix(role: Role): String = when (role) {
    Role.UNSET -> ""
    else -> " · ${Role.displayLabel(role)}"
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MapPalette.TextTertiary, modifier = Modifier.width(104.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        trailingIcon?.let {
            Icon(it, contentDescription = "Copy", tint = MapPalette.TextTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun EntityRow(entity: MapEntity, trailing: String?, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x0DFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KindBadge(entity.kind, size = 28.dp, muted = entity.verified == false || entity.status == ShelterStatus.CLOSED)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entity.headline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MapPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = MapPalette.TextSecondary)
        }
    }
}

private enum class PillTone { Primary, Critical, Neutral, Danger }

@Composable
private fun PillButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
) {
    val (bg, fg) = when (tone) {
        PillTone.Primary -> MapPalette.You to Color.White
        PillTone.Critical -> MapPalette.Sos to Color.White
        PillTone.Danger -> Color(0x33FF453A) to Color(0xFFFF8A80)
        PillTone.Neutral -> Color(0x1AFFFFFF) to MapPalette.TextPrimary
    }
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0x1AFFFFFF))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp)) }
}

// ================================================================ overview

@Composable
private fun ColumnScope.OverviewSheet(
    entities: List<MapEntity>,
    allCount: Int,
    filter: MapFilter,
    fix: MapFix?,
    now: Long,
    localRole: Role,
    onSelect: (MapEntity) -> Unit,
) {
    val sorted = remember(entities, fix?.lat, fix?.lon) {
        if (fix == null) entities.sortedByDescending { it.timestampMs ?: 0L }
        else entities.sortedBy { distanceMeters(fix.lat, fix.lon, it.lat, it.lon) }
    }
    val sos = sorted.filter { it.isCritical }
    val responderKinds = if (localRole == Role.AMBULANCE || localRole == Role.FIRE || localRole == Role.GOV) null
    else MapEntityKind.AMBULANCE
    val nearestHelp = responderKinds?.let { k -> sorted.firstOrNull { it.kind == k && it.status != ShelterStatus.CLOSED } }
    val lead: MapEntity? = sos.firstOrNull() ?: nearestHelp ?: sorted.firstOrNull()

    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    allCount == 0 -> "Nothing on the map yet"
                    filter == MapFilter.ALL -> "$allCount on the map"
                    else -> "${entities.size} ${filter.label.lowercase()} of $allCount"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (sos.isNotEmpty()) {
                Text(
                    "${sos.size} SOS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MapPalette.Sos,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MapPalette.Sos.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (lead == null) {
            Text(
                if (allCount == 0) "SOS messages, reports sent with a location, and responder nodes appear here as they reach this phone over the mesh."
                else "Nothing matches this filter.",
                style = MaterialTheme.typography.bodySmall,
                color = MapPalette.TextSecondary,
            )
        } else {
            val label = when {
                lead.isCritical -> "Nearest SOS"
                lead === nearestHelp -> "Nearest ambulance"
                else -> if (fix != null) "Nearest" else "Latest"
            }
            EntityRow(
                entity = lead,
                trailing = fix?.let { formatMeters(distanceMeters(it.lat, it.lon, lead.lat, lead.lon)) },
                subtitle = listOfNotNull(label, lead.timestampMs?.let { formatAge(now - it) }, statusLabel(lead.status)).joinToString(" · "),
                onClick = { onSelect(lead) },
            )
        }
        if (sorted.size > 1) {
            Spacer(Modifier.height(18.dp))
            SectionLabel(if (fix != null) "By distance" else "Most recent")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sorted.filter { it.id != lead?.id }.take(40).forEach { e ->
                    EntityRow(
                        entity = e,
                        trailing = fix?.let { formatMeters(distanceMeters(it.lat, it.lon, e.lat, e.lon)) },
                        subtitle = listOfNotNull(
                            e.kind.label,
                            e.timestampMs?.let { formatAge(now - it) },
                            statusLabel(e.status),
                            if (e.verified == false) "unverified" else null,
                        ).joinToString(" · "),
                        onClick = { onSelect(e) },
                    )
                }
            }
        }
    }
}

// ================================================================= helpers

private fun configureOsmdroid(context: Context) {
    try {
        Configuration.getInstance().apply {
            userAgentValue = "bitchat-android"
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = java.io.File(context.cacheDir, "osm_tiles").apply { mkdirs() }
            // Keep tiles usable for a month so the map keeps working long after losing internet.
            expirationOverrideDuration = 30L * 24 * 60 * 60 * 1000L
        }
    } catch (_: Exception) { }
}

private fun hasLocationPermission(context: Context): Boolean =
    ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun restoreFix(prefs: SharedPreferences): MapFix? {
    val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
    val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
    val time = prefs.getLong(KEY_TIME, 0L).takeIf { it > 0 } ?: return null
    val acc = prefs.getFloat(KEY_ACC, -1f).takeIf { it > 0f }
    return MapFix(lat, lon, acc, time, restored = true)
}

private fun persistFix(prefs: SharedPreferences, f: MapFix) {
    prefs.edit()
        .putString(KEY_LAT, f.lat.toString())
        .putString(KEY_LON, f.lon.toString())
        .putFloat(KEY_ACC, f.accuracyM ?: -1f)
        .putLong(KEY_TIME, f.timeMs)
        .apply()
}

/** Validated internet, observed for as long as the map is on screen. */
@Composable
private fun rememberInternetState(context: Context): androidx.compose.runtime.State<Boolean> {
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }
    val state = remember {
        mutableStateOf(
            runCatching {
                cm?.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }.getOrDefault(false)
        )
    }
    DisposableEffect(cm) {
        if (cm == null) return@DisposableEffect onDispose { }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            override fun onLost(network: Network) {
                state.value = false
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return state
}

/** Hand-offs to the rest of the phone: maps apps, the share sheet, the clipboard. */
private class MapActions(private val context: Context) {
    private fun osmUrl(e: MapEntity) =
        "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=17/%.5f/%.5f".format(e.lat, e.lon, e.lat, e.lon)

    fun openExternal(e: MapEntity) {
        val label = Uri.encode(e.headline)
        val geo = Uri.parse("geo:%.6f,%.6f?q=%.6f,%.6f(%s)".format(e.lat, e.lon, e.lat, e.lon, label))
        val intent = Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(osmUrl(e))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No maps app or browser installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun share(e: MapEntity) {
        val text = buildString {
            append(e.headline)
            append('\n')
            append("%.5f, %.5f".format(e.lat, e.lon))
            append('\n')
            append(osmUrl(e))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "Share location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun copy(e: MapEntity) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Location", "%.5f, %.5f".format(e.lat, e.lon)))
        Toast.makeText(context, "Coordinates copied", Toast.LENGTH_SHORT).show()
    }
}
