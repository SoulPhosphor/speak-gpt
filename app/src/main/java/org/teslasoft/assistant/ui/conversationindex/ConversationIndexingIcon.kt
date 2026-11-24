/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.conversationindex

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.index.ConversationIndexRuntime
import org.teslasoft.assistant.conversation.index.IndexingDisplay

/** What the indexing icon and its pop-up read and do (Step 12). */
interface IndexingControls {
    val display: StateFlow<IndexingDisplay>
    fun pause()
    fun resume()
    /** Reads the numbers again; they arrive through [display]. */
    fun refresh()

    companion object {
        /** The app's conversation indexing. */
        fun of(context: Context): IndexingControls {
            val app = context.applicationContext
            val progress = ConversationIndexRuntime.progress(app)
            return object : IndexingControls {
                override val display = progress.display
                override fun pause() = ConversationIndexRuntime.pause(app)
                override fun resume() = ConversationIndexRuntime.resume(app)
                override fun refresh() = progress.refresh()
            }
        }
    }
}

/**
 * The chat header's conversation-indexing icon (G.16a; Owner, 2026-11-24,
 * Step 12). Shown only while indexing catches up or is paused by the user;
 * it turns while indexing runs and is still when paused, or when the phone's
 * animations are off. Tapping it opens the progress pop-up, which follows
 * the progress while open and closes when the icon goes. The progress lives
 * in [ConversationIndexRuntime], so changing chats never restarts it; the
 * icon only follows it while the screen is started.
 */
class ConversationIndexingIcon(
    private val activity: FragmentActivity,
    private val icon: ImageButton,
    private val controls: IndexingControls = IndexingControls.of(activity),
    /** Whether the phone's animations are on; with them off the icon stays still. */
    private val animationsOn: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() }
) {
    private var turning: ObjectAnimator? = null
    private var turningOn = false
    private var dialog: ConversationIndexingDialog? = null
    private var shown: IndexingDisplay = IndexingDisplay.Hidden

    /** Tests: whether the icon was set turning (the phone's animation scale decides how it looks). */
    val isTurning: Boolean get() = turningOn

    /** Tests: the open pop-up, if any. */
    val openDialog: ConversationIndexingDialog? get() = dialog?.takeIf { it.isShowing }

    fun attach() {
        icon.setOnClickListener { open() }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dialog?.dismiss()
                dialog = null
            }
        })
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    controls.display.collect { show(it) }
                } finally {
                    // Nothing turns or reads while the screen is not in front; coming back shows the state again.
                    stopTurning()
                    dialog?.stopUpdates()
                }
            }
        }
    }

    private fun show(display: IndexingDisplay) {
        val kindChanged = display::class != shown::class
        shown = display
        when (display) {
            IndexingDisplay.Hidden -> {
                icon.visibility = View.GONE
                stopTurning()
                dialog?.dismiss()
            }
            is IndexingDisplay.Running -> {
                icon.visibility = View.VISIBLE
                startTurning()
            }
            is IndexingDisplay.Paused -> {
                icon.visibility = View.VISIBLE
                stopTurning()
            }
        }
        describe(display, kindChanged)
        dialog?.takeIf { it.isShowing }?.bind(display)
    }

    /**
     * The icon's spoken label: the title and the percentage or status. A
     * new percentage is not set while a screen reader is on the icon, so it
     * is not read out again with every step.
     */
    private fun describe(display: IndexingDisplay, kindChanged: Boolean) {
        // Running with a known total: the percentage; counting or paused: the status line's words.
        val status = when (display) {
            is IndexingDisplay.Running -> display.numbers?.let { IndexingDialogText.percent(activity, display) }
                ?: IndexingDialogText.status(activity, display)
            else -> IndexingDialogText.status(activity, display)
        } ?: return
        val label = activity.getString(
            R.string.conversation_indexing_icon_description,
            activity.getString(R.string.conversation_indexing_title),
            status
        )
        if (kindChanged || !icon.isAccessibilityFocused) icon.contentDescription = label
    }

    private fun open() {
        if (shown == IndexingDisplay.Hidden || activity.isFinishing || activity.isDestroyed) return
        val current = dialog?.takeIf { it.isShowing } ?: ConversationIndexingDialog(activity, controls).also { dialog = it }
        current.open(shown)
    }

    private fun startTurning() {
        if (!animationsOn()) {
            stopTurning()
            return
        }
        if (turningOn && turning?.isStarted == true) return
        turning?.cancel()
        turningOn = true
        turning = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, FULL_TURN).apply {
            duration = activity.resources.getInteger(R.integer.chat_indexing_icon_turn_ms).toLong()
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopTurning() {
        turningOn = false
        turning?.cancel()
        turning = null
        icon.rotation = 0f
    }

    private companion object {
        const val FULL_TURN = 360f
    }
}
