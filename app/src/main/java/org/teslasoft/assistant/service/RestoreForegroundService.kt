/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.ChatRestoreManager
import java.io.File

/**
 * Foreground lifetime for a Restore From Backup run.
 *
 * The whole-chat-set replacement ([ChatRestoreManager.restoreFromArchive]) is a
 * long, destructive operation, so it runs here rather than tied to an Activity:
 * the ongoing notification keeps it alive if the user leaves the screen, and if
 * the app is closed the restore journal plus `resumeIfPending` at the next
 * launch finish an interrupted swap. This is the one approved caller of the
 * restore engine (owner-approved reachable restore, replace-only); the engine's
 * no-reachable-caller guard whitelists exactly this file.
 *
 * On success the app MUST be fully restarted before any chat is read or written
 * — live SecurePrefs/SharedPreferences handles still cache the pre-restore
 * files — so this service relaunches the app and ends the process. On failure it
 * broadcasts the engine's reason so the live screen can show it; nothing was
 * changed, so a lost broadcast (screen already gone) costs only the message.
 */
class RestoreForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "restore_operations"
        private const val NOTIFICATION_ID = 9941
        private const val EXTRA_ARCHIVE_PATH = "archivePath"

        /** Package-scoped result broadcast the backup/restore screen listens for. */
        const val ACTION_RESTORE_FAILED = "org.teslasoft.assistant.action.RESTORE_FAILED"
        const val EXTRA_FAILURE_DETAIL = "detail"

        /** Start a restore of the archive already staged at [archivePath]
         *  (a private cache copy of the picked file — the service cannot read
         *  the picker's content URI itself). */
        fun start(context: Context, archivePath: String) {
            val intent = Intent(context, RestoreForegroundService::class.java)
                .putExtra(EXTRA_ARCHIVE_PATH, archivePath)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) { /* caller shows the failure path if this throws */ }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else startForeground(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val path = intent?.getStringExtra(EXTRA_ARCHIVE_PATH)
        if (path.isNullOrEmpty()) {
            finishWithFailure(null, deleteArchive = null)
            return START_NOT_STICKY
        }

        Thread {
            val archive = File(path)
            val result = try {
                ChatRestoreManager.restoreFromArchive(applicationContext, archive)
            } catch (e: Exception) {
                ChatRestoreManager.Result(false, e.javaClass.simpleName)
            }
            if (result.ok) {
                // The swap is committed; the process must not keep running on the
                // old cached preference handles. Relaunch and exit.
                runCatching { archive.delete() }
                restartApp()
            } else {
                finishWithFailure(result.detail, deleteArchive = archive)
            }
        }.start()

        // Not sticky: an interrupted run is finished by resumeIfPending at the
        // next launch, not by the system restarting this service with no intent.
        return START_NOT_STICKY
    }

    private fun finishWithFailure(detail: String?, deleteArchive: File?) {
        deleteArchive?.let { runCatching { it.delete() } }
        sendBroadcast(
            Intent(ACTION_RESTORE_FAILED)
                .setPackage(packageName)
                .putExtra(EXTRA_FAILURE_DETAIL, detail)
        )
        stopForegroundCompat()
        stopSelf()
    }

    private fun restartApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            runCatching { startActivity(launch) }
        }
        stopForegroundCompat()
        stopSelf()
        Runtime.getRuntime().exit(0)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else stopForeground(true)
        } catch (_: Exception) { /* already stopped */ }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.chat_restore_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false); setSound(null, null) }
            )
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subject)
            .setContentTitle(getString(R.string.chat_restore_progress))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
}
