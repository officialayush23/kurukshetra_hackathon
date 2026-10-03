package com.bitchat.android.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.bitchat.android.R
import com.bitchat.android.model.ShelterStatus
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** The viewer's own fix, as the overlay needs it. */
data class MapFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val timeMs: Long,
    /** Restored from the last session rather than reported by the GPS just now. */
    val restored: Boolean,
)

/**
 * Everything drawn on top of the tiles, in one overlay.
 *
 * One overlay rather than an osmdroid [org.osmdroid.views.overlay.Marker] per entity, because the
 * map needs things markers do not do: clustering that stays put while panning, a selected state
 * with a contextual label, a pulse reserved for life-safety items, and a direct-line guidance
 * path drawn beneath the markers it connects.
 *
 * Clustering is done on a grid in world pixels at the current zoom, so a cluster does not
 * reshuffle as the map is dragged — only when the zoom changes. SOS items and the selected item
 * are never folded into a cluster: the first must never be hidden, the second is what the person
 * is looking at.
 */
class EmergencyMapOverlay(
    context: Context,
    private val onEntityTap: (String?) -> Unit,
    private val onClusterTap: (List<MapEntity>) -> Unit,
    private val onUserGesture: () -> Unit,
) : Overlay() {

    var entities: List<MapEntity> = emptyList()
    var selectedId: String? = null
    var guidanceTargetId: String? = null
    var fix: MapFix? = null
    var showRings: Boolean = false

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val animationsEnabled: Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)

    private val typeface: Typeface = runCatching {
        ResourcesCompat.getFont(context, R.font.geist_mono_medium)
    }.getOrNull() ?: Typeface.DEFAULT_BOLD

    private val markerRadius = dp(14f)
    private val sosRadius = dp(16f)
    private val clusterRadius = dp(17f)
    private val glyphPx = (dp(17f)).toInt().coerceAtLeast(12)
    private val clusterCellPx = dp(64f)

    private val glyphs: Map<MapEntityKind, Bitmap> = MapEntityKind.entries.associateWith { kind ->
        rasterGlyph(glyphFor(kind), glyphPx, android.graphics.Color.WHITE)
    }
    private val darkGlyphs: Map<MapEntityKind, Bitmap> = MapEntityKind.entries.associateWith { kind ->
        rasterGlyph(glyphFor(kind), glyphPx, 0xFF0B0F17.toInt())
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@EmergencyMapOverlay.typeface
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.WHITE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dash = DashPathEffect(floatArrayOf(dp(10f), dp(7f)), 0f)

    private val reusePoint = Point()
    private val rect = RectF()

    private sealed class Hit(val x: Float, val y: Float, val r: Float) {
        class One(x: Float, y: Float, r: Float, val entity: MapEntity) : Hit(x, y, r)
        class Many(x: Float, y: Float, r: Float, val members: List<MapEntity>) : Hit(x, y, r)
    }

    private var hits: List<Hit> = emptyList()

    private class Projected(val entity: MapEntity, val x: Float, val y: Float)

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        val zoom = projection.zoomLevel
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val margin = dp(48f)
        val now = SystemClock.uptimeMillis()

        val me = fix
        var meX = 0f
        var meY = 0f
        if (me != null) {
            projection.toPixels(GeoPoint(me.lat, me.lon), reusePoint)
            meX = reusePoint.x.toFloat()
            meY = reusePoint.y.toFloat()
        }

        // ---- distance rings: context, drawn faintest and first.
        if (showRings && me != null) {
            stroke.strokeWidth = dp(1f)
            stroke.pathEffect = null
            text.textSize = dp(10f)
            listOf(500f, 1000f, 2000f).forEach { meters ->
                val r = projection.metersToPixels(meters, me.lat, zoom)
                if (r < dp(18f) || r > max(w, h) * 1.6f) return@forEach
                stroke.color = 0x33FFFFFF
                canvas.drawCircle(meX, meY, r, stroke)
                text.color = 0x99FFFFFF.toInt()
                canvas.drawText(formatMeters(meters), meX, meY - r - dp(4f), text)
            }
        }

        // ---- accuracy halo under everything else of "me".
        if (me != null) {
            val acc = me.accuracyM
            if (acc != null && acc > 0f) {
                val r = projection.metersToPixels(acc, me.lat, zoom)
                if (r > dp(12f)) {
                    fill.color = withAlpha(MapPalette.You.toArgb(), if (me.restored) 0x14 else 0x26)
                    canvas.drawCircle(meX, meY, min(r, max(w, h)), fill)
                    stroke.color = withAlpha(MapPalette.You.toArgb(), 0x55)
                    stroke.strokeWidth = dp(1f)
                    stroke.pathEffect = null
                    canvas.drawCircle(meX, meY, min(r, max(w, h)), stroke)
                }
            }
        }

        // ---- project, cull.
        val visible = ArrayList<Projected>(entities.size)
        entities.forEach { e ->
            projection.toPixels(GeoPoint(e.lat, e.lon), reusePoint)
            val x = reusePoint.x.toFloat()
            val y = reusePoint.y.toFloat()
            val pinned = e.id == selectedId || e.id == guidanceTargetId
            if (!pinned && (x < -margin || y < -margin || x > w + margin || y > h + margin)) return@forEach
            visible += Projected(e, x, y)
        }

        // ---- guidance: a direct line, dashed, because it is not a road.
        val target = guidanceTargetId?.let { id -> visible.firstOrNull { it.entity.id == id } }
        if (me != null && target != null) {
            linePaint.pathEffect = null
            linePaint.color = 0xCC05070B.toInt()
            linePaint.strokeWidth = dp(7f)
            canvas.drawLine(meX, meY, target.x, target.y, linePaint)
            linePaint.pathEffect = dash
            linePaint.color = MapPalette.Uncertain.toArgb()
            linePaint.strokeWidth = dp(3.5f)
            canvas.drawLine(meX, meY, target.x, target.y, linePaint)
            linePaint.pathEffect = null
        }

        // ---- cluster everything that may be clustered.
        val pinned = ArrayList<Projected>()
        val loose = ArrayList<Projected>()
        visible.forEach { p ->
            if (p.entity.isCritical || p.entity.id == selectedId || p.entity.id == guidanceTargetId) {
                pinned += p
            } else {
                loose += p
            }
        }
        val singles = ArrayList<Projected>()
        val clusters = ArrayList<List<Projected>>()
        if (zoom >= 17.0 || loose.size < 2) {
            singles += loose
        } else {
            val worldPx = 256.0 * 2.0.pow(zoom)
            val cells = LinkedHashMap<Long, MutableList<Projected>>()
            loose.forEach { p ->
                val nx = (p.entity.lon + 180.0) / 360.0
                val latRad = Math.toRadians(p.entity.lat.coerceIn(-85.0, 85.0))
                val ny = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0
                val cx = floor(nx * worldPx / clusterCellPx).toLong()
                val cy = floor(ny * worldPx / clusterCellPx).toLong()
                cells.getOrPut((cx shl 32) xor (cy and 0xffffffffL)) { ArrayList() } += p
            }
            cells.values.forEach { group ->
                if (group.size == 1) singles += group[0] else clusters += group
            }
        }

        val newHits = ArrayList<Hit>(visible.size)

        clusters.forEach { group ->
            val x = group.sumOf { it.x.toDouble() }.toFloat() / group.size
            val y = group.sumOf { it.y.toDouble() }.toFloat() / group.size
            drawCluster(canvas, x, y, group.map { it.entity })
            newHits += Hit.Many(x, y, clusterRadius, group.map { it.entity })
        }

        // Responders above reports, so the help is never hidden beneath the problem.
        singles.sortedBy { if (it.entity.kind.isResponder) 1 else 0 }.forEach { p ->
            drawMarker(canvas, p.x, p.y, p.entity, selected = false)
            newHits += Hit.One(p.x, p.y, markerRadius, p.entity)
        }

        var anyPulse = false
        pinned.sortedBy {
            when {
                it.entity.id == selectedId -> 3
                it.entity.id == guidanceTargetId -> 2
                else -> 1
            }
        }.forEach { p ->
            if (p.entity.isCritical && animationsEnabled) {
                drawPulse(canvas, p.x, p.y, now)
                anyPulse = true
            }
            val selected = p.entity.id == selectedId
            drawMarker(canvas, p.x, p.y, p.entity, selected = selected)
            newHits += Hit.One(p.x, p.y, if (p.entity.isCritical) sosRadius else markerRadius, p.entity)
        }

        // ---- you, above the markers, so you can always find yourself.
        if (me != null) {
            val dot = dp(7.5f)
            fill.color = 0x66000000
            canvas.drawCircle(meX, meY + dp(1f), dot + dp(3f), fill)
            fill.color = android.graphics.Color.WHITE
            canvas.drawCircle(meX, meY, dot + dp(2.5f), fill)
            fill.color = if (me.restored) 0xFF5B6B85.toInt() else MapPalette.You.toArgb()
            canvas.drawCircle(meX, meY, dot, fill)
        }

        // ---- the one label on the map: the selected item's.
        val sel = selectedId?.let { id -> pinned.firstOrNull { it.entity.id == id } }
        if (sel != null) drawLabel(canvas, sel.x, sel.y + markerRadius * 1.25f + dp(8f), sel.entity.headline)

        hits = newHits

        if (anyPulse) mapView.postInvalidateDelayed(48)
    }

    private fun drawPulse(canvas: Canvas, x: Float, y: Float, now: Long) {
        val period = 1800L
        for (k in 0..1) {
            val phase = ((now + k * period / 2) % period) / period.toFloat()
            val r = sosRadius + phase * dp(26f)
            val a = ((1f - phase) * 0x70).toInt().coerceIn(0, 0xFF)
            fill.color = withAlpha(MapPalette.Sos.toArgb(), a)
            canvas.drawCircle(x, y, r, fill)
        }
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, e: MapEntity, selected: Boolean) {
        val base = MapPalette.of(e.kind).toArgb()
        val r = (if (e.isCritical) sosRadius else markerRadius) * if (selected) 1.25f else 1f
        val muted = e.verified == false || e.status == ShelterStatus.CLOSED

        // Soft drop, then a keyline, then the body. No large shadows.
        fill.color = 0x73000000
        canvas.drawCircle(x, y + dp(1.5f), r + dp(1.5f), fill)
        if (selected) {
            fill.color = withAlpha(base, 0x40)
            canvas.drawCircle(x, y, r + dp(9f), fill)
        }
        fill.color = if (selected) android.graphics.Color.WHITE else 0xFF0B0F17.toInt()
        canvas.drawCircle(x, y, r + dp(2f), fill)
        fill.color = if (muted) blend(base, 0xFF2A3140.toInt(), 0.55f) else base
        canvas.drawCircle(x, y, r, fill)

        // Dark glyph on the light fills (ambulance sky, report amber), white on the rest.
        val useDark = e.kind == MapEntityKind.INCIDENT || e.kind == MapEntityKind.AMBULANCE ||
            e.kind == MapEntityKind.SHELTER
        val glyph = (if (useDark && !muted) darkGlyphs else glyphs)[e.kind]
        if (glyph != null) {
            val gs = glyphPx * (if (selected) 1.2f else 1f) * (if (e.isCritical) 1.08f else 1f)
            rect.set(x - gs / 2, y - gs / 2, x + gs / 2, y + gs / 2)
            canvas.drawBitmap(glyph, null, rect, bitmapPaint)
        }

        // A status pip for nodes that have declared themselves full or shut.
        if (e.status == ShelterStatus.FULL || e.status == ShelterStatus.CLOSED) {
            val px = x + r * 0.72f
            val py = y - r * 0.72f
            fill.color = 0xFF0B0F17.toInt()
            canvas.drawCircle(px, py, dp(5f), fill)
            fill.color = if (e.status == ShelterStatus.FULL) MapPalette.Report.toArgb() else MapPalette.Sos.toArgb()
            canvas.drawCircle(px, py, dp(3.5f), fill)
        }
    }

    private fun drawCluster(canvas: Canvas, x: Float, y: Float, members: List<MapEntity>) {
        // The ring takes the colour of the kind most of the members are, so a cluster of
        // ambulances reads as ambulances before it is opened.
        val dominant = members.groupingBy { it.kind }.eachCount().maxByOrNull { it.value }?.key
        val ring = dominant?.let { MapPalette.of(it).toArgb() } ?: android.graphics.Color.WHITE
        val r = clusterRadius + min(8f, members.size.toFloat()) * dp(0.6f)
        fill.color = 0x73000000
        canvas.drawCircle(x, y + dp(1.5f), r + dp(1f), fill)
        fill.color = 0xF2121821.toInt()
        canvas.drawCircle(x, y, r, fill)
        stroke.pathEffect = null
        stroke.color = ring
        stroke.strokeWidth = dp(2.5f)
        canvas.drawCircle(x, y, r - dp(1.25f), stroke)
        text.color = android.graphics.Color.WHITE
        text.textSize = dp(13f)
        val label = if (members.size > 99) "99+" else members.size.toString()
        canvas.drawText(label, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawLabel(canvas: Canvas, x: Float, top: Float, label: String) {
        text.textSize = dp(12f)
        val s = if (label.length > 34) label.take(33) + "…" else label
        val tw = text.measureText(s)
        val padH = dp(9f)
        val hgt = dp(24f)
        rect.set(x - tw / 2 - padH, top, x + tw / 2 + padH, top + hgt)
        fill.color = 0xF20E1116.toInt()
        canvas.drawRoundRect(rect, hgt / 2, hgt / 2, fill)
        stroke.color = 0x33FFFFFF
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = null
        canvas.drawRoundRect(rect, hgt / 2, hgt / 2, stroke)
        text.color = android.graphics.Color.WHITE
        canvas.drawText(s, x, top + hgt / 2 - (text.descent() + text.ascent()) / 2, text)
    }

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserGesture()
        return false
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val slop = dp(10f)
        var best: Hit? = null
        var bestD = Float.MAX_VALUE
        // Last drawn wins ties: it is the one on top.
        hits.asReversed().forEach { h ->
            val d = hypot(e.x - h.x, e.y - h.y)
            if (d <= h.r + slop && d < bestD) {
                best = h
                bestD = d
            }
        }
        return when (val hit = best) {
            is Hit.One -> { onEntityTap(hit.entity.id); true }
            is Hit.Many -> { onClusterTap(hit.members); true }
            null -> { onEntityTap(null); false }
        }
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
