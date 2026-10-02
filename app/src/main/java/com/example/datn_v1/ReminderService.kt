package com.example.datn_v1

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import java.util.*

/**
 * ReminderService — Foreground Service chạy ngầm, kiểm tra lịch nhắc nhở từ Firebase.
 *
 * Luồng:
 * 1. Mỗi 30 giây đọc Schedules/{uid} từ Firebase
 * 2. So sánh hour:minute + repeatMode với giờ hiện tại
 * 3. Khi đến giờ → tạo lời dẫn từ cách xưng hô và thông tin người chăm sóc
 * 4. Phát event REMINDER_TRIGGERED đến ChatbotFragment qua LiveData
 * 5. Ghi reminder_logs/{uid}/{logId} lên Firebase
 */
class ReminderService : Service() {

    companion object {
        private const val TAG = "ReminderService"
        private const val CHANNEL_ID = "reminder_service_channel"
        private const val NOTIF_ID = 2001
        private const val CHECK_INTERVAL_MS = 30_000L     // Kiểm tra mỗi 30 giây
        private const val TRIGGER_WINDOW_MS = 60_000L     // Cửa sổ trigger: ± 1 phút

        /** LiveData singleton cho ChatbotFragment observe */
        val reminderEvent = MutableLiveData<ReminderEvent?>()

        fun start(context: Context) {
            val intent = Intent(context, ReminderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReminderService::class.java))
        }
    }

    // ── Firebase ────────────────────────────────────────────────────────────
    private lateinit var schedulesRef: DatabaseReference
    private lateinit var userRef: DatabaseReference
    private lateinit var reminderLogsRef: DatabaseReference

    // ── Coroutine ──────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── State ──────────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var uid: String? = null
    private var userProfile: UserProfile? = null

    // Track: scheduleId → lastTriggeredMinute (tránh phát lại trong cùng phút)
    private val triggeredThisMinute = mutableMapOf<String, String>()

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "Chưa đăng nhập — ReminderService dừng")
            stopSelf()
            return
        }
        schedulesRef   = FirebaseDatabase.getInstance().getReference("Schedules/$uid")
        userRef        = FirebaseDatabase.getInstance().getReference("Users/$uid")
        reminderLogsRef= FirebaseDatabase.getInstance().getReference("reminder_logs/$uid")

        loadUserProfileThenStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "ReminderService stopped")
    }

    // ── Profile load ────────────────────────────────────────────────────────

    private fun loadUserProfileThenStart() {
        userRef.get().addOnSuccessListener { snapshot ->
            userProfile = UserProfile(
                fullName          = snapshot.child("fullName").getValue(String::class.java) ?: "",
                robotName         = snapshot.child("robotName").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: "Robot",
                robotPronoun      = snapshot.child("robotPronoun").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: "tôi",
                elderlyPronoun    = snapshot.child("elderlyPronoun").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: "Bạn",
                emergencyPhone    = snapshot.child("emergencyPhone").getValue(String::class.java) ?: "",
                caregiverName     = snapshot.child("caregiverName").getValue(String::class.java) ?: "",
                caregiverRelation = snapshot.child("caregiverRelation").getValue(String::class.java) ?: "",
                medicalConditions = snapshot.child("medicalConditions").getValue(String::class.java) ?: "",
                specialNotes      = snapshot.child("specialNotes").getValue(String::class.java) ?: "",
                hobbies           = snapshot.child("hobbies").getValue(String::class.java) ?: ""
            )
            scheduleNextCheck()
            Log.d(TAG, "ReminderService started for uid=$uid")
        }.addOnFailureListener {
            Log.e(TAG, "Không load được profile: ${it.message}")
            scheduleNextCheck()
        }
    }

    // ── Periodic check ──────────────────────────────────────────────────────

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkSchedules()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    private fun scheduleNextCheck() {
        handler.removeCallbacks(checkRunnable)
        handler.post(checkRunnable)
    }

    private fun checkSchedules() {
        val uid = this.uid ?: return
        schedulesRef.get().addOnSuccessListener { snapshot ->
            val nowCal = Calendar.getInstance()
            val nowHour   = nowCal.get(Calendar.HOUR_OF_DAY)
            val nowMinute = nowCal.get(Calendar.MINUTE)
            val nowDow    = nowCal.get(Calendar.DAY_OF_WEEK)   // 1=Sun..7=Sat
            val nowDom    = nowCal.get(Calendar.DAY_OF_MONTH)
            val todayStart= Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            for (child in snapshot.children) {
                val item = child.getValue(ScheduleRobotItem::class.java) ?: continue
                if (!item.active) continue

                val scheduleId = child.key ?: continue
                val minuteKey  = "$scheduleId-${nowHour}h${nowMinute}m"

                // Tránh trigger lại cùng phút
                if (triggeredThisMinute[scheduleId] == minuteKey) continue

                // Kiểm tra giờ:phút khớp
                val hourMatch   = item.hour == nowHour && item.minute == nowMinute
                if (!hourMatch) continue

                // Kiểm tra ngày theo repeatMode
                val shouldTrigger = when (item.repeatMode) {
                    "daily"   -> true
                    "weekly"  -> {
                        val savedDow = Calendar.getInstance().apply {
                            timeInMillis = item.dateMillis
                        }.get(Calendar.DAY_OF_WEEK)
                        savedDow == nowDow
                    }
                    "monthly" -> {
                        val savedDom = Calendar.getInstance().apply {
                            timeInMillis = item.dateMillis
                        }.get(Calendar.DAY_OF_MONTH)
                        savedDom == nowDom
                    }
                    else -> { // "none" — chỉ ngày cụ thể
                        item.dateMillis in todayStart until (todayStart + 24 * 60 * 60 * 1000)
                    }
                }

                if (shouldTrigger) {
                    triggeredThisMinute[scheduleId] = minuteKey
                    Log.d(TAG, "Trigger reminder: ${item.title} luc ${nowHour}h${nowMinute}")
                    triggerReminder(scheduleId, item)
                }
            }

            // Dọn map để tránh memory leak sau mỗi giờ
            if (nowMinute == 0) triggeredThisMinute.clear()
        }
    }

    // ── Trigger reminder ────────────────────────────────────────────────────

    private fun triggerReminder(scheduleId: String, item: ScheduleRobotItem) {
        serviceScope.launch {
            val profile = userProfile
            // Tạo log entry trên Firebase trước
            val logRef = reminderLogsRef.push()
            val logId  = logRef.key ?: return@launch

            val caregiverName     = profile?.caregiverName ?: ""
            val caregiverRelation = profile?.caregiverRelation ?: ""

            // Ghi log ban đầu
            val logData = mapOf(
                "scheduleId"       to scheduleId,
                "title"            to item.title,
                "note"             to item.note,
                "triggeredAt"      to System.currentTimeMillis(),
                "status"           to "delivered",
                "elderlyResponse"  to "",
                "retryCount"       to 0,
                "confirmedAt"      to 0L,
                "caregiverName"    to caregiverName,
                "caregiverRelation" to caregiverRelation
            )
            logRef.setValue(logData)

            val llmWrapper = ChatbotSpeechPolicy.reminderIntroduction(profile ?: UserProfile())

            // Gửi event đến ChatbotFragment
            val event = ReminderEvent(
                scheduleId        = scheduleId,
                title             = item.title,
                note              = item.note,
                llmWrapper        = llmWrapper,
                caregiverName     = caregiverName,
                caregiverRelation = caregiverRelation,
                timestamp         = System.currentTimeMillis(),
                logId             = logId
            )
            withContext(Dispatchers.Main) {
                reminderEvent.postValue(event)
            }
        }
    }

    // ── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dịch vụ nhắc nhở",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Theo dõi lịch nhắc nhở sức khỏe"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle("Robot dang hoat dong")
            .setContentText("Dang theo doi lich nhac nho")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }
}

/**
 * Model nhẹ để Robot App đọc ScheduleItem từ Firebase.
 * Chỉ cần các trường cần thiết để kiểm tra trigger.
 */
data class ScheduleRobotItem(
    val title: String = "",
    val note: String = "",
    val dateMillis: Long = 0L,
    val hour: Int = 8,
    val minute: Int = 0,
    val repeatMode: String = "none",
    val active: Boolean = true
)
