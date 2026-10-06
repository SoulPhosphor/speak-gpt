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
        val centered = provider.lastIndexOf("android:gravity=\"center\"", caption)

        assertTrue(input in 0 until output)
        assertTrue(output < cached)
        assertTrue(cached < caption)
        assertTrue(centered in (cached + 1) until caption)
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
