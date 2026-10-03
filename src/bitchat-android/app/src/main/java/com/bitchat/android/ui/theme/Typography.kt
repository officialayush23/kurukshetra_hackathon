package com.bitchat.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// Kept for callers that size relative to it.
internal const val BASE_FONT_SIZE = com.bitchat.android.util.AppConstants.UI.BASE_FONT_SIZE_SP

/** Message body style: 16/22, the comfortable reading size of a messaging app. */
val MessageBodyTextStyle = ChatVisualTokens.MessageBodyStyle

/** Sender label above a message group. Single line, never wraps. */
val MessageSenderTextStyle = ChatVisualTokens.SenderStyle

private fun style(size: Int, line: Int, weight: FontWeight, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = BitchatFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking,
)

/**
 * The iOS text scale, mapped onto Material roles so stock components pick it up:
 *
 * | Material | iOS | size |
 * |---|---|---|
 * | displaySmall | Large Title | 32 bold |
 * | headlineLarge | Title 1 | 28 bold |
 * | headlineMedium | Title 2 | 22 bold |
 * | headlineSmall | Title 3 | 20 semibold |
 * | titleLarge | Headline | 17 semibold |
 * | titleMedium / bodyLarge | Body | 16 |
 * | bodyMedium | Subheadline | 15 |
 * | bodySmall | Footnote | 13 |
 * | labelSmall | Caption | 12 |
 *
 * Inter runs a little wider than Apple's face, so sizes sit one step under Apple's and large
 * sizes are tracked in slightly.
 */
val Typography = Typography(
    displayLarge = style(48, 54, FontWeight.Bold, (-0.8).sp),
    displayMedium = style(40, 46, FontWeight.Bold, (-0.6).sp),
    displaySmall = style(32, 38, FontWeight.Bold, (-0.5).sp),
    headlineLarge = style(28, 34, FontWeight.Bold, (-0.4).sp),
    headlineMedium = style(22, 28, FontWeight.Bold, (-0.3).sp),
    headlineSmall = style(20, 25, FontWeight.SemiBold, (-0.2).sp),
    titleLarge = style(17, 22, FontWeight.SemiBold, (-0.2).sp),
    titleMedium = style(16, 21, FontWeight.SemiBold, (-0.1).sp),
    titleSmall = style(15, 20, FontWeight.Medium),
    bodyLarge = style(16, 22, FontWeight.Normal, (-0.1).sp),
    bodyMedium = style(15, 20, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.Medium),
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)
