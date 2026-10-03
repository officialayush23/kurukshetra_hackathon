package com.bitchat.android.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.ui.design.AppleButton
import com.bitchat.android.ui.design.ButtonKind
import com.bitchat.android.ui.design.IconTile
import com.bitchat.android.ui.design.InsetGroup
import com.bitchat.android.ui.theme.LocalBitchatPalette

/** One button on an onboarding step. */
data class StepAction(
    val label: String,
    val onClick: () -> Unit,
    val kind: ButtonKind = ButtonKind.Filled,
    val enabled: Boolean = true,
)

/**
 * The one layout every onboarding step uses, in the iOS setup-assistant manner: a coloured
 * icon tile, a title, a short explanation, an optional card of detail, and the actions pinned
 * to the bottom. Steps differ only in their words, so they all feel like one flow.
 */
@Composable
fun OnboardingStep(
    modifier: Modifier,
    icon: ImageVector,
    tint: Color,
    title: String,
    message: String?,
    detailHeader: String? = null,
    detail: String? = null,
    note: String? = null,
    primary: StepAction? = null,
    secondary: List<StepAction> = emptyList(),
    loading: Boolean = false,
) {
    val palette = LocalBitchatPalette.current
    Box(modifier.fillMaxSize().background(palette.groupedBackground), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 56.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconTile(icon, tint, size = 72.dp)
                Spacer(Modifier.height(20.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                if (message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
                if (detail != null) {
                    Spacer(Modifier.height(24.dp))
                    InsetGroup(header = detailHeader, footer = note) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else if (note != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                if (loading) {
                    Spacer(Modifier.height(28.dp))
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            if (primary != null || secondary.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    primary?.let {
                        AppleButton(it.label, it.onClick, Modifier.fillMaxWidth(), kind = it.kind, enabled = it.enabled && !loading)
                    }
                    if (secondary.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            secondary.forEach { a ->
                                AppleButton(
                                    a.label, a.onClick, Modifier.weight(1f),
                                    kind = if (a.kind == ButtonKind.Filled) ButtonKind.Plain else a.kind,
                                    enabled = a.enabled && !loading,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A quiet "working on it" step: icon, title, spinner, one line. */
@Composable
fun OnboardingWorking(modifier: Modifier, icon: ImageVector, tint: Color, title: String, message: String) {
    OnboardingStep(modifier = modifier, icon = icon, tint = tint, title = title, message = message, loading = true)
}
