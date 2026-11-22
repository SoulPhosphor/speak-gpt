package org.teslasoft.assistant.ui.widgets

import android.os.Looper
import android.view.View
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.librarian.MemoryTokenBudget
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MemoryTokenMeterTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun withMeter(test: (MemoryTokenMeter) -> Unit) {
        ActivityScenario.launch(MemoryTokenMeterTestActivity::class.java).use { scenario ->
            var meter: MemoryTokenMeter? = null
            scenario.onActivity { meter = it.findViewById(R.id.memory_length_meter) }
            test(meter!!)
        }
    }

    private fun onUi(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun measure(meter: MemoryTokenMeter, block: (MemoryTokenBudget.Input) -> MemoryTokenBudget.Usage?) {
        MemoryTokenMeter::class.java.getDeclaredField("measure").apply { isAccessible = true }.set(meter, block)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var ready = false
        while (!ready && System.nanoTime() < end) {
            onUi { ready = condition() }
            if (!ready) Thread.sleep(10)
        }
        assertTrue("UI did not reach the expected state", ready)
    }

    @Test fun liveTextAndTagsAreCountedOffTheUiThreadAndModelChangeRefreshesTheMeter() = withMeter { meter ->
        var capacity = 100
        measure(meter) {
            assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
            MemoryTokenBudget.Usage(it.document().length + 5, capacity)
        }
        val tokens = meter.findViewById<TextView>(R.id.memory_token_count)
        val words = meter.findViewById<TextView>(R.id.memory_word_count)
        val bar = meter.findViewById<LinearProgressIndicator>(R.id.memory_token_progress)
        onUi { meter.bind(MemoryTokenBudget.Input("one two", tags = listOf("tag"))) }
        waitFor { tokens.text.toString() == "16 / 100 Tokens" }
        assertEquals("2 Words", words.text.toString())
        assertEquals(16, bar.progress)
        onUi { meter.bind(MemoryTokenBudget.Input("one two", tags = listOf("tag", "more"))) }
        waitFor { tokens.text.toString() == "22 / 100 Tokens" }
        capacity = 20
        EmbeddingTokenCounter.invalidate()
        waitFor { tokens.text.toString() == "22 / 20 Tokens" }
        assertTrue(tokens.isActivated)
        assertEquals(100, bar.progress)
    }

    @Test fun obsoleteSlowResultCannotOverwriteAReboundMemory() = withMeter { meter ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        measure(meter) {
            if (it.content == "old") {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                MemoryTokenBudget.Usage(90, 100)
            } else MemoryTokenBudget.Usage(10, 100)
        }
        val tokens = meter.findViewById<TextView>(R.id.memory_token_count)
        onUi { meter.bind(MemoryTokenBudget.Input("old")) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        onUi { meter.bind(MemoryTokenBudget.Input("new")) }
        release.countDown()
        waitFor { tokens.text.toString() == "10 / 100 Tokens" }
        assertEquals("1 Word", meter.findViewById<TextView>(R.id.memory_word_count).text.toString())
    }

    @Test fun unavailableTokenizerIsNeverPresentedAsZeroUsage() = withMeter { meter ->
        measure(meter) { throw IllegalStateException("fixture failure") }
        onUi { meter.bind(MemoryTokenBudget.Input("kept text")) }
        val tokens = meter.findViewById<TextView>(R.id.memory_token_count)
        waitFor { tokens.text.toString() == "— / — Tokens" }
        assertEquals(View.GONE, meter.findViewById<View>(R.id.memory_token_progress).visibility)
        assertEquals("2 Words", meter.findViewById<TextView>(R.id.memory_word_count).text.toString())
    }

    @Test fun typingIntoTheSameMemoryNeverFlashesOrMovesTheMeter() = withMeter { meter ->
        val release = CountDownLatch(1)
        measure(meter) {
            if (it.content == "one two three") release.await(5, TimeUnit.SECONDS)
            null
        }
        val tokens = meter.findViewById<TextView>(R.id.memory_token_count)
        val words = meter.findViewById<TextView>(R.id.memory_word_count)
        val bar = meter.findViewById<View>(R.id.memory_token_progress)
        onUi { meter.bind(MemoryTokenBudget.Input("one two")) }
        waitFor { words.text.toString() == "2 Words" }
        assertEquals(View.GONE, bar.visibility)
        var height = 0
        onUi { height = meter.height }
        onUi { meter.refresh(MemoryTokenBudget.Input("one two three")) }
        Thread.sleep(300)
        onUi {
            assertEquals("2 Words", words.text.toString())
            assertEquals("— / — Tokens", tokens.text.toString())
            assertEquals(View.GONE, bar.visibility)
            assertEquals(height, meter.height)
        }
        release.countDown()
        waitFor { words.text.toString() == "3 Words" }
        assertEquals(View.GONE, bar.visibility)
    }
}
