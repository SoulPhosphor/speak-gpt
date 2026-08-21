/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *************************************************************************/

package org.teslasoft.assistant.usage

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale
import kotlin.math.max

enum class TokenCountSource(val storedValue: String) {
    PROVIDER_REPORTED("provider_reported"),
    ESTIMATED_CL100K("estimated_cl100k");

    companion object {
        fun fromStored(value: String?): TokenCountSource =
            entries.firstOrNull { it.storedValue == value } ?: ESTIMATED_CL100K
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
        if (input == null && total != null && output != null) input = max(0, total - output)
        if (output == null && total != null && input != null) output = max(0, total - input)
        return TokenCounts(input, output, total)
    }

    fun hasAnyValue(): Boolean = inputTokens != null || outputTokens != null || totalTokens != null
}

data class TokenPricingSnapshot(
    val inputPricePerToken: Double? = null,
    val outputPricePerToken: Double? = null
)

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
    val totalCost: Double? = null
) {
    val countSource: TokenCountSource get() = TokenCountSource.fromStored(source)

    fun groupKey(): UsageGroupKey = UsageGroupKey(model.trim(), provider.trim())
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
    val hasUnknownCost: Boolean get() = groups.any { it.hasUnknownCost }
}

object TokenUsageAccounting {
    const val KEY_USAGE_RECORDS = "tokenUsageRecords"

    const val PROVIDER_NOT_REPORTED = "Not Reported"
    const val MODEL_NOT_RECORDED = "Not Recorded"

    private val gson = Gson()
    private val recordListType = object : TypeToken<ArrayList<TurnUsageRecord>>() {}.type

    fun encodeRecords(records: List<TurnUsageRecord>): String = gson.toJson(records)

    fun decodeRecords(value: String?): List<TurnUsageRecord> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson<ArrayList<TurnUsageRecord>>(value, recordListType) ?: emptyList()
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
        pricing: TokenPricingSnapshot
    ): TurnUsageRecord {
        val inputCost = counts.inputTokens?.let { count ->
            pricing.inputPricePerToken?.let { count * it }
        }
        val outputCost = counts.outputTokens?.let { count ->
            pricing.outputPricePerToken?.let { count * it }
        }
        val totalCost = if (inputCost != null && outputCost != null) inputCost + outputCost else null
        return TurnUsageRecord(
            model = model.trim().ifBlank { MODEL_NOT_RECORDED },
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
            totalCost = totalCost
        )
    }

    fun aggregate(records: List<TurnUsageRecord>): ConversationUsageSummary {
        val grouped = LinkedHashMap<UsageGroupKey, MutableList<TurnUsageRecord>>()
        records.forEach { record -> grouped.getOrPut(record.groupKey()) { mutableListOf() }.add(record) }
        return ConversationUsageSummary(grouped.map { (key, rows) ->
            val inputPrices = rows.mapNotNull { it.inputPricePerToken }.distinctPriceValues()
            val outputPrices = rows.mapNotNull { it.outputPricePerToken }.distinctPriceValues()
            UsageGroup(
                model = key.model,
                provider = key.provider,
                inputTokens = rows.sumOf { it.inputTokens ?: 0 },
                outputTokens = rows.sumOf { it.outputTokens ?: 0 },
                inputCost = rows.sumOf { it.inputCost ?: 0.0 },
                outputCost = rows.sumOf { it.outputCost ?: 0.0 },
                totalCost = rows.sumOf { it.totalCost ?: 0.0 },
                inputPricePerToken = inputPrices.singleOrNull(),
                outputPricePerToken = outputPrices.singleOrNull(),
                hasUnknownInputTokens = rows.any { it.inputTokens == null },
                hasUnknownOutputTokens = rows.any { it.outputTokens == null },
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
        fallbackModel: String,
        fallbackProvider: String = PROVIDER_NOT_REPORTED,
        fallbackEndpoint: String? = null,
        legacyEstimate: (assistantIndex: Int) -> TokenCounts
    ): ConversationUsageSummary {
        val records = mutableListOf<TurnUsageRecord>()
        messages.forEachIndexed { index, message ->
            if (message["isBot"] != true && message["isBot"]?.toString() != "true") return@forEachIndexed
            val stored = decodeRecords(message[KEY_USAGE_RECORDS]?.toString())
            if (stored.isNotEmpty()) {
                records.addAll(stored)
                return@forEachIndexed
            }

            // Compatibility path: responseTokens is legacy provider TOTAL, so
            // it is deliberately not treated as output. Reconstruct the old
            // CL100K in/out behavior and label it estimated.
            val model = message["responseModel"]?.toString()?.trim()?.ifBlank { null }
                ?: fallbackModel.trim().ifBlank { MODEL_NOT_RECORDED }
            val counts = legacyEstimate(index).withDerivedTotal()
            val pricing = LegacyTokenPricing.forModel(model) ?: TokenPricingSnapshot()
            records.add(
                createRecord(
                    model = model,
                    provider = message["responseProvider"]?.toString()?.trim()?.ifBlank { null }
                        ?: fallbackProvider,
                    apiEndpoint = fallbackEndpoint,
                    counts = counts,
                    source = TokenCountSource.ESTIMATED_CL100K,
                    pricing = pricing
                )
            )
        }
        return aggregate(records)
    }

    private fun List<Double>.distinctPriceValues(): List<Double> =
        distinctBy { String.format(Locale.US, "%.15g", it) }
}

enum class UsageCardMode { EMPTY, SINGLE_PRICING, MULTI_PRICING }

data class QuickSettingsUsagePresentation(
    val mode: UsageCardMode,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalCost: Double,
    val singleGroup: UsageGroup? = null
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
                summary.groups.single()
            )
            else -> QuickSettingsUsagePresentation(
                UsageCardMode.MULTI_PRICING,
                summary.totalInputTokens,
                summary.totalOutputTokens,
                summary.totalCost
            )
        }
    }
}
