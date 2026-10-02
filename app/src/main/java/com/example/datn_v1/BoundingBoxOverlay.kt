package com.example.datn_v1

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom View vẽ kết quả YOLO Pose + Track ID lên trên camera preview.
 *
 * Dữ liệu đầu vào (từ tracking_fragment):
 *   - rect       : tọa độ bbox tính theo PIXEL của camera (srcW × srcH)
 *   - score      : YOLO confidence hoặc xác suất từ LSTM [0,1]
 *   - classId    : 0 = NO FALL, 1 = FALL (khi có kết quả LSTM)
 *   - hasLstmResult: false nếu score và nhãn chỉ đến từ YOLO
 *   - keypoints  : 17 điểm COCO, tọa độ PIXEL của camera
 *   - trackId    : ID ổn định từ SORT Tracker (hiển thị trên bbox)
 *
 * onDraw() tự scale sang kích thước của View (match_parent).
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ─── Data classes ────────────────────────────────────────────────────────
    data class Keypoint(
        val x: Float,
        val y: Float,
        val visible: Boolean,
        val confidence: Float = if (visible) 1f else 0f
    )

    data class Detection(
        val rect      : RectF,
        val score     : Float,
        val classId   : Int,            // 0 = NO FALL, 1 = FALL
        val hasLstmResult: Boolean,
        val keypoints : List<Keypoint>, // 17 điểm COCO (camera-pixel space)
        val trackId   : Int = -1        // Track ID từ SORT (-1 = chưa track)
    ) {
        val isFall get() = classId == 1
    }

    // COCO-17 skeleton: cặp index keypoint (0-based)
    private val SKELETON = listOf(
        0 to 1,  0 to 2,               // mũi → mắt
        1 to 3,  2 to 4,               // mắt → tai
        5 to 6,                         // vai - vai
        5 to 7,  7 to 9,               // tay trái
        6 to 8,  8 to 10,              // tay phải
        5 to 11, 6 to 12,              // thân
        11 to 12,                       // hông - hông
        11 to 13, 13 to 15,            // chân trái
        12 to 14, 14 to 16             // chân phải
    )

    // ─── Bảng màu theo Track ID ─────────────────────────────────────────────
    /**
     * 12 màu đậm, phân biệt rõ ràng trên nền camera.
     * Mỗi track sẽ dùng màu = TRACK_COLORS[trackId % size].
     */
    private val TRACK_COLORS = intArrayOf(
        Color.parseColor("#FF2196F3"),   // Blue
        Color.parseColor("#FFFF9800"),   // Orange
        Color.parseColor("#FF9C27B0"),   // Purple
        Color.parseColor("#FF00BCD4"),   // Cyan
        Color.parseColor("#FFFFEB3B"),   // Yellow
        Color.parseColor("#FF4CAF50"),   // Green
        Color.parseColor("#FFE91E63"),   // Pink
        Color.parseColor("#FF3F51B5"),   // Indigo
        Color.parseColor("#FF009688"),   // Teal
        Color.parseColor("#FFFF5722"),   // Deep Orange
        Color.parseColor("#FF8BC34A"),   // Light Green
        Color.parseColor("#FF795548"),   // Brown
    )

    private fun trackColor(trackId: Int): Int {
        if (trackId < 0) return Color.WHITE
        return TRACK_COLORS[trackId % TRACK_COLORS.size]
    }

    private var detections: List<Detection> = emptyList()
    private var srcW = 1f   // camera width  (pixel)
    private var srcH = 1f   // camera height (pixel)

    // ─── Paints ──────────────────────────────────────────────────────────────

    /** Tạo Paint nhanh */
    private fun paint(
        color: Int,
        style: Paint.Style = Paint.Style.STROKE,
        stroke: Float = 0f,
        textSize: Float = 0f
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; this.style = style
        if (stroke > 0) strokeWidth = stroke
        if (textSize > 0) { this.textSize = textSize; isFakeBoldText = true }
    }

    // Bbox — FALL: màu đỏ
    private val fallBoxPaint  = paint(Color.parseColor("#FFEE3333"), Paint.Style.STROKE, 7f)
    private val fallFillPaint = paint(Color.parseColor("#22EE3333"), Paint.Style.FILL)
    private val fallLabelBg   = paint(Color.parseColor("#CCEE3333"), Paint.Style.FILL)
    private val fallKptPaint  = paint(Color.parseColor("#FFFF6644"), Paint.Style.FILL)
    private val fallSkelPaint = paint(Color.parseColor("#CCFF6644"), Paint.Style.STROKE, 4f)

    // Label text chung
    private val labelTextPaint = paint(Color.WHITE, Paint.Style.FILL, textSize = 38f)

    // ID badge
    private val idBadgePaint = paint(Color.WHITE, Paint.Style.FILL, textSize = 34f)
    private val idBgPaint    = paint(Color.parseColor("#AA000000"), Paint.Style.FILL)

    // ─── Reusable paint objects (tránh tạo mới mỗi frame) ────────────────────
    private val dynBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f
    }
    private val dynLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dynKptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dynSkelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val kptOutlinePaint = paint(Color.WHITE, Paint.Style.STROKE, 2f)

    // ─── Public API ──────────────────────────────────────────────────────────
    fun setDetections(dets: List<Detection>, camW: Float, camH: Float) {
        detections = dets; srcW = camW; srcH = camH
        postInvalidate()
    }

    fun clear() { detections = emptyList(); postInvalidate() }

    // ─── Draw ────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        val vw = width.toFloat();  val vh = height.toFloat()
        if (vw <= 0 || vh <= 0 || srcW <= 0 || srcH <= 0) return

        // Scale: camera-pixel → view-pixel (dùng logic FILL_CENTER giống PreviewView)
        val scale = maxOf(vw / srcW, vh / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val offsetX = (vw - scaledW) / 2f
        val offsetY = (vh - scaledH) / 2f

        for (det in detections) {
            // ── Tọa độ bbox trên View ─────────────────────────────────────────
            val l = det.rect.left   * scale + offsetX
            val t = det.rect.top    * scale + offsetY
            val r = det.rect.right  * scale + offsetX
            val b = det.rect.bottom * scale + offsetY

            // Lấy màu theo trackId (dùng cho Normal)
            val tColor = trackColor(det.trackId)

            // 1. Vẽ bbox theo trạng thái
            when {
                det.isFall -> {
                    // FALL — màu ĐỎ
                    canvas.drawRoundRect(l, t, r, b, 14f, 14f, fallFillPaint)
                    canvas.drawRoundRect(l, t, r, b, 14f, 14f, fallBoxPaint)
                }
                else -> {
                    // Normal — màu track
                    dynBoxPaint.color = tColor
                    canvas.drawRoundRect(l, t, r, b, 14f, 14f, dynBoxPaint)
                }
            }

            // 2. Label text theo trạng thái
            val (labelName, labelBg) = when {
                !det.hasLstmResult -> "YOLO" to null
                det.isFall -> "FALL" to fallLabelBg
                else       -> "NO FALL" to null
            }
            val pct      = String.format("%.0f", det.score * 100)
            val idPrefix = if (det.trackId >= 0) "ID:${det.trackId} " else ""
            val label    = "$idPrefix$labelName  $pct%"

            val tw = labelTextPaint.measureText(label)
            val th = 40f; val px = 14f; val py = 6f
            val labelTop = if (t > th + py * 2) t - th - py * 2 else b

            // Label background
            if (labelBg != null) {
                canvas.drawRoundRect(l, labelTop, l + tw + px * 2, labelTop + th + py * 2, 8f, 8f, labelBg)
            } else {
                dynLabelBg.color = withAlpha(tColor, 0xCC)
                canvas.drawRoundRect(l, labelTop, l + tw + px * 2, labelTop + th + py * 2, 8f, 8f, dynLabelBg)
            }
            canvas.drawText(label, l + px, labelTop + th, labelTextPaint)

            // 4. Keypoints – chỉ vẽ nếu có
            if (det.keypoints.size == 17) {
                val kptPaint: Paint
                val skelPaint: Paint

                when {
                    det.isFall  -> { kptPaint = fallKptPaint;  skelPaint = fallSkelPaint }
                    else        -> {
                        dynKptPaint.color  = tColor
                        dynSkelPaint.color = withAlpha(tColor, 0xCC)
                        kptPaint  = dynKptPaint
                        skelPaint = dynSkelPaint
                    }
                }

                // 4a. Đường skeleton
                for ((idxA, idxB) in SKELETON) {
                    val kA = det.keypoints[idxA]; val kB = det.keypoints[idxB]
                    if (!kA.visible || !kB.visible) continue
                    canvas.drawLine(
                        kA.x * scale + offsetX, kA.y * scale + offsetY,
                        kB.x * scale + offsetX, kB.y * scale + offsetY,
                        skelPaint
                    )
                }
                // 4b. Điểm khớp
                for (kp in det.keypoints) {
                    if (!kp.visible) continue
                    canvas.drawCircle(kp.x * scale + offsetX, kp.y * scale + offsetY, 8f, kptPaint)
                    // Viền trắng mỏng để nổi bật
                    canvas.drawCircle(kp.x * scale + offsetX, kp.y * scale + offsetY, 8f, kptOutlinePaint)
                }
            }
        }
    }

    /**
     * Đặt alpha cho color (giữ nguyên RGB)
     */
    private fun withAlpha(color: Int, alpha: Int): Int {
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }
}
