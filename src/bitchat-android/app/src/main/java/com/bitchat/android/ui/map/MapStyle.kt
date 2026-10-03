package com.bitchat.android.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.House
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath

/**
 * The map's visual language. The PWA (`frontend/indradhanu/src/components/map/mapTheme.ts` in
 * DisruptionOps) uses the same hues for the same meanings, so an SOS, a responder or a report
 * reads identically on a phone and in a browser:
 *
 *  - red is reserved for life safety (SOS), and only SOS pulses;
 *  - amber is an unverified field report;
 *  - each responder service keeps one calm hue; the glyph carries the type;
 *  - "you" is always the blue dot.
 */
object MapPalette {
    val Sos = Color(0xFFFF453A)
    val Report = Color(0xFFFFB020)
    val Ambulance = Color(0xFF5AC8FA)
    val Fire = Color(0xFFFF7A33)
    val Gov = Color(0xFF8E8CFF)
    val Shelter = Color(0xFF30D9A0)
    val You = Color(0xFF0A84FF)

    /** A real walking/road route. */
    val Route = Color(0xFF32D74B)

    /** Uncertain guidance: a direct line, not a road route. */
    val Uncertain = Color(0xFFFFB020)

    // Surfaces shared by every floating control over the map.
    val Surface = Color(0xF20E1116)
    val SurfaceRaised = Color(0xFF161B22)
    val Stroke = Color(0x29FFFFFF)
    val TextPrimary = Color(0xFFF2F4F7)
    val TextSecondary = Color(0xFF98A2B3)
    val TextTertiary = Color(0xFF667085)
    val Live = Color(0xFF32D74B)
    val Stale = Color(0xFFFFB020)

    fun of(kind: MapEntityKind): Color = when (kind) {
        MapEntityKind.SOS -> Sos
        MapEntityKind.INCIDENT -> Report
        MapEntityKind.AMBULANCE -> Ambulance
        MapEntityKind.FIRE -> Fire
        MapEntityKind.GOV -> Gov
        MapEntityKind.SHELTER -> Shelter
    }
}

fun glyphFor(kind: MapEntityKind): ImageVector = when (kind) {
    MapEntityKind.SOS -> Icons.Rounded.Sos
    MapEntityKind.INCIDENT -> Icons.Rounded.ReportProblem
    MapEntityKind.AMBULANCE -> Icons.Rounded.LocalHospital
    MapEntityKind.FIRE -> Icons.Rounded.LocalFireDepartment
    MapEntityKind.GOV -> Icons.Rounded.AccountBalance
    MapEntityKind.SHELTER -> Icons.Rounded.House
}

/**
 * A Material icon flattened to android.graphics paths, so the osmdroid overlay can draw it
 * without a composition. Material icons are flat path lists on a 24-unit viewport; group
 * transforms are not used by them and are ignored here.
 */
private fun ImageVector.toAndroidPaths(): List<Path> {
    val out = ArrayList<Path>()
    fun walk(group: VectorGroup) {
        group.forEach { node ->
            when (node) {
                is VectorPath -> {
                    val p = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
                    p.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                        Path.FillType.EVEN_ODD
                    } else {
                        Path.FillType.WINDING
                    }
                    out += p
                }
                is VectorGroup -> walk(node)
            }
        }
    }
    walk(root)
    return out
}

/** Rasterise [vector] as a single-colour glyph `sizePx` square. */
fun rasterGlyph(vector: ImageVector, sizePx: Int, color: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val scale = sizePx / vector.viewportWidth
    val m = Matrix().apply { setScale(scale, scale) }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    vector.toAndroidPaths().forEach { p ->
        p.transform(m)
        canvas.drawPath(p, paint)
    }
    return bmp
}

/**
 * Dark cartography over the same OpenStreetMap raster tiles the app already caches.
 *
 * The tiles are not swapped for a different provider: the 30-day tile cache built up while
 * online is exactly what keeps the map usable once the network is gone, and a new source would
 * start that cache from empty. Instead the light tiles are re-coloured at draw time: inverted,
 * hue-rotated back so water stays blue and arterials stay warm, desaturated, and lifted onto a
 * deep navy so labels stay legible without glare.
 */
fun darkTileFilter(): ColorMatrixColorFilter {
    val invert = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    // Luminance-preserving 180° hue rotation (SVG feColorMatrix hueRotate).
    val hue = ColorMatrix(
        floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    val saturation = ColorMatrix().apply { setSaturation(0.42f) }
    val tone = ColorMatrix(
        floatArrayOf(
            0.80f, 0f, 0f, 0f, 8f,
            0f, 0.84f, 0f, 0f, 12f,
            0f, 0f, 0.92f, 0f, 24f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    val m = ColorMatrix(invert)
    m.postConcat(hue)
    m.postConcat(saturation)
    m.postConcat(tone)
    return ColorMatrixColorFilter(m)
}

/** Background painted while a tile has not loaded (or is not cached): matches the dark map. */
val MAP_LOADING_BG: Int = Color(0xFF0B0F17).toArgb()
val MAP_LOADING_LINE: Int = Color(0xFF141A24).toArgb()
