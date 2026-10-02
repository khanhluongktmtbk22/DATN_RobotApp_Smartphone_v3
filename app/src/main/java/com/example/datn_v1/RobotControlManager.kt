package com.example.datn_v1

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * RobotControlManager
 *
 * Nhận lệnh bám người trực tiếp trong ứng dụng hoặc lệnh joystick qua Firebase,
 * chọn một nguồn theo chế độ bám người rồi truyền đến ESP32 qua BLE GATT Write.
 *
 * Giao thức BLE:
 *   - Tên thiết bị: "OhmniRobot" (phải khớp với tên quảng cáo của ESP32)
 *   - Service UUID: BLE_SERVICE_UUID
 *   - Characteristic UUID: BLE_CHAR_UUID (WRITE_NO_RESPONSE)
 *   - Chuỗi lệnh: "L<left_speed>R<right_speed>\n"
 *     Ví dụ: "L20.0R-20.0\n" (xoay phải), "L-15.0R-15.0\n" (lùi)
 *
 * Quy ước lệnh Firebase /robot_control đã được hiệu chỉnh theo robot thật:
 *   x ∈ [-1, 1]: kênh tiến/lùi
 *   y ∈ [-1, 1]: kênh hiệu chỉnh quay
 *
 * Hai motor được lắp đối xứng nên đây là target trục motor, không phải hai
 * vận tốc bánh cùng hệ dấu:
 *   effective_steering = deadzone(y) * 0.15
 *   left_motor_target  = (effective_steering + x) * MAX_SPEED
 *   right_motor_target = (effective_steering - x) * MAX_SPEED
 *
 * TrackingFragment quản lý vòng đời và đồng bộ chế độ bám người.
 */
class RobotControlManager(private val context: Context) {

    companion object {
        private const val TAG = "RobotCtrlMgr"

        // ── BLE IDs (phải khớp với firmware ESP32) ────────────────────────────
        const val BLE_DEVICE_NAME = "OhmniRobot"
        val BLE_SERVICE_UUID: UUID = UUID.fromString("4FAFC201-1FB5-459E-8FCC-C5C9C331914B")
        val BLE_CHAR_UUID: UUID    = UUID.fromString("BEB5483E-36E1-4688-B7F5-EA07361B26A8")

        // ── Tốc độ tối đa (rad/s) – khớp velocity_limit trong firmware ──────
        const val MAX_SPEED = 20.0f   // 50% of velocity_limit (40 rad/s)

        // Tracking gửi khoảng 8 lệnh/giây; 500 ms cho phép mất vài gói nhưng không để
        // robot tiếp tục chạy quá lâu khi pipeline camera hoặc Firebase bị gián đoạn.
        const val CMD_TIMEOUT_MS = 500L

        // Không thực thi snapshot Firebase cũ sau khi app/BLE reconnect.
        const val COMMAND_MAX_AGE_MS = 2_000L
        const val COMMAND_MAX_FUTURE_SKEW_MS = 5_000L

        // ── Scan timeout ──────────────────────────────────────────────────────
        const val SCAN_TIMEOUT_MS = 10_000L

        // ── Reconnect delay ───────────────────────────────────────────────────
        const val RECONNECT_DELAY_MS = 3_000L

        /**
         * Đổi hai kênh control thành target motor.
         *
         * Công thức này giữ nguyên calibration đã chạy đúng trên robot thật.
         */
        internal fun mapControlToMotorTargets(
            rawTranslation: Float,
            rawSteering: Float
        ): Pair<Float, Float> {
            val translation = rawTranslation.coerceIn(-1f, 1f)
            val steering = rawSteering.coerceIn(-1f, 1f)

            var effectiveSteering = steering
            // Calibration từ f2b771a: bỏ nhiễu rẽ nhỏ khi lệnh tiến/lùi đang mạnh.
            if (abs(steering) < 0.3f && abs(translation) > 0.3f) {
                effectiveSteering = 0f
            }
            effectiveSteering *= 0.15f

            val leftMotorTarget = (effectiveSteering + translation) * MAX_SPEED
            val rightMotorTarget = (effectiveSteering - translation) * MAX_SPEED
            return leftMotorTarget to rightMotorTarget
        }
    }

    // ─── Firebase ──────────────────────────────────────────────────────────────
    private var controlRef: DatabaseReference? = null
    private var controlListener: ValueEventListener? = null
    private var serverTimeOffsetRef: DatabaseReference? = null
    private var serverTimeOffsetListener: ValueEventListener? = null
    @Volatile private var serverTimeOffsetMs: Long? = null
    private val commandRouter = ControlCommandRouter()

    // ─── BLE ───────────────────────────────────────────────────────────────────
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var cmdCharacteristic: BluetoothGattCharacteristic? = null

    // ─── State ─────────────────────────────────────────────────────────────────
    private var isScanning = false
    private var isConnected = false
    private var isRunning   = false

    // ─── Handlers ──────────────────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())

    // Watchdog: dừng robot nếu không nhận lệnh trong CMD_TIMEOUT_MS
    private val watchdogRunnable = Runnable {
        Log.w(TAG, "Control timeout – sending stop")
        writeToEsp32("L0.0R0.0\n")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /** Kết nối BLE; đợi TrackingFragment cung cấp chế độ trước khi nhận lệnh. */
    fun start() {
        if (isRunning) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "Cannot start robot control without an authenticated pair")
            return
        }
        val database = FirebaseDatabase.getInstance()
        controlRef = database.getReference(FirebasePairPaths.robotControl(uid))
        serverTimeOffsetRef = database.getReference(".info/serverTimeOffset")
        isRunning = true
        Log.d(TAG, "RobotControlManager started for pair=$uid")
        startServerTimeSync()
        startBleScan()
    }

    /** null: chưa đọc được trạng thái chế độ, không cho phép chuyển động. */
    fun setAutoFollowEnabled(enabled: Boolean?) = onControlThread {
        if (!isRunning || !commandRouter.select(enabled)) return@onControlThread
        stopFirebaseListener()
        stopMotion()
        if (commandRouter.mode == ControlCommandRouter.Mode.REMOTE) startFirebaseListener()
    }

    /** Được gọi từ luồng suy luận; thời hạn cục bộ dùng đồng hồ đơn điệu. */
    fun submitAutoFollowCommand(x: Double, y: Double) {
        val generation = commandRouter.generation
        val createdAt = SystemClock.elapsedRealtime()
        onControlThread {
            if (!isRunning || !commandRouter.acceptsLocal(
                    generation, createdAt, SystemClock.elapsedRealtime(), CMD_TIMEOUT_MS
                )) return@onControlThread
            applyControl(x, y)
        }
    }

    private fun onControlThread(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) action()
        else mainHandler.post { action() }
    }

    private fun stopMotion() {
        mainHandler.removeCallbacks(watchdogRunnable)
        writeToEsp32("L0.0R0.0\n")
    }

    /** Chung hiệu chỉnh động cơ và timeout cho cả hai nguồn. */
    private fun applyControl(x: Double, y: Double) {
        if (!x.isFinite() || !y.isFinite()) {
            stopMotion()
            return
        }
        val translation = x.coerceIn(-1.0, 1.0).toFloat()
        val steering = y.coerceIn(-1.0, 1.0).toFloat()
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, CMD_TIMEOUT_MS)
        val (left, right) = mapControlToMotorTargets(translation, steering)
        writeToEsp32(buildCommand(left, right))
    }

    /** Dừng và giải phóng điều khiển khi TrackingFragment bị hủy. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        commandRouter.select(null)
        Log.d(TAG, "RobotControlManager stopped")

        // Gửi lệnh dừng trước khi ngắt
        writeToEsp32("L0.0R0.0\n")

        mainHandler.removeCallbacksAndMessages(null)
        stopFirebaseListener()
        stopServerTimeSync()
        controlRef = null
        stopBleScan()
        disconnectGatt()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Firebase
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Firebase cung cấp độ lệch giữa đồng hồ máy chủ và đồng hồ thiết bị.
     * Lệnh từ CaregiverApp và thời điểm kiểm tra tại RobotApp nhờ đó dùng cùng
     * một mốc thời gian, không phụ thuộc hai điện thoại có lệch giờ hay không.
     */
    private fun startServerTimeSync() {
        val ref = serverTimeOffsetRef ?: return
        serverTimeOffsetListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                serverTimeOffsetMs = snapshot.getValue(Long::class.java)
                Log.d(TAG, "Firebase server-time offset=$serverTimeOffsetMs ms")
            }

            override fun onCancelled(error: DatabaseError) {
                serverTimeOffsetMs = null
                Log.e(TAG, "Server-time synchronization cancelled: ${error.message}")
                if (commandRouter.mode == ControlCommandRouter.Mode.REMOTE) stopMotion()
            }
        }
        ref.addValueEventListener(serverTimeOffsetListener!!)
    }

    private fun stopServerTimeSync() {
        val ref = serverTimeOffsetRef
        if (ref != null) serverTimeOffsetListener?.let { ref.removeEventListener(it) }
        serverTimeOffsetListener = null
        serverTimeOffsetRef = null
        serverTimeOffsetMs = null
    }

    private fun estimatedServerNow(): Long? =
        serverTimeOffsetMs?.let { offset -> System.currentTimeMillis() + offset }

    private fun startFirebaseListener() {
        val ref = controlRef ?: return
        val generation = commandRouter.generation
        controlListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isRunning || commandRouter.mode != ControlCommandRouter.Mode.REMOTE ||
                    generation != commandRouter.generation) return
                val rawX = snapshot.child("x").getValue(Double::class.java) ?: 0.0
                val rawY = snapshot.child("y").getValue(Double::class.java) ?: 0.0
                val timestamp = snapshot.child("ts").getValue(Long::class.java)
                val now = estimatedServerNow()

                val isFresh = commandRouter.acceptsRemote(
                    generation, timestamp, now, COMMAND_MAX_AGE_MS, COMMAND_MAX_FUTURE_SKEW_MS
                )
                val isFinite = rawX.isFinite() && rawY.isFinite()

                if (!isFresh || !isFinite) {
                    Log.w(
                        TAG,
                        "Ignoring invalid/stale control: ts=$timestamp " +
                            "serverNow=$now age=${if (timestamp != null && now != null) now - timestamp else null} " +
                            "x=$rawX y=$rawY"
                    )
                    stopMotion()
                    return
                }

                applyControl(rawX, rawY)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase control cancelled: ${error.message}")
                if (isRunning && generation == commandRouter.generation &&
                    commandRouter.mode == ControlCommandRouter.Mode.REMOTE) stopMotion()
            }
        }
        ref.addValueEventListener(controlListener!!)
    }

    private fun stopFirebaseListener() {
        val ref = controlRef
        if (ref != null) controlListener?.let { ref.removeEventListener(it) }
        controlListener = null
    }

    /** Tạo chuỗi lệnh gửi đến ESP32: "L<left>R<right>\n" */
    private fun buildCommand(left: Float, right: Float): String {
        val l = String.format(Locale.US, "%.2f", left.coerceIn(-MAX_SPEED, MAX_SPEED))
        val r = String.format(Locale.US, "%.2f", right.coerceIn(-MAX_SPEED, MAX_SPEED))
        return "L${l}R${r}\n"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BLE – Scanning
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!checkBlePermissions()) {
            Log.w(TAG, "BLE permissions not granted – cannot scan")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }
        if (isScanning) return
        isScanning = true
        scanner = adapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filter = ScanFilter.Builder()
            .setDeviceName(BLE_DEVICE_NAME)
            .build()

        Log.d(TAG, "BLE scan started – looking for '$BLE_DEVICE_NAME'")
        scanner?.startScan(listOf(filter), settings, scanCallback)

        // Auto-stop scan after timeout
        mainHandler.postDelayed({
            if (isScanning && !isConnected) {
                stopBleScan()
                Log.w(TAG, "BLE scan timeout – device '$BLE_DEVICE_NAME' not found")
                scheduleReconnect()
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isRunning) return
            val device = result.device
            Log.d(TAG, "Found BLE device: ${device.name} [${device.address}]")
            stopBleScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: error=$errorCode")
            isScanning = false
            scheduleReconnect()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BLE – GATT Connection
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting GATT to ${device.address}...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected – discovering services...")
                    isConnected = true
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    isConnected = false
                    cmdCharacteristic = null
                    if (isRunning) scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = gatt.getService(BLE_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "BLE service $BLE_SERVICE_UUID not found on device")
                return
            }
            cmdCharacteristic = service.getCharacteristic(BLE_CHAR_UUID)
            if (cmdCharacteristic == null) {
                Log.e(TAG, "BLE characteristic $BLE_CHAR_UUID not found")
                return
            }
            Log.d(TAG, "BLE ready – characteristic found. Robot control active.")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT write failed: $status")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BLE – Write Command
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun writeToEsp32(cmd: String) {
        val char = cmdCharacteristic ?: return
        val gattConn = gatt ?: return
        if (!isConnected) return

        val bytes = cmd.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: mới hơn
            gattConn.writeCharacteristic(
                char,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gattConn.writeCharacteristic(char)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        cmdCharacteristic = null
        isConnected = false
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        Log.d(TAG, "Scheduling BLE reconnect in ${RECONNECT_DELAY_MS}ms...")
        mainHandler.postDelayed({
            if (isRunning && !isConnected) startBleScan()
        }, RECONNECT_DELAY_MS)
    }

    private fun checkBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }
}
