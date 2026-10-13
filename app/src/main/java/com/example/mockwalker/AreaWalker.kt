package com.example.mockwalker

import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Random walk inside the polygon formed by [area] (closed back to the first point).
 * A new random direction is chosen every [changeEverySec]; the walker turns toward it
 * gradually, and when the next step would leave the area it picks a direction that stays inside.
 * Works in local meters (x = east, y = north) around the area's center, fine for walkable sizes.
 */
class AreaWalker(
    area: List<GeoPoint>,
    private val changeEverySec: Double = 5.0,
    private val random: Random = Random.Default,
) {
    private val origin = PathTransform.center(area)
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(origin.latitude))
    private val xs = DoubleArray(area.size) { (area[it].longitude - origin.longitude) * mPerDegLon }
    private val ys = DoubleArray(area.size) { (area[it].latitude - origin.latitude) * mPerDegLat }

    private val smoother = HeadingSmoother()
    private var x = 0.0
    private var y = 0.0
    private var targetDeg = random.nextDouble(0.0, 360.0)
    private var sinceChangeSec = 0.0

    var headingDeg = targetDeg
        private set

    /** False when the last step could not move (the area is smaller than one step). */
    var moving = true
        private set

    val position: GeoPoint get() = GeoPoint(origin.latitude + y / mPerDegLat, origin.longitude + x / mPerDegLon)

    init {
        // start in the middle when it is inside, otherwise at a random inside point, else the first corner
        if (!inside(0.0, 0.0)) {
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            var found = false
            for (i in 0 until 500) {
                val px = random.nextDouble(minX, maxX + 1e-9)
                val py = random.nextDouble(minY, maxY + 1e-9)
                if (inside(px, py)) {
                    x = px; y = py; found = true
                    break
                }
            }
            if (!found && xs.isNotEmpty()) {
                x = xs[0]; y = ys[0]
            }
        }
        smoother.step(targetDeg, 0.0, snap = true)
    }

    fun step(distM: Double, dtSec: Double) {
        sinceChangeSec += dtSec
        if (sinceChangeSec >= changeEverySec) {
            targetDeg = random.nextDouble(0.0, 360.0)
            sinceChangeSec = 0.0
        }
        val h = smoother.step(targetDeg, dtSec)
        if (tryMove(h, distM)) {
            headingDeg = h
            moving = true
            return
        }
        // at the edge: bounce into a random direction that stays inside, the 5 s clock restarts
        repeat(48) {
            val d = random.nextDouble(0.0, 360.0)
            if (tryMove(d, distM)) {
                targetDeg = d
                headingDeg = smoother.step(d, dtSec, snap = true)
                sinceChangeSec = 0.0
                moving = true
                return
            }
        }
        moving = distM <= 0.0
    }

    private fun tryMove(deg: Double, distM: Double): Boolean {
        val r = Math.toRadians(deg)
        val nx = x + distM * sin(r)
        val ny = y + distM * cos(r)
        if (!inside(nx, ny)) return false
        x = nx
        y = ny
        return true
    }

    /** Even-odd ray casting. */
    fun inside(px: Double, py: Double): Boolean {
        var c = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > py) != (ys[j] > py) &&
                px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]
            ) c = !c
            j = i
        }
        return c
    }

    fun inside(p: GeoPoint) =
        inside((p.longitude - origin.longitude) * mPerDegLon, (p.latitude - origin.latitude) * mPerDegLat)

    companion object {
        /** Enclosed area in square meters (shoelace; a self-crossing outline counts its loops signed). */
        fun areaM2(points: List<GeoPoint>): Double {
            if (points.size < 3) return 0.0
            val c = PathTransform.center(points)
            val kx = 111_320.0 * cos(Math.toRadians(c.latitude))
            var sum = 0.0
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                sum += (a.longitude - c.longitude) * kx * (b.latitude - c.latitude) * 111_320.0 -
                    (b.longitude - c.longitude) * kx * (a.latitude - c.latitude) * 111_320.0
            }
            return kotlin.math.abs(sum) / 2
        }
    }
}
