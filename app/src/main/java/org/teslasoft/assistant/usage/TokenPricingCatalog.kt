/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.usage

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
            fun usable(price: Double?): Double? = price?.takeIf { it.isFinite() && it >= 0.0 }
            return TokenPricingSnapshot(
                usable(providerPrice.promptPrice),
                usable(providerPrice.completionPrice),
                usable(providerPrice.cacheReadPrice),
                usable(providerPrice.cacheWritePrice)
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
                    when (PricingSource.forEndpoint(endpoint)) {
                        PricingSource.OPENAI -> loadFirstParty("openai", model)
                        PricingSource.ANTHROPIC -> loadFirstParty("anthropic", model)
                        PricingSource.XAI -> XaiRegionalPricing.applyToCatalog(
                            endpoint.host,
                            loadXai(endpoint, model) ?: loadFirstParty("x-ai", model)
                        )
                        PricingSource.NANOGPT -> loadNanoGpt(endpoint, model)
                        PricingSource.VENICE -> loadVenice(endpoint, model)
                        PricingSource.FEATHERLESS -> loadFeatherless(endpoint, model)
                        null -> loadGeneric(endpoint, model)
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
        val url = XaiRegionalPricing.catalogUrl(endpoint.host) ?: return null
        val body = fetch(endpoint, url) ?: return null
        val pricing = FirstPartyPricing.matchXai(body, model) ?: return null
        return TokenPricingCatalog(model, modelPricing = pricing)
    }

    /** NanoGPT lists prices only in its detailed model list, fetched with the
     * user's key so account-specific pricing applies. */
    private fun loadNanoGpt(endpoint: ApiEndpointObject, model: String): TokenPricingCatalog? {
        val base = endpoint.host.toHttpUrlOrNull() ?: return null
        val url = base.newBuilder().addPathSegment("models")
            .addQueryParameter("detailed", "true").build().toString()
        val pricing = fetch(endpoint, url)?.let { NanoGptPricing.match(it, model) } ?: return null
        return TokenPricingCatalog(model, modelPricing = pricing)
    }

    /** Venice's text model list, falling back to Venice's own compatibility
     * mapping when the model name is one of Venice's documented aliases. */
    private fun loadVenice(endpoint: ApiEndpointObject, model: String): TokenPricingCatalog? {
        val base = endpoint.host.toHttpUrlOrNull() ?: return null
        val models = fetch(
            endpoint,
            base.newBuilder().addPathSegment("models")
                .addQueryParameter("type", "text").build().toString()
        ) ?: return null
        val pricing = VenicePricing.match(models, model)
            ?: fetch(
                endpoint,
                base.newBuilder().addPathSegment("models")
                    .addPathSegment("compatibility_mapping")
                    .addQueryParameter("type", "text").build().toString()
            )?.let { VenicePricing.mappedModel(it, model) }
                ?.let { VenicePricing.match(models, it) }
            ?: return null
        return TokenPricingCatalog(model, modelPricing = pricing)
    }

    private fun loadFeatherless(endpoint: ApiEndpointObject, model: String): TokenPricingCatalog? {
        val pricing = FeatherlessPricing.load(endpoint.host, model) { url -> fetch(endpoint, url) }
            ?: return null
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

    /** Any other service: its own model list, if it uses the OpenRouter-style
     * `pricing` layout. */
    private fun loadGeneric(endpoint: ApiEndpointObject, model: String): TokenPricingCatalog? {
        val base = endpoint.host.toHttpUrlOrNull() ?: return null
        val body = fetch(endpoint, base.newBuilder().addPathSegment("models").build().toString())
            ?: return null
        val pricing = GenericPricing.match(body, model) ?: return null
        return TokenPricingCatalog(model, modelPricing = pricing)
    }

    private fun fetch(endpoint: ApiEndpointObject, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                when (endpoint.authType) {
                    ApiEndpointObject.AUTH_X_API_KEY -> header("x-api-key", endpoint.apiKey)
                    ApiEndpointObject.AUTH_API_KEY -> header("api-key", endpoint.apiKey)
                    ApiEndpointObject.AUTH_XI_API_KEY -> header("xi-api-key", endpoint.apiKey)
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

/** Services whose pricing is read in a provider-specific, documented way,
 * recognized by their official API host. */
internal enum class PricingSource(val hosts: Set<String>) {
    OPENAI(setOf("api.openai.com")),
    ANTHROPIC(setOf("api.anthropic.com")),
    XAI(setOf("api.x.ai", "us.api.x.ai")),
    NANOGPT(setOf("nano-gpt.com", "api.nano-gpt.com")),
    VENICE(setOf("api.venice.ai")),
    FEATHERLESS(setOf("api.featherless.ai"));

    companion object {
        fun forEndpoint(endpoint: ApiEndpointObject): PricingSource? = forUrl(endpoint.host)

        fun forUrl(url: String?): PricingSource? {
            val host = url?.toHttpUrlOrNull()?.host?.lowercase() ?: return null
            return entries.firstOrNull { host in it.hosts }
        }
    }
}

/** xAI documents US inference at 1.1x global token rates, including cached
 * and long-context rates. Read global metadata so the premium is applied once,
 * rather than assuming whether regional metadata already includes it. Existing
 * keys work on both official endpoints; only model metadata is fetched here.
 * https://docs.x.ai/developers/pricing#us-regional-endpoint-pricing
 * https://docs.x.ai/developers/advanced-api-usage/regions
 * The source order remains xAI first, public OpenRouter fallback, then unknown.
 * Provider-reported charges are never scaled. */
internal object XaiRegionalPricing {
    private const val US_HOST = "us.api.x.ai"

    fun catalogUrl(baseUrl: String): String? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        return base.newBuilder().apply {
            if (base.host == US_HOST) host("api.x.ai")
        }.addPathSegment("language-models").build().toString()
    }

    fun applyToCatalog(baseUrl: String, catalog: TokenPricingCatalog?): TokenPricingCatalog? {
        if (baseUrl.toHttpUrlOrNull()?.host != US_HOST) return catalog
        return catalog?.copy(modelPricing = catalog.modelPricing?.scaled(1.1))
    }
}

/** A price is usable only when it is a finite, non-negative number. Zero is a
 * valid reported price. */
private fun JsonElement?.priceOrNull(): Double? = try {
    this?.takeIf { it.isJsonPrimitive }?.asDouble?.takeIf { it.isFinite() && it >= 0.0 }
} catch (_: Exception) { null }

private fun JsonObject.textOrNull(name: String): String? = try {
    get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null }
} catch (_: Exception) { null }

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun parseObject(json: String): JsonObject? = try {
    JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
} catch (_: Exception) { null }

/** Matches a first-party model id to its entry in OpenRouter's public catalog.
 * Only an exact id (`author/model`) or OpenRouter's own `canonical_slug` for
 * that entry counts as a match. An unmatched model has no price. */
internal object FirstPartyPricing {
    fun isCatalog(catalogJson: String): Boolean = catalogData(catalogJson) != null

    private fun catalogData(catalogJson: String): JsonArray? =
        parseObject(catalogJson)?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray

    fun match(catalogJson: String, author: String, model: String): TokenPricingSnapshot? {
        val data = catalogData(catalogJson) ?: return null
        val wanted = "$author/${model.trim()}"
        var bySlug: TokenPricingSnapshot? = null
        data.forEach { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val pricing = item.objectOrNull("pricing") ?: return@forEach
            val snapshot = TokenPricingSnapshot(
                pricing.get("prompt").priceOrNull(),
                pricing.get("completion").priceOrNull(),
                pricing.get("input_cache_read").priceOrNull(),
                pricing.get("input_cache_write").priceOrNull()
            )
            if (snapshot.inputPricePerToken == null && snapshot.outputPricePerToken == null) {
                return@forEach
            }
            if (item.textOrNull("id") == wanted) return snapshot
            if (bySlug == null && item.textOrNull("canonical_slug") == wanted) bySlug = snapshot
        }
        return bySlug
    }

    /** xAI's own list (`/language-models`). Prices are in USD cents per 100
     * million tokens, so dividing by 10^10 gives dollars per token. A model is
     * matched by its exact id or one of xAI's listed aliases.
     *
     * Long context: once a request's prompt reaches `long_context_threshold`,
     * xAI bills every token at the `*_long_context` rates. Per xAI, a
     * threshold of 0 means no long-context tier, and a long-context price of 0
     * means the standard price applies. An absent field is read as 0, the
     * default value of xAI's numeric fields. */
    fun matchXai(catalogJson: String, model: String): TokenPricingSnapshot? {
        val root = parseObject(catalogJson) ?: return null
        val models = (root.get("models") ?: root.get("data"))
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val wanted = model.trim()
        models.forEach { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val aliases = item.get("aliases")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { alias -> alias.takeIf { it.isJsonPrimitive }?.asString }
                .orEmpty()
            if (wanted != item.textOrNull("id") && wanted !in aliases) return@forEach
            fun price(name: String): Double? = item.get(name).priceOrNull()?.div(1e10)
            val base = TokenPricingSnapshot(
                price("prompt_text_token_price"),
                price("completion_text_token_price"),
                price("cached_prompt_text_token_price")
            )
            if (base.inputPricePerToken == null || base.outputPricePerToken == null) return null
            val thresholdField = item.get("long_context_threshold")
            val threshold = if (thresholdField == null) 0L
                else thresholdField.priceOrNull()?.toLong() ?: return null
            if (threshold == 0L) return base
            fun longRate(name: String, standard: Double?): Double? {
                val field = item.get(name) ?: return standard
                val value = field.priceOrNull() ?: return null
                return if (value == 0.0) standard else value / 1e10
            }
            return base.copy(
                extended = ExtendedPricingTier(
                    threshold,
                    TokenPricingSnapshot(
                        longRate("prompt_text_token_price_long_context", base.inputPricePerToken),
                        longRate("completion_text_token_price_long_context", base.outputPricePerToken),
                        longRate(
                            "cached_prompt_text_token_price_long_context",
                            base.cachedInputPricePerToken
                        )
                    ),
                    appliesAtThreshold = true
                )
            )
        }
        return null
    }
}

/** NanoGPT's detailed model list (`/models?detailed=true`). Per NanoGPT's
 * documentation, `prompt` and `completion` are USD per million tokens
 * (`unit: per_million_tokens`, `currency: USD`), and the cache fields are USD
 * per thousand tokens, as their names state. Matched by exact model id. */
internal object NanoGptPricing {
    fun match(catalogJson: String, model: String): TokenPricingSnapshot? {
        val data = parseObject(catalogJson)?.get("data")
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val wanted = model.trim()
        val item = data.firstOrNull { element ->
            element.isJsonObject && element.asJsonObject.textOrNull("id") == wanted
        }?.asJsonObject ?: return null
        val pricing = item.objectOrNull("pricing") ?: return null
        if (pricing.textOrNull("currency") != "USD") return null
        if (pricing.textOrNull("unit") != "per_million_tokens") return null
        val input = pricing.get("prompt").priceOrNull() ?: return null
        val output = pricing.get("completion").priceOrNull() ?: return null
        return TokenPricingSnapshot(
            input / 1e6,
            output / 1e6,
            pricing.get("cacheReadInputPer1kTokens").priceOrNull()?.div(1e3),
            pricing.get("cacheWriteInputPer1kTokens").priceOrNull()?.div(1e3)
        )
    }
}

/** Venice's model list (`/models?type=text`). Per Venice's API specification,
 * `model_spec.pricing` gives `input`, `output`, `cache_input` (cache reads) and
 * `cache_write` (cache writes) in USD per million tokens, plus an optional
 * `extended` tier whose rates apply to the whole request once its input tokens
 * exceed `context_token_threshold`. `cache_input` is present only for models
 * that support context caching. Only the USD amounts are used. */
internal object VenicePricing {
    fun match(catalogJson: String, model: String): TokenPricingSnapshot? {
        val data = parseObject(catalogJson)?.get("data")
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val wanted = model.trim()
        val item = data.firstOrNull { element ->
            element.isJsonObject && element.asJsonObject.textOrNull("id") == wanted
        }?.asJsonObject ?: return null
        val pricing = item.objectOrNull("model_spec")?.objectOrNull("pricing") ?: return null
        // Venice documents that cache_input is present only for models that
        // support context caching, so its absence means no caching.
        val base = rates(pricing)?.copy(cachingOffered = pricing.has("cache_input"))
            ?: return null
        val extended = pricing.objectOrNull("extended")?.let { tier ->
            val threshold = tier.get("context_token_threshold").priceOrNull()?.toLong()
                ?: return null
            ExtendedPricingTier(threshold, rates(tier) ?: return null)
        }
        return base.copy(extended = extended)
    }

    /** Venice's alias table (`/models/compatibility_mapping`): a documented
     * alias name mapped to the Venice model it runs. */
    fun mappedModel(mappingJson: String, model: String): String? =
        parseObject(mappingJson)?.objectOrNull("data")?.textOrNull(model.trim())

    private fun rates(pricing: JsonObject): TokenPricingSnapshot? {
        fun usd(name: String): Double? =
            pricing.objectOrNull(name)?.get("usd").priceOrNull()?.div(1e6)
        return TokenPricingSnapshot(
            usd("input") ?: return null,
            usd("output") ?: return null,
            usd("cache_input"),
            usd("cache_write")
        )
    }
}

/** Featherless model detail: pricing.prompt/completion are decimal USD per
 * token, not the per-million rates displayed on model web pages.
 * https://featherless.ai/docs/api-reference-models
 *
 * The ordinary completion response has no documented billing receipt or mode.
 * /v1/plan is accessible with a normal key; only the exact plan id documented
 * alongside billing_mode=request_pricing in /usage/activity is recognized.
 * Other plans (including the documented feather_pro_plus subscription), unknown
 * plans, and failed plan reads cannot acquire a calculated request charge.
 * https://featherless.ai/docs/api-reference-plan
 * https://featherless.ai/docs/api-reference-usage-activity
 *
 * No admin billing calls or matching by time/model/token counts. The activity
 * request_id is not documented as equal to a completion id. Cached rates and
 * absence-of-caching semantics are not documented for the model-detail layout;
 * neither is inferred from missing fields. */
internal object FeatherlessPricing {
    fun load(baseUrl: String, model: String, fetchBody: (String) -> String?): TokenPricingSnapshot? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val detailUrl = base.newBuilder().addPathSegment("models")
            .addPathSegment(model).build().toString()
        val pricing = fetchBody(detailUrl)?.let { match(it, model) } ?: return null
        // A permission/network/parse failure must retain model prices and token
        // accounting while leaving the billing applicability unknown.
        val plan = try {
            fetchBody(base.newBuilder().addPathSegment("plan").build().toString())
        } catch (_: Exception) { null }
        return pricing.copy(featherlessRequestPricingConfirmed =
            plan?.let { parseObject(it)?.textOrNull("id") } == "feather_request_pricing")
    }

    fun match(detailJson: String, model: String): TokenPricingSnapshot? {
        val item = parseObject(detailJson) ?: return null
        if (item.textOrNull("id") != model) return null
        val pricing = item.objectOrNull("pricing") ?: return null
        // Ignore image/request prices: they are not token rates. No invented
        // alias for a cached-input rate or assumption of zero cache usage.
        return TokenPricingSnapshot(
            inputPricePerToken = pricing.get("prompt").priceOrNull(),
            outputPricePerToken = pricing.get("completion").priceOrNull(),
            featherlessRequestPricingConfirmed = false
        ).takeIf { it.inputPricePerToken != null || it.outputPricePerToken != null }
    }
}

/** Any other service whose model list uses OpenRouter's `pricing` layout.
 * A stated `unit` is used and an unrecognized one makes the price unknown.
 * With no stated unit, the layout's convention applies: dollars per token,
 * as OpenRouter publishes it. Prices in a currency other than USD are not
 * used. Matched by exact model id. */
internal object GenericPricing {
    private val unitFactors = mapOf(
        "per_token" to 1.0,
        "per_thousand_tokens" to 1e-3,
        "per_million_tokens" to 1e-6
    )

    fun match(catalogJson: String, model: String): TokenPricingSnapshot? {
        val data = parseObject(catalogJson)?.get("data")
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val wanted = model.trim()
        val item = data.firstOrNull { element ->
            element.isJsonObject && element.asJsonObject.textOrNull("id") == wanted
        }?.asJsonObject ?: return null
        val pricing = item.objectOrNull("pricing") ?: return null
        pricing.textOrNull("currency")?.let { if (it != "USD") return null }
        val factor = pricing.textOrNull("unit")?.let { unitFactors[it] ?: return null } ?: 1.0
        return TokenPricingSnapshot(
            pricing.get("prompt").priceOrNull() ?: return null,
            pricing.get("completion").priceOrNull() ?: return null,
            (pricing.get("input_cache_read") ?: pricing.get("cached_prompt")).priceOrNull(),
            pricing.get("input_cache_write").priceOrNull()
        ).scaled(factor)
    }
}
