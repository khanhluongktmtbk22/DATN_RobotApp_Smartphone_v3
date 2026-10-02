package com.example.datn_v1

/**
 * Realtime Database paths private to one RobotApp/CaregiverApp account pair.
 * Both devices in a pair must sign in with the same Firebase Auth account.
 */
internal object FirebasePairPaths {
    private const val ROOT = "pair_sessions"

    fun root(uid: String): String = "$ROOT/${validatedUid(uid)}"
    fun robotControl(uid: String): String = "${root(uid)}/robot_control"
    fun followEnabled(uid: String): String = "${root(uid)}/robot_follow_enabled"
    fun fallDetectionEnabled(uid: String): String = "${root(uid)}/ai_fall_detection_enabled"
    fun activeCall(uid: String): String = "${root(uid)}/webrtc_signal"
    fun callHistory(uid: String): String = "${root(uid)}/call_history"
    fun fallAlerts(uid: String): String = "${root(uid)}/fall_alerts"

    private fun validatedUid(uid: String): String {
        require(uid.isNotBlank()) { "Firebase uid must not be blank" }
        require(uid.none { it in ".#$[]/" }) { "Firebase uid contains an invalid path character" }
        return uid
    }
}
