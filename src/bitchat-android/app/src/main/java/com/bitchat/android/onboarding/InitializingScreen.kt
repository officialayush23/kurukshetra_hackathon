package com.bitchat.android.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.ui.design.ButtonKind
import com.bitchat.android.ui.theme.AppleColors

/**
 * Loading screen shown while the mesh starts up.
 */
@Composable
fun InitializingScreen(modifier: Modifier) {
    OnboardingStep(
        modifier = modifier,
        icon = Icons.Rounded.CellTower,
        tint = AppleColors.BlueLight,
        title = stringResource(R.string.initializing_mesh_network),
        message = stringResource(R.string.setting_up_bluetooth),
        note = stringResource(R.string.should_take_seconds),
        loading = true,
    )
}

/**
 * Error screen shown if initialization fails
 */
@Composable
fun InitializationErrorScreen(
    modifier: Modifier,
    errorMessage: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    OnboardingStep(
        modifier = modifier,
        icon = Icons.Rounded.ErrorOutline,
        tint = Color(0xFF8E8E93),
        title = stringResource(R.string.setup_not_complete),
        message = errorMessage,
        primary = StepAction(stringResource(R.string.try_again), onRetry),
        secondary = listOf(StepAction(stringResource(R.string.open_settings), onOpenSettings, ButtonKind.Plain)),
    )
}
