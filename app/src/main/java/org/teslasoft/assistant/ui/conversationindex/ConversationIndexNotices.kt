/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.conversationindex

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import org.teslasoft.assistant.conversation.index.ConversationIndexConditions
import org.teslasoft.assistant.conversation.index.IndexNotice

/**
 * Shows the conversation index's notices (G20, Owner 2026-11-24): one
 * pop-up for each new ongoing condition, on the screen in front. A condition
 * that arises while the app is in the background is shown when the app next
 * becomes active. Closing the pop-up leaves the condition itself in place;
 * the settings status keeps showing it until recovery is verified.
 */
object ConversationIndexNotices : Application.ActivityLifecycleCallbacks {

    private val main = Handler(Looper.getMainLooper())
    /** Reads the saved conditions off the main thread. */
    private val reader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conversation-index-notices").apply { isDaemon = true }
    }
    private var resumed: WeakReference<Activity>? = null
    private var showing = false
    /** The screen the open notice belongs to; closing that screen also ends the notice. */
    private var shownOn: WeakReference<Activity>? = null

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        ConversationIndexConditions.addListener { main.post { check() } }
    }

    /** Looks for a notice that is due and shows it on the screen in front. Main thread. */
    private fun check() {
        val activity = resumed?.get() ?: return
        if (showing || activity !is AppCompatActivity || activity.isFinishing || activity.isDestroyed) return
        val app = activity.applicationContext
        reader.execute {
            val notice = try { ConversationIndexConditions.pendingNotice(app) } catch (_: Exception) { null }
            if (notice != null) main.post { show(notice) }
        }
    }

    private fun show(notice: IndexNotice) {
        val activity = resumed?.get() ?: return
        if (showing || activity.isFinishing || activity.isDestroyed) return
        // Still due: another screen may have shown it meanwhile.
        if (ConversationIndexConditions.pendingNotice(activity) != notice) return
        showing = true
        shownOn = WeakReference(activity)
        try {
            ConversationIndexConditions.markNoticed(activity, notice)
            ConversationIndexDialogs.showNotice(activity, notice).setOnDismissListener {
                showing = false
                shownOn = null
                check()
            }
        } catch (_: Exception) {
            showing = false
            shownOn = null
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        check()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (shownOn?.get() === activity) {
            showing = false
            shownOn = null
        }
    }
}
