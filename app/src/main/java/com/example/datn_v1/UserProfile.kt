package com.example.datn_v1

/**
 * Data class chứa thông tin người dùng đọc từ Firebase Users/{uid}.
 * Dùng chung cho cả RobotApp và CaregiverApp (cùng tài khoản, cùng UID).
 */
data class UserProfile(
    val fullName: String = "",
    val email: String = "",
    val robotName: String = "Robot",        // Tên robot — dùng làm wake word
    val robotPronoun: String = "tôi",       // Robot tự xưng
    val elderlyPronoun: String = "Bạn",     // Cách gọi người già: "Ông", "Bà", "Bạn"...
    val emergencyPhone: String = "",
    val caregiverName: String = "",          // Tên người chăm sóc (VD: Lan, Minh)
    val caregiverRelation: String = "",      // Quan hệ (VD: Con gái, Con trai, Cháu)
    val medicalConditions: String = "",     // Bệnh lý nền — cung cấp context cho Groq
    val specialNotes: String = "",          // Ghi chú đặc biệt
    val hobbies: String = ""                // Sở thích — để chatbot chủ động trò chuyện
) {
    /**
     * Tạo system prompt cho Groq từ thông tin profile.
     * Giúp chatbot biết mình là ai và đang nói chuyện với ai.
     */
    fun toSystemPrompt(): String {
        val dateFormat = java.text.SimpleDateFormat("EEEE, dd/MM/yyyy HH:mm", java.util.Locale.forLanguageTag("vi-VN"))
        val currentTime = dateFormat.format(java.util.Date())

        val sb = StringBuilder()
        sb.appendLine("Bạn là một robot chăm sóc người cao tuổi thân thiện có tên là \"$robotName\".")
        sb.appendLine("Thời gian hiện tại của hệ thống: $currentTime.")
        sb.appendLine("Bạn xưng hô với chính mình là \"$robotPronoun\" và gọi người dùng là \"$elderlyPronoun\".")
        sb.appendLine("Nhiệm vụ của bạn là trò chuyện, hỗ trợ và đồng hành cùng người cao tuổi.")
        sb.appendLine("")
        sb.appendLine("Thông tin về người dùng bạn đang chăm sóc:")
        if (elderlyPronoun.isNotBlank()) {
            sb.appendLine("- Gọi họ là: $elderlyPronoun")
        }
        if (caregiverName.isNotBlank()) {
            sb.appendLine("- Người chăm sóc: $caregiverRelation $caregiverName")
        }
        if (medicalConditions.isNotBlank()) {
            sb.appendLine("- Tình trạng sức khỏe: $medicalConditions")
        }
        if (hobbies.isNotBlank()) {
            sb.appendLine("- Sở thích: $hobbies")
        }
        if (specialNotes.isNotBlank()) {
            sb.appendLine("- Ghi chú đặc biệt: $specialNotes")
        }
        sb.appendLine("")
        sb.appendLine("Hướng dẫn cách trả lời:")
        sb.appendLine("- Trả lời bằng tiếng Việt, tự nhiên, ấm áp, ngắn gọn (1-3 câu nếu không cần thiết phải dài).")
        // sb.appendLine("- Quan tâm đến sức khỏe, nhắc nhở uống thuốc hoặc nghỉ ngơi khi phù hợp.")
        // sb.appendLine("- Nếu người dùng nói về sở thích của họ, hãy tỏ ra hứng thú và hỏi thêm.")
        sb.appendLine("- KHÔNG sử dụng markdown, emoji, hay ký tự đặc biệt trong câu trả lời vì câu trả lời sẽ được đọc bằng giọng nói.")
        sb.appendLine("- Nếu được hỏi về tình huống khẩn cấp hoặc sức khỏe nguy hiểm, hãy nhắc họ liên hệ người thân hoặc gọi cấp cứu 115.")
        if (caregiverName.isNotBlank()) {
            // LƯU Ý: KHÔNG đề cập đến gọi điện trong chat prompt.
            // Việc phát hiện ý định gọi điện được xử lý bởi detectCallIntent() riêng biệt.
            // Nếu AI được hướng dẫn "xác nhận ý định", nó sẽ hỏi thêm thay vì để hệ thống tự xử lý.
        }
        return sb.toString()
    }

    /**
     * Tạo context string cho reminder prompt.
     * Chỉ chứa thông tin xưng hô và quan hệ — KHÔNG chứa nội dung y tế.
     */
    fun toReminderContext(): String {
        val sb = StringBuilder()
        sb.appendLine("Robot tên: $robotName")
        sb.appendLine("Robot tự xưng: $robotPronoun")
        sb.appendLine("Gọi người cao tuổi là: $elderlyPronoun")
        if (caregiverName.isNotBlank()) {
            sb.appendLine("Người chăm sóc: $caregiverRelation $caregiverName")
        }
        return sb.toString()
    }
}
