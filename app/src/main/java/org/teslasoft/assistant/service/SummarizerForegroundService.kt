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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.activities.ChatActivity
import org.teslasoft.assistant.util.summarizer.SummarizerController
import org.teslasoft.assistant.util.summarizer.SummarizerControllerRegistry
import java.util.concurrent.ConcurrentHashMap

/** Foreground lifetime and notification surface for active summary work. */
class SummarizerForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "summarizer_operations"
        private const val NOTIFICATION_ID = 9931
        private const val ACTION_UPDATE = "summarizer.update"
        private const val ACTION_CANCEL = "summarizer.cancel"
        private const val EXTRA_CHAT_ID = "chatId"
        private const val EXTRA_CHAT_NAME = "chatName"
        private const val EXTRA_KIND = "kind"
        private data class Notice(val name: String, val kind: SummarizerController.OperationKind)
        private val active = ConcurrentHashMap<String, Notice>()

        fun begin(context: Context, chatId: String, chatName: String, kind: SummarizerController.OperationKind) {
            active[chatId] = Notice(chatName, kind)
            val intent = Intent(context, SummarizerForegroundService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_CHAT_NAME, chatName)
                .putExtra(EXTRA_KIND, kind.name)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) { /* operation continues without notification */ }
        }

        fun end(context: Context, chatId: String) {
            active.remove(chatId)
            val remaining = active.entries.firstOrNull()
            if (remaining == null) {
                try { context.stopService(Intent(context, SummarizerForegroundService::class.java)) }
                catch (_: Exception) { /* already stopped */ }
            } else {
                begin(context, remaining.key, remaining.value.name, remaining.value.kind)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        if (intent?.action == ACTION_CANCEL) {
            SummarizerControllerRegistry.cancel(chatId)
            return START_NOT_STICKY
        }
        createChannel()
        val chatName = intent?.getStringExtra(EXTRA_CHAT_NAME).orEmpty()
        val kind = runCatching {
            SummarizerController.OperationKind.valueOf(intent?.getStringExtra(EXTRA_KIND).orEmpty())
        }.getOrDefault(SummarizerController.OperationKind.COMPACTING)
        val notification = notification(chatId, chatName, kind)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.summarizer_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false); setSound(null, null) }
            )
        }
    }

    private fun notification(
        chatId: String,
        chatName: String,
        kind: SummarizerController.OperationKind
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            31,
            Intent(this, ChatActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("name", chatName)
                .setAction(Intent.ACTION_VIEW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this,
            32,
            Intent(this, SummarizerForegroundService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_CHAT_ID, chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = getString(
            if (kind == SummarizerController.OperationKind.COMPACTING) {
                R.string.compaction_notification_text
            } else R.string.summarizer_notification_text,
            chatName.ifBlank { getString(R.string.label_untitled_chat) }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subject)
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.btn_cancel), cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }
}
