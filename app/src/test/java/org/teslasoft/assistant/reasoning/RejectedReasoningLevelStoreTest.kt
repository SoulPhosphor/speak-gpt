/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectedReasoningLevelStoreTest {

    @Test
    fun emptyStoreRejectsNothing() {
        assertTrue(RejectedReasoningLevelStore.get(null, "m").isEmpty())
        assertTrue(RejectedReasoningLevelStore.get("{}", "m").isEmpty())
        assertFalse(RejectedReasoningLevelStore.isRejected("{}", "m", ReasoningEffort.XHIGH))
    }

    @Test
    fun addRecordsAndReadsBackPerModel() {
        val json = RejectedReasoningLevelStore.add(null, "vendor/x", ReasoningEffort.XHIGH)
        assertTrue(RejectedReasoningLevelStore.isRejected(json, "vendor/x", ReasoningEffort.XHIGH))
        // Scoped to the exact model id.
        assertFalse(RejectedReasoningLevelStore.isRejected(json, "vendor/y", ReasoningEffort.XHIGH))
        // Untouched levels stay allowed.
        assertFalse(RejectedReasoningLevelStore.isRejected(json, "vendor/x", ReasoningEffort.MINIMAL))
    }

    @Test
    fun addAccumulatesBothLearnableLevels() {
        var json = RejectedReasoningLevelStore.add(null, "m", ReasoningEffort.MINIMAL)
        json = RejectedReasoningLevelStore.add(json, "m", ReasoningEffort.XHIGH)
        assertEquals(
            setOf(ReasoningEffort.MINIMAL, ReasoningEffort.XHIGH),
            RejectedReasoningLevelStore.get(json, "m")
        )
    }

    @Test
    fun addIsIdempotentAndSignalsNoChange() {
        val first = RejectedReasoningLevelStore.add(null, "m", ReasoningEffort.XHIGH)
        val second = RejectedReasoningLevelStore.add(first, "m", ReasoningEffort.XHIGH)
        assertEquals(first, second)
    }

    @Test
    fun onlyTheOptimisticExtremesAreStorable() {
        // Universal levels are never rejected/learned, so recording one is a no-op.
        val json = RejectedReasoningLevelStore.add("{}", "m", ReasoningEffort.HIGH)
        assertFalse(RejectedReasoningLevelStore.isRejected(json, "m", ReasoningEffort.HIGH))
        assertTrue(RejectedReasoningLevelStore.get(json, "m").isEmpty())
    }

    @Test
    fun malformedJsonReadsAsEmpty() {
        assertTrue(RejectedReasoningLevelStore.get("not json", "m").isEmpty())
    }
}
