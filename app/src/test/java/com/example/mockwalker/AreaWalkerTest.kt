package com.example.mockwalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint
import kotlin.random.Random

class AreaWalkerTest {
    private val center = GeoPoint(37.37, -121.93)

    // 100 m square, corners clockwise from north-west
    private val square: List<GeoPoint> = listOf(315.0, 45.0, 135.0, 225.0).map {
        val (lat, lon) = GeoMath.destination(center.latitude, center.longitude, Math.toRadians(it), 50.0 * Math.sqrt(2.0))
        GeoPoint(lat, lon)
    }

    @Test
    fun staysInsideTheArea() {
        val w = AreaWalker(square, random = Random(7))
        val start = w.position
        assertTrue(w.inside(start))
        var farthest = 0.0
        repeat(3600) {
            w.step(1.6, 1.0) // about 5.8 km/h for an hour
            val p = w.position
            assertTrue("left the area at step $it", w.inside(p))
            farthest = maxOf(farthest, GeoMath.distance(center.latitude, center.longitude, p.latitude, p.longitude))
            assertTrue(w.moving)
        }
        // it really wanders, not stuck near the start
        assertTrue("farthest $farthest", farthest > 40.0)
    }

    @Test
    fun changesDirectionAboutEveryFiveSeconds() {
        // a big area so edge bounces do not interfere
        val huge = listOf(315.0, 45.0, 135.0, 225.0).map {
            val (lat, lon) = GeoMath.destination(center.latitude, center.longitude, Math.toRadians(it), 5000.0)
            GeoPoint(lat, lon)
        }
        val w = AreaWalker(huge, random = Random(3))
        val headings = List(600) {
            w.step(1.4, 1.0)
            w.headingDeg
        }
        // the heading is held straight for 4-6 s at a time, then changes in one step
        val runs = mutableListOf<Int>()
        var len = 1
        for (i in 1 until headings.size) {
            if (headings[i] == headings[i - 1]) len++ else {
                runs.add(len)
                len = 1
            }
        }
        runs.removeAt(0) // the first hold started before the first step
        assertTrue("runs ${runs.toSet()}", runs.all { it in 4..6 })
        assertTrue(runs.size in 95..150)
    }

    @Test
    fun areaOfSquare() {
        assertEquals(10_000.0, AreaWalker.areaM2(square), 50.0)
    }
}
