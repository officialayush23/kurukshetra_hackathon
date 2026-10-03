package com.bitchat.android.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.ui.design.ButtonKind
import com.bitchat.android.ui.theme.AppleColors

/**
 * Asks for "Allow all the time" location so geohash channels and the map keep working while
 * the app is in the background. Same layout and button order as the battery step.
 */
@Composable
fun BackgroundLocationPermissionScreen(
    modifier: Modifier,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    OnboardingStep(
        modifier = modifier,
        icon = Icons.Rounded.MyLocation,
        tint = AppleColors.GreenLight,
        title = stringResource(R.string.background_location_required_title),
        message = stringResource(R.string.background_location_explanation) + "\n\n" +
            stringResource(R.string.background_location_settings_tip),
        detailHeader = stringResource(R.string.background_location_needs_for),
        detail = stringResource(R.string.background_location_needs_bullets),
        note = stringResource(R.string.background_location_privacy_note),
        primary = StepAction(stringResource(R.string.grant_background_location), onContinue),
        secondary = listOf(
            StepAction(stringResource(R.string.check_again), onRetry, ButtonKind.Plain),
            StepAction(stringResource(R.string.battery_optimization_skip), onSkip, ButtonKind.Plain),
        ),
    )
}
