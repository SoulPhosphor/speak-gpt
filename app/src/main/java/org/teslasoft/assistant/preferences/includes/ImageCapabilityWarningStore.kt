/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import android.content.Context
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.util.Hash

/** "Don't show again" is scoped to the same route identity as capability
 * learning. Suppressing one unknown model/provider never hides another. */
object ImageCapabilityWarningStore {
    private const val PREFS = "image_capability_warning_suppression"

    fun key(
        endpoint: ApiEndpointObject,
        modelId: String,
        routing: ImageRoutingConfig
    ): String = Hash.hash(
        endpoint.id + "|" + ImageCapabilityScope.key(endpoint, modelId, routing)
    )

    fun isSuppressed(
        context: Context,
        endpoint: ApiEndpointObject,
        modelId: String,
        routing: ImageRoutingConfig
    ): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(key(endpoint, modelId, routing), false)

    fun suppress(
        context: Context,
        endpoint: ApiEndpointObject,
        modelId: String,
        routing: ImageRoutingConfig
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(endpoint, modelId, routing), true)
            .apply()
    }
}
