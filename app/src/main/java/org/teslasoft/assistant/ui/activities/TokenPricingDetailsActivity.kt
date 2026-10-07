/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.teslasoft.assistant.R
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.usage.ConversationUsageSummary
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageFunction
import org.teslasoft.assistant.usage.UsageGroup
import org.teslasoft.assistant.usage.UsageLog
import org.teslasoft.assistant.usage.UsageMeterComponent
import org.teslasoft.assistant.usage.UsageMeterTotal
import org.teslasoft.assistant.usage.UsageMeterUnit
import org.teslasoft.assistant.usage.UsageSection
import org.teslasoft.assistant.usage.UsageValueFormatter
import java.util.Locale

class TokenPricingDetailsActivity : FragmentActivity() {
    companion object {
        const val EXTRA_USAGE_SUMMARY = "usageSummary"
        const val EXTRA_USAGE_SECTIONS = "usageSections"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_token_pricing_details)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        val summary = TokenUsageAccounting.decodeSummary(
            intent.getStringExtra(EXTRA_USAGE_SUMMARY)
        )
        renderConversationSummary(summary)
        val sections = UsageLog.decodeSections(intent.getStringExtra(EXTRA_USAGE_SECTIONS))
            .ifEmpty {
                listOfNotNull(
                    summary.takeIf { it.groups.isNotEmpty() }
                        ?.let { UsageSection(UsageCategory.CHAT, it) }
                )
            }
        val container = findViewById<LinearLayout>(R.id.pricing_sections)
        val inflater = LayoutInflater.from(this)
        // Sections arrive in screen order and only when they have requests.
        sections.forEach { usageSection ->
            val pill = inflater.inflate(R.layout.view_usage_section_pill, container, false) as TextView
            pill.setText(sectionTitle(usageSection.category))
            container.addView(pill)
            modelSections(usageSection.summary).forEach { model ->
                val card = inflater.inflate(R.layout.view_usage_model_section, container, false)
                bindModelSummary(card, model, usageSection.functionsByModel[model.key].orEmpty())
                val providers = card.findViewById<LinearLayout>(R.id.provider_cards)
                model.providers.forEachIndexed { index, group ->
                    val block = inflater.inflate(R.layout.view_usage_provider_block, providers, false)
                    bindProviderCard(block, group)
                    block.findViewById<View>(R.id.provider_gap).visibility =
                        if (index == 0) View.GONE else View.VISIBLE
                    // Only the last provider meets the card's rounded bottom.
                    block.findViewById<View>(R.id.pricing_footer).setBackgroundResource(
                        if (index == model.providers.lastIndex) R.drawable.bg_usage_pricing_footer
                        else R.drawable.bg_usage_pricing_footer_inner
                    )
                    providers.addView(block)
                }
                container.addView(card)
            }
        }
    }

    private fun sectionTitle(category: UsageCategory): Int = when (category) {
        UsageCategory.CHAT -> R.string.usage_section_chat
        UsageCategory.IMAGE_GENERATION -> R.string.usage_section_image_generations
        UsageCategory.SUMMARIZING -> R.string.usage_section_summarizing
        UsageCategory.STT -> R.string.usage_section_stt
        UsageCategory.TTS -> R.string.usage_section_tts
    }

    private fun functionName(function: UsageFunction): Int = when (function) {
        UsageFunction.SUMMARIZING -> R.string.usage_function_summarizing
        UsageFunction.COMPACTING -> R.string.usage_function_compacting
        UsageFunction.CONDENSING -> R.string.usage_function_condensing
        UsageFunction.REDUCING -> R.string.usage_function_reducing
        UsageFunction.IMAGE_DESCRIPTION -> R.string.usage_function_image_description
        UsageFunction.REMOVAL -> R.string.usage_function_removal
    }

    private data class ModelSection(
        val model: String,
        val providers: List<UsageGroup>
    ) {
        val key: String get() = model.trim().lowercase(Locale.ROOT)
        val recordCount: Int get() = providers.sumOf { it.recordCount }
        val totalCost: Double get() = providers.sumOf { it.totalCost }
        val hasUnknownCost: Boolean get() = providers.any { it.hasUnknownCost }
    }

    private fun modelSections(summary: ConversationUsageSummary): List<ModelSection> =
        summary.groups
            .groupBy { it.model.trim().lowercase(Locale.ROOT) }
            .values
            .map { groups ->
                ModelSection(
                    groups.first().model,
                    groups.sortedBy { it.provider.lowercase(Locale.ROOT) }
                )
            }
            .sortedBy { it.model.lowercase(Locale.ROOT) }

    private fun renderConversationSummary(summary: ConversationUsageSummary) {
        findViewById<TextView>(R.id.conversation_total_cost).text =
            UsageValueFormatter.cost(summary.totalCost, summary.hasUnknownCost)
        val requests = summary.groups.sumOf { it.recordCount }
        val models = summary.groups.map { it.model.lowercase(Locale.ROOT) }.distinct().size
        val providers = summary.groups.map { it.provider.lowercase(Locale.ROOT) }.distinct().size
        findViewById<TextView>(R.id.conversation_usage_meta).text =
            getString(R.string.usage_conversation_meta, requests, models, providers)
    }

    private fun bindModelSummary(view: View, model: ModelSection, functions: List<UsageFunction>) {
        view.findViewById<TextView>(R.id.model_name).text = model.model
        view.findViewById<TextView>(R.id.model_meta).text = getString(
            R.string.usage_model_meta, model.recordCount, model.providers.size
        )
        view.findViewById<TextView>(R.id.model_total_cost).text =
            UsageValueFormatter.cost(model.totalCost, model.hasUnknownCost)
        val functionLine = view.findViewById<TextView>(R.id.model_functions)
        if (functions.isEmpty()) {
            functionLine.visibility = View.GONE
        } else {
            functionLine.text = functions.joinToString(", ") { getString(functionName(it)) }
            functionLine.visibility = View.VISIBLE
        }
    }

    private fun bindProviderCard(view: View, group: UsageGroup) {
        view.findViewById<TextView>(R.id.provider_name).text = group.provider
        view.findViewById<TextView>(R.id.provider_meta).text =
            getString(R.string.usage_provider_meta, group.recordCount)
        view.findViewById<TextView>(R.id.provider_total_cost).text =
            UsageValueFormatter.cost(group.totalCost, group.hasUnknownCost)
        group.meters?.let { meters ->
            bindMeteredRows(view, meters)
            return
        }

        bindUsageRow(
            view, R.id.usage_input_row, R.string.usage_input,
            UsageValueFormatter.tokens(
                group.uncachedInputTokens, group.hasUnknownUncachedInputTokens
            ),
            UsageValueFormatter.cost(
                group.uncachedInputCost, group.hasUnknownUncachedInputCost
            )
        )
        bindUsageRow(
            view, R.id.usage_output_row, R.string.usage_output,
            UsageValueFormatter.tokens(group.outputTokens, group.hasUnknownOutputTokens),
            UsageValueFormatter.cost(group.outputCost, group.hasUnknownOutputCost)
        )
        bindUsageRow(
            view, R.id.usage_cached_row, R.string.usage_cached,
            UsageValueFormatter.tokens(
                group.cachedInputTokens, group.hasUnknownCachedInputTokens
            ),
            UsageValueFormatter.cost(
                group.cachedInputCost, group.hasUnknownCachedInputCost
            )
        )

        view.findViewById<TextView>(R.id.cache_hit_rate).text =
            UsageValueFormatter.percentage(
                group.cachedInputTokens,
                group.inputTokens,
                group.hasUnknownCachedInputTokens || group.hasUnknownInputTokens
            )

        bindPrice(
            view, R.id.price_input, R.string.usage_input,
            group.inputPricePerToken, group.hasVariableInputPricing
        )
        bindPrice(
            view, R.id.price_output, R.string.usage_output,
            group.outputPricePerToken, group.hasVariableOutputPricing
        )
        bindPrice(
            view, R.id.price_cached, R.string.usage_cached,
            group.cachedInputPricePerToken, group.hasVariableCachedInputPricing
        )
    }

    /**
     * A request billed in units other than text tokens (TTS) shows only what was billed,
     * in its own unit: no token header, Cached row or Cache Hit Rate, and the price
     * caption names the actual basis.
     */
    private fun bindMeteredRows(view: View, meters: List<UsageMeterTotal>) {
        val inflater = LayoutInflater.from(this)
        val table = view.findViewById<LinearLayout>(R.id.usage_table)
        table.removeAllViews()
        MeteredUsagePresentation.rows(meters) { getString(R.string.usage_duration_seconds, it) }
            .forEachIndexed { index, line ->
                if (index > 0) table.addView(inflater.inflate(R.layout.view_usage_table_divider, table, false))
                val row = inflater.inflate(R.layout.view_usage_table_row, table, false)
                table.addView(row)
                row.findViewById<TextView>(R.id.usage_label).setText(line.label)
                row.findViewById<TextView>(R.id.usage_tokens).text = line.quantity
                row.findViewById<TextView>(R.id.usage_cost).text = line.cost
            }
        table.visibility = if (meters.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.cache_hit_rate_box).visibility = View.GONE

        val facts = view.findViewById<LinearLayout>(R.id.price_facts)
        facts.removeAllViews()
        MeteredUsagePresentation.prices(meters, getString(R.string.usage_variable_price)).forEach { price ->
            val fact = inflater.inflate(R.layout.view_usage_price_fact, facts, false)
            facts.addView(fact)
            fact.findViewById<TextView>(R.id.price_label).setText(price.label)
            fact.findViewById<TextView>(R.id.price_value).text = price.value
        }
        view.findViewById<TextView>(R.id.price_caption).text =
            MeteredUsagePresentation.captions(meters).joinToString(" · ") { getString(it) }
        view.findViewById<View>(R.id.pricing_footer).visibility =
            if (meters.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindUsageRow(
        root: View,
        rowId: Int,
        labelId: Int,
        tokens: String,
        cost: String
    ) {
        val row = root.findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.usage_label).setText(labelId)
        row.findViewById<TextView>(R.id.usage_tokens).text = tokens
        row.findViewById<TextView>(R.id.usage_cost).text = cost
    }

    private fun bindPrice(
        root: View,
        factId: Int,
        labelId: Int,
        price: Double?,
        variable: Boolean
    ) {
        val fact = root.findViewById<View>(factId)
        fact.findViewById<TextView>(R.id.price_label).setText(labelId)
        fact.findViewById<TextView>(R.id.price_value).text = if (variable) {
            getString(R.string.usage_variable_price)
        } else {
            UsageValueFormatter.pricePerMillion(price)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 35) return
        try {
            findViewById<View>(R.id.action_bar)?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )
        } catch (_: Exception) { }
    }
}

/** What the Usage & Cost screen shows for metered (TTS) usage: label resources and text. */
internal object MeteredUsagePresentation {
    data class Row(val label: Int, val quantity: String, val cost: String)
    data class Price(val label: Int, val value: String)

    /** [seconds] adds the unit to a duration, for example "12.4 sec". */
    fun rows(meters: List<UsageMeterTotal>, seconds: (String) -> String): List<Row> = meters.map { meter ->
        Row(label(meter.component), when {
            meter.hasUnknownQuantity -> UsageValueFormatter.NOT_REPORTED
            meter.unit == UsageMeterUnit.SECOND -> seconds(UsageValueFormatter.seconds(meter.quantity))
            else -> UsageValueFormatter.count(meter.quantity, false)
        }, UsageValueFormatter.cost(meter.cost, meter.hasUnknownCost))
    }

    fun prices(meters: List<UsageMeterTotal>, variable: String): List<Price> = meters.map { meter ->
        Price(label(meter.component), when {
            meter.hasVariablePrice -> variable
            meter.currency?.equals("USD", ignoreCase = true) != true -> UsageValueFormatter.NOT_REPORTED
            else -> UsageValueFormatter.price(displayedRate(meter))
        })
    }

    /** One caption per distinct unit, naming the basis the prices are shown in. */
    fun captions(meters: List<UsageMeterTotal>): List<Int> = meters.map { it.unit }.distinct().map {
        when (it) {
            UsageMeterUnit.CHARACTER -> R.string.usage_price_per_million_characters
            UsageMeterUnit.BYTE -> R.string.usage_price_per_million_utf8_bytes
            UsageMeterUnit.TOKEN -> R.string.usage_price_per_million
            UsageMeterUnit.SECOND -> R.string.usage_price_per_minute_audio
        }
    }

    fun label(component: UsageMeterComponent): Int = when (component) {
        UsageMeterComponent.CHARACTERS -> R.string.usage_meter_characters
        UsageMeterComponent.UTF8_BYTES -> R.string.usage_meter_utf8_bytes
        UsageMeterComponent.TEXT_INPUT -> R.string.usage_meter_text_input
        UsageMeterComponent.AUDIO_OUTPUT -> R.string.usage_meter_audio_output
    }

    /** The frozen rate restated per 1M units, or per minute of audio. */
    private fun displayedRate(meter: UsageMeterTotal): Double? {
        val amount = meter.priceAmount ?: return null
        val basis = meter.priceQuantity?.takeIf { it > 0.0 } ?: return null
        val per = if (meter.unit == UsageMeterUnit.SECOND) 60.0 else 1_000_000.0
        return java.math.BigDecimal.valueOf(amount).multiply(java.math.BigDecimal.valueOf(per))
            .divide(java.math.BigDecimal.valueOf(basis), java.math.MathContext.DECIMAL128).toDouble()
    }
}
