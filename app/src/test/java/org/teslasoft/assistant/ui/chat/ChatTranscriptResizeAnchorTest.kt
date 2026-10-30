/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.app.Activity
import android.app.Application
import android.view.MotionEvent
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
    fun shrinkingAtTheEndKeepsTheLastRowAgainstTheComposer() = withTranscript { t ->
        t.showEnd(TALL)

        t.resize(SHORT)

        assertEquals(SHORT - PADDING, t.lastBottom())
    }

    @Test
    fun growingAtTheEndKeepsTheLastRowAgainstTheComposer() = withTranscript { t ->
        t.showEnd(SHORT)

        t.resize(TALL)

        assertEquals(TALL - PADDING, t.lastBottom())
    }

    @Test
    fun aKeyboardArrivingOverSeveralFramesHoldsTheEndOnEveryFrame() = withTranscript { t ->
        t.showEnd(TALL)

        for (height in listOf(1500, 1320, 1110, 960, SHORT)) {
            t.resize(height)
            assertEquals(height - PADDING, t.lastBottom())
        }
        for (height in listOf(960, 1250, TALL)) {
            t.resize(height)
            assertEquals(height - PADDING, t.lastBottom())
        }
    }

    @Test
    fun aFinalReplyTallerThanTheScreenKeepsItsEndAgainstTheComposer() =
        withTranscript(lastRowHeight = 2600) { t ->
            t.showEnd(TALL)

            t.resize(SHORT)

            assertEquals(SHORT - PADDING, t.lastBottom())
        }

    @Test
    fun aLastRowThatChangesHeightDuringTheResizeIsStillHeldByItsEnd() = withTranscript { t ->
        t.showEnd(TALL)

        t.transcript.captureResizeAnchor()
        t.rows.lastRowHeight = ROW + 120
        t.rows.notifyItemChanged(LAST)
        t.layout(SHORT)

        assertEquals(SHORT - PADDING, t.lastBottom())
    }

    @Test
    fun shrinkingMidChatKeepsTheSameRowTheSameDistanceFromTheComposer() = withTranscript { t ->
        t.layout(TALL)
        t.manager.scrollToPositionWithOffset(10, 0)
        t.layout(TALL)
        val anchorPosition = t.manager.findLastVisibleItemPosition()
        val distanceFromBottom = t.manager.findViewByPosition(anchorPosition)!!.top - TALL

        t.resize(SHORT)

        assertEquals(
            SHORT + distanceFromBottom,
            t.manager.findViewByPosition(anchorPosition)!!.top
        )
    }

    @Test
    fun aShortChatThatStillFitsStaysAtTheTop() = withTranscript(count = 2) { t ->
        t.layout(TALL)
        assertEquals(0, t.manager.findViewByPosition(0)!!.top)

        t.resize(SHORT)

        assertEquals(0, t.manager.findViewByPosition(0)!!.top)
    }

    @Test
    fun aShortChatThatStopsFittingMovesOnlyEnoughToStayAboveTheComposer() =
        withTranscript(count = 5) { t ->
            // 5 rows of 250: fits the tall viewport (1564 usable), not the short one (864).
            t.layout(TALL)
            assertEquals(0, t.manager.findViewByPosition(0)!!.top)

            t.resize(SHORT)

            // Sticky to the composer: the last row's bottom sits on it, so the
            // chat moved up by exactly the 386 it no longer fits by. (Row 0 is
            // now fully off screen and may be recycled, so row 1 is measured.)
            assertEquals(SHORT - PADDING, t.lastBottom())
            assertEquals(ROW - 386, t.manager.findViewByPosition(1)!!.top)

            t.resize(TALL)

            // Coming back down with the composer returns it to where it began.
            assertEquals(0, t.manager.findViewByPosition(0)!!.top)
            assertEquals(5 * ROW, t.lastBottom())
        }

    @Test
    fun aFingerOnTheChatKeepsTheKeyboardFromMovingIt() = withTranscript { t ->
        t.showEnd(TALL)
        // A row near the top stays on screen when nothing moves; rows near
        // the bottom are correctly left under the composer and recycled.
        val watched = t.manager.findFirstCompletelyVisibleItemPosition()
        val rowTop = t.manager.findViewByPosition(watched)!!.top

        t.touch(MotionEvent.ACTION_DOWN)
        t.resize(SHORT)

        // Rule 1: nothing automatic moves the conversation under a finger.
        assertEquals(rowTop, t.manager.findViewByPosition(watched)!!.top)

        t.touch(MotionEvent.ACTION_UP)
        t.showEnd(TALL)
        t.resize(SHORT)

        // Once the finger is lifted the hold works again.
        assertEquals(SHORT - PADDING, t.lastBottom())
    }

    private class Harness(
        val transcript: ChatTranscriptRecyclerView,
        val manager: LinearLayoutManager,
        val rows: Rows
    ) {
        val last get() = rows.itemCount - 1

        fun layout(height: Int) {
            transcript.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            transcript.layout(0, 0, WIDTH, height)
        }

        /** Same order as the app: note the anchor at the old size, then resize. */
        fun resize(height: Int) {
            transcript.captureResizeAnchor()
            layout(height)
        }

        fun showEnd(height: Int) {
            layout(height)
            manager.scrollToPositionWithOffset(last, height - PADDING - rows.heightOf(last))
            layout(height)
            assertEquals(height - PADDING, lastBottom())
        }

        fun lastBottom(): Int = manager.findViewByPosition(last)!!.bottom

        fun touch(action: Int) {
            val event = MotionEvent.obtain(0L, 0L, action, WIDTH / 2f, 100f, 0)
            transcript.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun withTranscript(
        count: Int = COUNT,
        lastRowHeight: Int = ROW,
        test: (Harness) -> Unit
    ) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val manager = LinearLayoutManager(activity)
            val rows = Rows(count, lastRowHeight)
            val transcript = ChatTranscriptRecyclerView(activity).apply {
                clipToPadding = false
                setPadding(0, 0, 0, PADDING)
                layoutManager = manager
                itemAnimator = null
                adapter = rows
            }
            activity.setContentView(FrameLayout(activity).apply { addView(transcript) })
            test(Harness(transcript, manager, rows))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private class Rows(
        private val count: Int,
        var lastRowHeight: Int
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        fun heightOf(position: Int): Int = if (position == count - 1) lastRowHeight else ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(View(parent.context)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            holder.itemView.layoutParams =
                RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightOf(position))
        }

        override fun getItemCount(): Int = count
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
