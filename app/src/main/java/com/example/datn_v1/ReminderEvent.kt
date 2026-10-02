package com.example.datn_v1

/**
 * Data class chứa thông tin reminder khi trigger.
 * Được gửi từ ReminderService → ChatbotFragment qua LiveData.
 */
data class ReminderEvent(
    val scheduleId: String,
    val title: String,
    val note: String,               // Nội dung gốc (y tế an toàn, không qua LLM)
    val llmWrapper: String,         // Lời dẫn từ hồ sơ; giữ tên trường để tương thích
    val caregiverName: String,
    val caregiverRelation: String,
    val timestamp: Long,
    val logId: String               // Firebase key cho reminder_logs
)

/**
 * Data class cho sự kiện té ngã được gửi từ TrackingService → ChatbotFragment.
 */
data class FallEvent(
    val trackId: Int,
    val state: String,              // "Fall" (raw binary LSTM class)
    val probFall: Float,
    val timestamp: Long,
    val verificationReason: String = "",
    val visibleKeypoints: Int = 0,
    val bboxFullyInside: Boolean = false,
    val upperBodyYRatio: Float? = null
)

enum class FallResolutionStatus { SAFE, DANGER }

data class FallResolution(
    val trackId: Int,
    val status: FallResolutionStatus,
    val timestamp: Long = System.currentTimeMillis()
)
