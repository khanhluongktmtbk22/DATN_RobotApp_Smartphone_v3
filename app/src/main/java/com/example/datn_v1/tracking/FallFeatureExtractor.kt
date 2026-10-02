package com.example.datn_v1.tracking

/** Builds the 69-value input used by fall_lstm32v2.tflite for one camera frame. */
internal object FallFeatureExtractor {
    private const val KEYPOINT_COUNT = 17
    private const val REFERENCE_WIDTH = 320f
    private const val REFERENCE_HEIGHT = 240f
    private const val PIXEL_SCALE = 100f

    data class Frame(val features: FloatArray, val xy: FloatArray)

    fun build(
        xs: FloatArray,
        ys: FloatArray,
        frameWidth: Float,
        frameHeight: Float,
        previousXY: FloatArray? = null
    ): Frame {
        require(xs.size == KEYPOINT_COUNT && ys.size == KEYPOINT_COUNT)
        require(frameWidth > 0f && frameHeight > 0f)
        require(previousXY == null || previousXY.size == KEYPOINT_COUNT * 2)

        // The training videos use a 320x240 pixel plane. Keep that pixel scale even when
        // CameraX or WebRTC supplies a different resolution.
        val scaleX = REFERENCE_WIDTH / frameWidth
        val scaleY = REFERENCE_HEIGHT / frameHeight
        val scaledX = FloatArray(KEYPOINT_COUNT) { xs[it] * scaleX }
        val scaledY = FloatArray(KEYPOINT_COUNT) { ys[it] * scaleY }
        val valid = (0 until KEYPOINT_COUNT).filter { scaledX[it] > 0f && scaledY[it] > 0f }
        val xy = FloatArray(KEYPOINT_COUNT * 2)
        val aspectRatio = if (valid.size < 2) {
            1f
        } else {
            val width = (valid.maxOf { scaledX[it] } - valid.minOf { scaledX[it] })
                .coerceAtLeast(1f)
            val height = (valid.maxOf { scaledY[it] } - valid.minOf { scaledY[it] })
                .coerceAtLeast(1f)
            for (i in 0 until KEYPOINT_COUNT) {
                xy[i * 2] = scaledX[i] / PIXEL_SCALE
                xy[i * 2 + 1] = scaledY[i] / PIXEL_SCALE
            }
            width / height
        }

        val features = FloatArray(FallDetectionQueue.NUM_FEATURES)
        for (i in 0 until KEYPOINT_COUNT) {
            val j = i * 2
            val k = i * 4
            features[k] = xy[j]
            features[k + 1] = xy[j + 1]
            features[k + 2] = if (previousXY == null) 0f else xy[j] - previousXY[j]
            features[k + 3] = if (previousXY == null) 0f else xy[j + 1] - previousXY[j + 1]
        }
        features[KEYPOINT_COUNT * 4] = aspectRatio
        return Frame(features, xy)
    }
}
