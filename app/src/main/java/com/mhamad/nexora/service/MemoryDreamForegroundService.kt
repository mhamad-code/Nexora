package com.mhamad.nexora.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mhamad.nexora.MEMORY_DREAM_NOTIFICATION_CHANNEL_ID
import com.mhamad.nexora.R
import com.mhamad.nexora.RouteActivity
import com.mhamad.nexora.data.ai.MemoryDreamKeepAlive
import kotlin.uuid.Uuid

private const val TAG = "MemoryDreamFgs"

/**
 *
 */
class MemoryDreamForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "com.mhamad.nexora.action.MEMORY_DREAM_ACQUIRE"
        private const val ACTION_RELEASE = "com.mhamad.nexora.action.MEMORY_DREAM_RELEASE"
        private const val EXTRA_HOLD_ID = "hold_id"

        const val NOTIFICATION_ID = 2004

        fun acquire(context: Context, holdId: String): Boolean {
            val intent = Intent(context, MemoryDreamForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_HOLD_ID, holdId)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.w(TAG, "Unable to start memory dream foreground service", it)
            }.getOrDefault(false)
        }

        fun release(context: Context, holdId: String) {
            val intent = Intent(context, MemoryDreamForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_HOLD_ID, holdId)
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release memory dream foreground service", it)
            }
        }
    }

    private val holds = mutableSetOf<String>()
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> release(intent, startId)
            else -> stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        holds.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type=$fgsType)")
        holds.clear()
        stopService()
    }

    private fun acquire(intent: Intent) {
        val holdId = intent.getStringExtra(EXTRA_HOLD_ID) ?: return stopService()
        holds += holdId
        enterForeground()
    }

    private fun release(intent: Intent, startId: Int) {
        intent.getStringExtra(EXTRA_HOLD_ID)?.let(holds::remove)
        if (holds.isEmpty()) stopSelf(startId)
    }

    private fun enterForeground() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            holds.clear()
            stopSelf()
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, MEMORY_DREAM_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.memory_dreaming))
            .setContentText(getString(R.string.memory_dream_notification_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, RouteActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
}

fun memoryDreamKeepAlive(context: Context) = MemoryDreamKeepAlive {
    val holdId = Uuid.random().toString()
    val started = MemoryDreamForegroundService.acquire(context, holdId)
    AutoCloseable { if (started) MemoryDreamForegroundService.release(context, holdId) }
}
