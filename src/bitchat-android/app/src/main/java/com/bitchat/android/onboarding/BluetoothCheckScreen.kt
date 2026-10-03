package com.bitchat.android.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.ui.design.ButtonKind
import com.bitchat.android.ui.theme.AppleColors

/**
 * Screen shown when checking Bluetooth status or requesting Bluetooth enable
 */
@Composable
fun BluetoothCheckScreen(
    modifier: Modifier,
    status: BluetoothStatus,
    onEnableBluetooth: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    isLoading: Boolean = false
) {
    when (status) {
        BluetoothStatus.DISABLED -> OnboardingStep(
            modifier = modifier,
            icon = Icons.Rounded.Bluetooth,
            tint = AppleColors.BlueLight,
            title = stringResource(R.string.bluetooth_recommended),
            message = null,
            detailHeader = stringResource(R.string.bluetooth_needs_for),
            detail = stringResource(R.string.bluetooth_needs_bullets),
            primary = StepAction(stringResource(R.string.enable_bluetooth), onEnableBluetooth),
            secondary = listOf(StepAction(stringResource(R.string.skip), onSkip, ButtonKind.Plain)),
            loading = isLoading,
        )
        BluetoothStatus.NOT_SUPPORTED -> OnboardingStep(
            modifier = modifier,
            icon = Icons.Rounded.BluetoothDisabled,
            tint = Color(0xFF8E8E93),
            title = stringResource(R.string.bluetooth_not_supported),
            message = stringResource(R.string.bluetooth_unsupported_explanation),
            primary = StepAction(stringResource(R.string.continue_btn), onSkip),
        )
        BluetoothStatus.ENABLED -> OnboardingWorking(
            modifier = modifier,
            icon = Icons.Rounded.Bluetooth,
            tint = AppleColors.BlueLight,
            title = stringResource(R.string.app_name),
            message = stringResource(R.string.checking_bluetooth_status),
        )
    }
}
