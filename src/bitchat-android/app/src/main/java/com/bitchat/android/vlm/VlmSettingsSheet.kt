package com.bitchat.android.vlm

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
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.ui.design.GroupDivider
import com.bitchat.android.ui.design.GroupFieldRow
import com.bitchat.android.ui.design.GroupRow
import com.bitchat.android.ui.design.GroupToggleRow
import com.bitchat.android.ui.design.InsetGroup
import com.bitchat.android.ui.design.StatusPill
import com.bitchat.android.ui.design.SegmentedControl
import com.bitchat.android.ui.theme.AppleColors
import com.bitchat.android.ui.theme.BitchatMonoFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Camera / VLM API: lets the surveillance pipeline on a nearby computer post detections into
 * the mesh through this phone. Detections reach only Gov and Command phones (AudiencePolicy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlmSettingsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    VlmSettingsManager.initialize(context)

    var enabled by remember { mutableStateOf(VlmSettingsManager.isEnabled()) }
    var httpPort by remember { mutableStateOf(VlmSettingsManager.getHttpPort().toString()) }
    var silenceEnabled by remember { mutableStateOf(VlmSettingsManager.isSilenceEnabled()) }
    var rateLimit by remember { mutableStateOf(VlmSettingsManager.getRateLimitMs().toString()) }
    var defaultDestType by remember { mutableStateOf(VlmSettingsManager.getDefaultDestination()?.type ?: "") }
    var defaultDestId by remember { mutableStateOf(VlmSettingsManager.getDefaultDestination()?.id ?: "") }
    var saved by remember { mutableStateOf(false) }

    if (!isPresented) return

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
                "Camera alerts",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
            )
            Text(
                "Lets the camera pipeline on a nearby computer send detections into the mesh through " +
                    "this phone. Only Gov and Command phones show them; the command centre gets them " +
                    "through any gateway.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            VlmStatusSection(enabled = enabled)

            InsetGroup {
                GroupToggleRow(
                    title = "Accept camera alerts",
                    subtitle = "Open the local API to the camera pipeline",
                    checked = enabled,
                    onCheckedChange = { enabled = it; VlmSettingsManager.setEnabled(it) },
                    icon = Icons.Rounded.Videocam,
                    iconTint = AppleColors.BlueLight,
                )
                GroupDivider(inset = 58.dp)
                GroupToggleRow(
                    title = "Silence",
                    subtitle = "Pause all camera messages",
                    checked = silenceEnabled,
                    onCheckedChange = { silenceEnabled = it; VlmSettingsManager.setSilenceEnabled(it) },
                    icon = Icons.Rounded.NotificationsOff,
                    iconTint = AppleColors.IndigoLight,
                )
            }

            InsetGroup(
                header = "Connection",
                footer = "Over USB: adb forward tcp:$httpPort tcp:$httpPort",
            ) {
                GroupFieldRow(
                    label = "Port", value = httpPort,
                    onValueChange = { p ->
                        httpPort = p.filter { it.isDigit() }.take(5)
                        httpPort.toIntOrNull()?.let { VlmSettingsManager.setHttpPort(it) }
                    },
                    placeholder = "8765",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                GroupDivider()
                GroupFieldRow(
                    label = "Rate limit", value = rateLimit,
                    onValueChange = { l ->
                        rateLimit = l.filter { it.isDigit() }.take(7)
                        rateLimit.toLongOrNull()?.let { VlmSettingsManager.setRateLimitMs(it) }
                    },
                    placeholder = "5000 ms",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            InsetGroup(
                header = "Default destination",
                footer = "Messages that name no destination go here. Leave empty to broadcast.",
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val idx = when (defaultDestType) { "peer" -> 1; "channel" -> 2; else -> 0 }
                    SegmentedControl(
                        options = listOf("Broadcast", "Person", "Channel"),
                        selectedIndex = idx,
                        onSelect = { i ->
                            saved = false
                            defaultDestType = when (i) { 1 -> "peer"; 2 -> "channel"; else -> "" }
                        },
                    )
                }
                if (defaultDestType.isNotBlank()) {
                    GroupDivider()
                    GroupFieldRow(
                        label = if (defaultDestType == "peer") "Peer id" else "Channel",
                        value = defaultDestId,
                        onValueChange = { defaultDestId = it.trim(); saved = false },
                        placeholder = if (defaultDestType == "peer") "Peer id" else "#channel",
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    )
                }
                GroupDivider()
                GroupRow(
                    title = if (saved) "Saved" else "Save destination",
                    accent = !saved,
                    enabled = defaultDestType.isBlank() || defaultDestId.isNotBlank(),
                    onClick = {
                        if (defaultDestType.isNotBlank() && defaultDestId.isNotBlank()) {
                            VlmSettingsManager.setDefaultDestination(VlmDestination(defaultDestType, defaultDestId))
                        } else if (defaultDestType.isBlank()) {
                            VlmSettingsManager.setDefaultDestination(null)
                        }
                        saved = true
                    },
                )
            }

            VlmApiUsageInfo()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun VlmStatusSection(enabled: Boolean) {
    val context = LocalContext.current
    val palette = LocalBitchatPalette.current
    val status by remember(enabled) { mutableStateOf(VlmMessageHandler.getStatus(context)) }
    val wifiIp = status["wifi_ip"] as? String ?: "0.0.0.0"
    val hasValidWifi = wifiIp != "0.0.0.0"

    InsetGroup(header = "Status") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(
                text = when {
                    !enabled -> "Off"
                    !hasValidWifi -> "On · no Wi-Fi"
                    else -> "On · ${status["peers_count"] ?: 0} peers"
                },
                dotColor = when {
                    !enabled -> Color(0xFF8E8E93)
                    !hasValidWifi -> AppleColors.OrangeLight
                    else -> palette.positive
                },
            )
            if (enabled && hasValidWifi) {
                Text("Address for the camera computer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "http://$wifiIp:${status["http_port"]}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = BitchatMonoFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else if (enabled) {
                Text("Join a Wi-Fi network to reach the camera computer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VlmApiUsageInfo() {
    val endpoints = listOf(
        "POST /send/text" to "Send a text alert",
        "POST /send/image" to "Send an image (multipart)",
        "POST /send/analysis" to "Send image + description",
        "GET /status" to "API status",
        "POST /silence" to "Toggle silence",
    )
    InsetGroup(header = "API", footer = "Broadcast intent: com.bitchat.android.VLM_API") {
        endpoints.forEachIndexed { i, (endpoint, desc) ->
            if (i > 0) GroupDivider()
            GroupRow(title = desc, value = endpoint)
        }
    }
}
