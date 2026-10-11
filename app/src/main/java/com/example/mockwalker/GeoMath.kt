package com.example.mockwalker

import org.osmdroid.util.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_M = 6371008.8

    /** Point reached from (lat, lon) travelling distM along a great circle at bearingRad (clockwise from north). */
    fun destination(lat: Double, lon: Double, bearingRad: Double, distM: Double): Pair<Double, Double> {
        val d = distM / EARTH_RADIUS_M
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(bearingRad))
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(d) * cos(lat1),
            cos(d) - sin(lat1) * sin(lat2)
        )
        val lonDeg = ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0
        return Pair(Math.toDegrees(lat2), lonDeg)
    }

    /** Haversine distance in meters. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Initial bearing in radians, clockwise from north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return atan2(y, x)
    }

    fun circlePoints(lat: Double, lon: Double, radiusM: Double, count: Int = 90): List<GeoPoint> =
        List(count) { i ->
            val (la, lo) = destination(lat, lon, 2 * PI * i / count, radiusM)
            GeoPoint(la, lo)
        }
}
