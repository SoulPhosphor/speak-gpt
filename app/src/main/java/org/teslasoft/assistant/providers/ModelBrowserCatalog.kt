package org.teslasoft.assistant.providers

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.temporal.ChronoUnit

internal data class ModelBrowserEntry(
    val id: String,
    val name: String,
    val created: Instant?,
    val isFree: Boolean,
    val providers: List<ModelBrowserProvider>,
    val advertisesReasoning: Boolean
) {
    fun isNew(now: Instant): Boolean =
        created?.let { it >= newModelCutoff(now) && it <= now } == true

    fun matchesModel(query: String): Boolean =
        id.contains(query, ignoreCase = true) || name.contains(query, ignoreCase = true)
}

internal data class ModelBrowserProvider(val id: String, val name: String) {
    fun matches(query: String): Boolean {
        val normalized = query.filter(Char::isLetterOrDigit)
        if (normalized.isEmpty()) return false
        return id.filter(Char::isLetterOrDigit).contains(normalized, ignoreCase = true) ||
            name.filter(Char::isLetterOrDigit).contains(normalized, ignoreCase = true)
    }
}

internal fun newModelCutoff(now: Instant): Instant = now.minus(30, ChronoUnit.DAYS)

internal data class ModelBrowserPage(val entries: List<ModelBrowserEntry>, val nextPage: Int?)

internal object ModelBrowserCatalog {
    fun parse(body: String, requestedPage: Int = 1): ModelBrowserPage {
        val root = JsonParser.parseString(body).asJsonObject
        val entries = requireNotNull(root.getAsJsonArray("data")).mapNotNull { element ->
            val model = element.objectOrNull() ?: return@mapNotNull null
            val id = model.value("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (!isChatModel(model, id)) return@mapNotNull null
            ModelBrowserEntry(
                id,
                model.value("name").orEmpty(),
                parseDate(model.value("created")) ?: parseDate(model.value("created_at")),
                hasFreeTextPricing(model, id),
                parseProviders(model.get("providers")),
                model.objectValue("capabilities")?.value("reasoning") == "true" ||
                    model.strings("reasoning_efforts").isNotEmpty()
            )
        }.distinctBy { it.id }
        val pagination = root.objectValue("pagination")
        val page = pagination?.value("current_page")?.toIntOrNull()
        val total = pagination?.value("total_pages")?.toIntOrNull()
        val next = if (page == requestedPage && total != null && page < total) page + 1 else null
        return ModelBrowserPage(entries, next)
    }

    fun parseProviderDirectory(body: String): List<ModelBrowserProvider> =
        parseProviders(JsonParser.parseString(body).asJsonObject.get("data"))

    private fun parseProviders(value: JsonElement?): List<ModelBrowserProvider> =
        value?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty().mapNotNull { element ->
            val obj = element.objectOrNull()
            val id = (obj?.value("slug") ?: obj?.value("id") ?: element.stringOrNull())
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ModelBrowserProvider(id, obj?.value("name")?.takeIf(String::isNotBlank) ?: id)
        }.distinctBy { it.id.lowercase() }

    private fun isChatModel(model: JsonObject, id: String): Boolean {
        val endpoints = model.strings("endpoints")
        if (endpoints.isNotEmpty()) {
            return endpoints.any { it.endsWith("/completions") }
        }
        val outputs = model.objectValue("architecture")?.strings("output_modalities").orEmpty()
            .ifEmpty { model.strings("output_modalities") }
        if (outputs.isNotEmpty()) return outputs.any { it.equals("text", ignoreCase = true) }
        if (id.startsWith("ft:") || id.contains(":ft")) return true
        return listOf("tts", "dall", "whisper", "embedding").none { id.contains(it, ignoreCase = true) }
    }

    private fun hasFreeTextPricing(model: JsonObject, id: String): Boolean {
        // NanoGPT advertises zero base rates for this selector, but it picks
        // a model at request time and that model determines the actual price.
        if (id == "auto-model" && model.value("owned_by") == "nanogpt") return false
        val pricing = model.objectValue("pricing") ?: return false
        val input = pricing.value("prompt") ?: pricing.value("input")
        val output = pricing.value("completion") ?: pricing.value("output")
        if (input?.toBigDecimalOrNull()?.signum() != 0 || output?.toBigDecimalOrNull()?.signum() != 0) {
            return false
        }
        if (!hasNoTextCharges(pricing)) return false
        return pricing.get("overrides")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
            .all { it.objectOrNull()?.let(::hasNoTextCharges) == true }
    }

    private fun hasNoTextCharges(pricing: JsonObject): Boolean =
        listOf("prompt", "completion", "input", "output", "request", "input_cache_read", "input_cache_write")
            .all { key -> pricing.value(key)?.let { it.toBigDecimalOrNull()?.signum() == 0 } ?: true }

    private fun parseDate(value: String?): Instant? {
        if (value == null) return null
        val number = value.toLongOrNull()
        return runCatching {
            when {
                number == null -> Instant.parse(value)
                number <= 0 -> null
                number >= 100_000_000_000 -> Instant.ofEpochMilli(number)
                else -> Instant.ofEpochSecond(number)
            }
        }.getOrNull()
    }

    private fun JsonElement.objectOrNull() = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonElement.stringOrNull() = takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.value(key: String) = get(key)?.stringOrNull()
    private fun JsonObject.objectValue(key: String) = get(key)?.objectOrNull()
    private fun JsonObject.strings(key: String) =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty().mapNotNull { it.stringOrNull() }
}
