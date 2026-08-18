package org.teslasoft.assistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeActivityLifetimePolicyTest {
    @Test
    fun `destroyed owner keeps controller alive`() {
        assertTrue(
            HandsFreeActivityLifetimePolicy.controllerCanRun(
                activityDestroyed = true,
                isSessionOwner = true,
                loopStopped = false,
                cancelled = false
            )
        )
    }

    @Test
    fun `destroyed non owner cannot keep controller alive`() {
        assertFalse(
            HandsFreeActivityLifetimePolicy.controllerCanRun(
                activityDestroyed = true,
                isSessionOwner = false,
                loopStopped = false,
                cancelled = false
            )
        )
    }

    @Test
    fun `explicit stop wins even for retained owner`() {
        assertFalse(
            HandsFreeActivityLifetimePolicy.controllerCanRun(
                activityDestroyed = true,
                isSessionOwner = true,
                loopStopped = true,
                cancelled = true
            )
        )
    }

    @Test
    fun `destroy preservation requires live owner session`() {
        assertTrue(
            HandsFreeActivityLifetimePolicy.shouldPreserveOnDestroy(
                isSessionOwner = true,
                handsFreeEnabled = true,
                loopStopped = false
            )
        )
        assertFalse(
            HandsFreeActivityLifetimePolicy.shouldPreserveOnDestroy(
                isSessionOwner = false,
                handsFreeEnabled = true,
                loopStopped = false
            )
        )
        assertFalse(
            HandsFreeActivityLifetimePolicy.shouldPreserveOnDestroy(
                isSessionOwner = true,
                handsFreeEnabled = false,
                loopStopped = false
            )
        )
    }
}
