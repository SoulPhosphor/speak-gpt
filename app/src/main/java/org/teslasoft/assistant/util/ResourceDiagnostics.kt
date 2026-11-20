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

package org.teslasoft.assistant.util

import android.content.Context
import android.os.Debug
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences
import java.util.WeakHashMap

/**
 * What the app is holding and running right now, for the diagnostic logs.
 * The point is to tell three things apart when memory grows:
 *  - active work (a reply, image, summary, memory scan or backup running);
 *  - intentional caching (the Whisper model staying loaded);
 *  - leaks (chat screens that were closed but are still in memory).
 *
 * Reads only cheap in-memory state; never touches storage or the network,
 * and never includes conversation content.
 */
object ResourceDiagnostics {

    /** Open and closed chat screens, held weakly so tracking never keeps one alive. */
    private val chatScreens = ScreenLifetimeTracker()

    /** Records a chat screen opening. [rebuilt] = Android rebuilt it from
     *  saved state after closing it in the background. */
    fun chatScreenCreated(context: Context, screen: Any, rebuilt: Boolean) {
        chatScreens.created(screen)
        systemLog(context, "ChatScreen", "info", "chat screen opened: rebuiltByAndroid=$rebuilt | ${state(context)}")
    }

    /** Records a chat screen being destroyed, BEFORE its teardown, so the
     *  line shows what was running at that moment. finishing=false with
     *  changingConfig=false means Android destroyed it, not the user. */
    fun chatScreenDestroyed(context: Context, screen: Any, finishing: Boolean, changingConfig: Boolean) {
        chatScreens.destroyed(screen)
        systemLog(
            context, "ChatScreen", if (finishing || changingConfig) "info" else "warning",
            "chat screen destroyed: finishing=$finishing changingConfig=$changingConfig " +
                "closedBy=${if (finishing) "app-or-user" else if (changingConfig) "configuration-change" else "android"} | " +
                state(context)
        )
    }

    /** Records a running hands-free conversation ending and why. */
    fun handsFreeEnded(context: Context, reason: String) {
        systemLog(context, "HandsFree", "info", "hands-free ended: reason=$reason | ${state(context)}")
    }

    private fun state(context: Context): String = fields() + " | " + MemoryDiagnostics.snapshotCompact(context)

    /** System Logs line, only while System Logs is on; never blocks the caller. */
    private fun systemLog(context: Context, tag: String, level: String, message: String) {
        try {
            val app = context.applicationContext
            if (!Preferences.getPreferences(app, "").getSystemLogging()) return
            Logger.logAsync(app, Logger.SYSTEM_LOG_TYPE, tag, level, message)
        } catch (_: Throwable) { /* diagnostics must never disturb the app */ }
    }

    /** Native heap allocated right now, in MB (whisper.cpp, decoded images, SQLCipher). */
    fun nativeHeapMb(): Long = (Debug.getNativeHeapAllocatedSize() + 512L * 1024L) / (1024L * 1024L)

    /**
     * One line of resource fields, e.g.
     * `whisper=small.en chatScreens=1 closedChatScreensInMemory=0 imageScreens=1
     * summaryScreens=1 handsFreeService=true replyService=false
     * work=replies:0,images:0,summaries:0,memoryScan:false,backup:false`.
     * Each probe is guarded so one failure only blanks its own field.
     */
    fun fields(): String {
        val (open, closed) = chatScreens.counts()
        return "whisper=${probe { org.teslasoft.assistant.stt.LocalWhisperEngine.get().loadedModelId() ?: "none" }} " +
            "chatScreens=$open closedChatScreensInMemory=$closed " +
            "imageScreens=${probe { org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry.attachedScreenCount() }} " +
            "summaryScreens=${probe { org.teslasoft.assistant.util.summarizer.SummarizerControllerRegistry.attachedScreenCount() }} " +
            "handsFreeService=${probe { org.teslasoft.assistant.service.HandsFreeService.isRunning }} " +
            "replyService=${probe { org.teslasoft.assistant.service.GenerationForegroundService.diagnostics().serviceRunning }} " +
            "work=replies:${probe { org.teslasoft.assistant.service.GenerationForegroundService.diagnostics().activeGenerations }}," +
            "images:${probe { org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry.activeJobCount() }}," +
            "summaries:${probe { org.teslasoft.assistant.util.summarizer.SummarizerControllerRegistry.activeOperations().size }}," +
            "memoryScan:${probe {
                org.teslasoft.assistant.service.MemoryAnalysisForegroundService.state.value is
                    org.teslasoft.assistant.service.MemoryAnalysisState.Running
            }}," +
            "backup:${probe { org.teslasoft.assistant.preferences.backup.AutoBackupController.isRunning() }}"
    }

    private inline fun probe(read: () -> Any): String =
        try { read().toString() } catch (_: Throwable) { "?" }
}

/**
 * Counts screens that are open and screens that were destroyed but have not
 * been freed yet. Screens are held weakly: a freed screen simply drops out.
 * A destroyed screen stays counted only until the next garbage collection,
 * so a count that stays above zero, or grows session after session, is the
 * sign of a leak.
 */
internal class ScreenLifetimeTracker {
    // value: true once the screen was destroyed
    private val screens = WeakHashMap<Any, Boolean>()

    fun created(screen: Any) = synchronized(screens) { screens[screen] = false }

    fun destroyed(screen: Any) = synchronized(screens) {
        if (screens.containsKey(screen)) screens[screen] = true
    }

    /** (open, destroyed but still in memory) */
    fun counts(): Pair<Int, Int> = synchronized(screens) {
        var open = 0
        var closed = 0
        for (destroyed in screens.values) if (destroyed) closed++ else open++
        open to closed
    }
}
