package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.service.GenerationKeepAliveDiagnostics
import org.teslasoft.assistant.service.HandsFreeConnectionDiagnostics

class GenerationNetworkDiagnosticsTest {
    @Test fun traceRecordsOnlyRealTransportChanges() {
        val trace = NetworkTransitionTrace("wifi", 1000L)
        trace.record("wifi", 1200L)
        trace.record("cellular", 96842L)
        trace.record("cellular", 97000L)
        val snapshot = trace.snapshot("cellular")
        assertEquals("wifi", snapshot.atDispatch)
        assertEquals("cellular", snapshot.atFailure)
        assertEquals(1, snapshot.transitions.size)
        assertEquals(95842L, snapshot.transitions.single().elapsedMs)
        assertEquals("wifi -> cellular at +95842 ms", snapshot.transitionsDisplay())
    }

    @Test fun noTransitionIsExplicit() {
        assertEquals("none observed", NetworkTransitionTrace("wifi", 0L).snapshot("wifi").transitionsDisplay())
    }

    @Test fun capturedVoiceStateWinsAfterTeardown() {
        val captured = GenerationFailureSnapshot(
            true,
            GenerationNetworkSnapshot("wifi", "cellular", emptyList()),
            GenerationKeepAliveDiagnostics(true, 1, 100L, true, true, null),
            HandsFreeConnectionDiagnostics(true, 1000L, true, true, null)
        )
        assertTrue(resolveFailureVoiceState(captured, false))
        assertFalse(resolveFailureVoiceState(null, false))
    }
}
