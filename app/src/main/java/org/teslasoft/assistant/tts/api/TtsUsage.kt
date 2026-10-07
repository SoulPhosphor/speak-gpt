package org.teslasoft.assistant.tts.api

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.usage.MeteredUsageAccounting
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.TurnUsageRecord
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageLog
import org.teslasoft.assistant.usage.UsageLogStore
import org.teslasoft.assistant.usage.UsageMeter
import org.teslasoft.assistant.usage.UsageMeterComponent
import org.teslasoft.assistant.usage.UsageMeterUnit
import org.teslasoft.assistant.usage.UsageQuantitySource
import java.math.BigDecimal
import java.util.Locale

/**
 * One synthesis the service returned valid audio for, and so charged for. Handed to the
 * caller that owns the usage record; it is kept in memory only. [source] carries the
 * endpoint snapshot the request used, so a later cost lookup reaches the same account.
 */
class TtsBilledSynthesis(
    val source: ResolvedTtsSource,
    /** The exact text sent to the service. */
    val input: String,
    val operation: TtsOperation,
    val audio: TtsAudio
)

/**
 * How spoken text is measured for billing. Characters are Unicode code points: a
 * character outside the Basic Multilingual Plane (most emoji) counts once, not as the two
 * UTF-16 units a Kotlin String length reports. Bytes are the UTF-8 encoding actually sent.
 */
object TtsTextMeasure {
    fun characters(text: String): Long = text.codePointCount(0, text.length).toLong()
    fun utf8Bytes(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()
}

/**
 * What official OpenAI speech requests billed, as quantities only. No price is ever written
 * into the app: OpenAI reports no charge for speech and publishes no price list the app can
 * download, so these requests show their quantities and a "Not Reported" cost.
 */
object OpenAiSpeechMetering {
    private val characterBilled = setOf("tts-1", "tts-1-1106", "tts-1-hd", "tts-1-hd-1106")

    fun meters(modelId: String, input: String, tokens: TtsReportedTokens?): List<UsageMeter> {
        if (modelId in characterBilled) return listOf(UsageMeter(UsageMeterComponent.CHARACTERS,
            UsageMeterUnit.CHARACTER, TtsTextMeasure.characters(input).toDouble(), UsageQuantitySource.LOCAL_EXACT))
        if (modelId in TtsServices.OPENAI_SSE_MODELS) return listOf(
            UsageMeter(UsageMeterComponent.TEXT_INPUT, UsageMeterUnit.TOKEN, tokens?.inputTokens?.toDouble(),
                tokens?.inputTokens?.let { UsageQuantitySource.PROVIDER_REPORTED }),
            UsageMeter(UsageMeterComponent.AUDIO_OUTPUT, UsageMeterUnit.TOKEN, tokens?.outputTokens?.toDouble(),
                tokens?.outputTokens?.let { UsageQuantitySource.PROVIDER_REPORTED }))
        return emptyList()
    }
}

/** OpenRouter's generation record for one request. */
data class OpenRouterGeneration(val totalCost: Double?, val providerName: String?, val model: String?)

object OpenRouterGenerationParser {
    /** Reads `data.total_cost` (US dollars), `data.provider_name` and `data.model`. */
    fun parse(body: String): OpenRouterGeneration? = try {
        val root = JsonParser.parseString(body).objectOrNull()
        val data = root?.get("data").objectOrNull() ?: root
        data?.let { o ->
            OpenRouterGeneration(
                totalCost = o.get("total_cost")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asDouble?.takeIf { it.isFinite() && it >= 0.0 },
                providerName = o.text("provider_name"),
                model = o.text("model"))
        }
    } catch (_: Exception) { null }
}

/**
 * Looks up OpenRouter's generation record for a speech request. OpenRouter documents the
 * generation endpoint and `X-Generation-Id` on speech, but not that speech generations are
 * listed there, so this is best effort: any failure returns what was learned, or null,
 * and never affects speech. A record can take a moment to appear, so a missing one is
 * asked for again after each delay in [delaysMs].
 */
class OpenRouterGenerationClient(
    private val http: TtsHttpExecutor = OkHttpTtsExecutor(),
    private val delaysMs: List<Long> = listOf(1_500L, 3_000L, 6_000L),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    fun lookup(source: ResolvedTtsSource, generationId: String, operation: TtsOperation): OpenRouterGeneration? {
        var partial: OpenRouterGeneration? = null
        for (delay in delaysMs) {
            sleep(delay)
            try {
                val url = (source.endpoint.baseUrl.trim().trimEnd('/') + "/generation").toHttpUrlOrNull()
                    ?.newBuilder()?.addQueryParameter("id", generationId)?.build()?.toString() ?: return partial
                val request = requestBuilder(source.endpoint, source.target, operation, url)
                    .header("Accept", "application/json").get().build()
                val response = http.execute(source.endpoint, source.target, operation, request, TtsRequestGate().begin())
                if (response.status in 200..299) {
                    val parsed = OpenRouterGenerationParser.parse(response.bytes.toString(Charsets.UTF_8))
                    if (parsed?.totalCost != null) return parsed
                    if (parsed != null) partial = parsed
                }
            } catch (_: Exception) { /* Unavailable cost details never become a speech failure. */ }
        }
        return partial
    }
}

object TtsUsageAccounting {
    /**
     * The usage record for one paid synthesis, from the most authoritative source available:
     * the service's reported charge, then reported quantities × prices fetched at request
     * time, then exact local quantities × prices fetched at request time. No price is ever
     * written into the app; anything else stays unknown.
     */
    fun record(billed: TtsBilledSynthesis, generation: OpenRouterGeneration? = null,
        openRouterPrice: TtsPrice? = null): TurnUsageRecord {
        val endpoint = billed.source.endpoint
        val modelId = billed.source.target.modelId
        val meters: List<UsageMeter>
        var provider = endpoint.label
        var model = modelId
        var reportedTotal: Double? = null
        when (endpoint.kind) {
            TtsEndpointKind.OPENROUTER -> {
                // The serving provider is only what OpenRouter reports; routing may fall back.
                provider = generation?.providerName ?: TokenUsageAccounting.PROVIDER_NOT_REPORTED
                model = generation?.model ?: modelId
                reportedTotal = generation?.totalCost
                meters = openRouterPrice?.let { openRouterMeters(it, billed.input, billed.audio.bytes) }.orEmpty()
            }
            TtsEndpointKind.OPENAI -> meters = OpenAiSpeechMetering.meters(modelId, billed.input,
                billed.audio.metering.tokens)
            // ElevenLabs bills characters; its character-cost header is the reported count.
            // No ElevenLabs US-dollar rate is applied, so the cost is not reported.
            TtsEndpointKind.ELEVENLABS -> meters = listOf(UsageMeter(UsageMeterComponent.CHARACTERS,
                UsageMeterUnit.CHARACTER, billed.audio.metering.characterCost?.toDouble(),
                billed.audio.metering.characterCost?.let { UsageQuantitySource.PROVIDER_REPORTED }))
            TtsEndpointKind.GENERIC -> meters = emptyList()
        }
        return MeteredUsageAccounting.record(model, provider, endpoint.baseUrl, meters, reportedTotal)
    }

    /**
     * Meters for an OpenRouter price, only when every paid charge states a unit this app
     * can measure exactly. Characters and UTF-8 bytes are counted from the text sent;
     * seconds from the audio returned; tokens are not reported by speech, so they stay
     * unknown. Prices are US dollars by OpenRouter's documented convention. Null when the
     * price cannot be applied without guessing.
     */
    fun openRouterMeters(price: TtsPrice, input: String, audio: ByteArray): List<UsageMeter>? {
        if (!price.complete || price.charges.isEmpty()) return null
        val meters = mutableListOf<UsageMeter>()
        for (charge in price.charges) {
            val amount = charge.amount ?: return null
            val basis = charge.quantity?.takeIf { it.signum() > 0 } ?: return null
            val currency = (charge.currency ?: "USD").uppercase(Locale.ROOT)
            val unit = charge.unit?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')?.replace(' ', '_')
                ?: return null
            val component = charge.component.lowercase(Locale.ROOT)
            val meter = when (unit) {
                "character", "characters", "char", "chars" -> UsageMeter(UsageMeterComponent.CHARACTERS,
                    UsageMeterUnit.CHARACTER, TtsTextMeasure.characters(input).toDouble(), UsageQuantitySource.LOCAL_EXACT)
                "byte", "bytes", "utf8_byte", "utf8_bytes" -> UsageMeter(UsageMeterComponent.UTF8_BYTES,
                    UsageMeterUnit.BYTE, TtsTextMeasure.utf8Bytes(input).toDouble(), UsageQuantitySource.LOCAL_EXACT)
                "second", "seconds", "audio_second", "audio_seconds", "minute", "minutes", "audio_minute",
                "audio_minutes" -> Mp3Duration.seconds(audio).let { seconds ->
                    UsageMeter(UsageMeterComponent.AUDIO_OUTPUT, UsageMeterUnit.SECOND, seconds,
                        seconds?.let { UsageQuantitySource.LOCAL_EXACT })
                }
                "token", "tokens" -> when (component) {
                    "input", "prompt" -> UsageMeter(UsageMeterComponent.TEXT_INPUT, UsageMeterUnit.TOKEN, null, null)
                    "output", "completion" -> UsageMeter(UsageMeterComponent.AUDIO_OUTPUT, UsageMeterUnit.TOKEN, null, null)
                    else -> return null
                }
                else -> return null
            }
            val perMinute = unit.endsWith("minute") || unit.endsWith("minutes")
            val priceQuantity = basis.multiply(if (perMinute) BigDecimal(60) else BigDecimal.ONE).toDouble()
            // A zero-priced component with no measurable quantity costs nothing and shows nothing.
            if (amount.signum() == 0 && meter.quantity == null) continue
            if (meters.any { it.component == meter.component && it.unit == meter.unit }) return null
            meters += meter.copy(priceAmount = amount.toDouble(), priceQuantity = priceQuantity, currency = currency)
        }
        return meters
    }

    /**
     * The price of the provider that served the request: the one OpenRouter reported, or,
     * when it is unknown, a price every listed provider shares. Otherwise null; a
     * convenient provider's price is never presented as the one charged.
     */
    fun servingPrice(catalog: TtsProviderCatalog, servingProvider: String?): TtsPrice? {
        if (servingProvider != null) return catalog.providers.firstOrNull {
            it.name.equals(servingProvider, ignoreCase = true) || it.id.equals(servingProvider, ignoreCase = true)
        }?.price
        val prices = catalog.providers.map { it.price }
        val first = prices.firstOrNull() ?: return null
        return first.takeIf { prices.all { samePrice(it, first) } }
    }

    private fun samePrice(a: TtsPrice, b: TtsPrice): Boolean = a.complete == b.complete &&
        a.charges.size == b.charges.size && a.charges.zip(b.charges).all { (x, y) ->
            x.component == y.component && x.unit == y.unit && x.currency == y.currency &&
                x.amount?.compareTo(y.amount ?: return false) == 0 &&
                x.quantity?.compareTo(y.quantity ?: return false) == 0
        }

    /**
     * Completes the record off the playback path: OpenRouter's reported charge and serving
     * provider, and its published price for that provider. Lookups that fail are skipped.
     */
    fun resolve(billed: TtsBilledSynthesis,
        generationLookup: (ResolvedTtsSource, String) -> OpenRouterGeneration? = { source, id ->
            OpenRouterGenerationClient().lookup(source, id, billed.operation) },
        catalogLookup: (ResolvedTtsSource) -> TtsProviderCatalog? = { source ->
            TtsDiscoveryClient().providers(source, TtsRequestGate().begin()) }
    ): TurnUsageRecord {
        if (billed.source.endpoint.kind != TtsEndpointKind.OPENROUTER) return record(billed)
        val generation = billed.audio.metering.generationId?.let { id ->
            try { generationLookup(billed.source, id) } catch (_: Exception) { null }
        }
        val price = try { catalogLookup(billed.source) } catch (_: Exception) { null }
            ?.let { servingPrice(it, generation?.providerName) }
        return record(billed, generation, price)
    }
}

/**
 * Appends paid syntheses to a chat's usage log. Runs in the app's process rather than a
 * screen, so a screen closing straight after the audio arrives does not lose the charge.
 */
object TtsUsageRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** For a chat already open and known to exist. */
    fun record(preferences: Preferences, billed: TtsBilledSynthesis) {
        scope.launch {
            val record = TtsUsageAccounting.resolve(billed)
            UsageLogStore.append(preferences, listOf(UsageLog.entry(UsageCategory.TTS, null, record)))
        }
    }

    /**
     * For a screen that names a chat (the Voice Browser opened from a chat's settings).
     * Recorded only when that chat exists; a blank or unknown ID records nothing, and no
     * other conversation is ever charged instead.
     */
    fun recordForChat(context: Context, chatId: String, billed: TtsBilledSynthesis) {
        if (chatId.isBlank()) return
        val app = context.applicationContext
        scope.launch {
            val exists = ChatPreferences.getChatPreferences().getChatListResult(app, includeFirstMessage = false)
                .chats.any { ChatPreferences.storedChatId(it) == chatId }
            if (!exists) return@launch
            val record = TtsUsageAccounting.resolve(billed)
            UsageLogStore.append(Preferences.getPreferences(app, chatId),
                listOf(UsageLog.entry(UsageCategory.TTS, null, record)))
        }
    }
}
