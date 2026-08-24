/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.usage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.teslasoft.assistant.preferences.RawStreamObservation
import org.teslasoft.assistant.providers.ProviderDiagnosticEvent
import org.teslasoft.assistant.providers.ProviderDiagnosticParser
import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot
import java.util.UUID

/** Request-scoped capture bound to the exact Ktor request. It is independent of
 * optional Response Lifecycle logging because accounting must always persist. */
class ProviderUsageAttempt(
    val requestedModel: String,
    val fallbackProvider: String,
    val apiEndpoint: String,
    val attemptId: String = UUID.randomUUID().toString()
) {
    @Volatile private var responseModel: String? = null
    @Volatile private var responseProvider: String? = null
    @Volatile private var promptTokens: Int? = null
    @Volatile private var completionTokens: Int? = null
    @Volatile private var totalTokens: Int? = null
    @Volatile private var inputCost: Double? = null
    @Volatile private var outputCost: Double? = null
    @Volatile private var totalCost: Double? = null
    @Volatile private var outerHttpStatus: Int? = null
    @Volatile private var finishReason: String? = null
    @Volatile private var generationId: String? = null
    @Volatile private var partialContentCharacters: Int = 0
    @Volatile private var reasoningCharacters: Int = 0
    @Volatile private var malformedPayloadCount: Int = 0
    private val providerDiagnosticEvents = mutableListOf<ProviderDiagnosticEvent>()
    @Volatile private var observationExpected = false
    private val observationFinished = CompletableDeferred<Unit>()

    @Synchronized
    fun noteTypedUsage(prompt: Int?, completion: Int?, total: Int?) {
        if (prompt != null) promptTokens = prompt
        if (completion != null) completionTokens = completion
        if (total != null) totalTokens = total
    }

    fun beginObservation(status: Int? = null) {
        observationExpected = true
        if (status != null) outerHttpStatus = status
    }

    /** Ordinary non-2xx response, bound to this exact request by Ktor attrs. */
    @Synchronized
    fun noteHttpResponse(status: Int, rawBody: String?) {
        outerHttpStatus = status
        providerDiagnosticEvents += ProviderDiagnosticParser.parseHttpBody(rawBody, status)
        providerDiagnosticEvents.firstNotNullOfOrNull { it.actualServingProvider }
            ?.let { responseProvider = it }
    }

    @Synchronized
    fun noteTypedChunk(
        finishReason: String?,
        id: String?,
        contentLength: Int
    ) {
        finishReason?.trim()?.ifBlank { null }?.let { this.finishReason = it }
        if (generationId == null) id?.trim()?.ifBlank { null }?.let { generationId = it }
        if (contentLength > 0) partialContentCharacters += contentLength
    }

    @Synchronized
    fun noteProvider(value: String?) {
        value?.trim()?.ifBlank { null }?.let { responseProvider = it }
    }

    @Synchronized
    fun noteRawObservation(value: RawStreamObservation) {
        value.actualServingProvider?.trim()?.ifBlank { null }?.let { responseProvider = it }
        value.model?.trim()?.ifBlank { null }?.let { responseModel = it }
        if (promptTokens == null) promptTokens = value.promptTokens
        if (completionTokens == null) completionTokens = value.completionTokens
        if (totalTokens == null) totalTokens = value.totalTokens
        if (inputCost == null) inputCost = value.inputCost
        if (outputCost == null) outputCost = value.outputCost
        if (totalCost == null) totalCost = value.totalCost
        value.finishReason?.trim()?.ifBlank { null }?.let { finishReason = it }
        if (generationId == null) {
            value.generationId?.trim()?.ifBlank { null }?.let { generationId = it }
        }
        malformedPayloadCount += value.malformedDataEvents
        reasoningCharacters += value.reasoningCharacters
        providerDiagnosticEvents += value.providerDiagnostics
        if (value.actualServingProvider.isNullOrBlank()) {
            value.providerDiagnostics.firstNotNullOfOrNull { it.actualServingProvider }
                ?.let { responseProvider = it }
        }
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
                counts = TokenCounts(promptTokens, completionTokens, totalTokens),
                providerCost = ProviderReportedCost(inputCost, outputCost, totalCost)
            )
        }
    }

    /** Same request-scoped evidence used by classifier, chat, and both logs. */
    suspend fun diagnosticSnapshot(): ProviderDiagnosticSnapshot {
        if (observationExpected && !observationFinished.isCompleted) {
            withTimeoutOrNull(500L) { observationFinished.await() }
        }
        return synchronized(this) {
            ProviderDiagnosticSnapshot(
                attemptId = attemptId,
                outerHttpStatus = outerHttpStatus,
                actualServingProvider = responseProvider,
                finishReason = finishReason,
                generationId = generationId,
                partialContentCharacters = partialContentCharacters,
                reasoningCharacters = reasoningCharacters,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                malformedPayloadCount = malformedPayloadCount,
                events = providerDiagnosticEvents.toList()
            )
        }
    }
}

/** Carries the request owner through SDK exceptions that cannot themselves be
 * augmented with response metadata. The classifier still sees [cause]. */
class GenerationAttemptFailureException(
    val attempt: ProviderUsageAttempt?,
    cause: Throwable
) : Exception(cause.message, cause)

data class ProviderUsageSnapshot(
    val model: String,
    val provider: String,
    val apiEndpoint: String,
    val counts: TokenCounts,
    val providerCost: ProviderReportedCost
)
