package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bitchat.android.services.SyncPreferences
import com.bitchat.android.util.SyncWorkScheduler
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit
) {
    if (!isPresented) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefs = SyncPreferences.getInstance(androidx.compose.ui.platform.LocalContext.current)

    var enabled by remember { mutableStateOf(prefs.enabled) }
    var url by remember { mutableStateOf(prefs.endpointUrl) }
    var useTor by remember { mutableStateOf(prefs.useTor) }
    var ingestEnabled by remember { mutableStateOf(prefs.localIngestEnabled) }
    var port by remember { mutableStateOf(prefs.localIngestPort.toString()) }
    var gatewayKey by remember { mutableStateOf(prefs.gatewayKey) }
    var cityId by remember { mutableStateOf(prefs.cityId) }
    var listen by remember { mutableStateOf(prefs.listenToCommand) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(com.bitchat.android.vlm.IndradhanuGateway.lastResult) }
    // The status lines below read preferences, which Compose cannot observe. Tick once a
    // second while the sheet is open so "Last sync" and the link line stay current.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Command Centre Link", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "When this phone has internet it pushes what the mesh heard (SOS, geotagged posts, VLM camera " +
                    "briefs, shelters) to the command centre, broadcasts the centre's alerts and dispatches on the " +
                    "mesh, and caches centres and walking routes for offline navigation. Off by default.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(12.dp))
            ToggleRow(
                label = "Enable sync on reconnect",
                checked = enabled,
                onCheckedChange = { enabled = it; prefs.enabled = it }
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; prefs.endpointUrl = it },
                label = { Text("Command centre API (e.g. https://api.example.com)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = gatewayKey,
                onValueChange = { gatewayKey = it; prefs.gatewayKey = it },
                label = { Text("Gateway key (MESH_GATEWAY_KEY)") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = cityId,
                onValueChange = { cityId = it; prefs.cityId = it },
                label = { Text("City id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            ToggleRow(
                label = "Listen to command centre (broadcast its alerts on the mesh)",
                checked = listen,
                onCheckedChange = { listen = it; prefs.listenToCommand = it }
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                label = "Route via local Tor (Arti) when available",
                checked = useTor,
                onCheckedChange = { useTor = it; prefs.useTor = it }
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            ToggleRow(
                label = "Run local ingest server (self-contained demo)",
                checked = ingestEnabled,
                onCheckedChange = { ingestEnabled = it; prefs.localIngestEnabled = it }
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }; prefs.localIngestPort = port.toIntOrNull() ?: prefs.localIngestPort },
                label = { Text("Local port (POST /ingest)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Button(
                onClick = {
                    busy = true
                    result = "Contacting the command centre…"
                    scope.launch {
                        result = try {
                            com.bitchat.android.vlm.IndradhanuGateway.syncNow()
                        } catch (e: Exception) {
                            "Sync failed: ${e.message}"
                        }
                        // Also queue the WorkManager flush, so a sync that failed on a
                        // flaky network retries by itself with backoff.
                        SyncWorkScheduler.scheduleFlush(ctx)
                        busy = false
                    }
                },
                enabled = !busy && url.isNotBlank()
            ) { Text(if (busy) "Syncing…" else "Sync now") }
            if (result.isNotBlank()) {
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.startsWith("Linked")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            @Suppress("UNUSED_VARIABLE") val refresh = tick
            val lastAt = prefs.lastSyncedAt
            val lastStr = if (lastAt > 0) DateFormat.getDateTimeInstance().format(Date(lastAt)) else "never"
            val err = prefs.lastSyncError
            Text("Last sync: $lastStr", style = MaterialTheme.typography.bodySmall)
            val link = try { com.bitchat.android.vlm.IndradhanuGateway.settingsJson() } catch (_: Exception) { emptyMap() }
            fun ago(key: String): String {
                val t = (link[key] as? Long) ?: 0L
                return if (t > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t)) else "never"
            }
            Text(
                "Gateway ${link["gateway_id"] ?: ""} · online ${link["online"] ?: false} · queued ${link["queued"] ?: 0}\n" +
                    "Alerts pulled: ${ago("last_pull_ok_ms")} · Centres cached: ${ago("last_places_ok_ms")}",
                style = MaterialTheme.typography.bodySmall
            )
            if (!err.isNullOrBlank()) {
                Text("Last error: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}