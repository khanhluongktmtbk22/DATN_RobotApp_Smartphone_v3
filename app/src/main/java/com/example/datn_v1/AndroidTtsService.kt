package com.example.datn_v1

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Service tích hợp TextToSpeech mặc định của Android.
 * Thay thế cho EdgeTtsService do API của Microsoft bị chặn (403 Forbidden).
 */
class AndroidTtsService(private val context: Context) {

    companion object {
        private const val TAG = "AndroidTtsService"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var activeUtteranceId: String? = null

    // Callbacks
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingFinished: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Ưu tiên tiếng Việt
                val result = tts?.setLanguage(Locale.Builder().setLanguage("vi").setRegion("VN").build())
                isReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
                
                if (!isReady) {
                    // Fallback tiếng Anh nếu chưa cài tiếng Việt
                    tts?.setLanguage(Locale.US)
                    isReady = true
                    Log.w(TAG, "Tiếng Việt TTS chưa được cài đặt — dùng tiếng Anh làm fallback")
                }
                
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        handler.post {
                            if (utteranceId == activeUtteranceId && utteranceId != null) {
                                onSpeakingStarted?.invoke()
                            }
                        }
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            if (utteranceId == activeUtteranceId && utteranceId != null) {
                                activeUtteranceId = null
                                onSpeakingFinished?.invoke()
                            }
                        }
                    }
                    
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post {
                            if (utteranceId == activeUtteranceId && utteranceId != null) {
                                activeUtteranceId = null
                                onError?.invoke("Lỗi Android TTS")
                            }
                        }
                    }
                })
                
                Log.d(TAG, "Android TTS đã sẵn sàng")
            } else {
                Log.e(TAG, "Không thể khởi tạo Android TTS")
                onError?.invoke("Không thể khởi tạo TTS")
            }
        }
    }

    /**
     * Phát văn bản bằng Android TTS.
     *
     * @param text Văn bản cần đọc
     * @param voiceGender Tham số giữ lại để tương thích interface, Android TTS mặc định sẽ lấy giọng hệ thống.
     */
    fun speak(text: String, voiceGender: String = "female") {
        if (text.isBlank()) return

        if (!isReady) {
            Log.e(TAG, "TTS chưa sẵn sàng")
            onError?.invoke("TTS chưa sẵn sàng")
            return
        }

        Log.d(TAG, "Đang đọc: \"$text\"")
        val utteranceId = UUID.randomUUID().toString()
        activeUtteranceId = utteranceId
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        
        // Dùng QUEUE_FLUSH để ngắt câu cũ đọc câu mới ngay lập tức
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId) == TextToSpeech.ERROR) {
            activeUtteranceId = null
            onError?.invoke("Không thể phát lời nói")
        }
    }

    /** Dừng audio đang phát */
    fun stopSpeaking() {
        activeUtteranceId = null
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
    }

    /** Giải phóng tài nguyên khi Fragment bị destroy */
    fun release() {
        stopSpeaking()
        tts?.shutdown()
        tts = null
    }
}
