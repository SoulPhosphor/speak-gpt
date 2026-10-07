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
    val amountDecimal: String? = null
) {
    val usd: Double? get() = amount?.takeIf { currency.equals("USD", true) }
}

object ImageUsageParser {
    private fun count(o: JsonObject?, name: String): Double? = o?.imageNumber(name)?.takeIf { it % 1.0 == 0.0 && it <= Long.MAX_VALUE.toDouble() }
    private fun meter(component: UsageMeterComponent, value: Double?) = UsageMeter(component,
        UsageMeterUnit.TOKEN, value, value?.let { UsageQuantitySource.PROVIDER_REPORTED })

    fun response(kind: ImageProviderKind, body: String, requestId: String?, generationId: String? = null): ImageUsageReceipt {
        val root = imageJson(body) ?: return ImageUsageReceipt(requestId = requestId, generationId = generationId)
        val usage = (root.get("usage") ?: root.get("usageMetadata")).imageObject()
        val meters = mutableListOf<UsageMeter>()
        if (kind == ImageProviderKind.GEMINI && (root.has("steps") || usage?.has("total_input_tokens") == true)) {
            fun modality(key: String, name: String, total: String): Double? {
                val details = usage?.imageArray(key) ?: return null
                val matching = details.mapNotNull { it.imageObject() }.filter { it.imageText("modality").equals(name, true) }
                if (matching.isNotEmpty()) return matching.map { count(it, "tokens") }
                    .takeIf { it.all { value -> value != null } }?.sumOf { it!! }
                // An absent modality is zero only when the full reported split reconciles.
                val values = details.map { count(it.imageObject(), "tokens") }
                return 0.0.takeIf { values.all { value -> value != null } && values.sumOf { it!! } == count(usage, total) }
            }
            fun unresolvedSplit(key: String, total: String, component: UsageMeterComponent) {
                val details = usage?.imageArray(key) ?: return
                val objects = details.map { it.imageObject() }
                val quantities = objects.map { count(it, "tokens") }
                val known = objects.all { it?.imageText("modality")?.lowercase() in setOf("text", "image") } &&
                    quantities.all { it != null }
                val reported = count(usage, total)
                if (!known || (reported != null && quantities.sumOf { it!! } != reported))
                    meters += meter(component, reported)
            }
            unresolvedSplit("input_tokens_by_modality", "total_input_tokens", UsageMeterComponent.INPUT)
            unresolvedSplit("output_tokens_by_modality", "total_output_tokens", UsageMeterComponent.OUTPUT)
            val input = modality("input_tokens_by_modality", "text", "total_input_tokens")
                ?: count(usage, "total_input_tokens").takeIf { usage?.imageArray("input_tokens_by_modality") == null }
            meters += meter(UsageMeterComponent.TEXT_INPUT, input)
            meters += meter(UsageMeterComponent.IMAGE_INPUT, modality("input_tokens_by_modality", "image", "total_input_tokens"))
            val imageOutput = modality("output_tokens_by_modality", "image", "total_output_tokens")
            val textOutput = modality("output_tokens_by_modality", "text", "total_output_tokens")
            val thoughts = count(usage, "total_thought_tokens")
            meters += meter(UsageMeterComponent.IMAGE_OUTPUT, imageOutput)
            meters += meter(UsageMeterComponent.TEXT_OUTPUT, textOutput?.let { text -> thoughts?.let { text + it } ?: text })
            // An omitted optional thought counter does not erase the reported text
            // split. An explicitly malformed counter still prevents a complete bill.
            if (usage?.has("total_thought_tokens") == true && thoughts == null)
                meters += meter(UsageMeterComponent.OUTPUT, null)
            if (usage?.imageArray("output_tokens_by_modality") == null)
                meters += meter(UsageMeterComponent.OUTPUT, count(usage, "total_output_tokens"))
            meters += meter(UsageMeterComponent.CACHED_TEXT_INPUT,
                modality("cached_tokens_by_modality", "text", "total_cached_tokens")
                    ?: 0.0.takeIf { count(usage, "total_cached_tokens") == 0.0 })
            meters += meter(UsageMeterComponent.CACHED_IMAGE_INPUT,
                modality("cached_tokens_by_modality", "image", "total_cached_tokens")
                    ?: 0.0.takeIf { count(usage, "total_cached_tokens") == 0.0 })
            count(usage, "total_tool_use_tokens")?.takeIf { it > 0 }?.let { meters += meter(UsageMeterComponent.INPUT, it) }
        } else if (kind == ImageProviderKind.GEMINI) {
            fun modalities(key: String, input: Boolean): Boolean {
                val details = usage?.imageArray(key) ?: return false
                details.forEach { detail ->
                    val o = detail.imageObject() ?: return@forEach
                    val component = when (o.imageText("modality")) {
                        "TEXT" -> if (input) UsageMeterComponent.TEXT_INPUT else UsageMeterComponent.TEXT_OUTPUT
                        "IMAGE" -> if (input) UsageMeterComponent.IMAGE_INPUT else UsageMeterComponent.IMAGE_OUTPUT
                        else -> return@forEach
                    }
                    meters += meter(component, count(o, "tokenCount"))
                }
                return details.size() > 0
            }
            if (!modalities("promptTokensDetails", true)) meters += meter(UsageMeterComponent.TEXT_INPUT, count(usage, "promptTokenCount"))
            if (!modalities("candidatesTokensDetails", false)) meters += meter(UsageMeterComponent.OUTPUT, count(usage, "candidatesTokenCount"))
            count(usage, "thoughtsTokenCount")?.let { thoughts ->
                // Thinking is additional text output in Gemini's documented usage layout.
                val index = meters.indexOfFirst { it.component == UsageMeterComponent.TEXT_OUTPUT }
                if (index >= 0 && meters[index].quantity != null) meters[index] = meters[index].copy(quantity = meters[index].quantity!! + thoughts)
                else meters += meter(UsageMeterComponent.TEXT_OUTPUT, thoughts)
            }
        } else if (kind == ImageProviderKind.OPENAI) {
            val input = usage?.get("input_tokens_details").imageObject()
            val output = usage?.get("output_tokens_details").imageObject()
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
        val amount = usage?.imageNumber("cost") ?: root.imageNumber("cost")
        return ImageUsageReceipt(root.imageText("model") ?: root.imageText("modelVersion"),
            root.imageText("provider") ?: root.imageText("provider_name"), imageIdentifier(requestId),
            imageIdentifier(generationId) ?: imageIdentifier(root.imageText("id")), images, megapixels, meters, amount, currency)
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
        val decimal = o.imageText(if (kind == ImageProviderKind.OPENROUTER) "total_cost" else "cost")
            ?.takeIf { value -> value.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true }
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
        var completePricing = metadata?.tariffsComplete == true
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
        val originalCharge = billing?.amount?.let { billing } ?: receipt
        if (originalCharge.amount != null && !originalCharge.currency.equals("USD", true)) completePricing = false
        val total = if (completePricing) billed.fold(java.math.BigDecimal.ZERO) { sum, meter ->
            sum.add(java.math.BigDecimal.valueOf(meter.cost!!))
        }.toDouble().takeIf { it.isFinite() } else null
        val reported = billing?.usd ?: receipt.usd
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
