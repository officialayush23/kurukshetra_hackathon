package com.bitchat.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.ui.design.AppleButton
import com.bitchat.android.ui.design.ButtonKind
import com.bitchat.android.ui.design.SegmentedControl
import com.bitchat.android.ui.design.pressable
import com.bitchat.android.ui.theme.LocalBitchatPalette

/** What a person can report, and the command centre category each maps to (null = read the words). */
private enum class ReportKind(val label: String, val category: String?) {
    Flooding("Flooding", "flooded_road"),
    Waterlogging("Waterlogging", "waterlogging"),
    Fire("Fire", "fire"),
    Collapse("Building damage", null),
    Injury("Someone hurt", null),
    Other("Something else", null),
}

private enum class CrewStatus(val label: String, val kind: String) {
    Accepted("Accepted", "accepted"),
    OnSite("On site", "on_site"),
    Complete("Done", "complete"),
    Blocked("Road blocked", "route_blocked"),
}

/**
 * Report a hazard without internet. The report goes out over the mesh with this phone's
 * position, and reaches the command centre through the first phone that has signal. Field
 * crews get a second tab for their task status.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    isCrew: Boolean,
    initialDraft: String,
    onSendReport: (category: String?, text: String) -> Unit,
    onSendStatus: (kind: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var kind by rememberSaveable { mutableStateOf<ReportKind?>(null) }
    var status by rememberSaveable { mutableStateOf<CrewStatus?>(null) }
    var text by rememberSaveable { mutableStateOf(initialDraft) }

    BitchatBottomSheet(onDismissRequest = onDismiss) {
        val dismiss = LocalSheetDismiss.current ?: onDismiss
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (isCrew && tab == 1) "Update your status" else "Report a hazard",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isCrew) {
                SegmentedControl(listOf("Hazard", "My task"), tab, { tab = it })
            }
            if (!isCrew || tab == 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportKind.entries.forEach { k -> Choice(k.label, kind == k) { kind = if (kind == k) null else k } }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CrewStatus.entries.forEach { s -> Choice(s.label, status == s) { status = if (status == s) null else s } }
                }
            }
            NoteField(
                value = text,
                onValueChange = { text = it.take(280) },
                placeholder = if (isCrew && tab == 1) "Add a note (optional)" else "What do you see? Where exactly?",
            )
            Text(
                "Sent over the mesh with your location. It reaches the command centre through the first phone nearby that has internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val canSend = if (isCrew && tab == 1) status != null else (kind != null || text.isNotBlank())
            AppleButton(
                text = "Send",
                icon = Icons.AutoMirrored.Rounded.Send,
                enabled = canSend,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (isCrew && tab == 1) {
                        status?.let { onSendStatus(it.kind, text) }
                    } else {
                        val words = text.trim().ifEmpty { kind?.label ?: "" }
                        onSendReport(kind?.category, if (kind != null && text.isNotBlank()) "${kind!!.label}: $words" else words)
                    }
                    dismiss()
                },
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Send an SOS: one deliberate screen, one red button. The message carries this phone's
 * position so responders on the mesh can find the person.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SosSheet(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    BitchatBottomSheet(onDismissRequest = onDismiss) {
        val dismiss = LocalSheetDismiss.current ?: onDismiss
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Send SOS", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Everyone nearby on the mesh, and responders, will see your SOS and your location.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Trapped", "Injured", "Water rising", "Need evacuation", "Medical help").forEach { q ->
                    Choice(q, text == q) { text = if (text == q) "" else q }
                }
            }
            NoteField(text, { text = it.take(200) }, "Add details (optional)")
            AppleButton(
                text = "Send SOS",
                icon = Icons.Rounded.Sos,
                kind = ButtonKind.Emergency,
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
                onClick = { onSend(text.ifBlank { "Need help" }); dismiss() },
            )
            AppleButton("Cancel", dismiss, Modifier.fillMaxWidth(), kind = ButtonKind.Plain)
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    val palette = LocalBitchatPalette.current
    val scheme = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) scheme.onPrimary else scheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) scheme.primary else palette.groupedCell)
            .pressable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun NoteField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val palette = LocalBitchatPalette.current
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        minLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.groupedCell)
            .padding(14.dp),
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Box {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = palette.textTertiary)
                inner()
            }
        },
    )
}
