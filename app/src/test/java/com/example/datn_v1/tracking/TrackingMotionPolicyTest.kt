package com.example.datn_v1.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingMotionPolicyTest {

    @Test
    fun forwardSpeed_increasesWithDistance_andStopsAtMaximum() {
        val nearFarBoundary = TrackingMotionPolicy.forwardSpeed(0.64f, 0.64f, false, true)
        val farther = TrackingMotionPolicy.forwardSpeed(0.45f, 0.64f, false, true)
        val extremelyFar = TrackingMotionPolicy.forwardSpeed(0.05f, 0.64f, false, true)

        assertTrue(farther < nearFarBoundary)
        assertEquals(-TrackingMotionPolicy.MAX_TRANSLATION, extremelyFar, 0.001)
    }

    @Test
    fun forwardSpeed_isReducedButContinuousWhileTurningOrAnklesAreUnreliable() {
        val straight = TrackingMotionPolicy.forwardSpeed(0.45f, 0.64f, false, true)
        val turning = TrackingMotionPolicy.forwardSpeed(0.45f, 0.64f, true, true)
        val unreliableAnkles = TrackingMotionPolicy.forwardSpeed(0.45f, 0.64f, false, false)

        assertTrue(turning < 0.0 && turning > straight)
        assertTrue(unreliableAnkles < 0.0 && unreliableAnkles > turning)
    }

    @Test
    fun backwardSpeed_increasesWhenPersonGetsCloser_andIsCapped() {
        val boundary = TrackingMotionPolicy.backwardSpeed(0.72f, 0.72f)
        val closer = TrackingMotionPolicy.backwardSpeed(0.84f, 0.72f)
        val extremelyClose = TrackingMotionPolicy.backwardSpeed(1.50f, 0.72f)

        assertTrue(closer > boundary)
        assertTrue(extremelyClose >= closer)
        assertTrue(extremelyClose <= TrackingMotionPolicy.MAX_TRANSLATION)
    }

    @Test
    fun smallCenterCorrection_passesTranslationDeadzone_withoutRaisingMaximum() {
        assertEquals(
            0.30,
            TrackingMotionPolicy.effectiveTrackingSteering(-0.55, 0.12),
            0.001
        )
        assertEquals(
            -0.30,
            TrackingMotionPolicy.effectiveTrackingSteering(-0.55, -0.12),
            0.001
        )
        assertEquals(
            0.50,
            TrackingMotionPolicy.effectiveTrackingSteering(-0.55, 0.50),
            0.001
        )
    }
}
