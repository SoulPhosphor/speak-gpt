/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.util

/** Chooses exactly one persistent diagnostic log for a generation failure. */
enum class GenerationDiagnosticDestination {
    ERROR_LOG,
    PROVIDER_FAILURE_LOG;

    companion object {
        fun choose(
            providerFailureLoggingEnabled: Boolean,
            reachedServer: Boolean,
            requestDispatched: Boolean
        ): GenerationDiagnosticDestination =
            if (providerFailureLoggingEnabled && reachedServer && requestDispatched) {
                PROVIDER_FAILURE_LOG
            } else {
                ERROR_LOG
            }
    }
}
