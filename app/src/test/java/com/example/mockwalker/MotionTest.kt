package com.example.mockwalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.random.Random

class MotionTest {

    @Test
    fun speedJitterStaysWithinFiveKphAndDrifts() {
        val j = SpeedJitter(random = Random(42))
        var prev = 0.0
        val seen = mutableListOf<Double>()
        repeat(2000) {
            val o = j.step(1.0)
            assertTrue("offset $o", abs(o) <= 5.0)
            assertTrue("step ${o - prev}", abs(o - prev) <= 0.8 + 1e-9)
            prev = o
            seen.add(o)
        }
        assertTrue(seen.max() > 3.0)
        assertTrue(seen.min() < -3.0)
    }

    @Test
    fun headingTurnsTheShortWayAtLimitedRate() {
        val h = HeadingSmoother(maxTurnDegPerSec = 90.0)
        assertEquals(350.0, h.step(350.0, 1.0), 1e-9)
        // 350 -> 100 is 110 degrees clockwise through north
        assertEquals(80.0, h.step(100.0, 1.0), 1e-9)
        assertEquals(100.0, h.step(100.0, 1.0), 1e-9)
        assertEquals(270.0, h.step(270.0, 1.0, snap = true), 1e-9)
    }

    @Test
    fun routeHeadingLooksAheadAroundCorners() {
        val a = GeoPoint(37.37, -121.93)
        val (nLat, nLon) = GeoMath.destination(a.latitude, a.longitude, 0.0, 100.0)
        val (eLat, eLon) = GeoMath.destination(nLat, nLon, Math.PI / 2, 100.0)
        val path = RoutePath(listOf(a, GeoPoint(nLat, nLon), GeoPoint(eLat, eLon)), EndMode.ONCE)

        assertEquals(0.0, angle(path.headingAt(50.0, 3.0)), 0.5)
        // just before the corner the heading already swings toward east
        val near = path.headingAt(99.0, 4.0)
        assertTrue("near corner $near", near > 30.0 && near < 80.0)
        assertEquals(90.0, path.headingAt(150.0, 3.0), 0.5)
        // at the end it keeps the last leg's direction
        assertEquals(90.0, path.headingAt(500.0, 3.0), 0.5)
    }

    @Test
    fun routeHeadingReversesForPingPong() {
        val a = GeoPoint(37.37, -121.93)
        val (nLat, nLon) = GeoMath.destination(a.latitude, a.longitude, 0.0, 100.0)
        val path = RoutePath(listOf(a, GeoPoint(nLat, nLon)), EndMode.PING_PONG)
        assertEquals(180.0, path.headingAt(150.0, 3.0), 0.5)
    }

    private fun angle(deg: Double) = if (deg > 180.0) deg - 360.0 else deg
}
