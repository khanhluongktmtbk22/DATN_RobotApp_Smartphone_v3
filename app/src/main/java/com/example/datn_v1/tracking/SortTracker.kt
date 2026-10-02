package com.example.datn_v1.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.datn_v1.BoundingBoxOverlay

/**
 * SORT Tracker — Simple Online and Realtime Tracking.
 *
 * Kết hợp:
 *   1. Kalman Filter (predict/update) cho mỗi track
 *   2. IoU cost matrix giữa predicted tracks và detections
 *   3. Hungarian Algorithm để tìm assignment tối ưu
 *   4. Re-ID Module để nhận dạng lại đối tượng bị mất dấu
 *
 * ═══════════════════════════════════════════════════════════════════
 *  Pipeline mỗi frame:
 *    Predict all tracks → Build IoU cost matrix → Hungarian solve
 *    → Matched: update Kalman
 *    → Unmatched tracks: nếu quá maxAge → lưu Re-ID gallery, xóa
 *    → Unmatched detections: thử Re-ID → match cũ hoặc tạo track mới
 * ═══════════════════════════════════════════════════════════════════
 *
 * @param maxAge        Số frame tối đa cho phép track không match trước khi xóa
 * @param minHits       Số lần match tối thiểu trước khi hiển thị track
 * @param iouThreshold  Ngưỡng IoU tối thiểu để chấp nhận assignment
 */
class SortTracker(
    private val maxAge: Int = 45,        // Tăng từ 30 lên 45 frame để giữ track lâu hơn khi mất dấu
    private val distractorMaxAge: Int = 10,
    private val minHits: Int = 2,        // Giảm từ 3 xuống 2 để track xuất hiện nhanh hơn
    private val iouThreshold: Float = 0.15f, // Giảm từ 0.3 xuống 0.15 để dễ match hơn khi bbox bị rung lắc
    /**
     * Ngưỡng khoảng cách tâm (pixel) dùng khi IoU-match thất bại.
     * Khi người ngã/nằm, bbox xoay 90° làm IoU gần 0 nhưng tâm vẫn gần nhau.
     * Fallback này giữ nguyên Track ID trong tình huống đó.
     */
    private val centerDistThreshold: Float = 120f
) {

    companion object {
        private const val TAG = "SortTracker"
        const val PRIMARY_TRACK_ID = 0
        private const val PROTECTED_PRIORITY_BONUS = 0.12
        private const val PROTECTED_ALTERNATIVE_PENALTY = 0.12
        private const val PROTECTED_DETECTION_PENALTY = 0.24
        private const val NORMAL_MAX_ASSOCIATION_COST = 0.76
        private const val PROTECTED_MAX_ASSOCIATION_COST = 0.88
    }

    // ─── Data classes ─────────────────────────────────────────────────────

    /** Input: Detection từ YOLO (sau NMS) */
    data class DetectionInput(
        val bbox: RectF,
        val score: Float,
        val classId: Int,                              // 0 = Normal, 1 = Fall
        val keypoints: List<BoundingBoxOverlay.Keypoint>
    )

    /** Output: Detection đã gán Track ID */
    data class TrackedObject(
        val trackId: Int,
        val bbox: RectF,
        val score: Float,
        val classId: Int,
        val keypoints: List<BoundingBoxOverlay.Keypoint>
    )

    // ─── Internal state ───────────────────────────────────────────────────

    /** Danh sách tất cả tracks đang hoạt động */
    private val tracks = mutableListOf<TrackState>()

    /** Re-ID module */
    private val reIdModule = ReIdModule()

    /** Mục tiêu chính được giữ lâu và đưa vào Re-ID; ID phụ chỉ chống nhiễu ngắn hạn. */
    private var protectedTrackId: Int? = null

    /** Lưu thông tin bổ sung cho mỗi track */
    private data class TrackState(
        val kalman: KalmanBoxTracker,
        var lastDetection: DetectionInput? = null, // Detection cuối cùng match
        var lastHistogram: org.opencv.core.Mat? = null // Histogram cuối cùng (cho Re-ID)
    )

    private data class MatchMetrics(
        val iou: Float,
        val normalizedCenterDistance: Float,
        val normalizedPoseDistance: Float,
        val areaPenalty: Float,
        val cost: Double
    )

    // ─── Main update method ───────────────────────────────────────────────

    /**
     * Cập nhật tracker với danh sách detections mới.
     *
     * @param detections  Danh sách detection từ YOLO (sau NMS)
     * @param frame       Frame gốc (Bitmap) — dùng cho Re-ID histogram
     * @return Danh sách TrackedObject có track ID ổn định
     */
    fun update(
        detections: List<DetectionInput>,
        frame: Bitmap? = null
    ): List<TrackedObject> {
        frameCount++

        // ═══════════════════════════════════════════════════════════════════
        //  BƯỚC 1: Predict — dự đoán vị trí mới cho tất cả tracks
        // ═══════════════════════════════════════════════════════════════════
        val predictedBoxes = tracks.map { it.kalman.predict() }

        // ═══════════════════════════════════════════════════════════════════
        //  BƯỚC 2: Build IoU Cost Matrix
        // ═══════════════════════════════════════════════════════════════════
        val nTracks = tracks.size
        val nDets = detections.size

        if (nTracks == 0 && nDets == 0) {
            reIdModule.ageGallery()
            return emptyList()
        }

        val protectedTrackIdx = tracks.indexOfFirst { it.kalman.id == protectedTrackId }
        val protectedPreferredDetIdx = if (protectedTrackIdx >= 0 && detections.isNotEmpty()) {
            selectProtectedDetection(
                track = tracks[protectedTrackIdx],
                predictedBox = predictedBoxes[protectedTrackIdx],
                detections = detections,
                frame = frame
            )
        } else null

        // Cost đa tín hiệu: IoU + tâm chuẩn hóa + tâm keypoint thân người + tỷ lệ diện tích.
        val costMatrix = if (nTracks > 0 && nDets > 0) {
            Array(nTracks) { i ->
                DoubleArray(nDets) { j ->
                    val baseCost = associationMetrics(
                        tracks[i], predictedBoxes[i], detections[j]
                    ).cost
                    when {
                        i == protectedTrackIdx && j == protectedPreferredDetIdx ->
                            (baseCost - PROTECTED_PRIORITY_BONUS).coerceAtLeast(0.0)
                        i == protectedTrackIdx -> baseCost + PROTECTED_ALTERNATIVE_PENALTY
                        j == protectedPreferredDetIdx -> baseCost + PROTECTED_DETECTION_PENALTY
                        else -> baseCost
                    }
                }
            }
        } else null

        // ═══════════════════════════════════════════════════════════════════
        //  BƯỚC 3: Hungarian Assignment
        // ═══════════════════════════════════════════════════════════════════
        val matchedPairs: List<Pair<Int, Int>>
        val unmatchedTrackIndices: MutableSet<Int>
        val unmatchedDetIndices: MutableSet<Int>

        if (costMatrix != null) {
            val rawAssignments = HungarianAlgorithm.solve(costMatrix)

            // Phân loại: matched vs unmatched (dựa trên IoU threshold)
            val matched = mutableListOf<Pair<Int, Int>>()
            val rejectedTracks = mutableSetOf<Int>()
            val rejectedDets = mutableSetOf<Int>()

            for ((trackIdx, detIdx) in rawAssignments) {
                val metrics = associationMetrics(
                    tracks[trackIdx], predictedBoxes[trackIdx], detections[detIdx]
                )
                if (isPlausibleMatch(metrics, trackIdx == protectedTrackIdx)) {
                    matched.add(trackIdx to detIdx)
                } else {
                    // IoU quá thấp → coi như không match
                    rejectedTracks.add(trackIdx)
                    rejectedDets.add(detIdx)
                }
            }

            // ── Center-distance fallback ────────────────────────────────────────
            // Khi người ngã/nằm: bbox xoay 90° → IoU gần 0 nhưng tâm vẫn gần.
            // Fallback: nếu track bị reject VÀ det bị reject, thử match lại bằng
            // khoảng cách tâm (pixel). Cặp nào gần nhất trong ngưỡng → vẫn giữ ID.
            val stillRejectedTracks = rejectedTracks.toMutableSet()
            val stillRejectedDets   = rejectedDets.toMutableSet()
            for (trackIdx in rejectedTracks) {
                var bestDist = centerDistThreshold
                var bestDetIdx = -1
                for (detIdx in stillRejectedDets) {
                    val dist = centerDist(predictedBoxes[trackIdx], detections[detIdx].bbox)
                    val metrics = associationMetrics(
                        tracks[trackIdx], predictedBoxes[trackIdx], detections[detIdx]
                    )
                    if (dist < bestDist &&
                        isPlausibleMatch(metrics, trackIdx == protectedTrackIdx)
                    ) {
                        bestDist = dist
                        bestDetIdx = detIdx
                    }
                }
                if (bestDetIdx >= 0) {
                    matched.add(trackIdx to bestDetIdx)
                    stillRejectedTracks.remove(trackIdx)
                    stillRejectedDets.remove(bestDetIdx)
                    Log.d(TAG, "Center-dist fallback: track=${tracks[trackIdx].kalman.id} " +
                               "det=$bestDetIdx dist=${"%.1f".format(bestDist)}px (IoU too low)")
                }
            }

            matchedPairs = matched

            // Tất cả track/det indices
            val allTrackIndices = (0 until nTracks).toMutableSet()
            val allDetIndices = (0 until nDets).toMutableSet()

            // Track indices đã match thành công
            val matchedTrackIndices = matched.map { it.first }.toSet()
            val matchedDetIndices = matched.map { it.second }.toSet()

            unmatchedTrackIndices = allTrackIndices
                .minus(matchedTrackIndices)
                .toMutableSet()

            unmatchedDetIndices = allDetIndices
                .minus(matchedDetIndices)
                .toMutableSet()

        } else {
            matchedPairs = emptyList()
            unmatchedTrackIndices = (0 until nTracks).toMutableSet()
            unmatchedDetIndices = (0 until nDets).toMutableSet()
        }

        // ═══════════════════════════════════════════════════════════════════
        //  BƯỚC 4: Update matched tracks
        // ═══════════════════════════════════════════════════════════════════
        for ((trackIdx, detIdx) in matchedPairs) {
            val track = tracks[trackIdx]
            val det = detections[detIdx]
            if (track.kalman.id == protectedTrackId) {
                val metrics = associationMetrics(track, predictedBoxes[trackIdx], det)
                if (metrics.iou < iouThreshold) {
                    Log.d(
                        TAG,
                        "Protected posture-match id=${track.kalman.id} " +
                            "iou=${"%.2f".format(metrics.iou)} " +
                            "center=${"%.2f".format(metrics.normalizedCenterDistance)} " +
                            "pose=${"%.2f".format(metrics.normalizedPoseDistance)}"
                    )
                }
            }
            track.kalman.update(det.bbox)
            track.lastDetection = det

            // Cập nhật histogram cho Re-ID (chỉ mỗi vài frame để tiết kiệm CPU)
            if (frame != null && track.kalman.age % 10 == 0 &&
                det.keypoints.count { it.visible } >= 8
            ) {
                track.lastHistogram?.release()
                track.lastHistogram = reIdModule.extractHistogram(frame, det.bbox)
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        //  BƯỚC 5: Xử lý unmatched tracks
        // ═══════════════════════════════════════════════════════════════════
        val tracksToRemove = mutableListOf<Int>()
        for (trackIdx in unmatchedTrackIndices) {
            val track = tracks[trackIdx]
            val trackMaxAge = if (track.kalman.id == protectedTrackId) maxAge else distractorMaxAge
            if (track.kalman.timeSinceUpdate > trackMaxAge) {
                // Track đã mất quá lâu → lưu vào Re-ID gallery rồi xóa
                if (track.kalman.id == protectedTrackId) track.lastHistogram?.let { hist ->
                    reIdModule.addToGallery(
                        track.kalman.id,
                        hist,
                        track.lastDetection?.classId ?: 0
                    )
                }
                tracksToRemove.add(trackIdx)
            }
        }

        // Xóa tracks (từ cuối lên đầu để không ảnh hưởng index)
        tracksToRemove.sortedDescending().forEach { idx ->
            val removed = tracks.removeAt(idx)
            removed.lastHistogram?.release()
            Log.d(TAG, "Removed track ID=${removed.kalman.id}")
        }

        // ═══════════════════════════════════════════════════════════════════
        //  BƯỚC 6: Xử lý unmatched detections → Re-ID hoặc tạo track mới
        // ═══════════════════════════════════════════════════════════════════
        for (detIdx in unmatchedDetIndices) {
            val det = detections[detIdx]
            var reIdDone = false

            // Thử Re-ID: trích histogram và so khớp với gallery
            if (frame != null) {
                val queryHist = reIdModule.extractHistogram(frame, det.bbox)
                if (queryHist != null) {
                    val match = reIdModule.findBestMatch(queryHist)
                    if (match != null) {
                        val (oldTrackId, score) = match
                        // Khôi phục track cũ với ID gốc
                        val restoredKalman = KalmanBoxTracker.restoreWithId(oldTrackId, det.bbox)
                        val trackState = TrackState(
                            kalman = restoredKalman,
                            lastDetection = det,
                            lastHistogram = queryHist
                        )
                        tracks.add(trackState)
                        reIdModule.removeFromGallery(oldTrackId)
                        reIdDone = true
                        Log.d(TAG, "Re-ID restored trackId=$oldTrackId (score=${"%.3f".format(score)})")
                    } else {
                        queryHist.release()
                    }
                }
            }

            if (!reIdDone) {
                // Tạo track hoàn toàn mới
                val newKalman = KalmanBoxTracker(det.bbox)
                val trackState = TrackState(
                    kalman = newKalman,
                    lastDetection = det,
                    lastHistogram = frame?.let { reIdModule.extractHistogram(it, det.bbox) }
                )
                tracks.add(trackState)
                Log.d(TAG, "New track ID=${newKalman.id}")
            }
        }

        // Age gallery
        reIdModule.ageGallery()

        // ═══════════════════════════════════════════════════════════════════
        //  BƯỚC 7: Output — chỉ trả về tracks đã đủ mature
        // ═══════════════════════════════════════════════════════════════════
        val result = mutableListOf<TrackedObject>()
        for (track in tracks) {
            // Chỉ hiển thị track nếu:
            //   - Đã match >= minHits lần  HOẶC  đang trong vài frame đầu
            //   - Và timeSinceUpdate <= 1 (vừa match hoặc vừa predict 1 frame)
            if ((track.kalman.hitStreak >= minHits || frameCount <= minHits)
                && track.kalman.timeSinceUpdate <= 1
            ) {
                val det = track.lastDetection
                result.add(
                    TrackedObject(
                        trackId   = track.kalman.id,
                        bbox      = track.kalman.getState(),
                        score     = det?.score ?: 0f,
                        classId   = det?.classId ?: 0,
                        keypoints = det?.keypoints ?: emptyList()
                    )
                )
            }
        }

        Log.d(TAG, "Tracks: active=${tracks.size} output=${result.size} gallery=${reIdModule.gallerySize()}")
        return result
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /** Frame counter (tổng số frame đã xử lý) */
    private var frameCount = 0

    fun setProtectedTrackId(trackId: Int?) {
        protectedTrackId = trackId
    }

    /**
     * Chuẩn hóa người mục tiêu thành ID 0. Nếu một track nhiễu cũ đang giữ ID 0,
     * loại track đó trước để trong tracker luôn chỉ tồn tại một ID 0 duy nhất.
     */
    fun promoteToPrimary(trackId: Int): Boolean {
        val primaryTrack = tracks.firstOrNull { it.kalman.id == trackId } ?: return false
        if (trackId != PRIMARY_TRACK_ID) {
            val oldPrimaryTracks = tracks.filter {
                it !== primaryTrack && it.kalman.id == PRIMARY_TRACK_ID
            }
            oldPrimaryTracks.forEach { stale ->
                tracks.remove(stale)
                stale.lastHistogram?.release()
            }
            reIdModule.removeFromGallery(PRIMARY_TRACK_ID)
            primaryTrack.kalman.reassignId(PRIMARY_TRACK_ID)
            Log.i(TAG, "Promoted track $trackId -> canonical ID $PRIMARY_TRACK_ID")
        }
        protectedTrackId = PRIMARY_TRACK_ID
        return true
    }

    /** Tính IoU giữa 2 RectF */
    private fun iou(a: RectF, b: RectF): Float {
        val iL = maxOf(a.left, b.left);   val iT = maxOf(a.top, b.top)
        val iR = minOf(a.right, b.right); val iB = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, iR - iL) * maxOf(0f, iB - iT)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Khoảng cách Euclidean giữa tâm của 2 bbox (pixel) — dùng cho center-dist fallback */
    private fun centerDist(a: RectF, b: RectF): Float {
        val dx = (a.left + a.right) / 2f - (b.left + b.right) / 2f
        val dy = (a.top + a.bottom) / 2f - (b.top + b.bottom) / 2f
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun associationMetrics(
        track: TrackState,
        predictedBox: RectF,
        detection: DetectionInput
    ): MatchMetrics {
        val previousBox = track.lastDetection?.bbox ?: predictedBox
        val referenceDiagonal = maxOf(
            bboxDiagonal(previousBox),
            bboxDiagonal(predictedBox),
            bboxDiagonal(detection.bbox),
            1f
        )
        val centerDistance = centerDist(predictedBox, detection.bbox) / referenceDiagonal

        val previousAnchor = track.lastDetection?.let { bodyAnchor(it.keypoints) }
        val currentAnchor = bodyAnchor(detection.keypoints)
        val poseDistance = if (previousAnchor != null && currentAnchor != null) {
            val dx = previousAnchor.first - currentAnchor.first
            val dy = previousAnchor.second - currentAnchor.second
            kotlin.math.sqrt(dx * dx + dy * dy) / referenceDiagonal
        } else {
            centerDistance
        }

        val oldArea = (previousBox.width() * previousBox.height()).coerceAtLeast(1f)
        val newArea = (detection.bbox.width() * detection.bbox.height()).coerceAtLeast(1f)
        val areaRatio = maxOf(oldArea / newArea, newArea / oldArea)
        val areaPenalty = (kotlin.math.ln(areaRatio.toDouble()) / kotlin.math.ln(8.0))
            .toFloat()
            .coerceIn(0f, 1f)
        val iouValue = iou(predictedBox, detection.bbox)

        val cost = (0.30f * (1f - iouValue) +
            0.30f * (centerDistance / 0.90f).coerceIn(0f, 1f) +
            0.30f * (poseDistance / 0.75f).coerceIn(0f, 1f) +
            0.10f * areaPenalty).toDouble()

        return MatchMetrics(
            iou = iouValue,
            normalizedCenterDistance = centerDistance,
            normalizedPoseDistance = poseDistance,
            areaPenalty = areaPenalty,
            cost = cost
        )
    }

    private fun selectProtectedDetection(
        track: TrackState,
        predictedBox: RectF,
        detections: List<DetectionInput>,
        frame: Bitmap?
    ): Int? {
        val candidates = detections.indices.filter {
            isPlausibleMatch(
                associationMetrics(track, predictedBox, detections[it]),
                isProtected = true
            )
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1 || frame == null || track.lastHistogram == null) {
            return candidates.minByOrNull {
                associationMetrics(track, predictedBox, detections[it]).cost
            }
        }

        // Chỉ tính màu khi có từ hai ứng viên hợp lệ, tránh tốn OpenCV ở cảnh một người.
        return candidates.minByOrNull { index ->
            val geometryCost = associationMetrics(track, predictedBox, detections[index]).cost
            val candidateHistogram = reIdModule.extractHistogram(frame, detections[index].bbox)
                ?: return@minByOrNull geometryCost + 0.20
            val correlation = try {
                reIdModule.compareHistograms(track.lastHistogram!!, candidateHistogram)
                    .takeIf { it.isFinite() }
                    ?.coerceIn(-1.0, 1.0)
                    ?: -1.0
            } finally {
                candidateHistogram.release()
            }
            val appearancePenalty = (1.0 - correlation) / 2.0
            geometryCost * 0.82 + appearancePenalty * 0.18
        }
    }

    private fun isPlausibleMatch(metrics: MatchMetrics, isProtected: Boolean): Boolean {
        val maxCenter = if (isProtected) 0.90f else 0.68f
        val maxPose = if (isProtected) 0.78f else 0.58f
        val maxCost = if (isProtected) {
            PROTECTED_MAX_ASSOCIATION_COST
        } else {
            NORMAL_MAX_ASSOCIATION_COST
        }
        val spatiallyClose = metrics.iou >= iouThreshold ||
            metrics.normalizedCenterDistance <= maxCenter ||
            metrics.normalizedPoseDistance <= maxPose
        return spatiallyClose && metrics.areaPenalty < 0.98f && metrics.cost <= maxCost
    }

    private fun bodyAnchor(
        keypoints: List<BoundingBoxOverlay.Keypoint>
    ): Pair<Float, Float>? {
        val torso = listOf(5, 6, 11, 12)
            .mapNotNull { index -> keypoints.getOrNull(index)?.takeIf { it.visible } }
        if (torso.size < 2) return null
        return torso.map { it.x }.average().toFloat() to
            torso.map { it.y }.average().toFloat()
    }

    private fun bboxDiagonal(box: RectF): Float {
        val width = box.width().coerceAtLeast(1f)
        val height = box.height().coerceAtLeast(1f)
        return kotlin.math.sqrt(width * width + height * height)
    }

    /**
     * Reset toàn bộ tracker (khi chuyển scene, v.v.)
     */
    fun reset() {
        for (track in tracks) {
            track.lastHistogram?.release()
        }
        tracks.clear()
        reIdModule.release()
        KalmanBoxTracker.resetIdCounter()
        frameCount = 0
        protectedTrackId = null
        Log.d(TAG, "Tracker reset")
    }

    /**
     * Giải phóng tài nguyên.
     */
    fun release() {
        reset()
    }
}
