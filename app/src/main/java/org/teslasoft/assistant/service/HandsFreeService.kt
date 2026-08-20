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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.activities.ChatActivity

/**
 * Foreground service that keeps the hands-free conversation alive while the
 * screen is off. Four jobs:
 *   1. Run as a foregroundServiceType="microphone" service so the OS lets the
 *      mic stay open from the background.
 *   2. Maintain a bounded, renewable partial wake-lock lease so the CPU
 *      doesn't sleep mid-recognition during long sessions.
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

        @Volatile
        private var activeService: HandsFreeService? = null

        @Volatile private var lastWifiLockFailure: String? = null

        /**
         * Actual state, not the hands-free preference. Audio Health samples
         * this at transcription boundaries so a screen-off slowdown can be
         * read against the CPU protection that existed at that instant.
         */
        fun wakeLockDiagnostics(): HandsFreeWakeLockDiagnostics =
            activeService?.snapshotWakeLockDiagnostics()
                ?: HandsFreeWakeLockDiagnostics(
                    serviceRunning = false,
                    serviceAgeMs = 0L,
                    wakeLockHeld = false,
                    leaseAgeMs = 0L
                )

        /** Service + CPU/Wi-Fi protection sampled before hands-free teardown. */
        fun connectionDiagnostics(): HandsFreeConnectionDiagnostics =
            activeService?.snapshotConnectionDiagnostics()
                ?: HandsFreeConnectionDiagnostics(
                    serviceRunning = false,
                    serviceAgeMs = 0L,
                    wakeLockHeld = false,
                    wifiLockHeld = false,
                    wifiLockFailure = lastWifiLockFailure
                )

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

    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val wakeLockHandler = Handler(Looper.getMainLooper())
    private var wakeLockRenewal: Runnable? = null
    private var wakeLockSessionToken = 0L
    @Volatile private var serviceStartedAtMs = 0L
    @Volatile private var leaseAcquiredAtMs = 0L

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

        val newSession = !isRunning
        isRunning = true
        activeService = this
        if (newSession) {
            beginWakeLockSession()
        } else if (wakeLock?.isHeld != true) {
            // A duplicate start normally only refreshes the notification. If
            // the service is still alive but its lock is not, restore the CPU
            // protection and make the loss visible under Audio Health.
            replaceWakeLock("unexpected loss recovered on service start", unexpectedLoss = true)
        }
        acquireWifiLock()
        // NOT sticky: if the OS kills this service (or the app is closed), it must
        // stay dead. START_STICKY would have Android resurrect it with a null
        // intent and no Activity driving it — a zombie that re-holds the mic
        // foreground type + wake lock and starves other apps' voice/mic input.
        return START_NOT_STICKY
    }

    private fun beginWakeLockSession() {
        wakeLockSessionToken++
        val token = wakeLockSessionToken
        serviceStartedAtMs = SystemClock.elapsedRealtime()
        wakeLockRenewal?.let { wakeLockHandler.removeCallbacks(it) }
        wakeLockRenewal = null
        val acquired = replaceWakeLock("acquired", unexpectedLoss = false)
        scheduleWakeLockRenewal(
            token,
            if (acquired) WakeLockLeasePolicy.RENEW_INTERVAL_MS
            else WakeLockLeasePolicy.RETRY_INTERVAL_MS
        )
    }

    /**
     * Replace the current timed lock with a fresh 60-minute lease. The new
     * lock is acquired before the old one is released, so the 30-minute
     * renewal creates no unprotected gap. If acquisition fails, the old lock
     * is left untouched and the single callback retries shortly.
     */
    private fun replaceWakeLock(event: String, unexpectedLoss: Boolean): Boolean {
        val oldLock = wakeLock
        val oldHeld = try { oldLock?.isHeld == true } catch (_: Throwable) { false }
        val replacement = try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WakeLockLeasePolicy.LEASE_MS)
            }
        } catch (t: Throwable) {
            logWakeLockEvent(
                level = "warning",
                message = "CPU wake-lock $event failed: ${t.javaClass.simpleName}: ${t.message}; " +
                        "heldBefore=$oldHeld ${snapshotWakeLockDiagnostics().asLogFields()}"
            )
            return false
        }

        val heldAfter = try { replacement.isHeld } catch (_: Throwable) { false }
        if (!heldAfter) {
            try { replacement.release() } catch (_: Throwable) {}
            logWakeLockEvent(
                level = "warning",
                message = "CPU wake-lock $event did not become held; heldBefore=$oldHeld " +
                        snapshotWakeLockDiagnostics().asLogFields()
            )
            return false
        }

        wakeLock = replacement
        leaseAcquiredAtMs = SystemClock.elapsedRealtime()
        try {
            if (oldHeld) oldLock?.release()
        } catch (_: Throwable) { /* replacement is already held */ }

        val level = if (unexpectedLoss || !oldHeld && event == "renewed") "warning" else "info"
        val prefix = if (!oldHeld && event == "renewed") {
            "CPU wake lock was unexpectedly not held before scheduled renewal; renewed"
        } else {
            "CPU wake lock $event"
        }
        logWakeLockEvent(
            level = level,
            message = "$prefix: heldBefore=$oldHeld heldAfter=$heldAfter " +
                    "lease=${WakeLockLeasePolicy.LEASE_MS}ms " +
                    "renewEvery=${WakeLockLeasePolicy.RENEW_INTERVAL_MS}ms " +
                    snapshotWakeLockDiagnostics().asLogFields()
        )
        return heldAfter
    }

    private fun scheduleWakeLockRenewal(token: Long, delayMs: Long) {
        wakeLockRenewal?.let { wakeLockHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (!WakeLockLeasePolicy.shouldRenew(isRunning, token, wakeLockSessionToken)) return
                val renewed = replaceWakeLock("renewed", unexpectedLoss = false)
                if (WakeLockLeasePolicy.shouldRenew(isRunning, token, wakeLockSessionToken)) {
                    wakeLockHandler.postDelayed(
                        this,
                        if (renewed) WakeLockLeasePolicy.RENEW_INTERVAL_MS
                        else WakeLockLeasePolicy.RETRY_INTERVAL_MS
                    )
                }
            }
        }
        wakeLockRenewal = task
        wakeLockHandler.postDelayed(task, delayMs)
    }

    private fun cancelWakeLockRenewal() {
        wakeLockSessionToken++
        wakeLockRenewal?.let { wakeLockHandler.removeCallbacks(it) }
        wakeLockRenewal = null
    }

    private fun snapshotWakeLockDiagnostics(): HandsFreeWakeLockDiagnostics {
        val now = SystemClock.elapsedRealtime()
        val started = serviceStartedAtMs
        val acquired = leaseAcquiredAtMs
        return HandsFreeWakeLockDiagnostics(
            serviceRunning = isRunning && activeService === this,
            serviceAgeMs = if (started > 0L) (now - started).coerceAtLeast(0L) else 0L,
            wakeLockHeld = try { wakeLock?.isHeld == true } catch (_: Throwable) { false },
            leaseAgeMs = if (acquired > 0L) (now - acquired).coerceAtLeast(0L) else 0L
        )
    }

    private fun snapshotConnectionDiagnostics(): HandsFreeConnectionDiagnostics {
        val wake = snapshotWakeLockDiagnostics()
        return HandsFreeConnectionDiagnostics(
            serviceRunning = wake.serviceRunning,
            serviceAgeMs = wake.serviceAgeMs,
            wakeLockHeld = wake.wakeLockHeld,
            wifiLockHeld = try { wifiLock?.isHeld == true } catch (_: Throwable) { false },
            wifiLockFailure = lastWifiLockFailure
        )
    }

    private fun logWakeLockEvent(level: String, message: String) {
        val enabled = try {
            Preferences.getPreferences(applicationContext, "").getAudioHealthLogging()
        } catch (_: Throwable) { false }
        if (!enabled) return
        try {
            Logger.logAsync(applicationContext, "event", "AudioHealth", level, message)
        } catch (_: Throwable) { /* diagnostics must never disturb the service */ }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) {
            lastWifiLockFailure = null
            return
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            lastWifiLockFailure = null
        } catch (t: Throwable) {
            val detail = "${t.javaClass.simpleName}: ${t.message}"
            lastWifiLockFailure = detail
            try {
                Logger.log(applicationContext, "event", "HandsFreeService", "warning",
                    "Wi-Fi lock acquisition failed: $detail")
            } catch (_: Throwable) { /* diagnostics must never disturb hands-free */ }
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
        val before = snapshotWakeLockDiagnostics()
        cancelWakeLockRenewal()
        isRunning = false
        releaseLocks()
        val afterHeld = try { wakeLock?.isHeld == true } catch (_: Throwable) { false }
        logWakeLockEvent(
            level = "info",
            message = "CPU wake lock released: heldBefore=${before.wakeLockHeld} " +
                    "heldAfter=$afterHeld serviceAge=${before.serviceAgeMs}ms"
        )
        if (activeService === this) activeService = null
        serviceStartedAtMs = 0L
        leaseAcquiredAtMs = 0L
        super.onDestroy()
    }
}

/** Actual hands-free keep-alive state sampled at generation failure time. */
data class HandsFreeConnectionDiagnostics(
    val serviceRunning: Boolean,
    val serviceAgeMs: Long,
    val wakeLockHeld: Boolean,
    val wifiLockHeld: Boolean,
    val wifiLockFailure: String?
) {
    fun asLogFields(): String =
        "serviceRunning=$serviceRunning serviceAge=${serviceAgeMs}ms " +
            "wakeLockHeld=$wakeLockHeld wifiLockHeld=$wifiLockHeld " +
            "wifiLockFailure=${wifiLockFailure ?: "none"}"
}
