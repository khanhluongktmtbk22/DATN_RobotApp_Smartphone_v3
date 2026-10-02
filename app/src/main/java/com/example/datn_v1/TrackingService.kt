package com.example.datn_v1

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData

/**
 * TrackingService — Foreground Service chạy camera + YOLO + LSTM fall detection ngầm.
 *
 * TODO (Phase 12): Di chuyển logic inference từ TrackingFragment vào đây.
 *
 * Hiện tại: stub cung cấp LiveData fallEvent để ChatbotFragment có thể compile và observe.
 * TrackingFragment vẫn chạy camera và khi phát hiện fall sẽ gọi TrackingService.reportFall()
 * để push event qua LiveData.
 */
class TrackingService : Service() {

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking_service_channel"
        private const val NOTIF_ID = 2002

        /** LiveData singleton cho ChatbotFragment observe */
        val fallEvent = MutableLiveData<FallEvent?>()
        val fallResolution = MutableLiveData<FallResolution?>()

        /**
         * Được gọi từ TrackingFragment khi phát hiện té ngã.
         * Phương thức này thread-safe (dùng postValue).
         */
        fun reportFall(event: FallEvent) {
            Log.d(TAG, "Fall reported: ${event.state}, prob=${event.probFall}")
            fallEvent.postValue(event)
        }

        fun reportFallResolution(trackId: Int, status: FallResolutionStatus) {
            Log.d(TAG, "Fall resolution: track=$trackId, status=$status")
            fallResolution.postValue(FallResolution(trackId, status))
        }

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.d(TAG, "TrackingService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "TrackingService stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dich vu giam sat",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Dang giam sat an toan cho nguoi cao tuoi"
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
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setContentTitle("Robot dang hoat dong")
            .setContentText("Dang giam sat an toan")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }
}
