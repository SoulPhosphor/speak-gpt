package org.teslasoft.assistant.tts.api

import com.google.gson.JsonObject
import org.teslasoft.assistant.providers.SortDirection
import java.math.BigDecimal
import java.util.Locale

data class TtsCharge(val component: String, val amount: BigDecimal?, val currency: String?,
    val unit: String?, val quantity: BigDecimal? = BigDecimal.ONE)

data class TtsPrice(val charges: List<TtsCharge>, val complete: Boolean) {
    val free: Boolean get() = complete && charges.isNotEmpty() && charges.all { it.amount?.signum() == 0 }
    fun display(): String = if (charges.isEmpty()) "?" else charges.joinToString("; ") { c ->
        val amount = c.amount?.stripTrailingZeros()?.toPlainString() ?: "?"
        "${c.component}: ${c.currency ?: "?"} $amount / ${c.quantity?.stripTrailingZeros()?.toPlainString() ?: "?"} ${c.unit ?: "?"}"
    }
}

data class TtsMetric(val value: Double, val definition: String)
data class TtsProvider(val id: String, val name: String, val price: TtsPrice,
    val latency: TtsMetric?, val uptime: TtsMetric?, val zdr: Boolean?, val training: Boolean?,
    val voices: TtsVoiceCatalog = TtsVoiceCatalog.Unavailable)
data class TtsProviderCatalog(val providers: List<TtsProvider>, val complete: Boolean)

object TtsProviderParser {
    fun parse(body: String): TtsProviderCatalog {
        val root = objectBody(body)
        val data = root.get("data").objectOrNull() ?: root
        val rows = data.getAsJsonArrayOrNull("endpoints") ?: throw IllegalArgumentException("Missing endpoints")
        var readable = true
        val providers = rows.mapNotNull { item ->
            val obj = item.objectOrNull() ?: run { readable = false; return@mapNotNull null }
            // A display name is not a routing identifier.
            val id = obj.text("tag") ?: obj.text("provider_id") ?: obj.text("slug")
                ?: run { readable = false; return@mapNotNull null }
            TtsProvider(id, obj.text("provider_name") ?: obj.text("name") ?: id,
                price(obj.get("pricing").objectOrNull()),
                metric(obj, "latency_last_30m", "latency"), metric(obj, "uptime_last_30m", "uptime"),
                obj.bool("is_zdr") ?: obj.bool("zdr"),
                obj.bool("may_train_on_data") ?: obj.bool("training") ?: obj.bool("data_collection")
                    ?: obj.text("data_collection")?.let { when(it) { "allow" -> true; "deny" -> false; else -> null } },
                TtsCatalogParser.voices(obj.get("supported_voices") ?: obj.get("voices")))
        }
        if (!readable && providers.isEmpty()) throw TtsCatalogDataException(TtsFailureKind.IDENTIFIERS_MISSING)
        return TtsProviderCatalog(providers, readable && providers.isNotEmpty() && complete(root))
    }

    /** Explicit endpoint-local fields only; a provider's general policy is never overlaid. */
    fun overlayZdr(catalog: TtsProviderCatalog, body: String, modelId: String): TtsProviderCatalog {
        val root = objectBody(body)
        val data = root.getAsJsonArrayOrNull("data") ?: root.getAsJsonArrayOrNull("endpoints") ?: return catalog
        val matching = data.mapNotNull { it.objectOrNull() }.filter {
            (it.text("model_variant_slug") ?: it.text("model_id") ?: it.text("model")
                ?: it.text("name")?.substringAfter("|", "")?.trim()) == modelId
        }.mapNotNull { it.text("tag") ?: it.text("provider_id") }.toSet()
        // A list entry is positive evidence; absence can be pagination, aliases, or missing tags.
        return catalog.copy(providers = catalog.providers.map { if (it.id in matching) it.copy(zdr = true) else it })
    }

    private fun metric(obj: JsonObject, primary: String, fallback: String): TtsMetric? {
        for (key in listOf(primary, fallback)) {
            val el = obj.get(key) ?: continue
            val raw = if (el.isJsonObject) el.asJsonObject.get("p50") else el
            val n = raw?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            if (n != null) return TtsMetric(n, key + if (el.isJsonObject) ":p50" else "")
        }
        return null
    }

    fun price(obj: JsonObject?): TtsPrice {
        obj ?: return TtsPrice(emptyList(), false)
        val components = obj.getAsJsonArrayOrNull("components")
        if (components != null) {
            val charges = components.mapNotNull { item -> item.objectOrNull()?.let { c ->
                TtsCharge(c.text("component") ?: "?", decimal(c, "amount"), c.text("currency"),
                    c.text("unit"), decimal(c, "quantity"))
            } }
            return TtsPrice(charges, charges.size == components.size() && charges.isNotEmpty() &&
                charges.all { it.component != "?" } && obj.bool("complete") != false)
        }
        val names = listOf("prompt", "completion", "input", "output", "request", "audio", "characters", "bytes")
        val charges = names.filter(obj::has).map { key ->
            val nested = obj.get(key).objectOrNull()
            TtsCharge(when(key) { "prompt" -> "input"; "completion" -> "output"; else -> key },
                if (nested != null) decimal(nested, "amount") else decimal(obj, key),
                nested?.text("currency") ?: obj.text("currency"),
                nested?.text("unit") ?: obj.text("${key}_unit") ?: obj.text("unit"),
                nested?.let { decimal(it, "quantity") } ?: decimal(obj, "quantity") ?: BigDecimal.ONE)
        }
        // Flat OpenRouter prompt/completion are both applicable. One missing component is unknown.
        val pairComplete = if (obj.has("prompt") || obj.has("completion")) obj.has("prompt") && obj.has("completion")
            else if (obj.has("input") || obj.has("output")) obj.has("input") && obj.has("output") else false
        return TtsPrice(charges, obj.bool("complete") ?: pairComplete)
    }

    private fun decimal(obj: JsonObject, key: String): BigDecimal? = obj.get(key)
        ?.takeIf { it.isJsonPrimitive }?.asString?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
}

/** Picker-local value, never the mutable text-model filter singleton. */
data class TtsProviderSort(val alphaAToZ: Boolean = true, val price: SortDirection = SortDirection.NONE,
    val latency: SortDirection = SortDirection.NONE, val uptime: SortDirection = SortDirection.NONE) {
    fun apply(rows: List<TtsProvider>): List<TtsProvider> = rows.sortedWith { a, b ->
        val p = if (price == SortDirection.NONE) 0 else TtsPriceComparator.compare(a.price, b.price, price)
        if (p != 0) return@sortedWith p
        // Unknown price groups use alphabetical order, not a misleading cross-unit tie-break.
        if (price == SortDirection.NONE || (TtsPriceComparator.known(a.price) && TtsPriceComparator.known(b.price))) {
            for ((direction, values) in listOf(latency to (a.latency to b.latency), uptime to (a.uptime to b.uptime))) {
                if (direction == SortDirection.NONE) continue
                val (left, right) = values
                // A fallback metric is kept identifiable; never compare different measurement windows.
                if (left != null && right != null && left.definition != right.definition)
                    return@sortedWith left.definition.compareTo(right.definition)
                val cmp = when { left == null && right == null -> 0; left == null -> 1; right == null -> -1
                    direction == SortDirection.HIGH_TO_LOW -> right.value.compareTo(left.value)
                    else -> left.value.compareTo(right.value) }
                if (cmp != 0) return@sortedWith cmp
            }
        }
        val alpha = a.name.compareTo(b.name, ignoreCase = true)
        if (alpha != 0) { if (alphaAToZ) alpha else -alpha } else a.id.compareTo(b.id)
    }
}

object TtsPriceComparator {
    private data class Rate(val component: String, val unit: String, val currency: String,
        val amount: BigDecimal, val quantity: BigDecimal)
    private fun rates(p: TtsPrice): List<Rate>? {
        if (!p.complete || p.charges.isEmpty()) return null
        val result = p.charges.map { c ->
            val amount = c.amount ?: return null
            val currency = c.currency?.uppercase(Locale.ROOT) ?: return null
            val q = c.quantity?.takeIf { it.signum() > 0 } ?: return null
            val unit = c.unit?.lowercase(Locale.ROOT) ?: return null
            val (normalized, scale) = when(unit) {
                "second", "seconds", "audio_second", "audio_seconds" -> "audio duration" to BigDecimal.ONE
                "minute", "minutes", "audio_minute", "audio_minutes" -> "audio duration" to BigDecimal(60)
                "token", "tokens" -> "tokens" to BigDecimal.ONE
                "character", "characters" -> "characters" to BigDecimal.ONE
                "byte", "bytes" -> "bytes" to BigDecimal.ONE
                else -> unit to BigDecimal.ONE
            }
            Rate(c.component, normalized, currency, amount, q.multiply(scale))
        }
        if (result.map { it.component }.distinct().size != result.size) return null
        return result.sortedWith(compareBy<Rate> { when(it.component) { "input" -> "0"; "output" -> "1"; else -> "2${it.component}" } })
    }
    fun known(p: TtsPrice): Boolean = p.free || rates(p) != null
    fun compare(a: TtsPrice, b: TtsPrice, direction: SortDirection): Int {
        if (direction == SortDirection.NONE) return 0
        val ar = rates(a); val br = rates(b)
        fun rank(p: TtsPrice, r: List<Rate>?) = if (p.free) 0 else if (r != null) 1 else 2
        val rank = rank(a, ar).compareTo(rank(b, br))
        if (rank != 0) return rank
        if (a.free || ar == null || br == null) return 0
        fun group(r: List<Rate>) = r.map { it.unit }.distinct().sorted().joinToString("|") + ":" +
            r.map { it.currency }.distinct().sorted().joinToString("|") + ":" +
            r.joinToString("|") { "${it.component}:${it.unit}:${it.currency}" }
        val group = group(ar).compareTo(group(br))
        if (group != 0) return group
        for ((left, right) in ar.zip(br)) {
            // Cross-multiplication is exact, including tiny rates and duration conversion.
            val cmp = left.amount.multiply(right.quantity).compareTo(right.amount.multiply(left.quantity))
            if (cmp != 0) return if (direction == SortDirection.HIGH_TO_LOW) -cmp else cmp
        }
        return 0
    }
}
