package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCostStructureTest {
    @Test
    fun eachModelCardHoldsItsProvidersBelowASectionPill() {
        val section = resource("layout/view_usage_model_section.xml")
        val provider = resource("layout/view_usage_provider_block.xml")
        val header = resource("layout/view_usage_model_summary.xml")

        // Owner ruling, October 6 2026: the model header and its providers
        // are one card, and only the provider blocks sit inside it.
        assertTrue(section.contains("MaterialCardView"))
        assertTrue(section.contains("view_usage_model_summary"))
        assertTrue(section.contains("@+id/provider_cards"))
        assertFalse(provider.contains("MaterialCardView"))
        assertTrue(provider.contains("@+id/provider_gap"))
        assertTrue(provider.contains("@+id/usage_input_row"))
        assertTrue(provider.contains("@+id/usage_output_row"))
        assertTrue(provider.contains("@+id/usage_cached_row"))
        assertTrue(provider.contains("@+id/cache_hit_rate"))
        assertFalse(provider.contains("Breakdown"))
        assertTrue(header.contains("@+id/model_functions"))
        assertTrue(resource("layout/view_usage_section_pill.xml").contains("Widget.App.Usage.SectionPill"))
    }

    @Test
    fun usageColorsComeFromThemeRoles() {
        for (drawable in listOf(
            "drawable/bg_usage_section_pill.xml",
            "drawable/bg_usage_model_header.xml",
            "drawable/bg_usage_provider_header.xml",
            "drawable/bg_usage_pricing_footer.xml",
            "drawable/bg_usage_pricing_footer_inner.xml"
        )) {
            val xml = resource(drawable)
            assertFalse(drawable, Regex("android:(start|end|)[cC]olor=\"#").containsMatchIn(xml))
            assertFalse(drawable, xml.contains("@color/"))
        }
    }

    @Test
    fun priceCaptionIsCenteredBelowInputOutputAndCachedPrices() {
        val provider = resource("layout/view_usage_provider_block.xml")
        val input = provider.indexOf("@+id/price_input")
        val output = provider.indexOf("@+id/price_output")
        val cached = provider.indexOf("@+id/price_cached")
        val caption = provider.indexOf("@string/usage_price_per_million")
        val captionStyle = provider.lastIndexOf("@style/Widget.App.Usage.PriceCaption", caption)
        val themes = resource("values/themes.xml")
        val captionDefinition = themes.substringAfter("<style name=\"Widget.App.Usage.PriceCaption\"")
            .substringBefore("</style>")

        assertTrue(input in 0 until output)
        assertTrue(output < cached)
        assertTrue(cached < caption)
        assertTrue(captionStyle in (cached + 1) until caption)
        assertTrue(captionDefinition.contains("<item name=\"android:gravity\">center</item>"))
    }

    @Test
    fun usageLayoutsTakeEveryVisualValueFromStyles() {
        // Owner ruling, October 7 2026: layouts hold structure only, so the
        // screen is themed and fixed from the styles alone.
        val visual = Regex("""android:(textSize|textColor|textStyle|background|gravity|minHeight|""" +
            """padding\w*|layout_margin\w*|layout_weight|clipToPadding)=|""" +
            """android:layout_(width|height)="(?!match_parent|wrap_content)""")
        for (layout in listOf("activity_token_pricing_details", "view_usage_model_section",
            "view_usage_model_summary", "view_usage_provider_block", "view_usage_section_pill",
            "view_usage_table_header", "view_usage_table_row", "view_usage_price_fact",
            "view_usage_table_divider")) {
            val xml = resource("layout/$layout.xml")
            assertFalse("$layout: ${visual.find(xml)?.value}", visual.containsMatchIn(xml))
        }
        for (drawable in listOf("bg_usage_cache_rate", "bg_usage_section_pill", "bg_usage_model_header",
            "bg_usage_pricing_footer")) {
            assertFalse(drawable, Regex("""[Rr]adius="\d""").containsMatchIn(resource("drawable/$drawable.xml")))
        }
    }

    @Test
    fun chatOverflowOpensUsageScreenAndQuickSettingsDoesNotOwnUsage() {
        val chat = source(
            "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"
        )
        val quickSettings = resource("layout/fragment_quick_settings.xml")

        assertTrue(chat.contains("R.string.usage_cost_title"))
        assertTrue(chat.contains("openUsageAndCost()"))
        assertTrue(chat.contains("TokenPricingDetailsActivity.EXTRA_USAGE_SUMMARY"))
        assertTrue(chat.contains("TokenPricingDetailsActivity.EXTRA_USAGE_SECTIONS"))
        assertTrue(chat.contains("completeTerminalUsageRecord"))
        assertTrue(chat.contains("!snapshot.counts.hasAnyValue() && !snapshot.providerCost.hasAnyValue()"))
        val activity = source(
            "src/main/java/org/teslasoft/assistant/ui/activities/TokenPricingDetailsActivity.kt"
        )
        assertTrue(activity.contains("groups.sortedBy { it.provider.lowercase(Locale.ROOT) }"))
        assertTrue(activity.contains(".sortedBy { it.model.lowercase(Locale.ROOT) }"))
        assertFalse(quickSettings.contains("@+id/usage_cost"))
    }

    private fun resource(relative: String): String {
        val candidates = listOf(File("src/main/res/$relative"), File("app/src/main/res/$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }
}
