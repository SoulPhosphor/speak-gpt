/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *************************************************************************/

package org.teslasoft.assistant.usage

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.teslasoft.assistant.preferences.MessageCompletionState
import java.util.Locale

enum class TokenCountSource(val storedValue: String) {
    PROVIDER_REPORTED("provider_reported"),
    ESTIMATED_CL100K("estimated_cl100k");

    companion object {
        fun fromStored(value: String?): TokenCountSource =
            entries.firstOrNull { it.storedValue == value } ?: ESTIMATED_CL100K
    }
}

enum class CostSource(val storedValue: String) {
    PROVIDER_REPORTED("provider_reported"),
    FROZEN_PRICING("frozen_pricing"),
    UNKNOWN("unknown");

    companion object {
        fun fromStored(value: String?): CostSource =
            entries.firstOrNull { it.storedValue == value } ?: UNKNOWN
    }
}

data class TokenCounts(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    /** Provider-reported cache-read tokens contained within [inputTokens]. */
    val cachedInputTokens: Int? = null,
    /** Provider-reported cache-write tokens contained within [inputTokens]. */
    val cacheWriteInputTokens: Int? = null
) {
    /** Fill only values that can be derived exactly from the reported values. */
    fun withDerivedTotal(): TokenCounts {
        var input = inputTokens
        var output = outputTokens
        var total = totalTokens
        if (total == null && input != null && output != null) total = input + output
        if (input == null && total != null && output != null && total >= output) {
            input = total - output
        }
        if (output == null && total != null && input != null && total >= input) {
            output = total - input
        }
        return TokenCounts(input, output, total, cachedInputTokens, cacheWriteInputTokens)
    }

    fun hasAnyValue(): Boolean = inputTokens != null || outputTokens != null ||
        totalTokens != null || cachedInputTokens != null || cacheWriteInputTokens != null
}

data class TokenPricingSnapshot(
    val inputPricePerToken: Double? = null,
    val outputPricePerToken: Double? = null,
    val cachedInputPricePerToken: Double? = null,
    val cacheWriteInputPricePerToken: Double? = null,
    /** A provider's long-context rates, when it publishes them (Venice). */
    val extended: ExtendedPricingTier? = null,
    /** False only when the provider documents that this model offers no
     * prompt caching, so its cached and cache-write usage is zero. Null when
     * that is not known. */
    val cachingOffered: Boolean? = null,
    /** Featherless alone: true only for the documented request-pricing plan
     * returned by its ordinary /v1/plan API. Unknown/flat-rate plans do not
     * acquire calculated per-request charges. Frozen prices remain available. */
    val featherlessRequestPricingConfirmed: Boolean? = null
) {
    fun scaled(factor: Double): TokenPricingSnapshot = copy(
        inputPricePerToken = inputPricePerToken?.times(factor),
        outputPricePerToken = outputPricePerToken?.times(factor),
        cachedInputPricePerToken = cachedInputPricePerToken?.times(factor),
        cacheWriteInputPricePerToken = cacheWriteInputPricePerToken?.times(factor),
        extended = extended?.let { it.copy(pricing = it.pricing.scaled(factor)) }
    )

    /** The rates that apply to a request with [inputTokens] total input. When
     * the input exceeds the provider's threshold, its extended rates apply to
     * the whole request. If a tier exists but the input count is unknown, the
     * applicable rates are unknown. */
    fun forInputTokens(inputTokens: Int?): TokenPricingSnapshot {
        val tier = extended ?: return this
        return when {
            inputTokens == null -> TokenPricingSnapshot()
            tier.applies(inputTokens) ->
                tier.pricing.copy(extended = null, cachingOffered = cachingOffered)
            else -> copy(extended = null)
        }
    }
}

/** Rates a provider applies to an entire request once its input tokens pass
 * [inputTokenThreshold]: above it (Venice), or at or above it when
 * [appliesAtThreshold] is true (xAI). */
data class ExtendedPricingTier(
    val inputTokenThreshold: Long,
    val pricing: TokenPricingSnapshot,
    val appliesAtThreshold: Boolean = false
) {
    fun applies(inputTokens: Int): Boolean =
        if (appliesAtThreshold) inputTokens >= inputTokenThreshold
        else inputTokens > inputTokenThreshold
}

/** Exact monetary values returned by the serving API. A reported total does
 * not imply that the provider supplied an input/output split. */
data class ProviderReportedCost(
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val totalCost: Double? = null,
    val cachedInputCost: Double? = null
) {
    fun hasAnyValue(): Boolean = inputCost != null || outputCost != null ||
        totalCost != null || cachedInputCost != null

    fun withDerivedTotal(): ProviderReportedCost = if (
        totalCost == null && inputCost != null && outputCost != null
    ) {
        copy(totalCost = inputCost + outputCost)
    } else {
        this
    }
}

/** One completed API request. A visible assistant turn may contain more than one
 * record when a tool call required a continuation request. */
data class TurnUsageRecord(
    val model: String,
    val provider: String,
    val apiEndpoint: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val cacheWriteInputTokens: Int? = null,
    val source: String = TokenCountSource.ESTIMATED_CL100K.storedValue,
    val inputPricePerToken: Double? = null,
    val outputPricePerToken: Double? = null,
    val cachedInputPricePerToken: Double? = null,
    val cacheWriteInputPricePerToken: Double? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val uncachedInputCost: Double? = null,
    val cachedInputCost: Double? = null,
    val totalCost: Double? = null,
    val costSource: String? = null
) {
    val countSource: TokenCountSource get() = TokenCountSource.fromStored(source)
    val storedCostSource: CostSource get() = when {
        costSource != null -> CostSource.fromStored(costSource)
        inputCost != null || outputCost != null || totalCost != null -> CostSource.FROZEN_PRICING
        else -> CostSource.UNKNOWN
    }

    fun groupKey(): UsageGroupKey = UsageGroupKey(
        model.trim().lowercase(Locale.ROOT),
        provider.trim().lowercase(Locale.ROOT)
    )
}

data class UsageGroupKey(val model: String, val provider: String)

data class UsageGroup(
    val model: String,
    val provider: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val uncachedInputTokens: Int,
    val cachedInputTokens: Int,
    val inputCost: Double,
    val outputCost: Double,
    val uncachedInputCost: Double,
    val cachedInputCost: Double,
    val totalCost: Double,
    val inputPricePerToken: Double?,
    val outputPricePerToken: Double?,
    val cachedInputPricePerToken: Double?,
    val hasUnknownInputTokens: Boolean,
    val hasUnknownOutputTokens: Boolean,
    val hasUnknownUncachedInputTokens: Boolean,
    val hasUnknownCachedInputTokens: Boolean,
    val hasUnknownInputCost: Boolean,
    val hasUnknownOutputCost: Boolean,
    val hasUnknownUncachedInputCost: Boolean,
    val hasUnknownCachedInputCost: Boolean,
    val hasUnknownCost: Boolean,
    val hasVariableInputPricing: Boolean,
    val hasVariableOutputPricing: Boolean,
    val hasVariableCachedInputPricing: Boolean,
    val hasVariablePricing: Boolean,
    val containsEstimatedTokens: Boolean,
    val recordCount: Int
)

data class ConversationUsageSummary(
    val groups: List<UsageGroup>
) {
    val totalInputTokens: Int get() = groups.sumOf { it.inputTokens }
    val totalOutputTokens: Int get() = groups.sumOf { it.outputTokens }
    val totalUncachedInputTokens: Int get() = groups.sumOf { it.uncachedInputTokens }
    val totalCachedInputTokens: Int get() = groups.sumOf { it.cachedInputTokens }
    val totalCost: Double get() = groups.sumOf { it.totalCost }
    val isMultiPricing: Boolean get() = groups.size > 1
    val hasUnknownInputTokens: Boolean get() = groups.any { it.hasUnknownInputTokens }
    val hasUnknownOutputTokens: Boolean get() = groups.any { it.hasUnknownOutputTokens }
    val hasUnknownUncachedInputTokens: Boolean get() = groups.any { it.hasUnknownUncachedInputTokens }
    val hasUnknownCachedInputTokens: Boolean get() = groups.any { it.hasUnknownCachedInputTokens }
    val hasUnknownInputCost: Boolean get() = groups.any { it.hasUnknownInputCost }
    val hasUnknownOutputCost: Boolean get() = groups.any { it.hasUnknownOutputCost }
    val hasUnknownUncachedInputCost: Boolean get() = groups.any { it.hasUnknownUncachedInputCost }
    val hasUnknownCachedInputCost: Boolean get() = groups.any { it.hasUnknownCachedInputCost }
    val hasUnknownCost: Boolean get() = groups.any { it.hasUnknownCost }
}

object TokenUsageAccounting {
    const val KEY_USAGE_RECORDS = "tokenUsageRecords"
    private const val KEY_VARIANTS = "variants"

    const val PROVIDER_NOT_REPORTED = "Not Reported"
    const val MODEL_NOT_REPORTED = "Not Reported"

    private val gson = Gson()
    private val recordListType = object : TypeToken<ArrayList<TurnUsageRecord>>() {}.type
    private val variantListType =
        object : TypeToken<ArrayList<HashMap<String, String>>>() {}.type

    fun encodeRecords(records: List<TurnUsageRecord>): String = gson.toJson(records)

    fun decodeRecords(value: String?): List<TurnUsageRecord> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson<ArrayList<TurnUsageRecord>>(value, recordListType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Regenerated replies retain every completed response as a variant while
     * the top-level message mirrors only the canonical one. The variant list is
     * therefore the accounting authority whenever it contains durable records;
     * reading the top-level record as well would double-count the canonical
     * variant, while reading only the top level would discard every alternate. */
    private fun decodeVariantRecords(value: String?): List<TurnUsageRecord> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val variants = gson.fromJson<ArrayList<HashMap<String, String>>>(
                value, variantListType
            ) ?: return emptyList()
            val storedByVariant = variants.map { variant ->
                variant to decodeRecords(variant[KEY_USAGE_RECORDS])
            }
            // A wholly legacy variant list stays on the existing compatibility
            // path. Once any variant has durable accounting, completed legacy
            // siblings must be represented as unknown so the known subset is
            // never presented as the complete historical total.
            if (storedByVariant.none { (_, records) -> records.isNotEmpty() }) {
                return emptyList()
            }
            storedByVariant.flatMap { (variant, records) ->
                if (records.isNotEmpty()) {
                    records
                } else if (MessageCompletionState.isComplete(
                        variant[MessageCompletionState.KEY_STATE]
                    )
                ) {
                    listOf(
                        TurnUsageRecord(
                            model = variant["responseModel"]?.trim()?.ifBlank { null }
                                ?: MODEL_NOT_REPORTED,
                            provider = variant["responseProvider"]?.trim()?.ifBlank { null }
                                ?: PROVIDER_NOT_REPORTED,
                            source = TokenCountSource.ESTIMATED_CL100K.storedValue
                        )
                    )
                } else {
                    emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeSummary(summary: ConversationUsageSummary): String = gson.toJson(summary)

    /**
     * Decode the transport copy handed to the Usage & Cost screen.
     * Do not ask Gson to discover List<UsageGroup> from the backing field's
     * generic signature: R8 can erase that signature in a minified APK, which
     * makes Gson populate the list with LinkedTreeMap and crashes the first
     * computed summary getter. Decode each array element against the concrete
     * UsageGroup class instead so the runtime type is stable in every build.
     */
    fun decodeSummary(value: String?): ConversationUsageSummary {
        if (value.isNullOrBlank()) return ConversationUsageSummary(emptyList())
        return try {
            val root = JsonParser.parseString(value)
            val groupsElement = root.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("groups")
            if (groupsElement == null || !groupsElement.isJsonArray) {
                ConversationUsageSummary(emptyList())
            } else {
                val groups = groupsElement.asJsonArray.mapNotNull { element ->
                    gson.fromJson<UsageGroup>(element, UsageGroup::class.java)
                }
                ConversationUsageSummary(groups)
            }
        } catch (_: Exception) {
            ConversationUsageSummary(emptyList())
        }
    }

    /** Provider counts win as a unit. CL100K is invoked only when the provider
     * supplied no usage value at all. */
    fun chooseCounts(
        providerCounts: TokenCounts?,
        estimateCl100k: () -> TokenCounts
    ): Pair<TokenCounts, TokenCountSource> {
        if (providerCounts?.hasAnyValue() == true) {
            return providerCounts.withDerivedTotal() to TokenCountSource.PROVIDER_REPORTED
        }
        return estimateCl100k().withDerivedTotal() to TokenCountSource.ESTIMATED_CL100K
    }

    fun createRecord(
        model: String,
        provider: String,
        apiEndpoint: String?,
        counts: TokenCounts,
        source: TokenCountSource,
        pricing: TokenPricingSnapshot,
        providerCost: ProviderReportedCost? = null
    ): TurnUsageRecord = createRecordFromReported(
        model, provider, apiEndpoint, counts, source, pricing, providerCost
    )

    private fun createRecordFromReported(
        model: String,
        provider: String,
        apiEndpoint: String?,
        reportedCounts: TokenCounts,
        source: TokenCountSource,
        pricing: TokenPricingSnapshot,
        providerCost: ProviderReportedCost?
    ): TurnUsageRecord {
        val costsApplicable = PricingSource.forUrl(apiEndpoint) != PricingSource.FEATHERLESS ||
            pricing.featherlessRequestPricingConfirmed == true
        // Featherless does not document receipt fields in ordinary completions.
        // Do not mistake generic-looking fields for a billed charge, especially
        // when the plan is flat-rate or its billing applicability is unknown.
        val exactCost = providerCost?.takeIf { costsApplicable && it.hasAnyValue() }
            ?.withDerivedTotal()
        val applied = pricing.forInputTokens(reportedCounts.inputTokens)
        // A model the provider documents as having no caching has zero cached
        // usage. Otherwise an unreported cache split stays unknown; it is never
        // assumed to be zero in order to price the input.
        val counts = if (applied.cachingOffered == false && reportedCounts.cachedInputTokens == null) {
            reportedCounts.copy(
                cachedInputTokens = 0,
                cacheWriteInputTokens = reportedCounts.cacheWriteInputTokens ?: 0
            )
        } else reportedCounts
        val uncachedInputTokens = if (
            counts.inputTokens != null && counts.cachedInputTokens != null &&
            counts.inputTokens >= counts.cachedInputTokens
        ) counts.inputTokens - counts.cachedInputTokens else null
        val calculatedUncachedInputCost = uncachedInputTokens?.takeIf { costsApplicable }?.let { count ->
            val writeTokens = counts.cacheWriteInputTokens
            when {
                writeTokens == null || writeTokens > count -> null
                writeTokens == 0 -> applied.inputPricePerToken?.let { count * it }
                else -> {
                    val regularCost = applied.inputPricePerToken
                        ?.let { (count - writeTokens) * it }
                    val writeCost = applied.cacheWriteInputPricePerToken
                        ?.let { writeTokens * it }
                    if (regularCost != null && writeCost != null) regularCost + writeCost else null
                }
            }
        }
        val calculatedCachedInputCost = counts.cachedInputTokens?.takeIf { costsApplicable }?.let { count ->
            if (count == 0) 0.0 else applied.cachedInputPricePerToken?.let { count * it }
        }
        val calculatedSplitInputCost = if (
            calculatedUncachedInputCost != null && calculatedCachedInputCost != null
        ) calculatedUncachedInputCost + calculatedCachedInputCost else null
        val calculatedOutputCost = counts.outputTokens?.takeIf { costsApplicable }?.let { count ->
            applied.outputPricePerToken?.let { count * it }
        }
        val inputCost: Double?
        val outputCost: Double?
        val uncachedInputCost: Double?
        val cachedInputCost: Double?
        val totalCost: Double?
        val costSource: CostSource
        if (exactCost != null) {
            // The provider total remains authoritative. Category costs use
            // explicit provider components when present, otherwise the frozen
            // per-request prices and token breakdown captured with this record.
            cachedInputCost = exactCost.cachedInputCost ?: calculatedCachedInputCost
            uncachedInputCost = calculatedUncachedInputCost
            inputCost = exactCost.inputCost ?: calculatedSplitInputCost
            outputCost = exactCost.outputCost ?: calculatedOutputCost
            totalCost = exactCost.totalCost
            costSource = CostSource.PROVIDER_REPORTED
        } else {
            uncachedInputCost = calculatedUncachedInputCost
            cachedInputCost = calculatedCachedInputCost
            inputCost = calculatedSplitInputCost
            outputCost = calculatedOutputCost
            totalCost = if (inputCost != null && outputCost != null) inputCost + outputCost else null
            costSource = if (inputCost != null || outputCost != null || totalCost != null) {
                CostSource.FROZEN_PRICING
            } else {
                CostSource.UNKNOWN
            }
        }
        return TurnUsageRecord(
            model = model.trim().ifBlank { MODEL_NOT_REPORTED },
            provider = provider.trim().ifBlank { PROVIDER_NOT_REPORTED },
            apiEndpoint = apiEndpoint?.trim()?.ifBlank { null },
            inputTokens = counts.inputTokens,
            outputTokens = counts.outputTokens,
            totalTokens = counts.totalTokens,
            cachedInputTokens = counts.cachedInputTokens,
            cacheWriteInputTokens = counts.cacheWriteInputTokens,
            source = source.storedValue,
            inputPricePerToken = applied.inputPricePerToken,
            outputPricePerToken = applied.outputPricePerToken,
            cachedInputPricePerToken = applied.cachedInputPricePerToken,
            cacheWriteInputPricePerToken = applied.cacheWriteInputPricePerToken,
            inputCost = inputCost,
            outputCost = outputCost,
            uncachedInputCost = uncachedInputCost,
            cachedInputCost = cachedInputCost,
            totalCost = totalCost,
            costSource = costSource.storedValue
        )
    }

    fun aggregate(records: List<TurnUsageRecord>): ConversationUsageSummary {
        val grouped = LinkedHashMap<UsageGroupKey, MutableList<TurnUsageRecord>>()
        records.forEach { record -> grouped.getOrPut(record.groupKey()) { mutableListOf() }.add(record) }
        return ConversationUsageSummary(grouped.map { (_, rows) ->
            val inputPrices = rows.mapNotNull { it.inputPricePerToken }.distinctPriceValues()
            val outputPrices = rows.mapNotNull { it.outputPricePerToken }.distinctPriceValues()
            val cachedInputPrices = rows.mapNotNull { it.cachedInputPricePerToken }.distinctPriceValues()
            val cacheWriteInputPrices = rows.mapNotNull { it.cacheWriteInputPricePerToken }
                .distinctPriceValues()
            val hasCacheWritePricing = rows.any { (it.cacheWriteInputTokens ?: 0) > 0 } &&
                (cacheWriteInputPrices.size > 1 || rows.any {
                    (it.cacheWriteInputTokens ?: 0) > 0 &&
                        (it.cacheWriteInputPricePerToken == null ||
                            it.cacheWriteInputPricePerToken != it.inputPricePerToken)
                })
            val displayModel = rows.first().model.trim().ifBlank { MODEL_NOT_REPORTED }
            val displayProvider = rows.first().provider.trim().ifBlank { PROVIDER_NOT_REPORTED }
            UsageGroup(
                model = displayModel,
                provider = displayProvider,
                inputTokens = rows.sumOf { it.inputTokens ?: 0 },
                outputTokens = rows.sumOf { it.outputTokens ?: 0 },
                uncachedInputTokens = rows.sumOf { row ->
                    if (row.inputTokens != null && row.cachedInputTokens != null &&
                        row.inputTokens >= row.cachedInputTokens
                    ) row.inputTokens - row.cachedInputTokens else 0
                },
                cachedInputTokens = rows.sumOf { it.cachedInputTokens ?: 0 },
                inputCost = rows.sumOf { it.inputCost ?: 0.0 },
                outputCost = rows.sumOf { it.outputCost ?: 0.0 },
                uncachedInputCost = rows.sumOf { it.uncachedInputCost ?: 0.0 },
                cachedInputCost = rows.sumOf { it.cachedInputCost ?: 0.0 },
                totalCost = rows.sumOf { it.totalCost ?: 0.0 },
                inputPricePerToken = inputPrices.singleOrNull()
                    ?.takeIf { rows.all { it.inputPricePerToken != null } },
                outputPricePerToken = outputPrices.singleOrNull()
                    ?.takeIf { rows.all { it.outputPricePerToken != null } },
                cachedInputPricePerToken = cachedInputPrices.singleOrNull()
                    ?.takeIf { rows.all { it.cachedInputPricePerToken != null } },
                hasUnknownInputTokens = rows.any { it.inputTokens == null },
                hasUnknownOutputTokens = rows.any { it.outputTokens == null },
                hasUnknownUncachedInputTokens = rows.any {
                    it.inputTokens == null || it.cachedInputTokens == null ||
                        it.cachedInputTokens > it.inputTokens
                },
                hasUnknownCachedInputTokens = rows.any { it.cachedInputTokens == null },
                hasUnknownInputCost = rows.any { it.inputCost == null },
                hasUnknownOutputCost = rows.any { it.outputCost == null },
                hasUnknownUncachedInputCost = rows.any { it.uncachedInputCost == null },
                hasUnknownCachedInputCost = rows.any { it.cachedInputCost == null },
                hasUnknownCost = rows.any { it.totalCost == null },
                hasVariableInputPricing = inputPrices.size > 1 || hasCacheWritePricing,
                hasVariableOutputPricing = outputPrices.size > 1,
                hasVariableCachedInputPricing = cachedInputPrices.size > 1,
                hasVariablePricing = inputPrices.size > 1 || outputPrices.size > 1 ||
                    cachedInputPrices.size > 1 || hasCacheWritePricing,
                containsEstimatedTokens = rows.any { it.countSource == TokenCountSource.ESTIMATED_CL100K },
                recordCount = rows.size
            )
        })
    }

    /** Build a whole-conversation summary. New messages are read exclusively
     * from their frozen records. Only legacy messages invoke [legacyEstimate]. */
    fun summarizeMessages(
        messages: List<Map<String, Any>>,
        legacyEstimate: (assistantIndex: Int) -> TokenCounts
    ): ConversationUsageSummary {
        val records = mutableListOf<TurnUsageRecord>()
        messages.forEachIndexed { index, message ->
            val variantRecords = decodeVariantRecords(message[KEY_VARIANTS]?.toString())
            val stored = if (variantRecords.isNotEmpty()) {
                variantRecords
            } else {
                decodeRecords(message[KEY_USAGE_RECORDS]?.toString())
            }
            if (stored.isNotEmpty()) {
                records.addAll(stored)
                return@forEachIndexed
            }
            if (message["isBot"] != true && message["isBot"]?.toString() != "true") return@forEachIndexed
            if (!MessageCompletionState.isComplete(
                    message[MessageCompletionState.KEY_STATE]?.toString()
                )
            ) return@forEachIndexed

            // Compatibility path: responseTokens is legacy provider TOTAL, so
            // it is deliberately not treated as output. Reconstruct the old
            // CL100K in/out behavior and label it estimated.
            val model = message["responseModel"]?.toString()?.trim()?.ifBlank { null }
                ?: MODEL_NOT_REPORTED
            val counts = legacyEstimate(index).withDerivedTotal()
            records.add(
                createRecord(
                    model = model,
                    provider = message["responseProvider"]?.toString()?.trim()?.ifBlank { null }
                        ?: PROVIDER_NOT_REPORTED,
                    apiEndpoint = null,
                    counts = counts,
                    source = TokenCountSource.ESTIMATED_CL100K,
                    // Old messages contain no frozen price snapshot. Applying
                    // current or nominal pricing would fabricate history.
                    pricing = TokenPricingSnapshot()
                )
            )
        }
        return aggregate(records)
    }

    private fun List<Double>.distinctPriceValues(): List<Double> =
        distinctBy { String.format(Locale.US, "%.15g", it) }
}

/** UI boundary for nullable accounting. Known zero remains "0"/"$0.00000";
 * an incomplete aggregate is never rendered as its partial known sum. */
object UsageValueFormatter {
    const val NOT_REPORTED = "Not Reported"

    fun tokens(knownSum: Int, hasUnknownPart: Boolean): String =
        if (hasUnknownPart) NOT_REPORTED else String.format(Locale.US, "%,d", knownSum)

    fun cost(knownSum: Double, hasUnknownPart: Boolean): String = when {
        hasUnknownPart -> NOT_REPORTED
        knownSum > 0.0 && knownSum < MIN_DISPLAYED_COST -> "<\$0.00001"
        else -> "\$" + String.format(Locale.US, "%.5f", knownSum)
    }

    fun pricePerMillion(pricePerToken: Double?): String = pricePerToken?.let {
        "\$" + String.format(Locale.US, "%.2f", it * 1_000_000)
    } ?: NOT_REPORTED

    fun percentage(numerator: Int, denominator: Int, hasUnknownPart: Boolean): String =
        if (hasUnknownPart || denominator <= 0) NOT_REPORTED
        else String.format(Locale.US, "%.1f%%", numerator.toDouble() * 100.0 / denominator)

    private const val MIN_DISPLAYED_COST = 0.00001
}
