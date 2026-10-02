package com.example.datn_v1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.datn_v1.databinding.VideoCallFragmentBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import org.webrtc.*

/**
 * VideoCallFragment – ROBOT APP
 *
 * Firebase node: /webrtc_signal
 *
 * Luồng:
 *  - Robot là CALLER: nhấn "Gọi Caregiver" → tạo Offer → ghi Firebase → chờ Answer
 *  - Robot là CALLEE: Caregiver gọi đến → Robot TỰ ĐỘNG nhận (auto-answer)
 *
 * ICE Candidate:
 *  - Robot luôn ghi vào "robotCandidates"
 *  - Robot luôn đọc từ "caregiverCandidates"
 *
 * Robot Control:
 *  - TrackingFragment là owner duy nhất của RobotControlManager/BLE.
 *  - WebRTC là camera owner trong cuộc gọi và chia sẻ local frame cho YOLO.
 *  - Fragment video không tạo thêm GATT connection.
 */
class VideoCallFragment : Fragment() {

    private companion object {
        const val TAG = "RobotVideoCall"
        const val CAMERA_CAPTURE_FPS = 25
        const val METERED_USERNAME   = "a45ea4fbddc012d96b52165a"
        const val METERED_CREDENTIAL = "24yjin88NfdaGLkC"
    }

    // ─── View Binding ──────────────────────────────────────────────────────────
    private var _binding: VideoCallFragmentBinding? = null
    private val binding get() = _binding!!

    // ─── Firebase ──────────────────────────────────────────────────────────────
    private lateinit var callRef: DatabaseReference

    // ─── WebRTC ────────────────────────────────────────────────────────────────
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var trackingFrameSink: VideoSink? = null

    // ─── Chatbot integration ─────────────────────────────────────────────────────────────────────────────
    /** True khi được navigate từ ChatbotFragment — sau kết thúc sẽ quay về chatbot */
    private var isFromChatbot = false
    private val callHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val callTimeoutRunnable = Runnable { onCallTimeout() }
    private val CALL_TIMEOUT_MS = 30_000L   // 30 giây chờ Caregiver nhận máy

    // ─── Audio Manager ─────────────────────────────────────────────────────────
    private var audioManager: AudioManager? = null
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerOn: Boolean = false

    // ─── State ─────────────────────────────────────────────────────────────────
    private var currentRole: String? = null   // "caller" | "callee" | null
    private var isCallActive = false
    private var remoteDescSet = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isMicOn = true
    private var isCamOn = true
    private var emergencyReason: String? = null
    private var hasHandledIncoming = false

    // ─── Firebase Listeners ────────────────────────────────────────────────────
    private var callNodeListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var remoteCandidateListener: ChildEventListener? = null

    // ─── Status auto-hide ────────────────────────────────────────────────────
    private val statusHideRunnable = Runnable {
        _binding?.tvStatus?.visibility = View.GONE
    }

    // ─── Permission Launcher (Camera + Audio) ─────────────────────────────────
    private val avPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) {
            initWebRTC()
        } else {
            Toast.makeText(
                context,
                "Can quyen Camera va Microphone de dung Video Call",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = VideoCallFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Toast.makeText(requireContext(), "Phiên đăng nhập không hợp lệ", Toast.LENGTH_LONG).show()
            return
        }
        callRef = FirebaseDatabase.getInstance().getReference(FirebasePairPaths.activeCall(uid))

        // Kiểm tra có được navigate từ ChatbotFragment không
        isFromChatbot = arguments?.getBoolean("from_chatbot", false) ?: false
        emergencyReason = arguments?.getString("emergency_reason")

        setupButtonListeners()
        showIdleState()
        checkAndRequestAvPermissions()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        callHandler.removeCallbacksAndMessages(null)
        releaseAll()
        _binding = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI State
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupButtonListeners() {
        binding.btnCallCaregiver.setOnClickListener  { startCallAsRobot() }
        binding.btnEndCall.setOnClickListener        { endCall(notifyRemote = true) }
        binding.btnToggleMic.setOnClickListener      { toggleMic() }
        binding.btnToggleCamera.setOnClickListener   { toggleCamera() }
    }

    private fun showIdleState() {
        currentRole        = null
        isCallActive       = false
        remoteDescSet      = false
        hasHandledIncoming = false
        isMicOn            = true
        isCamOn            = true
        pendingIceCandidates.clear()

        // Luôn bật mic và cam khi về trạng thái idle, đảm bảo cuộc gọi mới luôn mở
        localAudioTrack?.setEnabled(true)
        localVideoTrack?.setEnabled(true)

        binding.layoutIdle.visibility        = View.VISIBLE
        binding.layoutCallControls.visibility = View.GONE
        binding.remoteSurfaceView.visibility  = View.INVISIBLE
        binding.localVideoCard.visibility     = View.GONE

        // Khôi phục nút gọi về trạng thái bình thường
        binding.btnCallCaregiver.isEnabled = true
        binding.btnCallCaregiver.alpha = 1.0f
        binding.btnCallCaregiver.text = "Gọi Caregiver"

        // Hiển status và reset text
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Sẵn sàng kết nối"
        binding.root.removeCallbacks(statusHideRunnable)

        // Reset mic/cam icons
        binding.btnToggleMic.setImageResource(R.drawable.vc_ic_mic_on)
        binding.btnToggleMic.alpha = 1.0f
        binding.btnToggleCamera.setImageResource(R.drawable.vc_ic_cam_on)
        binding.btnToggleCamera.alpha = 1.0f

        // Clear last video frame
        try { binding.remoteSurfaceView.clearImage() } catch (_: Exception) {}
    }

    private fun showActiveCallState(statusText: String, autoHide: Boolean = false) {
        binding.layoutIdle.visibility        = View.GONE
        binding.layoutCallControls.visibility = View.VISIBLE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = statusText
        binding.root.removeCallbacks(statusHideRunnable)
        if (autoHide) binding.root.postDelayed(statusHideRunnable, 3000)
    }

    private fun updateStatus(text: String, autoHide: Boolean = false) {
        activity?.runOnUiThread {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = text
            binding.root.removeCallbacks(statusHideRunnable)
            if (autoHide) binding.root.postDelayed(statusHideRunnable, 3000)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Permissions
    // ══════════════════════════════════════════════════════════════════════════

    private fun checkAndRequestAvPermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) initWebRTC() else avPermissionLauncher.launch(missing.toTypedArray())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WebRTC Initialization
    // ══════════════════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private fun initWebRTC() {
        // ── Cấu hình AudioManager để kích hoạt AEC phần cứng ──────────────────
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = audioManager!!.mode
        savedSpeakerOn = audioManager!!.isSpeakerphoneOn
        audioManager!!.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager!!.isSpeakerphoneOn = true

        eglBase = EglBase.create()

        binding.localSurfaceView.apply {
            init(eglBase!!.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(true)   // Robot đổi sang dùng camera trước
            setZOrderMediaOverlay(true) // Đảm bảo PiP hiển thị đúng lớp trên cùng
        }
        binding.remoteSurfaceView.apply {
            init(eglBase!!.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(false)
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(requireContext())
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        setupLocalMedia()
        listenForCallState()

        // Tu dong goi Caregiver neu duoc navigate tu ChatbotFragment
        if (isFromChatbot) {
            callHandler.postDelayed({ startCallAsRobot() }, 800)
        }
    }

    private fun buildAudioConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
    }

    private fun setupLocalMedia() {
        localAudioTrack = factory!!.createAudioTrack(
            "ROBOT_AUDIO",
            factory!!.createAudioSource(buildAudioConstraints())
        )

        val enumerator = Camera2Enumerator(requireContext())
        val cameraId = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: run { Log.w(TAG, "No camera found on device"); return }

        val capturer = enumerator.createCapturer(cameraId, null)
            ?: run { Log.w(TAG, "Cannot create camera capturer"); return }
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("RobotCaptureThread", eglBase!!.eglBaseContext)
        surfaceTextureHelper = helper
        val source = factory!!.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(helper, requireContext(), source.capturerObserver)
        capturer.startCapture(640, 480, CAMERA_CAPTURE_FPS)

        localVideoTrack = factory!!.createVideoTrack("ROBOT_VIDEO", source)
        localVideoTrack!!.addSink(binding.localSurfaceView)

        trackingFrameSink = VideoSink { frame ->
            (activity as? MainActivity)?.submitWebRtcFrameForTracking(frame)
        }.also { localVideoTrack!!.addSink(it) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ICE Server Configuration
    // ══════════════════════════════════════════════════════════════════════════

    private fun getIceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:global.relay.metered.ca:80").createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80")
            .setUsername(METERED_USERNAME).setPassword(METERED_CREDENTIAL).createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80?transport=tcp")
            .setUsername(METERED_USERNAME).setPassword(METERED_CREDENTIAL).createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443")
            .setUsername(METERED_USERNAME).setPassword(METERED_CREDENTIAL).createIceServer(),
        PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp")
            .setUsername(METERED_USERNAME).setPassword(METERED_CREDENTIAL).createIceServer()
    )

    // ══════════════════════════════════════════════════════════════════════════
    // PeerConnection
    // ══════════════════════════════════════════════════════════════════════════

    private fun createPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(getIceServers()).apply {
            sdpSemantics            = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType       = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state: $state")
                activity?.runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CHECKING ->
                            updateStatus("Đang kết nối...")

                        PeerConnection.IceConnectionState.CONNECTED -> {
                            callHandler.removeCallbacks(callTimeoutRunnable)
                            isCallActive = true
                            updateStatus("Đã kết nối", autoHide = true)
                            binding.remoteSurfaceView.visibility = View.VISIBLE
                            binding.localVideoCard.visibility    = View.VISIBLE
                            binding.layoutCallControls.visibility = View.VISIBLE
                            binding.layoutIdle.visibility       = View.GONE

                        }

                        PeerConnection.IceConnectionState.COMPLETED ->
                            updateStatus("Đang gọi - Đã kết nối", autoHide = true)

                        PeerConnection.IceConnectionState.DISCONNECTED ->
                            updateStatus("Mất tín hiệu, đang kết nối lại...")

                        PeerConnection.IceConnectionState.FAILED -> {
                            updateStatus("Kết nối thất bại")
                            Toast.makeText(
                                context,
                                "Không kết nối được. Kiểm tra mạng hoặc TURN server.",
                                Toast.LENGTH_LONG
                            ).show()
                            endCall(notifyRemote = true)
                        }

                        PeerConnection.IceConnectionState.CLOSED ->
                            endCall(notifyRemote = false)

                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                callRef.child("robotCandidates").push().setValue(
                    mapOf(
                        "candidate"     to candidate.sdp,
                        "sdpMid"        to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex
                    )
                )
            }
            override fun onIceCandidatesRemoved(arr: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() ?: return
                if (track is VideoTrack) {
                    activity?.runOnUiThread {
                        track.addSink(binding.remoteSurfaceView)
                        binding.remoteSurfaceView.visibility = View.VISIBLE
                    }
                }
            }
        })
    }

    private fun addLocalTracks() {
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("ROBOT_STREAM")) }
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("ROBOT_STREAM")) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 1: Robot là CALLER (gọi đến Caregiver)
    // ══════════════════════════════════════════════════════════════════════════

    private fun startCallAsRobot() {
        if (factory == null) {
            Toast.makeText(context, "Dang khoi tao WebRTC, vui long thu lai...", Toast.LENGTH_SHORT).show()
            return
        }
        currentRole = "caller"

        binding.btnCallCaregiver.isEnabled = false
        binding.btnCallCaregiver.alpha = 0.5f
        binding.btnCallCaregiver.text = "Dang goi..."
        updateStatus("Dang goi Caregiver...")

        val callData = mutableMapOf<String, Any>(
            "callerRole" to "robot",
            "status" to "calling"
        )
        emergencyReason?.let { callData["emergencyReason"] = it }
        callRef.setValue(callData).addOnCompleteListener {
                peerConnection = createPeerConnection() ?: return@addOnCompleteListener
                addLocalTracks()

                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }

            peerConnection?.createOffer(makeSdpObserver(
                onCreateSuccess = { offer ->
                    peerConnection?.setLocalDescription(makeSdpObserver(
                        onSetSuccess = {
                            callRef.child("offer").setValue(
                                mapOf("type" to offer.type.canonicalForm(), "sdp" to offer.description)
                            )
                            listenForAnswer()
                            listenForCaregiverCandidates()
                            // Bat dau dem nguoc timeout 30s
                            callHandler.postDelayed(callTimeoutRunnable, CALL_TIMEOUT_MS)
                        }
                    ), offer)
                }
            ), constraints)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 2: Robot là CALLEE (Caregiver gọi → Robot TỰ ĐỘNG nhận)
    // ══════════════════════════════════════════════════════════════════════════

    private fun autoAnswerIncomingCall() {
        if (factory == null || hasHandledIncoming) return
        hasHandledIncoming = true
        callHandler.postDelayed(callTimeoutRunnable, CALL_TIMEOUT_MS)
        currentRole = "callee"
        activity?.runOnUiThread { showActiveCallState("Caregiver đang kết nối...") }

        peerConnection = createPeerConnection() ?: return
        addLocalTracks()

        callRef.child("offer").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!snap.exists()) return
                callRef.child("offer").removeEventListener(this)

                val sdp  = snap.child("sdp").getValue(String::class.java)  ?: return
                val type = snap.child("type").getValue(String::class.java) ?: return
                val offer = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)

                peerConnection?.setRemoteDescription(makeSdpObserver(
                    onSetSuccess = {
                        remoteDescSet = true
                        drainPendingIceCandidates()

                        peerConnection?.createAnswer(makeSdpObserver(
                            onCreateSuccess = { answer ->
                                peerConnection?.setLocalDescription(makeSdpObserver(
                                    onSetSuccess = {
                                        val updates = mapOf(
                                            "answer/type" to answer.type.canonicalForm(),
                                            "answer/sdp"  to answer.description,
                                            "status"      to "connected"
                                        )
                                        callRef.updateChildren(updates)
                                        activity?.runOnUiThread { updateStatus("Đang kết nối...") }
                                    }
                                ), answer)
                            }
                        ), MediaConstraints())
                    }
                ), offer)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        listenForCaregiverCandidates()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Firebase Signaling Listeners
    // ══════════════════════════════════════════════════════════════════════════

    private fun listenForCallState() {
        callNodeListener = callRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status     = snapshot.child("status").getValue(String::class.java)
                val callerRole = snapshot.child("callerRole").getValue(String::class.java)

                when {
                    status == "calling" && callerRole == "caregiver" && currentRole == null ->
                        autoAnswerIncomingCall()

                    (status == "ended" || !snapshot.exists()) && (currentRole != null || isCallActive) ->
                        activity?.runOnUiThread { endCall(notifyRemote = false) }
                }
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "listenForCallState cancelled: ${e.message}")
            }
        })
    }

    private fun listenForAnswer() {
        answerListener = callRef.child("answer").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || peerConnection?.remoteDescription != null) return
                val sdp  = snapshot.child("sdp").getValue(String::class.java)  ?: return
                val type = snapshot.child("type").getValue(String::class.java) ?: return
                val answer = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)

                peerConnection?.setRemoteDescription(makeSdpObserver(
                    onSetSuccess = {
                        remoteDescSet = true
                        drainPendingIceCandidates()
                        callRef.child("status").setValue("connected")
                        updateStatus("Dang ket noi...")
                    }
                ), answer)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun listenForCaregiverCandidates() {
        remoteCandidateListener = callRef.child("caregiverCandidates")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                    val sdp         = snapshot.child("candidate").getValue(String::class.java)  ?: return
                    val sdpMid      = snapshot.child("sdpMid").getValue(String::class.java)     ?: return
                    val sdpMLineIdx = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                    val ice = IceCandidate(sdpMid, sdpMLineIdx, sdp)
                    if (remoteDescSet) peerConnection?.addIceCandidate(ice)
                    else pendingIceCandidates.add(ice)
                }
                override fun onChildChanged(s: DataSnapshot, p: String?) {}
                override fun onChildRemoved(s: DataSnapshot) {}
                override fun onChildMoved(s: DataSnapshot, p: String?) {}
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun drainPendingIceCandidates() {
        pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Controls
    // ══════════════════════════════════════════════════════════════════════════

    private fun toggleMic() {
        isMicOn = !isMicOn
        localAudioTrack?.setEnabled(isMicOn)
        binding.btnToggleMic.setImageResource(
            if (isMicOn) R.drawable.vc_ic_mic_on else R.drawable.vc_ic_mic_off
        )
        binding.btnToggleMic.alpha = if (isMicOn) 1.0f else 0.5f
    }

    private fun toggleCamera() {
        isCamOn = !isCamOn
        localVideoTrack?.setEnabled(isCamOn)
        binding.btnToggleCamera.setImageResource(
            if (isCamOn) R.drawable.vc_ic_cam_on else R.drawable.vc_ic_cam_off
        )
        binding.btnToggleCamera.alpha = if (isCamOn) 1.0f else 0.5f
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Call End & Cleanup
    // ══════════════════════════════════════════════════════════════════════════

    private fun endCall(notifyRemote: Boolean) {
        if (currentRole == null && !isCallActive) return

        // Huy timeout
        callHandler.removeCallbacks(callTimeoutRunnable)

        activity?.runOnUiThread {
            binding.remoteSurfaceView.visibility = View.INVISIBLE
            binding.localVideoCard.visibility    = View.GONE
            showIdleState()
        }

        if (notifyRemote) callRef.child("status").setValue("ended")
        cleanupPeerConnection()
        cleanupFirebaseSignalingListeners()

        // Luon quay ve ChatbotFragment sau khi ket thuc cuoc goi
        isFromChatbot = false
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            (activity as? MainActivity)?.returnToChatbotAfterCall()
        }, 1200)
    }

    /** Xu ly khi Caregiver khong nhan may sau 30 giay */
    private fun onCallTimeout() {
        Log.d(TAG, "Call timeout - Caregiver khong nhan may")
        endCall(notifyRemote = true)
    }

    private fun cleanupPeerConnection() {
        try {
            _binding?.remoteSurfaceView?.clearImage()
            _binding?.localSurfaceView?.clearImage()
        } catch (_: Exception) {}
        try {
            peerConnection?.senders?.forEach { sender ->
                peerConnection?.removeTrack(sender)
            }
        } catch (_: Exception) {}
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        isCallActive = false
    }

    private fun cleanupFirebaseSignalingListeners() {
        answerListener?.let { callRef.child("answer").removeEventListener(it) }
        answerListener = null
        remoteCandidateListener?.let { callRef.child("caregiverCandidates").removeEventListener(it) }
        remoteCandidateListener = null
    }

    @Suppress("DEPRECATION")
    private fun releaseAll() {
        endCall(notifyRemote = false)
        callNodeListener?.let { callRef.removeEventListener(it) }
        callNodeListener = null
        try { videoCapturer?.stopCapture() } catch (e: InterruptedException) {
            Log.e(TAG, "stopCapture interrupted: ${e.message}")
        }
        videoCapturer?.dispose()
        videoCapturer = null
        trackingFrameSink?.let { localVideoTrack?.removeSink(it) }
        trackingFrameSink = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        factory?.dispose()
        factory = null
        _binding?.localSurfaceView?.release()
        _binding?.remoteSurfaceView?.release()
        eglBase?.release()
        eglBase = null
        try {
            audioManager?.mode = savedAudioMode
            audioManager?.isSpeakerphoneOn = savedSpeakerOn
        } catch (_: Exception) {}
        audioManager = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SDP Observer Helper
    // ══════════════════════════════════════════════════════════════════════════

    private fun makeSdpObserver(
        onCreateSuccess: ((SessionDescription) -> Unit)? = null,
        onSetSuccess: (() -> Unit)? = null
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            sdp?.let { onCreateSuccess?.invoke(it) }
        }
        override fun onSetSuccess() {
            onSetSuccess?.invoke()
        }
        override fun onCreateFailure(err: String?) {
            Log.e(TAG, "SDP Create Failure: $err")
        }
        override fun onSetFailure(err: String?) {
            Log.e(TAG, "SDP Set Failure: $err")
        }
    }
}
