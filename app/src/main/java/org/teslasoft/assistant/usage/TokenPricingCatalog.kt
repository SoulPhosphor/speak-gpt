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
            return TokenPricingSnapshot(
                providerPrice.promptPrice,
                providerPrice.completionPrice,
                providerPrice.cacheReadPrice,
                providerPrice.cacheWritePrice
            ).takeIf {
                it.inputPricePerToken != null || it.outputPricePerToken != null ||
                    it.cachedInputPricePerToken != null ||
                    it.cacheWriteInputPricePerToken != null
            }
        }
        return modelPricing
    }
}

/** Reads pricing concurrently with generation so turn completion normally only
 * awaits an already-finished catalog request. The returned values are frozen
 * into the completed usage record; the Usage & Cost screen never fetches new prices for
 * records that already carry a snapshot. */
object TokenPricingCatalogClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val OPENROUTER_PUBLIC_MODELS_URL = "https://openrouter.ai/api/v1/models"
    private const val PUBLIC_CATALOG_TTL_MS = 6L * 60 * 60 * 1000

    @Volatile private var publicCatalogCache: Pair<Long, String>? = null

    suspend fun load(endpoint: ApiEndpointObject?, model: String): TokenPricingCatalog =
        withContext(Dispatchers.IO) {
            if (endpoint == null || model.isBlank()) {
                return@withContext TokenPricingCatalog(model)
            }
            val remote = try {
                if (endpoint.isOpenRouterRouting()) {
                    loadOpenRouter(endpoint, model)
                } else {
                    val author = FirstPartyPricing.openRouterAuthorFor(endpoint)
                    when {
                        author == null -> loadGeneric(endpoint, model)
                        author == FirstPartyPricing.XAI ->
                            loadXai(endpoint, model) ?: loadFirstParty(author, model)
                        else -> loadFirstParty(author, model)
                    }
                }
            } catch (_: Exception) {
                null
            }
            remote ?: TokenPricingCatalog(model)
        }

    /** OpenAI, Anthropic and xAI publish no prices through their model lists.
     * Their models are priced from OpenRouter's public catalog, fetched without
     * credentials so the user's first-party API key never leaves its own host. */
    private fun loadFirstParty(author: String, model: String): TokenPricingCatalog? {
        val now = System.currentTimeMillis()
        val cached = publicCatalogCache
        val body = if (cached != null && now - cached.first < PUBLIC_CATALOG_TTL_MS) {
            cached.second
        } else {
            val request = Request.Builder()
                .url(OPENROUTER_PUBLIC_MODELS_URL)
                .header("Accept", "application/json")
                .get()
                .build()
            val fetched = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }?.takeIf { FirstPartyPricing.isCatalog(it) } ?: return null
            publicCatalogCache = now to fetched
            fetched
        }
        val pricing = FirstPartyPricing.match(body, author, model) ?: return null
        return TokenPricingCatalog(model, modelPricing = pricing)
    }

    /** xAI publishes its own prices with the user's key, which is more accurate
     * than OpenRouter's copy. OpenRouter's public list remains the fallback. */
    private fun loadXai(endpoint: ApiEndpointObject, model: String): TokenPricingCatalog? {
        val base = endpoint.host.toHttpUrlOrNull() ?: return null
        val body = fetch(
            endpoint,
            base.newBuilder().addPathSegment("language-models").build().toString()
        ) ?: return null
        val pricing = FirstPartyPricing.matchXai(body, model) ?: return null
        return TokenPricingCatalog(model, modelPricing = pricing)
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
        val snapshot = TokenPricingSnapshot(
            number("prompt"),
            number("completion"),
            number("input_cache_read") ?: number("cached_prompt"),
            number("input_cache_write")
        )
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

/** Maps first-party API hosts to their OpenRouter catalog author and matches
 * the API's model name (often a dated snapshot) to that author's catalog entry.
 * An unmatched model has no price rather than a guessed one. */
internal object FirstPartyPricing {
    const val XAI = "x-ai"

    private val authorsByHost = mapOf(
        "api.openai.com" to "openai",
        "api.anthropic.com" to "anthropic",
        "api.x.ai" to XAI
    )
    private val snapshotDate = Regex("-(\\d{8}|\\d{4}-\\d{2}-\\d{2}|(0[1-9]|1[0-2])\\d{2})$")
    private val reasoningMode = Regex("-(non-)?reasoning$")

    fun openRouterAuthorFor(endpoint: ApiEndpointObject): String? {
        val host = endpoint.host.toHttpUrlOrNull()?.host?.lowercase() ?: return null
        return authorsByHost[host]
    }

    fun isCatalog(catalogJson: String): Boolean = catalogData(catalogJson) != null

    private fun catalogData(catalogJson: String): com.google.gson.JsonArray? = try {
        JsonParser.parseString(catalogJson).takeIf { it.isJsonObject }?.asJsonObject
            ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { null }

    fun match(catalogJson: String, author: String, model: String): TokenPricingSnapshot? {
        val data = catalogData(catalogJson) ?: return null
        val prefix = "$author/"
        val byName = LinkedHashMap<String, TokenPricingSnapshot>()
        val bySlug = LinkedHashMap<String, TokenPricingSnapshot>()
        data.forEach { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val pricing = item.get("pricing")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@forEach
            fun price(name: String): Double? = try {
                pricing.get(name)?.takeIf { it.isJsonPrimitive }?.asDouble?.takeIf { it >= 0.0 }
            } catch (_: Exception) { null }
            val snapshot = TokenPricingSnapshot(
                price("prompt"),
                price("completion"),
                price("input_cache_read"),
                price("input_cache_write")
            )
            if (snapshot.inputPricePerToken == null && snapshot.outputPricePerToken == null) {
                return@forEach
            }
            fun key(value: String?): String? = value?.trim()?.lowercase()
                ?.takeIf { it.startsWith(prefix) && ':' !in it }
                ?.removePrefix(prefix)?.let(::normalize)
            key(item.stringOrNull("id"))?.let { byName.putIfAbsent(it, snapshot) }
            key(item.stringOrNull("canonical_slug"))?.let { bySlug.putIfAbsent(it, snapshot) }
        }
        fun lookup(name: String): TokenPricingSnapshot? = byName[name] ?: bySlug[name]

        val requested = normalize(model.trim().lowercase().removePrefix(prefix))
        val withoutAlias = requested.removeSuffix("-latest")
        val withoutDate = withoutAlias.replace(snapshotDate, "")
        val candidates = mutableListOf(requested, withoutAlias, withoutDate)
        if (author == XAI) candidates += withoutDate.replace(reasoningMode, "")
        return candidates.distinct().firstNotNullOfOrNull { lookup(it) }
    }

    /** xAI's own list (`/language-models`). Prices are in USD cents per 100
     * million tokens, so dividing by 10^10 gives dollars per token. A model is
     * matched by its id or one of its aliases. */
    fun matchXai(catalogJson: String, model: String): TokenPricingSnapshot? {
        val root = try {
            JsonParser.parseString(catalogJson).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) { null } ?: return null
        val models = (root.get("models") ?: root.get("data"))
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val requested = normalize(model.trim().lowercase().removePrefix("$XAI/"))
        models.forEach { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val aliases = item.get("aliases")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { alias ->
                    alias.takeIf { it.isJsonPrimitive }?.asString
                }.orEmpty()
            val names = (listOfNotNull(item.stringOrNull("id")) + aliases)
                .map { normalize(it.trim().lowercase()) }
            if (requested !in names) return@forEach
            fun price(name: String): Double? = try {
                item.get(name)?.takeIf { it.isJsonPrimitive }?.asDouble
                    ?.takeIf { it >= 0.0 }?.div(1e10)
            } catch (_: Exception) { null }
            val snapshot = TokenPricingSnapshot(
                price("prompt_text_token_price"),
                price("completion_text_token_price"),
                price("cached_prompt_text_token_price")
            )
            return snapshot.takeIf {
                it.inputPricePerToken != null && it.outputPricePerToken != null
            }
        }
        return null
    }

    private fun normalize(name: String): String = name.replace('.', '-')

    private fun com.google.gson.JsonObject.stringOrNull(name: String): String? = try {
        get(name)?.takeIf { it.isJsonPrimitive }?.asString
    } catch (_: Exception) { null }
}
