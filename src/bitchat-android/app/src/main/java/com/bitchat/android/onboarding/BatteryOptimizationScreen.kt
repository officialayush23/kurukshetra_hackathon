package com.bitchat.android.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.ui.design.ButtonKind
import com.bitchat.android.ui.theme.AppleColors

/**
 * Screen shown when checking battery optimization status or requesting battery optimization disable
 */
@Composable
fun BatteryOptimizationScreen(
    modifier: Modifier,
    status: BatteryOptimizationStatus,
    onDisableBatteryOptimization: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    isLoading: Boolean = false
) {
    val context = LocalContext.current

    // Initialize preference manager
    LaunchedEffect(Unit) {
        BatteryOptimizationPreferenceManager.init(context)
    }

    when (status) {
        BatteryOptimizationStatus.ENABLED -> OnboardingStep(
            modifier = modifier,
            icon = Icons.Rounded.BatteryChargingFull,
            tint = AppleColors.TealLight,
            title = stringResource(R.string.battery_optimization_detected_title),
            message = stringResource(R.string.battery_optimization_explanation_short),
            detailHeader = stringResource(R.string.benefits_of_disabling),
            detail = stringResource(R.string.battery_benefits_short),
            primary = StepAction(stringResource(R.string.disable_battery_optimization), onDisableBatteryOptimization),
            secondary = listOf(
                StepAction(stringResource(R.string.check_again), onRetry, ButtonKind.Plain),
                StepAction(stringResource(R.string.battery_optimization_skip), {
                    BatteryOptimizationPreferenceManager.setSkipped(context, true)
                    onSkip()
                }, ButtonKind.Plain),
            ),
            loading = isLoading,
        )
        BatteryOptimizationStatus.DISABLED -> OnboardingWorking(
            modifier = modifier,
            icon = Icons.Rounded.CheckCircle,
            tint = AppleColors.GreenLight,
            title = stringResource(R.string.battery_optimization_disabled_title),
            message = stringResource(R.string.battery_optimization_success_message),
        )
        BatteryOptimizationStatus.NOT_SUPPORTED -> OnboardingStep(
            modifier = modifier,
            icon = Icons.Rounded.CheckCircle,
            tint = AppleColors.GreenLight,
            title = stringResource(R.string.battery_optimization_not_required),
            message = stringResource(R.string.battery_optimization_not_supported_message),
            primary = StepAction(stringResource(R.string.continue_btn), onRetry),
        )
    }
}
