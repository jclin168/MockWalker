package com.example.mockwalker

import kotlin.math.abs
import kotlin.random.Random

/**
 * A value that wanders within +/- [maxOffset]. It picks a new random target every
 * [minHoldSec]..[maxHoldSec] seconds and eases toward it at [ratePerSec], so it drifts
 * smoothly instead of jumping every tick. Used for the walking pace and for GPS wander.
 */
class RandomDrift(
    private val maxOffset: Double,
    private val ratePerSec: Double,
    private val minHoldSec: Double = 4.0,
    private val maxHoldSec: Double = 12.0,
    private val random: Random = Random.Default,
) {
    var offset = 0.0
        private set
    private var target = 0.0
    private var holdSec = 0.0

    fun reset() {
        offset = 0.0
        target = 0.0
        holdSec = 0.0
    }

    fun step(dtSec: Double): Double {
        holdSec -= dtSec
        if (holdSec <= 0.0) {
            target = random.nextDouble(-maxOffset, maxOffset)
            holdSec = random.nextDouble(minHoldSec, maxHoldSec)
        }
        val maxStep = ratePerSec * dtSec
        offset += (target - offset).coerceIn(-maxStep, maxStep)
        return offset
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
