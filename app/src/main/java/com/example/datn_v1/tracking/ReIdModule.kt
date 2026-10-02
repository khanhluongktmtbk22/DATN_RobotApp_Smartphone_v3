package com.example.datn_v1.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.imgproc.Imgproc

/**
 * Re-Identification Module — Nhận dạng lại đối tượng bị mất dấu.
 *
 * Pipeline:
 *   1. Crop vùng bbox từ frame (Bitmap → OpenCV Mat)
 *   2. Chuyển RGB → HSV
 *   3. Tính Color Histogram trên kênh H + S (bỏ V → bất biến ánh sáng)
 *   4. Normalize histogram
 *   5. So khớp bằng Correlation (Imgproc.compareHist)
 *
 * Gallery: Lưu histogram của các track bị mất (lost) để so khớp lại
 *          khi xuất hiện detection mới không match track nào.
 */
class ReIdModule(
    private val reidThreshold: Double = 0.65,
    private val maxLostAge: Int = 150           // Lưu tối đa 150 khung hình
) {

    companion object {
        private const val TAG = "ReIdModule"

        // Histogram bins: H=50, S=60 → tổng 50×60 = 3000 bins (2D histogram)
        private const val H_BINS = 50
        private const val S_BINS = 60
    }

    /**
     * Mục trong gallery: histogram + tuổi (số frame kể từ khi mất dấu)
     */
    data class GalleryEntry(
        val histogram: Mat,
        var lostAge: Int = 0,
        val classId: Int = 0      // Lưu classId để ưu tiên match cùng loại
    )

    /** Gallery lưu histogram của các track bị mất */
    private val gallery = mutableMapOf<Int, GalleryEntry>()

    // Tái sử dụng để giảm GC
    private val hsvMat = Mat()
    private val histMat = Mat()

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Trích xuất Color Histogram (H+S) từ vùng bbox trên frame.
     *
     * @param frame   Frame gốc dưới dạng Bitmap (ARGB_8888)
     * @param bbox    Bounding box tọa độ camera-pixel
     * @return Mat chứa histogram đã normalize, hoặc null nếu lỗi
     */
    fun extractHistogram(frame: Bitmap, bbox: RectF): Mat? {
        try {
            // Chỉ lấy vùng trung tâm cơ thể để giảm nền, đầu và chân. Vùng này ổn
            // định hơn khi bbox đổi từ dọc (đứng) sang ngang (nằm).
            val insetX = bbox.width() * 0.18f
            val insetY = bbox.height() * 0.12f
            val left   = maxOf(0, (bbox.left + insetX).toInt())
            val top    = maxOf(0, (bbox.top + insetY).toInt())
            val right  = minOf(frame.width, (bbox.right - insetX).toInt())
            val bottom = minOf(frame.height, (bbox.bottom - insetY).toInt())

            val w = right - left
            val h = bottom - top
            if (w <= 2 || h <= 2) return null   // Quá nhỏ để trích histogram

            // Crop bitmap
            val cropped = Bitmap.createBitmap(frame, left, top, w, h)

            // Bitmap → OpenCV Mat (RGB)
            val rgbMat = Mat()
            Utils.bitmapToMat(cropped, rgbMat)
            cropped.recycle()

            // RGB → HSV
            Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)
            rgbMat.release()

            // Tính 2D Histogram trên kênh H (0) và S (1)
            val channels = MatOfInt(0, 1)                     // H, S channels
            val histSize = MatOfInt(H_BINS, S_BINS)
            val ranges = MatOfFloat(
                0f, 180f,      // H range [0, 180) trong OpenCV
                0f, 256f       // S range [0, 256)
            )

            val images = listOf(hsvMat)
            val mask = Mat()   // Không mask

            Imgproc.calcHist(images, channels, mask, histMat, histSize, ranges)

            // Normalize → [0, 1]
            val result = Mat()
            Core.normalize(histMat, result, 0.0, 1.0, Core.NORM_MINMAX)

            // Cleanup
            channels.release()
            histSize.release()
            ranges.release()
            mask.release()

            return result
        } catch (e: Exception) {
            Log.e(TAG, "extractHistogram error: ${e.message}")
            return null
        }
    }

    /**
     * So khớp 2 histogram bằng Correlation.
     *
     * @return Score ∈ [-1, 1]; gần 1 = rất giống
     */
    fun compareHistograms(histA: Mat, histB: Mat): Double {
        return Imgproc.compareHist(histA, histB, Imgproc.CV_COMP_CORREL)
    }

    /**
     * Thêm histogram của track đã mất vào gallery.
     */
    fun addToGallery(trackId: Int, histogram: Mat, classId: Int = 0) {
        gallery[trackId] = GalleryEntry(
            histogram = histogram.clone(),
            lostAge = 0,
            classId = classId
        )
        Log.d(TAG, "Added trackId=$trackId to gallery (size=${gallery.size})")
    }

    /**
     * Xóa track khỏi gallery (khi đã match thành công hoặc quá cũ).
     */
    fun removeFromGallery(trackId: Int) {
        gallery.remove(trackId)?.histogram?.release()
    }

    /**
     * Tìm track cũ tốt nhất match với queryHist.
     *
     * @return (trackId, score) nếu tìm thấy match > threshold, null nếu không
     */
    fun findBestMatch(queryHist: Mat): Pair<Int, Double>? {
        if (gallery.isEmpty()) return null

        var bestId = -1
        var bestScore = -1.0

        for ((trackId, entry) in gallery) {
            val score = compareHistograms(queryHist, entry.histogram)
            if (score > bestScore) {
                bestScore = score
                bestId = trackId
            }
        }

        return if (bestScore >= reidThreshold && bestId >= 0) {
            Log.d(TAG, "Re-ID match: trackId=$bestId score=${"%.3f".format(bestScore)}")
            bestId to bestScore
        } else {
            null
        }
    }

    /**
     * Cập nhật tuổi gallery mỗi frame. Xóa entry quá cũ.
     * Gọi mỗi frame trong SortTracker.update().
     */
    fun ageGallery() {
        val toRemove = mutableListOf<Int>()
        for ((trackId, entry) in gallery) {
            entry.lostAge++
            if (entry.lostAge > maxLostAge) {
                toRemove.add(trackId)
            }
        }
        toRemove.forEach { removeFromGallery(it) }
    }

    /**
     * Giải phóng tất cả tài nguyên OpenCV.
     */
    fun release() {
        for ((_, entry) in gallery) {
            entry.histogram.release()
        }
        gallery.clear()
        hsvMat.release()
        histMat.release()
    }

    /**
     * Số entry hiện tại trong gallery.
     */
    fun gallerySize(): Int = gallery.size
}
