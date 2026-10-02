package com.example.datn_v1

import org.junit.Assert.assertEquals
import org.junit.Test

class RobotControlManagerTest {

    @Test
    fun forward_preservesCalibratedOppositeMotorTargets() {
        val (left, right) = RobotControlManager.mapControlToMotorTargets(-1f, 0f)
        assertEquals(-20f, left, 0.001f)
        assertEquals(20f, right, 0.001f)
    }

    @Test
    fun backward_preservesCalibratedOppositeMotorTargets() {
        val (left, right) = RobotControlManager.mapControlToMotorTargets(1f, 0f)
        assertEquals(20f, left, 0.001f)
        assertEquals(-20f, right, 0.001f)
    }

    @Test
    fun positiveSteering_preservesEqualPositiveMotorTargets() {
        val (left, right) = RobotControlManager.mapControlToMotorTargets(0f, 1f)
        assertEquals(3f, left, 0.001f)
        assertEquals(3f, right, 0.001f)
    }

    @Test
    fun negativeSteering_preservesEqualNegativeMotorTargets() {
        val (left, right) = RobotControlManager.mapControlToMotorTargets(0f, -1f)
        assertEquals(-3f, left, 0.001f)
        assertEquals(-3f, right, 0.001f)
    }

    @Test
    fun smallSteering_isIgnoredDuringStrongTranslation() {
        val (left, right) = RobotControlManager.mapControlToMotorTargets(0.4f, 0.2f)
        assertEquals(8f, left, 0.001f)
        assertEquals(-8f, right, 0.001f)
    }

    @Test
    fun intentionalSteering_isPreservedDuringTranslation() {
        val (left, right) = RobotControlManager.mapControlToMotorTargets(0.4f, 0.5f)
        assertEquals(9.5f, left, 0.001f)
        assertEquals(-6.5f, right, 0.001f)
    }
}
