/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.activities.ChatActivity
import org.teslasoft.assistant.util.summarizer.SummarizerController
import org.teslasoft.assistant.util.summarizer.SummarizerControllerRegistry
import java.lang.ref.WeakReference

/** Small non-modal app-level notice shown when the affected chat is no longer
 * the foreground screen. Only the contained Cancel button consumes taps. */
class SummarizerAppStatusOverlay : Application.ActivityLifecycleCallbacks,
    SummarizerControllerRegistry.AppListener {
    private val handler = Handler(Looper.getMainLooper())
    private var activity = WeakReference<Activity>(null)
    private var card = WeakReference<MaterialCardView>(null)
    private var shownChatId: String? = null
    private var latestState: Pair<String, SummarizerController.OperationState>? = null

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        SummarizerControllerRegistry.addAppListener(this)
    }

    override fun onOperationChanged(chatId: String, state: SummarizerController.OperationState) {
        latestState = chatId to state
        handler.post { render(chatId, state) }
    }

    override fun onActivityResumed(value: Activity) {
        activity = WeakReference(value)
        latestState?.let { render(it.first, it.second) }
            ?: SummarizerControllerRegistry.activeOperations().firstOrNull()?.let { render("", it) }
    }

    override fun onActivityPaused(activity: Activity) { removeCard() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    private fun render(chatId: String, state: SummarizerController.OperationState) {
        val host = activity.get() ?: return
        val sameChat = host is ChatActivity && host.intent?.getStringExtra("chatId") == chatId
        if (sameChat || state is SummarizerController.OperationState.Idle ||
            state is SummarizerController.OperationState.Cancelled
        ) {
            removeCard()
            return
        }
        if (state !is SummarizerController.OperationState.Running &&
            state !is SummarizerController.OperationState.Succeeded &&
            state !is SummarizerController.OperationState.Failed
        ) {
            removeCard()
            return
        }
        removeCard()
        val root = host.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        val container = MaterialCardView(host).apply {
            radius = 20f * resources.displayMetrics.density
            cardElevation = 4f * resources.displayMetrics.density
        }
        val row = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        val spinner = CircularProgressIndicator(host).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        val text = TextView(host).apply {
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            textSize = 14f
            setPadding(dp(8), 0, dp(4), 0)
        }
        val cancel = MaterialButton(host).apply {
            setText(R.string.btn_cancel)
            minWidth = 0
            setOnClickListener { SummarizerControllerRegistry.cancel(chatId) }
        }
        when (state) {
            is SummarizerController.OperationState.Running -> {
                text.text = host.getString(
                    if (state.kind == SummarizerController.OperationKind.COMPACTING) {
                        R.string.compaction_app_status_running
                    } else R.string.summarizer_app_status_running,
                    state.chatName.ifBlank { host.getString(R.string.label_untitled_chat) }
                )
                row.addView(spinner); row.addView(text); row.addView(cancel)
            }
            is SummarizerController.OperationState.Succeeded -> {
                text.text = host.getString(
                    if (state.kind == SummarizerController.OperationKind.COMPACTING) {
                        R.string.compaction_status_complete
                    } else R.string.summarizer_status_complete
                )
                row.addView(text)
                handler.postDelayed({ if (shownChatId == chatId) removeCard() }, 3000L)
            }
            is SummarizerController.OperationState.Failed -> {
                text.setText(
                    org.teslasoft.assistant.util.summarizer.SummarizerOperationMessages
                        .failureMessageRes(state.kind, state.category)
                )
                row.addView(text)
            }
            else -> return
        }
        container.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(12) })
        shownChatId = chatId
        card = WeakReference(container)
    }

    private fun removeCard() {
        card.get()?.let { (it.parent as? FrameLayout)?.removeView(it) }
        card.clear()
        shownChatId = null
    }

    private fun dp(value: Int): Int =
        ((activity.get()?.resources?.displayMetrics?.density ?: 1f) * value).toInt()
}
