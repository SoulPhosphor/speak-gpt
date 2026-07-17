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
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.ui.activities.ChatActivity

/**
 * Foreground service that keeps the hands-free conversation alive while the
 * screen is off. Four jobs:
 *   1. Run as a foregroundServiceType="microphone" service so the OS lets the
 *      mic stay open from the background.
 *   2. Hold a partial wake lock so the CPU doesn't sleep mid-recognition.
 *   3. Hold a Wi-Fi lock for the WHOLE conversation, not just while a reply
 *      streams. GenerationForegroundService's Wi-Fi lock covers each response,
 *      but between turns — while the app just listens with the screen off —
 *      nothing used to stop Android putting the Wi-Fi radio to sleep. The
 *      radio would then be down when the next turn's request went out, and the
 *      request died on DNS before Wi-Fi could wake ("Unable to resolve host",
 *      Network: none — owner report, July 10 2026, screen-off hands-free).
 *   4. Show a persistent notification (required by the OS for foreground
 *      services) that lets the user tap back into the chat.
 */
class HandsFreeService : Service() {

    companion object {
        private const val CHANNEL_ID = "hands_free_channel"
        private const val NOTIFICATION_ID = 9921
        private const val WAKE_LOCK_TAG = "PhosphorShines:HandsFree"

        // Liveness flag for ChatActivity's readback keep-alive decision. The
        // hands-free PREFERENCE being on does not mean this service is up —
        // it only runs while the mic loop is actually armed. A readback that
        // skips its own keep-alive because "hands-free covers it" while this
        // service is NOT running leaves the process with no foreground
        // protection at all, and the cached-apps freezer then kills it
        // mid-readback ([FREEZER BINDER ASYNC FULL]). True only between a
        // successful startForeground and onDestroy.
        @Volatile
        var isRunning: Boolean = false
            private set

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
    private var wifiLock: WifiManager.WifiLock? = null

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
        } catch (e: Exception) {
            // If startForeground fails (e.g. user revoked POST_NOTIFICATIONS,
            // or RECORD_AUDIO was revoked — a mic-typed foreground service
            // needs it on Android 14+), bail out cleanly rather than crashing
            // the app — but leave a persistent, ungated trace: this failure
            // means the hands-free session has NO screen-off keep-alive, and
            // it used to vanish without a line anywhere.
            try {
                Logger.log(applicationContext, "event", "HandsFreeService", "error",
                    "startForeground failed: ${e.javaClass.simpleName}: ${e.message} — " +
                            "hands-free keep-alive unavailable (screen-off listening may be cut off)")
            } catch (_: Throwable) { /* logging must never crash the service */ }
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        acquireWakeLock()
        acquireWifiLock()
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

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // FULL_HIGH_PERF is deprecated but is the only mode that keeps
            // Wi-Fi out of power-save while the screen is off — same choice,
            // for the same reason, as GenerationForegroundService. Held for
            // the whole listening session so the radio is already awake when
            // a turn's request goes out. Cellular is unaffected either way.
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { /* no Wi-Fi service; nothing to hold */ }
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
            // Hang Up: stops any readback and ends the listening loop in one tap,
            // the same as the in-app stop control. Routed to the live ChatActivity
            // via a package-scoped broadcast (ChatActivity.ACTION_HANG_UP).
            .addAction(R.drawable.ic_stop_recording, getString(R.string.notification_hang_up), buildHangUpIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun buildHangUpIntent(): PendingIntent {
        val intent = Intent(ChatActivity.ACTION_HANG_UP).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        isRunning = false
        releaseLocks()
        super.onDestroy()
    }
}
