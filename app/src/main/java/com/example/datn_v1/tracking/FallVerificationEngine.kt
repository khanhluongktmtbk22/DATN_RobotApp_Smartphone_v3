package com.example.datn_v1.tracking

/**
 * Post-verification for the binary LSTM output.
 *
 * A candidate is armed after a stable run of consecutive FALL outputs. Verification starts
 * when the model returns to NO FALL. At that point the visible nose/shoulders are checked against the
 * upper 70% of the frame:
 * - upper-body point at y <= 0.70: reject as a likely false positive;
 * - upper-body points below y = 0.70: ask the chatbot for confirmation;
 * - missing endpoint evidence: wait briefly, then ask for confirmation because losing the
 *   person/keypoints immediately after a FALL sequence is itself a high-risk signal.
 */
class FallVerificationEngine {

    data class FrameEvidence(
        val targetVisible: Boolean,
        val upperBodyYRatio: Float? = null,
        val visibleKeypoints: Int = 0,
        val bboxFullyInside: Boolean = false,
        val aspectRatio: Float = 0f
    )

    sealed class Decision {
        data object None : Decision()
        data class Rejected(val reason: String) : Decision()
        data class AskForConfirmation(
            val reason: String,
            val evidenceScore: Int,
            val evidence: FrameEvidence
        ) : Decision()
    }

    private enum class Phase { MONITORING, FALL_SEQUENCE, VERIFYING, PROMPTED, DISMISSED, CONFIRMED }

    @Volatile private var phase = Phase.MONITORING
    private var safeEndpointFrames = 0
    private var lowEndpointFrames = 0
    private var missingEndpointFrames = 0
    private var recoveryFrames = 0
    private var fallCandidateFrames = 0

    val isIncidentActive: Boolean
        get() = phase != Phase.MONITORING

    val canAcquireNewTarget: Boolean
        get() = phase == Phase.MONITORING || phase == Phase.DISMISSED

    fun update(rawFallDetected: Boolean, evidence: FrameEvidence): Decision {
        if (phase == Phase.DISMISSED || phase == Phase.CONFIRMED) {
            if (!rawFallDetected) {
                recoveryFrames++
                if (recoveryFrames >= RECOVERY_FRAMES) reset()
            } else {
                recoveryFrames = 0
            }
            return Decision.None
        }

        if (phase == Phase.PROMPTED) return Decision.None

        if (phase == Phase.MONITORING) {
            if (rawFallDetected) {
                fallCandidateFrames++
                if (fallCandidateFrames >= MIN_CONSECUTIVE_FALL_FRAMES) {
                    phase = Phase.FALL_SEQUENCE
                    fallCandidateFrames = 0
                    resetEndpointCounters()
                }
            } else {
                fallCandidateFrames = 0
            }
            return Decision.None
        }

        if (rawFallDetected) {
            phase = Phase.FALL_SEQUENCE
            resetEndpointCounters()
            return Decision.None
        }

        // The first NO FALL frame marks the end of the LSTM fall sequence.
        phase = Phase.VERIFYING
        val upperY = evidence.upperBodyYRatio
        if (!evidence.targetVisible || upperY == null) {
            missingEndpointFrames++
            if (missingEndpointFrames >= MAX_ENDPOINT_WAIT_FRAMES) {
                phase = Phase.PROMPTED
                return Decision.AskForConfirmation(
                    reason = "target_or_upper_body_lost_after_fall",
                    evidenceScore = 1,
                    evidence = evidence
                )
            }
            return Decision.None
        }
        missingEndpointFrames = 0

        if (upperY <= UPPER_70_PERCENT_RATIO) {
            safeEndpointFrames++
            lowEndpointFrames = 0
            if (safeEndpointFrames >= ENDPOINT_STABLE_FRAMES) {
                phase = Phase.DISMISSED
                return Decision.Rejected("head_or_shoulder_in_upper_70_percent")
            }
            return Decision.None
        }

        lowEndpointFrames++
        safeEndpointFrames = 0
        if (lowEndpointFrames >= ENDPOINT_STABLE_FRAMES) {
            phase = Phase.PROMPTED
            return Decision.AskForConfirmation(
                reason = "head_and_shoulders_below_upper_70_percent",
                evidenceScore = 1,
                evidence = evidence
            )
        }
        return Decision.None
    }

    fun resolveAsSafe() {
        if (phase == Phase.PROMPTED) phase = Phase.DISMISSED
    }

    fun resolveAsDanger() {
        if (phase == Phase.PROMPTED) phase = Phase.CONFIRMED
    }

    fun reset() {
        phase = Phase.MONITORING
        recoveryFrames = 0
        fallCandidateFrames = 0
        resetEndpointCounters()
    }

    private fun resetEndpointCounters() {
        safeEndpointFrames = 0
        lowEndpointFrames = 0
        missingEndpointFrames = 0
    }

    companion object {
        private const val UPPER_70_PERCENT_RATIO = 0.70f
        private const val MIN_CONSECUTIVE_FALL_FRAMES = 5
        private const val ENDPOINT_STABLE_FRAMES = 3
        private const val MAX_ENDPOINT_WAIT_FRAMES = 12
        private const val RECOVERY_FRAMES = 10
    }
}
