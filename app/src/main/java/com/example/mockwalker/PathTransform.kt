package com.example.mockwalker

import org.osmdroid.util.GeoPoint

/**
 * Moves and rotates waypoints as a rigid shape. Each point keeps its distance and bearing from
 * the path's center, so the shape stays the same size in meters wherever it is placed.
 */
object PathTransform {

    /** Middle of the bounding box. */
    fun center(points: List<GeoPoint>): GeoPoint = GeoPoint(
        (points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2,
        (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2
    )

    /** Same shape with its center at [newCenter]. */
    fun moveTo(points: List<GeoPoint>, newCenter: GeoPoint): List<GeoPoint> =
        transform(points, center(points), newCenter, 0.0)

    /** Turned [deg] clockwise around [pivot] (the path's center by default). */
    fun rotate(points: List<GeoPoint>, deg: Double, pivot: GeoPoint = center(points)): List<GeoPoint> =
        transform(points, pivot, pivot, Math.toRadians(deg))

    private fun transform(points: List<GeoPoint>, from: GeoPoint, to: GeoPoint, turnRad: Double) =
        points.map { p ->
            val d = GeoMath.distance(from.latitude, from.longitude, p.latitude, p.longitude)
            if (d == 0.0) {
                GeoPoint(to.latitude, to.longitude)
            } else {
                val br = GeoMath.bearing(from.latitude, from.longitude, p.latitude, p.longitude)
                val (lat, lon) = GeoMath.destination(to.latitude, to.longitude, br + turnRad, d)
                GeoPoint(lat, lon)
            }
        }
}
