/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.teslasoft.assistant.providers.ReportedProviderParser

/**
 * Durable attribution stored on the message itself. The JSON record travels in
 * the same encrypted chat-history blob as the text, so reopening a mixed-model
 * conversation never has to infer old attribution from the chat's current
 * settings.
 *
 * Provider usage remains structured: input, output and total tokens have
 * separate fields, while the provider's complete `usage` object and any
 * top-level cost fields are retained for the planned conversation summary.
 * Missing values are omitted rather than estimated.
 */
class MessageMetadata(
    val createdAt: Long?,
    val requestedModelId: String?,
    val actualModelId: String?,
    val endpointId: String?,
    val endpointLabel: String?,
    val endpointSource: String?,
    val configuredProvider: String?,
    val actualProvider: String?,
    val responseId: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val providerUsageJson: String?,
    val providerCostJson: String?
) {
    fun toJson(): String {
        val json = JSONObject()
        put(json, "createdAt", createdAt)
        put(json, "requestedModelId", requestedModelId)
        put(json, "actualModelId", actualModelId)
        put(json, "endpointId", endpointId)
        put(json, "endpointLabel", endpointLabel)
        put(json, "endpointSource", endpointSource)
        put(json, "configuredProvider", configuredProvider)
        put(json, "actualProvider", actualProvider)
        put(json, "responseId", responseId)

        if (inputTokens != null || outputTokens != null || totalTokens != null ||
            providerUsageJson != null || providerCostJson != null
        ) {
            val usage = JSONObject()
            put(usage, "inputTokens", inputTokens)
            put(usage, "outputTokens", outputTokens)
            put(usage, "totalTokens", totalTokens)
            providerUsageJson?.let { raw -> parseObject(raw)?.let { usage.put("providerDetails", it) } }
            providerCostJson?.let { raw -> parseObject(raw)?.let { usage.put("costDetails", it) } }
            json.put("usage", usage)
        }
        return json.toString()
    }

    fun displayModelId(): String? = actualModelId?.trim()?.ifBlank { null }
        ?: requestedModelId?.trim()?.ifBlank { null }

    fun displayProvider(): String? = actualProvider?.trim()?.ifBlank { null }
        ?: configuredProvider?.trim()?.ifBlank { null }

    fun hasTokenUsage(): Boolean = inputTokens != null || outputTokens != null || totalTokens != null

    companion object {
        const val KEY = "messageMetadata"

        fun createdNow(now: Long = System.currentTimeMillis()): MessageMetadata = MessageMetadata(
            createdAt = now,
            requestedModelId = null,
            actualModelId = null,
            endpointId = null,
            endpointLabel = null,
            endpointSource = null,
            configuredProvider = null,
            actualProvider = null,
            responseId = null,
            inputTokens = null,
            outputTokens = null,
            totalTokens = null,
            providerUsageJson = null,
            providerCostJson = null
        )

        fun fromMessage(message: Map<String, Any?>): MessageMetadata? =
            fromJson(message[KEY]?.toString())

        fun fromJson(raw: String?): MessageMetadata? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                val usage = json.optJSONObject("usage")
                MessageMetadata(
                    createdAt = longOrNull(json, "createdAt"),
                    requestedModelId = stringOrNull(json, "requestedModelId"),
                    actualModelId = stringOrNull(json, "actualModelId"),
                    endpointId = stringOrNull(json, "endpointId"),
                    endpointLabel = stringOrNull(json, "endpointLabel"),
                    endpointSource = stringOrNull(json, "endpointSource"),
                    configuredProvider = stringOrNull(json, "configuredProvider"),
                    actualProvider = stringOrNull(json, "actualProvider"),
                    responseId = stringOrNull(json, "responseId"),
                    inputTokens = usage?.let { longOrNull(it, "inputTokens") },
                    outputTokens = usage?.let { longOrNull(it, "outputTokens") },
                    totalTokens = usage?.let { longOrNull(it, "totalTokens") },
                    providerUsageJson = usage?.optJSONObject("providerDetails")?.toString(),
                    providerCostJson = usage?.optJSONObject("costDetails")?.toString()
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun put(json: JSONObject, key: String, value: Any?) {
            if (value != null && (!(value is String) || value.isNotBlank())) json.put(key, value)
        }

        private fun stringOrNull(json: JSONObject, key: String): String? =
            if (!json.has(key) || json.isNull(key)) null else clean(json.optString(key))

        private fun longOrNull(json: JSONObject, key: String): Long? =
            if (!json.has(key) || json.isNull(key)) null else try {
                json.getLong(key)
            } catch (_: Exception) {
                null
            }

        private fun parseObject(raw: String): JSONObject? = try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }

        private fun clean(value: String?): String? = value?.trim()?.ifBlank { null }
    }
}

/** Mutable facts for one streamed response, pinned to one visible message. */
class MessageMetadataCapture(
    val messageIndex: Int,
    initial: MessageMetadata,
    requestedModelId: String?,
    endpointId: String?,
    endpointLabel: String?,
    endpointSource: String?,
    configuredProvider: String?
) {
    private val createdAt = initial.createdAt
    private val requestedModelId = requestedModelId.clean() ?: initial.requestedModelId
    private val endpointId = endpointId.clean() ?: initial.endpointId
    private val endpointLabel = endpointLabel.clean() ?: initial.endpointLabel
    private val endpointSource = endpointSource.clean() ?: initial.endpointSource
    private val configuredProvider = configuredProvider.clean() ?: initial.configuredProvider

    private var actualModelId: String? = initial.actualModelId
    private var actualProvider: String? = initial.actualProvider
    private var responseId: String? = initial.responseId
    private var inputTokens: Long? = initial.inputTokens
    private var outputTokens: Long? = initial.outputTokens
    private var totalTokens: Long? = initial.totalTokens
    private var providerUsageJson: String? = initial.providerUsageJson
    private var providerCostJson: String? = initial.providerCostJson

    @Volatile
    private var observationExpected = false
    private val observationFinished = CompletableDeferred<Unit>()

    @Synchronized
    fun noteTypedChunk(id: String?, input: Int?, output: Int?, total: Int?) {
        if (responseId == null) responseId = id.clean()
        input?.let { inputTokens = it.toLong() }
        output?.let { outputTokens = it.toLong() }
        total?.let { totalTokens = it.toLong() }
    }

    @Synchronized
    fun noteObserved(metadata: ReportedProviderParser.ResponseMetadata) {
        if (actualModelId == null) actualModelId = metadata.modelId.clean()
        if (actualProvider == null) actualProvider = metadata.provider.clean()
        if (responseId == null) responseId = metadata.responseId.clean()
        metadata.inputTokens?.let { inputTokens = it }
        metadata.outputTokens?.let { outputTokens = it }
        metadata.totalTokens?.let { totalTokens = it }
        metadata.usageJson.clean()?.let { providerUsageJson = it }
        metadata.costJson.clean()?.let { providerCostJson = it }
    }

    fun beginObservation() {
        observationExpected = true
    }

    fun finishObservation() {
        observationFinished.complete(Unit)
    }

    suspend fun awaitObservation(timeoutMs: Long) {
        if (!observationExpected || observationFinished.isCompleted) return
        withTimeoutOrNull(timeoutMs) { observationFinished.await() }
    }

    @Synchronized
    fun snapshot(): MessageMetadata = MessageMetadata(
        createdAt = createdAt,
        requestedModelId = requestedModelId,
        actualModelId = actualModelId,
        endpointId = endpointId,
        endpointLabel = endpointLabel,
        endpointSource = endpointSource,
        configuredProvider = configuredProvider,
        actualProvider = actualProvider,
        responseId = responseId,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        providerUsageJson = providerUsageJson,
        providerCostJson = providerCostJson
    )

    private fun String?.clean(): String? = this?.trim()?.ifBlank { null }
}
