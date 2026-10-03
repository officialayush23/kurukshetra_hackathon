package com.bitchat.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Bitchat-specific color tokens that do not have a faithful Material 3 semantic role.
 *
 * Standard backgrounds, surfaces, text, outlines, primary/secondary accents, and errors belong
 * to [androidx.compose.material3.MaterialTheme.colorScheme]. Keeping only the extra app semantics
 * here lets Material components inherit correct defaults without losing Bitchat's identity.
 */
@Immutable
data class BitchatPalette(
    // MARK: - Form controls
    /**
     * Resting border for text inputs. Deliberately a neutral grey rather than the green-tinted
     * Material outline: the composer is the one surface the user stares at while typing.
     */
    val inputOutline: Color,
    /** Border for a focused text input. A step brighter, still neutral. */
    val inputOutlineFocused: Color,
    /**
     * Fill for text inputs. Near-black / near-white and completely untinted, for the same reason
     * as [inputOutline] — and because the composer sits on top of a green-tinted scrim, so any
     * tint of its own compounds into something muddy.
     */
    val inputSurface: Color,
    /** Fill for a focused text input. A barely perceptible lift. */
    val inputSurfaceFocused: Color,
    /** Resting disc behind the composer's action glyphs. Neutral grey. */
    val inputButton: Color,

    // MARK: - Extra semantics
    /** Timestamps, placeholders, section labels, disabled states. */
    val textTertiary: Color,
    /** Self, mentions targeting you, unread DMs. */
    val accentOrange: Color,
    /** Nostr reachability. */
    val accentPurple: Color,

    // MARK: - Deterministic peer colors
    /**
     * Saturation/value applied after deriving a peer's stable hue. Swap this when adding a
     * new theme — see [PeerColorStyle] for contrast guidelines.
     */
    val peerColors: PeerColorStyle,

    // MARK: - Grouped lists (iOS "inset grouped")
    /** Page behind grouped sections: the grey around white cards in light mode. */
    val groupedBackground: Color,
    /** The card a group of rows sits on. */
    val groupedCell: Color,
    /** Hairline between rows inside a group. */
    val separator: Color,
    /** Outgoing message bubble and its text. */
    val bubbleOutgoing: Color,
    val onBubbleOutgoing: Color,
    /** Incoming message bubble. Text uses onSurface. */
    val bubbleIncoming: Color,
    /** Translucent bar material for headers and the composer. */
    val barMaterial: Color,
    /** Success / connected. */
    val positive: Color,
)

val DarkBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFF38383A),
    inputOutlineFocused = Color(0xFF545458),
    inputSurface = Color(0xFF1C1C1E),
    inputSurfaceFocused = Color(0xFF2C2C2E),
    inputButton = Color(0xFF2C2C2E),
    textTertiary = Color(0xFF8E8E93),
    accentOrange = Color(0xFFFF9F0A),
    accentPurple = Color(0xFFBF5AF2),
    peerColors = PeerColorStyle.Dark,
    groupedBackground = Color(0xFF000000),
    groupedCell = Color(0xFF1C1C1E),
    separator = Color(0xFF38383A),
    bubbleOutgoing = Color(0xFF0A84FF),
    onBubbleOutgoing = Color(0xFFFFFFFF),
    bubbleIncoming = Color(0xFF26252A),
    barMaterial = Color(0xE6161618),
    positive = Color(0xFF30D158),
)

val LightBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFFD1D1D6),
    inputOutlineFocused = Color(0xFFAEAEB2),
    inputSurface = Color(0xFFFFFFFF),
    inputSurfaceFocused = Color(0xFFFFFFFF),
    inputButton = Color(0xFFE5E5EA),
    textTertiary = Color(0xFF6E6E73),
    accentOrange = Color(0xFFC93400),
    accentPurple = Color(0xFF8944AB),
    peerColors = PeerColorStyle.Light,
    groupedBackground = Color(0xFFF2F2F7),
    groupedCell = Color(0xFFFFFFFF),
    separator = Color(0xFFD8D8DC),
    bubbleOutgoing = Color(0xFF007AFF),
    onBubbleOutgoing = Color(0xFFFFFFFF),
    bubbleIncoming = Color(0xFFE9E9EB),
    barMaterial = Color(0xEBF9F9F9),
    positive = Color(0xFF248A3D),
)

val LocalBitchatPalette = staticCompositionLocalOf { DarkBitchatPalette }

/**
 * Motion tokens. The redesign leans on short, snappy transitions: long durations read as
 * sluggish on a chat surface where the user is scanning quickly.
 */
object BitchatMotion {
    /** iOS-like spring: settles quickly with no visible bounce. */
    const val SPRING_DAMPING = 0.86f
    const val SPRING_STIFFNESS = 380f

    /** Icon tints, text colors, small fills. */
    const val QUICK_MS = 120

    /** Tab indicators, pill growth, chip reveals. */
    const val STANDARD_MS = 180

    /** Sheet-level fades and scroll-driven top bars. */
    const val EMPHASIZED_MS = 240
}
