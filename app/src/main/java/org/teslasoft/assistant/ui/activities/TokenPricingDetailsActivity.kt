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
import org.teslasoft.assistant.usage.UsageGroup
import org.teslasoft.assistant.usage.UsageValueFormatter
import java.util.Locale

class TokenPricingDetailsActivity : FragmentActivity() {
    companion object {
        const val EXTRA_USAGE_SUMMARY = "usageSummary"
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
        val container = findViewById<LinearLayout>(R.id.pricing_sections)
        modelSections(summary).forEach { model ->
            val section = LayoutInflater.from(this)
                .inflate(R.layout.view_usage_model_section, container, false)
            bindModelSummary(section, model)
            val providers = section.findViewById<LinearLayout>(R.id.provider_cards)
            model.providers.forEach { group ->
                val card = LayoutInflater.from(this)
                    .inflate(R.layout.view_usage_provider_card, providers, false)
                bindProviderCard(card, group)
                providers.addView(card)
            }
            container.addView(section)
        }
    }

    private data class ModelSection(
        val model: String,
        val providers: List<UsageGroup>
    ) {
        val recordCount: Int get() = providers.sumOf { it.recordCount }
        val totalCost: Double get() = providers.sumOf { it.totalCost }
        val hasUnknownCost: Boolean get() = providers.any { it.hasUnknownCost }
        val uncachedInputTokens: Int get() = providers.sumOf { it.uncachedInputTokens }
        val cachedInputTokens: Int get() = providers.sumOf { it.cachedInputTokens }
        val outputTokens: Int get() = providers.sumOf { it.outputTokens }
        val uncachedInputCost: Double get() = providers.sumOf { it.uncachedInputCost }
        val cachedInputCost: Double get() = providers.sumOf { it.cachedInputCost }
        val outputCost: Double get() = providers.sumOf { it.outputCost }
        val unknownUncachedTokens: Boolean get() = providers.any { it.hasUnknownUncachedInputTokens }
        val unknownCachedTokens: Boolean get() = providers.any { it.hasUnknownCachedInputTokens }
        val unknownOutputTokens: Boolean get() = providers.any { it.hasUnknownOutputTokens }
        val unknownUncachedCost: Boolean get() = providers.any { it.hasUnknownUncachedInputCost }
        val unknownCachedCost: Boolean get() = providers.any { it.hasUnknownCachedInputCost }
        val unknownOutputCost: Boolean get() = providers.any { it.hasUnknownOutputCost }
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

    private fun bindModelSummary(view: View, model: ModelSection) {
        view.findViewById<TextView>(R.id.model_name).text = model.model
        view.findViewById<TextView>(R.id.model_meta).text = getString(
            R.string.usage_model_meta, model.recordCount, model.providers.size
        )
        view.findViewById<TextView>(R.id.model_total_cost).text =
            UsageValueFormatter.cost(model.totalCost, model.hasUnknownCost)
        bindFact(
            view, R.id.model_input, R.string.usage_input,
            UsageValueFormatter.tokens(model.uncachedInputTokens, model.unknownUncachedTokens),
            UsageValueFormatter.cost(model.uncachedInputCost, model.unknownUncachedCost)
        )
        bindFact(
            view, R.id.model_output, R.string.usage_output,
            UsageValueFormatter.tokens(model.outputTokens, model.unknownOutputTokens),
            UsageValueFormatter.cost(model.outputCost, model.unknownOutputCost)
        )
        bindFact(
            view, R.id.model_cached, R.string.usage_cached,
            UsageValueFormatter.tokens(model.cachedInputTokens, model.unknownCachedTokens),
            UsageValueFormatter.cost(model.cachedInputCost, model.unknownCachedCost)
        )
    }

    private fun bindFact(
        root: View,
        factId: Int,
        labelId: Int,
        tokens: String,
        cost: String
    ) {
        val fact = root.findViewById<View>(factId)
        fact.findViewById<TextView>(R.id.fact_label).setText(labelId)
        fact.findViewById<TextView>(R.id.fact_tokens).text = tokens
        fact.findViewById<TextView>(R.id.fact_cost).text = cost
    }

    private fun bindProviderCard(view: View, group: UsageGroup) {
        view.findViewById<TextView>(R.id.provider_name).text = group.provider
        view.findViewById<TextView>(R.id.provider_meta).text =
            getString(R.string.usage_provider_meta, group.recordCount)
        view.findViewById<TextView>(R.id.provider_total_cost).text =
            UsageValueFormatter.cost(group.totalCost, group.hasUnknownCost)

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
