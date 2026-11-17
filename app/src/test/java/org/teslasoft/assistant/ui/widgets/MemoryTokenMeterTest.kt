package org.teslasoft.assistant.ui.widgets

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.librarian.MemoryTokenBudget
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MemoryTokenMeterTest {
    private fun withMeter(test: (MemoryTokenMeter) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.UI_Material)
        controller.setup().visible()
        try {
            val meter = MemoryTokenMeter(controller.get())
            controller.get().setContentView(meter)
            test(meter)
        } finally { controller.pause().stop().destroy() }
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            Thread.sleep(10)
        }
        assertTrue("UI did not reach the expected state", condition())
    }

    @Test fun liveTextAndTagsAreCountedOffTheUiThreadAndModelChangeRefreshesTheMeter() = withMeter { meter ->
        var capacity = 100
        meter.measure = {
            assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
            MemoryTokenBudget.Usage(it.document().length + 5, capacity)
        }
        val tokens = meter.findViewById<TextView>(R.id.memory_token_count)
        val words = meter.findViewById<TextView>(R.id.memory_word_count)
        val bar = meter.findViewById<LinearProgressIndicator>(R.id.memory_token_progress)
        meter.bind(MemoryTokenBudget.Input("one two", tags = listOf("tag")))
        waitFor { tokens.text.toString() == "16 / 100 Tokens" }
        assertEquals("2 Words", words.text.toString())
        assertEquals(16, bar.progress)
        meter.bind(MemoryTokenBudget.Input("one two", tags = listOf("tag", "more")))
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
        meter.measure = {
            if (it.content == "old") {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                MemoryTokenBudget.Usage(90, 100)
            } else MemoryTokenBudget.Usage(10, 100)
        }
        val tokens = meter.findViewById<TextView>(R.id.memory_token_count)
        meter.bind(MemoryTokenBudget.Input("old"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        meter.bind(MemoryTokenBudget.Input("new"))
        release.countDown()
        waitFor { tokens.text.toString() == "10 / 100 Tokens" }
        assertEquals("1 Word", meter.findViewById<TextView>(R.id.memory_word_count).text.toString())
    }

    @Test fun unavailableTokenizerIsNeverPresentedAsZeroUsage() = withMeter { meter ->
        meter.measure = { throw IllegalStateException("fixture failure") }
        meter.bind(MemoryTokenBudget.Input("kept text"))
        val tokens = meter.findViewById<TextView>(R.id.memory_token_count)
        waitFor { tokens.text.toString() == "— / — Tokens" }
        assertEquals(View.GONE, meter.findViewById<View>(R.id.memory_token_progress).visibility)
        assertEquals("2 Words", meter.findViewById<TextView>(R.id.memory_word_count).text.toString())
    }
}
