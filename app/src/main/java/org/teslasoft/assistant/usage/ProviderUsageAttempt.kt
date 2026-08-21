/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.usage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.teslasoft.assistant.preferences.RawStreamObservation

/** Request-scoped capture bound to the exact Ktor request. It is independent of
 * optional Response Lifecycle logging because accounting must always persist. */
class ProviderUsageAttempt(
    val requestedModel: String,
    val fallbackProvider: String,
    val apiEndpoint: String
) {
    @Volatile private var responseModel: String? = null
    @Volatile private var responseProvider: String? = null
    @Volatile private var promptTokens: Int? = null
    @Volatile private var completionTokens: Int? = null
    @Volatile private var totalTokens: Int? = null
    @Volatile private var observationExpected = false
    private val observationFinished = CompletableDeferred<Unit>()

    @Synchronized
    fun noteTypedUsage(prompt: Int?, completion: Int?, total: Int?) {
        if (prompt != null) promptTokens = prompt
        if (completion != null) completionTokens = completion
        if (total != null) totalTokens = total
    }

    fun beginObservation() { observationExpected = true }

    @Synchronized
    fun noteProvider(value: String?) {
        value?.trim()?.ifBlank { null }?.let { responseProvider = it }
    }

    @Synchronized
    fun noteRawObservation(value: RawStreamObservation) {
        value.model?.trim()?.ifBlank { null }?.let { responseModel = it }
        if (promptTokens == null) promptTokens = value.promptTokens
        if (completionTokens == null) completionTokens = value.completionTokens
        if (totalTokens == null) totalTokens = value.totalTokens
    }

    fun finishObservation() { observationFinished.complete(Unit) }

    suspend fun snapshot(): ProviderUsageSnapshot {
        if (observationExpected && !observationFinished.isCompleted) {
            withTimeoutOrNull(500L) { observationFinished.await() }
        }
        return synchronized(this) {
            ProviderUsageSnapshot(
                model = responseModel ?: requestedModel,
                provider = responseProvider ?: fallbackProvider,
                apiEndpoint = apiEndpoint,
                counts = TokenCounts(promptTokens, completionTokens, totalTokens)
            )
        }
    }
}

data class ProviderUsageSnapshot(
    val model: String,
    val provider: String,
    val apiEndpoint: String,
    val counts: TokenCounts
)
