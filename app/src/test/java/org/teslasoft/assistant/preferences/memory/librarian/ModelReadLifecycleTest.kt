package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ModelReadLifecycleTest {
    @Test fun retirementNeverClosesARunningNativeReadOrBlocksTheCaller() {
        val released = AtomicInteger()
        val lifecycle = ModelReadLifecycle { released.incrementAndGet() }
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reader = Thread {
            try { lifecycle.read { started.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)) } }
            catch (t: Throwable) { failure.set(t) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            lifecycle.close()
            assertEquals(0, released.get())
            assertThrows(IllegalStateException::class.java) { lifecycle.read { fail("retired native model was accessed") } }
        } finally { finish.countDown(); reader.join(5000) }
        assertNull(failure.get())
        assertEquals(1, released.get())
        lifecycle.close()
        assertEquals(1, released.get())
    }

    @Test fun aFailedReadStillReleasesItsRetiredModelExactlyOnce() {
        var released = 0
        val lifecycle = ModelReadLifecycle { released++ }
        assertThrows(IllegalArgumentException::class.java) {
            lifecycle.read { lifecycle.close(); throw IllegalArgumentException("model failed") }
        }
        assertEquals(1, released)
        lifecycle.close()
        assertEquals(1, released)
    }
}
