package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeSummarizerOperationPolicyTest {
    @Test fun warningBeginsAtOneHundredThousandEstimatedTokens() {
        assertFalse(LargeSummarizerOperationPolicy.needsConfirmation(99_999))
        assertTrue(LargeSummarizerOperationPolicy.needsConfirmation(100_000))
    }
}
