package com.example.datn_v1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.datn_v1.databinding.ChatbotFragmentBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * ChatbotFragment — Màn hình chatbot chính.
 *
 * Kiến trúc Xiaozhi-inspired:
 *  1. Khởi động → load profile Firebase → init các service
 *  2. Continuous STT listening với wake word detection
 *  3. Khi nghe wake word → chuyển LISTENING mode
 *  4. Nhận câu hỏi → gửi Groq (streaming) → TTS từng câu ngay lập tức
 *  5. Hiển thị biểu cảm khuôn mặt theo từng trạng thái
 *
 * State machine:
 *   SLEEPING → WAKEUP (greeting) → LISTENING → THINKING → SPEAKING → LISTENING
 *   Silence or an end-conversation command: LISTENING → SLEEPING.
 *
 * LLM đang sử dụng trong luồng runtime: Groq API.
 */
class ChatbotFragment : Fragment(R.layout.chatbot_fragment) {

    companion object {
        private const val TAG = "ChatbotFragment"
        private const val REQUEST_MIC_PERMISSION = 1001

        // THAY KEY NÀY BẰNG KEY THẬT CỦA BẠN
        private val GROQ_API_KEY get() = BuildConfig.GROQ_API_KEY

        // Timeout: sau bao lâu không có câu hỏi thì trở về SLEEPING
        private const val IDLE_TIMEOUT_MS = ChatbotSpeechPolicy.SILENCE_TIMEOUT_MS

        // Khoảng cách thời gian tối thiểu giữa các câu TTS để tránh nhiễu
        private const val TTS_SENTENCE_DELAY_MS = 80L
    }

    // ── ViewBinding ───────────────────────────────────────────────────────────
    private var _binding: ChatbotFragmentBinding? = null

    // ── Services ──────────────────────────────────────────────────────────────
    private var groqService: GroqChatService? = null
    private var ttsService: AndroidTtsService? = null
    private var sttManager: SpeechRecognizerManager? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var currentState: ChatbotState = ChatbotState.INITIALIZING
    private var userProfile: UserProfile = UserProfile()
    private var groqJob: Job? = null
    private var callIntentJob: Job? = null
    private var isResponseStreaming = false
    private var stateGeneration = 0L

    // ── Permission Launcher ───────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            initServicesAndLoad()
        } else {
            Toast.makeText(requireContext(),
                "Cần quyền microphone để chatbot hoạt động", Toast.LENGTH_LONG).show()
            updateStatusText("Cần quyền microphone")
        }
    }

    // ── Timers & Handlers ─────────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleTimeoutRunnable = Runnable {
        if (currentState == ChatbotState.LISTENING) transitionTo(ChatbotState.SLEEPING)
    }
    private val nextTtsRunnable = Runnable { playNextInQueue() }
    private var pendingReminderConfirmListening = false
    private var afterSpeechState: ChatbotState? = null

    // ── Reminder flow state ───────────────────────────────────────────────────
    private var currentReminderEvent: ReminderEvent? = null
    private var reminderRetryRunnable: Runnable? = null
    /** Retry delays: 2 min, 5 min, 10 min */
    private val retryDelaysMs: List<Long> = listOf(2 * 60_000L, 5 * 60_000L, 10 * 60_000L)
    private val reminderConfirmTimeoutRunnable = Runnable { onReminderConfirmTimeout() }

    // ── Fall confirmation state ───────────────────────────────────────────────
    private var currentFallAlertId: String? = null
    private var currentFallEvent: FallEvent? = null
    private var pendingFallConfirmListening = false
    private val fallConfirmTimeoutRunnable = Runnable { onFallConfirmTimeout() }

    // ── Video call navigation flag ────────────────────────────────────────────
    // Được set true trước khi TTS xác nhận gọi điện.
    // Khi TTS đọc xong → onTtsFinished() kiểm tra flag này để navigate.
    private var pendingVideoCallNavigation = false

    // ── Streaming TTS buffer ──────────────────────────────────────────────────
    // Buffer tích lũy text từ Groq streaming, cắt câu khi đủ
    private val ttsBuffer = StringBuilder()
    private val sentenceSeparators = Regex("[.!?。！？\n]")
    private var fullResponseBuilder = StringBuilder()

    // ── Firebase ──────────────────────────────────────────────────────────────
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseDatabase.getInstance().getReference("Users") }

    // ════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = ChatbotFragmentBinding.bind(view)

        checkMicPermissionAndInit()
        observeReminderEvents()
        observeFallEvents()
    }

    /** Observe LiveData từ ReminderService */
    private fun observeReminderEvents() {
        ReminderService.reminderEvent.observe(viewLifecycleOwner) { event ->
            event ?: return@observe
            ReminderService.reminderEvent.postValue(null) // consume
            triggerReminderInterrupt(event)
        }
    }

    /** Observe LiveData từ TrackingService (phát hiện té ngã) */
    private fun observeFallEvents() {
        TrackingService.fallEvent.observe(viewLifecycleOwner) { event ->
            event ?: return@observe
            TrackingService.fallEvent.postValue(null) // consume
            // Chỉ xử lý nếu không đang trong cuộc gọi
            if (currentState != ChatbotState.IN_CALL) {
                triggerFallConfirmation(event)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacksAndMessages(null)
        reminderRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        groqJob?.cancel()
        callIntentJob?.cancel()
        sttManager?.destroy()
        ttsService?.release()
        _binding = null
        Log.d(TAG, "ChatbotFragment destroyed")
    }

    // ════════════════════════════════════════════════════════════════════════
    // PERMISSION
    // ════════════════════════════════════════════════════════════════════════

    private fun checkMicPermissionAndInit() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            initServicesAndLoad()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // KHỞI TẠO
    // ════════════════════════════════════════════════════════════════════════

    private fun initServicesAndLoad() {
        // Hiển thị loading
        updateStatusText("Đang tải hồ sơ...")

        // Load profile từ Firebase rồi mới khởi tạo Groq
        loadUserProfileFromFirebase { profile ->
            userProfile = profile
            initAllServices(profile)
            startSleepingMode()
        }
    }

    private fun loadUserProfileFromFirebase(onComplete: (UserProfile) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "Chưa đăng nhập — dùng profile mặc định")
            onComplete(UserProfile())
            return
        }

        val prefs = requireContext().getSharedPreferences("RobotProfileCache", android.content.Context.MODE_PRIVATE)
        val cachedRobotName = prefs.getString("robotName_$uid", null)

        // 1. Nếu có cache local, gọi onComplete NGAY LẬP TỨC để chatbot không bị kẹt
        if (cachedRobotName != null) {
            val localProfile = UserProfile(
                fullName         = prefs.getString("fullName_$uid", "") ?: "",
                email            = prefs.getString("email_$uid", "") ?: "",
                robotName        = cachedRobotName,
                robotPronoun     = prefs.getString("robotPronoun_$uid", "tôi") ?: "tôi",
                elderlyPronoun   = prefs.getString("elderlyPronoun_$uid", "Bạn") ?: "Bạn",
                emergencyPhone   = prefs.getString("emergencyPhone_$uid", "") ?: "",
                caregiverName    = prefs.getString("caregiverName_$uid", "") ?: "",
                caregiverRelation= prefs.getString("caregiverRelation_$uid", "") ?: "",
                medicalConditions= prefs.getString("medicalConditions_$uid", "") ?: "",
                specialNotes     = prefs.getString("specialNotes_$uid", "") ?: "",
                hobbies          = prefs.getString("hobbies_$uid", "") ?: ""
            )
            Log.d(TAG, "Đã load profile từ local cache: robotName=${localProfile.robotName}")
            _binding?.tvRobotName?.text = localProfile.robotName.uppercase()
            onComplete(localProfile)
        }

        // 2. Timeout 8 giây cho Firebase (chỉ áp dụng nếu chưa có cache)
        var completed = cachedRobotName != null
        val timeoutRunnable = Runnable {
            if (!completed) {
                completed = true
                Log.w(TAG, "Firebase load timeout — khởi động với profile mặc định")
                if (isAdded) onComplete(UserProfile())
            }
        }
        if (!completed) {
            mainHandler.postDelayed(timeoutRunnable, 8_000L)
        }

        database.child(uid).get()
            .addOnSuccessListener { snapshot ->
                try {
                    val profile = UserProfile(
                        fullName         = snapshot.child("fullName").getValue(String::class.java) ?: "",
                        email            = snapshot.child("email").getValue(String::class.java) ?: "",
                        robotName        = snapshot.child("robotName").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: "Robot",
                        robotPronoun     = snapshot.child("robotPronoun").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: "tôi",
                        elderlyPronoun   = snapshot.child("elderlyPronoun").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: "Bạn",
                        emergencyPhone   = snapshot.child("emergencyPhone").getValue(String::class.java) ?: "",
                        caregiverName    = snapshot.child("caregiverName").getValue(String::class.java) ?: "",
                        caregiverRelation= snapshot.child("caregiverRelation").getValue(String::class.java) ?: "",
                        medicalConditions= snapshot.child("medicalConditions").getValue(String::class.java) ?: "",
                        specialNotes     = snapshot.child("specialNotes").getValue(String::class.java) ?: "",
                        hobbies          = snapshot.child("hobbies").getValue(String::class.java) ?: ""
                    )

                    // Lưu xuống SharedPreferences
                    prefs.edit().apply {
                        putString("fullName_$uid", profile.fullName)
                        putString("email_$uid", profile.email)
                        putString("robotName_$uid", profile.robotName)
                        putString("robotPronoun_$uid", profile.robotPronoun)
                        putString("elderlyPronoun_$uid", profile.elderlyPronoun)
                        putString("emergencyPhone_$uid", profile.emergencyPhone)
                        putString("caregiverName_$uid", profile.caregiverName)
                        putString("caregiverRelation_$uid", profile.caregiverRelation)
                        putString("medicalConditions_$uid", profile.medicalConditions)
                        putString("specialNotes_$uid", profile.specialNotes)
                        putString("hobbies_$uid", profile.hobbies)
                        apply()
                    }

                    if (!completed) {
                        completed = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        Log.d(TAG, "Đã load profile từ Firebase: robotName=${profile.robotName}")
                        _binding?.tvRobotName?.text = profile.robotName.uppercase()
                        onComplete(profile)
                    } else {
                        // Cập nhật ngầm
                        userProfile = profile
                        groqService?.updateProfileAndReset(profile)
                        _binding?.tvRobotName?.text = profile.robotName.uppercase()
                        Log.d(TAG, "Đã đồng bộ ngầm profile từ Firebase")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi parse profile: ${e.message}")
                    if (!completed) {
                        completed = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        onComplete(UserProfile())
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Không load được profile từ Firebase: ${e.message}")
                if (!completed) {
                    completed = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Không thể tải hồ sơ", Toast.LENGTH_SHORT).show()
                    }
                    onComplete(UserProfile())
                }
            }
    }

    private fun initAllServices(profile: UserProfile) {
        // 1. Groq
        if (GROQ_API_KEY == "YOUR_GROQ_API_KEY" || GROQ_API_KEY.isBlank()) {
            Log.e(TAG, "Chưa cài Groq API key! Chatbot sẽ không trả lời được.")
            updateStatusText("Chưa cài API key Groq")
        } else {
            groqService = GroqChatService(GROQ_API_KEY, profile)
        }

        // 2. Android TTS
        ttsService = AndroidTtsService(requireContext()).apply {
            onSpeakingStarted  = { /* face animation handled in state */ }
            onSpeakingFinished = {
                if (ttsQueue.isEmpty()) {
                    isTtsBusy = false
                    onTtsFinished()
                } else {
                    mainHandler.postDelayed(nextTtsRunnable, TTS_SENTENCE_DELAY_MS)
                }
            }
            onError            = { msg ->
                Log.e(TAG, "TTS lỗi: $msg")
                ttsQueue.clear()
                isTtsBusy = false
                onTtsFinished()
            }
        }

        // 3. STT
        sttManager = SpeechRecognizerManager(requireContext()).apply {
            // SpeechRecognizer delivers callbacks on the main thread. Handle them before
            // deciding whether its wake-word session should automatically restart.
            onReadyForSpeech   = { onMicReady() }
            onBeginningOfSpeech = { resetConversationTimeout() }
            onEndOfSpeech      = { onSpeechEnd() }
            onPartialResult    = { text -> onPartialStt(text) }
            onFinalResult      = { text -> onFinalStt(text) }
            onError            = { code -> onSttError(code) }
            init()
        }

        Log.d(TAG, "Tất cả services đã khởi tạo")
    }

    // ════════════════════════════════════════════════════════════════════════
    // STATE MACHINE
    // ════════════════════════════════════════════════════════════════════════

    private fun transitionTo(newState: ChatbotState) {
        if (currentState == newState) return
        ++stateGeneration
        Log.d(TAG, "State: $currentState → $newState")
        currentState = newState
        updateUIForState(newState)
    }

    private fun postForCurrentState(delayMs: Long, action: () -> Unit) {
        val generation = stateGeneration
        mainHandler.postDelayed({
            if (_binding != null && generation == stateGeneration) action()
        }, delayMs)
    }

    private fun resetConversationTimeout() {
        if (currentState != ChatbotState.LISTENING) return
        mainHandler.removeCallbacks(idleTimeoutRunnable)
        mainHandler.postDelayed(idleTimeoutRunnable, IDLE_TIMEOUT_MS)
    }

    private fun cancelChatPlayback() {
        groqJob?.cancel()
        callIntentJob?.cancel()
        isResponseStreaming = false
        ttsBuffer.clear()
        ttsQueue.clear()
        isTtsBusy = false
        pendingReminderConfirmListening = false
        pendingFallConfirmListening = false
        pendingVideoCallNavigation = false
        afterSpeechState = null
        mainHandler.removeCallbacks(nextTtsRunnable)
        mainHandler.removeCallbacks(reminderConfirmTimeoutRunnable)
        mainHandler.removeCallbacks(fallConfirmTimeoutRunnable)
        ttsService?.stopSpeaking()
        sttManager?.cancel()
    }

    private fun updateUIForState(state: ChatbotState) {
        val b = _binding ?: return

        // Dừng animation cũ và hủy idle timeout mỗi khi đổi state
        stopAnimations()
        mainHandler.removeCallbacks(idleTimeoutRunnable)


        when (state) {
            ChatbotState.INITIALIZING -> {
                // UI đã hiện "Đang tải hồ sơ..." từ trước, không cần làm gì
            }
            ChatbotState.SLEEPING -> {
                cancelChatPlayback()
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_idle)
                updateStatusText("Đang chờ... Gọi tên ${userProfile.robotName}")
                b.tvSttRealtime.visibility = View.INVISIBLE
                postForCurrentState(150L) {
                    sttManager?.startListening(continuous = true)
                }
            }

            ChatbotState.WAKEUP -> {
                // Mỗi lần đánh thức bắt đầu một phiên mới; các lượt LISTENING tiếp
                // theo trong phiên vẫn giữ ngữ cảnh cho đến khi robot trở về ngủ.
                cancelChatPlayback()
                groqService?.resetHistory()
                fullResponseBuilder.clear()
                b.tvSttRealtime.text = ""
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_listening)
                val greeting = ChatbotSpeechPolicy.wakeGreeting(userProfile)
                updateStatusText(greeting)
                animateFaceWakeup()
                speakText(greeting)
            }

            ChatbotState.LISTENING -> {
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_listening)
                updateStatusText("Đang lắng nghe...")
                b.tvSttRealtime.visibility = View.VISIBLE
                b.tvSttRealtime.text = "..."
                sttManager?.cancel()
                sttManager?.startListening(continuous = false)
                resetConversationTimeout()
            }

            ChatbotState.THINKING -> {
                sttManager?.cancel()
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_thinking)
                updateStatusText("Đang suy nghĩ...")
                b.tvSttRealtime.visibility = View.VISIBLE
                animateThinking()
            }

            ChatbotState.SPEAKING -> {
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_speaking)
                updateStatusText("Đang nói...")
                b.tvSttRealtime.visibility = View.VISIBLE
            }

            ChatbotState.IDLE -> {
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_idle)
                updateStatusText("${userProfile.elderlyPronoun} còn muốn hỏi gì không ạ?")
                b.tvSttRealtime.visibility = View.VISIBLE
                postForCurrentState(0L) { transitionTo(ChatbotState.LISTENING) }
            }

            ChatbotState.REMINDING -> {
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_speaking)
                updateStatusText("Đang nhắc nhở...")
                b.tvSttRealtime.visibility = View.VISIBLE
            }

            ChatbotState.FALL_CONFIRMING -> {
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_listening)
                updateStatusText("Xác nhận an toàn...")
                b.tvSttRealtime.visibility = View.VISIBLE
                b.tvSttRealtime.text = "..."
            }

            ChatbotState.IN_CALL -> {
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_idle)
                updateStatusText("Đang trong cuộc gọi...")
                b.tvSttRealtime.visibility = View.INVISIBLE
            }

            ChatbotState.ERROR -> {
                b.ivChatbotFace.setImageResource(R.drawable.chatbot_face_idle)
                updateStatusText("Đã xảy ra lỗi")
                if (!isTtsBusy) postForCurrentState(0L) { transitionTo(ChatbotState.SLEEPING) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SLEEPING MODE (Wake Word Detection)
    // ════════════════════════════════════════════════════════════════════════

    private fun startSleepingMode() {
        transitionTo(ChatbotState.SLEEPING)
    }

    // ════════════════════════════════════════════════════════════════════════
    // STT CALLBACKS
    // ════════════════════════════════════════════════════════════════════════

    private fun onMicReady() {
        Log.d(TAG, "Mic sẵn sàng, state=$currentState")
        resetConversationTimeout()
    }

    private fun onSpeechEnd() {
        Log.d(TAG, "Người dùng ngừng nói, state=$currentState")
        if (currentState == ChatbotState.LISTENING) {
            updateStatusText("Đang xử lý...")
        }
    }

    /**
     * Xử lý partial result (text realtime khi đang nói).
     * Trong SLEEPING mode: kiểm tra wake word.
     * Trong LISTENING mode: hiển thị text.
     */
    private fun onPartialStt(partialText: String) {
        when (currentState) {
            ChatbotState.SLEEPING -> {
                // Kiểm tra wake word (tên robot, không phân biệt hoa thường)
                val robotNameLower = userProfile.robotName.lowercase().trim()
                if (partialText.lowercase().contains(robotNameLower) && robotNameLower.length >= 2) {
                    Log.d(TAG, "Wake word phát hiện: \"$partialText\"")
                    sttManager?.cancel()
                    transitionTo(ChatbotState.WAKEUP)
                }
            }
            ChatbotState.LISTENING -> {
                resetConversationTimeout()
                _binding?.tvSttRealtime?.text = "\"$partialText\""
            }
            else -> { /* Bỏ qua partial trong các state khác */ }
        }
    }

    /**
     * Xử lý kết quả STT cuối cùng.
     */
    private fun onFinalStt(finalText: String) {
        when (currentState) {
            ChatbotState.SLEEPING -> {
                val robotNameLower = userProfile.robotName.lowercase().trim()
                if (finalText.lowercase().contains(robotNameLower) && robotNameLower.length >= 2) {
                    transitionTo(ChatbotState.WAKEUP)
                }
            }
            ChatbotState.LISTENING -> {
                _binding?.tvSttRealtime?.text = "\"$finalText\""
                if (ChatbotSpeechPolicy.isEndConversation(finalText, userProfile.robotName)) {
                    transitionTo(ChatbotState.SLEEPING)
                    return
                }
                // Kiểm tra ý định gọi điện TRƯỚC khi gử chat
                checkCallIntentAndProceed(finalText)
            }
            ChatbotState.FALL_CONFIRMING -> {
                // Người già đã trả lời xác nhận té ngã
                mainHandler.removeCallbacks(fallConfirmTimeoutRunnable)
                sttManager?.cancel()
                onFallConfirmResponse(finalText)
            }
            ChatbotState.REMINDING -> {
                // Người già xác nhận đã nghe lời nhắc
                mainHandler.removeCallbacks(reminderConfirmTimeoutRunnable)
                sttManager?.cancel()
                onReminderConfirmResponse(finalText)
            }
            else -> {
                Log.d(TAG, "Final STT bo qua (state=$currentState): \"$finalText\"")
            }
        }
    }

    private fun onSttError(errorCode: Int) {
        Log.w(TAG, "STT error=$errorCode, state=$currentState")
        if (currentState == ChatbotState.LISTENING) {
            // Silence ends this conversation; wake-word monitoring remains active.
            if (errorCode == android.speech.SpeechRecognizer.ERROR_NO_MATCH
                || errorCode == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                transitionTo(ChatbotState.SLEEPING)
            }
        }
        // Lỗi khác khi SLEEPING sẽ được SpeechRecognizerManager tự xử lý restart
    }

    // ════════════════════════════════════════════════════════════════════════
    // GROQ + TTS PIPELINE (Xiaozhi-inspired streaming)
    // ════════════════════════════════════════════════════════════════════════

    // ═════════════════════════════════════════════════════════════════════════
    // VOICE CALL INTENT (Phase 7)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Kiểm tra ý định gọi điện trước khi gử tới Groq chat.
     * Nếu phát hiện ý định → khởi tạo cuộc gọi, nếu không → gử chat bình thường.
     */
    private fun checkCallIntentAndProceed(userMessage: String) {
        // Hiện text của user ngay lập tức và chuyển sang trạng thái suy nghĩ
        addMessageToLog(isUser = true, text = userMessage)
        transitionTo(ChatbotState.THINKING)

        val groq = groqService
        if (groq == null || userProfile.caregiverName.isBlank()) {
            // Không có thông tin người chăm sóc → chat bình thường
            sendToGroq(userMessage)
            return
        }
        callIntentJob = viewLifecycleOwner.lifecycleScope.launch {
            val intent = try {
                groq.detectCallIntent(userMessage, userProfile.caregiverName, userProfile.caregiverRelation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "detectCallIntent lỗi: ${e.message}")
                "none"
            }
            withContext(Dispatchers.Main) {
                if (!isAdded || currentState != ChatbotState.THINKING) return@withContext
                if (intent == "call") {
                    initiateVoiceCall()
                } else {
                    sendToGroq(userMessage)
                }
            }
        }
    }

    /**
     * Khởi tạo cuộc gọi từ chatbot: TTS xác nhận → chuyển sang VideoCallFragment.
     */
    private fun initiateVoiceCall() {
        val caregiverName = userProfile.caregiverName
        // Chỉ dùng tên, bỏ quan hệ để câu nghe tự nhiên hơn:
        // "gọi cho Khang" thay vì "gọi cho Con Khang"
        val confirmMsg = "Vâng, ${userProfile.robotPronoun} đang kết nối cuộc gọi với $caregiverName cho ${userProfile.elderlyPronoun} nhé."
        transitionTo(ChatbotState.IN_CALL)
        // Đánh dấu cần chuyển sang VideoCallFragment SAU KHI TTS đọc xong
        pendingVideoCallNavigation = true
        speakText(confirmMsg)
        // onTtsFinished() sẽ kiểm tra pendingVideoCallNavigation và navigate khi TTS hoàn thành
    }

    /** Được gọi từ MainActivity khi cuộc gọi kết thúc/nhỡ/timeout */
    fun onReturnFromCall() {
        transitionTo(ChatbotState.SLEEPING)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // REMINDER FLOW (Phase 5)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Interrupt chatbot và phát lời nhắc.
     * Được gọi từ LiveData observer khi ReminderService trigger.
     */
    private fun triggerReminderInterrupt(event: ReminderEvent) {
        // Hủy retry cũ nếu có
        reminderRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        currentReminderEvent = event

        // Dừng tất cả hoạt động hiện tại
        cancelChatPlayback()
        mainHandler.removeCallbacks(idleTimeoutRunnable)

        transitionTo(ChatbotState.REMINDING)

        val reminderProfile = userProfile.copy(
            caregiverName = event.caregiverName.ifBlank { userProfile.caregiverName },
            caregiverRelation = event.caregiverRelation.ifBlank { userProfile.caregiverRelation }
        )
        val introduction = ChatbotSpeechPolicy.reminderIntroduction(reminderProfile)
        val content = event.note.ifBlank { event.title }
        val fullMessage = "$introduction $content"
        addMessageToLog(isUser = false, text = fullMessage)
        pendingReminderConfirmListening = true
        speakText(fullMessage)
    }

    private fun startReminderConfirmListening() {
        updateStatusText("${userProfile.elderlyPronoun} đã nghe chưa ạ?")
        sttManager?.cancel()
        sttManager?.startListening(continuous = false)
        // Timeout 10 giây
        mainHandler.postDelayed(reminderConfirmTimeoutRunnable, 10_000L)
    }

    private fun onReminderConfirmResponse(response: String) {
        val logId = currentReminderEvent?.logId ?: return
        val uid   = auth.currentUser?.uid ?: return

        // Phân loại: có tích cực không (vang/ok/biet/roi/xac nhan...)
        val positiveKeywords = listOf("vang", "vâng", "ok", "roi", "rồi", "biet", "biết", "duoc", "được", "da nghe", "đã nghe", "xac nhan", "xác nhận", "co", "có", "da", "dạ")
        val isConfirmed = positiveKeywords.any { response.lowercase().contains(it) }

        val ref = FirebaseDatabase.getInstance().getReference("reminder_logs/$uid/$logId")
        if (isConfirmed) {
            ref.updateChildren(mapOf(
                "status"          to "confirmed",
                "elderlyResponse" to response,
                "confirmedAt"     to System.currentTimeMillis()
            ))
            val replyMsg = "Dạ, ${userProfile.robotPronoun} đã ghi nhận rồi ạ."
            currentReminderEvent = null
            afterSpeechState = ChatbotState.SLEEPING
            speakText(replyMsg)
        } else {
            scheduleReminderRetry(retryCount = 0)
        }
    }

    private fun onReminderConfirmTimeout() {
        scheduleReminderRetry(retryCount = 0)
    }

    private fun scheduleReminderRetry(retryCount: Int) {
        val event = currentReminderEvent ?: return
        val uid   = auth.currentUser?.uid ?: return

        if (retryCount >= retryDelaysMs.size) {
            // Hết số lần retry → ghi log failed
            FirebaseDatabase.getInstance()
                .getReference("reminder_logs/$uid/${event.logId}")
                .updateChildren(mapOf("status" to "failed", "retryCount" to retryCount))
            currentReminderEvent = null
            transitionTo(ChatbotState.SLEEPING)
            return
        }

        val delayMs = retryDelaysMs[retryCount]
        Log.d(TAG, "Lên lịch retry lần ${retryCount + 1} sau ${delayMs / 60000} phút")

        FirebaseDatabase.getInstance()
            .getReference("reminder_logs/$uid/${event.logId}")
            .updateChildren(mapOf("status" to "retrying", "retryCount" to (retryCount + 1)))

        transitionTo(ChatbotState.SLEEPING)

        val retryRunnable = object : Runnable {
            override fun run() {
                reminderRetryRunnable = null
                triggerReminderInterrupt(event)
            }
        }
        reminderRetryRunnable = retryRunnable
        mainHandler.postDelayed(retryRunnable, delayMs)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FALL CONFIRMATION FLOW (Phase 6)
    // ═════════════════════════════════════════════════════════════════════════

    private fun triggerFallConfirmation(event: FallEvent) {
        Log.d(TAG, "Té ngã phát hiện: state=${event.state}, prob=${event.probFall}")

        // Tạo alert trên Firebase ngay lập tức
        // Reserve a key only. Caregiver is notified after danger/no-response is confirmed.
        val uid = auth.currentUser?.uid ?: return
        currentFallAlertId = FirebaseDatabase.getInstance()
            .getReference(FirebasePairPaths.fallAlerts(uid)).push().key
        currentFallEvent = event

        // Ngắt chatbot hiện tại
        cancelChatPlayback()
        mainHandler.removeCallbacksAndMessages(null)

        transitionTo(ChatbotState.FALL_CONFIRMING)

        val question = "${userProfile.elderlyPronoun} ơi, ${userProfile.robotPronoun} thấy ${userProfile.elderlyPronoun} vừa bị ngã, ${userProfile.elderlyPronoun} có sao không ạ?"
        pendingFallConfirmListening = true
        speakText(question)
    }

    private fun onFallConfirmResponse(response: String) {
        val alertId = currentFallAlertId ?: return
        val event = currentFallEvent ?: return
        val groq    = groqService
        pendingFallConfirmListening = false

        viewLifecycleOwner.lifecycleScope.launch {
            val classification = try {
                groq?.classifyFallResponse(response) ?: 1 // Default danger nếu không có Groq
            } catch (e: Exception) {
                Log.w(TAG, "classifyFallResponse lỗi: ${e.message}")
                1 // Default danger khi lỗi
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val now = System.currentTimeMillis()
                if (classification == 1) {
                    // Nguy hiểm — cập nhật Firebase và thông báo
                    writeConfirmedFallAlert(
                        alertId = alertId,
                        event = event,
                        confirmationStatus = "confirmed_danger",
                        severity = "high",
                        confirmedAt = now,
                        elderlyResponse = response,
                        llmClassification = 1
                    )
                    TrackingService.reportFallResolution(event.trackId, FallResolutionStatus.DANGER)
                    val replyMsg = "${userProfile.robotPronoun} đang gọi người nhà cho ${userProfile.elderlyPronoun} rồi ạ, ${userProfile.elderlyPronoun} hãy cố gắng nhé."
                    transitionTo(ChatbotState.IN_CALL)
                    pendingVideoCallNavigation = true
                    speakText(replyMsg)
                } else {
                    // An toàn
                    TrackingService.reportFallResolution(event.trackId, FallResolutionStatus.SAFE)
                    val replyMsg = "Vâng, ${userProfile.elderlyPronoun} cẩn thận nhé."
                    afterSpeechState = ChatbotState.LISTENING
                    speakText(replyMsg)
                }
                currentFallAlertId = null
                currentFallEvent = null
            }
        }
    }

    private fun onFallConfirmTimeout() {
        val alertId = currentFallAlertId ?: return
        val event = currentFallEvent ?: return
        pendingFallConfirmListening = false
        Log.w(TAG, "Fall confirm timeout — không có phản hồi")
        writeConfirmedFallAlert(
            alertId = alertId,
            event = event,
            confirmationStatus = "no_response",
            severity = "critical",
            confirmedAt = System.currentTimeMillis()
        )
        TrackingService.reportFallResolution(event.trackId, FallResolutionStatus.DANGER)
        val warnMsg = "${userProfile.elderlyPronoun} không trả lời, ${userProfile.robotPronoun} đang cảnh báo người nhà!"
        transitionTo(ChatbotState.IN_CALL)
        pendingVideoCallNavigation = true
        speakText(warnMsg)
        currentFallAlertId = null
        currentFallEvent = null
    }

    private fun writeConfirmedFallAlert(
        alertId: String,
        event: FallEvent,
        confirmationStatus: String,
        severity: String,
        confirmedAt: Long,
        elderlyResponse: String? = null,
        llmClassification: Int? = null
    ) {
        val data = mutableMapOf<String, Any>(
            "timestamp" to event.timestamp,
            "state" to event.state,
            "trackId" to event.trackId,
            "probFall" to event.probFall,
            "verificationReason" to event.verificationReason,
            "visibleKeypoints" to event.visibleKeypoints,
            "bboxFullyInside" to event.bboxFullyInside,
            "confirmationStatus" to confirmationStatus,
            "severity" to severity,
            "confirmedAt" to confirmedAt
        )
        event.upperBodyYRatio?.let { data["upperBodyYRatio"] = it }
        elderlyResponse?.let { data["elderlyResponse"] = it }
        llmClassification?.let { data["llmClassification"] = it }
        val uid = auth.currentUser?.uid ?: return
        FirebaseDatabase.getInstance()
            .getReference(FirebasePairPaths.fallAlerts(uid))
            .child(alertId)
            .setValue(data)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GROQ + TTS PIPELINE
    // ═════════════════════════════════════════════════════════════════════════

    private fun sendToGroq(userMessage: String) {
        val groq = groqService ?: run {
            showNoApiKeyError()
            return
        }

        transitionTo(ChatbotState.THINKING)
        ttsBuffer.clear()
        fullResponseBuilder.clear()
        isResponseStreaming = true

        groqJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                var firstChunk = true
                var lastCleanedLength = 0

                groq.sendMessageStream(userMessage)
                    .catch { e ->
                        Log.e(TAG, "Lỗi Groq stream: ${e.message}")
                        withContext(Dispatchers.Main) {
                            handleGroqError()
                        }
                    }
                    .onCompletion { cause ->
                        if (cause != null) return@onCompletion
                        // Xử lý phần text còn lại trong buffer (câu cuối chưa có dấu kết thúc)
                        withContext(Dispatchers.Main) {
                            val remaining = ttsBuffer.toString().trim()
                            if (remaining.isNotBlank()) {
                                speakText(remaining)
                                ttsBuffer.clear()
                            }
                            // Cập nhật toàn bộ response (đã clean <think>) vào UI
                            val finalRaw = fullResponseBuilder.toString()
                            updateLastBotMessage(getCleanedStreamingText(finalRaw).trim())
                            isResponseStreaming = false
                            if (!isTtsBusy) onTtsFinished()
                        }
                    }
                    .collect { chunk ->
                        if (chunk.isBlank()) return@collect

                        withContext(Dispatchers.Main) {
                            fullResponseBuilder.append(chunk)
                            val fullRaw = fullResponseBuilder.toString()

                            // Chuyển SPEAKING khi có chunk đầu tiên
                            if (firstChunk) {
                                firstChunk = false
                                transitionTo(ChatbotState.SPEAKING)
                                // Thêm placeholder message cho bot
                                addMessageToLog(isUser = false, text = "...")
                            }

                            // Lọc bỏ thẻ <think> trong thời gian thực
                            val cleanedText = getCleanedStreamingText(fullRaw)
                            if (cleanedText.length > lastCleanedLength) {
                                val newText = cleanedText.substring(lastCleanedLength)
                                ttsBuffer.append(newText)
                                lastCleanedLength = cleanedText.length
                                processTtsBuffer()
                            }

                            // Cập nhật giao diện chat với text đã clean
                            updateLastBotMessage(cleanedText.trimStart())
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception trong Groq coroutine: ${e.message}")
                withContext(Dispatchers.Main) { handleGroqError() }
            }
        }
    }

    /**
     * Tìm câu hoàn chỉnh trong buffer và đẩy vào TTS ngay lập tức.
     * Đây là key optimization của Xiaozhi: không chờ Groq trả hết response.
     */
    private fun processTtsBuffer() {
        val text = ttsBuffer.toString()
        val lastSepIdx = findLastSentenceBoundary(text)
        if (lastSepIdx > 10) { // Chỉ TTS nếu câu đủ dài (>10 ký tự)
            val sentenceToSpeak = text.substring(0, lastSepIdx + 1).trim()
            val remaining = text.substring(lastSepIdx + 1)
            ttsBuffer.clear()
            ttsBuffer.append(remaining)
            if (sentenceToSpeak.isNotBlank()) {
                speakText(sentenceToSpeak)
            }
        }
    }

    private fun findLastSentenceBoundary(text: String): Int {
        var lastIdx = -1
        for (i in text.indices) {
            if (text[i] in listOf('.', '!', '?', '。', '！', '？', '\n')) {
                lastIdx = i
            }
        }
        return lastIdx
    }

    private val ttsQueue = ArrayDeque<String>()
    private var isTtsBusy = false

    /**
     * Thêm câu vào queue TTS, phát tuần tự (không chồng lên nhau)
     */
    private fun speakText(sentence: String) {
        if (sentence.isBlank()) return
        ttsQueue.addLast(sentence)
        if (!isTtsBusy) {
            playNextInQueue()
        }
    }

    private fun playNextInQueue() {
        if (ttsQueue.isEmpty()) {
            isTtsBusy = false
            onTtsFinished()
            return
        }
        isTtsBusy = true
        val sentence = ttsQueue.removeFirst()
        Log.d(TAG, "TTS phát: \"$sentence\"")
        ttsService?.speak(sentence)
    }

    private fun onTtsFinished() {
        if (isTtsBusy || ttsQueue.isNotEmpty() || _binding == null) return
        if (currentState == ChatbotState.WAKEUP) {
            transitionTo(ChatbotState.LISTENING)
            return
        }
        if (pendingReminderConfirmListening && currentState == ChatbotState.REMINDING) {
            pendingReminderConfirmListening = false
            startReminderConfirmListening()
            return
        }
        // Do not let speech recognition hear the robot's own confirmation question.
        if (pendingFallConfirmListening && ttsQueue.isEmpty() &&
            currentState == ChatbotState.FALL_CONFIRMING
        ) {
            pendingFallConfirmListening = false
            sttManager?.startListening(continuous = false)
            mainHandler.removeCallbacks(fallConfirmTimeoutRunnable)
            mainHandler.postDelayed(fallConfirmTimeoutRunnable, 10_000L)
            return
        }
        // Ưu tiên 1: Nếu đang chờ chuyển sang cuộc gọi video → navigate ngay sau TTS xong
        if (pendingVideoCallNavigation && ttsQueue.isEmpty()) {
            pendingVideoCallNavigation = false
            Log.d(TAG, "TTS xác nhận gọi xong → chuyển sang VideoCallFragment")
            (activity as? MainActivity)?.navigateToVideoCallFromChatbot()
            return
        }
        afterSpeechState?.let { nextState ->
            afterSpeechState = null
            transitionTo(nextState)
            return
        }
        if (currentState == ChatbotState.ERROR) {
            transitionTo(ChatbotState.SLEEPING)
        } else if (!isResponseStreaming &&
            currentState in setOf(ChatbotState.SPEAKING, ChatbotState.THINKING)) {
            transitionTo(ChatbotState.LISTENING)
        }
    }

    private fun handleGroqError() {
        isResponseStreaming = false
        ttsBuffer.clear()
        val errorMsg = "Xin lỗi, ${userProfile.elderlyPronoun}, ${userProfile.robotPronoun} đang gặp sự cố kết nối. Vui lòng thử lại."
        addMessageToLog(isUser = false, text = errorMsg)
        speakText(errorMsg)
        transitionTo(ChatbotState.ERROR)
    }

    private fun showNoApiKeyError() {
        val msg = "Chatbot chưa được cấu hình API key. Vui lòng liên hệ kỹ thuật viên."
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        updateStatusText("Chưa cài API key")
        transitionTo(ChatbotState.SLEEPING)
    }

    // The compact layout shows only the latest utterance instead of a conversation log.
    @Suppress("UNUSED_PARAMETER")
    private fun addMessageToLog(isUser: Boolean, text: String) {
        val b = _binding ?: return
        b.tvSttRealtime.text = text
        b.tvSttRealtime.visibility = View.VISIBLE
    }

    /** Cập nhật nội dung message bot cuối cùng (khi streaming xong) */
    private fun updateLastBotMessage(fullText: String) {
        _binding?.tvSttRealtime?.text = fullText
    }

    private fun updateStatusText(status: String) {
        _binding?.tvChatbotStatus?.text = status
    }

    // ════════════════════════════════════════════════════════════════════════
    // ANIMATIONS
    // ════════════════════════════════════════════════════════════════════════

    private fun animateFaceWakeup() {
        val b = _binding ?: return
        // Scale up rồi về bình thường (hiệu ứng "nhảy dậy")
        b.ivChatbotFace.animate()
            .scaleX(1.15f).scaleY(1.15f)
            .setDuration(200)
            .withEndAction {
                b.ivChatbotFace.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .start()
            }.start()
    }

    private fun animateThinking() {
        val b = _binding ?: return
        // Nhịp đập nhẹ (pulse) khi đang suy nghĩ
        val pulse = AlphaAnimation(1f, 0.6f).apply {
            duration = 600
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        b.ivChatbotFace.startAnimation(pulse)
    }

    private fun stopAnimations() {
        _binding?.ivChatbotFace?.clearAnimation()
    }

    // ════════════════════════════════════════════════════════════════════════
    // UTILS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Dùng cho Streaming Chatbot: Xóa thẻ <think> đang đóng hoặc đang mở.
     */
    private fun getCleanedStreamingText(raw: String): String {
        var text = raw
        // Xóa block <think>...</think> hoàn chỉnh
        text = text.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        // Xóa block <think> đang mở ở cuối (nếu có)
        val openIdx = text.lastIndexOf("<think>", ignoreCase = true)
        if (openIdx != -1) {
            text = text.substring(0, openIdx)
        }
        return text
    }

}
