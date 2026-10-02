package com.example.datn_v1.tracking

import android.graphics.RectF

/**
 * Kalman Filter cho một track đơn lẻ trong SORT.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  STATE VECTOR (7 chiều):
 *    x = [cx, cy, s, r, vx, vy, vs]
 *      cx, cy  — tâm bounding box
 *      s       — diện tích (width × height)
 *      r       — tỷ lệ khung hình (width / height) – constant
 *      vx, vy  — vận tốc tâm
 *      vs      — vận tốc diện tích
 *
 *  OBSERVATION (4 chiều):
 *    z = [cx, cy, s, r]
 *
 *  Motion model: Constant Velocity
 *    x(k+1) = F * x(k) + noise
 *    z(k)   = H * x(k) + noise
 * ═══════════════════════════════════════════════════════════════════
 */
class KalmanBoxTracker(initialBbox: RectF, private val overrideId: Int? = null) {

    companion object {
        private var idCounter = 0

        /** Reset bộ đếm ID (dùng khi restart tracker) */
        fun resetIdCounter() { idCounter = 0 }

        /**
         * Tạo tracker mới nhưng giữ lại track ID cũ (dùng cho Re-ID).
         * idCounter vẫn tăng để tránh trùng lặp trong tương lai.
         */
        fun restoreWithId(oldId: Int, initialBbox: RectF): KalmanBoxTracker {
            return KalmanBoxTracker(initialBbox, overrideId = oldId)
        }

        // ─── Kích thước ───────────────────────────────────────────────────
        private const val STATE_DIM = 7   // [cx, cy, s, r, vx, vy, vs]
        private const val MEAS_DIM  = 4   // [cx, cy, s, r]
        private const val POSTURE_RATIO_SNAP_THRESHOLD = 1.55
    }

    /** ID duy nhất của track này */
    var id: Int = overrideId ?: idCounter++
        private set

    /** Gán ID logic cố định khi một track được xác nhận là người mục tiêu chính. */
    fun reassignId(newId: Int) {
        id = newId
        if (newId >= idCounter) idCounter = newId + 1
    }

    /** Số frame liên tiếp không match detection */
    var timeSinceUpdate: Int = 0
        private set

    /** Số frame liên tiếp được match (dùng để quyết định hiển thị) */
    var hitStreak: Int = 0
        private set

    /** Tổng số frame đã tồn tại */
    var age: Int = 0
        private set

    /** Số lần update thành công */
    var hits: Int = 0
        private set

    // ─── State & covariance matrices ──────────────────────────────────────

    /** State vector: [cx, cy, s, r, vx, vy, vs] */
    private val x = DoubleArray(STATE_DIM)

    /** State Transition Matrix (7×7): constant velocity */
    private val F = Array(STATE_DIM) { i ->
        DoubleArray(STATE_DIM) { j ->
            when {
                i == j -> 1.0
                // vx → cx, vy → cy, vs → s  (index 4→0, 5→1, 6→2)
                i < 3 && j == i + 4 -> 1.0
                else -> 0.0
            }
        }
    }

    /** Observation Matrix (4×7): đo [cx, cy, s, r] */
    private val H = Array(MEAS_DIM) { i ->
        DoubleArray(STATE_DIM) { j ->
            if (i == j) 1.0 else 0.0
        }
    }

    /** Error Covariance (7×7) */
    private val P = Array(STATE_DIM) { i ->
        DoubleArray(STATE_DIM) { j ->
            when {
                i != j -> 0.0
                i >= 4 -> 1000.0  // uncertainty lớn cho vận tốc (chưa biết)
                i == 2 -> 10.0    // diện tích
                i == 3 -> 10.0    // ratio
                else   -> 10.0    // vị trí
            }
        }
    }

    /** Process Noise (7×7) */
    private val Q = Array(STATE_DIM) { i ->
        DoubleArray(STATE_DIM) { j ->
            when {
                i != j -> 0.0
                i >= 4 -> 0.1   // vận tốc 
                i == 2 -> 1.0   // diện tích
                i == 3 -> 0.01  // ratio gần như constant
                else   -> 100.0  // vị trí (Tăng mạnh để tracker nhạy bén hơn, bám sát bounding box thực tế bị rung lắc do pose)
            }
        }
    }

    /** Measurement Noise (4×4) */
    private val R = Array(MEAS_DIM) { i ->
        DoubleArray(MEAS_DIM) { j ->
            when {
                i != j -> 0.0
                i == 2 -> 100.0  // diện tích noise lớn
                i == 3 -> 10.0  // ratio noise
                else   -> 10.0  // vị trí (tin tưởng detection hơn chút nhưng vẫn cho phép nhiễu)
            }
        }
    }

    init {
        val z = bboxToZ(initialBbox)
        // Khởi tạo state từ observation, vận tốc = 0
        for (i in 0 until MEAS_DIM) x[i] = z[i]
        // vx, vy, vs = 0 (mặc định)
    }

    // ─── Predict ──────────────────────────────────────────────────────────

    /**
     * Dự đoán trạng thái ở frame tiếp theo.
     * @return Bbox dự đoán
     */
    fun predict(): RectF {
        // Nếu diện tích + vận tốc sẽ <= 0 → set vs = 0
        if (x[2] + x[6] <= 0) x[6] *= 0.0

        // x = F * x
        val xNew = matVecMul(F, x)
        System.arraycopy(xNew, 0, x, 0, STATE_DIM)

        // P = F * P * F^T + Q
        val FP = matMul(F, P)
        val FPFt = matMulTranspose(FP, F)
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                P[i][j] = FPFt[i][j] + Q[i][j]
            }
        }

        age++
        if (timeSinceUpdate > 0) hitStreak = 0
        timeSinceUpdate++

        return getState()
    }

    // ─── Update ───────────────────────────────────────────────────────────

    /**
     * Cập nhật Kalman Filter bằng detection thực tế.
     */
    fun update(bbox: RectF) {
        timeSinceUpdate = 0
        hits++
        hitStreak++

        val z = bboxToZ(bbox)

        // Đứng ↔ ngồi ↔ nằm làm aspect ratio đổi đột ngột. Nếu vẫn ép ratio cũ qua
        // Kalman, bbox dự đoán sai hình dạng và frame sau rất dễ bị cấp ID mới.
        val oldRatio = maxOf(x[3], 0.01)
        val newRatio = maxOf(z[3], 0.01)
        val ratioChange = maxOf(oldRatio / newRatio, newRatio / oldRatio)
        if (ratioChange >= POSTURE_RATIO_SNAP_THRESHOLD) {
            x[2] = z[2]
            x[3] = z[3]
            x[6] = 0.0
            P[2][2] = 10.0
            P[3][3] = 2.0
        }

        // Innovation: y = z - H * x
        val Hx = matVecMul(H, x)
        val y = DoubleArray(MEAS_DIM) { z[it] - Hx[it] }

        // Innovation covariance: S = H * P * H^T + R
        val HP = matMul(H, P)
        val HPHt = matMulTranspose(HP, H)
        val S = Array(MEAS_DIM) { i ->
            DoubleArray(MEAS_DIM) { j -> HPHt[i][j] + R[i][j] }
        }

        // Kalman gain: K = P * H^T * S^{-1}
        val PHt = matMulTransposeRight(P, H) // P * H^T (7×4)
        val SInv = invert4x4(S)
        val K = matMul(PHt, SInv)  // (7×4) * (4×4) → (7×4)

        // Cập nhật state: x = x + K * y
        val Ky = matVecMul(K, y)
        for (i in 0 until STATE_DIM) x[i] += Ky[i]

        // Cập nhật covariance: P = (I - K * H) * P
        val KH = matMul(K, H) // (7×4) * (4×7) → (7×7)
        val IKH = Array(STATE_DIM) { i ->
            DoubleArray(STATE_DIM) { j ->
                (if (i == j) 1.0 else 0.0) - KH[i][j]
            }
        }
        val newP = matMul(IKH, P)
        for (i in 0 until STATE_DIM) {
            System.arraycopy(newP[i], 0, P[i], 0, STATE_DIM)
        }
    }

    // ─── Get current state as bbox ────────────────────────────────────────

    fun getState(): RectF = zToBbox(x)

    // ─── Bbox ↔ State conversions ─────────────────────────────────────────

    /**
     * RectF → measurement vector [cx, cy, s, r]
     */
    private fun bboxToZ(rect: RectF): DoubleArray {
        val w = (rect.right - rect.left).toDouble()
        val h = (rect.bottom - rect.top).toDouble()
        val cx = rect.left + w / 2.0
        val cy = rect.top + h / 2.0
        val s = w * h                          // diện tích
        val r = if (h > 0) w / h else 1.0      // aspect ratio
        return doubleArrayOf(cx, cy, s, r)
    }

    /**
     * State/measurement vector [cx, cy, s, r, ...] → RectF
     */
    private fun zToBbox(z: DoubleArray): RectF {
        val s = maxOf(z[2], 1.0)  // diện tích, clamp > 0
        val r = maxOf(z[3], 0.01) // ratio, clamp > 0
        val w = Math.sqrt(s * r)
        val h = s / w
        val cx = z[0]
        val cy = z[1]
        return RectF(
            (cx - w / 2.0).toFloat(),
            (cy - h / 2.0).toFloat(),
            (cx + w / 2.0).toFloat(),
            (cy + h / 2.0).toFloat()
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Ma trận helpers — tính toán thuần Kotlin, không dependency ngoài
    // ═══════════════════════════════════════════════════════════════════════

    /** Ma trận × vector */
    private fun matVecMul(A: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        return DoubleArray(A.size) { i ->
            var sum = 0.0
            for (k in v.indices) sum += A[i][k] * v[k]
            sum
        }
    }

    /** Ma trận × ma trận */
    private fun matMul(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        val m = A.size
        val n = B[0].size
        val p = B.size
        return Array(m) { i ->
            DoubleArray(n) { j ->
                var sum = 0.0
                for (k in 0 until p) sum += A[i][k] * B[k][j]
                sum
            }
        }
    }

    /** A * B^T (B transposed) */
    private fun matMulTranspose(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        val m = A.size
        val n = B.size  // B^T có n cột = B có n hàng
        return Array(m) { i ->
            DoubleArray(n) { j ->
                var sum = 0.0
                for (k in A[i].indices) sum += A[i][k] * B[j][k]
                sum
            }
        }
    }

    /** A * B^T (B transposed) — khi A và B có kích thước khác nhau */
    private fun matMulTransposeRight(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        val m = A.size
        val n = B.size
        val p = B[0].size
        return Array(m) { i ->
            DoubleArray(n) { j ->
                var sum = 0.0
                for (k in 0 until p) sum += A[i][k] * B[j][k]
                sum
            }
        }
    }

    /**
     * Nghịch đảo ma trận 4×4 bằng Gauss-Jordan.
     * Trả về ma trận đơn vị nếu singular.
     */
    private fun invert4x4(M: Array<DoubleArray>): Array<DoubleArray> {
        val n = 4
        // Augmented matrix [M | I]
        val aug = Array(n) { i ->
            DoubleArray(2 * n) { j ->
                if (j < n) M[i][j] else if (j - n == i) 1.0 else 0.0
            }
        }

        for (col in 0 until n) {
            // Pivot: tìm hàng có |value| lớn nhất
            var maxRow = col
            var maxVal = Math.abs(aug[col][col])
            for (row in col + 1 until n) {
                val v = Math.abs(aug[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-12) {
                // Singular → trả về đơn vị
                return Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
            }

            // Swap rows
            val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp

            // Scale pivot row
            val pivot = aug[col][col]
            for (j in 0 until 2 * n) aug[col][j] /= pivot

            // Eliminate column
            for (row in 0 until n) {
                if (row == col) continue
                val factor = aug[row][col]
                for (j in 0 until 2 * n) aug[row][j] -= factor * aug[col][j]
            }
        }

        // Trích phần nghịch đảo
        return Array(n) { i -> DoubleArray(n) { j -> aug[i][j + n] } }
    }
}
