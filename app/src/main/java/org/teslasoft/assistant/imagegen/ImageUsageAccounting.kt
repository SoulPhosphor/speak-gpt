package org.teslasoft.assistant.imagegen

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.teslasoft.assistant.usage.*

/** Allowlisted billing evidence. Raw response bodies never enter the usage log. */
data class ImageUsageReceipt(
    val model: String? = null,
    val provider: String? = null,
    val requestId: String? = null,
    val generationId: String? = null,
    val images: Double? = null,
    val megapixels: Double? = null,
    val meters: List<UsageMeter> = emptyList(),
    val amount: Double? = null,
    val currency: String? = null,
    val amountDecimal: String? = null,
    val usageVerified: Boolean = true
) {
    val usd: Double? get() = amount?.takeIf { currency.equals("USD", true) }
}

object ImageUsageParser {
    private fun chargeDecimal(o: JsonObject?, name: String): String? = o?.imageText(name)
        ?.takeIf { value -> value.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true }
    private fun count(o: JsonObject?, name: String): Double? {
        return runCatching {
            val field = o?.get(name)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean } ?: return null
            val exact = field.asString.toBigDecimal().longValueExact().takeIf { it >= 0 } ?: return null
            exact.toDouble().takeIf { java.math.BigDecimal.valueOf(it).longValueExact() == exact }
        }.getOrNull()
    }

    private fun addCounts(first: Double, second: Double): Double? {
        val exact = java.math.BigDecimal.valueOf(first).add(java.math.BigDecimal.valueOf(second))
        val result = exact.toDouble().takeIf { it.isFinite() } ?: return null
        return result.takeIf { java.math.BigDecimal.valueOf(it).compareTo(exact) == 0 }
    }

    private fun split(o: JsonObject?, key: String, totalKey: String, countKey: String): Map<String, Double>? {
        val details = o?.imageArray(key) ?: return null
        val total = count(o, totalKey) ?: return null
        val counts = linkedMapOf<String, java.math.BigDecimal>()
        for (detail in details) {
            val entry = detail.imageObject() ?: return null
            val modality = entry.imageText("modality")?.lowercase()?.takeIf { it == "text" || it == "image" } ?: return null
            val quantity = count(entry, countKey) ?: return null
            counts[modality] = (counts[modality] ?: java.math.BigDecimal.ZERO).add(java.math.BigDecimal.valueOf(quantity))
        }
        if (counts.values.fold(java.math.BigDecimal.ZERO) { sum, quantity -> sum.add(quantity) }
                .compareTo(java.math.BigDecimal.valueOf(total)) != 0) return null
        val result = linkedMapOf<String, Double>()
        for ((modality, exact) in counts) {
            val value = exact.toDouble().takeIf { it.isFinite() } ?: return null
            if (java.math.BigDecimal.valueOf(value).compareTo(exact) != 0) return null
            result[modality] = value
        }
        return result
    }
    private fun meter(component: UsageMeterComponent, value: Double?) = UsageMeter(component,
        UsageMeterUnit.TOKEN, value, value?.let { UsageQuantitySource.PROVIDER_REPORTED })

    fun response(kind: ImageProviderKind, body: String, requestId: String?, generationId: String? = null): ImageUsageReceipt {
        val root = imageJson(body) ?: return ImageUsageReceipt(requestId = requestId, generationId = generationId)
        val usage = (root.get("usage") ?: root.get("usageMetadata")).imageObject()
        val meters = mutableListOf<UsageMeter>()
        var usageVerified = true
        fun preserveUnresolvedThoughts(thoughts: Double?, malformed: Boolean) {
            val index = meters.indexOfFirst { it.component == UsageMeterComponent.OUTPUT }
            if (index < 0) return
            val output = meters[index].quantity
            val combined = if (malformed) null else if (thoughts == null) output
                else output?.let { addCounts(it, thoughts) }
            if (combined == null) usageVerified = false
            meters[index] = meter(UsageMeterComponent.OUTPUT, combined)
        }
        if (kind == ImageProviderKind.GEMINI && (root.has("steps") || usage?.has("total_input_tokens") == true)) {
            fun modality(key: String, name: String, total: String): Double? {
                val verified = split(usage, key, total, "tokens") ?: return null
                return verified[name] ?: 0.0
            }
            fun unresolvedSplit(key: String, total: String, component: UsageMeterComponent) {
                if (split(usage, key, total, "tokens") == null) {
                    usageVerified = false
                    meters += meter(component, count(usage, total))
                }
            }
            unresolvedSplit("input_tokens_by_modality", "total_input_tokens", UsageMeterComponent.INPUT)
            unresolvedSplit("output_tokens_by_modality", "total_output_tokens", UsageMeterComponent.OUTPUT)
            val input = modality("input_tokens_by_modality", "text", "total_input_tokens")
            meters += meter(UsageMeterComponent.TEXT_INPUT, input)
            meters += meter(UsageMeterComponent.IMAGE_INPUT, modality("input_tokens_by_modality", "image", "total_input_tokens"))
            val imageOutput = modality("output_tokens_by_modality", "image", "total_output_tokens")
            val textOutput = modality("output_tokens_by_modality", "text", "total_output_tokens")
            val thoughts = count(usage, "total_thought_tokens")
            val malformedThoughts = usage?.has("total_thought_tokens") == true && thoughts == null
            meters += meter(UsageMeterComponent.IMAGE_OUTPUT, imageOutput)
            val combinedText = if (malformedThoughts || textOutput == null) null
                else if (thoughts == null) textOutput else addCounts(textOutput, thoughts)
            if (textOutput != null && thoughts != null && combinedText == null) usageVerified = false
            meters += meter(UsageMeterComponent.TEXT_OUTPUT, combinedText)
            if (textOutput == null) preserveUnresolvedThoughts(thoughts, malformedThoughts)
            // An omitted optional thought counter keeps the final-text split readable.
            // A malformed one makes the billed output quantity and cost unknown.
            if (malformedThoughts) usageVerified = false
            val cache = split(usage, "cached_tokens_by_modality", "total_cached_tokens", "tokens")
            val zeroCache = usage?.has("cached_tokens_by_modality") != true && count(usage, "total_cached_tokens") == 0.0
            meters += meter(UsageMeterComponent.CACHED_TEXT_INPUT, cache?.get("text") ?: 0.0.takeIf { zeroCache || cache != null })
            meters += meter(UsageMeterComponent.CACHED_IMAGE_INPUT, cache?.get("image") ?: 0.0.takeIf { zeroCache || cache != null })
            if (cache == null && !zeroCache) {
                usageVerified = false
                count(usage, "total_cached_tokens")?.let { meters += meter(UsageMeterComponent.CACHED_INPUT, it) }
            }
            count(usage, "total_tool_use_tokens")?.takeIf { it > 0 }?.let { meters += meter(UsageMeterComponent.INPUT, it) }
        } else if (kind == ImageProviderKind.GEMINI) {
            fun modalities(key: String, input: Boolean): Boolean {
                val verified = split(usage, key, if (input) "promptTokenCount" else "candidatesTokenCount", "tokenCount")
                    ?: return false
                val components = if (input) listOf("text" to UsageMeterComponent.TEXT_INPUT, "image" to UsageMeterComponent.IMAGE_INPUT)
                    else listOf("text" to UsageMeterComponent.TEXT_OUTPUT, "image" to UsageMeterComponent.IMAGE_OUTPUT)
                components.forEach { (modality, component) -> meters += meter(component, verified[modality] ?: 0.0) }
                return true
            }
            if (!modalities("promptTokensDetails", true)) {
                usageVerified = false
                meters += meter(UsageMeterComponent.INPUT, count(usage, "promptTokenCount"))
            }
            if (!modalities("candidatesTokensDetails", false)) {
                usageVerified = false
                meters += meter(UsageMeterComponent.OUTPUT, count(usage, "candidatesTokenCount"))
            }
            val thoughts = count(usage, "thoughtsTokenCount")
            val malformedThoughts = usage?.has("thoughtsTokenCount") == true && thoughts == null
            if (malformedThoughts) {
                usageVerified = false
                val index = meters.indexOfFirst { it.component == UsageMeterComponent.TEXT_OUTPUT }
                if (index >= 0) meters[index] = meter(UsageMeterComponent.TEXT_OUTPUT, null)
                else meters += meter(UsageMeterComponent.TEXT_OUTPUT, null)
            } else thoughts?.let {
                // Thinking is additional output in the provider's documented usage layout.
                val index = meters.indexOfFirst { it.component == UsageMeterComponent.TEXT_OUTPUT }
                if (index >= 0) {
                    val text = meters[index].quantity
                    val combined = text?.let { value -> addCounts(value, it) }
                    if (text != null && combined == null) usageVerified = false
                    meters[index] = meter(UsageMeterComponent.TEXT_OUTPUT, combined)
                } else meters += meter(UsageMeterComponent.TEXT_OUTPUT, null)
            }
            preserveUnresolvedThoughts(thoughts, malformedThoughts)
            if (usage?.has("cachedContentTokenCount") == true) {
                val cached = count(usage, "cachedContentTokenCount")
                meters += meter(UsageMeterComponent.CACHED_INPUT, cached)
                // Cached input needs its own published rate; the standard input rate
                // cannot establish a complete bill for a discounted request.
                if (cached == null || cached > 0.0) usageVerified = false
            }
        } else if (kind == ImageProviderKind.OPENAI) {
            val input = usage?.get("input_tokens_details").imageObject()
            val output = usage?.get("output_tokens_details").imageObject()
            fun reconcile(details: JsonObject?, key: String, component: UsageMeterComponent) {
                if (details == null || usage?.has(key) != true) return
                val text = count(details, "text_tokens")
                val image = count(details, "image_tokens")
                val total = count(usage, key)
                if (text == null || image == null || total == null ||
                    java.math.BigDecimal.valueOf(text).add(java.math.BigDecimal.valueOf(image))
                        .compareTo(java.math.BigDecimal.valueOf(total)) != 0) {
                    usageVerified = false
                    meters += meter(component, total)
                }
            }
            reconcile(input, "input_tokens", UsageMeterComponent.INPUT)
            reconcile(output, "output_tokens", UsageMeterComponent.OUTPUT)
            if (input != null) {
                meters += meter(UsageMeterComponent.TEXT_INPUT, count(input, "text_tokens"))
                meters += meter(UsageMeterComponent.IMAGE_INPUT, count(input, "image_tokens"))
                val cached = input.get("cached_tokens_details").imageObject()
                cached?.let {
                    meters += meter(UsageMeterComponent.CACHED_TEXT_INPUT, count(it, "text_tokens"))
                    meters += meter(UsageMeterComponent.CACHED_IMAGE_INPUT, count(it, "image_tokens"))
                }
            } else meters += meter(UsageMeterComponent.INPUT, count(usage, "input_tokens"))
            if (output != null) {
                meters += meter(UsageMeterComponent.TEXT_OUTPUT, count(output, "text_tokens"))
                meters += meter(UsageMeterComponent.IMAGE_OUTPUT, count(output, "image_tokens"))
            } else {
                // Without a split, preserve the total. The frozen model document can
                // identify image-only output, but the model name cannot.
                meters += meter(UsageMeterComponent.OUTPUT, count(usage, "output_tokens"))
            }
        } else {
            val input = count(usage, "prompt_tokens") ?: count(usage, "input_tokens")
            val output = count(usage, "completion_tokens") ?: count(usage, "output_tokens")
            input?.let { meters += meter(UsageMeterComponent.INPUT, it) }
            output?.let { meters += meter(UsageMeterComponent.OUTPUT, it) }
        }
        val credits = usage?.imageNumber("credits") ?: root.imageNumber("credits_used")
        credits?.let { meters += UsageMeter(UsageMeterComponent.CREDITS, UsageMeterUnit.CREDIT, it, UsageQuantitySource.PROVIDER_REPORTED) }
        val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
        val chatImages = root.imageArray("choices")?.firstOrNull()?.imageObject()?.get("message").imageObject()?.imageArray("images")
        val nativeImages = root.imageArray("candidates")?.flatMap { candidate ->
            candidate.imageObject()?.get("content").imageObject()?.imageArray("parts")?.toList().orEmpty()
        }?.count { part ->
            val o = part.imageObject()
            o?.get("thought")?.asBoolean != true && (o?.get("inlineData") ?: o?.get("inline_data")).imageObject()
                ?.let { (it.imageText("mimeType") ?: it.imageText("mime_type"))?.startsWith("image/") == true } == true
        }
        val images = when {
            kind == ImageProviderKind.GEMINI && root.has("steps") -> GeminiImageAdapter.interactionImages(root).size.toDouble()
            data != null -> data.size().toDouble()
            chatImages != null -> chatImages.size().toDouble()
            nativeImages != null -> nativeImages.toDouble()
            else -> null
        }
        val megapixels = data?.map { element ->
            val o = element.imageObject()
            val width = count(o, "width")?.takeIf { it > 0 }
            val height = count(o, "height")?.takeIf { it > 0 }
            if (width != null && height != null) width * height / 1_000_000.0 else null
        }?.takeIf { it.isNotEmpty() && it.all { value -> value != null && value.isFinite() } }
            ?.sumOf { it!! }
        val reportedCurrency = usage?.imageText("currency") ?: root.imageText("currency")
        val currency = reportedCurrency ?: if (kind == ImageProviderKind.OPENROUTER) "USD" else null
        // Select the preferred evidence before parsing, so an unreadable usage charge
        // cannot borrow a different numeric charge from the response root.
        val charge = usage?.takeIf { it.has("cost") } ?: root
        val amount = charge.imageNumber("cost")
        return ImageUsageReceipt(root.imageText("model") ?: root.imageText("modelVersion"),
            root.imageText("provider") ?: root.imageText("provider_name"), imageIdentifier(requestId),
            imageIdentifier(generationId) ?: imageIdentifier(root.imageText("id")), images, megapixels, meters, amount, currency,
            chargeDecimal(charge, "cost"), usageVerified)
    }

    /** Only a receipt with the exact provider-issued identity can enrich a request. */
    fun billing(kind: ImageProviderKind, body: String, expectedId: String): ImageUsageReceipt? {
        val root = imageJson(body) ?: return null
        val o = root.get("data").imageObject() ?: root
        val id = o.imageText(if (kind == ImageProviderKind.NANOGPT) "request_id" else "id")
        if (id != expectedId) return null
        val amount = o.imageNumber(if (kind == ImageProviderKind.OPENROUTER) "total_cost" else "cost")
        val currency = o.imageText("currency") ?: if (kind == ImageProviderKind.OPENROUTER) "USD" else null
        val usage = o.get("usage").imageObject()
        val meters = listOfNotNull(
            (count(usage, "input_tokens") ?: count(o, "native_tokens_prompt"))?.let { meter(UsageMeterComponent.INPUT, it) },
            (count(usage, "output_tokens") ?: count(o, "native_tokens_completion"))?.let { meter(UsageMeterComponent.OUTPUT, it) })
        val decimal = chargeDecimal(o, if (kind == ImageProviderKind.OPENROUTER) "total_cost" else "cost")
        return ImageUsageReceipt(o.imageText("model"), o.imageText("provider_name"),
            requestId = if (kind == ImageProviderKind.NANOGPT) id else null,
            generationId = if (kind == ImageProviderKind.OPENROUTER) id else null,
            amount = amount, currency = currency, meters = meters, amountDecimal = decimal)
    }
}

/** One HTTP attempt. The snapshot is made before dispatch and never repriced on screen. */
data class ImageUsageAttempt(
    val kind: ImageProviderKind,
    val model: String,
    val provider: String,
    val endpoint: String,
    val startedAtMs: Long,
    val parameters: Map<String, String>,
    val metadata: ImageModelMetadata?,
    val httpStatus: Int? = null,
    val finalized: Boolean = false,
    val receipt: ImageUsageReceipt = ImageUsageReceipt()
) {
    fun record(billing: ImageUsageReceipt? = null): TurnUsageRecord {
        val selectedProvider = billing?.provider ?: receipt.provider ?: provider
        // Billing receipts may report a serving model. Its prices are used only if its ID
        // matches the metadata frozen for this request; requested IDs are preserved too.
        val selectedModel = billing?.model ?: receipt.model ?: model
        val sameModel = selectedModel in metadata?.resolvedIds.orEmpty()
        var completePricing = metadata?.tariffsComplete == true && receipt.usageVerified && billing?.usageVerified != false
        val tariffs = if (sameModel) metadata?.let { published ->
            if (published.endpointRecords.isEmpty()) published.tariffs else {
                val identified = billing?.provider ?: receipt.provider
                val serving = published.endpointRecords.filter {
                    it.providerName.equals(selectedProvider, true) || it.providerSlug.equals(selectedProvider, true)
                }.ifEmpty { if (identified.isNullOrBlank()) published.endpointRecords else emptyList() }
                completePricing = completePricing && serving.isNotEmpty() && serving.all { it.tariffsComplete }
                serving.firstOrNull()?.tariffs?.takeIf { first -> serving.all { it.tariffs == first } }.orEmpty()
            }
        }.orEmpty() else emptyList()
        val imageOnly = sameModel && metadata?.outputModalities == setOf("image")
        val combinedMeters = receipt.meters.filterNot { original -> billing?.meters.orEmpty().any { it.component == original.component && it.unit == original.unit } } + billing?.meters.orEmpty()
        val meters = combinedMeters.map { meter ->
            if (kind == ImageProviderKind.OPENAI && imageOnly && meter.component == UsageMeterComponent.OUTPUT)
                meter.copy(component = UsageMeterComponent.IMAGE_OUTPUT) else meter
        }.toMutableList()
        val images = UsageMeter(UsageMeterComponent.IMAGES, UsageMeterUnit.IMAGE, receipt.images,
            receipt.images?.let { UsageQuantitySource.LOCAL_EXACT })
        meters += images
        receipt.megapixels?.let { meters += UsageMeter(UsageMeterComponent.IMAGE_OUTPUT,
            UsageMeterUnit.MEGAPIXEL, it, UsageQuantitySource.LOCAL_EXACT) }
        val billed = mutableListOf<UsageMeter>()
        completePricing = completePricing && tariffs.isNotEmpty() && (httpStatus == null || httpStatus in 200..299)
        // No reference images are sent by these generation-only adapters. The native
        // Images API returns images, without generated text. These are exact zeros.
        val exactZeros = when (kind) {
            ImageProviderKind.OPENAI -> listOf(UsageMeterComponent.IMAGE_INPUT, UsageMeterComponent.CACHED_IMAGE_INPUT) +
                if (imageOnly) listOf(UsageMeterComponent.TEXT_OUTPUT) else emptyList()
            ImageProviderKind.GEMINI -> listOf(UsageMeterComponent.IMAGE_INPUT, UsageMeterComponent.CACHED_IMAGE_INPUT)
            else -> emptyList()
        }
        val inputIsZero = meters.firstOrNull { it.component == UsageMeterComponent.TEXT_INPUT }?.quantity == 0.0
        val documentedUncached = kind == ImageProviderKind.OPENAI && sameModel && metadata?.directCachedInputExcluded == true
        if (documentedUncached && meters.any { it.component == UsageMeterComponent.CACHED_TEXT_INPUT && (it.quantity ?: 0.0) > 0.0 })
            completePricing = false
        val zeros = exactZeros + if (inputIsZero || documentedUncached) listOf(UsageMeterComponent.CACHED_TEXT_INPUT) else emptyList()
        zeros.forEach { component ->
            val index = meters.indexOfFirst { it.component == component }
            if (index < 0) meters += UsageMeter(component, UsageMeterUnit.TOKEN, 0.0, UsageQuantitySource.LOCAL_EXACT)
            else if (meters[index].quantity == null) meters[index] = meters[index].copy(quantity = 0.0, quantitySource = UsageQuantitySource.LOCAL_EXACT)
        }
        // This path sends no input images or references, so published per-image
        // input/reference lines have an exact local quantity of zero.
        tariffs.filter { it.billable == "input_image" || it.billable == "input_reference" }.forEach { tariff ->
            UsageMeterUnit.fromKey(tariff.unit)?.let { unit ->
                if (meters.none { it.component == UsageMeterComponent.IMAGE_INPUT && it.unit == unit })
                    meters += UsageMeter(UsageMeterComponent.IMAGE_INPUT, unit, 0.0, UsageQuantitySource.LOCAL_EXACT)
            }
        }
        val effectiveParameters = metadata?.parameters.orEmpty().mapNotNull { field -> field.defaultValue?.let { field.key to it } }.toMap() + parameters
        val matched = tariffs.filter { tariff -> tariff.conditions.all { (key, value) ->
            if (key == "variant") value.equals(effectiveParameters["resolution"], true) || value.equals(effectiveParameters["size"], true)
            else effectiveParameters[key] == value
        } }
        if (tariffs.map { it.billable }.toSet() != matched.map { it.billable }.toSet()) completePricing = false
        for (tariff in matched) {
            val component = when (tariff.billable) {
                "output_image" -> if (tariff.unit == "image") UsageMeterComponent.IMAGES else UsageMeterComponent.IMAGE_OUTPUT
                "text_input" -> UsageMeterComponent.TEXT_INPUT
                "text_cached_input" -> UsageMeterComponent.CACHED_TEXT_INPUT
                "text_output" -> UsageMeterComponent.TEXT_OUTPUT
                "input_image" -> UsageMeterComponent.IMAGE_INPUT
                "input_reference" -> UsageMeterComponent.IMAGE_INPUT
                "image_input" -> UsageMeterComponent.IMAGE_INPUT
                "image_cached_input" -> UsageMeterComponent.CACHED_IMAGE_INPUT
                "image_output" -> UsageMeterComponent.IMAGE_OUTPUT
                "credits" -> UsageMeterComponent.CREDITS
                else -> { completePricing = false; continue }
            }
            val unit = UsageMeterUnit.fromKey(tariff.unit)
            if (unit == null) { completePricing = false; continue }
            if (billed.any { it.component == component && it.unit == unit }) { completePricing = false; continue }
            val index = meters.indexOfFirst { it.component == component && it.unit == unit }
            var meter = if (index >= 0) meters[index] else UsageMeter(component, unit, null, null)
            if (component == UsageMeterComponent.TEXT_INPUT || component == UsageMeterComponent.IMAGE_INPUT) {
                val cachedComponent = if (component == UsageMeterComponent.TEXT_INPUT) UsageMeterComponent.CACHED_TEXT_INPUT else UsageMeterComponent.CACHED_IMAGE_INPUT
                if (tariffs.any { it.billable == if (component == UsageMeterComponent.TEXT_INPUT) "text_cached_input" else "image_cached_input" }) {
                    val cached = meters.firstOrNull { it.component == cachedComponent }?.quantity ?: 0.0.takeIf { meter.quantity == 0.0 }
                    val quantity = meter.quantity?.let { total -> if (total == 0.0) 0.0 else cached?.takeIf { it <= total }?.let { total - it } }
                    meter = meter.copy(quantity = quantity, quantitySource = quantity?.let { UsageQuantitySource.PROVIDER_REPORTED })
                }
            }
            val priced = meter.copy(priceAmount = tariff.amount, priceQuantity = tariff.quantity, currency = tariff.currency).withCalculatedCost()
            billed += priced
            if (index >= 0) meters[index] = priced else meters += priced
        }
        // Conditional tiers must resolve. Never treat an unmatched tier as a free request.
        if (billed.isEmpty() || billed.any { it.cost == null }) completePricing = false
        // A reported billable quantity with no matching price cannot disappear from a total.
        val informational = setOf(UsageMeterComponent.IMAGES, UsageMeterComponent.CREDITS)
        if (tariffs.none { it.unit == "image" } && meters.any { it.component !in informational && it.quantity != 0.0 &&
                billed.none { price -> price.component == it.component && price.unit == it.unit } }) completePricing = false
        val originalCharge = billing?.takeIf { it.amount != null || it.amountDecimal != null } ?: receipt
        if (originalCharge.amountDecimal != null && originalCharge.amount == null) completePricing = false
        if ((originalCharge.amount != null || originalCharge.amountDecimal != null) && !originalCharge.currency.equals("USD", true)) completePricing = false
        val total = if (completePricing) billed.fold(java.math.BigDecimal.ZERO) { sum, meter ->
            sum.add(java.math.BigDecimal.valueOf(meter.cost!!))
        }.toDouble().takeIf { it.isFinite() } else null
        val reported = originalCharge.usd
        return MeteredUsageAccounting.record(selectedModel, selectedProvider, endpoint, meters, reported).copy(
            totalCost = reported ?: total,
            costSource = when { reported != null -> CostSource.PROVIDER_REPORTED; total != null -> CostSource.FROZEN_PRICING; else -> CostSource.UNKNOWN }.storedValue,
            requestId = receipt.requestId ?: receipt.generationId,
            requestedModel = model, requestStartedAtMs = startedAtMs, httpStatus = httpStatus,
            requestParameters = Gson().toJson(effectiveParameters), pricingSource = metadata?.sourceUrl,
            pricingEvidence = metadata?.let { Gson().toJson(it) },
            reportedChargeAmount = originalCharge.amount, reportedChargeCurrency = originalCharge.currency,
            reportedChargeDecimal = originalCharge.amountDecimal
        )
    }
}
