package com.bitchat.android.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import com.bitchat.android.model.Role
import com.bitchat.android.model.ShelterStatus

/** Stable emoji per role, used by the COP map markers and the shelter rows. */
fun roleEmoji(role: Role): String = when (role) {
    Role.AMBULANCE -> "🚑"
    Role.FIRE -> "🚒"
    Role.GOV -> "🏛"
    Role.COMMAND -> "🛰"
    Role.CIVILIAN -> "🆘"
    Role.UNSET -> "📍"
}

/** Distinct background colour per role so blank emoji markers still read at a glance. */
fun roleAccentArgb(role: Role): Int = when (role) {
    Role.AMBULANCE -> 0xFFE53935.toInt()
    Role.FIRE -> 0xFFFB8C00.toInt()
    Role.GOV -> 0xFF1E88E5.toInt()
    Role.COMMAND -> 0xFF8E24AA.toInt()
    Role.CIVILIAN -> 0xFFD81B60.toInt()
    Role.UNSET -> 0xFF607D8B.toInt()
}

/** Delivery-state tint ring used to badge whether an incident reached a responder. */
enum class ReachTint { REACHED, RELAYED, FAILED, UNKNOWN }
fun reachTintArgb(tint: ReachTint): Int = when (tint) {
    ReachTint.REACHED -> 0xFF43C46A.toInt()
    ReachTint.RELAYED -> 0xFFFFC107.toInt()
    ReachTint.FAILED -> 0xFFB71C1C.toInt()
    ReachTint.UNKNOWN -> 0xFF757575.toInt()
}

/** Render a role emoji inside a coloured circle with an optional delivery ring. */
fun buildEmojiMarker(
    context: Context,
    emoji: String,
    backgroundArgb: Int,
    ringTint: Int? = null
): BitmapDrawable {
    val sizePx = 96
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val radius = sizePx / 2f - 6f

    if (ringTint != null) {
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringTint
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawCircle(cx, cy, radius + 4f, ringPaint)
    }

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundArgb
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, radius, bgPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 52f
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    val bounds = Rect()
    textPaint.getTextBounds(emoji, 0, emoji.length, bounds)
    val textY = cy - (bounds.exactCenterY())
    canvas.drawText(emoji, cx, textY, textPaint)

    return BitmapDrawable(context.resources, bmp)
}

/** Status dot colour shared between the shelter list and the map info window. */
fun shelterStatusColorArgb(status: ShelterStatus): Int = when (status) {
    ShelterStatus.OPEN -> 0xFF43C46A.toInt()
    ShelterStatus.FULL -> 0xFFFFC107.toInt()
    ShelterStatus.CLOSED -> 0xFFB71C1C.toInt()
}

/** Format a great-circle distance in metres into a compact demo-friendly label. */
fun formatDistance(meters: Float): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    meters < 10000 -> "${"%.1f".format(meters / 1000)} km"
    else -> "${(meters / 1000).toInt()} km"
}