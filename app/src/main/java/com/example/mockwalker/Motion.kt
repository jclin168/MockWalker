package com.example.mockwalker

import kotlin.math.abs
import kotlin.random.Random

/**
 * Random speed offset within +/- [maxOffsetKph]. It picks a new target every few seconds and
 * eases toward it, so the pace drifts like a person's instead of jumping every tick.
 */
class SpeedJitter(
    private val maxOffsetKph: Double = 5.0,
    private val rateKphPerSec: Double = 0.8,
    private val random: Random = Random.Default,
) {
    var offsetKph = 0.0
        private set
    private var targetKph = 0.0
    private var holdSec = 0.0

    fun reset() {
        offsetKph = 0.0
        targetKph = 0.0
        holdSec = 0.0
    }

    fun step(dtSec: Double): Double {
        holdSec -= dtSec
        if (holdSec <= 0.0) {
            targetKph = random.nextDouble(-maxOffsetKph, maxOffsetKph)
            holdSec = random.nextDouble(4.0, 12.0)
        }
        val maxStep = rateKphPerSec * dtSec
        offsetKph += (targetKph - offsetKph).coerceIn(-maxStep, maxStep)
        return offsetKph
    }
}

/** Turns the reported heading toward the direction of travel at a limited rate, by the shorter way round. */
class HeadingSmoother(private val maxTurnDegPerSec: Double = 120.0) {
    private var heading: Double? = null

    fun reset() {
        heading = null
    }

    /** [snap] jumps straight to [targetDeg], e.g. after a pen-up teleport. */
    fun step(targetDeg: Double, dtSec: Double, snap: Boolean = false): Double {
        val cur = heading
        val next = if (cur == null || snap) {
            targetDeg
        } else {
            val diff = ((targetDeg - cur) % 360.0 + 540.0) % 360.0 - 180.0 // in [-180, 180)
            val maxTurn = maxTurnDegPerSec * dtSec
            cur + if (abs(diff) <= maxTurn) diff else maxTurn * Math.signum(diff)
        }
        return normalize(next).also { heading = it }
    }

    companion object {
        fun normalize(deg: Double) = ((deg % 360.0) + 360.0) % 360.0
    }
}
