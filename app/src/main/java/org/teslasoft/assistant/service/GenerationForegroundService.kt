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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Logger
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
        private const val EXTRA_READING = "reading"

        // Generations in flight. The service stops only when this drops to 0.
        private val activeGenerations = AtomicInteger(0)

        @Volatile private var activeService: GenerationForegroundService? = null
        @Volatile private var lastKeepAliveFailure: String? = null

        /** Actual state sampled before generation teardown on a failure. */
        fun diagnostics(): GenerationKeepAliveDiagnostics =
            activeService?.snapshotDiagnostics()
                ?: GenerationKeepAliveDiagnostics(
                    serviceRunning = false,
                    activeGenerations = activeGenerations.get(),
                    serviceAgeMs = 0L,
                    wakeLockHeld = false,
                    wifiLockHeld = false,
                    lastFailure = lastKeepAliveFailure
                )

        /**
         * Call when a keep-alive phase starts. Must be paired with [end].
         * [reading] = true means the phase is reading the reply aloud (TTS
         * playback) rather than streaming text, so the notification can say so;
         * a readback begin after the generation begin simply re-posts the
         * notification with the reading title while the ref count keeps the
         * service alive across both phases.
         */
        fun begin(context: Context, chatId: String?, chatName: String?, reading: Boolean = false) {
            activeGenerations.incrementAndGet()
            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_CHAT_NAME, chatName)
                putExtra(EXTRA_READING, reading)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                lastKeepAliveFailure = "${e.javaClass.simpleName}: ${e.message}"
                // Starting a foreground service can be refused (e.g. the app is
                // already in the background under strict OEM policies). The
                // generation itself must still proceed; it just loses the
                // keep-alive safety net — which must leave a persistent trace,
                // because "the reply died when the screen went off" is
                // undiagnosable without it. Ungated, one line per refusal.
                try {
                    Logger.log(context.applicationContext, "event", "GenerationService", "error",
                        "keep-alive service refused to start: ${e.javaClass.simpleName}: ${e.message} — " +
                                "generation continues without screen-off protection")
                } catch (_: Throwable) { /* logging must never break generation */ }
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
    @Volatile private var wifiManager: WifiManager? = null
    @Volatile private var wifiLock: WifiManager.WifiLock? = null
    private val wifiLockGuard = Any()
    private val lockScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wifiLockJob: Job? = null
    @Volatile private var serviceDestroyed = false
    @Volatile private var serviceStartedAtMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val chatName = intent?.getStringExtra(EXTRA_CHAT_NAME)
        val reading = intent?.getBooleanExtra(EXTRA_READING, false) == true

        createChannelIfNeeded()

        val notification = buildNotification(chatId, chatName, reading)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // mediaPlayback (not dataSync): this keep-alive now spans both the
                // text stream and the TTS readback that follows it. Reading a reply
                // aloud is audio playback, and mediaPlayback is exempt from the
                // daily aggregate cap Android 15+ puts on dataSync services. The
                // manifest declares the matching type + permission; mismatching the
                // two throws here on Android 14+.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            lastKeepAliveFailure = "${e.javaClass.simpleName}: ${e.message}"
            // If startForeground fails, bail out cleanly rather than crash;
            // the generation continues without the keep-alive. Persist the
            // reason (ungated) — this used to disappear without a trace.
            try {
                Logger.log(applicationContext, "event", "GenerationService", "error",
                    "startForeground failed: ${e.javaClass.simpleName}: ${e.message} — " +
                            "generation continues without screen-off protection")
            } catch (_: Throwable) { /* logging must never crash the service */ }
            stopSelf()
            return START_NOT_STICKY
        }

        activeService = this
        if (serviceStartedAtMs == 0L) serviceStartedAtMs = SystemClock.elapsedRealtime()
        lastKeepAliveFailure = null
        acquireLocks()
        // NOT sticky: a resurrected copy with no generation in flight would
        // just hold a wake lock for nothing.
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire(30 * 60 * 1000L)
                }
            } catch (t: Throwable) {
                recordKeepAliveFailure("CPU wake-lock acquisition failed", t)
            }
        }
        acquireWifiLockAsync()
    }

    /**
     * WifiManager construction can block on Android system services. Service lifecycle
     * callbacks run on the main thread, so resolve it once on IO and cache it here.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLockAsync() {
        if (serviceDestroyed || wifiLock?.isHeld == true || wifiLockJob?.isActive == true) return
        wifiLockJob = lockScope.launch {
            var acquiredLock: WifiManager.WifiLock? = null
            try {
                val wm = wifiManager ?: (
                    applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                ).also { wifiManager = it }
                if (!isActive || serviceDestroyed) return@launch

                acquiredLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    WAKE_LOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }

                synchronized(wifiLockGuard) {
                    if (isActive && !serviceDestroyed) {
                        wifiLock = acquiredLock
                        acquiredLock = null
                    }
                }
            } catch (t: Throwable) {
                recordKeepAliveFailure("Wi-Fi lock acquisition failed", t)
            } finally {
                acquiredLock?.let { lock ->
                    try {
                        if (lock.isHeld) lock.release()
                    } catch (_: Exception) { /* service already shutting down */ }
                }
            }
        }
    }

    private fun recordKeepAliveFailure(prefix: String, t: Throwable) {
        val detail = "$prefix: ${t.javaClass.simpleName}: ${t.message}"
        lastKeepAliveFailure = detail
        try {
            Logger.log(applicationContext, "event", "GenerationService", "warning", detail)
        } catch (_: Throwable) { /* diagnostics must never disturb generation */ }
    }

    private fun snapshotDiagnostics(): GenerationKeepAliveDiagnostics {
        val now = SystemClock.elapsedRealtime()
        val started = serviceStartedAtMs
        return GenerationKeepAliveDiagnostics(
            serviceRunning = activeService === this,
            activeGenerations = activeGenerations.get(),
            serviceAgeMs = if (started > 0L) (now - started).coerceAtLeast(0L) else 0L,
            wakeLockHeld = try { wakeLock?.isHeld == true } catch (_: Throwable) { false },
            wifiLockHeld = try { wifiLock?.isHeld == true } catch (_: Throwable) { false },
            lastFailure = lastKeepAliveFailure
        )
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) { /* ignore */ }
        wakeLock = null
        val heldWifiLock = synchronized(wifiLockGuard) {
            wifiLock.also { wifiLock = null }
        }
        try {
            if (heldWifiLock?.isHeld == true) heldWifiLock.release()
        } catch (_: Exception) { /* ignore */ }
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

    private fun buildNotification(chatId: String?, chatName: String?, reading: Boolean): Notification {
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
        val title = if (reading) {
            getString(R.string.voice_reading_notification_title)
        } else {
            getString(R.string.generation_notification_title)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            // Hang Up: a single tap that stops any readback and stops listening,
            // exactly like the in-app stop control. Delivered to the live
            // ChatActivity via a package-scoped broadcast (see
            // ChatActivity.ACTION_HANG_UP / hangUpReceiver).
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
        serviceDestroyed = true
        lockScope.cancel()
        releaseLocks()
        if (activeService === this) activeService = null
        serviceStartedAtMs = 0L
        super.onDestroy()
    }
}

data class GenerationKeepAliveDiagnostics(
    val serviceRunning: Boolean,
    val activeGenerations: Int,
    val serviceAgeMs: Long,
    val wakeLockHeld: Boolean,
    val wifiLockHeld: Boolean,
    val lastFailure: String?
) {
    fun asLogFields(): String =
        "serviceRunning=$serviceRunning activeGenerations=$activeGenerations " +
            "serviceAge=${serviceAgeMs}ms wakeLockHeld=$wakeLockHeld " +
            "wifiLockHeld=$wifiLockHeld lastFailure=${lastFailure ?: "none"}"
}
