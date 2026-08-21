/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortSupportTest {

    @Test
    fun identifiesAClearEffortRejection() {
        val body = "Invalid value for 'reasoning_effort': 'xhigh'. Supported values are none, low, medium, high."
        assertTrue(ReasoningEffortSupport.isEffortRejection(body, ReasoningEffort.XHIGH))
    }

    @Test
    fun identifiesReasoningObjectRejection() {
        val body = "The \"reasoning\" effort value 'minimal' is not supported by this model."
        assertTrue(ReasoningEffortSupport.isEffortRejection(body, ReasoningEffort.MINIMAL))
    }

    @Test
    fun requiresTheExactRejectedValue() {
        // Names the parameter and invalidity, but for a different value.
        val body = "Invalid value for 'reasoning_effort': 'minimal'."
        assertFalse(ReasoningEffortSupport.isEffortRejection(body, ReasoningEffort.XHIGH))
    }

    @Test
    fun doesNotFireOnUnrelatedErrors() {
        assertFalse(ReasoningEffortSupport.isEffortRejection("rate limit exceeded", ReasoningEffort.XHIGH))
        assertFalse(ReasoningEffortSupport.isEffortRejection("model produced xhigh quality output", ReasoningEffort.XHIGH))
        assertFalse(ReasoningEffortSupport.isEffortRejection(null, ReasoningEffort.MINIMAL))
        assertFalse(ReasoningEffortSupport.isEffortRejection("", ReasoningEffort.MINIMAL))
    }

    @Test
    fun requiresAnInvaliditySignal() {
        // Names the parameter and value but does not say it is invalid.
        val body = "reasoning_effort set to xhigh for this request"
        assertFalse(ReasoningEffortSupport.isEffortRejection(body, ReasoningEffort.XHIGH))
    }
}
