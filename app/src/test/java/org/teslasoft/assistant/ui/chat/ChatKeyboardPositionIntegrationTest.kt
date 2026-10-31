package org.teslasoft.assistant.ui.chat

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatKeyboardPositionIntegrationTest {
    @Test
    fun theSameContentTracksTheMessageBoxThroughKeyboardOpeningAndClosing() = withChat { t ->
        val row = t.manager.findLastVisibleItemPosition()
        val distance = t.rowTop(row) - t.messageBoxTop()

        for (inset in listOf(150, 300, 500, 700, 500, 300, 150, 0)) {
            t.keyboard(inset)
            assertEquals("Keyboard inset $inset", distance, t.rowTop(row) - t.messageBoxTop())
        }
    }

    @Test
    fun theSameContentTracksTheMessageBoxWhenTypingWrapsAnotherLine() = withChat { t ->
        t.keyboard(600)
        val row = t.manager.findLastVisibleItemPosition()
        val distance = t.rowTop(row) - t.messageBoxTop()

        // A typed line changes the editor's measured height without a mode
        // change or a new keyboard inset. The real parent remeasures both.
        for (height in listOf(150, 200, 250, 200, 150, 100)) {
            t.messageBox.layoutParams = t.messageBox.layoutParams.apply { this.height = height }
            t.layout()
            assertEquals("Message box height $height", distance, t.rowTop(row) - t.messageBoxTop())
        }
    }

    private class Chat(activity: Activity) {
        val root = ConstraintLayout(activity)
        val manager = LinearLayoutManager(activity)
        val transcript = ChatTranscriptRecyclerView(activity).apply {
            id = View.generateViewId()
            layoutManager = manager
            itemAnimator = null
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 40
                override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
                    object : RecyclerView.ViewHolder(View(parent.context)) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    holder.itemView.layoutParams = RecyclerView.LayoutParams(-1, 250)
                }
            }
        }
        val host = ChatImeInsetLayout(activity).apply { id = View.generateViewId() }
        val messageBox = View(activity)

        init {
            host.addView(messageBox, ViewGroup.LayoutParams(-1, 100))
            root.addView(host, ConstraintLayout.LayoutParams(0, -2).apply {
                startToStart = 0
                endToEnd = 0
                bottomToBottom = 0
            })
            root.addView(transcript, ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = 0
                endToEnd = 0
                topToTop = 0
                bottomToTop = host.id
            })
            host.onBottomInsetChanging = { _, _ -> transcript.captureResizeAnchor() }
            activity.setContentView(root)
            layout()
            manager.scrollToPositionWithOffset(10, 0)
            layout()
        }

        fun layout() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 1800)
        }

        fun keyboard(bottom: Int) {
            ViewCompat.dispatchApplyWindowInsets(host, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                .build())
            layout()
        }

        fun rowTop(row: Int) = transcript.top + manager.findViewByPosition(row)!!.top
        fun messageBoxTop() = host.top + messageBox.top
    }

    private fun withChat(test: (Chat) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            test(Chat(controller.get()))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
