package org.teslasoft.assistant.ui.activities

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.teslasoft.assistant.R
import org.teslasoft.assistant.usage.CostSource
import org.teslasoft.assistant.usage.MeteredUsageAccounting
import org.teslasoft.assistant.usage.TokenCountSource
import org.teslasoft.assistant.usage.TokenCounts
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.TurnUsageRecord
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageLog
import org.teslasoft.assistant.usage.UsageMeter
import org.teslasoft.assistant.usage.UsageMeterComponent
import org.teslasoft.assistant.usage.UsageMeterUnit
import org.teslasoft.assistant.usage.UsageQuantitySource
import org.teslasoft.assistant.usage.UsageValueFormatter

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UsageCostTtsRenderingTest {
    private fun meter(component: UsageMeterComponent, unit: UsageMeterUnit, quantity: Double?, amount: Double?,
        basis: Double?) = UsageMeter(component, unit, quantity, quantity?.let { UsageQuantitySource.LOCAL_EXACT },
        amount, basis, amount?.let { "USD" })

    private val chat = TurnUsageRecord(model = "gpt-chat", provider = "Chat Provider", inputTokens = 100,
        outputTokens = 50, totalTokens = 150, cachedInputTokens = 40, cacheWriteInputTokens = 0,
        source = TokenCountSource.PROVIDER_REPORTED.storedValue, inputPricePerToken = 0.000001,
        outputPricePerToken = 0.000002, cachedInputPricePerToken = 0.0000005, inputCost = 0.00008,
        outputCost = 0.0001, uncachedInputCost = 0.00006, cachedInputCost = 0.00002, totalCost = 0.00018,
        costSource = CostSource.FROZEN_PRICING.storedValue)

    private fun open(): TokenPricingDetailsActivity {
        val tts = listOf(
            MeteredUsageAccounting.record("tts-1", "Characters Co", null, listOf(meter(UsageMeterComponent.CHARACTERS,
                UsageMeterUnit.CHARACTER, 2_847.0, 15.0, 1_000_000.0)), null),
            MeteredUsageAccounting.record("mini-tts", "Tokens Co", null, listOf(
                meter(UsageMeterComponent.TEXT_INPUT, UsageMeterUnit.TOKEN, 48.0, 0.6, 1_000_000.0),
                meter(UsageMeterComponent.AUDIO_OUTPUT, UsageMeterUnit.TOKEN, 914.0, 12.0, 1_000_000.0)), null),
            MeteredUsageAccounting.record("duration-tts", "Seconds Co", null, listOf(meter(UsageMeterComponent.AUDIO_OUTPUT,
                UsageMeterUnit.SECOND, 12.4, 0.01, 1.0)), null),
            MeteredUsageAccounting.record("bytes-tts", "Bytes Co", null, listOf(meter(UsageMeterComponent.UTF8_BYTES,
                UsageMeterUnit.BYTE, 3_012.0, 0.000001, 1.0)), null))
        val log = UsageLog.EMPTY.append(listOf(UsageLog.entry(UsageCategory.CHAT, "m", chat, 1L)) +
            tts.map { UsageLog.entry(UsageCategory.TTS, null, it, 2L) })
        val noEstimate: (Int) -> TokenCounts = { TokenCounts(null, null, null) }
        val intent = Intent(RuntimeEnvironment.getApplication(), TokenPricingDetailsActivity::class.java)
            .putExtra(TokenPricingDetailsActivity.EXTRA_USAGE_SUMMARY,
                TokenUsageAccounting.encodeSummary(log.summarize(emptyList(), noEstimate)))
            .putExtra(TokenPricingDetailsActivity.EXTRA_USAGE_SECTIONS,
                UsageLog.encodeSections(log.sections(emptyList(), noEstimate)))
        val controller = Robolectric.buildActivity(TokenPricingDetailsActivity::class.java, intent)
        controller.get().setTheme(R.style.UI_Material)
        return controller.setup().get()
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> if (view.isShown) listOf(view.text.toString()) else emptyList()
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** The visible text of the provider block whose name is [provider]. */
    private fun block(activity: TokenPricingDetailsActivity, provider: String): List<String> {
        val name = findText(activity.findViewById(R.id.pricing_sections), provider)!!
        var parent = name.parent as View
        while (parent.findViewById<View>(R.id.pricing_footer) == null) parent = parent.parent as View
        return texts(parent)
    }

    private fun findText(view: View, text: String): TextView? = when (view) {
        is TextView -> view.takeIf { it.text.toString() == text }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findText(view.getChildAt(it), text) }
        else -> null
    }

    @Test fun ttsShowsBilledUnitsWithoutCacheRowsAndChatIsUnchanged() {
        val activity = open()
        val characters = block(activity, "Characters Co")
        assertTrue(characters.containsAll(listOf("Characters", "2,847",
            UsageValueFormatter.cost(2_847 * 15.0 / 1e6, false), "\$15.00",
            "Price per 1M Characters")))
        for (absent in listOf("Cached", "Cache Hit Rate", "Price per 1M Tokens", "Tokens", "Input", "Output"))
            assertFalse(absent, characters.contains(absent))

        val tokens = block(activity, "Tokens Co")
        assertTrue(tokens.containsAll(listOf("Text Input", "48", "Audio Output", "914", "\$0.60", "\$12.00",
            "Price per 1M Tokens")))
        assertFalse(tokens.contains("Cached"))

        val duration = block(activity, "Seconds Co")
        assertTrue(duration.containsAll(listOf("Audio Output", "12.4 sec", "\$0.12400", "\$0.60",
            "Price per Minute of Audio")))

        val bytes = block(activity, "Bytes Co")
        assertTrue(bytes.containsAll(listOf("UTF-8 Bytes", "3,012", "\$1.00", "Price per 1M UTF-8 Bytes")))

        val chatBlock = block(activity, "Chat Provider")
        assertTrue(chatBlock.containsAll(listOf("Tokens", "Input", "60", "Output", "50", "Cached", "40",
            "Cache Hit Rate", "40.0%", "Price per 1M Tokens")))
    }

    @Test fun conversationTotalIncludesKnownTtsCost() {
        val activity = open()
        val expected = 0.00018 + 2_847 * 15.0 / 1e6 + (48 * 0.6 + 914 * 12.0) / 1e6 + 12.4 * 0.01 + 3_012 * 0.000001
        assertEquals("\$" + String.format(java.util.Locale.US, "%.5f", expected),
            activity.findViewById<TextView>(R.id.conversation_total_cost).text.toString())
    }
}
