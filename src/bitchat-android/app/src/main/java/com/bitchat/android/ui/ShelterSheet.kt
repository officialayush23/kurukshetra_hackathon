package com.bitchat.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.House
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.bitchat.android.model.Role
import com.bitchat.android.model.Shelter
import com.bitchat.android.model.ShelterStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.map.formatDistance
import com.bitchat.android.ui.map.shelterStatusColorArgb
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelterSheet(
    isPresented: Boolean,
    shelters: List<AppStateStore.VerifiedShelter>,
    localRole: Role,
    onDeleteShelter: (Shelter) -> Unit,
    onPublish: (name: String, lat: Double, lon: Double, capacity: Int, role: Role, status: ShelterStatus) -> Unit,
    onOpenMap: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isPresented) return
    val context = LocalContext.current

    // Auto-fill lat/lon with current GPS, but only if the user hasn't typed
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var userEditedPosition by remember { mutableStateOf(false) }
    var fetchingLocation by remember { mutableStateOf(false) }
    // Live coordinates used for the nearest-to-me sort
    var myLat by remember { mutableStateOf<Double?>(null) }
    var myLon by remember { mutableStateOf<Double?>(null) }

    fun fillLocation() {
        val havePermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (!havePermission) return
        fetchingLocation = true
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancellation = CancellationTokenSource()
        try {
            client.lastLocation
                .addOnSuccessListener { loc: Location? ->
                    fetchingLocation = false
                    if (loc != null && !userEditedPosition) {
                        lat = "%.5f".format(java.util.Locale.US, loc.latitude)
                        lon = "%.5f".format(java.util.Locale.US, loc.longitude)
                        myLat = loc.latitude
                        myLon = loc.longitude
                    }
                }
                .addOnFailureListener { fetchingLocation = false }
            client.getCurrentLocation(
                com.google.android.gms.location.CurrentLocationRequest.Builder()
                    .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(15000)
                    .build(),
                cancellation.token
            ).addOnSuccessListener { loc ->
                if (loc != null && !userEditedPosition) {
                    lat = "%.5f".format(java.util.Locale.US, loc.latitude)
                    lon = "%.5f".format(java.util.Locale.US, loc.longitude)
                    myLat = loc.latitude
                    myLon = loc.longitude
                }
            }
        } catch (_: Exception) {
            fetchingLocation = false
        }
    }

    if (lat.isBlank() && lon.isBlank()) {
        androidx.compose.runtime.LaunchedEffect(Unit) { fillLocation() }
    }

    val isResponder = localRole == Role.AMBULANCE || localRole == Role.FIRE || localRole == Role.GOV || localRole == Role.COMMAND
    var selectedRole by remember(isResponder) { mutableStateOf(if (isResponder) localRole else Role.CIVILIAN) }
    var status by remember { mutableStateOf(ShelterStatus.OPEN) }
    var capacity by remember { mutableStateOf("50") }

    val sortedShelters = remember(shelters, myLat, myLon) {
        if (myLat != null && myLon != null) {
            shelters.sortedBy { vs ->
                FloatArray(1).also {
                    Location.distanceBetween(myLat!!, myLon!!, vs.shelter.lat, vs.shelter.lon, it)
                }[0]
            }
        } else shelters
    }

    val palette = com.bitchat.android.ui.theme.LocalBitchatPalette.current
    com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Places", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                com.bitchat.android.ui.design.AppleButton(
                    "Map", onOpenMap, kind = com.bitchat.android.ui.design.ButtonKind.Tinted,
                    icon = Icons.Rounded.Map, height = 36.dp,
                )
            }
            Text(
                "Shelters, staging points and SOS beacons shared on the mesh, nearest first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            com.bitchat.android.ui.design.InsetGroup(
                header = if (sortedShelters.isEmpty()) null else "Nearby · ${sortedShelters.size}",
            ) {
                if (sortedShelters.isEmpty()) {
                    Text(
                        "Nothing shared nearby yet. Places appear here as phones in range publish them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                sortedShelters.forEachIndexed { i, vs ->
                    val sh: Shelter = vs.shelter
                    if (i > 0) com.bitchat.android.ui.design.GroupDivider(inset = 58.dp)
                    val dist = myLat?.let { la ->
                        myLon?.let { lo ->
                            val arr = FloatArray(1)
                            Location.distanceBetween(la, lo, sh.lat, sh.lon, arr)
                            formatDistance(arr[0])
                        }
                    }
                    com.bitchat.android.ui.design.GroupRow(
                        title = sh.name,
                        subtitle = listOfNotNull(
                            Role.displayLabel(sh.role),
                            sh.capacity.takeIf { it > 0 }?.let { "room for $it" },
                            sh.status.name.lowercase().replaceFirstChar { it.uppercase() },
                            if (vs.verified) "verified" else "unverified",
                        ).joinToString(" · "),
                        icon = placeGlyph(sh.role),
                        iconTint = if (sh.status == ShelterStatus.CLOSED) Color(0xFF8E8E93)
                        else com.bitchat.android.ui.home.roleColor(sh.role),
                        value = dist,
                        trailing = {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable(onClickLabel = "Remove ${sh.name}") { onDeleteShelter(sh) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = palette.textTertiary, modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                }
            }

            com.bitchat.android.ui.design.InsetGroup(
                header = if (isResponder) "Share a ${Role.displayLabel(localRole)} point" else "Share an SOS beacon",
                footer = if (isResponder) "Shared on the mesh as ${Role.displayLabel(localRole)}. Filled with your current position; edit it to place a staging point elsewhere."
                else "Your SOS beacon is shared on the mesh with this position so responders can find you.",
            ) {
                com.bitchat.android.ui.design.GroupFieldRow(
                    label = "Name", value = name, onValueChange = { name = it },
                    placeholder = if (isResponder) "RS Puram staging" else "Your name or place",
                )
                com.bitchat.android.ui.design.GroupDivider()
                com.bitchat.android.ui.design.GroupFieldRow(
                    label = "Latitude", value = lat,
                    onValueChange = { lat = it; userEditedPosition = true },
                    placeholder = if (fetchingLocation) "Locating…" else "18.52040",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                com.bitchat.android.ui.design.GroupDivider()
                com.bitchat.android.ui.design.GroupFieldRow(
                    label = "Longitude", value = lon,
                    onValueChange = { lon = it; userEditedPosition = true },
                    placeholder = if (fetchingLocation) "Locating…" else "73.85670",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                com.bitchat.android.ui.design.GroupDivider()
                com.bitchat.android.ui.design.GroupRow(
                    title = "Use my location",
                    accent = true,
                    onClick = { userEditedPosition = false; fillLocation() },
                    trailing = { Icon(Icons.Rounded.MyLocation, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                )
                if (isResponder) {
                    com.bitchat.android.ui.design.GroupDivider()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        com.bitchat.android.ui.design.SegmentedControl(
                            options = ShelterStatus.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                            selectedIndex = ShelterStatus.entries.indexOf(status),
                            onSelect = { status = ShelterStatus.entries[it] },
                        )
                    }
                    com.bitchat.android.ui.design.GroupDivider()
                    com.bitchat.android.ui.design.GroupFieldRow(
                        label = "Capacity", value = capacity,
                        onValueChange = { capacity = it.filter { c -> c.isDigit() }.take(6) },
                        placeholder = "People",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            com.bitchat.android.ui.design.AppleButton(
                text = if (isResponder) "Share on the mesh" else "Send SOS beacon",
                kind = if (isResponder) com.bitchat.android.ui.design.ButtonKind.Filled
                else com.bitchat.android.ui.design.ButtonKind.Emergency,
                enabled = lat.trim().replace(',', '.').toDoubleOrNull() != null && lon.trim().replace(',', '.').toDoubleOrNull() != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                onClick = {
                    val la = lat.trim().replace(',', '.').toDoubleOrNull() ?: return@AppleButton
                    val lo = lon.trim().replace(',', '.').toDoubleOrNull() ?: return@AppleButton
                    val cap = capacity.toIntOrNull() ?: 0
                    onPublish(name.trim().ifBlank { "Node ${System.currentTimeMillis() % 10000}" }, la, lo, cap, selectedRole, status)
                    name = ""
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A glyph per kind of place: same as the map, so a row and its marker match. */
private fun placeGlyph(role: Role): androidx.compose.ui.graphics.vector.ImageVector = when (role) {
    Role.AMBULANCE -> Icons.Rounded.LocalHospital
    Role.FIRE -> Icons.Rounded.LocalFireDepartment
    Role.GOV, Role.COMMAND -> Icons.Rounded.AccountBalance
    Role.CIVILIAN -> Icons.Rounded.Sos
    Role.UNSET -> Icons.Rounded.House
}
