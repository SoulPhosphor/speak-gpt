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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.activities.ChatActivity

/**
 * Foreground service that keeps the hands-free conversation alive while the
 * screen is off. Three jobs:
 *   1. Run as a foregroundServiceType="microphone" service so the OS lets the
 *      mic stay open from the background.
 *   2. Hold a partial wake lock so the CPU doesn't sleep mid-recognition.
 *   3. Show a persistent notification (required by the OS for foreground
 *      services) that lets the user tap back into the chat.
 */
class HandsFreeService : Service() {

    companion object {
        private const val CHANNEL_ID = "hands_free_channel"
        private const val NOTIFICATION_ID = 9921
        private const val WAKE_LOCK_TAG = "PhosphorShines:HandsFree"

        private const val EXTRA_CHAT_ID = "chatId"
        private const val EXTRA_CHAT_NAME = "chatName"

        fun start(context: Context, chatId: String?, chatName: String?) {
            val intent = Intent(context, HandsFreeService::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_CHAT_NAME, chatName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HandsFreeService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val chatName = intent?.getStringExtra(EXTRA_CHAT_NAME)

        createChannelIfNeeded()

        val notification = buildNotification(chatId, chatName)
        try {
            // Typed startForeground (3-arg, with FOREGROUND_SERVICE_TYPE_MICROPHONE)
            // was added in API 29 and is what tells the OS this service is the
            // one using the mic — required for background mic access on
            // Android 11+ when the screen goes off. Previously gated to API 34,
            // which left Android 11/12/13 falling back to the untyped overload
            // and (per playstore reports) silently losing the mic mid-session.
            // The manifest already declares foregroundServiceType="microphone";
            // the runtime type is the authoritative signal the OS checks.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // If startForeground fails (e.g. user revoked POST_NOTIFICATIONS),
            // bail out cleanly rather than crashing the app.
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        // NOT sticky: if the OS kills this service (or the app is closed), it must
        // stay dead. START_STICKY would have Android resurrect it with a null
        // intent and no Activity driving it — a zombie that re-holds the mic
        // foreground type + wake lock and starves other apps' voice/mic input.
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // 1 hour safety cap; real conversations rarely run that long.
            // Released in onDestroy() the moment the service stops.
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) { /* ignore */ }
        wakeLock = null
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.hands_free_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            description = getString(R.string.hands_free_channel_desc)
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
            ?: getString(R.string.hands_free_notification_text)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_microphone)
            .setContentTitle(getString(R.string.hands_free_notification_title))
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
        releaseWakeLock()
        super.onDestroy()
    }
}
