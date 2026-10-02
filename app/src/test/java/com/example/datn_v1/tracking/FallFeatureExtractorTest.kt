package com.example.datn_v1.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class FallFeatureExtractorTest {
    @Test
    fun usesRawPixelScaleAndZeroVelocityOnFirstFrame() {
        val xs = FloatArray(17) { 10f + it }
        val ys = FloatArray(17) { 20f + it }
        val frame = FallFeatureExtractor.build(xs, ys, 320f, 240f)

        assertEquals(69, frame.features.size)
        assertEquals(0.10f, frame.features[0], 0.0001f)
        assertEquals(0.20f, frame.features[1], 0.0001f)
        assertEquals(0f, frame.features[2], 0f)
        assertEquals(0f, frame.features[3], 0f)
        assertEquals(1f, frame.features[68], 0.0001f)
    }

    @Test
    fun rescalesCameraCoordinatesBeforeComputingVelocity() {
        val firstX = FloatArray(17) { 20f + it * 2f }
        val firstY = FloatArray(17) { 40f + it * 2f }
        val first = FallFeatureExtractor.build(firstX, firstY, 640f, 480f)
        val second = FallFeatureExtractor.build(
            FloatArray(17) { firstX[it] + 20f },
            FloatArray(17) { firstY[it] + 10f },
            640f,
            480f,
            first.xy
        )

        assertEquals(0.20f, first.features[1], 0.0001f)
        assertEquals(0.10f, second.features[2], 0.0001f)
        assertEquals(0.05f, second.features[3], 0.0001f)
    }

    @Test
    fun missingFrameIsKeptWithZeroCoordinatesAndAspectOne() {
        val first = FallFeatureExtractor.build(
            FloatArray(17) { 10f + it },
            FloatArray(17) { 20f + it },
            320f,
            240f
        )
        val missing = FallFeatureExtractor.build(
            FloatArray(17), FloatArray(17), 320f, 240f, first.xy
        )

        assertEquals(0f, missing.features[0], 0f)
        assertEquals(0f, missing.features[1], 0f)
        assertEquals(-first.xy[0], missing.features[2], 0.0001f)
        assertEquals(-first.xy[1], missing.features[3], 0.0001f)
        assertEquals(1f, missing.features[68], 0f)
    }

    @Test
    fun positiveCoordinatesCountEvenWhenOtherKeypointsAreMissing() {
        val xs = FloatArray(17)
        val ys = FloatArray(17)
        xs[0] = 20f; ys[0] = 30f
        xs[1] = 120f; ys[1] = 80f
        val frame = FallFeatureExtractor.build(xs, ys, 320f, 240f)

        assertEquals(2f, frame.features[68], 0.0001f)
        assertEquals(0f, frame.features[8], 0f)
    }
}
