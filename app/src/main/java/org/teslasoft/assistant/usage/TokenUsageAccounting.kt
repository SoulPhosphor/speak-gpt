/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *************************************************************************/

package org.teslasoft.assistant.usage

import com.google.gson.Gson
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
    val totalTokens: Int?
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
        return TokenCounts(input, output, total)
    }

    fun hasAnyValue(): Boolean = inputTokens != null || outputTokens != null || totalTokens != null
}

data class TokenPricingSnapshot(
    val inputPricePerToken: Double? = null,
    val outputPricePerToken: Double? = null
)

/** Exact monetary values returned by the serving API. A reported total does
 * not imply that the provider supplied an input/output split. */
data class ProviderReportedCost(
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val totalCost: Double? = null
) {
    fun hasAnyValue(): Boolean = inputCost != null || outputCost != null || totalCost != null

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
    val source: String = TokenCountSource.ESTIMATED_CL100K.storedValue,
    val inputPricePerToken: Double? = null,
    val outputPricePerToken: Double? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
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
    val inputCost: Double,
    val outputCost: Double,
    val totalCost: Double,
    val inputPricePerToken: Double?,
    val outputPricePerToken: Double?,
    val hasUnknownInputTokens: Boolean,
    val hasUnknownOutputTokens: Boolean,
    val hasUnknownInputCost: Boolean,
    val hasUnknownOutputCost: Boolean,
    val hasUnknownCost: Boolean,
    val hasVariablePricing: Boolean,
    val containsEstimatedTokens: Boolean,
    val recordCount: Int
)

data class ConversationUsageSummary(
    val groups: List<UsageGroup>
) {
    val totalInputTokens: Int get() = groups.sumOf { it.inputTokens }
    val totalOutputTokens: Int get() = groups.sumOf { it.outputTokens }
    val totalCost: Double get() = groups.sumOf { it.totalCost }
    val isMultiPricing: Boolean get() = groups.size > 1
    val hasUnknownInputTokens: Boolean get() = groups.any { it.hasUnknownInputTokens }
    val hasUnknownOutputTokens: Boolean get() = groups.any { it.hasUnknownOutputTokens }
    val hasUnknownInputCost: Boolean get() = groups.any { it.hasUnknownInputCost }
    val hasUnknownOutputCost: Boolean get() = groups.any { it.hasUnknownOutputCost }
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

    fun decodeSummary(value: String?): ConversationUsageSummary = try {
        gson.fromJson(value, ConversationUsageSummary::class.java)
            ?: ConversationUsageSummary(emptyList())
    } catch (_: Exception) {
        ConversationUsageSummary(emptyList())
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
    ): TurnUsageRecord {
        val exactCost = providerCost?.takeIf { it.hasAnyValue() }?.withDerivedTotal()
        val calculatedInputCost = counts.inputTokens?.let { count ->
            pricing.inputPricePerToken?.let { count * it }
        }
        val calculatedOutputCost = counts.outputTokens?.let { count ->
            pricing.outputPricePerToken?.let { count * it }
        }
        val inputCost: Double?
        val outputCost: Double?
        val totalCost: Double?
        val costSource: CostSource
        if (exactCost != null) {
            // Provider cost is authoritative as a unit. Never fill an absent
            // provider split with nominal-price calculations.
            inputCost = exactCost.inputCost
            outputCost = exactCost.outputCost
            totalCost = exactCost.totalCost
            costSource = CostSource.PROVIDER_REPORTED
        } else {
            inputCost = calculatedInputCost
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
            source = source.storedValue,
            inputPricePerToken = pricing.inputPricePerToken,
            outputPricePerToken = pricing.outputPricePerToken,
            inputCost = inputCost,
            outputCost = outputCost,
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
            val displayModel = rows.first().model.trim().ifBlank { MODEL_NOT_REPORTED }
            val displayProvider = rows.first().provider.trim().ifBlank { PROVIDER_NOT_REPORTED }
            UsageGroup(
                model = displayModel,
                provider = displayProvider,
                inputTokens = rows.sumOf { it.inputTokens ?: 0 },
                outputTokens = rows.sumOf { it.outputTokens ?: 0 },
                inputCost = rows.sumOf { it.inputCost ?: 0.0 },
                outputCost = rows.sumOf { it.outputCost ?: 0.0 },
                totalCost = rows.sumOf { it.totalCost ?: 0.0 },
                inputPricePerToken = inputPrices.singleOrNull()
                    ?.takeIf { rows.all { it.inputPricePerToken != null } },
                outputPricePerToken = outputPrices.singleOrNull()
                    ?.takeIf { rows.all { it.outputPricePerToken != null } },
                hasUnknownInputTokens = rows.any { it.inputTokens == null },
                hasUnknownOutputTokens = rows.any { it.outputTokens == null },
                hasUnknownInputCost = rows.any { it.inputCost == null },
                hasUnknownOutputCost = rows.any { it.outputCost == null },
                hasUnknownCost = rows.any { it.totalCost == null },
                hasVariablePricing = inputPrices.size > 1 || outputPrices.size > 1,
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
        if (hasUnknownPart) NOT_REPORTED else knownSum.toString()

    fun cost(knownSum: Double, hasUnknownPart: Boolean): String = when {
        hasUnknownPart -> NOT_REPORTED
        knownSum > 0.0 && knownSum < MIN_DISPLAYED_COST -> "<\$0.00001"
        else -> "\$" + String.format(Locale.US, "%.5f", knownSum)
    }

    fun pricePerMillion(pricePerToken: Double?): String = pricePerToken?.let {
        "\$" + String.format(Locale.US, "%.2f", it * 1_000_000)
    } ?: NOT_REPORTED

    private const val MIN_DISPLAYED_COST = 0.00001
}

enum class UsageCardMode { EMPTY, SINGLE_PRICING, MULTI_PRICING }

data class QuickSettingsUsagePresentation(
    val mode: UsageCardMode,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalCost: Double,
    val singleGroup: UsageGroup? = null,
    val hasUnknownInputTokens: Boolean = false,
    val hasUnknownOutputTokens: Boolean = false,
    val hasUnknownCost: Boolean = false
) {
    val showPricingDetails: Boolean get() = mode == UsageCardMode.MULTI_PRICING

    companion object {
        fun from(summary: ConversationUsageSummary): QuickSettingsUsagePresentation = when {
            summary.groups.isEmpty() -> QuickSettingsUsagePresentation(UsageCardMode.EMPTY, 0, 0, 0.0)
            summary.groups.size == 1 -> QuickSettingsUsagePresentation(
                UsageCardMode.SINGLE_PRICING,
                summary.totalInputTokens,
                summary.totalOutputTokens,
                summary.totalCost,
                summary.groups.single(),
                summary.hasUnknownInputTokens,
                summary.hasUnknownOutputTokens,
                summary.hasUnknownCost
            )
            else -> QuickSettingsUsagePresentation(
                UsageCardMode.MULTI_PRICING,
                summary.totalInputTokens,
                summary.totalOutputTokens,
                summary.totalCost,
                hasUnknownInputTokens = summary.hasUnknownInputTokens,
                hasUnknownOutputTokens = summary.hasUnknownOutputTokens,
                hasUnknownCost = summary.hasUnknownCost
            )
        }
    }
}
