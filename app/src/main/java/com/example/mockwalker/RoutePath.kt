package com.example.mockwalker

import org.osmdroid.util.GeoPoint
import kotlin.math.min

enum class EndMode { ONCE, PING_PONG, LOOP }

/**
 * A polyline the walker follows. Position is a pure function of distance traveled.
 * [breaks] holds node indices i where the leg (i-1 -> i) is a pen-up jump: it has zero length,
 * so the position teleports instead of walking, and it is not drawn (used to keep letters apart).
 */
class RoutePath(points: List<GeoPoint>, val endMode: EndMode, breaks: Set<Int> = emptySet()) {

    data class Sample(val lat: Double, val lon: Double, val headingDeg: Double, val finished: Boolean)

    private val closes = endMode == EndMode.LOOP && points.size >= 2

    /** Nodes actually walked (LOOP adds the closing leg back to the first point). */
    val nodes: List<GeoPoint> = if (closes) points + points.first() else points

    /** With pen-up jumps, the LOOP closing leg jumps back to the start as well. */
    val breaks: Set<Int> =
        breaks.filter { it in 1 until points.size }.toSet() +
            (if (closes && breaks.isNotEmpty()) setOf(points.size) else emptySet())

    /** Drawable pieces of the path, split at the pen-up jumps. */
    val strokes: List<List<GeoPoint>>
        get() {
            val out = mutableListOf<List<GeoPoint>>()
            var start = 0
            for (i in 1..nodes.size) {
                if (i == nodes.size || i in breaks) {
                    out.add(nodes.subList(start, i))
                    start = i
                }
            }
            return out.filter { it.size >= 2 }
        }

    private val cum = DoubleArray(nodes.size)
    val totalM: Double

    init {
        for (i in 1 until nodes.size) {
            cum[i] = cum[i - 1] + if (i in this.breaks) 0.0 else GeoMath.distance(
                nodes[i - 1].latitude, nodes[i - 1].longitude,
                nodes[i].latitude, nodes[i].longitude
            )
        }
        totalM = if (nodes.isEmpty()) 0.0 else cum.last()
    }

    fun sampleAt(traveled: Double): Sample {
        if (nodes.size < 2 || totalM <= 0.0) {
            val p = nodes.firstOrNull() ?: GeoPoint(0.0, 0.0)
            return Sample(p.latitude, p.longitude, 0.0, true)
        }
        var reverse = false
        var finished = false
        val d = when (endMode) {
            EndMode.ONCE -> {
                if (traveled >= totalM) finished = true
                min(traveled, totalM)
            }
            EndMode.LOOP -> traveled % totalM
            EndMode.PING_PONG -> {
                val m = traveled % (2 * totalM)
                if (m > totalM) {
                    reverse = true
                    2 * totalM - m
                } else m
            }
        }
        var i = 0
        while (i < nodes.size - 2 && cum[i + 1] < d) i++
        val a = nodes[i]
        val b = nodes[i + 1]
        val br = GeoMath.bearing(a.latitude, a.longitude, b.latitude, b.longitude)
        val (lat, lon) = GeoMath.destination(a.latitude, a.longitude, br, (d - cum[i]).coerceAtLeast(0.0))
        var heading = Math.toDegrees(br)
        if (reverse) heading += 180.0
        heading = ((heading % 360.0) + 360.0) % 360.0
        return Sample(lat, lon, heading, finished)
    }
}
