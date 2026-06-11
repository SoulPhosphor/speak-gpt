/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.activities.ChatActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service held only while a response is being generated, so the
 * stream survives the user switching apps or turning the screen off.
 *
 * Without it, the moment the activity leaves the foreground the OS may freeze
 * the process or let Wi-Fi power-save drop the socket, and the request dies
 * mid-stream with "Software caused connection abort". While this service is
 * running the app keeps foreground importance, the CPU is held awake by a
 * partial wake lock, and a Wi-Fi lock keeps the radio out of power-save.
 *
 * The actual network call stays in the activity's coroutine; this service is
 * purely a keep-alive. It is reference counted so overlapping generations
 * (e.g. chat + assistant overlay) don't release each other's hold.
 */
class GenerationForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "generation_channel"
        private const val NOTIFICATION_ID = 9922
        private const val WAKE_LOCK_TAG = "PhosphorShines:Generation"

        private const val EXTRA_CHAT_ID = "chatId"
        private const val EXTRA_CHAT_NAME = "chatName"

        // Generations in flight. The service stops only when this drops to 0.
        private val activeGenerations = AtomicInteger(0)

        /** Call when a generation starts. Must be paired with [end]. */
        fun begin(context: Context, chatId: String?, chatName: String?) {
            activeGenerations.incrementAndGet()
            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_CHAT_NAME, chatName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Starting a foreground service can be refused (e.g. the app is
                // already in the background under strict OEM policies). The
                // generation itself must still proceed; it just loses the
                // keep-alive safety net.
                activeGenerations.decrementAndGet()
            }
        }

        /** Call when a generation finishes (success, error, or cancellation). */
        fun end(context: Context) {
            val remaining = activeGenerations.decrementAndGet()
            if (remaining <= 0) {
                activeGenerations.set(0)
                try {
                    context.stopService(Intent(context, GenerationForegroundService::class.java))
                } catch (_: Exception) { /* already stopped */ }
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val chatName = intent?.getStringExtra(EXTRA_CHAT_NAME)

        createChannelIfNeeded()

        val notification = buildNotification(chatId, chatName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // If startForeground fails, bail out cleanly rather than crash;
            // the generation continues without the keep-alive.
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        // NOT sticky: a resurrected copy with no generation in flight would
        // just hold a wake lock for nothing.
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                // 30 minute safety cap; a single response never takes that
                // long. Released in onDestroy() the moment generation ends.
                acquire(30 * 60 * 1000L)
            }
        }
        if (wifiLock?.isHeld != true) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                // FULL_HIGH_PERF is deprecated but is the only mode that keeps
                // Wi-Fi out of power-save while the screen is off; the
                // suggested LOW_LATENCY replacement is only honored for
                // foreground apps with the screen on — exactly the situation
                // this service exists to survive.
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) { /* no Wi-Fi service; cellular path is unaffected */ }
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) { /* ignore */ }
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) { /* ignore */ }
        wifiLock = null
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.generation_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            description = getString(R.string.generation_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(chatId: String?, chatName: String?): Notification {
        val openIntent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("chatId", chatId)
            putExtra("name", chatName)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = chatName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.generation_notification_text)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(getString(R.string.generation_notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }
}
