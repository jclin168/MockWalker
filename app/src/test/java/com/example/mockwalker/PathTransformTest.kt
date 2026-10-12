package com.example.mockwalker

import org.junit.Assert.assertEquals
import org.junit.Test
import org.osmdroid.util.GeoPoint

class PathTransformTest {
    private val center = GeoPoint(37.37, -121.93)

    private fun dist(a: GeoPoint, b: GeoPoint) =
        GeoMath.distance(a.latitude, a.longitude, b.latitude, b.longitude)

    private fun bearingDeg(a: GeoPoint, b: GeoPoint) =
        (Math.toDegrees(GeoMath.bearing(a.latitude, a.longitude, b.latitude, b.longitude)) + 360.0) % 360.0

    private fun angleDiff(a: Double, b: Double) = Math.abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)

    private val square = ShapeGen.polygon(center, 4, 50.0).points

    @Test
    fun moveKeepsShapeAndSize() {
        val target = GeoPoint(25.03, 121.56)
        val moved = PathTransform.moveTo(square, target)
        assertEquals(0.0, dist(PathTransform.center(moved), target), 0.5)
        for (i in square.indices) {
            val j = (i + 1) % square.size
            assertEquals(dist(square[i], square[j]), dist(moved[i], moved[j]), 0.05)
            assertEquals(0.0, angleDiff(bearingDeg(center, square[i]), bearingDeg(target, moved[i])), 0.1)
        }
    }

    @Test
    fun rotateTurnsClockwiseAroundCenter() {
        // first vertex is due north; 90 degrees clockwise puts it due east
        val r = PathTransform.rotate(square, 90.0)
        assertEquals(50.0, dist(center, r[0]), 0.05)
        assertEquals(90.0, bearingDeg(center, r[0]), 0.1)
        val back = PathTransform.rotate(r, -90.0)
        for (i in square.indices) assertEquals(0.0, dist(square[i], back[i]), 0.01)
    }
}
