package org.teslasoft.assistant.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorEndedStreamTest {

    @Test fun errorEventWithoutFinishReasonIsAFailure() {
        assertTrue(ResponseLifecycle.endedOnProviderError(true, null))
        assertTrue(ResponseLifecycle.endedOnProviderError(true, "  "))
    }

    @Test fun providerFinishedReplyKeepsItsOwnTerminalSemantics() {
        assertFalse(ResponseLifecycle.endedOnProviderError(true, "stop"))
        assertFalse(ResponseLifecycle.endedOnProviderError(true, "length"))
    }

    @Test fun noErrorEventIsNotAProviderFailure() {
        assertFalse(ResponseLifecycle.endedOnProviderError(false, null))
    }
}
