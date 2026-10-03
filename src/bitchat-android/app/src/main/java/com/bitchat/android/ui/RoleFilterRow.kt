package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bitchat.android.model.Role

/**
 * Emergency-response role filter row. Renders one chip per consumer role plus an
 * "All" chip. Chips are mutually inclusive: when nothing is selected the mesh timeline shows
 * every broadcast (legacy [Role.UNSET] included); once any role chip is selected only messages
 * tagged with that role (plus untagged legacy traffic) are surfaced by [ChatViewModel]'s
 * public-messages collector.
 *
 * The row is horizontally scrollable so even the widest role label ("Ambulance") never
 * wraps or clips inside the chip on narrow screens.
 *
 * All four consumer roles are rendered unconditionally so a responder arriving on scene can
 * pre-subscribe to GOV / AMBULANCE alerts before any such peer has been observed; the row
 * shows the full catalog of responder traffic this node is willing to receive.
 */
@Composable
fun RoleFilterRow(
    selectedRoles: Set<Role>,
    availableRoles: Set<Role>,
    onToggle: (Role) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
    ) {
        // "All": highlighted when the subscription set is empty
        FilterChip(
            selected = selectedRoles.isEmpty(),
            onClick = onClearAll,
            label = { Text(Role.displayLabel(Role.UNSET)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = colorScheme.primary,
                selectedLabelColor = colorScheme.onPrimary
            ),
            shape = CircleShape
        )
        // One chip per non-UNSET role, unconditionally so responders can pre-subscribe.
        // FilterChip auto-sizes to its label (single-line), so the chip never wraps the text.
        availableRoles.forEach { role ->
            FilterChip(
                selected = role in selectedRoles,
                onClick = { onToggle(role) },
                label = { Text(Role.displayLabel(role), maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = roleAccent(role, colorScheme.primary),
                    selectedLabelColor = Color.White
                ),
                shape = CircleShape
            )
        }
    }
}

/** Stable demo-time color per responder role so chips stand out at a glance. */
private fun roleAccent(role: Role, base: Color): Color = when (role) {
    Role.AMBULANCE -> Color(0xFFE53935) // red
    Role.FIRE -> Color(0xFFFB8C00)      // orange
    Role.GOV -> Color(0xFF1E88E5)      // blue
    Role.COMMAND -> Color(0xFF8E24AA)  // purple
    Role.CIVILIAN -> Color(0xFF43A047)  // green
    Role.UNSET -> base
}