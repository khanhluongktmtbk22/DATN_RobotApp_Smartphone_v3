package com.example.datn_v1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Wrapper cho Android SpeechRecognizer (Google STT).
 *
 * Kiến trúc Xiaozhi-inspired:
 * - Continuous listening: tự động restart sau khi nhận kết quả cuối
 * - Partial results: hiển thị text realtime khi người dùng đang nói
 * - Wake word detection: phát hiện tên robot từ partial results
 * - Auto-restart: khi bị lỗi do silence/timeout, tự khởi động lại (nếu cần)
 */
class SpeechRecognizerManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "SpeechRecognizerMgr"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sessionId = 0L

    // Callbacks từ Fragment
    var onPartialResult: ((String) -> Unit)? = null   // Text đang nhận (realtime)
    var onFinalResult: ((String) -> Unit)? = null     // Text hoàn chỉnh
    var onError: ((Int) -> Unit)? = null              // Mã lỗi Android SpeechRecognizer
    var onReadyForSpeech: (() -> Unit)? = null        // Mic đã sẵn sàng nhận âm
    var onBeginningOfSpeech: (() -> Unit)? = null
    var onEndOfSpeech: (() -> Unit)? = null           // Người dùng ngừng nói

    // Trạng thái nội bộ
    private var isListening = false
    private var shouldContinue = false // Flag để auto-restart

    // ── Khởi tạo ─────────────────────────────────────────────────────────────

    fun init() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition không khả dụng trên thiết bị này")
            return
        }
        Log.d(TAG, "SpeechRecognizer khởi tạo thành công")
    }

    // ── Control ───────────────────────────────────────────────────────────────

    /**
     * Bắt đầu lắng nghe.
     * @param continuous Nếu true, sẽ auto-restart sau mỗi lần nhận kết quả
     */
    fun startListening(continuous: Boolean = false) {
        if (isListening) return
        handler.removeCallbacksAndMessages(null)
        val session = ++sessionId
        shouldContinue = continuous
        isListening = true

        val intent = buildRecognizerIntent()
        try {
            // Bind callbacks to this session; discard late results from cancelled sessions.
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener(session))
                startListening(intent)
            }
            Log.d(TAG, "Bắt đầu lắng nghe (continuous=$continuous)")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi startListening: ${e.message}")
            isListening = false
            onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** Dừng lắng nghe (kết thúc session hiện tại) */
    fun stopListening() {
        shouldContinue = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.stopListening()
        Log.d(TAG, "Dừng lắng nghe")
    }

    /** Hủy nhận dạng ngay lập tức */
    fun cancel() {
        ++sessionId
        shouldContinue = false
        isListening = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /** Giải phóng tài nguyên */
    fun destroy() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "SpeechRecognizer đã hủy")
    }

    // ── Intent builder ────────────────────────────────────────────────────────

    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")         // Tiếng Việt
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)     // Kết quả tạm thời
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Endpointing: sau khoảng lặng ngắn, recognizer chốt câu và trả onResults.
            // Đây là hint cho recognition service; một số engine/OEM có thể tự dùng ngưỡng riêng.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
        }
    }


    // ── RecognitionListener ───────────────────────────────────────────────────

    private fun recognitionListener(session: Long) = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            if (session != sessionId) return
            Log.d(TAG, "Mic sẵn sàng nhận âm")
            onReadyForSpeech?.invoke()
        }

        override fun onBeginningOfSpeech() {
            if (session != sessionId) return
            Log.d(TAG, "Phát hiện giọng nói bắt đầu")
            onBeginningOfSpeech?.invoke()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Có thể dùng để animate mic icon, bỏ qua để giảm overhead
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (session != sessionId) return
            Log.d(TAG, "Người dùng ngừng nói")
            // Wait for onResults/onError before allowing another recognition session.
            onEndOfSpeech?.invoke()
        }

        override fun onError(error: Int) {
            if (session != sessionId) return
            isListening = false
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO               -> "Lỗi audio"
                SpeechRecognizer.ERROR_CLIENT              -> "Lỗi client"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Thiếu quyền microphone"
                SpeechRecognizer.ERROR_NETWORK             -> "Lỗi mạng"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT     -> "Timeout mạng"
                SpeechRecognizer.ERROR_NO_MATCH            -> "Không nhận ra giọng nói"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY     -> "Nhận dạng đang bận"
                SpeechRecognizer.ERROR_SERVER              -> "Lỗi server STT"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT      -> "Không nghe thấy giọng nói"
                else -> "Lỗi không xác định ($error)"
            }
            Log.w(TAG, "STT lỗi: $errorMsg (code=$error)")

            // Auto-restart nếu lỗi nhỏ (timeout, no_match) và continuous mode
            if (shouldContinue && (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        || error == SpeechRecognizer.ERROR_CLIENT)) {
                Log.d(TAG, "Auto-restart STT sau lỗi nhỏ")
                handler.postDelayed({
                    if (session == sessionId && shouldContinue) startListening(continuous = true)
                }, 500)
            } else {
                onError?.invoke(error)
            }
        }

        override fun onResults(results: Bundle?) {
            if (session != sessionId) return
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                Log.d(TAG, "Kết quả STT cuối cùng: \"$text\"")
                onFinalResult?.invoke(text)
            } else if (!shouldContinue) {
                onError?.invoke(SpeechRecognizer.ERROR_NO_MATCH)
            }

            // Auto-restart nếu ở chế độ continuous
            if (session == sessionId && shouldContinue) {
                handler.postDelayed({
                    if (session == sessionId && shouldContinue) startListening(continuous = true)
                }, 300)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (session != sessionId) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: return
            if (text.isNotBlank()) {
                Log.d(TAG, "Partial STT: \"$text\"")
                onPartialResult?.invoke(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
