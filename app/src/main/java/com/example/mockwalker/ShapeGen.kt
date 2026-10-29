package com.example.mockwalker

import org.osmdroid.util.GeoPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Generates route waypoints for preset shapes and text, centered on a point.
 * Shapes are built in local meters (x = east, y = north) and projected with GeoMath.destination.
 */
object ShapeGen {

    /** Generated waypoints; [breaks] are indices that start a new stroke (see RoutePath). */
    data class Result(val points: List<GeoPoint>, val breaks: Set<Int> = emptySet())

    /** Regular polygon, first vertex at rotationDeg (0 = due north), clockwise. LOOP end mode closes it. */
    fun polygon(center: GeoPoint, sides: Int, radiusM: Double, rotationDeg: Double = 0.0) = Result(
        List(sides) { i -> project(center, 2 * PI * i / sides + Math.toRadians(rotationDeg), radiusM) }
    )

    /** Archimedean spiral from the center outward, clockwise, ending at radiusM. */
    fun spiral(center: GeoPoint, radiusM: Double, rotationDeg: Double = 0.0, turns: Double = 3.0): Result {
        val maxAngle = 2 * PI * turns
        val rot = Math.toRadians(rotationDeg)
        // about 3 m between points, at least 24 per turn so small spirals stay round
        val steps = max(ceil(turns * 24).toInt(), ceil(PI * radiusM * turns / 3.0).toInt())
        return Result(List(steps + 1) { i ->
            val a = maxAngle * i / steps
            project(center, a + rot, radiusM * a / maxAngle)
        })
    }

    /**
     * Writes text with a single-stroke block font, each letter its own stroke: the walker
     * jumps from one letter to the next instead of drawing a joining line.
     * rotationDeg turns the whole text clockwise around its center (0 = reads west to east).
     * pitchM is the distance from the start of one letter to the start of the next
     * (default [defaultPitchM]); less than half the letter height makes letters overlap.
     * Returns null if no supported character (A-Z, 0-9) is present.
     */
    fun text(
        center: GeoPoint, text: String, letterHeightM: Double, rotationDeg: Double = 0.0, pitchM: Double? = null,
    ): Result? {
        val unit = letterHeightM / GLYPH_HEIGHT
        // pitch = start of one letter to the start of the next; a space advances one pitch too
        val advance = pitchM?.let { it / unit } ?: (GLYPH_WIDTH + LETTER_GAP)
        val local = mutableListOf<Pair<Double, Double>>()
        val breaks = mutableSetOf<Int>()
        var x = 0.0
        var width = 0.0
        for (ch in text.uppercase()) {
            val glyph = GLYPHS[ch]
            if (glyph != null) {
                if (local.isNotEmpty()) breaks.add(local.size)
                glyph.forEach { (gx, gy) -> local.add(Pair(x + gx, gy)) }
                width = x + GLYPH_WIDTH
            }
            if (glyph != null || ch == ' ') x += advance
        }
        if (local.isEmpty()) return null
        // center the text block on the chosen point, then rotate clockwise around it
        val ox = width / 2
        val oy = GLYPH_HEIGHT / 2
        val rot = Math.toRadians(rotationDeg)
        val points = local.map { (lx, ly) ->
            val ex = (lx - ox) * unit
            val ny = (ly - oy) * unit
            project(center, atan2(ex, ny) + rot, hypot(ex, ny))
        }
        return Result(points, breaks)
    }

    private fun project(center: GeoPoint, bearingRad: Double, distM: Double): GeoPoint {
        val (la, lo) = GeoMath.destination(center.latitude, center.longitude, bearingRad, distM)
        return GeoPoint(la, lo)
    }

    private const val GLYPH_WIDTH = 2.0
    private const val GLYPH_HEIGHT = 4.0
    private const val LETTER_GAP = 1.0

    /** Pitch used when none is given: letter width plus gap, 0.75 x the letter height. */
    fun defaultPitchM(letterHeightM: Double) = letterHeightM / GLYPH_HEIGHT * (GLYPH_WIDTH + LETTER_GAP)

    // Single-stroke glyphs on a 2 x 4 grid (x right, y up). Strokes retrace where needed
    // so each glyph is one unbroken path; most end on the baseline to keep joins tidy.
    private val GLYPHS: Map<Char, List<Pair<Double, Double>>> = mapOf(
        'A' to g(0, 0, 1, 4, 2, 0, 1.5, 2, 0.5, 2, 1.5, 2, 2, 0),
        'B' to g(0, 0, 0, 4, 1.5, 4, 2, 3.5, 2, 2.5, 1.5, 2, 0, 2, 1.5, 2, 2, 1.5, 2, 0.5, 1.5, 0, 0, 0),
        'C' to g(2, 4, 0, 4, 0, 0, 2, 0),
        'D' to g(0, 0, 0, 4, 1.2, 4, 2, 3, 2, 1, 1.2, 0, 0, 0),
        'E' to g(2, 4, 0, 4, 0, 2, 1.5, 2, 0, 2, 0, 0, 2, 0),
        'F' to g(2, 4, 0, 4, 0, 2, 1.5, 2, 0, 2, 0, 0),
        'G' to g(2, 4, 0, 4, 0, 0, 2, 0, 2, 2, 1, 2),
        'H' to g(0, 4, 0, 0, 0, 2, 2, 2, 2, 4, 2, 0),
        'I' to g(0, 4, 2, 4, 1, 4, 1, 0, 0, 0, 2, 0),
        'J' to g(0, 4, 2, 4, 1.5, 4, 1.5, 0, 0, 0, 0, 1),
        'K' to g(0, 4, 0, 0, 0, 2, 2, 4, 0, 2, 2, 0),
        'L' to g(0, 4, 0, 0, 2, 0),
        'M' to g(0, 0, 0, 4, 1, 2, 2, 4, 2, 0),
        'N' to g(0, 0, 0, 4, 2, 0, 2, 4),
        'O' to g(0, 0, 0, 4, 2, 4, 2, 0, 0, 0),
        'P' to g(0, 0, 0, 4, 2, 4, 2, 2, 0, 2),
        'Q' to g(1, 1, 2, 0, 0, 0, 0, 4, 2, 4, 2, 0),
        'R' to g(0, 0, 0, 4, 2, 4, 2, 2, 0, 2, 2, 0),
        'S' to g(2, 4, 0, 4, 0, 2, 2, 2, 2, 0, 0, 0),
        'T' to g(0, 4, 2, 4, 1, 4, 1, 0),
        'U' to g(0, 4, 0, 0, 2, 0, 2, 4),
        'V' to g(0, 4, 1, 0, 2, 4),
        'W' to g(0, 4, 0.5, 0, 1, 2, 1.5, 0, 2, 4),
        'X' to g(0, 0, 2, 4, 1, 2, 0, 4, 2, 0),
        'Y' to g(0, 4, 1, 2, 2, 4, 1, 2, 1, 0),
        'Z' to g(0, 4, 2, 4, 0, 0, 2, 0),
        '0' to g(0, 0, 0, 4, 2, 4, 2, 0, 0, 0, 2, 4),
        '1' to g(0.5, 3, 1, 4, 1, 0, 0, 0, 2, 0),
        '2' to g(0, 4, 2, 4, 2, 2, 0, 2, 0, 0, 2, 0),
        '3' to g(0, 4, 2, 4, 2, 2, 0.5, 2, 2, 2, 2, 0, 0, 0),
        '4' to g(0, 4, 0, 2, 2, 2, 2, 4, 2, 0),
        '5' to g(2, 4, 0, 4, 0, 2, 2, 2, 2, 0, 0, 0),
        '6' to g(2, 4, 0, 4, 0, 0, 2, 0, 2, 2, 0, 2),
        '7' to g(0, 4, 2, 4, 1, 0),
        '8' to g(0, 0, 0, 4, 2, 4, 2, 0, 0, 0, 0, 2, 2, 2),
        '9' to g(2, 0, 2, 4, 0, 4, 0, 2, 2, 2),
    )

    private fun g(vararg v: Number): List<Pair<Double, Double>> =
        v.toList().chunked(2) { Pair(it[0].toDouble(), it[1].toDouble()) }
}
