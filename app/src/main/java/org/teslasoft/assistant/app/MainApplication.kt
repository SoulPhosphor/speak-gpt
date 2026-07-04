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

package org.teslasoft.assistant.app

import android.app.Application
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import cat.ereza.customactivityoncrash.config.CaocConfig
import com.google.android.material.color.DynamicColors
import org.conscrypt.Conscrypt
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.memory.MemoryExporter
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager
import java.security.Security

/**
 * Called when the application is starting up. This method is responsible for setting up
 * the app and any necessary components.
 *
 * This implementation calls the [onCreate] method of the superclass [Application] and
 * applies dynamic colors to the activities of the app using the [DynamicColors] class.
 *
 * @see [Application.onCreate]
 * @see [DynamicColors.applyToActivitiesIfAvailable]
 */
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        DynamicColors.applyToActivitiesIfAvailable(this)
        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && GlobalPreferences.getPreferences(this).getAmoledPitchBlack())

        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Do NOT clear the event log on startup. Application.onCreate runs every
        // time Android recreates the process (leaving the app, screen off, memory
        // pressure), so clearing here silently erased the Voice Debug Log between
        // sessions — the "VAD logging is on but the log is empty" bug. The log
        // self-limits via Logger.trimByEntries (~1000 entries / 7 days for the
        // voice channel); clearing stays user-driven via the button in LogsActivity.

        // Record *why the previous process died* (low memory, force-stop, crash)
        // into the Voice Debug Log. A hard kill runs no code on the way out, so
        // this after-the-fact query is the only way a screen-off readback that
        // was killed mid-sentence leaves any trace. Deduped + best-effort inside.
        Logger.logLastExitReason(this)

        // Companion memory store housekeeping (only once the store exists —
        // nothing here may create the encrypted database as a side effect):
        // integrity_check surfaced loudly per the spec, then the rotating
        // automatic backup if one is due. Off the main thread; app start must
        // not wait on SQLCipher.
        Thread {
            try {
                if (MemoryStore.isProvisioned(this)) {
                    val problem = MemoryStore.getInstance(this).integrityCheck()
                    if (problem != null) {
                        Logger.log(this, "event", "MemoryStore", "error", "Memory store integrity check failed: $problem")
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(this, getString(R.string.memory_integrity_failed), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        MemoryExporter.autoExportIfDue(this)
                    }
                }
            } catch (e: Exception) {
                Logger.log(this, "event", "MemoryStore", "error", "Memory store startup check failed: ${e.message}")
            }
        }.start()

        CaocConfig.Builder.create()
            .backgroundMode(CaocConfig.BACKGROUND_MODE_SHOW_CUSTOM)
            .enabled(true)
            .showErrorDetails(true)
            .showRestartButton(false)
            .logErrorOnRestart(true)
            .trackActivities(true)
            .minTimeBetweenCrashesMs(3000)
            .errorDrawable(R.mipmap.ic_launcher_round)
            .restartActivity(null)
            .errorActivity(org.teslasoft.core.CrashHandlerActivity::class.java)
            .eventListener(null)
            .customCrashDataCollector(null)
            .apply()
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }
}
