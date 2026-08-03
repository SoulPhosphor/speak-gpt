/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.util

import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

/** Resolves settings used by the primary visible streamed chat request. */
object VisibleChatRequestConfig {
    /**
     * The active endpoint profile is the source of truth. The per-chat value
     * remains a compatibility fallback for a missing/invalid profile value.
     */
    fun maximumResponseTokens(endpointProfileValue: Int?, perChatValue: Int?): Int =
        endpointProfileValue?.takeIf { it > 0 }
            ?: perChatValue?.takeIf { it > 0 }
            ?: ApiEndpointObject.DEFAULT_MAX_TOKENS
}
