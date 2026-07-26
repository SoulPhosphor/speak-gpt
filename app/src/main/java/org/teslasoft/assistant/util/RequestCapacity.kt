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

import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy

/** Immutable, provider-neutral text message in a frozen chat request. */
data class FrozenPayloadMessage(val role: String, val content: String)

/**
 * All fields SpeakGPT sends for a normal streaming chat request.
 *
 * Lists and maps are copied on construction so later UI, memory or attachment
 * changes cannot mutate the request after measurement.
 */
data class FrozenChatPayload(
    val model: String,
    val messages: List<FrozenPayloadMessage>,
    val maximumResponseTokens: Int,
    val temperature: Double?,
    val topP: Double?,
    val frequencyPenalty: Double?,
    val presencePenalty: Double?,
    val seed: Int?,
    val logitBias: Map<String, Int>?
) {
    val frozenMessages: List<FrozenPayloadMessage> = messages.toList()
    val frozenLogitBias: Map<String, Int>? = logitBias?.toMap()
}

data class SerializedRequestMeasurement(
    val requestCharacters: Long,
    val serializedUtf8Bytes: Long
)

data class RequestHeapState(
    val heapLimit: Long,
    val heapUsed: Long
) {
    companion object {
        fun current(runtime: Runtime = Runtime.getRuntime()): RequestHeapState =
            RequestHeapState(
                heapLimit = runtime.maxMemory(),
                heapUsed = runtime.totalMemory() - runtime.freeMemory()
            )
    }
}

/**
 * Counts the compact JSON which the frozen payload represents without
 * allocating a source-sized JSON String or UTF-8 ByteArray.
 */
object RequestCapacity {
    const val FIXED_REQUEST_OBJECT_OVERHEAD = 1L * 1024L * 1024L

    fun measure(payload: FrozenChatPayload): SerializedRequestMeasurement {
        val counter = JsonSizeCounter()
        counter.ascii('{')
        counter.fieldName("model")
        counter.string(payload.model)
        counter.ascii(',')
        counter.fieldName("max_tokens")
        counter.number(payload.maximumResponseTokens)
        counter.ascii(',')
        counter.fieldName("messages")
        counter.ascii('[')
        payload.frozenMessages.forEachIndexed { index, message ->
            if (index > 0) counter.ascii(',')
            counter.ascii('{')
            counter.fieldName("role")
            counter.string(message.role)
            counter.ascii(',')
            counter.fieldName("content")
            counter.string(message.content)
            counter.ascii('}')
        }
        counter.ascii(']')
        payload.temperature?.let {
            counter.ascii(',')
            counter.fieldName("temperature")
            counter.number(it)
        }
        payload.topP?.let {
            counter.ascii(',')
            counter.fieldName("top_p")
            counter.number(it)
        }
        payload.frequencyPenalty?.let {
            counter.ascii(',')
            counter.fieldName("frequency_penalty")
            counter.number(it)
        }
        payload.presencePenalty?.let {
            counter.ascii(',')
            counter.fieldName("presence_penalty")
            counter.number(it)
        }
        payload.seed?.let {
            counter.ascii(',')
            counter.fieldName("seed")
            counter.number(it)
        }
        payload.frozenLogitBias?.let { biases ->
            counter.ascii(',')
            counter.fieldName("logit_bias")
            counter.ascii('{')
            biases.entries.forEachIndexed { index, entry ->
                if (index > 0) counter.ascii(',')
                counter.string(entry.key.toString())
                counter.ascii(':')
                counter.number(entry.value)
            }
            counter.ascii('}')
        }
        counter.ascii(',')
        counter.fieldName("stream")
        counter.literal("true")
        counter.ascii('}')
        return counter.measurement()
    }

    fun estimatedAdditionalMemory(
        measurement: SerializedRequestMeasurement
    ): Long {
        val characterCopies = saturatedMultiply(measurement.requestCharacters, 4L)
        return saturatedAdd(
            saturatedAdd(characterCopies, measurement.serializedUtf8Bytes),
            FIXED_REQUEST_OBJECT_OVERHEAD
        )
    }

    fun canAssemble(
        measurement: SerializedRequestMeasurement,
        heap: RequestHeapState
    ): Boolean {
        val safetyReserve = maxOf(32L * 1024L * 1024L, heap.heapLimit / 4L)
        val required = saturatedAdd(
            saturatedAdd(heap.heapUsed, estimatedAdditionalMemory(measurement)),
            safetyReserve
        )
        return required <= heap.heapLimit
    }

    /**
     * No exact model/framing mapping is assumed. The app's CL100K usage
     * display is therefore represented honestly as one approximate value.
     */
    fun approximateInputTokens(payload: FrozenChatPayload): TokenMeasurement {
        var total = 0L
        for (message in payload.frozenMessages) {
            total += IncludeTextPolicy.estimateTokens(message.content).toLong()
            // Approximate role and per-message framing, never presented exact.
            total += IncludeTextPolicy.estimateTokens(message.role).toLong() + 4L
        }
        total += IncludeTextPolicy.estimateTokens(payload.model).toLong() + 3L
        return TokenMeasurement.Approximate(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun saturatedMultiply(value: Long, multiplier: Long): Long =
        if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

    private class JsonSizeCounter {
        private var characters = 0L
        private var utf8Bytes = 0L

        fun ascii(char: Char) {
            characters++
            utf8Bytes++
        }

        fun literal(value: String) {
            characters += value.length
            utf8Bytes += value.length
        }

        fun fieldName(value: String) {
            string(value)
            ascii(':')
        }

        fun number(value: Number) = literal(value.toString())

        fun string(value: String) {
            ascii('"')
            var index = 0
            while (index < value.length) {
                val char = value[index]
                when (char) {
                    '"', '\\' -> {
                        characters += 2
                        utf8Bytes += 2
                    }
                    '\b', '\u000C', '\n', '\r', '\t' -> {
                        characters += 2
                        utf8Bytes += 2
                    }
                    else -> when {
                        char.code < 0x20 -> {
                            characters += 6
                            utf8Bytes += 6
                        }
                        char.code <= 0x7F -> {
                            characters++
                            utf8Bytes++
                        }
                        char.code <= 0x7FF -> {
                            characters++
                            utf8Bytes += 2
                        }
                        Character.isHighSurrogate(char) &&
                            index + 1 < value.length &&
                            Character.isLowSurrogate(value[index + 1]) -> {
                            characters += 2
                            utf8Bytes += 4
                            index++
                        }
                        else -> {
                            characters++
                            utf8Bytes += 3
                        }
                    }
                }
                index++
            }
            ascii('"')
        }

        fun measurement() = SerializedRequestMeasurement(characters, utf8Bytes)
    }
}

sealed class TokenMeasurement {
    data class Exact(val value: Int) : TokenMeasurement()
    data class Range(val minimum: Int, val maximum: Int) : TokenMeasurement()
    data class Approximate(val value: Int) : TokenMeasurement()
    data object Unknown : TokenMeasurement()
}

sealed class ModelContextDecision {
    data object Send : ModelContextDecision()
    data class Block(val requiredAtLeast: Int, val contextWindow: Int) :
        ModelContextDecision()
    data class WarnRange(
        val minimumRequired: Int,
        val maximumRequired: Int,
        val contextWindow: Int
    ) : ModelContextDecision()
    data class WarnApproximate(val approximateRequired: Int, val contextWindow: Int) :
        ModelContextDecision()
}

object ModelContextCapacity {
    fun decide(
        contextWindow: Int?,
        input: TokenMeasurement,
        maximumResponseTokens: Int
    ): ModelContextDecision {
        val context = contextWindow?.takeIf { it > 0 } ?: return ModelContextDecision.Send
        val output = maximumResponseTokens.coerceAtLeast(0)
        return when (input) {
            is TokenMeasurement.Exact -> {
                val required = addTokens(input.value, output)
                if (required > context) {
                    ModelContextDecision.Block(required, context)
                } else {
                    ModelContextDecision.Send
                }
            }
            is TokenMeasurement.Range -> {
                val minimum = addTokens(input.minimum, output)
                val maximum = addTokens(input.maximum, output)
                when {
                    maximum <= context -> ModelContextDecision.Send
                    minimum > context -> ModelContextDecision.Block(minimum, context)
                    else -> ModelContextDecision.WarnRange(minimum, maximum, context)
                }
            }
            is TokenMeasurement.Approximate -> {
                val required = addTokens(input.value, output)
                if (required > context) {
                    ModelContextDecision.WarnApproximate(required, context)
                } else {
                    ModelContextDecision.Send
                }
            }
            TokenMeasurement.Unknown -> ModelContextDecision.Send
        }
    }

    private fun addTokens(input: Int, output: Int): Int {
        val total = input.toLong().coerceAtLeast(0) + output.toLong()
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
