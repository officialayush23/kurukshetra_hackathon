package com.bitchat.android.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.R

/**
 * Permission explanation screen shown before requesting permissions
 * Explains why bitchat needs each permission and reassures users about privacy
 */
@Composable
fun PermissionExplanationScreen(
    modifier: Modifier,
    permissionCategories: List<PermissionCategory>,
    onContinue: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = com.bitchat.android.ui.theme.LocalBitchatPalette.current
    val scrollState = rememberScrollState()

    Box(modifier = modifier.background(palette.groupedBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 96.dp) // room for the fixed button
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            com.bitchat.android.ui.design.LargeTitle(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.about_tagline),
            )
            Spacer(modifier = Modifier.height(8.dp))

            com.bitchat.android.ui.design.InsetGroup(header = stringResource(R.string.privacy_protected)) {
                com.bitchat.android.ui.design.GroupRow(
                    title = stringResource(R.string.privacy_bullets),
                    icon = Icons.Filled.Security,
                    iconTint = com.bitchat.android.ui.theme.AppleColors.GreenLight,
                )
            }

            com.bitchat.android.ui.design.InsetGroup(header = stringResource(R.string.permissions_header)) {
                permissionCategories.forEachIndexed { i, category ->
                    if (i > 0) com.bitchat.android.ui.design.GroupDivider(inset = 58.dp)
                    com.bitchat.android.ui.design.GroupRow(
                        title = category.type.nameValue,
                        subtitle = category.description,
                        icon = getPermissionIcon(category.type),
                        iconTint = permissionTint(category.type),
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Fixed button on a translucent bar, as on iOS.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(palette.barMaterial)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            com.bitchat.android.ui.design.AppleButton(
                text = stringResource(R.string.grant_permissions),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One calm colour per permission, as in iOS Settings. Red is never used here. */
private fun permissionTint(type: PermissionType): androidx.compose.ui.graphics.Color = when (type) {
    PermissionType.NEARBY_DEVICES, PermissionType.WIFI_AWARE -> com.bitchat.android.ui.theme.AppleColors.BlueLight
    PermissionType.PRECISE_LOCATION, PermissionType.BACKGROUND_LOCATION -> com.bitchat.android.ui.theme.AppleColors.GreenLight
    PermissionType.MICROPHONE -> com.bitchat.android.ui.theme.AppleColors.OrangeLight
    PermissionType.NOTIFICATIONS -> com.bitchat.android.ui.theme.AppleColors.IndigoLight
    PermissionType.BATTERY_OPTIMIZATION -> com.bitchat.android.ui.theme.AppleColors.TealLight
    PermissionType.OTHER -> androidx.compose.ui.graphics.Color(0xFF8E8E93)
}

private fun getPermissionIcon(permissionType: PermissionType): ImageVector {
    return when (permissionType) {
        PermissionType.NEARBY_DEVICES -> Icons.Filled.Bluetooth
        PermissionType.PRECISE_LOCATION -> Icons.Filled.LocationOn
        PermissionType.BACKGROUND_LOCATION -> Icons.Filled.LocationOn
        PermissionType.MICROPHONE -> Icons.Filled.Mic
        PermissionType.NOTIFICATIONS -> Icons.Filled.Notifications
        PermissionType.WIFI_AWARE -> Icons.Filled.Wifi
        PermissionType.BATTERY_OPTIMIZATION -> Icons.Filled.Power
        PermissionType.OTHER -> Icons.Filled.Settings
    }
}
