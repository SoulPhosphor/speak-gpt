package org.teslasoft.assistant.util

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.teslasoft.assistant.util.ProcessExitSnapshot.Activity as Work

/** Diagnostics only: no disk reads, timers, conversation content, or changes to task execution. */
internal object ProcessActivityTracker {
    private val ledger = ProcessActivityLedger()
    private var manager: ActivityManager? = null
    private var published: ProcessExitSnapshot? = null
    private var registered = false

    @Synchronized fun register(app: Application) {
        if (registered || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        registered = true
        manager = app.getSystemService(ActivityManager::class.java)
        val started = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started.add(activity); position(started.isNotEmpty()) }
            override fun onActivityStopped(activity: Activity) { started.remove(activity); position(started.isNotEmpty()) }
            override fun onActivityDestroyed(activity: Activity) { started.remove(activity); position(started.isNotEmpty()) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        publish()
    }

    @Synchronized private fun position(foreground: Boolean) {
        ledger.foreground = foreground
        publish()
    }
    @Synchronized fun update(owner: Any, activities: Set<Work>) {
        ledger.update(owner, activities)
        publish()
    }
    fun clear(owner: Any) = update(owner, emptySet())
    fun begin(work: Work): AutoCloseable {
        val owner = Any()
        val closed = AtomicBoolean()
        update(owner, setOf(work))
        return AutoCloseable { if (closed.compareAndSet(false, true)) clear(owner) }
    }

    // Only state transitions publish. Android associates these tiny flags with
    // the dying process, so a new launch cannot overwrite the previous record.
    private fun publish() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val service = manager ?: return
        val snapshot = ledger.snapshot()
        if (snapshot == published) return
        try {
            service.setProcessStateSummary(snapshot.encode())
            published = snapshot
        } catch (_: Exception) { /* throttling or unavailable diagnostics must not disrupt work */ }
    }

    fun exitLines(summary: ByteArray?, importance: Int): String {
        // Prefer Android's definitive exit-time state over an earlier screen
        // callback. A foreground service alone doesn't establish visibility.
        val position = when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Foreground"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "Background"
            else -> "Unknown"
        }
        ProcessExitSnapshot.decode(summary)?.let { snapshot ->
            val atExit = when (position) {
                "Foreground" -> true
                "Background" -> false
                else -> snapshot.foreground
            }
            return snapshot.copy(foreground = atExit).lines()
        }
        return "App Position: $position\nActivity: Unknown"
    }
}
