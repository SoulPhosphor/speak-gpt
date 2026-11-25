/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
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
import org.teslasoft.assistant.imagegen.ImageGenerationEventLog
import org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.ui.activities.ChatActivity

/**
 * Short-lived foreground keep-alive for image requests. The process-level
 * image registry owns the network call; this service only keeps the process,
 * CPU, and Wi-Fi responsive when the user switches apps or turns the screen
 * off. Image generation is data synchronization, deliberately separate from
 * [GenerationForegroundService]'s media-playback contract.
 */
class ImageGenerationForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "image_generation_channel"
        private const val NOTIFICATION_ID = 9925
        private const val WAKE_LOCK_TAG = "PhosphorShines:ImageGeneration"
        private const val EXTRA_CHAT_ID = "chatId"

        private val lifecycleLock = Any()
        private val starts = ImageGenerationServiceStarts()
        private var readyService: ImageGenerationForegroundService? = null

        /** Starts one reference-counted keep-alive. False means Android
         * refused the service start and generation must continue without it. */
        fun begin(context: Context, chatId: String?): Boolean = synchronized(lifecycleLock) {
            starts.requested()
            val intent = Intent(context, ImageGenerationForegroundService::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                starts.refused()
                try {
                    Logger.log(
                        context.applicationContext,
                        "event",
                        "ImageGenerationService",
                        "error",
                        "keep-alive service refused to start: " +
                            "${e.javaClass.simpleName}: ${e.message} — " +
                            "image generation continues without screen-off protection"
                    )
                } catch (_: Throwable) { /* diagnostics must not break generation */ }
                false
            }
        }

        @Suppress("UNUSED_PARAMETER")
        fun end(context: Context): Unit = synchronized(lifecycleLock) {
            starts.released()
            // A pending startForegroundService must reach startForeground before it can be stopped.
            if (starts.idle) {
                val service = readyService
                readyService = null
                try {
                    service?.stopSelf()
                } catch (_: Exception) { /* already stopped */ }
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Android's time limit was reached for this run: logged once, and the keep-alive already released. */
    private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android's background execution time limit for data-sync services
     * (Android 15 and later). The service must stop within seconds or Android
     * crashes the app. Only the keep-alive stops (notification, wake and Wi-Fi
     * locks): no generation is cancelled, and each still reports how it ends.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (timedOut) return
        timedOut = true
        try {
            val jobs = ImageGenerationJobRegistry.activeJobIds().map { (chatId, imageId) ->
                DataSyncTimeoutLog.ImageJob(chatId, imageId)
            }
            Logger.log(
                applicationContext,
                ImageGenerationEventLog.ERROR_CHANNEL,
                "ImageGenerationService",
                "warning",
                DataSyncTimeoutLog.imageGeneration(jobs)
            )
        } catch (_: Throwable) { /* the stop below matters more than the log */ }
        synchronized(lifecycleLock) { if (readyService === this) readyService = null }
        releaseLocks()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) { /* already stopped */ }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannelIfNeeded()
        val notification = buildNotification(intent?.getStringExtra(EXTRA_CHAT_ID))
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
        } catch (e: Exception) {
            synchronized(lifecycleLock) {
                starts.delivered()
                if (readyService === this) readyService = null
            }
            try {
                Logger.log(
                    applicationContext,
                    "event",
                    "ImageGenerationService",
                    "error",
                    "startForeground failed: ${e.javaClass.simpleName}: ${e.message} — " +
                        "image generation continues without screen-off protection"
                )
            } catch (_: Throwable) { /* diagnostics must not crash the service */ }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val finishedBeforeStartup = synchronized(lifecycleLock) {
            starts.delivered()
            readyService = this
            if (starts.idle) {
                readyService = null
                stopSelfResult(startId)
                true
            } else false
        }
        if (finishedBeforeStartup) return START_NOT_STICKY
        timedOut = false
        acquireLocks()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(15 * 60 * 1000L)
            }
        }
        if (wifiLock?.isHeld != true) {
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    WAKE_LOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) { /* cellular/no Wi-Fi remains unaffected */ }
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.image_gen_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                description = getString(R.string.image_gen_notification_channel_desc)
            }
        )
    }

    private fun buildNotification(chatId: String?): Notification {
        val openIntent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("chatId", chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_image)
            .setContentTitle(getString(R.string.image_gen_notification_title))
            .setContentText(getString(R.string.image_gen_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
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

    override fun onDestroy() {
        synchronized(lifecycleLock) { if (readyService === this) readyService = null }
        releaseLocks()
        super.onDestroy()
    }
}
