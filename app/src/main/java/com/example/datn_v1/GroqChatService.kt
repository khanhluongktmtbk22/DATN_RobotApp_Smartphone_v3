package com.example.datn_v1

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service tích hợp Groq API cho ChatbotFragment.
 * Đây là LLM service chính của RobotApp.
 */
class GroqChatService(
    private val apiKey: String,
    private val userProfile: UserProfile
) {
    companion object {
        private const val TAG = "GroqChatService"
        // Model production dùng cho hội thoại: ổn định, phản hồi nhanh và hỗ trợ tiếng Việt.
        private const val MODEL_CHAT = "openai/gpt-oss-120b"
        // Dùng cùng model với mức reasoning thấp cho các tác vụ phân loại ngắn.
        private const val MODEL_CLASSIFY = "openai/gpt-oss-120b"
        private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Mỗi phiên sở hữu một danh sách riêng. Request cũ không thể ghi vào phiên mới.
    @Volatile
    private var history = newSessionHistory()

    private fun newSessionHistory() = mutableListOf(
        JSONObject()
            .put("role", "system")
            .put("content", userProfile.toSystemPrompt())
    )

    /**
     * Gửi tin nhắn và nhận phản hồi streaming.
     * Trả về Flow<String> mỗi chunk text từ Groq.
     */
    fun sendMessageStream(userMessage: String): Flow<String> = flow {
        currentCoroutineContext().ensureActive()
        val sessionHistory = history
        Log.d(TAG, "Gửi tin nhắn tới Groq: $userMessage")

        // Add user message to history
        val userMsgObj = JSONObject()
            .put("role", "user")
            .put("content", userMessage)
        val messagesArray = synchronized(sessionHistory) {
            sessionHistory.add(userMsgObj)
            // Giữ system prompt và tối đa 20 tin nhắn gần nhất trong cùng phiên.
            val maxHistoryMessages = 20
            while (sessionHistory.size > maxHistoryMessages + 1) {
                sessionHistory.removeAt(1)
            }
            JSONArray().also { messages ->
                sessionHistory.forEach { messages.put(it) }
            }
        }

        val requestBodyJson = JSONObject()
            .put("model", MODEL_CHAT)
            .put("messages", messagesArray)
            .put("temperature", 0.7)
            .put("reasoning_effort", "low")
            .put("include_reasoning", false)
            .put("max_completion_tokens", 320)
            .put("stream", true)

        val requestBody = requestBodyJson.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val botResponse = StringBuilder()
        var requestCompleted = false

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw IOException("Groq API ${response.code}: $errorBody")
                }

                response.body?.charStream()?.buffered()?.use { reader ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data:")) {
                            val data = line.substringAfter("data:").trim()
                            if (data == "[DONE]") break
                            try {
                                val json = JSONObject(data)
                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content", "").orEmpty()
                                    if (content.isNotEmpty() && content != "null") {
                                        botResponse.append(content)
                                        emit(content)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Lỗi parse JSON stream: $data", e)
                            }
                        }
                    }
                }
            }

            // Add bot response to history
            if (botResponse.isNotEmpty()) {
                val botMsgObj = JSONObject()
                    .put("role", "assistant")
                    .put("content", botResponse.toString())
                synchronized(sessionHistory) { sessionHistory.add(botMsgObj) }
                requestCompleted = true
            }

        } catch (e: IOException) {
            Log.e(TAG, "Lỗi kết nối Groq API: ${e.message}", e)
            throw e
        } finally {
            // Không để lại một user message mồ côi khi request bị lỗi hoặc coroutine bị hủy.
            if (!requestCompleted) synchronized(sessionHistory) { sessionHistory.remove(userMsgObj) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Xóa lịch sử hội thoại — dùng khi bắt đầu cuộc trò chuyện mới
     */
    fun resetHistory() {
        // Thay danh sách thay vì clear(): luồng streaming cũ có thể vẫn đang kết thúc.
        history = newSessionHistory()
        Log.d(TAG, "Đã reset lịch sử hội thoại")
    }

    /**
     * Cập nhật profile người dùng và reset session (khi profile thay đổi)
     */
    fun updateProfileAndReset(newProfile: UserProfile): GroqChatService {
        return GroqChatService(apiKey, newProfile)
    }

    // ════════════════════════════════════════════════════════════════════════
    // DEDICATED API METHODS (tách biệt khỏi chat history)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Sinh câu chào thân thiện cho lời nhắc (Data Interpolation pattern).
     * KHÔNG chứa nội dung y tế — chỉ sinh ngữ cảnh bao quanh.
     * Gọi Groq API trực tiếp, không ảnh hưởng chat history.
     */
    suspend fun generateReminderWrapper(
        caregiverName: String,
        caregiverRelation: String,
        reminderTitle: String,
        elderlyPronoun: String,
        robotPronoun: String
    ): String {
        val caregiverPart = if (caregiverName.isNotBlank()) 
            "$caregiverRelation $caregiverName" else "người chăm sóc"
        val systemPrompt = """
            Bạn là robot chăm sóc người cao tuổi. Tự xưng là "$robotPronoun", gọi người cao tuổi là "$elderlyPronoun".
            Hãy viết đúng 1 câu chào ngắn gọn (tối đa 20 từ), thân thiện để giới thiệu lời nhắc từ $caregiverPart.
            Lời nhắc có tiêu đề: "$reminderTitle".
            QUY TẮc: Chỉ viết đúng 1 câu. Không nhắc lại tiêu đề. Không markdown. Không emoji. Không giải thích.
        """.trimIndent()
        return callGroqDedicated(systemPrompt, "Sinh câu chào", maxTokens = 60)
    }

    /**
     * Phân loại phản hồi té ngã: 0=safe, 1=danger.
     * System prompt cực kỳ chặt chẽ — chỉ trả về số 0 hoặc 1.
     */
    suspend fun classifyFallResponse(elderlyResponse: String): Int {
        val normalized = elderlyResponse.lowercase().trim()
        val dangerScan = normalized
            .replace("không bị ngã", "")
            .replace("không đau", "")
        val dangerKeywords = listOf(
            "bị ngã", "té rồi", "đau", "không đứng", "không dậy", "cứu", "giúp",
            "gọi người nhà", "gọi cấp cứu", "có vấn đề"
        )
        if (dangerKeywords.any { dangerScan.contains(it) }) return 1
        val safeKeywords = listOf(
            "không sao", "không có gì", "vẫn ổn", "ổn mà", "bình thường", "không bị ngã"
        )
        if (safeKeywords.any { normalized.contains(it) }) return 0

        val systemPrompt = """
            Bạn là hệ thống y tế khẩn cấp. Người cao tuổi vừa bị ngã và được hỏi "Bà có sao không?".
            Hãy phân loại câu trả lời: "$elderlyResponse".
            Bạn PHẢI trả về ĐÚNG MỘT JSON hợp lệ, KHÔNG chứa thêm văn bản nào khác.
            Định dạng JSON:
            {"status": "danger"} nếu họ nói đau, mệt, cần gọi người nhà, hoặc trả lời không rõ ràng.
            {"status": "safe"} nếu họ nói rõ ràng là hoàn toàn bình thường, không sao cả.
        """.trimIndent()
        val result = callGroqClassify(systemPrompt, elderlyResponse)
        
        return try {
            val jsonStart = result.indexOf("{")
            val jsonEnd = result.lastIndexOf("}")
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonString = result.substring(jsonStart, jsonEnd + 1)
                val json = JSONObject(jsonString)
                if (json.optString("status") == "safe") 0 else 1
            } else 1
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Phát hiện ý định gọi điện từ câu nói của người già.
     * Trả về: "call" nếu muốn gọi điện, "none" nếu không.
     */
    suspend fun detectCallIntent(userMessage: String, caregiverName: String, caregiverRelation: String): String {
        // Bước 1: Kiểm tra nhanh bằng keyword trước (không cần API call)
        val callKeywords = listOf(
            "gọi điện", "goi dien", "gọi video", "goi video", "gọi cho", "goi cho",
            "gọi giùm", "goi gium", "gọi giúp", "goi giup",
            "muốn gặp", "muon gap", "cho gặp", "cho gap",
            "nói chuyện với", "noi chuyen voi",
            "kết nối", "ket noi", "bấm máy", "bam may",
            "bật máy lên", "bat may len",
            "liên lạc", "lien lac", "liên hệ", "lien he",
            "gọi ngay", "goi ngay", "call", "facetime", "video call"
        )
        val msgLower = userMessage.lowercase().trim()
        // Kiểm tra keyword chung
        if (callKeywords.any { msgLower.contains(it) }) {
            Log.d(TAG, "detectCallIntent: keyword match → call | msg=\"$userMessage\"")
            return "call"
        }
        // Kiểm tra tên người chăm sóc (nếu có)
        if (caregiverName.isNotBlank()) {
            val nameLower = caregiverName.lowercase().trim()
            if (msgLower.contains(nameLower) && nameLower.length >= 2) {
                // Tên người chăm sóc xuất hiện → rất có khả năng muốn gọi
                // Dùng AI để xác nhận thêm
                Log.d(TAG, "detectCallIntent: caregiver name found, sending to AI for confirmation")
            }
        }

        // Không gọi thêm một request AI cho mọi câu chat thông thường. Chỉ phân loại bằng
        // model khi câu có nhắc người thân hoặc biểu đạt mong muốn liên lạc mơ hồ.
        val caregiverMentioned = caregiverName.trim().takeIf { it.length >= 2 }
            ?.let { msgLower.contains(it.lowercase()) } == true
        val relationMentioned = caregiverRelation.trim().takeIf { it.length >= 2 }
            ?.let { msgLower.contains(it.lowercase()) } == true
        val ambiguousCallHints = listOf(
            "nhớ con", "nhớ cháu", "nhớ người nhà", "muốn gặp", "lâu rồi không thấy",
            "ở đâu rồi", "đâu rồi"
        )
        if (!caregiverMentioned && !relationMentioned && ambiguousCallHints.none { msgLower.contains(it) }) {
            return "none"
        }

        // Bước 2: Dùng AI (model nhỏ, không thinking) để phân loại các trường hợp mơ hồ
        val systemPrompt = """
Bạn là hệ thống phân loại ý định gọi điện. Nhiệm vụ: xác định xem câu nói có phải yêu cầu gọi điện/video call không.

ĐỐI TƯỢNG: Người cao tuổi nói chuyện với robot chăm sóc.
NGƯỜI THÂN: $caregiverRelation tên $caregiverName.

CÁC TRƯỜNG HỢP LÀ YÊU CẦU GỌI ĐIỆN:
- Nhắc đến hành động gọi, liên lạc, gặp mặt với người thân.
- Nhắc tên "$caregiverName" kèm mong muốn liên lạc/gặp.
- Ví dụ: "Tôi nhớ con quá", "Lâu rồi không thấy $caregiverName", "$caregiverRelation đâu rồi", "Muốn gặp $caregiverName", "Nhớ $caregiverName lắm".

CÁC TRƯỜNG HỢP KHÔNG PHẢI GỌI ĐIỆN:
- Hỏi thời tiết, giờ giấc, tin tức.
- Chào hỏi thông thường với robot.
- Kể chuyện về người thân (không yêu cầu gặp).

QUY TẮC NGHIÊM NGẶT:
1. Chỉ trả về JSON, KHÔNG có text nào khác.
2. Không giải thích, không markdown.

Định dạng duy nhất được phép:
{"intent": "call"}
hoặc
{"intent": "none"}
        """.trimIndent()

        val result = callGroqClassify(systemPrompt, userMessage)
        Log.d(TAG, "detectCallIntent: AI raw result=\"$result\" | msg=\"$userMessage\"")

        return try {
            val jsonStart = result.indexOf("{")
            val jsonEnd = result.lastIndexOf("}")
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonString = result.substring(jsonStart, jsonEnd + 1)
                val json = JSONObject(jsonString)
                val intent = json.optString("intent", "none").lowercase()
                Log.d(TAG, "detectCallIntent: parsed intent=\"$intent\"")
                if (intent.contains("call")) "call" else "none"
            } else {
                Log.w(TAG, "detectCallIntent: JSON parse failed, raw=\"$result\"")
                "none"
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectCallIntent: exception=\"${e.message}\"")
            "none"
        }
    }

    /**
     * Gọi Groq API trực tiếp với system prompt riêng biệt.
     * KHÔNG ảnh hưởng chat history chính.
     * Dùng cho các tác vụ phân loại, sinh text ngắn.
     */
    /**
     * Gọi Groq với mức reasoning thấp cho các tác vụ phân loại JSON ngắn.
     * Phần reasoning không được trả về để kết quả chỉ chứa nội dung cần phân tích.
     */
    private suspend fun callGroqClassify(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 64
    ): String {
        val messagesArray = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userMessage))
        }

        val requestBodyJson = JSONObject()
            .put("model", MODEL_CLASSIFY) // Model production, reasoning thấp
            .put("messages", messagesArray)
            .put("temperature", 0.0)      // Deterministic output
            .put("reasoning_effort", "low")
            .put("include_reasoning", false)
            .put("max_completion_tokens", maxTokens)
            .put("stream", false)

        val requestBody = requestBodyJson.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Groq classify API error: ${response.code} — ${response.body?.string()}")
                    return@withContext ""
                }
                val responseBody = response.body?.string() ?: return@withContext ""
                val json = JSONObject(responseBody)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    message?.optString("content", "")?.trim() ?: ""
                } else ""
            } catch (e: Exception) {
                Log.e(TAG, "Groq classify call error: ${e.message}", e)
                ""
            }
        }
    }

    /**
     * Gọi Groq API trực tiếp với system prompt riêng biệt.
     * KHÔNG ảnh hưởng chat history chính.
     * Dùng cho các tác vụ sinh text ngắn (reminder wrapper).
     */
    private suspend fun callGroqDedicated(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 200
    ): String {
        val messagesArray = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userMessage))
        }

        val requestBodyJson = JSONObject()
            .put("model", MODEL_CHAT)
            .put("messages", messagesArray)
            .put("temperature", 0.3)
            .put("reasoning_effort", "low")
            .put("include_reasoning", false)
            .put("max_completion_tokens", maxTokens)
            .put("stream", false)

        val requestBody = requestBodyJson.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Groq dedicated API error: ${response.code}")
                    return@withContext ""
                }
                val responseBody = response.body?.string() ?: return@withContext ""
                val json = JSONObject(responseBody)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    val raw = message?.optString("content", "") ?: ""
                    // Lọc <think>...</think> blocks (Qwen / DeepSeek reasoning)
                    raw.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()
                } else ""
            } catch (e: Exception) {
                Log.e(TAG, "Groq dedicated call error: ${e.message}", e)
                ""
            }
        }
    }
}
