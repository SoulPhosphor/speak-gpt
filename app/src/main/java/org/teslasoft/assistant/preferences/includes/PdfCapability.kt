/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import org.json.JSONObject
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import java.net.URI

enum class PdfCapability(val key: String) {
    UNKNOWN("unknown"), SUPPORTED("supported"), UNSUPPORTED("unsupported");
    companion object {
        fun fromKey(key: String?): PdfCapability =
            entries.firstOrNull { it.key.equals(key, true) } ?: UNKNOWN
    }
}

object PdfCapabilityStore {
    const val EMPTY = "{}"
    private const val METADATA = "@metadata:"
    private const val ROUTE = "@route:"

    fun get(json: String?, key: String): PdfCapability = try {
        PdfCapability.fromKey(JSONObject(json ?: EMPTY).optString(key))
    } catch (_: Exception) { PdfCapability.UNKNOWN }

    fun set(json: String?, key: String, value: PdfCapability): String {
        val objectValue = try { JSONObject(json ?: EMPTY) } catch (_: Exception) { JSONObject() }
        if (value == PdfCapability.UNKNOWN) objectValue.remove(key) else objectValue.put(key, value.key)
        return objectValue.toString()
    }

    fun getMetadata(json: String?, model: String) = get(json, METADATA + model.trim())
    fun setMetadata(json: String?, model: String, value: PdfCapability) =
        set(json, METADATA + model.trim(), value)
    fun getRoute(json: String?, scope: String) = get(json, ROUTE + scope)
    fun setRoute(json: String?, scope: String, value: PdfCapability) = set(json, ROUTE + scope, value)
}

enum class PdfCapabilityProvider {
    OPENROUTER, NANOGPT, FEATHERLESS, OPENAI, ANTHROPIC, GEMINI, XAI, GENERIC;

    companion object {
        fun forEndpoint(endpoint: ApiEndpointObject): PdfCapabilityProvider {
            if (endpoint.hasOpenRouterCatalogAuthority()) return OPENROUTER
            val host = try { URI(endpoint.host.trim()).host.orEmpty().lowercase() } catch (_: Exception) { "" }
            val label = endpoint.provider.lowercase()
            return when {
                host == "nano-gpt.com" || host.endsWith(".nano-gpt.com") -> NANOGPT
                host == "api.featherless.ai" -> FEATHERLESS
                host == "api.openai.com" || label == "openai" -> OPENAI
                host == "api.anthropic.com" || label == "anthropic" -> ANTHROPIC
                host.endsWith("generativelanguage.googleapis.com") || label == "gemini" -> GEMINI
                host == "api.x.ai" || label == "xai" || label == "x.ai" -> XAI
                else -> GENERIC
            }
        }
    }
}

data class PdfRoutingConfig(
    val provider: PdfCapabilityProvider,
    val routingType: String,
    val pinnedProvider: String?,
    val allowFallbacks: Boolean
) {
    val pinned: Boolean get() =
        (provider == PdfCapabilityProvider.OPENROUTER || provider == PdfCapabilityProvider.NANOGPT) &&
            routingType == FavoriteModelObject.ROUTING_ONLY && !pinnedProvider.isNullOrBlank()

    companion object {
        fun from(endpoint: ApiEndpointObject, favorite: FavoriteModelObject?) = PdfRoutingConfig(
            PdfCapabilityProvider.forEndpoint(endpoint),
            favorite?.routingType ?: FavoriteModelObject.ROUTING_AUTOMATIC,
            favorite?.selectedProvider?.trim()?.ifBlank { null },
            favorite?.allowFallbacks != false
        )
    }
}

object PdfCapabilityResolver {
    /** UNKNOWN intentionally means local fallback, never an optimistic native send. */
    fun resolve(
        endpoint: ApiEndpointObject,
        modelId: String,
        routing: PdfRoutingConfig = PdfRoutingConfig.from(endpoint, null),
        liveModelEvidence: PdfCapability = PdfCapability.UNKNOWN,
        livePinnedProviderEvidence: PdfCapability = PdfCapability.UNKNOWN
    ): PdfCapability {
        val stored = endpoint.pdfCapabilityByModel
        val modelEvidence = first(
            liveModelEvidence,
            PdfCapabilityStore.getMetadata(stored, modelId),
            PdfCapabilityStore.get(stored, modelId)
        )
        if (modelEvidence == PdfCapability.UNSUPPORTED) return PdfCapability.UNSUPPORTED
        val scope = scope(endpoint, modelId, routing)
        val routeEvidence = first(livePinnedProviderEvidence, PdfCapabilityStore.getRoute(stored, scope))
        if (routing.pinned) {
            if (routeEvidence == PdfCapability.UNSUPPORTED) return PdfCapability.UNSUPPORTED
            return if (modelEvidence == PdfCapability.SUPPORTED && routeEvidence == PdfCapability.SUPPORTED) {
                PdfCapability.SUPPORTED
            } else PdfCapability.UNKNOWN
        }
        first(modelEvidence, routeEvidence).takeIf { it != PdfCapability.UNKNOWN }?.let { return it }
        return documentedDirectDefault(routing.provider, modelId)
    }

    fun scope(endpoint: ApiEndpointObject, modelId: String, routing: PdfRoutingConfig): String =
        listOf(
            "v1", routing.provider.name.lowercase(), endpoint.host.trim().trimEnd('/').lowercase(),
            modelId.trim(), if (routing.pinned) "only:${routing.pinnedProvider!!.lowercase()}" else "direct-or-routed",
            "pdf"
        ).joinToString("|")

    private fun documentedDirectDefault(provider: PdfCapabilityProvider, model: String): PdfCapability = when (provider) {
        PdfCapabilityProvider.FEATHERLESS -> PdfCapability.UNSUPPORTED
        PdfCapabilityProvider.ANTHROPIC -> if (model.startsWith("claude", true)) PdfCapability.SUPPORTED else PdfCapability.UNKNOWN
        PdfCapabilityProvider.GEMINI -> if (model.startsWith("gemini", true)) PdfCapability.SUPPORTED else PdfCapability.UNKNOWN
        PdfCapabilityProvider.OPENAI -> if (listOf("gpt-4o", "gpt-4.1", "gpt-5", "gpt-6", "o1", "o3", "o4")
                .any { model.startsWith(it, true) }) PdfCapability.SUPPORTED else PdfCapability.UNKNOWN
        // xAI publishes no per-model file-input flag (its model metadata lists
        // only text/image input). These are the models xAI's own SDK and
        // cookbook attach files to; every other Grok model stays on local text.
        PdfCapabilityProvider.XAI -> if (model.trim().lowercase() in XAI_FILE_INPUT_MODELS)
            PdfCapability.SUPPORTED else PdfCapability.UNKNOWN
        else -> PdfCapability.UNKNOWN
    }

    private val XAI_FILE_INPUT_MODELS = setOf("grok-4.20", "grok-4.7")

    private fun first(vararg values: PdfCapability) =
        values.firstOrNull { it != PdfCapability.UNKNOWN } ?: PdfCapability.UNKNOWN
}
