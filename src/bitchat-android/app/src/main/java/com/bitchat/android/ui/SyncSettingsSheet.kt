package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.services.SyncPreferences
import com.bitchat.android.ui.design.AppleButton
import com.bitchat.android.ui.design.GroupDivider
import com.bitchat.android.ui.design.GroupFieldRow
import com.bitchat.android.ui.design.GroupRow
import com.bitchat.android.ui.design.GroupToggleRow
import com.bitchat.android.ui.design.InsetGroup
import com.bitchat.android.ui.theme.AppleColors
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.util.SyncWorkScheduler
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Command centre link: what this phone does as a gateway when it has internet. Grouped like
 * iOS Settings, most important first; the advanced self-hosted ingest sits at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit
) {
    if (!isPresented) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = SyncPreferences.getInstance(ctx)
    val palette = LocalBitchatPalette.current

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

    BitchatBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Command centre link",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
            )
            Text(
                "With internet, this phone passes what the mesh heard to the command centre, " +
                    "brings its alerts and dispatches back onto the mesh, and saves centres and routes " +
                    "for offline guidance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            InsetGroup(header = "Gateway") {
                GroupToggleRow(
                    title = "Sync when online",
                    checked = enabled,
                    onCheckedChange = { enabled = it; prefs.enabled = it },
                    icon = Icons.Rounded.CloudSync,
                    iconTint = AppleColors.BlueLight,
                )
                GroupDivider(inset = 58.dp)
                GroupToggleRow(
                    title = "Relay command centre alerts",
                    subtitle = "Broadcast its alerts, closures and dispatches on the mesh",
                    checked = listen,
                    onCheckedChange = { listen = it; prefs.listenToCommand = it },
                    icon = Icons.Rounded.Campaign,
                    iconTint = AppleColors.OrangeLight,
                )
            }

            InsetGroup(header = "Command centre", footer = "The gateway key is MESH_GATEWAY_KEY on the API.") {
                GroupFieldRow(
                    label = "Address", value = url,
                    onValueChange = { url = it; prefs.endpointUrl = it },
                    placeholder = "https://api.example.org",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                GroupDivider()
                GroupFieldRow(
                    label = "Gateway key", value = gatewayKey,
                    onValueChange = { gatewayKey = it; prefs.gatewayKey = it },
                    placeholder = "Required", secure = true,
                )
                GroupDivider()
                GroupFieldRow(
                    label = "City", value = cityId,
                    onValueChange = { cityId = it; prefs.cityId = it },
                    placeholder = "pune",
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            }

            @Suppress("UNUSED_VARIABLE") val refresh = tick
            val lastAt = prefs.lastSyncedAt
            val lastStr = if (lastAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastAt)) else "Never"
            val err = prefs.lastSyncError
            val link = try { com.bitchat.android.vlm.IndradhanuGateway.settingsJson() } catch (_: Exception) { emptyMap() }
            fun at(key: String): String {
                val t = (link[key] as? Long) ?: 0L
                return if (t > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t)) else "Never"
            }
            InsetGroup(
                header = "Status",
                footer = if (!err.isNullOrBlank()) "Last error: $err" else null,
            ) {
                GroupRow(title = "Last sync", value = lastStr)
                GroupDivider()
                GroupRow(title = "Alerts pulled", value = at("last_pull_ok_ms"))
                GroupDivider()
                GroupRow(title = "Centres saved", value = at("last_places_ok_ms"))
                GroupDivider()
                GroupRow(title = "Waiting to send", value = "${link["queued"] ?: 0}")
                GroupDivider()
                GroupRow(title = "This gateway", value = "${link["gateway_id"] ?: ""}")
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppleButton(
                    text = if (busy) "Syncing…" else "Sync now",
                    loading = busy,
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
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
                )
                if (result.isNotBlank()) {
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.startsWith("Linked")) palette.positive else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            InsetGroup(header = "Advanced") {
                GroupToggleRow(
                    title = "Use Tor when available",
                    subtitle = "Through the local Arti proxy",
                    checked = useTor,
                    onCheckedChange = { useTor = it; prefs.useTor = it },
                    icon = Icons.Rounded.Security,
                    iconTint = AppleColors.IndigoLight,
                )
                GroupDivider(inset = 58.dp)
                GroupToggleRow(
                    title = "Local ingest server",
                    subtitle = "Self-contained demo: POST /ingest on this phone",
                    checked = ingestEnabled,
                    onCheckedChange = { ingestEnabled = it; prefs.localIngestEnabled = it },
                    icon = Icons.Rounded.Dns,
                    iconTint = androidx.compose.ui.graphics.Color(0xFF8E8E93),
                )
                if (ingestEnabled) {
                    GroupDivider()
                    GroupFieldRow(
                        label = "Port", value = port,
                        onValueChange = {
                            port = it.filter { c -> c.isDigit() }.take(5)
                            prefs.localIngestPort = port.toIntOrNull() ?: prefs.localIngestPort
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
