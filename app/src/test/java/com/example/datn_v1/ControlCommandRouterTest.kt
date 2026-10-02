package com.example.datn_v1

import org.junit.Assert.*
import org.junit.Test

class ControlCommandRouterTest {
    private val router = ControlCommandRouter()

    private fun remote(ts: Long?, now: Long? = 10_000L, token: Long = router.generation) =
        router.acceptsRemote(token, ts, now, 2_000L, 5_000L)

    @Test fun noCommandsBeforeModeIsKnown() {
        assertFalse(router.acceptsLocal(router.generation, 100, 100, 500))
        assertFalse(remote(10_000))
    }

    @Test fun followModeAcceptsLocalButIgnoresEvenRemoteStop() {
        router.select(true)
        assertTrue(router.acceptsLocal(router.generation, 100, 150, 500))
        assertFalse(remote(10_000))
    }

    @Test fun remoteModeDiscardsStoredSnapshotThenAcceptsNewJoystickCommand() {
        router.select(false)
        assertFalse(remote(9_900))
        assertTrue(remote(10_000))
        assertFalse(router.acceptsLocal(router.generation, 100, 150, 500))
    }

    @Test fun switchingBackCannotReplayQueuedLocalCommand() {
        router.select(true)
        val previousSession = router.generation
        router.select(false)
        router.select(true)
        assertFalse(router.acceptsLocal(previousSession, 100, 150, 500))
        assertTrue(router.acceptsLocal(router.generation, 100, 150, 500))
    }

    @Test fun oldFirebaseCallbackCannotConsumeNewSessionsBaseline() {
        router.select(false)
        val previousSession = router.generation
        router.select(true)
        router.select(false)
        assertFalse(remote(9_900, token = previousSession))
        assertFalse(remote(10_000))
        assertTrue(remote(10_001))
    }

    @Test fun localCommandExpiresUsingElapsedTime() {
        router.select(true)
        assertTrue(router.acceptsLocal(router.generation, 100, 600, 500))
        assertFalse(router.acceptsLocal(router.generation, 100, 601, 500))
        assertFalse(router.acceptsLocal(router.generation, 100, 99, 500))
    }

    @Test fun remoteRejectsMissingStaleAndFutureTimestamp() {
        router.select(false)
        assertFalse(remote(null)) // Initial snapshot
        assertFalse(remote(null))
        assertFalse(remote(7_999))
        assertFalse(remote(15_001))
        assertTrue(remote(8_000))
        assertTrue(remote(15_000))
    }

    @Test fun remoteRejectsMovementUntilServerTimeIsAvailable() {
        router.select(false)
        assertFalse(remote(10_000, now = 10_000)) // Initial snapshot
        assertFalse(remote(10_001, now = null))
        assertTrue(remote(10_002, now = 10_002))
    }

    @Test fun remoteRejectsDuplicateAndOutOfOrderCommand() {
        router.select(false)
        remote(null)
        assertTrue(remote(10_000))
        assertFalse(remote(10_000))
        assertFalse(remote(9_999))
        assertTrue(remote(10_001))
    }

    @Test fun corruptStoredFutureTimestampDoesNotBlockNewCommands() {
        router.select(false)
        assertFalse(remote(Long.MAX_VALUE))
        assertTrue(remote(10_000))
    }

    @Test fun repeatedModeNotificationDoesNotInterruptControl() {
        assertTrue(router.select(false))
        remote(null)
        val session = router.generation
        assertFalse(router.select(false))
        assertEquals(session, router.generation)
        assertTrue(remote(10_000))
    }

    @Test fun losingModeStateBlocksBothSources() {
        router.select(true)
        router.select(null)
        assertFalse(router.acceptsLocal(router.generation, 100, 100, 500))
        assertFalse(remote(10_000))
    }
}
