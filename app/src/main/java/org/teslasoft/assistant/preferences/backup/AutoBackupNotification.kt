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

package org.teslasoft.assistant.preferences.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.activities.MemoryBackupRestoreActivity

/**
 * The phone notification shown while an automatic backup runs (owner request,
 * Nov 18 2026): the existing "Creating Automatic Backup…" wording with a
 * moving progress bar, removed when the pass ends. The backup engine reports
 * no progress, so no percentage is shown. Tapping it opens Backup & Restore.
 * Best-effort: a notification problem never affects the backup.
 */
object AutoBackupNotification {

    private const val CHANNEL_ID = "automatic_backup_channel"
    private const val NOTIFICATION_ID = 9924

    fun show(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CHANNEL_ID) == null
            ) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.backup_auto_section),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                    }
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MemoryBackupRestoreActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_file_save)
                .setContentTitle(context.getString(R.string.backup_auto_status_creating))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) { /* the backup matters more than its notification */ }
    }

    fun hide(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) { /* best-effort */ }
    }
}
