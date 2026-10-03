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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                        lat = "%.5f".format(loc.latitude)
                        lon = "%.5f".format(loc.longitude)
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
                    lat = "%.5f".format(loc.latitude)
                    lon = "%.5f".format(loc.longitude)
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
    val roleChoices = if (isResponder) listOf(localRole) else listOf(Role.CIVILIAN)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                if (isResponder) "Register a ${Role.displayLabel(localRole)} point" else "Register an SOS beacon",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Auto-filled with your current GPS. Edit to override or skip cell coverage — use it to plant a static staging point.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedShelters, key = { it.shelter.id }) { vs ->
                    val s: Shelter = vs.shelter
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(shelterStatusColorArgb(s.status)),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.size(12.dp)
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge)
                            val distLabel: String? = myLat?.let { la ->
                                myLon?.let { lo ->
                                    val arr = FloatArray(1)
                                    Location.distanceBetween(la, lo, s.lat, s.lon, arr)
                                    " · ${formatDistance(arr[0])}"
                                }
                            }
                            Text(
                                "${Role.displayLabel(s.role)} · room for ${s.capacity} · ${s.status.name.lowercase()}${distLabel ?: ""} · ${if (vs.verified) "verified" else "unverified"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (vs.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { onDeleteShelter(s) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(if (isResponder) "Publish a node from your current location" else "Publish SOS beacon", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (e.g. RS Puram Staging)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it; userEditedPosition = true },
                    label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it; userEditedPosition = true },
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { fillLocation() }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Use my location")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!isResponder) {
                Text("Shared as: SOS beacon (civilian)", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Shared as: ${Role.displayLabel(localRole)}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ShelterStatus.entries.forEach { st ->
                    FilterChip(
                        selected = status == st,
                        onClick = { status = st },
                        label = { Text(st.name) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = capacity,
                onValueChange = { capacity = it.filter { c -> c.isDigit() } },
                label = { Text("Capacity (people)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val la = lat.toDoubleOrNull() ?: return@Button
                        val lo = lon.toDoubleOrNull() ?: return@Button
                        val cap = capacity.toIntOrNull() ?: 0
                        onPublish(name.trim().ifBlank { "Node ${System.currentTimeMillis() % 10000}" }, la, lo, cap, selectedRole, status)
                        name = ""
                    },
                    enabled = lat.isNotBlank() && lon.isNotBlank()
                ) { Text(if (isResponder) "Publish to the mesh" else "Send SOS beacon") }
                Button(onClick = onOpenMap) { Text("Open Map") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}