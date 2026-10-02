package com.example.datn_v1

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.datn_v1.databinding.TrackingFragmentBinding
import com.example.datn_v1.tracking.FallFeatureExtractor
import com.example.datn_v1.tracking.FallVerificationEngine
import com.example.datn_v1.tracking.LstmFallDetector
import com.example.datn_v1.tracking.PostureGeometry
import com.example.datn_v1.tracking.SortTracker
import com.example.datn_v1.tracking.TrackingMotionPolicy
import org.opencv.android.OpenCVLoader
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.auth.FirebaseAuth
import org.webrtc.VideoFrame

/**
 * TrackingFragment — Phát hiện té ngã bằng YOLO26n-pose TFLite + LSTM + SORT Tracking + Re-ID.
 *
 * ================================================================
 *  STAGE 1: yolo26n-pose_float32.tflite (Pose Estimation)
 *  Input : [1, 480, 640, 3]  NHWC  float32  (pixel / 255.0)
 *  Output: [1, 300, 57]      float32  — anchors-free format
 *    det[d][0..3]  = cx, cy, w, h  (pixel space, model input size)
 *    det[d][4]     = objectness score
 *    det[d][5]     = class confidence (person)
 *    det[d][6..56] = 17 keypoints x 3 (x, y, visibility)
 *    Score thường dùng: det[d][4] hoặc det[d][5], lấy max(4,5)
 *
 *  STAGE 2: fall_lstm32v2.tflite (Fall Classification)
 *  Input : [1, 15, 69]  float32
 *    — 15 frames x 69 features
 *    — features = 17 kps x (x/100, y/100, dx, dy) + aspect_ratio
 *  Output: [1, 2]  float32  — [prob_normal, prob_fall]
 *
 *  STAGE 3: Binary output + endpoint keypoint verification before an alert
 * ================================================================
 */
class TrackingFragment : Fragment(R.layout.tracking_fragment) {

    private val inferenceLock = Object()
    private var _binding: TrackingFragmentBinding? = null
    private val binding get() = _binding!!

    private var interpreter: Interpreter? = null
    private var lstmFallDetector: LstmFallDetector? = null
    private lateinit var cameraExecutor: ExecutorService
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var isFrontCamera = true
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var isCameraPausedForVideoCall = false
    private var cameraBindingRequestId = 0L
    private var wasAiPipelineEnabled = false

    // ─── Model files ─────────────────────────────────────────────────────────
    // GPU model (raw, không NMS, static tensors, FP32): độ chính xác cao hơn, chạy GPU
    // Tạo bằng: python convert_to_tflite_gpu.py
    private val MODEL_NAME_GPU = "yolo26n-pose_320_gpu.tflite"  // output [1,56,2100]
    // E2E model (có NMS tích hợp): fallback, chỉ chạy CPU (dynamic tensors)
    private val MODEL_NAME_E2E = "yolo26n-pose_320.tflite"      // output [1,300,57]

    private var MODEL_IN_H = 320
    private var MODEL_IN_W = 320
    private val NUM_KPS    = 17

    // ─── TFLite Thresholds ───────────────────────────────────────────────────
    private val CONF_THRESHOLD = 0.07f  // Giữ thêm bbox điểm thấp để thử khả năng nhận người ở xa hoặc bị che khuất
    private val IOU_THRESHOLD  = 0.45f  // Trả về 0.45 (chuẩn YOLO) để không lỡ tay xóa nhầm hộp bao toàn thân
    private val KPS_VIS_THRESH = 0.30f  // Giữ nguyên 0.30 để mắt/tai dễ hiện hơn

    // ─── Output format detection (set trong setupAIModel sau khi load model) ───
    // E2E  format: [1, 300, 57]  dim1=300, dim2=57 — (xmin,ymin,xmax,ymax,obj,cls,kps)
    // RAW  format: [1, 2100, 56] dim1=2100,dim2=56 — (cx,cy,w,h,conf,kps)  ← GPU OK
    // RAW-T format: [1, 56, 2100] dim1=56, dim2=2100 — transposed (hiếm)
    private var outDim1: Int = 300      // dim sau batch trong output tensor
    private var outDim2: Int = 57       // dim cuối trong output tensor
    private var isRawOutput: Boolean = false         // true = không có NMS
    private var isTransposedOutput: Boolean = false  // true = channels-first [1,56,2100]

    // Output buffer — sẽ được resize động sau khi load model
    private var modelOutput = Array(1) { Array(300) { FloatArray(57) } }

    // Phát hiện coordinate system một lần
    private var coordSystemDetected = false
    private var bboxNormalized      = false

    // SORT Tracker
    private lateinit var sortTracker: SortTracker
    private var opencvInitialized = false

    // ─── Robot Control (BLE → ESP32) ──────────────────────────────────────
    /**
     * Lắng nghe Firebase /robot_control và gửi lệnh BLE đến ESP32.
     * Được start ngay khi TrackingFragment mở — không cần chờ VideoCall.
     */
    @Volatile private var robotControlManager: RobotControlManager? = null

    // Firebase đồng bộ chế độ; lệnh chuyển động bám người đi trực tiếp qua BLE.
    /** Firebase node caregiver bật/tắt: /robot_follow_enabled */
    private lateinit var followEnabledRef: DatabaseReference
    private var resolvedFollowEnabled: Boolean? = null
    private var followFlagListener: ValueEventListener? = null

    /** True khi Caregiver bật nút "Bám Theo" */
    @Volatile private var isAutoFollowEnabled = false

    // ─── Fall Detection AI (Firebase-driven) ──────────────────────────────────
    /**
     * Firebase node caregiver bật/tắt LSTM: /ai_fall_detection_enabled
     * Khi false: YOLO vẫn chạy (cần cho auto-follow) nhưng kết quả LSTM
     * bị bỏ qua và không gửi cảnh báo lên Firebase.
     */
    private lateinit var fallDetectionEnabledRef: DatabaseReference
    private var fallDetectionFlagListener: ValueEventListener? = null

    /** True khi pipeline YOLO + LSTM phát hiện té ngã được phép chạy */
    @Volatile private var isFallDetectionEnabled = false

    /** trackId của người đang bám (null = chưa lock) */
    private var lockedTrackId: Int? = null

    private val fallVerifier = FallVerificationEngine()
    private var lastTargetBbox: RectF? = null
    private var lastPrimaryFallProb = 0f
    private var primaryRecoveryCandidateId: Int? = null
    private var primaryRecoveryStableFrames = 0
    private val PRIMARY_RECOVERY_STABLE_FRAMES = 3
    private val TARGET_REACQUIRE_TIMEOUT_MS = 2_000L
    /** Giữ nguyên hành vi cũ: chỉ dừng sau khi mất hẳn target quá 2 giây. */
    private val TARGET_LOST_TIMEOUT_MS = 2_000L

    /** Timestamp (ms) lần cuối nhìn thấy target */
    private var lastTargetSeenMs = 0L

    /** Lệnh gửi trước đó — dùng cho anti-jitter */
    private var prevFollowCmd = FollowCmd.STOP

    /** Số frame liên tiếp cùng lệnh (anti-jitter counter) */
    private var sameFrameCount = 0

    /** Enum các lệnh di chuyển có thể gửi */
    private enum class FollowCmd {
        STOP, FALL_BACKWARD, PIVOT_LEFT, PIVOT_RIGHT
    }

    private var fallFramingStableFrames = 0
    private var fallFramingLocked = false
    private var wasFallFramingActive = false
    private val FALL_FRAME_MARGIN_X = 0.05f
    private val FALL_FRAME_MARGIN_TOP = 0.04f
    private val FALL_FRAME_MARGIN_BOTTOM = 0.02f
    private val FALL_FRAME_CENTER_LEFT = 0.43f
    private val FALL_FRAME_CENTER_RIGHT = 0.57f
    private val FALL_FRAME_MAX_OCCUPANCY = 0.80f
    private val FALL_FRAME_STABLE_FRAMES = 5
    private val FALL_FRAME_RELEASE_LEFT = 0.02f
    private val FALL_FRAME_RELEASE_RIGHT = 0.98f
    private val FALL_FRAME_RELEASE_CENTER_LEFT = 0.35f
    private val FALL_FRAME_RELEASE_CENTER_RIGHT = 0.65f
    private val FALL_FRAME_RELEASE_MAX_OCCUPANCY = 0.90f

    /** Tư thế hình học chỉ dùng cho điều khiển; không thay đổi nhãn nhị phân của LSTM. */
    private enum class BodyPosture { UPRIGHT, LYING, UNKNOWN }
    private var bodyPosture = BodyPosture.UNKNOWN
    private var lyingPostureFrames = 0
    private var uprightPostureFrames = 0
    private val LYING_POSTURE_ENTER_FRAMES = 3
    private val UPRIGHT_POSTURE_ENTER_FRAMES = 4
    private val UPRIGHT_POSTURE_EXIT_LYING_FRAMES = 10
    private val POSTURE_LYING_BBOX_ASPECT = 1.05f
    private val POSTURE_UPRIGHT_BBOX_ASPECT = 0.82f
    private val POSTURE_AXIS_RATIO = 1.15f
    private val POSTURE_MIN_RELIABLE_KEYPOINTS = 5

    /** Hysteresis khoảng cách để không đảo tiến/lùi khi metric dao động gần ngưỡng. */
    private enum class DistanceBand { TOO_FAR, SAFE, TOO_CLOSE }
    private var distanceBand = DistanceBand.SAFE
    private var smoothedDistanceMetric: Float? = null

    private val DIST_FAR_ENTER = 0.56f
    private val DIST_FAR_EXIT = 0.64f
    private val DIST_CLOSE_EXIT = 0.72f
    private val DIST_CLOSE_ENTER = 0.80f
    private val CONTROL_KP_CONFIDENCE = 0.45f
    // Hysteresis đã chặn đảo chiều; tăng alpha để nhận ra người đang rời xa sớm hơn.
    private val DISTANCE_EMA_ALPHA = 0.45f

    /** Bộ điều khiển vector: translation rời rạc kết hợp steering tỉ lệ. */
    private var filteredTrackingTranslation = 0.0
    private var filteredTrackingSteering = 0.0
    private var lastTrackingVectorSentMs = 0L
    private var anklesConfirmed = false
    private var ankleVisibleFrames = 0
    private var ankleMissingFrames = 0
    private val ANKLE_CONFIRM_FRAMES = 3
    private val ANKLE_LOST_FRAMES = 2
    private val TRACK_FRAME_MARGIN_X = 0.05f
    private val TRACK_FRAME_MARGIN_TOP = 0.04f
    private val TRACK_FRAME_MARGIN_BOTTOM = 0.04f
    private val TRACK_FRAME_MAX_OCCUPANCY = 0.82f
    // Giữ bbox sát tâm hơn; steering max vẫn giữ nguyên.
    private val TRACK_CENTER_DEAD_ZONE = 0.07f
    private val TRACK_STEERING_GAIN = 2.2
    private val TRACK_MAX_STEERING = 0.50
    private val TRACK_REVEAL_ANKLES_SPEED = 0.30
    // Translation cần bắt kịp người; steering giữ độ mượt cũ để không tăng độ giật khi quay.
    private val TRACK_TRANSLATION_EMA_ALPHA = 0.60
    private val TRACK_STEERING_EMA_ALPHA = 0.35
    private val TRACK_VECTOR_SEND_INTERVAL_MS = 120L

    private val cameraPermissions = arrayOf(Manifest.permission.CAMERA)

    private val blePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val startupPermissions: Array<String>
        get() = cameraPermissions + blePermissions

    // CameraX được yêu cầu cung cấp 25 fps để chuỗi đầu vào LSTM có nhịp lấy mẫu ổn định.
    // Tốc độ thực tế vẫn phụ thuộc vào dải FPS mà phần cứng camera hỗ trợ.
    private val CAMERA_TARGET_FPS = 25
    private val CAMERA_TARGET_FPS_RANGE = Range(CAMERA_TARGET_FPS, CAMERA_TARGET_FPS)
    private val WEBRTC_AI_MAX_WIDTH    = 320
    private val webRtcFrameInFlight = AtomicBoolean(false)

    // ─── Keypoint indices (COCO 17-point format) ──────────────────────────────
    private val KP_NOSE           = 0
    private val KP_LEFT_SHOULDER  = 5
    private val KP_RIGHT_SHOULDER = 6
    private val KP_LEFT_HIP       = 11
    private val KP_RIGHT_HIP      = 12
    private val KP_LEFT_KNEE      = 13
    private val KP_RIGHT_KNEE  = 14
    private val KP_LEFT_ANKLE  = 15
    private val KP_RIGHT_ANKLE = 16

    /** Chuỗi fallback điều khiển: chân -> gối -> hông -> vai; không dùng điểm đầu. */
    private enum class PoseCoverage { ANKLES, KNEES, HIPS, SHOULDERS, NONE }

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = TrackingFragmentBinding.bind(view)
        cameraExecutor = Executors.newSingleThreadExecutor()

        initOpenCV()
        sortTracker = SortTracker(maxAge = 45, minHits = 2, iouThreshold = 0.25f)
        setupAIModel()
        setupLstmDetector()
        setupAutoFollow()
        setupFallDetection()

        TrackingService.fallResolution.observe(viewLifecycleOwner) { resolution ->
            resolution ?: return@observe
            synchronized(inferenceLock) {
                when (resolution.status) {
                    FallResolutionStatus.SAFE -> fallVerifier.resolveAsSafe()
                    FallResolutionStatus.DANGER -> fallVerifier.resolveAsDanger()
                }
            }
            TrackingService.fallResolution.value = null
        }

        startFeaturesWithGrantedPermissions()
        val missingPermissions = startupPermissions.filterNot(::isPermissionGranted)
        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /** TrackingFragment là owner duy nhất của Firebase control listener và BLE GATT. */
    private fun startRobotControlManager() {
        if (robotControlManager != null) return
        robotControlManager = RobotControlManager(requireContext()).also {
            it.start()
            it.setAutoFollowEnabled(resolvedFollowEnabled)
        }
        Log.d(TAG, "RobotControlManager started for TrackingFragment")
    }

    /** Camera preview and BLE control must not block each other on unrelated permissions. */
    private fun startFeaturesWithGrantedPermissions() {
        if (cameraPermissions.all(::isPermissionGranted)) {
            refreshCameraPipeline()
        }
        if (blePermissions.all(::isPermissionGranted)) {
            startRobotControlManager()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        
        synchronized(inferenceLock) {
            interpreter?.close()
            interpreter = null

            lstmFallDetector?.close()
            lstmFallDetector = null

            nnApiDelegate?.close()
            nnApiDelegate = null

            gpuDelegate?.close()
            gpuDelegate = null
        }
        
        sortTracker.release()
        // Cleanup auto-follow
        if (::followEnabledRef.isInitialized) {
            followFlagListener?.let { followEnabledRef.removeEventListener(it) }
        }
        followFlagListener = null
        resolvedFollowEnabled = null
        // Cleanup fall detection listener
        if (::fallDetectionEnabledRef.isInitialized) {
            fallDetectionFlagListener?.let { fallDetectionEnabledRef.removeEventListener(it) }
        }
        fallDetectionFlagListener = null
        // Dừng RobotControlManager
        robotControlManager?.stop()
        robotControlManager = null
        _binding = null
    }

    // ─── OpenCV ──────────────────────────────────────────────────────────────
    private fun initOpenCV() {
        opencvInitialized = OpenCVLoader.initLocal()
        Log.d(TAG, if (opencvInitialized) "OpenCV OK" else "OpenCV FAILED — Re-ID disabled")
    }

    // ─── Load YOLO model ─────────────────────────────────────────────────────
    /**
     * Tải ByteBuffer của model tốt nhất có sẵn:
     *   1. GPU model (yolo26n-pose_320_gpu.tflite) — raw output, static tensors, GPU OK
     *   2. E2E model (yolo26n-pose_320.tflite)     — fallback, CPU only
     */
    private fun loadBestAvailableModel(): Pair<ByteBuffer, Boolean> {
        return try {
            val buf = loadModelFile(MODEL_NAME_GPU)
            Log.d(TAG, "Using GPU model: $MODEL_NAME_GPU")
            Pair(buf, true)   // isGpuModel = true
        } catch (_: Exception) {
            Log.d(TAG, "GPU model not found, using E2E model: $MODEL_NAME_E2E")
            Pair(loadModelFile(MODEL_NAME_E2E), false)  // isGpuModel = false
        }
    }

    private fun setupAIModel() {
        try {
            val (buf, isGpuModel) = loadBestAvailableModel()

            // ─── Hardware acceleration: NNAPI → GPU → CPU ───────────────────────
            //
            // NNAPI (Android Neural Networks API): ưu tiên nhất
            //   • Tự phân công ops lên GPU/DSP/NPU linh hoạt
            //   • Không yêu cầu 100% ops GPU-compatible (mixed execution)
            //   • Tối ưu cho Snapdragon (Adreno GPU + Hexagon DSP)
            //
            // GPU delegate: fallback nếu NNAPI fail
            //   • Chỉ hoạt động khi TẤT CẢ ops đều GPU-compatible
            //   • Nhưng raw YOLO model (không NMS) hầu hết có thể chạy GPU
            //
            // CPU XNNPack: an toàn tuyệt đối, tự tối ưu SIMD

            var loaded = false

            // Try 1: GPU delegate (ưu tiên cao nhất, ép phần cứng Adreno)
            if (!loaded) try {
                val gpuOptions = org.tensorflow.lite.gpu.GpuDelegateFactory.Options().apply {
                    setQuantizedModelsAllowed(false) // Tắt cho phép giảm precision xuống FP16
                    setInferencePreference(
                        org.tensorflow.lite.gpu.GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED
                    )
                }
                gpuDelegate = GpuDelegate(gpuOptions)
                val opts = Interpreter.Options().apply {
                    addDelegate(gpuDelegate)
                }
                interpreter = Interpreter(buf, opts)
                loaded = true
                Log.d(TAG, "YOLO loaded with GPU delegate")
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate unavailable: ${e.message}")
                gpuDelegate?.close()
                gpuDelegate = null
            }

            // Try 2: NNAPI (Fallback)
            if (!loaded) try {
                val nnApiOptions = NnApiDelegate.Options().apply {
                    setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                }
                val nnApi = NnApiDelegate(nnApiOptions)
                val opts  = Interpreter.Options().apply {
                    addDelegate(nnApi)
                }
                interpreter  = Interpreter(buf, opts)
                nnApiDelegate = nnApi
                loaded = true
                Log.d(TAG, "YOLO loaded with NNAPI delegate (Fallback)")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI unavailable: ${e.message}")
            }

            // Try 3: CPU XNNPack (luôn thành công)
            if (!loaded) {
                val cpuOpts = Interpreter.Options().apply { setNumThreads(4) }
                interpreter = Interpreter(buf, cpuOpts)
                Log.d(TAG, "YOLO loaded with CPU (4 threads + XNNPack)")
            }

            val inputShape  = interpreter!!.getInputTensor(0).shape()
            val outputShape = interpreter!!.getOutputTensor(0).shape()
            Log.d(TAG, "YOLO Input  shape: ${inputShape.contentToString()}")
            Log.d(TAG, "YOLO Output shape: ${outputShape.contentToString()}")

            // Input: [1, H, W, 3]
            if (inputShape.size == 4) {
                MODEL_IN_H = inputShape[1]
                MODEL_IN_W = inputShape[2]
                Log.d(TAG, "YOLO input: ${MODEL_IN_W}x${MODEL_IN_H}")
            }

            // Detect output format và resize buffer động
            // E2E  [1, 300, 57] : dim1=300, dim2=57
            // RAW  [1, 2100, 56]: dim1=2100,dim2=56  ← GPU model
            // RAW-T[1, 56, 2100]: dim1=56, dim2=2100 (transposed, hiếm)
            if (outputShape.size == 3) {
                outDim1 = outputShape[1]
                outDim2 = outputShape[2]
                isRawOutput        = !(outDim1 == 300 && outDim2 == 57)
                isTransposedOutput = isRawOutput && (outDim1 < outDim2)
                // Resize buffer để khớp với output shape thực tế
                modelOutput = Array(1) { Array(outDim1) { FloatArray(outDim2) } }
                val formatStr = when {
                    !isRawOutput        -> "E2E [1,$outDim1,$outDim2] CPU-only"
                    isTransposedOutput  -> "RAW-Transposed [1,$outDim1,$outDim2] GPU-OK"
                    else                -> "RAW [1,$outDim1,$outDim2] GPU-OK"
                }
                Log.d(TAG, "Output format: $formatStr")
            }

        } catch (e: Exception) {
            Log.e(TAG, "YOLO model load error", e)
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), "Lỗi load YOLO model hoàn toàn!", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ─── Load LSTM model ─────────────────────────────────────────────────────
    private fun setupLstmDetector() {
        try {
            lstmFallDetector = LstmFallDetector(requireContext())
            Log.d(TAG, "LSTM Fall Detector loaded OK")
        } catch (e: Exception) {
            Log.e(TAG, "LSTM model load error", e)
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), "Lỗi load LSTM model!", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ─── CameraX ─────────────────────────────────────────────────────────────
    fun pauseCamera() {
        isCameraPausedForVideoCall = true
        cameraBindingRequestId++
        try {
            cameraProvider?.unbindAll()
            Log.d(TAG, "Tracking camera paused")
        } catch (e: Exception) {
            Log.w(TAG, "pauseCamera error: ${e.message}")
        }
    }

    fun resumeCamera() {
        isCameraPausedForVideoCall = false
        if (isAdded && !isDetached) {
            refreshCameraPipeline()
            Log.d(TAG, "Tracking camera resumed")
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null || isCameraPausedForVideoCall ||
            !cameraPermissions.all(::isPermissionGranted)) return

        if (hidden && !isAiPipelineEnabled()) {
            // Không cần giữ camera khi Fragment bị ẩn và cả hai tính năng AI đều tắt.
            cameraBindingRequestId++
            cameraProvider?.unbindAll()
            Log.d(TAG, "Camera released: tracking and fall detection are disabled")
        } else if (hidden) {
            // Chỉ giữ ImageAnalysis khi một tính năng AI đang hoạt động.
            startCamera(includePreview = false, includeAnalysis = true)
        } else {
            // Run after FragmentManager has made the view visible and attachable.
            binding.viewFinder.post {
                if (_binding != null && !isHidden && !isCameraPausedForVideoCall) {
                    startCamera(
                        includePreview = true,
                        includeAnalysis = isAiPipelineEnabled()
                    )
                }
            }
        }
    }

    private fun isAiPipelineEnabled(): Boolean =
        isAutoFollowEnabled || isFallDetectionEnabled

    /** Xóa kết quả của frame cuối ngay khi không còn tính năng AI nào hoạt động. */
    private fun clearAiPresentationWhenDisabled() {
        val currentBinding = _binding ?: return
        currentBinding.root.post {
            val latestBinding = _binding ?: return@post
            if (!isAiPipelineEnabled()) {
                latestBinding.bboxOverlay.clear()
                latestBinding.tvScore.text = "AI: Đang tắt"
                latestBinding.tvScore.setTextColor(Color.GRAY)
            }
        }
    }

    /**
     * Đồng bộ các use case CameraX với trạng thái chức năng.
     * - Fragment đang hiển thị: luôn giữ Preview, chỉ gắn ImageAnalysis khi cần AI.
     * - Fragment bị ẩn: chỉ giữ camera nếu một tính năng AI đang hoạt động.
     */
    private fun refreshCameraPipeline() {
        val analysisEnabled = isAiPipelineEnabled()
        val hasJustStopped = wasAiPipelineEnabled && !analysisEnabled
        wasAiPipelineEnabled = analysisEnabled

        if (!analysisEnabled) clearAiPresentationWhenDisabled()
        if (hasJustStopped) {
            synchronized(inferenceLock) {
                if (::sortTracker.isInitialized) sortTracker.reset()
            }
        }

        if (!isAdded || _binding == null || isCameraPausedForVideoCall ||
            !cameraPermissions.all(::isPermissionGranted)
        ) return

        if (isHidden && !analysisEnabled) {
            cameraBindingRequestId++
            cameraProvider?.unbindAll()
            Log.d(TAG, "Camera released: no active AI feature")
            return
        }

        startCamera(
            includePreview = !isHidden,
            includeAnalysis = analysisEnabled
        )
    }

    @ExperimentalCamera2Interop
    private fun startCamera(
        includePreview: Boolean = !isHidden,
        includeAnalysis: Boolean = isAiPipelineEnabled()
    ) {
        if (isCameraPausedForVideoCall || _binding == null) return
        val requestId = ++cameraBindingRequestId
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val currentBinding = _binding ?: return@addListener
            if (isCameraPausedForVideoCall || requestId != cameraBindingRequestId) {
                return@addListener
            }
            val prov    = future.get()
            cameraProvider = prov
            val preview = if (includePreview) {
                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    CAMERA_TARGET_FPS_RANGE
                )
                previewBuilder.build().also {
                    it.surfaceProvider = currentBinding.viewFinder.surfaceProvider
                }
            } else null
            val analysis = if (includeAnalysis) {
                val analysisBuilder = ImageAnalysis.Builder()
                Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    CAMERA_TARGET_FPS_RANGE
                )
                analysisBuilder
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(
                                Size(MODEL_IN_W, MODEL_IN_H),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build().also {
                        it.setAnalyzer(cameraExecutor) { proxy ->
                            // Bảo vệ trường hợp cờ vừa tắt nhưng CameraX chưa rebind xong.
                            if (!isAiPipelineEnabled()) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            val t0 = System.currentTimeMillis()
                            runInference(proxy)
                            Log.d(TAG, "Pipeline: ${System.currentTimeMillis() - t0} ms")
                        }
                    }
            } else null
            try {
                prov.unbindAll()
                val cam = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                          else               CameraSelector.DEFAULT_BACK_CAMERA
                val useCases = buildList<UseCase> {
                    preview?.let(::add)
                    analysis?.let(::add)
                }
                prov.bindToLifecycle(viewLifecycleOwner, cam, *useCases.toTypedArray())
                Log.d(
                    TAG,
                    "Camera bound: preview=$includePreview, analysis=$includeAnalysis, " +
                        "requestedFps=$CAMERA_TARGET_FPS"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ─── Inference pipeline ──────────────────────────────────────────────────
    private fun runInference(imageProxy: ImageProxy) {
        try {
            runInference(imageProxyToBitmap(imageProxy))
        } catch (e: Exception) {
            Log.e(TAG, "CameraX inference error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Nhận frame từ camera WebRTC trong lúc CameraX đang được nhường cho video call.
     * Frame được retain trước khi chuyển sang executor YOLO và release sau khi xử lý.
     */
    fun submitWebRtcFrame(frame: VideoFrame) {
        if (!isAdded || _binding == null || cameraExecutor.isShutdown) return
        if (!isAutoFollowEnabled && !isFallDetectionEnabled) return
        if (!webRtcFrameInFlight.compareAndSet(false, true)) return

        frame.retain()
        try {
            cameraExecutor.execute {
                try {
                    videoFrameToBitmap(frame)?.let(::runInference)
                } catch (e: Exception) {
                    Log.e(TAG, "WebRTC inference error: ${e.message}", e)
                } finally {
                    frame.release()
                    webRtcFrameInFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            frame.release()
            webRtcFrameInFlight.set(false)
        }
    }

    private data class LetterboxFrame(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val sourceWidth: Float,
        val sourceHeight: Float,
        val inputWidth: Float,
        val inputHeight: Float
    )

    private fun createLetterboxFrame(source: Bitmap): LetterboxFrame {
        val inputWidth = MODEL_IN_W.toFloat()
        val inputHeight = MODEL_IN_H.toFloat()
        val scale = minOf(
            inputWidth / source.width.toFloat(),
            inputHeight / source.height.toFloat()
        )
        val contentWidth = source.width * scale
        val contentHeight = source.height * scale
        val padX = (inputWidth - contentWidth) / 2f
        val padY = (inputHeight - contentHeight) / 2f

        val output = Bitmap.createBitmap(MODEL_IN_W, MODEL_IN_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(
            source,
            null,
            RectF(padX, padY, padX + contentWidth, padY + contentHeight),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        return LetterboxFrame(
            bitmap = output,
            scale = scale,
            padX = padX,
            padY = padY,
            sourceWidth = source.width.toFloat(),
            sourceHeight = source.height.toFloat(),
            inputWidth = inputWidth,
            inputHeight = inputHeight
        )
    }

    private fun runInference(bitmap: Bitmap) {
        val camW = bitmap.width.toFloat()
        val camH = bitmap.height.toFloat()
        var modelInputBitmap: Bitmap? = null

        try {
            // Khi cả hai tính năng AI đều tắt: không tốn CPU/GPU chạy inference
            if (!isAutoFollowEnabled && !isFallDetectionEnabled) {
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.tvScore.text = "AI: Đang tắt"
                        binding.tvScore.setTextColor(Color.GRAY)
                        binding.bboxOverlay.setDetections(emptyList(), camW, camH)
                    }
                }
                return
            }

            // Giữ nguyên tỉ lệ ảnh, thêm padding để tạo đầu vào vuông rồi chuẩn hóa /255.
            val letterboxFrame = createLetterboxFrame(bitmap)
            modelInputBitmap = letterboxFrame.bitmap
            val tensorImg = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImg.load(letterboxFrame.bitmap)
            val inputBuf = ImageProcessor.Builder()
                .add(NormalizeOp(0f, 255f))
                .build()
                .process(tensorImg).buffer.also { it.rewind() }

            val nmsResults: List<InternalDet>
            val fallResults = mutableMapOf<Int, LstmFallDetector.FallResult>()
            var trackedObjects = emptyList<SortTracker.TrackedObject>()
            var primaryTarget: SortTracker.TrackedObject? = null
            var verificationDecision: FallVerificationEngine.Decision = FallVerificationEngine.Decision.None
            var fallFramingActive = false
            var currentPosture = bodyPosture

            synchronized(inferenceLock) {
                val interp = interpreter
                if (interp == null) return

                // Step C: Inference -> [1, 300, 57]
                val t1 = System.currentTimeMillis()
                interp.run(inputBuf, modelOutput)
                Log.d(TAG, "YOLO inference: ${System.currentTimeMillis() - t1} ms")

                // Phát hiện coordinate system 1 lần
                detectCoordSystem()

                // Step 1+2: Decode + filter
                val raw = decodeAndFilter(letterboxFrame)

                // Step 3: NMS
                nmsResults = nms(raw)
                Log.d(TAG, "Detections: raw=${raw.size} NMS=${nmsResults.size}")

                // Step 4: SORT Tracking + Re-ID
                val trackerInput = nmsResults.map { det ->
                    SortTracker.DetectionInput(
                        bbox      = det.rect,
                        score     = det.score,
                        classId   = det.classId,
                        keypoints = det.keypoints
                    )
                }
                val frameBitmap    = if (opencvInitialized) bitmap else null
                trackedObjects = sortTracker.update(trackerInput, frameBitmap)
                Log.d(TAG, "Tracked: ${trackedObjects.size}")

                // Khóa đúng một người ngay từ lúc pipeline bắt đầu. Auto-follow và LSTM
                // dùng chung target này, không tự chuyển sang người vừa đi ngang qua.
                if (isAutoFollowEnabled || isFallDetectionEnabled) {
                    primaryTarget = resolvePrimaryTarget(
                        trackedObjects = trackedObjects,
                        camW = camW,
                        camH = camH,
                        lstm = lstmFallDetector
                    )
                    primaryTarget?.let { primary ->
                        trackedObjects = trackedObjects
                            .filterNot {
                                it.trackId == SortTracker.PRIMARY_TRACK_ID &&
                                    it.bbox !== primary.bbox
                            }
                            .map { if (it.bbox === primary.bbox) primary else it }
                    }
                }

                // Step 5: LSTM Fall Detection chỉ dành cho người mục tiêu.
                // Chỉ chạy khi Caregiver bật nút "Phát Hiện Té Ngã" từ Firebase
                val lstm = if (isFallDetectionEnabled) lstmFallDetector else null
                if (lstm != null) {
                    // A temporarily missing detection still occupies one position in the
                    // 15-frame sequence, as it did during training.
                    val primaryId = primaryTarget?.trackId ?: lockedTrackId
                    if (primaryId != null) {
                        val features = buildFeatureVector69(
                            keypoints = primaryTarget?.keypoints,
                            trackId = primaryId,
                            camW = camW,
                            camH = camH
                        )
                        val result = lstm.processFeatures(primaryId, features)
                        if (primaryTarget?.keypoints?.size == NUM_KPS) {
                            fallResults[primaryId] = result
                        }
                    }
                    val primaryIds = primaryId?.let { setOf(it) } ?: emptySet()
                    lstm.cleanupStaleQueues(primaryIds)
                    prevInputXYPerTrack.keys.retainAll(primaryIds)

                    val result = primaryTarget?.let { fallResults[it.trackId] }
                    if (result?.rawFallDetected == true) {
                        lastPrimaryFallProb = maxOf(lastPrimaryFallProb, result.probFall)
                    } else if (!fallVerifier.isIncidentActive) {
                        lastPrimaryFallProb = result?.probFall ?: 0f
                    }
                    verificationDecision = fallVerifier.update(
                        rawFallDetected = result?.rawFallDetected == true,
                        evidence = buildFallEvidence(primaryTarget, camW, camH)
                    )
                }
                fallFramingActive = fallVerifier.isIncidentActive
                if (verificationDecision is FallVerificationEngine.Decision.AskForConfirmation) {
                    markBodyPostureLying("verified_fall_endpoint")
                }
                currentPosture = primaryTarget?.let {
                    updateBodyPosture(it, camW, camH)
                } ?: bodyPosture
            } // end synchronized

            // Nếu cả hai công tắc vừa được tắt trong lúc frame đang suy luận,
            // bỏ toàn bộ kết quả của frame đó để bbox/cảnh báo cũ không xuất hiện lại.
            if (!isAiPipelineEnabled()) return

            // Step 4.5: Auto-Follow — tính và gửi lệnh điều khiển robot
            if (isAutoFollowEnabled) {
                computeAndSendFollowCmd(
                    trackedObjects,
                    camW,
                    camH,
                    fallFramingActive || currentPosture == BodyPosture.LYING,
                    currentPosture
                )
            }

            val maxFallProb = fallResults.values.maxOfOrNull { it.probFall } ?: 0f

            // Chỉ hỏi xác nhận sau khi tầng hậu kiểm tư thế kết luận đủ nghi ngờ.
            when (val decision = verificationDecision) {
                is FallVerificationEngine.Decision.AskForConfirmation -> {
                    val nowMs = System.currentTimeMillis()
                    val trackId = lockedTrackId ?: -1
                    val result = primaryTarget?.let { fallResults[it.trackId] }
                    Log.i(TAG, "Verified fall candidate: ${decision.reason}, score=${decision.evidenceScore}")
                    TrackingService.reportFall(
                        FallEvent(
                            trackId = trackId,
                            state = "Fall",
                            probFall = maxOf(lastPrimaryFallProb, result?.probFall ?: 0f),
                            timestamp = nowMs,
                            verificationReason = decision.reason,
                            visibleKeypoints = decision.evidence.visibleKeypoints,
                            bboxFullyInside = decision.evidence.bboxFullyInside,
                            upperBodyYRatio = decision.evidence.upperBodyYRatio
                        )
                    )
                }
                is FallVerificationEngine.Decision.Rejected -> {
                    Log.i(TAG, "Fall candidate rejected: ${decision.reason}")
                    lastPrimaryFallProb = 0f
                }
                FallVerificationEngine.Decision.None -> Unit
            }

            // Step 6: Cập nhật UI
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread

                val rawFall = fallResults.values.any { it.rawFallDetected }

                binding.tvScore.text = buildString {
                    append(if (rawFall) "LSTM: FALL" else "LSTM: NO FALL")
                    append("\nFall: ${String.format(java.util.Locale.US, "%.0f%%", maxFallProb * 100)}")
                    append("\nTracks: ${trackedObjects.size}")
                }
                binding.tvScore.setTextColor(
                    if (rawFall) Color.parseColor("#FFFF4444")
                    else Color.parseColor("#FF44FF88")
                )

                binding.bboxOverlay.setDetections(
                    trackedObjects.map { obj ->
                        val modelResult = fallResults[obj.trackId]?.takeIf { it.isReady }
                        val rawFallForTrack = modelResult?.rawFallDetected == true
                        BoundingBoxOverlay.Detection(
                            rect      = obj.bbox,
                            score     = when {
                                modelResult == null -> obj.score
                                rawFallForTrack -> modelResult.probFall
                                else -> modelResult.probNormal
                            },
                            classId   = if (rawFallForTrack) 1 else 0,
                            hasLstmResult = modelResult != null,
                            keypoints = obj.keypoints,
                            trackId   = obj.trackId
                        )
                    },
                    camW, camH
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
        } finally {
            modelInputBitmap?.let { if (!it.isRecycled) it.recycle() }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Build the new model's 69-value feature vector in the LE2I pixel coordinate scale. */
    private fun buildFeatureVector69(
        keypoints: List<BoundingBoxOverlay.Keypoint>?,
        trackId: Int,
        camW: Float,
        camH: Float
    ): FloatArray {
        // Do not filter by the overlay's visibility threshold: training used positive x/y.
        val xs = FloatArray(NUM_KPS) { keypoints?.getOrNull(it)?.x ?: 0f }
        val ys = FloatArray(NUM_KPS) { keypoints?.getOrNull(it)?.y ?: 0f }
        val frame = FallFeatureExtractor.build(
            xs = xs,
            ys = ys,
            frameWidth = camW,
            frameHeight = camH,
            previousXY = prevInputXYPerTrack[trackId]
        )
        prevInputXYPerTrack[trackId] = frame.xy
        return frame.features
    }

    private val prevInputXYPerTrack = mutableMapOf<Int, FloatArray>()

    // Binary LSTM results are kept per stable SORT track ID.

    /**
     * Snapshot bbox của từng track theo frame cuối cùng thấy.
     * Dùng để tính khoảng cách tâm giữa track cũ và track mới.
     */

    /**
     * Ngưỡng khoảng cách tâm bbox (pixel camera) để coi track mới = track cũ bị đổi ID.
     * ~150px ≈ 1/3 chiều rộng frame 480px — đủ rộng cho trường hợp ngã nhưng không quá rộng
     * để tránh nhầm sang người khác.
     */

    // ─── Firebase Fall Alert ─────────────────────────────────────────────────
    /**
     * Ref ghi cảnh báo té ngã lên Firebase /fall_alerts.
     * Mỗi alert được lưu dưới key = timestamp (ms) để dễ sort.
     */

    /**
     * Timestamp (ms) lần cuối gửi cảnh báo.
     * Cooldown 30 giây để tránh spam Firebase khi người nằm bất động lâu.
     */



    // ─── Auto-Follow Engine ───────────────────────────────────────────────────
    /**
     * Thiết lập Firebase listener để lắng nghe flag bật/tắt từ CaregiverApp.
     * Khi flag = false: dừng robot ngay, reset trạng thái tracking.
     */
    private fun setupAutoFollow() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.e(TAG, "Auto-follow unavailable: no authenticated pair")
            binding.switchAutoFollow.isEnabled = false
            return
        }
        val database = FirebaseDatabase.getInstance()
        followEnabledRef = database.getReference(FirebasePairPaths.followEnabled(uid))

        // 1. Gắn sự kiện cho nút Switch trên màn hình Robot
        binding.switchAutoFollow.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == isAutoFollowEnabled) return@setOnCheckedChangeListener
            isAutoFollowEnabled = isChecked
            resolvedFollowEnabled = isChecked
            robotControlManager?.setAutoFollowEnabled(isChecked)
            followEnabledRef.setValue(isChecked)
            refreshCameraPipeline()
            
            if (isChecked) {
                Log.d(TAG, "Auto-Follow: Enabled via Robot UI")
                resetFollowState()
            } else {
                Log.d(TAG, "Auto-Follow: Disabled via Robot UI")
                resetFollowState()
            }
        }

        // 2. Lắng nghe trạng thái từ Firebase (đồng bộ từ Caregiver app)
        followFlagListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.getValue(Boolean::class.java) ?: false
                val wasEnabled = isAutoFollowEnabled
                isAutoFollowEnabled = enabled
                resolvedFollowEnabled = enabled
                robotControlManager?.setAutoFollowEnabled(enabled)
                if (wasEnabled != enabled) refreshCameraPipeline()

                // Đồng bộ UI
                activity?.runOnUiThread {
                    if (_binding != null && binding.switchAutoFollow.isChecked != enabled) {
                        binding.switchAutoFollow.isChecked = enabled
                    }
                }

                if (wasEnabled && !enabled) {
                    // Caregiver (hoặc ai đó) vừa TẮT → dừng robot ngay + reset trạng thái
                    Log.d(TAG, "Auto-Follow: Caregiver disabled → sending STOP")
                    resetFollowState()
                } else if (!wasEnabled && enabled) {
                    Log.d(TAG, "Auto-Follow: Caregiver enabled → starting tracking")
                    resetFollowState()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "follow_enabled listener cancelled: ${error.message}")
                resolvedFollowEnabled = null
                isAutoFollowEnabled = false
                robotControlManager?.setAutoFollowEnabled(null)
            }
        }
        followEnabledRef.addValueEventListener(followFlagListener!!)
    }

    // ─── Fall Detection Setup (Firebase-driven) ───────────────────────────────
    /**
     * Lắng nghe /ai_fall_detection_enabled từ Firebase.
     * Đồng bộ UI switch và cờ isFallDetectionEnabled.
     * Khi tắt: pipeline LSTM dừng, không gửi cảnh báo. YOLO vẫn chạy nếu auto-follow bật.
     */
    private fun setupFallDetection() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.e(TAG, "Fall detection sync unavailable: no authenticated pair")
            binding.switchFallDetection.isEnabled = false
            return
        }
        fallDetectionEnabledRef = FirebaseDatabase.getInstance()
            .getReference(FirebasePairPaths.fallDetectionEnabled(uid))

        // Switch trực tiếp trên màn hình Robot
        binding.switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == isFallDetectionEnabled) return@setOnCheckedChangeListener
            isFallDetectionEnabled = isChecked
            fallDetectionEnabledRef.setValue(isChecked)
            resetFallPipeline(clearTarget = !isAutoFollowEnabled)
            refreshCameraPipeline()
            Log.d(TAG, "Fall Detection: ${if (isChecked) "Enabled" else "Disabled"} via Robot UI")
        }

        // Listener Firebase — đồng bộ từ CaregiverApp
        fallDetectionFlagListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.getValue(Boolean::class.java) ?: false
                val changed = isFallDetectionEnabled != enabled
                isFallDetectionEnabled = enabled
                if (changed) {
                    resetFallPipeline(clearTarget = !isAutoFollowEnabled)
                    refreshCameraPipeline()
                }
                Log.d(TAG, "Fall Detection Firebase sync: $enabled")
                activity?.runOnUiThread {
                    val b = _binding ?: return@runOnUiThread
                    if (b.switchFallDetection.isChecked != enabled) {
                        b.switchFallDetection.isChecked = enabled
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "fall_detection_enabled listener cancelled: ${error.message}")
            }
        }
        fallDetectionEnabledRef.addValueEventListener(fallDetectionFlagListener!!)
    }

    private fun resetFallPipeline(clearTarget: Boolean) {
        synchronized(inferenceLock) {
            lstmFallDetector?.reset()
            fallVerifier.reset()
            fallFramingStableFrames = 0
            fallFramingLocked = false
            lastPrimaryFallProb = 0f
            prevInputXYPerTrack.clear()
            if (clearTarget) clearPrimaryTarget()
        }
    }

    /** Reset toàn bộ trạng thái auto-follow (gọi khi bật/tắt) */
    private fun resetFollowState(clearTarget: Boolean = !isFallDetectionEnabled) {
        if (clearTarget) synchronized(inferenceLock) { clearPrimaryTarget() }
        prevFollowCmd = FollowCmd.STOP
        sameFrameCount = 0
        distanceBand = DistanceBand.SAFE
        smoothedDistanceMetric = null
        fallFramingStableFrames = 0
        fallFramingLocked = false
        wasFallFramingActive = false
        resetTrackingVectorState()
        resetBodyPosture()
    }

    private fun clearPrimaryTarget() {
        lockedTrackId = null
        lastTargetSeenMs = 0L
        lastTargetBbox = null
        primaryRecoveryCandidateId = null
        primaryRecoveryStableFrames = 0
        distanceBand = DistanceBand.SAFE
        smoothedDistanceMetric = null
        fallFramingStableFrames = 0
        fallFramingLocked = false
        wasFallFramingActive = false
        resetTrackingVectorState()
        resetBodyPosture()
        sortTracker.setProtectedTrackId(null)
        prevInputXYPerTrack.clear()
        lstmFallDetector?.reset()
    }

    /**
     * Người xuất hiện đầu tiên được khóa làm mục tiêu chính. Khi SORT đổi ID do tư thế
     * đứng -> nằm, chỉ nhận ID mới ở gần bbox cuối và chuyển toàn bộ state LSTM sang ID đó.
     */
    private fun resolvePrimaryTarget(
        trackedObjects: List<SortTracker.TrackedObject>,
        camW: Float,
        camH: Float,
        lstm: LstmFallDetector?
    ): SortTracker.TrackedObject? {
        val now = System.currentTimeMillis()
        val oldId = lockedTrackId

        trackedObjects.firstOrNull { it.trackId == oldId }?.let { target ->
            primaryRecoveryCandidateId = null
            primaryRecoveryStableFrames = 0
            lastTargetSeenMs = now
            lastTargetBbox = RectF(target.bbox)
            if (target.trackId != SortTracker.PRIMARY_TRACK_ID &&
                sortTracker.promoteToPrimary(target.trackId)
            ) {
                lstm?.transferStateTo(target.trackId, SortTracker.PRIMARY_TRACK_ID)
                prevInputXYPerTrack.remove(target.trackId)?.let {
                    prevInputXYPerTrack[SortTracker.PRIMARY_TRACK_ID] = it
                }
                lockedTrackId = SortTracker.PRIMARY_TRACK_ID
                return target.copy(trackId = SortTracker.PRIMARY_TRACK_ID)
            }
            sortTracker.setProtectedTrackId(SortTracker.PRIMARY_TRACK_ID)
            return target
        }

        if (oldId != null && lastTargetBbox != null &&
            now - lastTargetSeenMs <= TARGET_REACQUIRE_TIMEOUT_MS
        ) {
            val previous = lastTargetBbox!!
            val diagonal = kotlin.math.sqrt(camW * camW + camH * camH)
            val previousArea = (previous.width() * previous.height()).coerceAtLeast(1f)
            val candidate = trackedObjects
                .filter {
                    centerDistance(previous, it.bbox) <= diagonal * 0.35f &&
                        it.bbox.centerX() / camW in 0.10f..0.90f &&
                        it.bbox.centerY() / camH in 0.05f..0.95f
                }
                .minByOrNull {
                    val distanceCost = centerDistance(previous, it.bbox) / diagonal
                    val area = (it.bbox.width() * it.bbox.height()).coerceAtLeast(1f)
                    val scaleCost = kotlin.math.abs(
                        kotlin.math.ln((area / previousArea).toDouble())
                    ).toFloat().coerceAtMost(2f) / 2f
                    val poseCost = 1f -
                        (it.keypoints.count { kp -> kp.visible }.toFloat() / NUM_KPS)
                    distanceCost * 0.60f + scaleCost * 0.25f + poseCost * 0.15f
                }
            val isNearLastPosition = candidate != null
            val isInCentralArea = candidate != null
            if (candidate != null && isNearLastPosition && isInCentralArea) {
                if (primaryRecoveryCandidateId == candidate.trackId) {
                    primaryRecoveryStableFrames++
                } else {
                    primaryRecoveryCandidateId = candidate.trackId
                    primaryRecoveryStableFrames = 1
                }
            } else {
                primaryRecoveryCandidateId = null
                primaryRecoveryStableFrames = 0
            }
            if (candidate != null &&
                primaryRecoveryStableFrames >= PRIMARY_RECOVERY_STABLE_FRAMES &&
                sortTracker.promoteToPrimary(candidate.trackId)
            ) {
                lockedTrackId = SortTracker.PRIMARY_TRACK_ID
                primaryRecoveryCandidateId = null
                primaryRecoveryStableFrames = 0
                lastTargetSeenMs = now
                lastTargetBbox = RectF(candidate.bbox)
                Log.i(TAG, "Primary target restored as ID 0 from temporary ID ${candidate.trackId}")
                return candidate.copy(trackId = SortTracker.PRIMARY_TRACK_ID)
            }
            return null
        }

        if (oldId != null && !fallVerifier.canAcquireNewTarget) return null

        if (oldId != null) clearPrimaryTarget()
        val firstTarget = trackedObjects.maxByOrNull {
            initialTargetQuality(it, camW, camH)
        } ?: return null
        if (!sortTracker.promoteToPrimary(firstTarget.trackId)) return null
        lockedTrackId = SortTracker.PRIMARY_TRACK_ID
        lastTargetSeenMs = now
        lastTargetBbox = RectF(firstTarget.bbox)
        Log.i(TAG, "Primary target locked as canonical ID 0 (source=${firstTarget.trackId})")
        return firstTarget.copy(trackId = SortTracker.PRIMARY_TRACK_ID)
    }

    private fun centerDistance(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Keep the original largest-person preference, with small quality tie-breakers. */
    private fun initialTargetQuality(
        target: SortTracker.TrackedObject,
        camW: Float,
        camH: Float
    ): Float {
        val visibleRatio = target.keypoints.count { it.visible }.toFloat() / NUM_KPS
        val areaRatio = (target.bbox.width() * target.bbox.height() / (camW * camH))
            .coerceIn(0f, 0.45f) / 0.45f
        val dx = target.bbox.centerX() / camW - 0.5f
        val dy = target.bbox.centerY() / camH - 0.5f
        val centrality = (1f - kotlin.math.sqrt(dx * dx + dy * dy) / 0.707f)
            .coerceIn(0f, 1f)
        val clipped = target.bbox.left <= camW * 0.01f ||
            target.bbox.top <= camH * 0.01f ||
            target.bbox.right >= camW * 0.99f ||
            target.bbox.bottom >= camH * 0.99f
        val edgePenalty = if (clipped) 0.10f else 0f
        return areaRatio * 0.60f + target.score * 0.15f +
            visibleRatio * 0.15f + centrality * 0.10f - edgePenalty
    }

    private fun buildFallEvidence(
        target: SortTracker.TrackedObject?,
        camW: Float,
        camH: Float
    ): FallVerificationEngine.FrameEvidence {
        target ?: return FallVerificationEngine.FrameEvidence(targetVisible = false)
        val visibleCount = target.keypoints.count { it.visible }
        val upperBodyPoints = listOf(KP_NOSE, KP_LEFT_SHOULDER, KP_RIGHT_SHOULDER)
            .mapNotNull { index -> target.keypoints.getOrNull(index)?.takeIf { it.visible } }
        // "Head or shoulder in the upper 70%" means any reliable upper-body point is enough.
        val upperBodyY = upperBodyPoints.minOfOrNull { it.y / camH }
        val marginX = camW * 0.02f
        val marginY = camH * 0.02f
        val fullyInside = target.bbox.left >= marginX &&
            target.bbox.top >= marginY &&
            target.bbox.right <= camW - marginX &&
            target.bbox.bottom <= camH - marginY
        val aspectRatio = target.bbox.width() / target.bbox.height().coerceAtLeast(1f)

        return FallVerificationEngine.FrameEvidence(
            targetVisible = true,
            upperBodyYRatio = upperBodyY,
            visibleKeypoints = visibleCount,
            bboxFullyInside = fullyInside,
            aspectRatio = aspectRatio
        )
    }

    private data class PoseAnchor(val x: Float, val y: Float)

    /**
     * Ước lượng độ gần mà không dùng mắt/mũi:
     *   1. Vai + hông + mắt cá chân.
     *   2. Vai + hông + đầu gối.
     *   3. Vai + hông, hoặc hông + gối + mắt cá chân.
     *   4. Kích thước bbox theo cả chiều cao và chiều rộng (hỗ trợ người đang nằm).
     *
     * Lấy max(pose, bbox) là lựa chọn bảo thủ: nếu một tín hiệu cho biết người đang gần thì
     * robot không được tiến chỉ vì một keypoint chân bị nhận sai gần phần đầu.
     */
    private fun estimateDistanceMetric(
        target: SortTracker.TrackedObject,
        camW: Float,
        camH: Float
    ): Float {
        val bboxMetric = maxOf(target.bbox.height() / camH, target.bbox.width() / camW)
            .coerceIn(0f, 1.5f)
        val kps = target.keypoints
        val poseMetric = if (kps.size >= NUM_KPS) {
            val shoulders = keypointCenter(kps, KP_LEFT_SHOULDER, KP_RIGHT_SHOULDER)
            val hips = keypointCenter(kps, KP_LEFT_HIP, KP_RIGHT_HIP)
            val knees = keypointCenter(kps, KP_LEFT_KNEE, KP_RIGHT_KNEE)
            val ankles = keypointCenter(kps, KP_LEFT_ANKLE, KP_RIGHT_ANKLE)

            when {
                shoulders != null && hips != null && ankles != null ->
                    normalizedAnchorDistance(shoulders, ankles, camW, camH) * 1.33f
                shoulders != null && hips != null && knees != null ->
                    normalizedAnchorDistance(shoulders, knees, camW, camH) * 1.80f
                hips != null && knees != null && ankles != null ->
                    normalizedAnchorDistance(hips, ankles, camW, camH) * 2.20f
                shoulders != null && hips != null ->
                    normalizedAnchorDistance(shoulders, hips, camW, camH) * 3.30f
                else -> null
            }
        } else null

        val rawMetric = maxOf(bboxMetric, poseMetric ?: 0f).coerceIn(0f, 1.5f)
        val previous = smoothedDistanceMetric
        val smoothed = if (previous == null) rawMetric else
            previous * (1f - DISTANCE_EMA_ALPHA) + rawMetric * DISTANCE_EMA_ALPHA
        smoothedDistanceMetric = smoothed
        return smoothed
    }

    private fun keypointCenter(
        keypoints: List<BoundingBoxOverlay.Keypoint>,
        firstIndex: Int,
        secondIndex: Int
    ): PoseAnchor? {
        val usable = listOf(firstIndex, secondIndex)
            .mapNotNull { keypoints.getOrNull(it) }
            .filter(::isReliableControlKeypoint)
        if (usable.isEmpty()) return null
        return PoseAnchor(
            x = usable.map { it.x }.average().toFloat(),
            y = usable.map { it.y }.average().toFloat()
        )
    }

    private fun isReliableControlKeypoint(keypoint: BoundingBoxOverlay.Keypoint): Boolean =
        keypoint.visible && keypoint.confidence >= CONTROL_KP_CONFIDENCE

    private fun normalizedAnchorDistance(
        first: PoseAnchor,
        second: PoseAnchor,
        camW: Float,
        camH: Float
    ): Float {
        val dx = (first.x - second.x) / camW
        val dy = (first.y - second.y) / camH
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun updateDistanceBand(metric: Float): DistanceBand {
        distanceBand = when (distanceBand) {
            DistanceBand.SAFE -> when {
                metric < DIST_FAR_ENTER -> DistanceBand.TOO_FAR
                metric >= DIST_CLOSE_ENTER -> DistanceBand.TOO_CLOSE
                else -> DistanceBand.SAFE
            }
            DistanceBand.TOO_FAR ->
                if (metric >= DIST_FAR_EXIT) DistanceBand.SAFE else DistanceBand.TOO_FAR
            DistanceBand.TOO_CLOSE ->
                if (metric <= DIST_CLOSE_EXIT) DistanceBand.SAFE else DistanceBand.TOO_CLOSE
        }
        return distanceBand
    }

    /**
     * Phân loại tư thế bằng hình học 2D, độc lập với nhãn FALL/NO FALL của LSTM.
     * LYING phải có bằng chứng thân dưới để một bbox khuôn mặt rộng không bị nhận nhầm là nằm.
     */
    private fun classifyBodyPosture(
        target: SortTracker.TrackedObject,
        camW: Float,
        camH: Float
    ): BodyPosture {
        val kps = target.keypoints
        if (kps.size < NUM_KPS) return BodyPosture.UNKNOWN

        val reliablePoints = kps.filter(::isReliableControlKeypoint)
        val hasLowerBody = listOf(
            KP_LEFT_HIP, KP_RIGHT_HIP,
            KP_LEFT_KNEE, KP_RIGHT_KNEE,
            KP_LEFT_ANKLE, KP_RIGHT_ANKLE
        ).any { isReliableControlKeypoint(kps[it]) }
        val shoulders = keypointCenter(kps, KP_LEFT_SHOULDER, KP_RIGHT_SHOULDER)
        val hips = keypointCenter(kps, KP_LEFT_HIP, KP_RIGHT_HIP)

        var lyingScore = 0
        var uprightScore = 0
        val bboxAspect = target.bbox.width() / target.bbox.height().coerceAtLeast(1f)
        if (bboxAspect >= POSTURE_LYING_BBOX_ASPECT) lyingScore += 2
        if (bboxAspect <= POSTURE_UPRIGHT_BBOX_ASPECT) uprightScore += 2

        var poseHeight: Float? = null
        if (reliablePoints.size >= POSTURE_MIN_RELIABLE_KEYPOINTS) {
            val poseWidth = (reliablePoints.maxOf { it.x } - reliablePoints.minOf { it.x }) / camW
            val currentPoseHeight =
                (reliablePoints.maxOf { it.y } - reliablePoints.minOf { it.y }) / camH
            poseHeight = currentPoseHeight
            if (poseWidth >= currentPoseHeight * POSTURE_AXIS_RATIO) lyingScore++
            if (currentPoseHeight >= poseWidth * POSTURE_AXIS_RATIO) uprightScore++
        }

        var torsoDy: Float? = null
        if (shoulders != null && hips != null) {
            val torsoDx = kotlin.math.abs(shoulders.x - hips.x) / camW
            val currentTorsoDy = kotlin.math.abs(shoulders.y - hips.y) / camH
            torsoDy = currentTorsoDy
            if (torsoDx >= currentTorsoDy * POSTURE_AXIS_RATIO) lyingScore += 2
            if (currentTorsoDy >= torsoDx * POSTURE_AXIS_RATIO) uprightScore += 2
        }

        if (PostureGeometry.isForeshortenedFloorPose(
                hasLowerBody = hasLowerBody,
                bboxAspect = bboxAspect,
                poseHeight = poseHeight,
                torsoDy = torsoDy
            )
        ) {
            // Override các điểm UPRIGHT giả do cơ thể nằm dọc theo phối cảnh camera.
            lyingScore += 6
        }

        return when {
            hasLowerBody && lyingScore >= 3 && lyingScore > uprightScore -> BodyPosture.LYING
            uprightScore >= 3 && uprightScore > lyingScore -> BodyPosture.UPRIGHT
            else -> BodyPosture.UNKNOWN
        }
    }

    private fun updateBodyPosture(
        target: SortTracker.TrackedObject,
        camW: Float,
        camH: Float
    ): BodyPosture {
        val evidence = classifyBodyPosture(target, camW, camH)
        val previous = bodyPosture
        when (evidence) {
            BodyPosture.LYING -> {
                lyingPostureFrames++
                uprightPostureFrames = 0
                if (lyingPostureFrames >= LYING_POSTURE_ENTER_FRAMES) {
                    bodyPosture = BodyPosture.LYING
                }
            }
            BodyPosture.UPRIGHT -> {
                uprightPostureFrames++
                lyingPostureFrames = 0
                val required = if (bodyPosture == BodyPosture.LYING) {
                    UPRIGHT_POSTURE_EXIT_LYING_FRAMES
                } else {
                    UPRIGHT_POSTURE_ENTER_FRAMES
                }
                if (uprightPostureFrames >= required) bodyPosture = BodyPosture.UPRIGHT
            }
            BodyPosture.UNKNOWN -> {
                lyingPostureFrames = maxOf(0, lyingPostureFrames - 1)
                uprightPostureFrames = maxOf(0, uprightPostureFrames - 1)
                // Preserve a latched LYING state until there is positive upright evidence.
            }
        }
        if (previous != bodyPosture) {
            Log.i(TAG, "Control posture: $previous -> $bodyPosture (evidence=$evidence)")
        }
        return bodyPosture
    }

    private fun markBodyPostureLying(reason: String) {
        val previous = bodyPosture
        bodyPosture = BodyPosture.LYING
        lyingPostureFrames = LYING_POSTURE_ENTER_FRAMES
        uprightPostureFrames = 0
        if (previous != BodyPosture.LYING) {
            Log.i(TAG, "Control posture: $previous -> LYING ($reason)")
        }
    }

    private fun resetBodyPosture() {
        bodyPosture = BodyPosture.UNKNOWN
        lyingPostureFrames = 0
        uprightPostureFrames = 0
    }

    // ─── Auto-Follow Core Functions ──────────────────────────────────────────

    /**
     * Chỉ công nhận ankle/knee khi có thêm một nhóm khớp hỗ trợ, tránh một điểm chân giả
     * làm robot hiểu nhầm người đang ở xa. Hông và vai là fallback; keypoint đầu không tham gia.
     */
    private fun checkPoseCoverage(target: SortTracker.TrackedObject): PoseCoverage {
        val kps = target.keypoints
        if (kps.size < NUM_KPS) return PoseCoverage.NONE
        val hasAnkle = listOf(KP_LEFT_ANKLE, KP_RIGHT_ANKLE)
            .any { isReliableControlKeypoint(kps[it]) }
        val hasKnee = listOf(KP_LEFT_KNEE, KP_RIGHT_KNEE)
            .any { isReliableControlKeypoint(kps[it]) }
        val hasHip = listOf(KP_LEFT_HIP, KP_RIGHT_HIP)
            .any { isReliableControlKeypoint(kps[it]) }
        val hasShoulder = listOf(KP_LEFT_SHOULDER, KP_RIGHT_SHOULDER)
            .any { isReliableControlKeypoint(kps[it]) }
        return when {
            hasAnkle && (hasKnee || hasHip) -> PoseCoverage.ANKLES
            hasKnee && (hasHip || hasShoulder) -> PoseCoverage.KNEES
            hasHip -> PoseCoverage.HIPS
            hasShoulder -> PoseCoverage.SHOULDERS
            else -> PoseCoverage.NONE
        }
    }

    /** Ngoại suy mắt cá chân từ hông-gối, hoặc từ vai-hông khi phần chân bị cắt. */
    private fun estimateExpectedAnkle(target: SortTracker.TrackedObject): PoseAnchor? {
        val kps = target.keypoints
        if (kps.size < NUM_KPS) return null

        val shoulders = keypointCenter(kps, KP_LEFT_SHOULDER, KP_RIGHT_SHOULDER)
        val hips = keypointCenter(kps, KP_LEFT_HIP, KP_RIGHT_HIP)
        val knees = keypointCenter(kps, KP_LEFT_KNEE, KP_RIGHT_KNEE)
        return when {
            hips != null && knees != null -> PoseAnchor(
                x = knees.x + (knees.x - hips.x) * 0.95f,
                y = knees.y + (knees.y - hips.y) * 0.95f
            )
            shoulders != null && hips != null -> PoseAnchor(
                x = hips.x + (hips.x - shoulders.x) * 1.80f,
                y = hips.y + (hips.y - shoulders.y) * 1.80f
            )
            else -> null
        }
    }

    private fun updateAnkleConfirmation(hasSupportedAnkle: Boolean): Boolean {
        if (hasSupportedAnkle) {
            ankleVisibleFrames++
            ankleMissingFrames = 0
            if (ankleVisibleFrames >= ANKLE_CONFIRM_FRAMES) anklesConfirmed = true
        } else {
            ankleMissingFrames++
            ankleVisibleFrames = 0
            if (ankleMissingFrames >= ANKLE_LOST_FRAMES) anklesConfirmed = false
        }
        return anklesConfirmed
    }

    /**
     * Giữ cách ước lượng bằng keypoint + bbox, nhưng điều khiển theo calibration đã chạy ổn:
     * vùng giữa đủ rộng để chống rung, tiến chậm khi đang căn hướng và lùi an toàn phản hồi ngay.
     */
    private fun computeTrackingCmd(
        target: SortTracker.TrackedObject,
        camW: Float,
        camH: Float
    ) {
        val bbox = target.bbox
        val centerXN = bbox.centerX() / camW
        val centerError = centerXN - 0.5f
        val poseCoverage = checkPoseCoverage(target)
        val anklesVisible = updateAnkleConfirmation(poseCoverage == PoseCoverage.ANKLES)
        val expectedAnkle = estimateExpectedAnkle(target)
        val distMetric = estimateDistanceMetric(target, camW, camH)
        val band = updateDistanceBand(distMetric)

        val left     = bbox.left   / camW
        val right    = bbox.right  / camW
        val top      = bbox.top    / camH
        val bottom   = bbox.bottom / camH
        val occupancy = maxOf(bbox.width() / camW, bbox.height() / camH)
        val bboxFullyInside = left  >= TRACK_FRAME_MARGIN_X &&
            right  <= 1f - TRACK_FRAME_MARGIN_X &&
            top    >= TRACK_FRAME_MARGIN_TOP &&
            bottom <= 1f - TRACK_FRAME_MARGIN_BOTTOM
        val expectedAnkleOutside = expectedAnkle?.let {
            it.x / camW !in TRACK_FRAME_MARGIN_X..(1f - TRACK_FRAME_MARGIN_X) ||
                it.y / camH !in 0.05f..(1f - TRACK_FRAME_MARGIN_BOTTOM)
        } ?: false
        val mustFitPerson    = !bboxFullyInside || occupancy > TRACK_FRAME_MAX_OCCUPANCY
        val mustRevealAnkles = !anklesVisible &&
            (expectedAnkleOutside || bottom > 0.90f || occupancy > 0.58f)

        val isTurning = kotlin.math.abs(centerError) > TRACK_CENTER_DEAD_ZONE
        val translation = when {
            mustFitPerson -> TrackingMotionPolicy.backwardSpeed(distMetric, DIST_CLOSE_EXIT)
            mustRevealAnkles -> TRACK_REVEAL_ANKLES_SPEED
            band == DistanceBand.TOO_CLOSE ->
                TrackingMotionPolicy.backwardSpeed(distMetric, DIST_CLOSE_EXIT)
            band == DistanceBand.TOO_FAR -> TrackingMotionPolicy.forwardSpeed(
                distanceMetric = distMetric,
                farExitMetric = DIST_FAR_EXIT,
                turning = isTurning,
                anklesReliable = anklesVisible
            )
            else -> 0.0
        }.coerceIn(-TrackingMotionPolicy.MAX_TRANSLATION, TrackingMotionPolicy.MAX_TRANSLATION)

        // Bbox luôn cung cấp hướng ngang, kể cả khi keypoint mắt cá chân tạm thời bị mất.
        val steering = if (kotlin.math.abs(centerError) <= TRACK_CENTER_DEAD_ZONE) {
            0.0
        } else {
            (centerError * TRACK_STEERING_GAIN)
                .coerceIn(-TRACK_MAX_STEERING, TRACK_MAX_STEERING)
                .toDouble()
        }

        sendTrackingVector(
            translation = translation,
            steering = steering,
            urgentBackOff = mustFitPerson || mustRevealAnkles
        )

        Log.v(
            TAG,
            "TrackVec: id=$lockedTrackId cx=${String.format("%.2f", centerXN)} " +
                "dist=${String.format("%.2f", distMetric)} band=$band pose=$poseCoverage " +
                "inside=$bboxFullyInside ankles=$anklesVisible " +
                "x=${String.format("%.2f", translation)} y=${String.format("%.2f", steering)}"
        )
    }

    /**
     * EMA chỉ làm mượt lệnh đang chạy cùng hướng. STOP, đổi chiều và lùi để đưa người
     * vào đủ khung hình phải có hiệu lực ngay để tránh quãng trôi do bộ lọc.
     */
    private fun sendTrackingVector(
        translation: Double,
        steering: Double,
        urgentBackOff: Boolean
    ) {
        val translationSignChanged = filteredTrackingTranslation * translation < 0.0
        filteredTrackingTranslation = when {
            urgentBackOff || translation == 0.0 || translationSignChanged -> translation
            else -> filteredTrackingTranslation * (1.0 - TRACK_TRANSLATION_EMA_ALPHA) +
                translation * TRACK_TRANSLATION_EMA_ALPHA
        }
        filteredTrackingSteering = if (steering == 0.0) {
            0.0
        } else {
            filteredTrackingSteering * (1.0 - TRACK_STEERING_EMA_ALPHA) +
                steering * TRACK_STEERING_EMA_ALPHA
        }
        val now = System.currentTimeMillis()
        if (now - lastTrackingVectorSentMs < TRACK_VECTOR_SEND_INTERVAL_MS) return
        lastTrackingVectorSentMs = now
        val effectiveSteering = TrackingMotionPolicy.effectiveTrackingSteering(
            translation = filteredTrackingTranslation,
            filteredSteering = filteredTrackingSteering
        )
        sendFollowCommand(filteredTrackingTranslation, effectiveSteering)
    }

    private fun resetTrackingVectorState() {
        filteredTrackingTranslation = 0.0
        filteredTrackingSteering = 0.0
        lastTrackingVectorSentMs = 0L
        anklesConfirmed = false
        ankleVisibleFrames = 0
        ankleMissingFrames = 0
    }

    /**
     * Sau khi LSTM đã phát FALL, robot chỉ được lùi hoặc quay tại chỗ để lấy trọn bbox.
     * Không có nhánh FORWARD trong hàm này vì camera đơn không đo được khoảng cách va chạm.
     */
    private fun computeFallFramingCmd(
        target: SortTracker.TrackedObject,
        camW: Float,
        camH: Float
    ) {
        val bbox = target.bbox
        if (bbox.width() <= 0f || bbox.height() <= 0f ||
            bbox.right <= 0f || bbox.bottom <= 0f || bbox.left >= camW || bbox.top >= camH
        ) {
            fallFramingStableFrames = 0
            sendIfChanged(FollowCmd.STOP, forceImmediate = true)
            return
        }

        val left = bbox.left / camW
        val right = bbox.right / camW
        val top = bbox.top / camH
        val bottom = bbox.bottom / camH
        val centerX = bbox.centerX() / camW
        val centerY = bbox.centerY() / camH
        val occupancy = maxOf(bbox.width() / camW, bbox.height() / camH)
        val fullyInside = left >= FALL_FRAME_MARGIN_X &&
            right <= 1f - FALL_FRAME_MARGIN_X &&
            top >= FALL_FRAME_MARGIN_TOP &&
            bottom <= 1f - FALL_FRAME_MARGIN_BOTTOM
        val horizontallyCentered = centerX in FALL_FRAME_CENTER_LEFT..FALL_FRAME_CENTER_RIGHT
        val wellFramed = fullyInside && horizontallyCentered &&
            occupancy <= FALL_FRAME_MAX_OCCUPANCY

        if (fallFramingLocked) {
            val leftSafeZone = left >= FALL_FRAME_RELEASE_LEFT &&
                right <= FALL_FRAME_RELEASE_RIGHT
            val centerSafeZone = centerX in
                FALL_FRAME_RELEASE_CENTER_LEFT..FALL_FRAME_RELEASE_CENTER_RIGHT
            val sizeSafeZone = occupancy <= FALL_FRAME_RELEASE_MAX_OCCUPANCY
            if (leftSafeZone && centerSafeZone && sizeSafeZone) {
                sendIfChanged(FollowCmd.STOP, forceImmediate = true)
                Log.v(
                    TAG,
                    "FallFraming: locked cx=${String.format("%.2f", centerX)} " +
                        "cy=${String.format("%.2f", centerY)} " +
                        "size=${String.format("%.2f", occupancy)} -> STOP"
                )
                return
            }
            fallFramingLocked = false
            fallFramingStableFrames = 0
        }

        val cmd = when {
            // Bbox bị cắt hoặc quá lớn: lùi chậm trước để tránh đụng người.
            !fullyInside || occupancy > FALL_FRAME_MAX_OCCUPANCY -> FollowCmd.FALL_BACKWARD
            centerX < FALL_FRAME_CENTER_LEFT -> FollowCmd.PIVOT_LEFT
            centerX > FALL_FRAME_CENTER_RIGHT -> FollowCmd.PIVOT_RIGHT
            else -> FollowCmd.STOP
        }

        if (wellFramed) {
            fallFramingStableFrames++
            if (fallFramingStableFrames >= FALL_FRAME_STABLE_FRAMES) {
                fallFramingLocked = true
                Log.i(TAG, "FallFraming: bbox locked fully inside and centered")
            }
            // Dừng ngay khi vừa vào vùng đạt yêu cầu để không chạy quá vị trí.
            sendIfChanged(FollowCmd.STOP, forceImmediate = true)
        } else {
            fallFramingStableFrames = 0
            sendIfChanged(cmd)
        }

        Log.v(
            TAG,
            "FallFraming: cx=${String.format("%.2f", centerX)} " +
                "cy=${String.format("%.2f", centerY)} " +
                "size=${String.format("%.2f", occupancy)} inside=$fullyInside " +
                "stable=$fallFramingStableFrames/$FALL_FRAME_STABLE_FRAMES -> $cmd"
        )
    }

    /**
     * Entry point của Auto-Follow engine, được gọi sau mỗi khung hình YOLO đã xử lý.
     *
     * Bình thường dùng pose + bbox để bám. Khi một chuỗi FALL đang được xử lý hoặc hình học
     * vẫn kết luận người đang LYING, chuyển sang bbox framing: không tiến, chỉ lùi chậm/quay
     * tại chỗ để lấy trọn bbox gần tâm. Mất target trong chế độ này thì STOP ngay.
     */
    private fun computeAndSendFollowCmd(
        tracked: List<SortTracker.TrackedObject>,
        camW: Float,
        camH: Float,
        fallFramingActive: Boolean,
        posture: BodyPosture
    ) {
        val now = System.currentTimeMillis()
        // ── 1. Use the stable, quality-scored primary target ──────────────
        val target = tracked.find { it.trackId == lockedTrackId }

        // ── 2. Xử lý mất target ───────────────────────────────────────────
        if (target == null) {
            fallFramingStableFrames = 0
            if (fallFramingActive) {
                // Không giữ lệnh tiến cũ dù chỉ một frame khi đang xử lý té ngã.
                sendIfChanged(FollowCmd.STOP, forceImmediate = true)
                return
            }
            val elapsed = if (lastTargetSeenMs > 0L) now - lastTargetSeenMs else Long.MAX_VALUE
            if (elapsed > TARGET_LOST_TIMEOUT_MS) {
                Log.d(TAG, "Auto-Follow: target lost > ${TARGET_LOST_TIMEOUT_MS}ms → STOP")
                sendIfChanged(FollowCmd.STOP, forceImmediate = true)
            }
            return
        }
        lastTargetSeenMs = now

        if (fallFramingActive) {
            resetTrackingVectorState()
            if (!wasFallFramingActive) {
                wasFallFramingActive = true
                sendIfChanged(FollowCmd.STOP, forceImmediate = true)
                Log.i(TAG, "Auto-Follow: entering bbox framing -> immediate STOP")
                return
            }
            Log.v(TAG, "Auto-Follow: bbox framing active, posture=$posture")
            computeFallFramingCmd(target, camW, camH)
        } else {
            wasFallFramingActive = false
            fallFramingStableFrames = 0
            fallFramingLocked = false
            computeTrackingCmd(target, camW, camH)
        }
    }



    /** Lệnh rời rạc chỉ dùng trong bbox framing khi người nằm. */
    private fun sendIfChanged(cmd: FollowCmd, forceImmediate: Boolean = false) {
        if (cmd == prevFollowCmd) {
            sameFrameCount++
        } else {
            prevFollowCmd  = cmd
            sameFrameCount = 1
        }
        val stableFramesRequired = when (cmd) {
            FollowCmd.FALL_BACKWARD -> 2
            FollowCmd.PIVOT_LEFT, FollowCmd.PIVOT_RIGHT, FollowCmd.STOP -> 3
        }
        if (forceImmediate || sameFrameCount >= stableFramesRequired) {
            val (x, y) = followCmdToXY(cmd)
            sendFollowCommand(x, y)
        }
    }

    /** Map lệnh bbox framing sang hai kênh đã hiệu chỉnh trên robot thật. */
    private fun followCmdToXY(cmd: FollowCmd): Pair<Double, Double> = when (cmd) {
        FollowCmd.FALL_BACKWARD -> Pair( 0.22,   0.0)
        FollowCmd.PIVOT_LEFT    -> Pair( 0.0,   -0.42)
        FollowCmd.PIVOT_RIGHT   -> Pair( 0.0,    0.42)
        FollowCmd.STOP          -> Pair( 0.0,    0.0)
    }

    /** Truyền trực tiếp đến bộ điều khiển cục bộ, không ghi vector bám người lên Firebase. */
    private fun sendFollowCommand(x: Double, y: Double) {
        if (isAutoFollowEnabled) robotControlManager?.submitAutoFollowCommand(x, y)
    }

    // ─── Coordinate system detection ─────────────────────────────────────────
    private fun detectCoordSystem() {
        if (coordSystemDetected) return
        // Kiểm tra giá trị col 0 (cx hoặc x_min) để biết normalized hay pixel-space
        val numAnchors = if (isTransposedOutput) outDim2 else outDim1
        val maxCx = (0 until numAnchors).mapNotNull { getOutputVal(it, 0).takeIf { v -> v > 0f } }.maxOrNull() ?: 0f
        bboxNormalized = maxCx <= 2.0f
        coordSystemDetected = true
        Log.d(TAG, "Coord: ${if (bboxNormalized) "NORMALIZED" else "PIXEL"}, maxCx=$maxCx")
    }

    private fun Float.toSourceX(frame: LetterboxFrame): Float {
        val modelX = if (bboxNormalized) this * frame.inputWidth else this
        return ((modelX - frame.padX) / frame.scale).coerceIn(0f, frame.sourceWidth)
    }

    private fun Float.toSourceY(frame: LetterboxFrame): Float {
        val modelY = if (bboxNormalized) this * frame.inputHeight else this
        return ((modelY - frame.padY) / frame.scale).coerceIn(0f, frame.sourceHeight)
    }

    /**
     * Helper: lấy giá trị từ output tensor xử lý cả 2 format.
     *   Normal    [1, anchors, channels]: modelOutput[0][anchor][channel]
     *   Transposed[1, channels, anchors]: modelOutput[0][channel][anchor]
     */
    private fun getOutputVal(anchor: Int, channel: Int): Float =
        if (isTransposedOutput) modelOutput[0][channel][anchor]
        else modelOutput[0][anchor][channel]

    // ─── Decode output (format-agnostic) ─────────────────────────────────────
    /**
     * Xử lý cả 2 format model:
     *
     * E2E  [1, 300, 57]:  col 0..3 = xmin,ymin,xmax,ymax (corners)
     *                     col 4    = objectness
     *                     col 5    = class_conf
     *                     col 6..  = 17 keypoints × 3
     *
     * RAW  [1, 2100, 56]: col 0..3 = cx,cy,w,h (center format)
     *                     col 4    = class_conf (single value)
     *                     col 5..  = 17 keypoints × 3
     */
    private data class InternalDet(
        val rect     : RectF,
        val score    : Float,
        val classId  : Int,
        val keypoints: List<BoundingBoxOverlay.Keypoint>
    )

    private fun decodeAndFilter(frame: LetterboxFrame): List<InternalDet> {
        val results    = mutableListOf<InternalDet>()
        val numAnchors = if (isTransposedOutput) outDim2 else outDim1
        val camW = frame.sourceWidth

        for (d in 0 until numAnchors) {
            val score: Float
            val leftRaw: Float; val topRaw: Float
            val rightRaw: Float; val bottomRaw: Float
            val kpsBase: Int

            if (isRawOutput) {
                // RAW format: [cx, cy, w, h, conf, kp0x, kp0y, kp0v, ...]
                score = getOutputVal(d, 4)
                if (score < CONF_THRESHOLD) continue

                val cx = getOutputVal(d, 0)
                val cy = getOutputVal(d, 1)
                val w  = getOutputVal(d, 2)
                val h  = getOutputVal(d, 3)
                leftRaw   = cx - w / 2f
                topRaw    = cy - h / 2f
                rightRaw  = cx + w / 2f
                bottomRaw = cy + h / 2f
                kpsBase = 5  // keypoints bắt đầu tại col 5
            } else {
                // E2E format: [xmin, ymin, xmax, ymax, objectness, cls_conf, kp0x, ...]
                val objConf   = getOutputVal(d, 4)
                val classConf = getOutputVal(d, 5)
                score = maxOf(objConf, classConf)
                if (score < CONF_THRESHOLD) continue

                leftRaw   = getOutputVal(d, 0)
                topRaw    = getOutputVal(d, 1)
                rightRaw  = getOutputVal(d, 2)
                bottomRaw = getOutputVal(d, 3)
                kpsBase = 6  // keypoints bắt đầu tại col 6
            }

            val left   = leftRaw.toSourceX(frame)
            val top    = topRaw.toSourceY(frame)
            val right  = rightRaw.toSourceX(frame)
            val bottom = bottomRaw.toSourceY(frame)

            if (right <= left || bottom <= top) continue

            val finalLeft  = if (isFrontCamera) camW - right else left
            val finalRight = if (isFrontCamera) camW - left  else right

            val keypoints = List(NUM_KPS) { k ->
                val base   = kpsBase + k * 3
                val rawX   = getOutputVal(d, base).toSourceX(frame)
                val finalX = if (isFrontCamera) camW - rawX else rawX
                BoundingBoxOverlay.Keypoint(
                    x       = finalX,
                    y       = getOutputVal(d, base + 1).toSourceY(frame),
                    visible = getOutputVal(d, base + 2) > KPS_VIS_THRESH,
                    confidence = getOutputVal(d, base + 2)
                )
            }

            results.add(
                InternalDet(
                    rect      = RectF(finalLeft, top, finalRight, bottom),
                    score     = score,
                    classId   = 0,
                    keypoints = keypoints
                )
            )
        }
        return results
    }

    // ─── NMS ─────────────────────────────────────────────────────────────────
    private fun nms(dets: List<InternalDet>): List<InternalDet> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep   = mutableListOf<InternalDet>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { iou(best.rect, it.rect) > IOU_THRESHOLD }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iL = maxOf(a.left, b.left); val iT = maxOf(a.top,  b.top)
        val iR = minOf(a.right, b.right); val iB = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, iR - iL) * maxOf(0f, iB - iT)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val padW  = plane.rowStride / plane.pixelStride
        val bmp   = Bitmap.createBitmap(padW, image.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        if (cropped !== bmp) bmp.recycle()
        return cropped
    }

    /**
     * Chuyển I420 của WebRTC sang bitmap RGB đã downscale trước khi chạy YOLO.
     * Downscale ngay trong lúc đổi màu để tránh tạo bitmap 640x480 mỗi frame.
     */
    private fun videoFrameToBitmap(frame: VideoFrame): Bitmap? {
        val i420 = frame.buffer.toI420() ?: return null
        val sourceWidth = i420.width
        val sourceHeight = i420.height
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            i420.release()
            return null
        }

        val scale = minOf(1f, WEBRTC_AI_MAX_WIDTH.toFloat() / sourceWidth)
        val outputWidth = maxOf(1, (sourceWidth * scale).toInt())
        val outputHeight = maxOf(1, (sourceHeight * scale).toInt())
        val pixels = IntArray(outputWidth * outputHeight)

        try {
            val dataY = i420.dataY
            val dataU = i420.dataU
            val dataV = i420.dataV
            val strideY = i420.strideY
            val strideU = i420.strideU
            val strideV = i420.strideV

            var outputIndex = 0
            for (outY in 0 until outputHeight) {
                val sourceY = outY * sourceHeight / outputHeight
                val chromaY = sourceY / 2
                for (outX in 0 until outputWidth) {
                    val sourceX = outX * sourceWidth / outputWidth
                    val chromaX = sourceX / 2

                    val y = (dataY.get(sourceY * strideY + sourceX).toInt() and 0xFF)
                    val u = (dataU.get(chromaY * strideU + chromaX).toInt() and 0xFF) - 128
                    val v = (dataV.get(chromaY * strideV + chromaX).toInt() and 0xFF) - 128

                    val c = maxOf(0, y - 16)
                    val r = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
                    val g = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                    val b = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
                    pixels[outputIndex++] =
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        } finally {
            i420.release()
        }

        val bitmap = Bitmap.createBitmap(
            pixels,
            outputWidth,
            outputHeight,
            Bitmap.Config.ARGB_8888
        )
        val normalizedRotation = ((frame.rotation % 360) + 360) % 360
        if (normalizedRotation == 0) return bitmap

        val matrix = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun loadModelFile(name: String): ByteBuffer {
        val fd = requireContext().assets.openFd(name)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startFeaturesWithGrantedPermissions()

            val message = when {
                !cameraPermissions.all(::isPermissionGranted) &&
                    !blePermissions.all(::isPermissionGranted) ->
                    "Cần quyền Camera để hiển thị tracking và quyền Thiết bị ở gần để điều khiển robot."
                !cameraPermissions.all(::isPermissionGranted) ->
                    "Cần quyền Camera để hiển thị màn hình tracking."
                !blePermissions.all(::isPermissionGranted) ->
                    "Cần quyền Thiết bị ở gần để joystick kết nối với robot qua Bluetooth."
                else -> null
            }
            message?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
            }
        }

    private fun isPermissionGranted(permission: String) =
        ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED

    companion object { private const val TAG = "TrackingFragment" }
}
