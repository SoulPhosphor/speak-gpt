/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.providers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Which side of a content-policy decision the provider actually identified. */
enum class ContentFilterSide(val wire: String) {
    NONE("none"),
    INPUT("input"),
    OUTPUT("output"),
    AMBIGUOUS("ambiguous")
}

enum class ProviderDiagnosticSource(val wire: String) {
    HTTP_RESPONSE("http_response"),
    SSE_EVENT("sse_event"),
    TYPED_CHUNK("typed_chunk")
}

/**
 * One provider-authored diagnostic event. [rawPayload] and [message] are kept
 * exactly as received (apart from rejecting blank strings). Parsed fields are
 * additional facts; they never replace or rewrite the provider's payload.
 */
data class ProviderDiagnosticEvent(
    val source: ProviderDiagnosticSource,
    val isError: Boolean,
    val isWarning: Boolean,
    val message: String? = null,
    val additionalMessages: List<String> = emptyList(),
    val rawPayload: String? = null,
    val code: String? = null,
    val type: String? = null,
    val errorType: String? = null,
    val embeddedHttpStatus: Int? = null,
    val actualServingProvider: String? = null,
    val contentFilterSide: ContentFilterSide = ContentFilterSide.NONE
)

/** Immutable, request-scoped evidence consumed by classification and display. */
data class ProviderDiagnosticSnapshot(
    val attemptId: String,
    val outerHttpStatus: Int? = null,
    val actualServingProvider: String? = null,
    val finishReason: String? = null,
    val generationId: String? = null,
    val partialContentCharacters: Int = 0,
    val reasoningCharacters: Int = 0,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val malformedPayloadCount: Int = 0,
    val events: List<ProviderDiagnosticEvent> = emptyList()
) {
    val errorEvents: List<ProviderDiagnosticEvent> get() = events.filter { it.isError }
    val warningEvents: List<ProviderDiagnosticEvent> get() = events.filter { it.isWarning }

    val primaryError: ProviderDiagnosticEvent?
        get() = errorEvents.firstOrNull { !it.message.isNullOrBlank() }
            ?: errorEvents.firstOrNull()

    val embeddedHttpStatus: Int?
        get() = errorEvents.firstNotNullOfOrNull { it.embeddedHttpStatus }

    val providerCode: String? get() = primaryError?.code
    val providerType: String? get() = primaryError?.type
    val providerErrorType: String? get() = primaryError?.errorType

    val contentFilterSide: ContentFilterSide
        get() = combinedFilterSide(events)

    val errorContentFilterSide: ContentFilterSide
        get() = combinedFilterSide(errorEvents)

    private fun combinedFilterSide(sourceEvents: List<ProviderDiagnosticEvent>): ContentFilterSide {
            val sides = sourceEvents.map { it.contentFilterSide }
                .filter { it != ContentFilterSide.NONE }
                .toSet()
            return when {
                sides.isEmpty() -> ContentFilterSide.NONE
                sides.size == 1 -> sides.first()
                else -> ContentFilterSide.AMBIGUOUS
            }
    }

    /** Exact provider messages, de-duplicated without normalizing their text. */
    val errorMessages: List<String>
        get() = errorEvents.flatMap { event ->
            listOfNotNull(event.message?.takeIf(String::isNotBlank)) +
                event.additionalMessages.filter(String::isNotBlank)
        }.distinct()

    val warningMessages: List<String>
        get() = warningEvents.flatMap { event ->
            listOfNotNull(event.message?.takeIf(String::isNotBlank)) +
                event.additionalMessages.filter(String::isNotBlank)
        }.distinct()

    val providerResponded: Boolean
        get() = outerHttpStatus != null || events.any { it.source != ProviderDiagnosticSource.TYPED_CHUNK }
}

/**
 * Provider-shape normalizer. It handles the common OpenAI/OpenRouter envelope,
 * Anthropic's error envelope, Gemini safety/error fields, Responses API events,
 * and plain-text HTTP bodies. UI code never needs provider-specific branches.
 */
object ProviderDiagnosticParser {

    fun parseHttpBody(rawBody: String?, outerStatus: Int): List<ProviderDiagnosticEvent> {
        if (rawBody.isNullOrBlank()) return emptyList()
        return parsePayload(rawBody, ProviderDiagnosticSource.HTTP_RESPONSE, forceError = true)
    }

    fun parseSsePayload(payload: String): List<ProviderDiagnosticEvent> =
        parsePayload(payload, ProviderDiagnosticSource.SSE_EVENT, forceError = false)

    private fun parsePayload(
        rawPayload: String,
        source: ProviderDiagnosticSource,
        forceError: Boolean
    ): List<ProviderDiagnosticEvent> {
        val parsed = try {
            JsonParser.parseString(rawPayload)
        } catch (_: Exception) {
            return if (forceError) {
                listOf(
                    ProviderDiagnosticEvent(
                        source = source,
                        isError = true,
                        isWarning = false,
                        message = rawPayload,
                        rawPayload = rawPayload
                    )
                )
            } else {
                emptyList()
            }
        }
        if (!parsed.isJsonObject) {
            return if (forceError) {
                listOf(
                    ProviderDiagnosticEvent(
                        source = source,
                        isError = true,
                        isWarning = false,
                        message = rawPayload,
                        rawPayload = rawPayload
                    )
                )
            } else emptyList()
        }

        val root = parsed.asJsonObject
        val result = mutableListOf<ProviderDiagnosticEvent>()
        val actualProvider = ReportedProviderParser.providerFromRoot(root)
            ?: root.string("provider_name")
            ?: root.obj("error")?.obj("metadata")?.string("provider_name")

        val finishReason = root.finishReason()
        val rootType = root.string("type")
        val responseError = root.obj("response")?.get("error")
        val errorElement = root.get("error")?.takeUnless { it.isJsonNull } ?: responseError
        val explicitlyFailed = rootType.equals("error", true) ||
            rootType.equals("response.failed", true) ||
            rootType.equals("response.incomplete", true) ||
            finishReason.equals("error", true)

        if (errorElement != null || forceError || explicitlyFailed) {
            result += errorEvent(
                source = source,
                rawPayload = rawPayload,
                root = root,
                errorElement = errorElement,
                provider = actualProvider,
                finishReason = finishReason,
                forceError = forceError || explicitlyFailed
            )
        }

        collectWarnings(root).forEach { warning ->
            result += ProviderDiagnosticEvent(
                source = source,
                isError = false,
                isWarning = true,
                message = warning,
                rawPayload = rawPayload,
                actualServingProvider = actualProvider,
                contentFilterSide = filterSide(root, finishReason, null, warning)
            )
        }

        collectModerationFindings(root).forEach { finding ->
            result += ProviderDiagnosticEvent(
                source = source,
                isError = false,
                isWarning = true,
                message = finding.message,
                rawPayload = rawPayload,
                actualServingProvider = actualProvider,
                contentFilterSide = finding.side
            )
        }

        // Gemini-compatible responses may terminate a candidate for safety
        // without using the OpenAI `error` member.
        val geminiSide = geminiSafetySide(root)
        if (geminiSide != ContentFilterSide.NONE && result.none { it.isError }) {
            val message = geminiSafetyMessage(root, geminiSide)
            result += ProviderDiagnosticEvent(
                source = source,
                isError = true,
                isWarning = false,
                message = message,
                rawPayload = rawPayload,
                actualServingProvider = actualProvider,
                contentFilterSide = geminiSide
            )
        }

        // OpenAI-compatible `finish_reason=content_filter` is output-side
        // evidence even when no top-level error object is present.
        if (finishReason.equals("content_filter", true) && result.none { it.isError }) {
            result += ProviderDiagnosticEvent(
                source = source,
                isError = true,
                isWarning = false,
                message = "content_filter",
                rawPayload = rawPayload,
                actualServingProvider = actualProvider,
                contentFilterSide = ContentFilterSide.OUTPUT
            )
        }
        return result
    }

    private fun errorEvent(
        source: ProviderDiagnosticSource,
        rawPayload: String,
        root: JsonObject,
        errorElement: JsonElement?,
        provider: String?,
        finishReason: String?,
        forceError: Boolean
    ): ProviderDiagnosticEvent {
        val error = errorElement?.takeIf { it.isJsonObject }?.asJsonObject
        val metadata = error?.obj("metadata")
        val rawUpstream = metadata?.get("raw")?.takeUnless { it.isJsonNull }
        val rawMessage = when {
            rawUpstream == null -> null
            rawUpstream.isJsonPrimitive -> rawUpstream.asString.takeIf(String::isNotBlank)
            else -> rawUpstream.toString()
        }
        val ordinaryMessage = when {
            errorElement?.isJsonPrimitive == true -> errorElement.asString.takeIf(String::isNotBlank)
            else -> error?.string("message")
                ?: root.string("message")
                ?: root.obj("response")?.obj("error")?.string("message")
        }
        val message = when {
            rawMessage != null -> rawMessage
            ordinaryMessage != null -> ordinaryMessage
            forceError -> rawPayload
            else -> null
        }
        val code = error?.primitiveText("code")
            ?: root.obj("response")?.obj("error")?.primitiveText("code")
            ?: root.primitiveText("code")
        val type = error?.string("type")
            ?: root.obj("response")?.obj("error")?.string("type")
            ?: root.string("type")
        val errorType = metadata?.string("error_type")
            ?: error?.string("error_type")
            ?: root.string("error_type")
        val embeddedStatus = listOf(code, error?.primitiveText("status"), root.primitiveText("status"))
            .firstNotNullOfOrNull { it?.toIntOrNull()?.takeIf { value -> value in 100..599 } }
        return ProviderDiagnosticEvent(
            source = source,
            isError = true,
            isWarning = false,
            message = message,
            additionalMessages = listOfNotNull(
                ordinaryMessage?.takeIf {
                    rawMessage != null && it != rawMessage &&
                        !it.equals("Provider returned error", ignoreCase = true)
                }
            ),
            rawPayload = rawPayload,
            code = code,
            type = type,
            errorType = errorType,
            embeddedHttpStatus = embeddedStatus,
            actualServingProvider = metadata?.string("provider_name") ?: provider,
            contentFilterSide = filterSide(root, finishReason, error, message)
        )
    }

    private fun collectWarnings(root: JsonObject): List<String> {
        val out = mutableListOf<String>()
        fun add(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            when {
                element.isJsonPrimitive -> element.asString.takeIf(String::isNotBlank)?.let(out::add)
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    (obj.string("message") ?: obj.string("warning") ?: obj.toString())
                        .takeIf(String::isNotBlank)?.let(out::add)
                }
                element.isJsonArray -> element.asJsonArray.forEach(::add)
            }
        }
        add(root.get("warning"))
        add(root.get("warnings"))
        return out.distinct()
    }

    private data class ModerationFinding(
        val side: ContentFilterSide,
        val message: String
    )

    /** Only explicit `filtered=true` / `flagged=true` facts count. Scores and
     * category names alone are not warnings and never trigger attribution. */
    private fun collectModerationFindings(root: JsonObject): List<ModerationFinding> {
        val findings = mutableListOf<ModerationFinding>()
        fun filteredCategories(element: JsonElement?): List<String> {
            if (element == null || element.isJsonNull) return emptyList()
            if (element.isJsonArray) return element.asJsonArray.flatMap(::filteredCategories)
            if (!element.isJsonObject) return emptyList()
            val obj = element.asJsonObject
            val out = mutableListOf<String>()
            for ((name, value) in obj.entrySet()) {
                when {
                    name.equals("filtered", true) && value.isJsonPrimitive &&
                        runCatching { value.asBoolean }.getOrDefault(false) -> out += "filtered"
                    name.equals("flagged", true) && value.isJsonPrimitive &&
                        runCatching { value.asBoolean }.getOrDefault(false) -> out += "flagged"
                    value.isJsonObject || value.isJsonArray -> {
                        val nested = filteredCategories(value)
                        if (nested.isNotEmpty()) {
                            if (nested.all { it == "filtered" || it == "flagged" }) {
                                out += name
                            } else {
                                out.addAll(nested)
                            }
                        }
                    }
                }
            }
            return out.distinct()
        }
        val promptCategories = filteredCategories(root.get("prompt_filter_results"))
        if (promptCategories.isNotEmpty()) {
            findings += ModerationFinding(
                ContentFilterSide.INPUT,
                "Provider moderation result: input filtered (${promptCategories.joinToString(", ")})"
            )
        }
        val outputCategories = mutableListOf<String>()
        root.array("choices")?.forEach { choiceElement ->
            val choice = choiceElement.takeIf { it.isJsonObject }?.asJsonObject
            outputCategories.addAll(filteredCategories(choice?.get("content_filter_results")))
        }
        val distinctOutputCategories = outputCategories.distinct()
        if (distinctOutputCategories.isNotEmpty()) {
            findings += ModerationFinding(
                ContentFilterSide.OUTPUT,
                "Provider moderation result: generated output filtered (${distinctOutputCategories.joinToString(", ")})"
            )
        }
        val generic = filteredCategories(root.get("moderation")) +
            filteredCategories(root.get("moderation_result")) +
            filteredCategories(root.get("moderation_results"))
        if (generic.isNotEmpty()) {
            findings += ModerationFinding(
                ContentFilterSide.AMBIGUOUS,
                "Provider moderation result: content flagged (${generic.distinct().joinToString(", ")})"
            )
        }
        return findings
    }

    private fun filterSide(
        root: JsonObject,
        finishReason: String?,
        error: JsonObject?,
        message: String?
    ): ContentFilterSide {
        if (finishReason.equals("content_filter", true) ||
            finishReason.equals("safety", true) ||
            finishReason.equals("prohibited_content", true)
        ) return ContentFilterSide.OUTPUT

        if (geminiSafetySide(root) != ContentFilterSide.NONE) return geminiSafetySide(root)

        val structured = listOfNotNull(
            error?.string("code"), error?.string("type"), error?.string("error_type"),
            error?.obj("metadata")?.string("error_type"), root.string("error_type")
        ).joinToString(" ").lowercase()
        if (structuredTokens(structured, "output_filter", "output_blocked", "response_filter", "generated_content_filter")) {
            return ContentFilterSide.OUTPUT
        }
        if (structuredTokens(structured, "input_filter", "input_blocked", "prompt_filter", "prompt_blocked")) {
            return ContentFilterSide.INPUT
        }
        if (structuredTokens(structured, "content_filter", "content_policy_violation", "moderation_blocked", "safety")) {
            return when {
                message?.contains("Output data may contain inappropriate content", ignoreCase = true) == true ->
                    ContentFilterSide.OUTPUT
                message?.contains("Input data may contain inappropriate content", ignoreCase = true) == true ->
                    ContentFilterSide.INPUT
                else -> ContentFilterSide.AMBIGUOUS
            }
        }
        return when {
            message?.contains("Output data may contain inappropriate content", ignoreCase = true) == true ->
                ContentFilterSide.OUTPUT
            message?.contains("Input data may contain inappropriate content", ignoreCase = true) == true ->
                ContentFilterSide.INPUT
            else -> ContentFilterSide.NONE
        }
    }

    private fun structuredTokens(value: String, vararg exactTokens: String): Boolean {
        if (value.isBlank()) return false
        val tokens = value.split(Regex("[^a-z0-9_]+"))
        return exactTokens.any(tokens::contains)
    }

    private fun geminiSafetySide(root: JsonObject): ContentFilterSide {
        val promptFeedback = root.obj("promptFeedback") ?: root.obj("prompt_feedback")
        if (promptFeedback?.primitiveText("blockReason")?.isNotBlank() == true ||
            promptFeedback?.primitiveText("block_reason")?.isNotBlank() == true
        ) return ContentFilterSide.INPUT
        val candidates = root.array("candidates") ?: return ContentFilterSide.NONE
        for (candidateElement in candidates) {
            val candidate = candidateElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val reason = candidate.primitiveText("finishReason")
                ?: candidate.primitiveText("finish_reason")
            if (reason.equals("SAFETY", true) || reason.equals("PROHIBITED_CONTENT", true) ||
                reason.equals("BLOCKLIST", true) || reason.equals("IMAGE_SAFETY", true)
            ) return ContentFilterSide.OUTPUT
        }
        return ContentFilterSide.NONE
    }

    private fun geminiSafetyMessage(root: JsonObject, side: ContentFilterSide): String {
        val promptFeedback = root.obj("promptFeedback") ?: root.obj("prompt_feedback")
        if (side == ContentFilterSide.INPUT) {
            return promptFeedback?.primitiveText("blockReason")
                ?: promptFeedback?.primitiveText("block_reason")
                ?: "Provider blocked the input for safety."
        }
        val candidates = root.array("candidates")
        candidates?.forEach { element ->
            val candidate = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val reason = candidate.primitiveText("finishReason")
                ?: candidate.primitiveText("finish_reason")
            if (!reason.isNullOrBlank()) return reason
        }
        return "Provider stopped generated output for safety."
    }
}

private fun JsonObject.obj(name: String): JsonObject? = try {
    get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject
} catch (_: Exception) { null }

private fun JsonObject.array(name: String): JsonArray? = try {
    get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonArray }?.asJsonArray
} catch (_: Exception) { null }

private fun JsonObject.string(name: String): String? = try {
    get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf(String::isNotBlank)
} catch (_: Exception) { null }

private fun JsonObject.primitiveText(name: String): String? = string(name)

private fun JsonObject.finishReason(): String? {
    array("choices")?.forEach { element ->
        val choice = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        choice.string("finish_reason")?.let { return it }
    }
    return obj("response")?.string("status")
}
