package com.example.datn_v1

/**
 * Các trạng thái của chatbot, lấy cảm hứng từ kiến trúc Xiaozhi.
 * State machine:
 *   SLEEPING → WAKEUP → LISTENING → THINKING → SPEAKING → LISTENING
 *   LISTENING → SLEEPING khi im lặng hoặc người dùng kết thúc hội thoại.
 */
enum class ChatbotState {
    /** Đang khởi tạo hồ sơ và các service */
    INITIALIZING,

    /** Đang ngủ, chờ wake word */
    SLEEPING,

    /** Vừa nhận diện được wake word, đang đọc lời chào */
    WAKEUP,

    /** Đang lắng nghe câu hỏi của người dùng */
    LISTENING,

    /** Đã nhận câu hỏi, đang chờ Groq phản hồi */
    THINKING,

    /** Đang đọc câu trả lời (TTS đang phát) */
    SPEAKING,

    /** Hoàn thành, sẵn sàng nhận câu hỏi tiếp theo */
    IDLE,

    /** Đang phát lời nhắc từ Caregiver */
    REMINDING,

    /** Đang hỏi xác nhận té ngã */
    FALL_CONFIRMING,

    /** Đang trong cuộc gọi video (chatbot tạm ngưng) */
    IN_CALL,

    /** Xảy ra lỗi */
    ERROR
}
