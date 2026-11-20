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
        /** Runs inside a load, before it completes. */
        @Volatile var duringInit: (String) -> Unit = {}

        override fun libraryReady() = ready
        override fun init(modelId: String, path: String): Long {
            inits.incrementAndGet()
            duringInit(modelId)
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
        val ticket = context.requestRelease()
        ensure("small") // the switch already freed base
        context.releaseLoadedBefore(ticket)
        assertEquals("small", context.current()?.modelId)
        assertEquals(1, native.frees.get())
    }

    @Test fun releaseRequestedWhileTheModelIsStillLoadingFreesItOnceLoaded() =
        runBlocking(Dispatchers.Default) {
            // Uninstall during a load: nothing is loaded yet when the release
            // is requested, but the model that finishes loading must not stay.
            val loading = CountDownLatch(1)
            val finishLoad = CountDownLatch(1)
            native.duringInit = {
                loading.countDown()
                finishLoad.await(5, TimeUnit.SECONDS)
            }
            val load = async(Dispatchers.IO) { ensure("small") }
            assertTrue(loading.await(5, TimeUnit.SECONDS))
            assertNull("nothing is loaded while the load runs", context.current())
            val ticket = context.requestRelease()
            val release = launch(Dispatchers.IO) { context.releaseLoadedBefore(ticket) }
            finishLoad.countDown()
            assertTrue(load.await())
            withTimeout(5_000) { release.join() }
            assertNull(context.current())
            assertTrue(native.live.isEmpty())
            assertEquals(1, native.frees.get())
            assertTrue(native.violations.isEmpty())
        }

    @Test fun modelWhoseLoadStartsAfterTheReleaseRequestIsKept() = runBlocking {
        val ticket = context.requestRelease()
        ensure("small")
        context.releaseLoadedBefore(ticket)
        assertEquals("small", context.current()?.modelId)
        assertEquals(0, native.frees.get())
    }

    @Test fun engineReleaseRecordsTheRequestEvenWhenNothingIsLoadedYet() {
        // The engine must take its ticket when release() is called, never
        // return early because no model has finished loading yet.
        val relative = "src/main/java/org/teslasoft/assistant/stt/LocalWhisperEngine.kt"
        val file = listOf(java.io.File(relative), java.io.File("app/$relative")).first { it.isFile }
        val source = file.readText()
        val start = source.indexOf("    fun release(")
        assertTrue("LocalWhisperEngine.release() not found", start >= 0)
        val body = source.substring(start, source.indexOf("\n    }\n", start))
        val ticketAt = body.indexOf("modelContext.requestRelease()")
        assertTrue(ticketAt >= 0)
        assertTrue(ticketAt < body.indexOf("releaseScope.launch"))
        assertTrue(body.contains("releaseLoadedBefore(ticket)"))
        assertFalse(body.contains("?: return"))
    }

    @Test fun releaseReportsTheModelItFreed() = runBlocking {
        ensure("small")
        assertEquals("small", context.release()?.modelId)
        assertNull("nothing left to free", context.release())
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
                    2 -> {
                        val ticket = context.requestRelease()
                        context.releaseLoadedBefore(ticket)
                    }
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
