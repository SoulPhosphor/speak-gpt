/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.reasoning

/**
 * Positively identifies a provider error as a rejection of the specific
 * reasoning-effort value that was sent (dynamic minimal/xhigh learning, owner
 * ruling Aug 2026).
 *
 * The retry-and-learn path acts only on a POSITIVE identification, so this is
 * deliberately conservative: it fires only when the error names the reasoning
 * effort parameter, names the exact rejected value, AND signals invalidity.
 * Anything short of that returns false, and the caller lets the failure flow
 * through the normal error path unchanged (no retry, no learning).
 */
object ReasoningEffortSupport {

    fun isEffortRejection(errorMessage: String?, rejectedEffort: ReasoningEffort): Boolean {
        val msg = errorMessage?.lowercase()?.takeIf { it.isNotBlank() } ?: return false

        val namesEffortParam = msg.contains("reasoning_effort") ||
            msg.contains("reasoning.effort") ||
            msg.contains("reasoning effort") ||
            msg.contains("\"reasoning\"") ||
            msg.contains("'reasoning'")

        // The exact refused value. minimal/xhigh are distinctive tokens, so this
        // is a strong positive signal rather than a generic reasoning error.
        val namesRejectedValue = msg.contains(rejectedEffort.serialized)

        val signalsInvalid = msg.contains("invalid") ||
            msg.contains("unsupported") ||
            msg.contains("not supported") ||
            msg.contains("supported values") ||
            msg.contains("not a valid") ||
            msg.contains("must be one of")

        return namesEffortParam && namesRejectedValue && signalsInvalid
    }
}
