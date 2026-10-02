package com.example.datn_v1.tracking

/**
 * Thuật toán Hungarian (Kuhn-Munkres) — giải bài toán gán ghép tối ưu.
 *
 * Cho ma trận chi phí C[m×n], tìm phép gán 1-1 sao cho tổng chi phí nhỏ nhất.
 * Trong SORT: cost[i][j] = 1 - IoU(predicted_track_i, detection_j)
 *            → minimize cost ↔ maximize tổng IoU.
 *
 * Xử lý ma trận không vuông bằng cách pad thêm hàng/cột với giá trị lớn (BIG).
 */
object HungarianAlgorithm {

    private const val BIG = 1e9

    /**
     * Giải bài toán gán ghép tối ưu (minimize).
     *
     * @param costMatrix Ma trận chi phí [nRows × nCols]. Không bị thay đổi.
     * @return Danh sách cặp (rowIndex, colIndex) — chỉ chứa index hợp lệ
     *         (trong phạm vi ma trận gốc, loại bỏ các index pad).
     */
    fun solve(costMatrix: Array<DoubleArray>): List<Pair<Int, Int>> {
        if (costMatrix.isEmpty() || costMatrix[0].isEmpty()) return emptyList()

        val nRows = costMatrix.size
        val nCols = costMatrix[0].size
        val n = maxOf(nRows, nCols) // Pad thành ma trận vuông

        // Tạo bản sao vuông, pad bằng BIG
        val cost = Array(n) { i ->
            DoubleArray(n) { j ->
                if (i < nRows && j < nCols) costMatrix[i][j] else BIG
            }
        }

        // ── Thuật toán Hungarian (dạng O(n³) cổ điển) ─────────────────────

        // u[i] = potential của hàng i, v[j] = potential của cột j
        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        // p[j] = hàng được gán cho cột j (0-indexed, -1 = chưa gán)
        val p = IntArray(n + 1) { -1 }
        // way[j] = cột trước j trên đường tăng trưởng
        val way = IntArray(n + 1)

        for (i in 0 until n) {
            // Bắt đầu tìm đường tăng trưởng từ hàng i
            p[n] = i   // cột ảo n tạm gán cho hàng i
            var j0 = n  // cột hiện tại (bắt đầu ở cột ảo)

            val minv = DoubleArray(n + 1) { Double.MAX_VALUE }
            val used = BooleanArray(n + 1) { false }

            // Tìm đường tăng trưởng (augmenting path)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.MAX_VALUE
                var j1 = -1

                for (j in 0 until n) {
                    if (used[j]) continue

                    val cur = cost[i0][j] - u[i0] - v[j]
                    if (cur < minv[j]) {
                        minv[j] = cur
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }

                // Cập nhật potentials
                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }

                j0 = j1
            } while (p[j0] != -1)

            // Cập nhật assignment dọc theo đường tăng trưởng
            while (j0 != n) {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            }
        }

        // Xây dựng kết quả: chỉ lấy assignment trong phạm vi gốc
        val result = mutableListOf<Pair<Int, Int>>()
        for (j in 0 until n) {
            if (p[j] >= 0 && p[j] < nRows && j < nCols) {
                result.add(p[j] to j)
            }
        }
        return result
    }
}
