package com.bitchat.android.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * The small set of building blocks every BiChat screen is made from, modelled on iOS:
 * inset grouped lists, filled buttons, quiet text fields, a segmented control. One place
 * decides corner radii, spacing and press behaviour, so screens stay consistent.
 *
 * Spacing is on an 8-pt grid; every tappable thing is at least 44 dp tall.
 */
object Apple {
    val ScreenPadding = 16.dp
    val GroupRadius = 12.dp
    val ControlRadius = 12.dp
    val RowMinHeight = 48.dp
    val TouchTarget = 44.dp

    fun <T> spring() = spring<T>(
        dampingRatio = BitchatMotion.SPRING_DAMPING,
        stiffness = BitchatMotion.SPRING_STIFFNESS,
    )
}

/** Scale-on-press without a ripple: the physical feel of an iOS control. */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) pressedScale else 1f,
        Apple.spring(),
        label = "press",
    )
    return this
        .scale(scale)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
}

// ------------------------------------------------------------------ grouped lists ---

/**
 * An inset grouped section: an optional header line, a rounded card of rows separated by
 * hairlines, and an optional footer explaining the rows.
 */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalBitchatPalette.current
    Column(modifier.fillMaxWidth().padding(horizontal = Apple.ScreenPadding)) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp, top = 8.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Apple.GroupRadius))
                .background(palette.groupedCell),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/** A hairline between two rows, inset to line up with the row text. */
@Composable
fun GroupDivider(inset: Dp = 16.dp) {
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(LocalBitchatPalette.current.separator)
    )
}

/** A rounded-square tile with a white glyph, as in iOS Settings. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, size: Dp = 30.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.23f)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

/**
 * One row of a grouped list. Title on the left, optional value or trailing content on the
 * right, a chevron when it navigates. Destructive rows are red and never navigate.
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    value: String? = null,
    destructive: Boolean = false,
    accent: Boolean = false,
    chevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        destructive -> MaterialTheme.colorScheme.error
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent,
        tween(BitchatMotion.QUICK_MS),
        label = "rowPress",
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Apple.RowMinHeight)
            .background(bg)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = source, indication = null, enabled = enabled,
                    role = Role.Button, onClick = onClick,
                ) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) IconTile(icon, if (enabled) iconTint else iconTint.copy(alpha = 0.4f))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge, color = color,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Text(
                value, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        trailing?.invoke(this)
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                tint = LocalBitchatPalette.current.textTertiary, modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------ buttons ---

enum class ButtonKind { Filled, Tinted, Plain, Destructive, Emergency }

/**
 * The one button. Filled for the primary action of a screen, tinted for secondary, plain
 * for tertiary. Emergency is reserved for SOS. Shows a spinner and ignores taps while busy.
 */
@Composable
fun AppleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 50.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        ButtonKind.Filled -> scheme.primary to scheme.onPrimary
        ButtonKind.Tinted -> scheme.primary.copy(alpha = 0.14f) to scheme.primary
        ButtonKind.Plain -> Color.Transparent to scheme.primary
        ButtonKind.Destructive -> scheme.error.copy(alpha = 0.14f) to scheme.error
        ButtonKind.Emergency -> scheme.error to Color.White
    }
    val active = enabled && !loading
    Box(
        modifier
            .heightIn(min = maxOf(height, Apple.TouchTarget))
            .clip(RoundedCornerShape(Apple.ControlRadius))
            .background(if (active || kind == ButtonKind.Plain) bg else bg.copy(alpha = bg.alpha * 0.5f))
            .pressable(enabled = active, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, null, tint = if (active) fg else fg.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Text(
                    text, style = MaterialTheme.typography.titleMedium,
                    color = if (active) fg else fg.copy(alpha = 0.5f), maxLines = 1,
                )
            }
        }
    }
}

/** A round glass control used over maps and media: 44 dp, icon only, labelled for TalkBack. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    background: Color = LocalBitchatPalette.current.inputButton,
    size: Dp = Apple.TouchTarget,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .pressable(pressedScale = 0.9f, onClick = onClick)
            .semantics { this.role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

// --------------------------------------------------------------------- text fields ---

/**
 * A filled, borderless field with its label above it. The border appears only on focus or
 * error, so a form reads as calm until it needs attention.
 */
@Composable
fun AppleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secure: Boolean = false,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
) {
    val palette = LocalBitchatPalette.current
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        when {
            error != null -> scheme.error
            focused -> scheme.primary
            else -> Color.Transparent
        },
        tween(BitchatMotion.STANDARD_MS), label = "fieldBorder",
    )
    Column(modifier.fillMaxWidth()) {
        Text(
            label, style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(Apple.ControlRadius))
                .background(palette.groupedCell)
                .border(1.5.dp, borderColor, RoundedCornerShape(Apple.ControlRadius))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = palette.textTertiary)
                    }
                    inner()
                }
            },
        )
        if (error != null) {
            Text(
                error, style = MaterialTheme.typography.bodySmall, color = scheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

// --------------------------------------------------------------- segmented control ---

/** iOS segmented control: a sliding thumb behind equal-width labels. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBitchatPalette.current
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(palette.inputButton)
            .padding(2.dp)
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(segment * selectedIndex, Apple.spring(), label = "segment")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(RoundedCornerShape(7.dp))
                .background(if (scheme.background.luminance() < 0.5f) Color(0xFF636366) else Color.White)
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { selected = i == selectedIndex; role = Role.Tab }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (i == selectedIndex) FontWeight.SemiBold else FontWeight.Medium,
                        color = scheme.onSurface, maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- status pill ---

/** A small rounded status label with a coloured dot: "Online", "Mesh · 3 nearby". */
@Composable
fun StatusPill(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(LocalBitchatPalette.current.inputButton)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .heightIn(min = 30.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** Large title header for full screens, as on iOS. */
@Composable
fun LargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actions != null) Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Standard content padding for a scrolling grouped screen. */
val GroupedContentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)

// ------------------------------------------------------------------- settings rows ---

/** The iOS switch look: green when on, white thumb, no outline. */
@Composable
fun AppleSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val palette = LocalBitchatPalette.current
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = palette.positive,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = palette.inputButton,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** A grouped-list row with a switch. The whole row toggles, not only the switch. */
@Composable
fun GroupToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    GroupRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = { AppleSwitch(checked, null, enabled) },
    )
}

/**
 * A text entry inside a grouped list: label on the left, the value on the right, as in iOS
 * Settings forms. Secure fields hide their value.
 */
@Composable
fun GroupFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    secure: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val palette = LocalBitchatPalette.current
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Apple.RowMinHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.widthIn(min = 88.dp, max = 140.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End),
            cursorBrush = SolidColor(scheme.primary),
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = palette.textTertiary, maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}
