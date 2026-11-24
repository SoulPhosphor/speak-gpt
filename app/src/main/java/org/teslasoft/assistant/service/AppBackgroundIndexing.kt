/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.service

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import org.teslasoft.assistant.conversation.index.BackgroundIndexingController
import org.teslasoft.assistant.conversation.index.BackgroundIndexingPlatform
import org.teslasoft.assistant.conversation.index.BackgroundStopReason
import org.teslasoft.assistant.conversation.index.ConversationIndexErrorLog
import org.teslasoft.assistant.conversation.index.ConversationIndexRuntime
import org.teslasoft.assistant.conversation.index.IndexingConditions
import org.teslasoft.assistant.conversation.index.IndexingDisplay

/**
 * Follows whether any screen of the app is visible and hands the moments the
 * app is left and opened to [BackgroundIndexingController] (Step 13). The
 * service may be started only while the app leaves the screen, which Android
 * allows; a rotation is not leaving the app.
 */
object AppBackgroundIndexing : Application.ActivityLifecycleCallbacks {

    /** Data-sync services the user starts, which background indexing yields to (Owner, 2026-11-24). */
    private val userWork = setOf(
        ImageGenerationForegroundService::class.java.name,
        RestoreForegroundService::class.java.name,
        SummarizerForegroundService::class.java.name,
        MemoryAnalysisForegroundService::class.java.name
    )

    @Volatile private var instance: BackgroundIndexingController? = null

    /** Tests: what the leave decision reads instead of the app's indexing progress and conditions. */
    @Volatile internal var displayForTest: IndexingDisplay? = null
    @Volatile internal var conditionsForTest: IndexingConditions? = null
    private var startedScreens = 0

    fun register(app: Application) {
        controller(app)
        app.registerActivityLifecycleCallbacks(this)
    }

    fun controller(context: Context): BackgroundIndexingController =
        instance ?: synchronized(this) {
            instance ?: BackgroundIndexingController(Platform(context.applicationContext), ConversationIndexRuntime.backgroundPermit)
                .also { instance = it }
        }

    override fun onActivityStarted(activity: Activity) {
        if (startedScreens++ == 0) guarded { controller(activity).appOpened() }
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedScreens == 0) return
        if (--startedScreens == 0 && !activity.isChangingConfigurations) guarded { controller(activity).appLeft() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    /** The index is derived; nothing here may reach a screen. */
    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (_: Exception) { }
    }

    private class Platform(private val app: Context) : BackgroundIndexingPlatform {

        override fun display(): IndexingDisplay = displayForTest
            ?: ConversationIndexRuntime.progressIfCreated()?.display?.value ?: IndexingDisplay.Hidden

        override fun conditions(): IndexingConditions = conditionsForTest
            ?: ConversationIndexRuntime.progressIfCreated()?.conditions()
                ?: IndexingConditions(settingOn = false, rebuilding = false, recoveryFailed = false, modelUnavailable = false)

        override fun userPaused(): Boolean = ConversationIndexRuntime.progressIfCreated()?.isPaused ?: false

        override fun stalled(): Boolean = ConversationIndexRuntime.progressIfCreated()?.isStalled ?: false

        override fun userWorkRunning(): Boolean = try {
            val manager = app.getSystemService(ActivityManager::class.java)
            // Lists only this app's own services.
            @Suppress("DEPRECATION")
            manager?.getRunningServices(Int.MAX_VALUE).orEmpty().any {
                it.service.packageName == app.packageName && it.service.className in userWork
            }
        } catch (_: Exception) {
            false
        }

        override fun startService() = ConversationIndexingForegroundService.start(app)

        override fun stopService(reason: BackgroundStopReason) = ConversationIndexingForegroundService.requestStop(reason)

        override fun showStoppedNotice() = ConversationIndexingForegroundService.showPausedNotice(app)

        override fun clearStoppedNotice() = ConversationIndexingForegroundService.clearPausedNotice(app)

        override fun logcat(message: String) {
            try { Log.i(ConversationIndexErrorLog.TAG, message) } catch (_: Throwable) { }
        }

        override fun errorLog(message: String) = ConversationIndexRuntime.log(app, "error", message)
    }
}
