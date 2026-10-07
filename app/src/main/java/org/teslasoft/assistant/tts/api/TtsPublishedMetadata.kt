package org.teslasoft.assistant.tts.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Request
import org.teslasoft.assistant.tts.voices.ApiCatalogVoice
import java.math.BigDecimal
import java.util.Locale

/** Values in this file are document addresses and schema keys, never provider catalogs or rates. */
internal data class OpenAiPublishedSpeech(
    val modelIds: Set<String>,
    val aliases: Map<String, String>,
    val voices: List<ApiCatalogVoice>,
    val restrictedVoices: Map<String, List<ApiCatalogVoice>>,
    val sseModels: Set<String>,
    val prices: Map<String, TtsPrice>
) {
    fun voicesFor(id: String) = restrictedVoices[aliases[id] ?: id] ?: voices
    fun priceFor(id: String) = prices[aliases[id] ?: id]
}

internal data class TtsSynthesisMetadata(val wire: TtsWireFormat, val price: TtsPrice? = null,
    val providers: TtsProviderCatalog? = null)

/** Reads JSON embedded in the providers' public pages as data. Never evaluates downloaded code. */
internal object PublishedPageJson {
    private const val CHUNK_START = "self.__next_f.push([1,"

    private fun chunks(body: String): List<String> {
        val result = mutableListOf<String>()
        var cursor = 0
        while (cursor < body.length) {
            val marker = body.indexOf(CHUNK_START, cursor).takeIf { it >= 0 } ?: break
            var start = marker + CHUNK_START.length
            while (start < body.length && body[start].isWhitespace()) start++
            cursor = start + 1
            if (start >= body.length || body[start] != '"') continue
            // A linear scanner handles large JSON strings without regex stack overflow.
            while (cursor < body.length) {
                when (body[cursor]) {
                    '\\' -> cursor += 2
                    '"' -> break
                    else -> cursor++
                }
            }
            if (cursor >= body.length) break
            runCatching { JsonParser.parseString(body.substring(start, cursor + 1)).asString }
                .getOrNull()?.let(result::add)
            cursor++
        }
        return result
    }

    fun objects(body: String): List<JsonObject> {
        val stream = chunks(body).joinToString("")
        val result = mutableListOf<JsonObject>()
        fun visit(value: JsonElement, depth: Int) {
            require(depth <= 80) { "Published metadata is too deeply nested" }
            when {
                value.isJsonObject -> {
                    result += value.asJsonObject
                    value.asJsonObject.entrySet().forEach { visit(it.value, depth + 1) }
                }
                value.isJsonArray -> value.asJsonArray.forEach { visit(it, depth + 1) }
            }
        }
        stream.lineSequence().forEach { line ->
            // React's row prefixes and string references are not JSON objects or executable code.
            val data = line.substringAfter(':', "").trim()
            if (data.startsWith("[") || data.startsWith("{")) {
                runCatching { JsonParser.parseString(data) }.getOrNull()?.let { visit(it, 0) }
            }
        }
        return result
    }
}

internal object OpenAiPublishedParser {
    private fun quoted(body: String) = Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toSet()
    private fun backticks(body: String) = Regex("`([^`]+)`").findAll(body).map { it.groupValues[1] }.toList()
    private fun parameter(body: String, name: String): String = body.substringAfter("- `$name:", "")
        .substringBefore("\n- `").substringBefore("\n### Example")
    private fun section(body: String, heading: String): String = body.substringAfter("## $heading\n", "")
        .substringBefore("\n## ")

    fun modelIds(reference: String): Set<String> {
        val declaration = parameter(reference, "model").lineSequence()
            .firstOrNull { it.contains("SpeechModel =") }
            ?: throw IllegalArgumentException("Published speech model enum is missing")
        return quoted(declaration).also { require(it.isNotEmpty()) { "Published speech model enum is empty" } }
    }

    fun catalog(reference: String, guide: String, modelDocs: Map<String, String>): OpenAiPublishedSpeech {
        val ids = modelIds(reference)
        val voiceSection = parameter(reference, "voice")
        val voices = Regex("(?m)^    - `\"([^\"]+)\"`").findAll(voiceSection)
            .map { ApiCatalogVoice(it.groupValues[1], it.groupValues[1]) }.toList()
        require(voices.isNotEmpty()) { "Published speech voice enum is missing" }
        val aliases = mutableMapOf<String, String>()
        val prices = mutableMapOf<String, TtsPrice>()
        modelDocs.forEach { (id, body) ->
            // The document must identify itself and confirm the speech endpoint.
            val documentedId = Regex("Model ID: `([^`]+)`").find(body)?.groupValues?.get(1)
            if (documentedId != id || !body.lineSequence().any {
                    it.contains("`v1/audio/speech`") && it.substringAfterLast('|', "").isBlank() &&
                        it.split('|').map(String::trim).contains("Supported") }) return@forEach
            aliases[id] = id
            backticks(section(body, "Snapshots")).forEach { aliases[it] = id }
            prices[id] = modelPrice(body)
        }
        val restricted = mutableMapOf<String, List<ApiCatalogVoice>>()
        guide.lineSequence().filter { it.contains("models support a smaller set:") }.forEach { line ->
            val split = line.split("models support a smaller set:", limit = 2)
            val allowed = backticks(split[1]).map { ApiCatalogVoice(it, it) }
            backticks(split[0]).forEach { restricted[it] = allowed }
        }
        val streaming = parameter(reference, "stream_format")
        val excludes = streaming.lineSequence().filter { it.contains("not supported for") }
            .flatMap { backticks(it.substringAfter("not supported for")) }.toSet()
        val sse = if (quoted(streaming).contains("sse")) (ids + aliases.keys).filter { id ->
            (aliases[id] ?: id) !in excludes
        }.toSet() else emptySet()
        return OpenAiPublishedSpeech(ids + aliases.keys, aliases, voices, restricted, sse, prices)
    }

    fun modelPrice(body: String): TtsPrice {
        val charges = mutableListOf<TtsCharge>()
        var modality = ""
        var readable = true
        section(body, "Pricing").lineSequence().forEach { line ->
            if (line.startsWith("### ")) modality = line.removePrefix("### ").lowercase(Locale.ROOT)
            val cells = line.trim().takeIf { it.startsWith('|') }?.trim('|')?.split('|')?.map(String::trim)
                ?: return@forEach
            if (cells.size != 3) return@forEach
            if (!cells[1].startsWith('$')) return@forEach
            val amount = cells[1].removePrefix("$").toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
                ?: run { readable = false; return@forEach }
            val basis = PublishedPriceUnits.parse(cells[2]) ?: run { readable = false; return@forEach }
            val component = when {
                basis.second == "character" -> "characters"
                cells[0].equals("Input", true) && modality == "text tokens" -> "input"
                cells[0].equals("Output", true) && modality == "audio tokens" -> "output"
                else -> { readable = false; return@forEach }
            }
            charges += TtsCharge(component, amount, "USD", basis.second, basis.first)
        }
        val components = charges.map { it.component }
        val completeBasis = components == listOf("characters") || components.toSet() == setOf("input", "output")
        return TtsPrice(charges, readable && completeBasis && components.distinct().size == charges.size)
    }
}

internal object PublishedPriceUnits {
    /** Parses a published basis (1K characters, 1M tokens, /minute); numerical scales are unit conversions. */
    fun parse(text: String): Pair<BigDecimal, String>? {
        val match = Regex("(?i)(?:(\\d+(?:\\.\\d+)?)\\s*([km])?\\s*)?(characters?|tokens?|seconds?|minutes?|bytes?)")
            .find(text) ?: return null
        val number = match.groupValues[1].takeIf(String::isNotEmpty)?.toBigDecimal() ?: BigDecimal.ONE
        val scale = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "k" -> BigDecimal("1000"); "m" -> BigDecimal("1000000"); else -> BigDecimal.ONE
        }
        return number.multiply(scale) to match.groupValues[3].lowercase(Locale.ROOT).removeSuffix("s")
    }
}

/** Public documents have no authentication headers; account requests retain the existing same-origin guard. */
internal class TtsPublishedMetadataClient(private val http: TtsHttpExecutor) {
    companion object {
        const val OPENAI_REFERENCE = "https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create.md"
        const val OPENAI_GUIDE = "https://developers.openai.com/api/docs/guides/text-to-speech.md"
        const val OPENAI_MODELS = "https://developers.openai.com/api/docs/models/"
        const val ELEVENLABS_PRICING = "https://elevenlabs.io/pricing/api"
    }

    private fun publicGet(source: ResolvedTtsSource, url: String, token: TtsRequestToken, op: TtsOperation): String {
        token.check()
        val request = Request.Builder().url(url).header("Accept", "text/markdown, text/html, application/json").get().build()
        val response = http.execute(source.endpoint, source.target, op, request, token)
        token.check()
        response.requireSuccess(source, op)
        return response.bytes.toString(Charsets.UTF_8)
    }

    fun openAi(source: ResolvedTtsSource, token: TtsRequestToken, op: TtsOperation): OpenAiPublishedSpeech {
        val reference = publicGet(source, OPENAI_REFERENCE, token, op)
        val ids = OpenAiPublishedParser.modelIds(reference)
        val documents = ids.associateWith { id ->
            // IDs are path segments from a fetched enum, never arbitrary URLs.
            val encoded = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
            runCatching { publicGet(source, "$OPENAI_MODELS$encoded.md", token, op) }.getOrElse { token.check(); "" }
        }
        return OpenAiPublishedParser.catalog(reference, publicGet(source, OPENAI_GUIDE, token, op), documents)
    }

    fun synthesis(source: ResolvedTtsSource, token: TtsRequestToken, op: TtsOperation): TtsSynthesisMetadata {
        val wire = TtsServices.wireFormat(source.endpoint, source.target.modelId)
        return when (source.endpoint.kind) {
            TtsEndpointKind.OPENAI -> {
                val data = openAi(source, token, op)
                TtsSynthesisMetadata(if (source.target.modelId in data.sseModels) TtsWireFormat.OPENAI_SSE else wire,
                    data.priceFor(source.target.modelId))
            }
            TtsEndpointKind.OPENROUTER -> {
                val catalog = TtsDiscoveryClient(http).providers(source, token)
                val published = openRouterPrices(source, token, op)
                val providers = catalog.copy(providers = catalog.providers.map { provider ->
                    provider.copy(price = published[provider.id] ?: provider.price)
                })
                TtsSynthesisMetadata(wire, providers = providers)
            }
            TtsEndpointKind.ELEVENLABS -> TtsSynthesisMetadata(wire, elevenLabsPrice(source, token, op))
            TtsEndpointKind.GENERIC -> TtsSynthesisMetadata(wire)
        }
    }

    fun openRouterPrices(source: ResolvedTtsSource, token: TtsRequestToken, op: TtsOperation): Map<String, TtsPrice> {
        val slug = source.target.modelId.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return OpenRouterPublishedPrices.parse(publicGet(source, "https://openrouter.ai/$slug", token, op),
            source.target.modelId)
    }

    private fun accountGet(source: ResolvedTtsSource, path: String, token: TtsRequestToken, op: TtsOperation): String {
        val request = requestBuilder(source.endpoint, source.target, op,
            source.endpoint.baseUrl.trimEnd('/') + "/" + path).header("Accept", "application/json").get().build()
        token.check()
        val response = http.execute(source.endpoint, source.target, op, request, token)
        token.check(); response.requireSuccess(source, op)
        return response.bytes.toString(Charsets.UTF_8)
    }

    private fun elevenLabsPrice(source: ResolvedTtsSource, token: TtsRequestToken, op: TtsOperation): TtsPrice? {
        val subscription = objectBody(accountGet(source, "user/subscription", token, op))
        val models = JsonParser.parseString(accountGet(source, "models", token, op)).asJsonArray
        val model = models.mapNotNull { it.objectOrNull() }.singleOrNull { it.text("model_id") == source.target.modelId }
            ?: return null
        val voiceId = java.net.URLEncoder.encode(source.target.voiceId.orEmpty(), "UTF-8").replace("+", "%20")
        val voice = objectBody(accountGet(source, "voices/$voiceId", token, op))
        // A shared voice with a custom price needs its published account rate; do not silently apply a base rate.
        val sharing = voice.get("sharing").objectOrNull()
        if (sharing?.get("rate")?.let { !it.isJsonNull } == true ||
            sharing?.get("fiat_rate")?.let { !it.isJsonNull } == true) return null
        return ElevenLabsPublishedPrices.parse(publicGet(source, ELEVENLABS_PRICING, token, op), model,
            subscription.text("tier") ?: return null)
    }
}

internal object OpenRouterPublishedPrices {
    fun parse(body: String, modelId: String): Map<String, TtsPrice> = PublishedPageJson.objects(body)
        .filter { it.text("model_variant_slug") == modelId && it.text("provider_slug") != null }
        .mapNotNull { endpoint ->
            val pricing = endpoint.get("pricing_json").objectOrNull() ?: return@mapNotNull null
            val charges = pricing.entrySet().map { (key, value) ->
                val suffix = key.substringAfter(':', "")
                val unit = when (suffix) {
                    "characters" -> "character"; "input_tokens", "output_tokens" -> "token"
                    "output_seconds", "input_seconds" -> "second"; "bytes" -> "byte"; else -> null
                }
                val component = if (suffix.startsWith("output_")) "output" else if (suffix.startsWith("input_")) "input" else suffix
                TtsCharge(component, value.takeIf { it.isJsonPrimitive }?.asString?.toBigDecimalOrNull()
                    ?.takeIf { it.signum() >= 0 }, "USD", unit)
            }
            endpoint.text("provider_slug")!! to TtsPrice(charges,
                charges.isNotEmpty() && charges.all { it.amount != null && it.unit != null })
        }.groupBy({ it.first }, { it.second }).mapNotNull { (id, prices) ->
            prices.distinct().singleOrNull()?.let { id to it }
        }.toMap()
}

internal object ElevenLabsPublishedPrices {
    fun parse(body: String, model: JsonObject, tier: String): TtsPrice? {
        val objects = PublishedPageJson.objects(body)
        val cards = objects.flatMap { it.getAsJsonArrayOrNull("models")?.mapNotNull(JsonElement::objectOrNull).orEmpty() }
            .filter { it.text("category") == "Text to Speech" }.distinctBy { it.text("id") }
        val id = model.text("model_id") ?: return null
        val matching = cards.filter { card ->
            val cardId = card.text("id") ?: return@filter false
            if (id == cardId || id.endsWith("_$cardId")) true
            else {
                // A slash-separated family is explicitly grouped by the publisher (e.g. Flash / Turbo).
                val label = card.text("name").orEmpty()
                label.contains(" / ") && label.split(" / ").any { name ->
                    model.text("name").orEmpty().split(Regex("[^A-Za-z0-9]+"))
                        .any { it.equals(name, true) }
                }
            }
        }.singleOrNull() ?: return null
        val tierId = objects.flatMap { it.getAsJsonArrayOrNull("tiers")?.mapNotNull(JsonElement::objectOrNull).orEmpty() }
            .distinctBy { it.text("id") }.singleOrNull {
                it.text("id") == tier || it.text("title").orEmpty().split(" / ").any { title -> title.equals(tier, true) }
            }?.text("id") ?: return null
        val rows = objects.filter { it.text("id") == "text_to_speech" }
            .flatMap { it.getAsJsonArrayOrNull("subSections")?.mapNotNull(JsonElement::objectOrNull).orEmpty() }
            .filter { it.text("label") == matching.text("name") }
            .flatMap { it.getAsJsonArrayOrNull("rows")?.mapNotNull(JsonElement::objectOrNull).orEmpty() }
        val charges = rows.mapNotNull { row ->
            val label = row.text("label") ?: return@mapNotNull null
            if (!label.startsWith("Price per ")) return@mapNotNull null
            val unit = PublishedPriceUnits.parse(label) ?: return@mapNotNull null
            val value = row.getAsJsonArrayOrNull("values")?.mapNotNull(JsonElement::objectOrNull)
                ?.singleOrNull { it.text("tierId") == tierId }?.get("value").objectOrNull() ?: return@mapNotNull null
            if (value.text("type") != "currency") return@mapNotNull null
            val amount = value.get("value")?.takeIf { it.isJsonPrimitive }?.asString?.toBigDecimalOrNull()
                ?.takeIf { it.signum() >= 0 } ?: return@mapNotNull null
            TtsCharge("characters", amount, "USD", unit.second, unit.first)
        }.distinct()
        return charges.singleOrNull()?.let { TtsPrice(listOf(it), true) }
    }
}
