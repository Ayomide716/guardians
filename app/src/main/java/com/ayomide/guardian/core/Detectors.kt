package com.ayomide.guardian.core

import kotlin.math.sqrt

/**
 * Pocket mode: the phone must first be covered (in a pocket or bag) for a
 * moment; uncovering it afterwards triggers the alarm.
 */
class PocketDetector(private val settleMs: Long = 1500) {
    enum class State { WAITING_FOR_POCKET, IN_POCKET, TRIGGERED }

    var state = State.WAITING_FOR_POCKET
        private set
    private var nearSince = -1L

    /** Feed each proximity reading. Returns true exactly once, when triggered. */
    fun onSample(near: Boolean, timeMs: Long): Boolean {
        when (state) {
            State.WAITING_FOR_POCKET -> {
                if (near) {
                    if (nearSince < 0) nearSince = timeMs
                    if (timeMs - nearSince >= settleMs) state = State.IN_POCKET
                } else {
                    nearSince = -1
                }
            }
            State.IN_POCKET -> if (!near) {
                state = State.TRIGGERED
                return true
            }
            State.TRIGGERED -> Unit
        }
        return false
    }
}

/**
 * Movement mode: learns the resting acceleration, then triggers when it
 * changes by more than [threshold] m/s² for [needed] consecutive samples.
 */
class MotionDetector(
    private val threshold: Float = 1.6f,
    private val baselineSamples: Int = 20,
    private val needed: Int = 3,
) {
    private val base = FloatArray(3)
    private var count = 0
    private var over = 0
    var triggered = false
        private set

    fun onSample(x: Float, y: Float, z: Float): Boolean {
        if (triggered) return false
        if (count < baselineSamples) {
            base[0] += x; base[1] += y; base[2] += z
            count++
            if (count == baselineSamples) for (i in 0..2) base[i] /= baselineSamples
            return false
        }
        val dx = x - base[0]; val dy = y - base[1]; val dz = z - base[2]
        val delta = sqrt(dx * dx + dy * dy + dz * dz)
        over = if (delta > threshold) over + 1 else 0
        if (over >= needed) {
            triggered = true
            return true
        }
        return false
    }
}
