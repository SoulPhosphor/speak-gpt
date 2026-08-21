/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.usage

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.ProviderEndpointInfo
import org.teslasoft.assistant.providers.ProviderEndpointsParser
import java.util.concurrent.TimeUnit

data class TokenPricingCatalog(
    val model: String,
    val providerPrices: List<ProviderEndpointInfo> = emptyList(),
    val modelPricing: TokenPricingSnapshot? = null
) {
    fun pricingFor(provider: String): TokenPricingSnapshot? {
        val providerPrice = providerPrices.firstOrNull {
            it.providerName.equals(provider, ignoreCase = true) ||
                it.slug.equals(provider, ignoreCase = true)
        }
        if (providerPrice != null) {
            return TokenPricingSnapshot(providerPrice.promptPrice, providerPrice.completionPrice)
                .takeIf { it.inputPricePerToken != null || it.outputPricePerToken != null }
        }
        return modelPricing
    }
}

/** Reads pricing concurrently with generation so turn completion normally only
 * awaits an already-finished catalog request. The returned values are frozen
 * into the completed usage record; Quick Settings never fetches new prices for
 * records that already carry a snapshot. */
object TokenPricingCatalogClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun load(endpoint: ApiEndpointObject?, model: String): TokenPricingCatalog =
        withContext(Dispatchers.IO) {
            if (endpoint == null || model.isBlank()) {
                return@withContext TokenPricingCatalog(
                    model, modelPricing = legacyPricingFor(endpoint, model)
                )
            }
            val remote = try {
                if (endpoint.isOpenRouterRouting()) loadOpenRouter(endpoint, model)
                else loadGeneric(endpoint, model)
            } catch (_: Exception) {
                null
            }
            remote ?: TokenPricingCatalog(
                model, modelPricing = legacyPricingFor(endpoint, model)
            )
        }

    /** Static compatibility prices are authoritative only for the official
     * OpenAI API host. A matching model name on OpenRouter, a proxy, or another
     * serving provider is not evidence that OpenAI's price applies. */
    internal fun legacyPricingFor(
        endpoint: ApiEndpointObject?,
        model: String
    ): TokenPricingSnapshot? {
        val host = endpoint?.host?.toHttpUrlOrNull()?.host ?: return null
        if (!host.equals("api.openai.com", ignoreCase = true)) return null
        return LegacyTokenPricing.forModel(model)
    }

    private fun loadOpenRouter(endpoint: ApiEndpointObject, model: String): TokenPricingCatalog? {
        val base = endpoint.host.trimEnd('/')
        if (base.isBlank()) return null
        val path = endpoint.providerDiscoveryPath
            .ifBlank { ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH }
            .replace("{model}", model)
        val body = fetch(endpoint, base + path) ?: return null
        val parsed = ProviderEndpointsParser.parse(body) ?: return null
        return TokenPricingCatalog(model, providerPrices = parsed.endpoints)
    }

    private fun loadGeneric(endpoint: ApiEndpointObject, model: String): TokenPricingCatalog? {
        val base = endpoint.host.toHttpUrlOrNull() ?: return null
        val body = fetch(endpoint, base.newBuilder().addPathSegment("models").build().toString())
            ?: return null
        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val item = data.firstOrNull { element ->
            element.isJsonObject && element.asJsonObject.get("id")
                ?.takeIf { it.isJsonPrimitive }?.asString == model
        }?.asJsonObject ?: return null
        val pricing = item.get("pricing")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        fun number(name: String): Double? = try {
            pricing.get(name)?.takeIf { it.isJsonPrimitive }?.asDouble
        } catch (_: Exception) { null }
        val snapshot = TokenPricingSnapshot(number("prompt"), number("completion"))
        return TokenPricingCatalog(model, modelPricing = snapshot)
    }

    private fun fetch(endpoint: ApiEndpointObject, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                when (endpoint.authType) {
                    ApiEndpointObject.AUTH_X_API_KEY -> header("x-api-key", endpoint.apiKey)
                    ApiEndpointObject.AUTH_API_KEY -> header("api-key", endpoint.apiKey)
                    else -> header("Authorization", "Bearer ${endpoint.apiKey}")
                }
            }
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }
}

/** Compatibility fallback used only for the official OpenAI API when it does
 * not publish pricing. Values are per token, matching the old Quick Settings
 * calculation. */
object LegacyTokenPricing {
    fun forModel(model: String): TokenPricingSnapshot? {
        val normalized = model.lowercase()
        val pair = when {
            "gpt-4o-mini-audio-preview" in normalized -> 0.0000015 to 0.000006
            "gpt-4o-mini-realtime-preview" in normalized -> 0.000006 to 0.000024
            "gpt-4o-audio-preview" in normalized -> 0.000025 to 0.0001
            "gpt-4o-realtime-preview" in normalized -> 0.00005 to 0.0002
            "gpt-4o-mini" in normalized -> 0.0000015 to 0.000006
            "gpt-4o" in normalized -> 0.000025 to 0.0001
            "o1-mini" in normalized || "o3-mini" in normalized -> 0.000011 to 0.000044
            normalized == "o1" || normalized.startsWith("o1-") -> 0.00015 to 0.0006
            else -> return null
        }
        return TokenPricingSnapshot(pair.first, pair.second)
    }
}
