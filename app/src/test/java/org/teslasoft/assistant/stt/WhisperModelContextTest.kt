package org.teslasoft.assistant.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class WhisperModelContextTest {

    /** Stands in for whisper.cpp: records every misuse of a handle. */
    private class FakeNative : WhisperModelContext.NativeOps {
        var ready = true
        val live: MutableSet<Long> = Collections.synchronizedSet(HashSet())
        val violations: MutableList<String> = Collections.synchronizedList(ArrayList())
        val inits = AtomicInteger()
        val frees = AtomicInteger()
        val abort = AtomicBoolean(false)
        private val nextHandle = AtomicLong(100)

        override fun libraryReady() = ready
        override fun init(modelId: String, path: String): Long {
            inits.incrementAndGet()
            Thread.sleep(5) // a load takes time; lets other callers pile up
            return nextHandle.incrementAndGet().also { live.add(it) }
        }
        override fun free(handle: Long) {
            frees.incrementAndGet()
            if (!live.remove(handle)) violations.add("double free of $handle")
        }
        override fun signalAbort() = abort.set(true)
        override fun clearAbort() = abort.set(false)

        /** A decode that polls the abort flag, as whisper_full does. */
        fun decode(handle: Long, steps: Int = 20) {
            repeat(steps) {
                if (!live.contains(handle)) violations.add("use after free of $handle")
                if (abort.get()) return
                Thread.sleep(1)
            }
        }
    }

    private val native = FakeNative()
    private val context = WhisperModelContext(native)

    private suspend fun ensure(model: String) = context.ensure(model) { "/models/$model.bin" }

    @Test fun loadedModelIsReusedBetweenConversations() = runBlocking {
        assertTrue(ensure("small"))
        val first = context.current()
        assertTrue(ensure("small"))
        assertEquals(1, native.inits.get())
        assertTrue(first === context.current())
    }

    @Test fun concurrentPreloadsLoadOnce() = runBlocking(Dispatchers.Default) {
        (1..8).map { async { ensure("small") } }.awaitAll()
        assertEquals(1, native.inits.get())
        assertEquals(1, native.live.size)
    }

    @Test fun unavailableModelNeverLoads() = runBlocking {
        assertFalse(context.ensure("") { "/models/x.bin" })
        assertFalse(context.ensure("missing") { null })
        native.ready = false
        assertFalse(ensure("small"))
        assertEquals(0, native.inits.get())
    }

    @Test fun switchingModelsFreesTheOldOne() = runBlocking {
        ensure("base")
        val base = context.current()!!.handle
        ensure("small")
        assertFalse(native.live.contains(base))
        assertEquals(1, native.live.size)
        assertTrue(native.violations.isEmpty())
    }

    @Test fun releaseTwiceFreesOnce() = runBlocking {
        ensure("small")
        context.release()
        context.release()
        assertEquals(1, native.frees.get())
        assertNull(context.current())
        assertTrue(native.violations.isEmpty())
    }

    @Test fun transcriptionAfterReleaseGetsNoHandle() = runBlocking {
        ensure("small")
        context.release()
        assertEquals(0L, context.withTranscription("small") { it })
    }

    @Test fun transcriptionForASwitchedAwayModelGetsNoHandle() = runBlocking {
        ensure("base")
        ensure("small")
        assertEquals(0L, context.withTranscription("base") { it })
        assertNotEquals(0L, context.withTranscription("small") { it })
    }

    @Test fun releaseWaitsForTheRunningTranscriptionAndAbortsIt() = runBlocking(Dispatchers.Default) {
        ensure("small")
        val started = CountDownLatch(1)
        val transcription = async(Dispatchers.IO) {
            context.withTranscription("small") { handle ->
                started.countDown()
                native.decode(handle, steps = 5_000) // stops only when aborted
                native.live.contains(handle)
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { context.release() }
        assertTrue("handle stayed valid for the whole decode", transcription.await())
        assertEquals(1, native.frees.get())
        assertTrue(native.violations.isEmpty())
    }

    @Test fun staleReleaseDoesNotFreeTheModelLoadedAfterIt() = runBlocking {
        ensure("base")
        val requested = context.current()
        ensure("small") // the switch already freed base
        context.release(requested)
        assertEquals("small", context.current()?.modelId)
        assertEquals(1, native.frees.get())
    }

    @Test fun releaseOfNothingLoadedIsHarmless() = runBlocking {
        context.release()
        assertEquals(0, native.frees.get())
    }

    @Test fun randomInterleavingsNeverMisuseAHandle() = runBlocking(Dispatchers.Default) {
        val models = listOf("base", "small")
        val random = Random(7)
        val jobs = (1..200).map { i ->
            val pick = random.nextInt(4)
            val model = models[random.nextInt(models.size)]
            launch(Dispatchers.IO) {
                when (pick) {
                    0 -> ensure(model)
                    1 -> context.release()
                    2 -> context.release(context.current())
                    else -> context.withTranscription(model) { handle ->
                        if (handle != 0L) native.decode(handle, steps = 3 + i % 5)
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        assertTrue(native.violations.joinToString(), native.violations.isEmpty())
        // At most the one loaded context remains; nothing leaked.
        val current = context.current()
        assertEquals(if (current == null) emptySet() else setOf(current.handle), native.live.toSet())
    }
}
