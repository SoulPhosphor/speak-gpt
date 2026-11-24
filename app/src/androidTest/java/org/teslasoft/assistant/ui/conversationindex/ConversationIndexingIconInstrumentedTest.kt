package org.teslasoft.assistant.ui.conversationindex

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.index.IndexingDisplay
import org.teslasoft.assistant.conversation.index.IndexingNumbers
import org.teslasoft.assistant.ui.activities.MemoryControlsActivity

/**
 * Step 12 on a device (G.16a; Owner, 2026-11-24): the chat header's
 * indexing icon shows only while indexing runs or is paused, turns only while
 * it runs (and never with the phone's animations off), and its pop-up shows
 * the approved wording for each state with Pause or Resume and Close. The
 * icon is given a fake progress, so every state is set exactly; the progress
 * itself is covered by `ConversationIndexProgressTest`. A themed app screen
 * hosts the icon; the chat screen's one-line hookup is checked on the device.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIndexingIconInstrumentedTest {

    private class FakeControls : IndexingControls {
        override val display = MutableStateFlow<IndexingDisplay>(IndexingDisplay.Hidden)
        val pauses = AtomicInteger()
        val resumes = AtomicInteger()
        val refreshes = AtomicInteger()
        override fun pause() { pauses.incrementAndGet() }
        override fun resume() { resumes.incrementAndGet() }
        override fun refresh() { refreshes.incrementAndGet() }
    }

    private val controls = FakeControls()
    /** Set on the test thread, read on the main thread. */
    @Volatile private var animationsOn = true
    private lateinit var scenario: ActivityScenario<MemoryControlsActivity>
    private lateinit var button: ImageButton
    private lateinit var icon: ConversationIndexingIcon
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val numbers = IndexingNumbers(percent = 42, indexedChats = 128, totalChats = 310)

    @Before fun setUp() {
        scenario = ActivityScenario.launch(MemoryControlsActivity::class.java)
        scenario.onActivity { activity ->
            button = ImageButton(activity, null, 0, R.style.Widget_App_Chat_HeaderAction_Activity).apply {
                visibility = View.GONE
                setImageResource(R.drawable.ic_sync)
            }
            activity.findViewById<FrameLayout>(android.R.id.content).addView(
                button, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            icon = ConversationIndexingIcon(activity, button, controls) { animationsOn }
            icon.attach()
        }
    }

    @After fun tearDown() {
        scenario.onActivity { icon.openDialog?.dismiss() }
        scenario.close()
    }

    /** Waits on the test thread until [condition], checked on the main thread, holds. */
    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            var held = false
            scenario.onActivity { held = condition() }
            if (held) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting until $what")
            Thread.sleep(POLL_MS)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        scenario.onActivity { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun show(display: IndexingDisplay, settled: () -> Boolean) {
        controls.display.value = display
        waitUntil("$display is shown", settled)
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)
    private val title get() = string(R.string.conversation_indexing_title)

    @Test fun theIconShowsOnlyWhileIndexingRunsOrIsPausedAndTurnsOnlyWhileItRuns() {
        assertEquals(View.GONE, onMain { button.visibility })

        show(IndexingDisplay.Running(null)) { button.visibility == View.VISIBLE }
        assertTrue(onMain { icon.isTurning })
        assertEquals("$title, ${string(R.string.conversation_indexing_counting)}", onMain { button.contentDescription })

        show(IndexingDisplay.Running(numbers)) { button.contentDescription?.toString()?.endsWith("42%") == true }
        assertTrue(onMain { icon.isTurning })
        assertEquals("$title, 42%", onMain { button.contentDescription })

        show(IndexingDisplay.Paused(numbers)) { !icon.isTurning }
        assertEquals(View.VISIBLE, onMain { button.visibility })
        assertEquals(0f, onMain { button.rotation })
        assertEquals("$title, ${string(R.string.conversation_indexing_paused)}", onMain { button.contentDescription })

        show(IndexingDisplay.Hidden) { button.visibility == View.GONE }
        assertFalse(onMain { icon.isTurning })
    }

    @Test fun withThePhonesAnimationsOffTheRunningIconStaysStill() {
        animationsOn = false
        show(IndexingDisplay.Running(numbers)) { button.visibility == View.VISIBLE }
        assertFalse(onMain { icon.isTurning })
        assertEquals(0f, onMain { button.rotation })
    }

    @Test fun thePopUpShowsEachStateWithTheApprovedWordingAndItsActions() {
        show(IndexingDisplay.Running(numbers)) { button.visibility == View.VISIBLE }
        onMain { button.performClick() }
        waitUntil("the pop-up opens") { icon.openDialog != null }
        val dialog = onMain { icon.openDialog }
        assertNotNull(dialog)
        dialog!!

        // Running with real numbers.
        assertEquals("42%", onMain { dialog.percentText.toString() })
        assertEquals("128 of 310 conversations indexed", onMain { dialog.statusText.toString() })
        assertTrue(onMain { dialog.barShown && !dialog.barIndeterminate && dialog.barProgress == 42 })
        assertTrue(onMain { dialog.dotsShown })
        assertEquals(string(R.string.conversation_indexing_pause), onMain { dialog.pauseResumeText.toString() })
        assertEquals("Indexing Conversations", onMain { dialog.textOf(R.id.indexing_title).toString() })
        assertEquals(
            "This runs in the background. You can keep chatting.",
            onMain { dialog.textOf(R.id.indexing_explanation).toString() }
        )
        assertEquals("Close", onMain { dialog.textOf(R.id.indexing_close).toString() })
        onMain { dialog.clickPauseResume() }
        assertEquals(1, controls.pauses.get())
        assertEquals(0, controls.resumes.get())

        // Paused: the percentage and bar are kept, still; Resume and Close.
        show(IndexingDisplay.Paused(numbers)) { dialog.statusText.toString() == "Paused" }
        assertEquals("42%", onMain { dialog.percentText.toString() })
        assertTrue(onMain { dialog.barShown && !dialog.barIndeterminate && dialog.barProgress == 42 })
        assertFalse(onMain { dialog.dotsShown })
        assertEquals(string(R.string.conversation_indexing_resume), onMain { dialog.pauseResumeText.toString() })
        onMain { dialog.clickPauseResume() }
        assertEquals(1, controls.resumes.get())
        assertEquals(1, controls.pauses.get())

        // Paused before the count finished: no percentage and no bar.
        show(IndexingDisplay.Paused(null)) { !dialog.barShown }
        assertEquals("", onMain { dialog.percentText.toString() })
        assertEquals("Paused", onMain { dialog.statusText.toString() })

        // Counting: no percentage, a moving bar and the counting status.
        show(IndexingDisplay.Running(null)) { dialog.barShown }
        assertTrue(onMain { dialog.barIndeterminate })
        assertEquals("", onMain { dialog.percentText.toString() })
        assertEquals("Counting messages to index…", onMain { dialog.statusText.toString() })
        assertTrue(onMain { dialog.dotsShown })
        assertEquals(string(R.string.conversation_indexing_pause), onMain { dialog.pauseResumeText.toString() })

        // Close only closes the pop-up; indexing is not paused or stopped.
        onMain { dialog.clickClose() }
        waitUntil("the pop-up closes") { icon.openDialog == null }
        assertEquals(1, controls.pauses.get())
        assertEquals(View.VISIBLE, onMain { button.visibility })
    }

    @Test fun theOpenPopUpReadsTheNumbersWhileIndexingRunsAndClosesWhenItEnds() {
        show(IndexingDisplay.Running(numbers)) { button.visibility == View.VISIBLE }
        onMain { button.performClick() }
        waitUntil("the pop-up opens") { icon.openDialog != null }
        val opened = controls.refreshes.get()
        assertTrue("opening reads the numbers", opened >= 1)
        waitUntil("the numbers are read again") { controls.refreshes.get() > opened }

        // Paused: no more reading.
        show(IndexingDisplay.Paused(numbers)) { icon.openDialog?.statusText?.toString() == "Paused" }
        val whilePaused = controls.refreshes.get()
        Thread.sleep(context.resources.getInteger(R.integer.conversation_indexing_refresh_ms) * 2L)
        assertEquals(whilePaused, controls.refreshes.get())

        // Reopened after closing, it reads and moves again.
        show(IndexingDisplay.Running(numbers)) { icon.openDialog?.dotsShown == true }
        onMain { icon.openDialog?.clickClose() }
        waitUntil("the pop-up closes") { icon.openDialog == null }
        onMain { button.performClick() }
        waitUntil("the pop-up opens again") { icon.openDialog?.dotsShown == true }
        val reopened = controls.refreshes.get()
        waitUntil("the reopened pop-up reads the numbers again") { controls.refreshes.get() > reopened }

        // Indexing ends: the pop-up and the icon go.
        show(IndexingDisplay.Hidden) { button.visibility == View.GONE }
        waitUntil("the pop-up closes with the icon") { icon.openDialog == null }
        assertNull(onMain { icon.openDialog })
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
