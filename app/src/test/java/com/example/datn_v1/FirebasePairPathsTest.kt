package com.example.datn_v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FirebasePairPathsTest {

    @Test
    fun differentAccounts_neverShareRealtimeChannels() {
        val firstUid = "robot-family-a"
        val secondUid = "robot-family-b"

        assertNotEquals(FirebasePairPaths.robotControl(firstUid), FirebasePairPaths.robotControl(secondUid))
        assertNotEquals(FirebasePairPaths.activeCall(firstUid), FirebasePairPaths.activeCall(secondUid))
        assertNotEquals(FirebasePairPaths.fallAlerts(firstUid), FirebasePairPaths.fallAlerts(secondUid))
    }

    @Test
    fun allRealtimeChannels_areChildrenOfAuthenticatedPair() {
        val uid = "family-uid"
        val root = "pair_sessions/$uid"

        assertEquals("$root/robot_control", FirebasePairPaths.robotControl(uid))
        assertEquals("$root/robot_follow_enabled", FirebasePairPaths.followEnabled(uid))
        assertEquals("$root/ai_fall_detection_enabled", FirebasePairPaths.fallDetectionEnabled(uid))
        assertEquals("$root/webrtc_signal", FirebasePairPaths.activeCall(uid))
        assertEquals("$root/call_history", FirebasePairPaths.callHistory(uid))
        assertEquals("$root/fall_alerts", FirebasePairPaths.fallAlerts(uid))
    }
}
