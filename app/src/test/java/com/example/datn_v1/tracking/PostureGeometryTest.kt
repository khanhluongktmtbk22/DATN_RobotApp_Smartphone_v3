package com.example.datn_v1.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureGeometryTest {

    @Test
    fun standingSamplesFromTestVideo_areNotFloorPose() {
        assertFalse(PostureGeometry.isForeshortenedFloorPose(true, 0.36f, 0.74f, 0.33f))
        assertFalse(PostureGeometry.isForeshortenedFloorPose(true, 0.39f, 0.72f, 0.35f))
    }

    @Test
    fun lyingSamplesFromTestVideo_areFloorPose() {
        assertTrue(PostureGeometry.isForeshortenedFloorPose(true, 0.80f, 0.34f, 0.11f))
        assertTrue(PostureGeometry.isForeshortenedFloorPose(true, 1.09f, 0.38f, 0.11f))
        assertTrue(PostureGeometry.isForeshortenedFloorPose(true, 0.70f, 0.35f, 0.11f))
    }

    @Test
    fun gettingUpSample_isNotKeptAsNewFloorEvidence() {
        assertFalse(PostureGeometry.isForeshortenedFloorPose(true, 0.90f, 0.29f, 0.21f))
    }

    @Test
    fun unreliableLowerBody_cannotTriggerFloorPose() {
        assertFalse(PostureGeometry.isForeshortenedFloorPose(false, 1.10f, 0.35f, 0.10f))
    }
}
