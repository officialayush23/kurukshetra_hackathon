package com.bitchat.android.ui.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.bitchat.android.ui.theme.DarkBitchatColorScheme
import com.bitchat.android.ui.theme.DarkBitchatPalette
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.Typography

/**
 * The map is always drawn in the dark visual language, whatever the app theme: dark
 * cartography with light chrome on top of it would glare, and a light map would lose the
 * contrast the critical markers depend on.
 *
 * While it is on screen the system bars are switched to light icons so the clock and battery
 * stay readable over the map, and put back exactly as they were when it closes.
 */
@Composable
fun MapThemeScope(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION")
        val previousNavColor = window?.navigationBarColor
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        window?.navigationBarColor = MAP_LOADING_BG
        onDispose {
            if (controller != null) {
                previousStatus?.let { controller.isAppearanceLightStatusBars = it }
                previousNav?.let { controller.isAppearanceLightNavigationBars = it }
            }
            @Suppress("DEPRECATION")
            if (window != null && previousNavColor != null) window.navigationBarColor = previousNavColor
        }
    }
    CompositionLocalProvider(LocalBitchatPalette provides DarkBitchatPalette) {
        MaterialTheme(colorScheme = DarkBitchatColorScheme, typography = Typography, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
