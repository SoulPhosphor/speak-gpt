/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.index.BackgroundIndexingContent
import org.teslasoft.assistant.conversation.index.BackgroundIndexingRule
import org.teslasoft.assistant.conversation.index.BackgroundIndexingStep
import org.teslasoft.assistant.conversation.index.BackgroundStopReason
import org.teslasoft.assistant.conversation.index.ConversationIndexRuntime

/**
 * Conversation indexing after the app is closed (memory development plan
 * Step 13; G.14, G.16a; Owner, 2026-11-24). A "data sync" foreground service
 * that keeps the app's own indexing running while a catch-up with work left
 * continues, with the Indexing Conversations notification showing the same
 * progress as the chat header's icon. It does no indexing itself: the work
 * stays on the index's threads, so foreground and background never process
 * the same catch-up twice.
 *
 * It is started only by [AppBackgroundIndexing] while the app leaves the
 * screen. About every 10 seconds the progress is read again and the next step
 * is worked out on the index's thread, after the work handed to it before. It
 * stops when the catch-up ends, the user paused before closing, the setting
 * or the model went away, a rebuild failed, the app opened, or Android's time
 * limit was reached ([onTimeout]); finished work is always kept.
 */
class ConversationIndexingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "conversation_indexing"
        private const val NOTIFICATION_ID = 9951
        private const val PAUSED_NOTIFICATION_ID = 9952
        private const val UPDATE_MILLIS = 10_000L
        private const val WAKE_LOCK_TAG = "PhosphorShines:ConversationIndexing"
        /** Renewed at every update (an update can wait behind a long rebuild); a service that stops updating lets the phone sleep within this time. */
        private const val WAKE_LOCK_MILLIS = 10 * 60 * 1000L

        /** How often the progress is read again; shorter only in tests. */
        @Volatile internal var updateMillis = UPDATE_MILLIS
        /** Tests: replaces the next step worked out from the app's indexing. */
        @Volatile internal var stepForTest: (() -> BackgroundIndexingStep)? = null

        // Main thread only.
        private var running: ConversationIndexingForegroundService? = null
        private var stopWanted: BackgroundStopReason? = null

        /** Whether a run of the service is going (main thread). */
        internal val isRunning: Boolean get() = running != null

        /** Asks Android to start the service; throws what Android throws. Main thread. */
        fun start(context: Context) {
            val intent = Intent(context, ConversationIndexingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            // Only a start Android accepted replaces an earlier start's stop request.
            stopWanted = null
        }

        /**
         * Stops the service for [reason]. A start still on its way stops as
         * soon as it reaches startForeground, which Android requires first.
         * Main thread.
         */
        fun requestStop(reason: BackgroundStopReason) {
            val service = running
            if (service != null) service.stop(reason) else stopWanted = reason
        }

        /** The approved notice that Android's limit paused indexing until the app is opened. */
        fun showPausedNotice(context: Context) {
            try {
                createChannel(context)
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_sync)
                    .setContentTitle(context.getString(R.string.conversation_indexing_title))
                    .setContentText(context.getString(R.string.conversation_indexing_notification_paused))
                    .setStyle(NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.conversation_indexing_notification_paused)))
                    .setContentIntent(openApp(context))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .build()
                context.getSystemService(NotificationManager::class.java)?.notify(PAUSED_NOTIFICATION_ID, notification)
            } catch (_: Exception) {
                // Without the notification permission Android shows nothing; indexing continues when the app opens.
            }
        }

        fun clearPausedNotice(context: Context) {
            try {
                context.getSystemService(NotificationManager::class.java)?.cancel(PAUSED_NOTIFICATION_ID)
            } catch (_: Exception) { }
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.conversation_indexing_title),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }

        /** Opens the app through its normal start, as the launcher does. */
        private fun openApp(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return PendingIntent.getActivity(
                context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val update = Runnable { update() }
    private var stopped = true
    private var lastStartId = 0
    private var shown: BackgroundIndexingContent? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Android requires startForeground for every start, even one that stops at once.
        val first = currentContent()
        try {
            createChannel(this)
            val notification = notification(first)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            if (stopped) {
                stopSelf(startId)
                AppBackgroundIndexing.controller(this).startFailed(e)
            }
            return START_NOT_STICKY
        }
        if (!stopped) return START_NOT_STICKY
        stopped = false
        shown = first
        running = this
        AppBackgroundIndexing.controller(this).serviceStarted()
        val wanted = stopWanted
        stopWanted = null
        if (wanted != null) {
            stop(wanted)
            return START_NOT_STICKY
        }
        update()
        // Not restarted by Android after the process ends: indexing continues when the app is opened.
        return START_NOT_STICKY
    }

    /** Android's time limit for data-sync services (Android 15 and later): stop at once; finished work is kept. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stop(BackgroundStopReason.TIME_LIMIT)
    }

    override fun onDestroy() {
        if (!stopped) {
            // Destroyed without one of the stops above.
            end()
            AppBackgroundIndexing.controller(this).serviceFailed(
                IllegalStateException("the service was destroyed before indexing stopped")
            )
        }
        super.onDestroy()
    }

    /** Reads the progress again and works out the next step on the index's thread. Main thread. */
    private fun update() {
        if (stopped) return
        try {
            renewWakeLock()
            val progress = ConversationIndexRuntime.progress(applicationContext)
            progress.refresh()
            val controller = AppBackgroundIndexing.controller(this)
            ConversationIndexRuntime.onIndexThread {
                val step = try {
                    stepForTest?.invoke() ?: BackgroundIndexingRule.next(
                        progress.display.value, progress.conditions(), progress.isPaused, controller.appVisible,
                        progress.isStalled, controller.continuedByService
                    )
                } catch (e: Exception) {
                    handler.post { fail(e) }
                    return@onIndexThread
                }
                handler.post { apply(step) }
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun apply(step: BackgroundIndexingStep) {
        if (stopped) return
        when (step) {
            is BackgroundIndexingStep.Stop -> stop(step.reason)
            is BackgroundIndexingStep.Show -> {
                if (step.content != shown) {
                    shown = step.content
                    try {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(step.content))
                    } catch (_: Exception) {
                        // The indexing matters more than the notification's number.
                    }
                }
                handler.postDelayed(update, updateMillis)
            }
        }
    }

    private fun stop(reason: BackgroundStopReason) {
        if (stopped) return
        end()
        AppBackgroundIndexing.controller(this).serviceStopped(reason)
    }

    private fun fail(e: Exception) {
        if (stopped) return
        end()
        AppBackgroundIndexing.controller(this).serviceFailed(e)
    }

    /** Ends this run; a newer start asked for meanwhile keeps the service. */
    private fun end() {
        stopped = true
        if (running === this) running = null
        handler.removeCallbacks(update)
        releaseWakeLock()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) { }
        stopSelf(lastStartId)
    }

    /** The content to start with, from the progress as it is now; null shows the title alone. */
    private fun currentContent(): BackgroundIndexingContent? = try {
        stepForTest?.let { (it() as? BackgroundIndexingStep.Show)?.content } ?: ConversationIndexRuntime.progressIfCreated()?.let { progress ->
            (BackgroundIndexingRule.next(
                progress.display.value, progress.conditions(), progress.isPaused, appVisible = false,
                stalled = progress.isStalled,
                continued = AppBackgroundIndexing.controller(this).continuedByService
            ) as? BackgroundIndexingStep.Show)?.content
        }
    } catch (_: Exception) {
        null
    }

    /** Progress counts only, never conversation text. */
    private fun notification(content: BackgroundIndexingContent?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(getString(R.string.conversation_indexing_title))
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (content) {
            null -> Unit
            BackgroundIndexingContent.Counting -> builder
                .setContentText(getString(R.string.conversation_indexing_counting))
                .setProgress(0, 0, true)
            BackgroundIndexingContent.Rebuilding -> builder
                .setContentText(getString(R.string.conversation_indexing_notification_rebuilding))
                .setProgress(0, 0, true)
            is BackgroundIndexingContent.Percent -> builder
                .setContentText(getString(R.string.conversation_indexing_notification_percent, content.percent))
                .setProgress(100, content.percent, false)
        }
        return builder.build()
    }

    /** The phone may sleep with the screen off; indexing continues while the service runs. */
    private fun renewWakeLock() {
        try {
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(WAKE_LOCK_MILLIS)
        } catch (_: Exception) { }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) { }
        wakeLock = null
    }
}
