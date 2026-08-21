/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningEffortSubstitutionTest {

    private val universal = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)

    @Test
    fun minimalStepsUpToLow() {
        assertEquals(
            ReasoningEffort.LOW,
            ReasoningEffortSubstitution.substitute(ReasoningEffort.MINIMAL, universal)
        )
    }

    @Test
    fun minimalStepsUpToNextSupportedWhenLowMissing() {
        assertEquals(
            ReasoningEffort.MEDIUM,
            ReasoningEffortSubstitution.substitute(
                ReasoningEffort.MINIMAL, listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
            )
        )
    }

    @Test
    fun xhighStepsDownToHigh() {
        assertEquals(
            ReasoningEffort.HIGH,
            ReasoningEffortSubstitution.substitute(ReasoningEffort.XHIGH, universal)
        )
    }

    @Test
    fun xhighStepsDownToNextSupportedWhenHighMissing() {
        assertEquals(
            ReasoningEffort.MEDIUM,
            ReasoningEffortSubstitution.substitute(
                ReasoningEffort.XHIGH, listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM)
            )
        )
    }

    @Test
    fun nullWhenNoSafeMiddleLevelIsSupported() {
        // No low/medium/high available → fall back to AUTO, never an extreme.
        assertNull(ReasoningEffortSubstitution.substitute(ReasoningEffort.MINIMAL, emptyList()))
        assertNull(ReasoningEffortSubstitution.substitute(ReasoningEffort.XHIGH, emptyList()))
    }

    @Test
    fun neverSubstitutesTheOppositeExtreme() {
        // A rejected minimal must never jump to xhigh just because it is the only
        // other entry, and vice versa — those resolve to AUTO (null) instead.
        assertNull(ReasoningEffortSubstitution.substitute(ReasoningEffort.MINIMAL, listOf(ReasoningEffort.XHIGH)))
        assertNull(ReasoningEffortSubstitution.substitute(ReasoningEffort.XHIGH, listOf(ReasoningEffort.MINIMAL)))
    }
}
