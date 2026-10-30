/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

/**
 * The keyboard or composer shrinking the chat viewport must keep the content
 * touching the composer in place (chat-keyboard-behavior.md, rule 4). At the
 * real end of a chat that content is the last row's bottom edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatTranscriptResizeAnchorTest {

    @Test
    fun shrinkingAtTheEndKeepsTheLastRowAgainstTheComposer() = withTranscript { transcript, manager ->
        layout(transcript, TALL)
        manager.scrollToPositionWithOffset(LAST, TALL - PADDING - ROW)
        layout(transcript, TALL)
        assertEquals(TALL - PADDING, manager.findViewByPosition(LAST)!!.bottom)

        transcript.captureResizeAnchor()
        layout(transcript, SHORT)

        assertEquals(SHORT - PADDING, manager.findViewByPosition(LAST)!!.bottom)
    }

    @Test
    fun growingAtTheEndKeepsTheLastRowAgainstTheComposer() = withTranscript { transcript, manager ->
        layout(transcript, SHORT)
        manager.scrollToPositionWithOffset(LAST, SHORT - PADDING - ROW)
        layout(transcript, SHORT)

        transcript.captureResizeAnchor()
        layout(transcript, TALL)

        assertEquals(TALL - PADDING, manager.findViewByPosition(LAST)!!.bottom)
    }

    @Test
    fun shrinkingMidChatKeepsTheSameRowTheSameDistanceFromTheComposer() =
        withTranscript { transcript, manager ->
            layout(transcript, TALL)
            manager.scrollToPositionWithOffset(10, 0)
            layout(transcript, TALL)
            val anchorPosition = manager.findLastVisibleItemPosition()
            val distanceFromBottom = manager.findViewByPosition(anchorPosition)!!.top - TALL

            transcript.captureResizeAnchor()
            layout(transcript, SHORT)

            assertEquals(
                SHORT + distanceFromBottom,
                manager.findViewByPosition(anchorPosition)!!.top
            )
        }

    private fun layout(view: View, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, height)
    }

    private fun withTranscript(test: (ChatTranscriptRecyclerView, LinearLayoutManager) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val manager = LinearLayoutManager(activity)
            val transcript = ChatTranscriptRecyclerView(activity).apply {
                clipToPadding = false
                setPadding(0, 0, 0, PADDING)
                layoutManager = manager
                itemAnimator = null
                adapter = FixedRows()
            }
            activity.setContentView(FrameLayout(activity).apply { addView(transcript) })
            test(transcript, manager)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private class FixedRows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(
                View(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW)
                }
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

        override fun getItemCount(): Int = COUNT
    }

    private companion object {
        const val WIDTH = 1080
        const val TALL = 1600
        const val SHORT = 900
        const val PADDING = 36
        const val ROW = 250
        const val COUNT = 30
        const val LAST = COUNT - 1
    }
}
