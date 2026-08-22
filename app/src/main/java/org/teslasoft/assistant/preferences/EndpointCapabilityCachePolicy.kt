/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

/**
 * Decides when capability learned for one effective API path is no longer safe
 * to reuse. Authentication and generation settings do not change model
 * identity; the base URL or chat-completions path does.
 */
object EndpointCapabilityCachePolicy {

    fun effectivePathChanged(
        originalHost: String,
        originalChatEndpoint: String,
        newHost: String,
        newChatEndpoint: String
    ): Boolean = normalizedHost(originalHost) != normalizedHost(newHost) ||
        normalizedChatEndpoint(originalChatEndpoint) != normalizedChatEndpoint(newChatEndpoint)

    private fun normalizedHost(host: String): String = host.trim().trimEnd('/')

    private fun normalizedChatEndpoint(path: String): String = path.trim()
        .ifBlank { ApiEndpointObject.DEFAULT_CHAT_ENDPOINT }
        .trim('/')
}
