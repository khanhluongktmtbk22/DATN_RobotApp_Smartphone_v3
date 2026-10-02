package com.example.datn_v1.tracking

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FallVerificationEngineTest {

    private val upperBodyHigh = FallVerificationEngine.FrameEvidence(
        targetVisible = true,
        upperBodyYRatio = 0.55f,
        visibleKeypoints = 12,
        bboxFullyInside = true,
        aspectRatio = 0.6f
    )

    @Test
    fun singleFallOutputDoesNotStartIncident() {
        val engine = FallVerificationEngine()
        engine.update(true, upperBodyHigh)
        assertFalse(engine.isIncidentActive)
    }

    @Test
    fun fiveConsecutiveFallOutputsStartEndpointVerification() {
        val engine = FallVerificationEngine()
        repeat(5) { engine.update(true, upperBodyHigh) }
        repeat(2) { engine.update(false, upperBodyHigh) }
        val decision = engine.update(false, upperBodyHigh)
        assertTrue(decision is FallVerificationEngine.Decision.Rejected)
    }

    @Test
    fun noFallOutputResetsConsecutiveFallCandidate() {
        val engine = FallVerificationEngine()
        repeat(4) { engine.update(true, upperBodyHigh) }
        engine.update(false, upperBodyHigh)
        repeat(4) { engine.update(true, upperBodyHigh) }
        assertFalse(engine.isIncidentActive)
    }

    @Test
    fun upperBodyInUpperSeventyPercentIsRejectedAfterFallEnds() {
        val engine = armedEngine()
        var decision: FallVerificationEngine.Decision = FallVerificationEngine.Decision.None
        repeat(3) { decision = engine.update(false, upperBodyHigh) }
        assertTrue(decision is FallVerificationEngine.Decision.Rejected)
    }

    @Test
    fun upperBodyBelowSeventyPercentAsksForConfirmation() {
        val engine = armedEngine()
        val low = upperBodyHigh.copy(upperBodyYRatio = 0.78f)
        var decision: FallVerificationEngine.Decision = FallVerificationEngine.Decision.None
        repeat(3) { decision = engine.update(false, low) }
        assertTrue(decision is FallVerificationEngine.Decision.AskForConfirmation)
    }

    @Test
    fun missingEndpointEvidenceAsksForConfirmationAfterFall() {
        val engine = armedEngine()
        val missing = FallVerificationEngine.FrameEvidence(targetVisible = false)
        var decision: FallVerificationEngine.Decision = FallVerificationEngine.Decision.None
        repeat(12) { decision = engine.update(false, missing) }
        assertTrue(decision is FallVerificationEngine.Decision.AskForConfirmation)
    }

    private fun armedEngine(): FallVerificationEngine {
        return FallVerificationEngine().apply {
            repeat(5) { update(true, upperBodyHigh) }
        }
    }
}
