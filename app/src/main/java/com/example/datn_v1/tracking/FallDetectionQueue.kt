package com.example.datn_v1.tracking

import java.util.LinkedList

/**
 * Sliding window of 15 feature vectors for one tracked person. Each frame has
 * 17 × (x/100, y/100, dx, dy) plus the keypoint aspect ratio, built by
 * [FallFeatureExtractor]. Missing detections remain in the window as zero-coordinate frames.
 */
class FallDetectionQueue(private val seqLen: Int = 15) {

    companion object {
        const val NUM_FEATURES = 69  // 17 × (x, y, dx, dy) + aspect_ratio
        const val NUM_KPS      = 17
    }

    private val frameQueue = LinkedList<FloatArray>()

    /**
     * Thêm 1 frame feature vector (69 floats) vào hàng đợi.
     * @param features FloatArray kích thước NUM_FEATURES (69)
     * @return true nếu đã gom đủ [seqLen] frames
     */
    fun addFrame(features: FloatArray): Boolean {
        require(features.size == NUM_FEATURES) {
            "Features must be $NUM_FEATURES floats, got ${features.size}"
        }
        frameQueue.addLast(features)
        if (frameQueue.size > seqLen) {
            frameQueue.removeFirst()
        }
        return frameQueue.size == seqLen
    }

    /**
     * Gộp tất cả frames thành FloatArray phẳng (size = seqLen × NUM_FEATURES).
     * Thứ tự: frame0_feat0, ..., frame0_feat68, frame1_feat0, ...
     */
    fun getFlattenedInput(): FloatArray {
        val result = FloatArray(seqLen * NUM_FEATURES)
        for (i in 0 until seqLen) {
            val frame = frameQueue[i]
            System.arraycopy(frame, 0, result, i * NUM_FEATURES, NUM_FEATURES)
        }
        return result
    }

    /** Số frame hiện tại trong hàng đợi */
    val size: Int get() = frameQueue.size

    /** Đã đủ frames chưa */
    val isReady: Boolean get() = frameQueue.size == seqLen

    /** Xóa toàn bộ dữ liệu */
    fun clear() {
        frameQueue.clear()
    }
}
