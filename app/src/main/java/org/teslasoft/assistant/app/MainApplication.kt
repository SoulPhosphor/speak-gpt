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
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import cat.ereza.customactivityoncrash.config.CaocConfig
import com.google.android.material.color.DynamicColors
import org.conscrypt.Conscrypt
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.RenameJournal
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.StartupHealth
import org.teslasoft.assistant.preferences.backup.AutoBackupController
import org.teslasoft.assistant.preferences.backup.AutoBackupScheduling
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.backup.StartupDatabaseCheck
import org.teslasoft.assistant.preferences.memory.MemoryExporter
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.TranscriptRecorder
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.util.MemoryDiagnostics
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

    // Opt-in app-wide memory heartbeat (Memory usage logging, off by default).
    // A dedicated background thread so the sample — which reads /proc and PSS —
    // never touches the main thread. The runnable always re-posts and only
    // *writes* when the toggle is on, so flipping the switch mid-session starts
    // logging without an app restart, and flipping it off stops the writes.
    private var memSampleThread: HandlerThread? = null
    private var memSampleHandler: Handler? = null
    private val memSampleRunnable = object : Runnable {
        override fun run() {
            try {
                if (Preferences.getPreferences(this@MainApplication, "").getMemoryUsageLogging()) {
                    Logger.log(this@MainApplication, "memory_usage", "MemSample", "info",
                        MemoryDiagnostics.snapshotFull(this@MainApplication))
                }
            } catch (_: Throwable) { /* a diagnostic must never crash the app */ }
            memSampleHandler?.postDelayed(this, MEM_SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Anchor the process-uptime clock to app start so every memory sample's
        // "sessionMin/uptime" field measures from here (touches the lazy init).
        MemoryDiagnostics.processUptimeMinutes()

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
                // Finish (or discard) a chat recovery restore interrupted by
                // process death (Build Phase 3 item 5) — BEFORE the outage
                // reconcile and rename recovery, both of which read the chat
                // files this may still be replacing from verified staging.
                org.teslasoft.assistant.preferences.backup.ChatRestoreManager.resumeIfPending(this)
            } catch (e: Exception) {
                MemoryLog.log(this, "ChatRestore", "error", "Chat-restore recovery at startup failed: ${e.message}")
            }
            try {
                // Recover anything the FIRST Round-4 build wrote to legacy
                // outage.* files during a storage lock (that plaintext
                // redirection was rejected and removed July 12 2026 — LOCKED
                // now blocks — but data already in those files must still be
                // merged back). MUST run before RenameJournal.reconcile —
                // rename recovery derives authority from the live chat list,
                // and this pass may be the thing restoring chats (and
                // outage-era journal entries) into it. Idempotent, per-file;
                // anything still locked stays untouched for a later start.
                SecurePrefs.reconcileOutageAtStartup(this)
            } catch (e: Exception) {
                MemoryLog.log(this, "SecurePrefs", "error", "Storage-outage reconciliation at startup failed: ${e.message}")
            }
            try {
                // Finish any chat rename whose memory re-point didn't complete
                // last session (process death or a SQLCipher failure between the
                // prefs pointer flip and MemoryStore.repointChat). Runs after
                // the outage merge above, and before the auto-export below and
                // before any future orphan pruning — so a renamed chat's
                // transcripts are re-pointed, not treated as abandoned. Guards
                // provisioning + the chat list internally; off the main thread
                // here.
                RenameJournal.reconcile(this)
            } catch (e: Exception) {
                MemoryLog.log(this, "RenameJournal", "error", "Rename reconciliation at startup failed: ${e.message}")
            }
            try {
                // Crash-triggered integrity checking (Build Phase 1 gate,
                // Build Phase 3 response). The whole-database PRAGMA
                // integrity_check reads every page and grows with the store,
                // so it runs ONLY after an abnormal previous exit (a write
                // could have been interrupted) or when a check/repair is
                // explicitly pending. When it fires it now covers ALL THREE
                // databases, and a CONFIRMED-damaged store is handled by the
                // Build Phase 3 flow — degraded flag, quarantine, automatic
                // staged repair attempt, and a queued dialog the next
                // foreground screen delivers (A1 problem / Database Repaired).
                // The old vanishing Toast (design doc F3) is gone.
                val shouldCheck = StartupHealth.shouldRunIntegrityCheck(
                    Logger.wasPreviousExitAbnormal(this),
                    StartupHealth.isIntegrityCheckPending(this)
                )
                if (shouldCheck) StartupDatabaseCheck.run(this)
            } catch (e: Exception) {
                MemoryLog.log(this, "MemoryStore", "error", "Startup database check failed: ${e.message}")
            }
            try {
                // Ordinary memory housekeeping — only for a provisioned store
                // that is not disabled pending repair (a degraded store's
                // getInstance would refuse anyway; checking first keeps the
                // log clean and the intent obvious).
                if (MemoryStore.isProvisioned(this) &&
                    !DatabaseHealthState.isDegraded(this, BackupType.MEMORY)) {
                    val store = MemoryStore.getInstance(this)
                    // One-time backfill: pre-existing chats become eligible
                    // for memory review too. The completion flag is set ONLY
                    // when the pass actually completed — a failed or partial
                    // pass leaves it unset and retries next start (the pass is
                    // idempotent: already-imported chats are skipped).
                    if (store.getMeta(MemoryStore.META_BACKFILL_DONE) != "1") {
                        if (TranscriptRecorder.backfillExistingChats(this).completed) {
                            store.setMeta(MemoryStore.META_BACKFILL_DONE, "1")
                        }
                    }
                    MemoryExporter.autoExportIfDue(this)
                }
            } catch (e: Exception) {
                MemoryLog.log(this, "MemoryStore", "error", "Memory store startup housekeeping failed: ${e.message}")
            }
            try {
                // Automatic Backups (owner ruling, July 23 2026). Two triggers:
                // the WorkManager job below is the reliable one that survives the
                // app being closed; this app-open pass is the CATCH-UP so a
                // backup that came due while the app was closed runs promptly on
                // next open. It runs HERE, on the startup housekeeping thread —
                // off the main thread and off the chat-list loader — so a due
                // backup never blocks the chat list from loading or a new chat
                // from opening (its only shared lock, CHAT_LIST_LOCK, is held
                // just briefly while a few small chat files are archived). Both
                // calls gate/guard internally and never throw.
                AutoBackupScheduling.sync(this)
                AutoBackupController.runIfDue(this)
            } catch (e: Exception) {
                MemoryLog.log(this, "AutoBackup", "error", "Automatic backup startup pass failed: ${e.message}")
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

        // Start the memory heartbeat. It runs process-wide (not tied to a chat)
        // so a leak that grows while the app sits idle is still captured; the
        // runnable is a no-op while the toggle is off, so this costs nothing in
        // normal use beyond a parked thread.
        try {
            val t = HandlerThread("mem-sampler").apply { start() }
            memSampleThread = t
            memSampleHandler = Handler(t.looper).also {
                it.postDelayed(memSampleRunnable, MEM_SAMPLE_INTERVAL_MS)
            }
        } catch (_: Throwable) { /* never let diagnostics setup crash startup */ }
    }

    /**
     * Android asking the app to release memory is a strong leak/pressure signal,
     * so record it (with a full footprint) to the Performance Log when Memory
     * usage logging is on. A [TRIM_MEMORY_COMPLETE] while foregrounded, or trims
     * arriving ever more frequently, is exactly the kind of thing a RAM problem
     * shows up as. Best-effort and gated; silent when the toggle is off.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            if (Preferences.getPreferences(this, "").getMemoryUsageLogging()) {
                Logger.log(this, "memory_usage", "MemTrim", "warning",
                    "onTrimMemory ${trimLevelName(level)} | ${MemoryDiagnostics.snapshotFull(this)}")
            }
        } catch (_: Throwable) { /* a diagnostic must never crash the app */ }
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        try {
            if (Preferences.getPreferences(this, "").getMemoryUsageLogging()) {
                Logger.log(this, "memory_usage", "MemTrim", "warning",
                    "onLowMemory | ${MemoryDiagnostics.snapshotFull(this)}")
            }
        } catch (_: Throwable) { /* a diagnostic must never crash the app */ }
    }

    @Suppress("DEPRECATION")
    private fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "level=$level"
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

    private companion object {
        // Memory heartbeat cadence. 60s is frequent enough to draw a growth
        // curve over a long session without flooding the Performance Log.
        private const val MEM_SAMPLE_INTERVAL_MS = 60_000L
    }
}
