/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The capability lookup every consumer uses (favorite rows, the Reasoning
 * Settings screen, Quick Settings, and request preparation). It layers the
 * §7.7 confidence ladder over the persisted per-endpoint store so a caller does
 * not need a live catalog to know a model reasons.
 *
 * Resolution order, strongest first:
 *  1. **Live catalog entry**, when the caller is rendering a freshly fetched
 *     list and has the model's metadata in hand (View All).
 *  2. **Persisted metadata** recorded on the endpoint by earlier catalog work
 *     ([ReasoningCapabilityStore]).
 *  3. **Live id-based tiers** — direct-provider knowledge and variant markers —
 *     via [ReasoningCapabilityResolver], ending at Unknown.
 *
 * Capability is keyed to the endpoint AND the model id (§7.9): two endpoints
 * serving the same id can hold different persisted metadata, so the caller
 * always passes the specific endpoint's stored JSON.
 */
object EndpointReasoningCapability {

    /**
     * @param reasoningCapabilityByModel the endpoint's persisted capability
     *   store JSON ([ApiEndpointObject.reasoningCapabilityByModel]).
     * @param modelId the effective model id.
     * @param liveModelEntry the model's freshly fetched catalog entry, when the
     *   caller has one (else null).
     */
    fun resolve(
        reasoningCapabilityByModel: String?,
        modelId: String?,
        liveModelEntry: JsonObject? = null,
        providerPath: ReasoningProviderPath = ReasoningProviderPath.GENERIC_OPENAI_COMPATIBLE
    ): ReasoningCapability {
        // 1. Freshest structured metadata wins.
        OpenRouterReasoningCapability.fromModelEntry(liveModelEntry)?.let { return it }

        // 2. Metadata previously recorded for this exact model on this endpoint.
        if (!modelId.isNullOrBlank()) {
            val stored = ReasoningCapabilityStore.get(reasoningCapabilityByModel, modelId)
            if (stored.support != ReasoningSupport.UNKNOWN) return stored
        }

        // 3. Id-based knowledge and markers, then Unknown.
        return ReasoningCapabilityResolver.resolve(modelId, null, providerPath)
    }

    /**
     * Compatibility wrapper retained for existing call sites while rejection
     * history is migrated out of the decision path. Effort choices are now
     * evidence-driven: authoritative metadata is exact, and an unknown ladder
     * remains unknown instead of being populated through speculative probing.
     */
    fun resolveWithLearnedRejections(
        reasoningCapabilityByModel: String?,
        rejectedLevelsByModel: String?,
        modelId: String?,
        liveModelEntry: JsonObject? = null,
        providerPath: ReasoningProviderPath = ReasoningProviderPath.GENERIC_OPENAI_COMPATIBLE
    ): ReasoningCapability {
        @Suppress("UNUSED_VARIABLE")
        val ignoredRejectedHistory = rejectedLevelsByModel
        return resolve(reasoningCapabilityByModel, modelId, liveModelEntry, providerPath)
    }

    /**
     * Learn capability for [modelId] from a freshly fetched catalog entry and
     * fold it into the endpoint's persisted store, returning the updated JSON.
     * Only structured metadata that establishes reasoning is recorded; anything
     * else leaves the store unchanged (uncertainty is never persisted, §7.7).
     * The returned JSON equals the input when nothing was learned, so callers
     * can cheaply detect a change before writing the endpoint back.
     */
    fun learnFromEntry(
        reasoningCapabilityByModel: String?,
        modelId: String?,
        modelEntry: JsonObject?
    ): String {
        val current = reasoningCapabilityByModel?.ifBlank { ReasoningCapabilityStore.EMPTY }
            ?: ReasoningCapabilityStore.EMPTY
        if (modelId.isNullOrBlank()) return current
        val learned = OpenRouterReasoningCapability.fromModelEntry(modelEntry) ?: return current
        return ReasoningCapabilityStore.set(current, modelId, learned)
    }

    /**
     * Fold every reasoning-capable model in a `/models` catalog response into the
     * endpoint's persisted store, returning the updated JSON (equal to the input
     * when nothing was learned). This is how capability discovery rides normal
     * catalog work (§7.7): a model whose metadata establishes reasoning gains its
     * record, so a favorite created while capability was Unknown can light up
     * later without the user re-adding it. Unknown models are never recorded;
     * authoritative absence is cached so weaker inference cannot override the
     * catalog.
     * Never throws: a malformed body leaves the store unchanged.
     */
    fun learnFromCatalogJson(reasoningCapabilityByModel: String?, catalogJson: String?): String {
        val current = reasoningCapabilityByModel?.ifBlank { ReasoningCapabilityStore.EMPTY }
            ?: ReasoningCapabilityStore.EMPTY
        return refreshFromOpenRouterCatalog(reasoningCapabilityByModel, catalogJson)
            ?.capabilityJson ?: current
    }

    /** Parse one successful OpenRouter `/models` response once and derive both
     *  the visible catalog entries and the refreshed endpoint capability cache.
     *  A malformed/inconclusive response returns null and leaves the cache for
     *  the caller to preserve. */
    fun refreshFromOpenRouterCatalog(
        reasoningCapabilityByModel: String?,
        catalogJson: String?
    ): OpenRouterCatalogRefresh? {
        var current = reasoningCapabilityByModel?.ifBlank { ReasoningCapabilityStore.EMPTY }
            ?: ReasoningCapabilityStore.EMPTY
        if (catalogJson.isNullOrBlank()) return null
        val data = try {
            JsonParser.parseString(catalogJson)
                .takeIf { it.isJsonObject }?.asJsonObject
                ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
        } catch (_: Exception) {
            null
        } ?: return null

        val models = ArrayList<OpenRouterCatalogModel>()
        for (element in data) {
            val obj = element?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val id = obj.get("id")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() } ?: continue
            models.add(OpenRouterCatalogModel(id, obj))
            OpenRouterReasoningCapability.fromModelEntry(obj)?.let { capability ->
                current = ReasoningCapabilityStore.set(current, id, capability)
            }
        }
        if (models.isEmpty()) return null
        // A successfully parsed non-empty catalog is conclusive for this
        // endpoint. Reuse the store's conservative cleanup primitive: retain
        // live models, but never prune on an unavailable/malformed response.
        current = ReasoningCapabilityStore.retainOnly(current, models.mapTo(LinkedHashSet()) { it.id })
        return OpenRouterCatalogRefresh(models, current)
    }

    /** A real response containing separate reasoning text proves reasoning and
     *  visible-reasoning support for this exact endpoint/model path. It never
     *  invents effort levels and never overwrites richer authoritative metadata.
     */
    fun learnFromObservedResponse(
        reasoningCapabilityByModel: String?,
        modelId: String?
    ): String {
        val currentJson = reasoningCapabilityByModel?.ifBlank { ReasoningCapabilityStore.EMPTY }
            ?: ReasoningCapabilityStore.EMPTY
        if (modelId.isNullOrBlank()) return currentJson
        val current = ReasoningCapabilityStore.get(currentJson, modelId)
        if (current.source == CapabilitySource.PROVIDER_METADATA) {
            return currentJson
        }
        val observed = if (current.support == ReasoningSupport.KNOWN) {
            current.copy(
                canReturnVisibleReasoning = true,
                source = if (current.source == CapabilitySource.PROVIDER_ADAPTER) {
                    CapabilitySource.PROVIDER_ADAPTER
                } else {
                    CapabilitySource.OBSERVED_RESPONSE
                }
            )
        } else {
            ReasoningCapability(
                support = ReasoningSupport.KNOWN,
                effortConfigurable = false,
                supportedEfforts = emptyList(),
                canDisableReasoning = false,
                canReturnVisibleReasoning = true,
                tokenBudgetSupported = false,
                source = CapabilitySource.OBSERVED_RESPONSE,
                effortsAuthoritative = false
            )
        }
        return ReasoningCapabilityStore.set(currentJson, modelId, observed)
    }
}

data class OpenRouterCatalogModel(val id: String, val entry: JsonObject)

data class OpenRouterCatalogRefresh(
    val models: List<OpenRouterCatalogModel>,
    val capabilityJson: String
)
