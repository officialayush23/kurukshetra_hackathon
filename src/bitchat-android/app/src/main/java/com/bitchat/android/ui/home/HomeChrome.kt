package com.bitchat.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.House
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.model.Role
import com.bitchat.android.shell.ShellControls
import com.bitchat.android.shell.ShellMode
import com.bitchat.android.ui.design.pressable
import com.bitchat.android.ui.map.MapPalette
import com.bitchat.android.ui.theme.LocalBitchatPalette

/** The colour a role is drawn in everywhere (chips, avatars, map). Red stays SOS-only. */
fun roleColor(role: Role): Color = when (role) {
    Role.AMBULANCE -> MapPalette.Ambulance
    Role.FIRE -> MapPalette.Fire
    Role.GOV -> MapPalette.Gov
    Role.COMMAND -> MapPalette.Gov
    Role.CIVILIAN -> MapPalette.Shelter
    Role.UNSET -> Color(0xFF8E8E93)
}

/**
 * The person's own avatar: initials on their role colour. Tap opens Settings; three quick
 * taps clear all data (the long-standing panic gesture, kept where it always was).
 */
@Composable
fun SelfAvatar(
    name: String,
    role: Role,
    onClick: () -> Unit,
    onTripleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    val initials = remember(name) {
        name.split(' ', '_', '-', '.').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifBlank { "?" }
    }
    Box(
        modifier
            .size(44.dp)
            .semantics { contentDescription = "Settings"; this.role = SemanticsRole.Button }
            .pointerInput(Unit) {
                detectTapGestures {
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTap < 500) taps + 1 else 1
                    lastTap = now
                    if (taps >= 3) { taps = 0; onTripleClick() } else onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(roleColor(role)),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

/**
 * Four actions that matter in an emergency, one tap each, always in the same place:
 * SOS (the only red thing on the screen), Report, Map, Places.
 */
@Composable
fun QuickActionBar(
    sosCount: Int,
    placesCount: Int,
    onSos: () -> Unit,
    onReport: () -> Unit,
    onMap: () -> Unit,
    onPlaces: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickAction("SOS", Icons.Rounded.Sos, MaterialTheme.colorScheme.error, onSos, Modifier.weight(1f), emphasised = true)
        QuickAction("Report", Icons.Rounded.EditNote, MapPalette.Report, onReport, Modifier.weight(1f))
        QuickAction("Map", Icons.Rounded.Map, MaterialTheme.colorScheme.primary, onMap, Modifier.weight(1f), badge = sosCount.takeIf { it > 0 })
        QuickAction(
            "Places", Icons.Rounded.House, MapPalette.Shelter, onPlaces, Modifier.weight(1f),
            count = placesCount.takeIf { it > 0 },
        )
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    badge: Int? = null,
    count: Int? = null,
) {
    val palette = LocalBitchatPalette.current
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (emphasised) tint else palette.inputButton)
                .pressable(onClick = onClick)
                .heightIn(min = 60.dp)
                .padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (emphasised) Color.White else tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                if (count != null) "$label · $count" else label,
                style = MaterialTheme.typography.labelMedium,
                color = if (emphasised) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("$badge", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

/**
 * The bridge between the two faces of the app, shown on the mesh side:
 *  - online and the person chose the mesh: "Online · Open Indradhanu";
 *  - offline: "Offline · Messages travel phone to phone" (information, no action).
 */
@Composable
fun ShellBanner(controls: ShellControls?, peerCount: Int, modifier: Modifier = Modifier) {
    if (controls == null || controls.mode != ShellMode.Mesh) return
    val palette = LocalBitchatPalette.current
    val online = controls.onlineAvailable
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.inputButton)
            .then(if (online) Modifier.pressable(onClick = controls.goOnline) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) palette.positive else MapPalette.Stale))
        Column(Modifier.weight(1f)) {
            Text(
                if (online) "You're online" else "Offline · using the mesh",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (online) "Open the Indradhanu app for live updates from the command centre."
                else when (peerCount) {
                    0 -> "No phones in range yet. Messages wait and send when someone is near."
                    1 -> "1 phone in range. Messages hop phone to phone."
                    else -> "$peerCount phones in range. Messages hop phone to phone."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (online) {
            Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Role filter chips, iOS capsule style: filled when on, quiet when off. */
@Composable
fun RoleChips(
    selected: Set<Role>,
    roles: List<Role>,
    onToggle: (Role) -> Unit,
    onAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip("All", selected.isEmpty(), MaterialTheme.colorScheme.onSurface, onAll)
        roles.forEach { r -> Chip(Role.displayLabel(r), r in selected, roleColor(r)) { onToggle(r) } }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun Chip(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    val palette = LocalBitchatPalette.current
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (on) scheme.onSurface else palette.inputButton)
            .pressable(onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) scheme.surface else scheme.onSurface, maxLines = 1)
    }
}
