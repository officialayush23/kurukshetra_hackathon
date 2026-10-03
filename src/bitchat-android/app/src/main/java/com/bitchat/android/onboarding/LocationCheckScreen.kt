package com.bitchat.android.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.ui.design.ButtonKind
import com.bitchat.android.ui.theme.AppleColors

/**
 * Screen shown when checking location services status or requesting location services enable
 */
@Composable
fun LocationCheckScreen(
    modifier: Modifier,
    status: LocationStatus,
    onEnableLocation: () -> Unit,
    onRetry: () -> Unit,
    isLoading: Boolean = false
) {
    when (status) {
        LocationStatus.DISABLED -> OnboardingStep(
            modifier = modifier,
            icon = Icons.Rounded.LocationOn,
            tint = AppleColors.GreenLight,
            title = stringResource(R.string.location_services_required),
            message = stringResource(R.string.location_explanation),
            detailHeader = stringResource(R.string.location_needs_for),
            detail = stringResource(R.string.location_needs_bullets),
            note = stringResource(R.string.privacy_first),
            primary = StepAction(stringResource(R.string.open_location_settings), onEnableLocation),
            secondary = listOf(StepAction(stringResource(R.string.check_again), onRetry, ButtonKind.Plain)),
            loading = isLoading,
        )
        LocationStatus.NOT_AVAILABLE -> OnboardingStep(
            modifier = modifier,
            icon = Icons.Rounded.LocationOff,
            tint = Color(0xFF8E8E93),
            title = stringResource(R.string.location_services_unavailable),
            message = stringResource(R.string.location_unavailable_explanation),
        )
        LocationStatus.ENABLED -> OnboardingWorking(
            modifier = modifier,
            icon = Icons.Rounded.LocationOn,
            tint = AppleColors.GreenLight,
            title = stringResource(R.string.app_name),
            message = stringResource(R.string.checking_location_services),
        )
    }
}
