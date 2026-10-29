package com.example.mockwalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class ShapeGenTest {
    private val center = GeoPoint(37.37, -121.93)

    private fun dist(a: GeoPoint, b: GeoPoint) =
        GeoMath.distance(a.latitude, a.longitude, b.latitude, b.longitude)

    @Test
    fun textPitchSetsDistanceBetweenLetterStarts() {
        // H and I both start at their top-left corner (x = 0) with the text read west to east
        fun pitchOf(r: ShapeGen.Result) = dist(r.points[0], r.points[6])
        val auto = ShapeGen.text(center, "HI", 40.0)!!
        assertEquals(ShapeGen.defaultPitchM(40.0), 30.0, 1e-9)
        assertEquals(30.0, pitchOf(auto), 0.05)
        assertEquals(55.0, pitchOf(ShapeGen.text(center, "HI", 40.0, pitchM = 55.0)!!), 0.05)
        // pitch is measured along the text, so rotation does not change it
        assertEquals(55.0, pitchOf(ShapeGen.text(center, "HI", 40.0, 33.0, 55.0)!!), 0.05)
        // a space advances one pitch: H _ I puts I two pitches after H
        assertEquals(110.0, pitchOf(ShapeGen.text(center, "H I", 40.0, pitchM = 55.0)!!), 0.05)
    }

    @Test
    fun textLettersAreSeparateStrokes() {
        val r = ShapeGen.text(center, "HI", 40.0)!!
        // H has 6 points, so I starts at index 6
        assertEquals(setOf(6), r.breaks)
        val path = RoutePath(r.points, EndMode.ONCE, r.breaks)
        assertEquals(2, path.strokes.size)
        // the jump between letters adds no walking distance
        val walked = (1 until r.points.size).filter { it != 6 }.sumOf { dist(r.points[it - 1], r.points[it]) }
        assertEquals(walked, path.totalM, 1e-6)
    }

    @Test
    fun walkerTeleportsAcrossTheJump() {
        val r = ShapeGen.text(center, "HI", 40.0)!!
        val path = RoutePath(r.points, EndMode.ONCE, r.breaks)
        val hLen = (1 until 6).sumOf { dist(r.points[it - 1], r.points[it]) }
        val before = path.sampleAt(hLen - 0.01)
        val after = path.sampleAt(hLen + 0.01)
        // just before: at the end of H; just after: at the start of I, no walking in between
        assertTrue(dist(GeoPoint(before.lat, before.lon), r.points[5]) < 0.1)
        assertTrue(dist(GeoPoint(after.lat, after.lon), r.points[6]) < 0.1)
    }

    @Test
    fun loopWithBreaksJumpsBackToStart() {
        val r = ShapeGen.text(center, "HI", 40.0)!!
        val once = RoutePath(r.points, EndMode.ONCE, r.breaks)
        val loop = RoutePath(r.points, EndMode.LOOP, r.breaks)
        assertEquals(once.totalM, loop.totalM, 1e-6)
        assertEquals(2, loop.strokes.size)
    }

    @Test
    fun rotationTurnsTextClockwise() {
        // unrotated: the text starts west of the center; rotated 90 degrees clockwise it starts north
        val flat = ShapeGen.text(center, "HI", 40.0)!!.points.first()
        val turned = ShapeGen.text(center, "HI", 40.0, 90.0)!!.points.first()
        assertTrue(flat.longitude < center.longitude)
        assertTrue(turned.latitude > center.latitude)
        assertEquals(dist(center, flat), dist(center, turned), 0.01)
    }

    @Test
    fun polygonsHaveNoBreaksAndClose() {
        val hex = ShapeGen.polygon(center, 6, 50.0)
        assertEquals(6, hex.points.size)
        assertTrue(hex.breaks.isEmpty())
        val path = RoutePath(hex.points, EndMode.LOOP, hex.breaks)
        assertEquals(300.0, path.totalM, 0.5) // hexagon side equals its radius
    }

    @Test
    fun unsupportedTextReturnsNull() {
        assertEquals(null, ShapeGen.text(center, "你好", 40.0))
    }
}
