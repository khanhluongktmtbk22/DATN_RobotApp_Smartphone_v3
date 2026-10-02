package com.example.datn_v1.tracking

import kotlin.math.abs
import kotlin.math.withSign

/** Pure motion policy so distance/speed tuning can be replayed and unit-tested. */
internal object TrackingMotionPolicy {
    const val MAX_TRANSLATION = 0.9

    private const val MIN_FORWARD = 0.6
    private const val MAX_FORWARD = MAX_TRANSLATION
    private const val FULL_FORWARD_METRIC = 0.30f
    private const val TURNING_FORWARD_SCALE = 0.85
    private const val UNRELIABLE_ANKLES_FORWARD_SCALE = 0.70

    private const val MIN_BACKWARD = 0.45
    private const val MAX_BACKWARD = 0.60
    private const val FULL_BACKWARD_METRIC = 0.96f

    // RobotControlManager discards steering below this while translation is strong.
    private const val STRONG_TRANSLATION_THRESHOLD = 0.30
    private const val EFFECTIVE_STEERING_THRESHOLD = 0.30

    fun forwardSpeed(
        distanceMetric: Float,
        farExitMetric: Float,
        turning: Boolean,
        anklesReliable: Boolean
    ): Double {
        val progress = normalizedProgress(
            value = farExitMetric - distanceMetric,
            range = farExitMetric - FULL_FORWARD_METRIC
        )
        val baseSpeed = lerp(MIN_FORWARD, MAX_FORWARD, progress)
        val scale = when {
            !anklesReliable -> UNRELIABLE_ANKLES_FORWARD_SCALE
            turning -> TURNING_FORWARD_SCALE
            else -> 1.0
        }
        return -(baseSpeed * scale).coerceAtMost(MAX_TRANSLATION)
    }

    fun backwardSpeed(distanceMetric: Float, closeExitMetric: Float): Double {
        val progress = normalizedProgress(
            value = distanceMetric - closeExitMetric,
            range = FULL_BACKWARD_METRIC - closeExitMetric
        )
        return lerp(MIN_BACKWARD, MAX_BACKWARD, progress)
            .coerceAtMost(MAX_TRANSLATION)
    }

    /**
     * This does not raise maximum steering. It only lets small tracking corrections pass the
     * calibrated motor deadzone while the robot is translating quickly.
     */
    fun effectiveTrackingSteering(translation: Double, filteredSteering: Double): Double {
        val magnitude = abs(filteredSteering)
        return if (
            abs(translation) > STRONG_TRANSLATION_THRESHOLD &&
            magnitude > 0.0 && magnitude < EFFECTIVE_STEERING_THRESHOLD
        ) {
            EFFECTIVE_STEERING_THRESHOLD.withSign(filteredSteering)
        } else {
            filteredSteering
        }
    }

    private fun normalizedProgress(value: Float, range: Float): Double =
        if (range <= 0f) 1.0 else (value / range).coerceIn(0f, 1f).toDouble()

    private fun lerp(start: Double, end: Double, progress: Double): Double =
        start + (end - start) * progress
}
