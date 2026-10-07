/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.usage

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/**
 * What a billed quantity of a non-token request measures. Text-token requests
 * (Chat, Summarizing) keep their token fields; this is for requests a service
 * bills in other units, or in tokens that are not text (TTS today). Stored as
 * [key]; the screen chooses each label.
 */
enum class UsageMeterComponent(val key: String) {
    CHARACTERS("characters"),
    UTF8_BYTES("utf8_bytes"),
    TEXT_INPUT("text_input"),
    AUDIO_OUTPUT("audio_output"),
    IMAGE_INPUT("image_input"),
    IMAGE_OUTPUT("image_output"),
    TEXT_OUTPUT("text_output"),
    CACHED_TEXT_INPUT("cached_text_input"),
    CACHED_IMAGE_INPUT("cached_image_input"),
    INPUT("input"),
    OUTPUT("output"),
    IMAGES("images"),
    CREDITS("credits");

    companion object {
        fun fromKey(key: String?): UsageMeterComponent? = entries.firstOrNull { it.key == key }
    }
}

/** The unit a meter's quantity and frozen price are counted in. */
enum class UsageMeterUnit(val key: String) {
    CHARACTER("character"),
    BYTE("byte"),
    TOKEN("token"),
    SECOND("second"),
    IMAGE("image"),
    MEGAPIXEL("megapixel"),
    CREDIT("credit");

    companion object {
        fun fromKey(key: String?): UsageMeterUnit? = entries.firstOrNull { it.key == key }
    }
}

/** Where a meter's quantity came from. */
enum class UsageQuantitySource(val key: String) {
    /** Reported by the serving API for this request. */
    PROVIDER_REPORTED("provider_reported"),
    /** Counted exactly from what this request sent or received. */
    LOCAL_EXACT("local_exact");

    companion object {
        fun fromKey(key: String?): UsageQuantitySource? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One billed component of one request: its quantity in the unit the service
 * bills, and the price frozen with the request ([priceAmount] in [currency]
 * per [priceQuantity] units). Null means not known; it is never estimated.
 */
data class UsageMeter(
    val component: UsageMeterComponent,
    val unit: UsageMeterUnit,
    val quantity: Double?,
    val quantitySource: UsageQuantitySource?,
    val priceAmount: Double? = null,
    val priceQuantity: Double? = null,
    val currency: String? = null,
    val cost: Double? = null
) {
    /** Price per single unit, or null when the price or its basis is unknown. */
    val unitPrice: Double?
        get() = if (priceAmount != null && priceQuantity != null && priceQuantity > 0.0)
            BigDecimal.valueOf(priceAmount)
                .divide(BigDecimal.valueOf(priceQuantity), MathContext.DECIMAL128).toDouble()
        else null

    /** Costs are calculated only from a known quantity and a frozen US-dollar
     * price; a zero price costs nothing whatever the quantity. */
    fun withCalculatedCost(): UsageMeter {
        if (cost != null) return this
        val amount = priceAmount ?: return this
        val basis = priceQuantity?.takeIf { it > 0.0 } ?: return this
        if (!currency.equals("USD", ignoreCase = true)) return this
        if (amount == 0.0) return copy(cost = 0.0)
        val count = quantity ?: return this
        val calculated = BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(amount))
            .divide(BigDecimal.valueOf(basis), MathContext.DECIMAL128).toDouble()
        return copy(cost = calculated)
    }
}

/** A provider block's total for one meter, across its requests. */
data class UsageMeterTotal(
    val component: UsageMeterComponent,
    val unit: UsageMeterUnit,
    val quantity: Double,
    val hasUnknownQuantity: Boolean,
    val cost: Double,
    val hasUnknownCost: Boolean,
    /** The frozen price when every request used the same one; null otherwise. */
    val priceAmount: Double?,
    val priceQuantity: Double?,
    val currency: String?,
    val hasVariablePrice: Boolean
)

object MeteredUsageAccounting {
    /**
     * A record for a request billed by [meters]. [providerTotalCost] is the
     * service's own reported US-dollar charge and is kept exactly. Otherwise
     * the total is the sum of the meters' calculated costs, and only when
     * every billed meter has one: a total is never shown from part of a bill.
     */
    fun record(
        model: String,
        provider: String,
        apiEndpoint: String?,
        meters: List<UsageMeter>,
        providerTotalCost: Double?
    ): TurnUsageRecord {
        val priced = meters.map { it.withCalculatedCost() }
        val calculated = if (priced.isNotEmpty() && priced.all { it.cost != null })
            priced.fold(BigDecimal.ZERO) { sum, meter -> sum.add(BigDecimal.valueOf(meter.cost!!)) }.toDouble()
        else null
        val reported = providerTotalCost?.takeIf { it.isFinite() && it >= 0.0 }
        val total = reported ?: calculated
        val costSource = when {
            reported != null -> CostSource.PROVIDER_REPORTED
            calculated != null -> CostSource.FROZEN_PRICING
            else -> CostSource.UNKNOWN
        }
        return TurnUsageRecord(
            model = model.trim().ifBlank { TokenUsageAccounting.MODEL_NOT_REPORTED },
            provider = provider.trim().ifBlank { TokenUsageAccounting.PROVIDER_NOT_REPORTED },
            apiEndpoint = apiEndpoint?.trim()?.ifBlank { null },
            source = TokenCountSource.PROVIDER_REPORTED.storedValue,
            totalCost = total,
            costSource = costSource.storedValue,
            meters = priced
        )
    }

    /** Null when no record in the group is metered (Chat, Summarizing). A
     * request without a component makes that component's sums unknown. */
    fun aggregate(rows: List<TurnUsageRecord>): List<UsageMeterTotal>? {
        if (rows.none { it.meters != null }) return null
        val keys = rows.flatMap { row -> row.meters.orEmpty().map { it.component to it.unit } }
            .distinct()
            .sortedWith(compareBy({ it.first.ordinal }, { it.second.ordinal }))
        return keys.map { (component, unit) ->
            val matching = rows.map { row ->
                row.meters.orEmpty().firstOrNull { it.component == component && it.unit == unit }
            }
            val present = matching.filterNotNull()
            val prices = present.mapNotNull { meter ->
                meter.unitPrice?.let { Triple(it, meter.currency?.uppercase(Locale.ROOT), meter) }
            }
            val distinct = prices.distinctBy { (perUnit, currency, _) ->
                String.format(Locale.US, "%.15g", perUnit) + "|" + currency
            }
            val single = distinct.singleOrNull()?.takeIf { prices.size == matching.size }?.third
            UsageMeterTotal(
                component = component,
                unit = unit,
                quantity = present.sumOf { it.quantity ?: 0.0 },
                hasUnknownQuantity = matching.any { it?.quantity == null },
                cost = present.sumOf { it.cost ?: 0.0 },
                hasUnknownCost = matching.any { it?.cost == null },
                priceAmount = single?.priceAmount,
                priceQuantity = single?.priceQuantity,
                currency = single?.currency,
                hasVariablePrice = distinct.size > 1
            )
        }
    }
}

/**
 * Meters are written field by field. A minified build erases the generic
 * signature Gson would need to rebuild List<UsageMeter>, which would fill the
 * list with untyped maps; reading them by hand keeps the types in every build.
 */
object UsageMeterCodec {
    fun encode(meters: List<UsageMeter>): JsonArray = JsonArray().apply {
        meters.forEach { meter ->
            add(JsonObject().apply {
                addProperty("component", meter.component.key)
                addProperty("unit", meter.unit.key)
                meter.quantity?.let { addProperty("quantity", it) }
                meter.quantitySource?.let { addProperty("quantitySource", it.key) }
                meter.priceAmount?.let { addProperty("priceAmount", it) }
                meter.priceQuantity?.let { addProperty("priceQuantity", it) }
                meter.currency?.let { addProperty("currency", it) }
                meter.cost?.let { addProperty("cost", it) }
            })
        }
    }

    /** Null when [value] is absent. A meter this version cannot read is left
     * out; the request's stored total is not affected by it. */
    fun decode(value: JsonElement?): List<UsageMeter>? {
        if (value == null || value.isJsonNull || !value.isJsonArray) return null
        return value.asJsonArray.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val component = UsageMeterComponent.fromKey(o.string("component")) ?: return@mapNotNull null
            val unit = UsageMeterUnit.fromKey(o.string("unit")) ?: return@mapNotNull null
            UsageMeter(
                component = component,
                unit = unit,
                quantity = o.number("quantity"),
                quantitySource = UsageQuantitySource.fromKey(o.string("quantitySource")),
                priceAmount = o.number("priceAmount"),
                priceQuantity = o.number("priceQuantity"),
                currency = o.string("currency"),
                cost = o.number("cost")
            )
        }
    }

    fun encodeTotals(totals: List<UsageMeterTotal>): JsonArray = JsonArray().apply {
        totals.forEach { total ->
            add(JsonObject().apply {
                addProperty("component", total.component.key)
                addProperty("unit", total.unit.key)
                addProperty("quantity", total.quantity)
                addProperty("hasUnknownQuantity", total.hasUnknownQuantity)
                addProperty("cost", total.cost)
                addProperty("hasUnknownCost", total.hasUnknownCost)
                total.priceAmount?.let { addProperty("priceAmount", it) }
                total.priceQuantity?.let { addProperty("priceQuantity", it) }
                total.currency?.let { addProperty("currency", it) }
                addProperty("hasVariablePrice", total.hasVariablePrice)
            })
        }
    }

    fun decodeTotals(value: JsonElement?): List<UsageMeterTotal>? {
        if (value == null || value.isJsonNull || !value.isJsonArray) return null
        return value.asJsonArray.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            UsageMeterTotal(
                component = UsageMeterComponent.fromKey(o.string("component")) ?: return@mapNotNull null,
                unit = UsageMeterUnit.fromKey(o.string("unit")) ?: return@mapNotNull null,
                quantity = o.number("quantity") ?: 0.0,
                hasUnknownQuantity = o.bool("hasUnknownQuantity") ?: true,
                cost = o.number("cost") ?: 0.0,
                hasUnknownCost = o.bool("hasUnknownCost") ?: true,
                priceAmount = o.number("priceAmount"),
                priceQuantity = o.number("priceQuantity"),
                currency = o.string("currency"),
                hasVariablePrice = o.bool("hasVariablePrice") ?: false
            )
        }
    }

    private fun JsonObject.string(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.number(key: String): Double? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }
    private fun JsonObject.bool(key: String): Boolean? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
