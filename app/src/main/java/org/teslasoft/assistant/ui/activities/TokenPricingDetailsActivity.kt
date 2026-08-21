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
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.UsageValueFormatter

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
        val container = findViewById<LinearLayout>(R.id.pricing_sections)
        summary.groups.forEach { group ->
            val section = LayoutInflater.from(this)
                .inflate(R.layout.view_token_pricing_section, container, false)
            section.findViewById<TextView>(R.id.text_pricing_group_title).text =
                getString(R.string.token_pricing_group_title, group.model, group.provider)
            section.findViewById<TextView>(R.id.text_pricing_group_usage).text =
                getString(R.string.cost_counter_usage)
                    .format(
                        UsageValueFormatter.tokens(
                            group.inputTokens, group.hasUnknownInputTokens
                        ),
                        UsageValueFormatter.tokens(
                            group.outputTokens, group.hasUnknownOutputTokens
                        )
                    )
            section.findViewById<TextView>(R.id.text_pricing_group_cost).text = buildString {
                append(getString(
                    R.string.cost_values_line,
                    UsageValueFormatter.cost(group.inputCost, group.hasUnknownInputCost),
                    UsageValueFormatter.cost(group.outputCost, group.hasUnknownOutputCost)
                ))
                append('\n').append(getString(
                    R.string.cost_total_value,
                    UsageValueFormatter.cost(group.totalCost, group.hasUnknownCost)
                ))
                append('\n').append(
                    if (group.hasVariablePricing) {
                        getString(R.string.cost_price_variable_line)
                    } else {
                        getString(
                            R.string.cost_price_values_line,
                            UsageValueFormatter.pricePerMillion(group.inputPricePerToken),
                            UsageValueFormatter.pricePerMillion(group.outputPricePerToken)
                        )
                    }
                )
            }
            container.addView(section)
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
