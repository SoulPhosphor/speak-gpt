package org.teslasoft.assistant.ui.chat

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatJumpButtonsControllerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = Preferences.getPreferences(context, "")

    private class Screen(context: Context) {
        val root = ConstraintLayout(context)
        val transcript = ChatTranscriptRecyclerView(context).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(context)
            adapter = Rows(ROWS)
        }
        val top = ImageButton(context).apply { id = R.id.chat_jump_top }
        val bottom = ImageButton(context).apply { id = R.id.chat_jump_bottom }
        val rail = FrameLayout(context).apply {
            id = R.id.chat_jump_rail
            addView(this@Screen.top)
            addView(this@Screen.bottom)
        }

        init {
            root.addView(transcript, ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            })
            root.addView(rail, ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0).apply {
                topToTop = transcript.id
                bottomToBottom = transcript.id
                rightToRight = transcript.id
            })
        }

        fun layout() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 400, 800)
        }

        val manager get() = transcript.layoutManager as LinearLayoutManager
        val railParams get() = rail.layoutParams as ConstraintLayout.LayoutParams
    }

    private class Rows(private val count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
            }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = position.toString()
        }
        override fun getItemCount() = count
    }

    @Test fun defaultsToShownOnTheRightSide() {
        assertTrue(preferences.getShowChatScrollNavigation())
        assertFalse(preferences.getChatScrollNavigationOnLeft())

        val screen = Screen(context)
        ChatJumpButtonsController(screen.rail, screen.transcript)
        assertEquals(View.VISIBLE, screen.rail.visibility)
        assertEquals(screen.transcript.id, screen.railParams.rightToRight)
        assertEquals(ConstraintLayout.LayoutParams.UNSET, screen.railParams.leftToLeft)
    }

    @Test fun followsTheSideAndOnOffSettings() {
        val screen = Screen(context)
        val controller = ChatJumpButtonsController(screen.rail, screen.transcript)

        preferences.setChatScrollNavigationOnLeft(true)
        controller.applySettings()
        assertEquals(screen.transcript.id, screen.railParams.leftToLeft)
        assertEquals(ConstraintLayout.LayoutParams.UNSET, screen.railParams.rightToRight)

        preferences.setShowChatScrollNavigation(false)
        controller.applySettings()
        assertEquals(View.GONE, screen.rail.visibility)

        preferences.setShowChatScrollNavigation(true)
        preferences.setChatScrollNavigationOnLeft(false)
        controller.applySettings()
        assertEquals(View.VISIBLE, screen.rail.visibility)
        assertEquals(screen.transcript.id, screen.railParams.rightToRight)
    }

    @Test fun hiddenWhileTheTranscriptIsHidden() {
        val screen = Screen(context)
        val controller = ChatJumpButtonsController(screen.rail, screen.transcript)
        screen.transcript.visibility = View.GONE
        controller.applySettings()
        assertEquals(View.GONE, screen.rail.visibility)
        screen.transcript.visibility = View.VISIBLE
        controller.applySettings()
        assertEquals(View.VISIBLE, screen.rail.visibility)
    }

    @Test fun arrowsJumpToTheFirstAndLastMessageAsTheUsersOwnScroll() {
        val screen = Screen(context)
        ChatJumpButtonsController(screen.rail, screen.transcript)
        var interactions = 0
        screen.transcript.onUserInteraction = { interactions++ }
        screen.layout()
        screen.manager.scrollToPositionWithOffset(ROWS / 2, 0)
        screen.layout()

        screen.top.performClick()
        screen.layout()
        assertEquals(0, screen.manager.findFirstCompletelyVisibleItemPosition())

        screen.bottom.performClick()
        screen.layout()
        screen.layout()
        assertEquals(ROWS - 1, screen.manager.findLastCompletelyVisibleItemPosition())
        assertEquals(2, interactions)
    }

    @Test fun arrowsDoNothingInAnEmptyChat() {
        val screen = Screen(context)
        screen.transcript.adapter = Rows(0)
        ChatJumpButtonsController(screen.rail, screen.transcript)
        var interactions = 0
        screen.transcript.onUserInteraction = { interactions++ }
        screen.layout()
        screen.top.performClick()
        screen.bottom.performClick()
        // Auto-scroll must stay on for the chat's first reply.
        assertEquals(0, interactions)
    }

    private companion object { const val ROWS = 50 }
}
