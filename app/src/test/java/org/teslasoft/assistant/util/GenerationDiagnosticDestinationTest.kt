/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationDiagnosticDestinationTest {

    @Test
    fun dispatchedProviderFailureUsesProviderLogWhenEnabled() {
        assertEquals(
            GenerationDiagnosticDestination.PROVIDER_FAILURE_LOG,
            GenerationDiagnosticDestination.choose(true, true, true)
        )
    }

    @Test
    fun disabledProviderLogFallsBackToAlwaysOnErrorLog() {
        assertEquals(
            GenerationDiagnosticDestination.ERROR_LOG,
            GenerationDiagnosticDestination.choose(false, true, true)
        )
    }

    @Test
    fun localAndPreDispatchFailuresNeverMasqueradeAsProviderFailures() {
        assertEquals(
            GenerationDiagnosticDestination.ERROR_LOG,
            GenerationDiagnosticDestination.choose(true, false, true)
        )
        assertEquals(
            GenerationDiagnosticDestination.ERROR_LOG,
            GenerationDiagnosticDestination.choose(true, true, false)
        )
    }
}
