package org.teslasoft.assistant.imagegen

/** Small, conservative readers for provider-published documents. An unrecognized shape yields no rate. */
internal fun imageHtmlText(html: String): String = html.replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&#39;", "'").replace("&amp;", "&")
    .replace("&quot;", "\"").trim().replace(Regex("\\s+"), " ")

object OpenAiImageReferenceParser {
    const val URL = "https://developers.openai.com/api/reference/resources/images/methods/generate/index.md"

    fun enrich(model: ImageModelMetadata, reference: String): ImageModelMetadata {
        val supported = reference.substringAfter("Supported models include ", "").substringBefore("\n\n")
        val ids = Regex("`([^`]+)`").findAll(supported).map { it.groupValues[1] }.toSet()
        if (model.resolvedIds.none { it in ids }) return model
        fun section(key: String): String = reference.substringAfter("- `$key:", "").substringBefore("\n- `")
        fun choices(key: String) = Regex("(?m)^  - `\"([^\"]+)\"`").findAll(section(key)).map { it.groupValues[1] }.toList()
        val parameters = model.parameters.toMutableList()
        listOf("background", "output_format").forEach { key ->
            val values = choices(key)
            if (values.isNotEmpty()) parameters += ImageParameter(key, ImageParameterType.ENUM, values)
        }
        val compression = Regex("\\(([0-9]+)-([0-9]+)%\\)").find(section("output_compression"))
        if (compression != null) parameters += ImageParameter("output_compression", ImageParameterType.INTEGER,
            minimum = compression.groupValues[1].toDouble(), maximum = compression.groupValues[2].toDouble())
        return model.copy(parameters = parameters, sourceUrl = model.sourceUrl + " | " + URL)
    }
}

/** Cache exclusions are model-specific published billing policy, not a fixed provider capability. */
object OpenAiImageCachePolicyParser {
    const val URL = "https://developers.openai.com/api/docs/guides/image-generation.md"

    fun enrich(model: ImageModelMetadata, guide: String): ImageModelMetadata {
        val current = model.copy(directCachedInputExcluded = false)
        val section = guide.substringAfter("### Cached input pricing", "").substringBefore("\n### ")
        val names = Regex("For (.*?), cached input pricing applies only to .*?Responses API\\.\\s*It (?:doesn't|does not|doesn’t) apply to direct Images API requests", RegexOption.DOT_MATCHES_ALL)
            .find(section)?.groupValues?.get(1)?.split(Regex(",\\s*|\\s+and\\s+")) ?: return current
        fun normalized(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")
        val exact = model.resolvedIds.any { id -> names.any { normalized(it) == normalized(id) } }
        // The canonical document publishes the family/variant name and its exact
        // IDs. Require an ID in the guide too; an ID prefix alone is not evidence.
        fun label(value: String) = value.lowercase().replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        val published = model.publishedName?.let(::label)
        val guideIds = Regex("`([^`]+)`").findAll(guide).map { it.groupValues[1] }.toSet()
        val family = published != null && model.resolvedIds.any { it in guideIds } && names.any { name ->
            val familyName = label(name)
            published == familyName || published.startsWith("$familyName ")
        }
        if (!exact && !family) return current
        return current.copy(directCachedInputExcluded = true,
            sourceUrl = listOfNotNull(model.sourceUrl, URL).joinToString(" | "))
    }
}

object OpenRouterImageConfigurationParser {
    const val URL = "https://openrouter.ai/docs/guides/overview/multimodal/image-generation.md"

    fun compression(guide: String): ImageParameter? {
        val text = imageHtmlText(guide).replace("`", "")
        val range = Regex("\\boutput_compression\\s*[—–-]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*[-–]\\s*([0-9]+(?:\\.[0-9]+)?)")
            .find(text) ?: return null
        val minimum = range.groupValues[1].toDoubleOrNull() ?: return null
        val maximum = range.groupValues[2].toDoubleOrNull() ?: return null
        if (!minimum.isFinite() || !maximum.isFinite() || minimum > maximum) return null
        return ImageParameter("output_compression", ImageParameterType.INTEGER, minimum = minimum, maximum = maximum)
    }
}

object GeminiImagePricingParser {
    const val URL = "https://ai.google.dev/gemini-api/docs/pricing"

    fun enrich(model: ImageModelMetadata, pricing: String): ImageModelMetadata {
        val heading = Regex("<h2[^>]*id=\"([^\"]+)\"[^>]*>").findAll(pricing)
            .firstOrNull { it.groupValues[1] in model.resolvedIds } ?: return model.copy(tariffsComplete = false)
        val section = pricing.substring(heading.range.last + 1).substringBefore("<h2")
        // The direct adapter sends a standard synchronous request, without caching or grounding tools.
        val table = Regex("<table[^>]*>.*?</table>", RegexOption.DOT_MATCHES_ALL).find(section)
            ?: return model.copy(tariffsComplete = false)
        val beforeTable = imageHtmlText(section.substring(0, table.range.first))
        if (!beforeTable.endsWith("Standard")) return model.copy(tariffsComplete = false)
        val rows = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(table.value).map { row ->
            Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1])
                .map { imageHtmlText(it.groupValues[1]) }.toList()
        }.toList()
        val header = rows.firstOrNull() ?: return model.copy(tariffsComplete = false)
        val paid = header.indexOfFirst { it.startsWith("Paid Tier") }
        val free = header.indexOfFirst { it == "Free Tier" }
        val basis = header.getOrNull(paid)?.let {
            Regex("per ([0-9]+(?:\\.[0-9]+)?)([MK]?) tokens in USD").find(it)
        } ?: return model.copy(tariffsComplete = false)
        val quantity = basis.groupValues[1].toDouble() * when (basis.groupValues[2]) { "M" -> 1e6; "K" -> 1e3; else -> 1.0 }
        val tariffs = mutableListOf<ImageTariff>()
        var complete = quantity.isFinite() && quantity > 0
        listOf("Input price" to "input", "Output price" to "output").forEach { (label, suffix) ->
            val row = rows.singleOrNull { it.firstOrNull() == label }
            // Do not choose a paid price for a model which might have been served free.
            if (row?.getOrNull(free) != "Not available") { complete = false; return@forEach }
            val value = row.getOrNull(paid).orEmpty().substringBefore("Equivalent").substringBefore("equivalent")
            val prices = Regex("\\$([0-9]+(?:\\.[0-9]+)?)\\s*\\(([^)]+)\\)").findAll(value).toList()
            if (prices.isEmpty()) complete = false
            prices.forEach { price ->
                val amount = price.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                if (amount == null) { complete = false; return@forEach }
                val modalities = price.groupValues[2].lowercase().split(Regex("[^a-z]+"))
                if ("text" in modalities) tariffs += ImageTariff("text_$suffix", "token", amount, quantity, "USD")
                if ("image" in modalities || "images" in modalities) tariffs += ImageTariff("image_$suffix", "token", amount, quantity, "USD")
            }
        }
        if (tariffs.none { it.billable == "text_input" } || tariffs.none { it.billable == "image_output" }) complete = false
        val outputDescription = rows.firstOrNull { it.firstOrNull() == "Output price" }?.getOrNull(paid).orEmpty()
        val sizes = model.nativeSizes.filter { size ->
            Regex("(?<![\\w.])" + Regex.escape(size) + "(?![\\w.])").containsMatchIn(outputDescription)
        }
        // Exact-model pricing also names supported output resolution tiers. This
        // resolves a missing guide table without assigning capabilities from a name.
        val parameters = if (model.parameters.none { it.key == "resolution" } && sizes.size > 1)
            listOf(ImageParameter("resolution", ImageParameterType.ENUM, sizes)) + model.parameters else model.parameters
        return model.copy(parameters = parameters, tariffs = tariffs, tariffsComplete = complete, sourceUrl = model.sourceUrl + " | " + URL)
    }
}

/** Retry-After is protocol evidence; HTTP dates and seconds both retain their full delay. */
object ImageBillingRetry {
    fun delayMs(header: String?, now: Long = System.currentTimeMillis()): Long? {
        if (header == null) return null
        header.trim().toLongOrNull()?.takeIf { it >= 0 }?.let {
            return if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000
        }
        return runCatching {
            (java.time.ZonedDateTime.parse(header.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - now).coerceAtLeast(0)
        }.getOrNull()
    }
}
