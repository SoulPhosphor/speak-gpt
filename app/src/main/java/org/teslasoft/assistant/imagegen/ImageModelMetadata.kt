package org.teslasoft.assistant.imagegen

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

/** Protocol identities, not a model or capability catalog. */
enum class ImageProviderKind {
    OPENAI, OPENROUTER, NANOGPT, GEMINI, COMPATIBLE;

    companion object {
        fun forEndpoint(endpoint: ApiEndpointObject): ImageProviderKind = when (endpoint.host.toHttpUrlOrNull()?.host) {
            "api.openai.com" -> OPENAI
            "openrouter.ai" -> OPENROUTER
            "nano-gpt.com", "api.nano-gpt.com" -> NANOGPT
            "generativelanguage.googleapis.com" -> GEMINI
            else -> if (endpoint.isOpenRouterRouting()) OPENROUTER else COMPATIBLE
        }
    }
}

enum class ImageParameterType { ENUM, INTEGER, NUMBER, STRING, BOOLEAN }

/** API protocols; the published guide supplies which one a model uses. */
enum class GeminiImageTransport { GENERATE_CONTENT, INTERACTIONS }

/** Every value and bound comes from the selected model's fetched metadata. */
data class ImageParameter(
    val key: String,
    val type: ImageParameterType,
    val values: List<String> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val defaultValue: String? = null
) {
    fun selectableValues(): List<String> = if (key == "output_format")
        values.filter(ImageFormat::supportsOutputName) else values

    fun accepts(value: String): Boolean = when (type) {
        ImageParameterType.ENUM -> value in values
        ImageParameterType.STRING -> value.isNotBlank()
        ImageParameterType.BOOLEAN -> value == "true" || value == "false"
        ImageParameterType.INTEGER, ImageParameterType.NUMBER -> value.toDoubleOrNull()?.let {
            it.isFinite() && (type != ImageParameterType.INTEGER || value.toLongOrNull() != null) &&
                (minimum == null || it >= minimum) && (maximum == null || it <= maximum)
        } == true
    }
}

/** Monetary lines and units remain provider data until accounting resolves them. */
data class ImageTariff(
    val billable: String,
    val unit: String,
    val amount: Double,
    val quantity: Double,
    val currency: String,
    val conditions: Map<String, String> = emptyMap()
)

data class ImageModelMetadata(
    val id: String,
    val parameters: List<ImageParameter> = emptyList(),
    val tariffs: List<ImageTariff> = emptyList(),
    val sourceUrl: String? = null,
    val endpointRecords: List<ImageServingMetadata> = emptyList(),
    val knownImageOutput: Boolean = true,
    val tariffsComplete: Boolean = true,
    val resolvedIds: Set<String> = setOf(id),
    val outputModalities: Set<String> = emptySet(),
    val nativeSizes: List<String> = emptyList(),
    val geminiTransport: GeminiImageTransport? = null,
    val requiresExplicitOutputFormat: Boolean = false,
    val directCachedInputExcluded: Boolean = false
) {
    /** An explicitly published format-only model must have an app-decodable output. */
    fun hasDisplayableOutput(): Boolean = parameters.firstOrNull {
        it.key == "output_format" && it.type == ImageParameterType.ENUM
    }?.selectableValues()?.isNotEmpty() ?: !requiresExplicitOutputFormat

    /** Keep known output restrictions when endpoint details fail; never reuse old rates. */
    fun withoutEndpointEvidence(): ImageModelMetadata = copy(
        parameters = parameters.filter { it.key == "output_format" },
        tariffs = emptyList(), endpointRecords = emptyList(), tariffsComplete = false)
}

data class ImageServingMetadata(
    val providerName: String?,
    val providerSlug: String?,
    val parameters: List<ImageParameter>,
    val tariffs: List<ImageTariff>,
    val tariffsComplete: Boolean = true
)

internal fun JsonElement?.imageObject(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.imageText(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }
    ?.asString?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
internal fun JsonObject.imageNumber(key: String): Double? = try {
    get(key)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }?.asDouble
        ?.takeIf { it.isFinite() && it >= 0.0 }
} catch (_: Exception) { null }
internal fun JsonObject.imageArray(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
internal fun imageIdentifier(value: String?): String? = value?.takeIf {
    it.isNotBlank() && it.length <= 512 && it.all { c -> c.isLetterOrDigit() || c in "-_./:" }
}
internal fun JsonObject.imageBound(key: String): Double? = runCatching {
    get(key)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }?.asDouble?.takeIf { it.isFinite() }
}.getOrNull()
internal fun imageJson(body: String): JsonObject? = runCatching { JsonParser.parseString(body).imageObject() }.getOrNull()
internal fun JsonObject.imageStrings(key: String): List<String> = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
    it.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isString }?.asString
}.orEmpty()

/** Reads typed image catalogs shared by the dedicated OpenRouter and NanoGPT APIs. */
object ImageMetadataParser {
    // These are protocol fields owned by the request, not model settings.
    private val reserved = setOf("model", "prompt", "n", "stream", "input_references", "user")
    private val settingsOrder = listOf("size", "resolution", "aspect_ratio", "quality", "background",
        "output_format", "output_compression", "seed")

    fun parameters(root: JsonObject?): List<ImageParameter> = root?.entrySet()?.mapNotNull { (key, value) ->
        if (key in reserved) return@mapNotNull null
        val descriptor = value.imageObject() ?: return@mapNotNull null
        val values = descriptor.imageStrings("values").ifEmpty { descriptor.imageStrings("enum") }
        val type = when (descriptor.imageText("type")) {
            "enum" -> ImageParameterType.ENUM
            "range", "integer" -> ImageParameterType.INTEGER
            "number" -> ImageParameterType.NUMBER
            "string" -> if (values.isNotEmpty()) ImageParameterType.ENUM else ImageParameterType.STRING
            // The Images API's boolean descriptor means 'supported', not a boolean request value.
            "boolean" -> when (key) {
                "seed", "output_compression" -> ImageParameterType.INTEGER
                else -> return@mapNotNull null
            }
            else -> return@mapNotNull null
        }
        if (type == ImageParameterType.ENUM && values.isEmpty()) return@mapNotNull null
        ImageParameter(key, type, values, descriptor.imageBound("min") ?: descriptor.imageBound("minimum"),
            descriptor.imageBound("max") ?: descriptor.imageBound("maximum"), descriptor.imageText("default"))
    }.orEmpty().sortedWith(compareBy({ settingsOrder.indexOf(it.key).takeIf { n -> n >= 0 } ?: Int.MAX_VALUE }, { it.key }))

    fun tariffs(value: JsonElement?): List<ImageTariff> = value?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { element ->
            val line = element.imageObject() ?: return@mapNotNull null
            val amount = line.imageNumber("cost_usd") ?: return@mapNotNull null
            val conditions = listOf("variant", "resolution", "size", "quality", "aspect_ratio").mapNotNull { key ->
                line.imageText(key)?.let { key to it }
            }.toMap()
            ImageTariff(line.imageText("billable") ?: return@mapNotNull null,
                line.imageText("unit") ?: return@mapNotNull null, amount,
                if (line.has("quantity")) line.imageNumber("quantity")?.takeIf { it > 0 } ?: return@mapNotNull null else 1.0, "USD", conditions)
        }.orEmpty()

    fun tariffsComplete(value: JsonElement?): Boolean = value?.takeIf { it.isJsonArray }?.asJsonArray
        ?.let { it.size() > 0 && tariffs(value).size == it.size() } == true

    fun catalog(body: String, sourceUrl: String): List<ImageModelMetadata> {
        val root = imageJson(body) ?: throw IllegalArgumentException("The provider's model list is not valid JSON")
        val data = root.imageArray("data") ?: throw IllegalArgumentException("The provider returned no model list")
        return data.mapNotNull { element ->
            val model = element.imageObject() ?: return@mapNotNull null
            val id = model.imageText("id") ?: return@mapNotNull null
            val modalities = model.get("architecture").imageObject()?.imageStrings("output_modalities")
            if (modalities != null && modalities.isNotEmpty() && "image" !in modalities) return@mapNotNull null
            if (model.get("capabilities").imageObject()?.get("image_generation")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == false) return@mapNotNull null
            ImageModelMetadata(id, parameters(model.get("supported_parameters").imageObject()),
                tariffs = tariffs(model.get("pricing")), sourceUrl = sourceUrl,
                tariffsComplete = tariffsComplete(model.get("pricing")))
        }
    }

    fun endpoints(body: String, model: ImageModelMetadata): ImageModelMetadata {
        val parsed = imageJson(body) ?: return model.withoutEndpointEvidence()
        val root = parsed.get("data").imageObject() ?: parsed
        if (root.imageText("id") != model.id) return model.withoutEndpointEvidence()
        val data = root.imageArray("endpoints") ?: return model.withoutEndpointEvidence()
        val endpoints = data.map { element ->
            // A partial route list cannot prove that every possible serving route
            // accepts these options or charges these rates.
            val endpoint = element.imageObject() ?: return model.withoutEndpointEvidence()
            ImageServingMetadata(endpoint.imageText("provider_name"), endpoint.imageText("provider_slug"),
                parameters(endpoint.get("supported_parameters").imageObject()), tariffs(endpoint.get("pricing")),
                tariffsComplete(endpoint.get("pricing")))
        }
        // Without pinning a serving provider, expose only parameters all routes support.
        val common = endpoints.flatMap { it.parameters }.distinctBy { it.key }.mapNotNull { parameter ->
            val matching = endpoints.map { it.parameters.firstOrNull { p -> p.key == parameter.key } }
            if (matching.isEmpty() || matching.any { it == null }) return@mapNotNull null
            val present = matching.filterNotNull()
            if (present.any { it.type != parameter.type }) return@mapNotNull null
            val values = parameter.values.filter { value -> present.all { value in it.values } }
            if (parameter.type == ImageParameterType.ENUM && values.isEmpty()) return@mapNotNull null
            val minimum = present.mapNotNull { it.minimum }.maxOrNull()
            val maximum = present.mapNotNull { it.maximum }.minOrNull()
            if (minimum != null && maximum != null && minimum > maximum) return@mapNotNull null
            parameter.copy(values = values, minimum = minimum, maximum = maximum,
                defaultValue = parameter.defaultValue?.takeIf { value -> present.all { it.defaultValue == value } })
        }
        if (endpoints.isEmpty()) return model.withoutEndpointEvidence()
        val catalogFormat = model.parameters.firstOrNull { it.key == "output_format" }
        val explicitFormatRequired = endpoints.any { endpoint ->
            val format = endpoint.parameters.firstOrNull { it.key == "output_format" } ?: catalogFormat
            format?.let { field ->
                field.defaultValue?.let { !ImageFormat.supportsOutputName(it) }
                    ?: field.values.any { !ImageFormat.supportsOutputName(it) }
            } == true
        }
        return model.copy(parameters = common, tariffs = emptyList(),
            tariffsComplete = true, endpointRecords = endpoints, requiresExplicitOutputFormat = explicitFormatRequired)
    }
}

/** Published OpenAI model documents identify exact IDs, image support and metering independently. */
object OpenAiImageMetadataParser {
    fun candidates(index: String): List<String> = index.lineSequence().filter {
        it.substringAfter("):", "").contains("image", ignoreCase = true)
    }.mapNotNull { Regex("\\(/api/docs/models/([^/)]+)\\.md\\)").find(it)?.groupValues?.get(1) }.distinct().toList()

    fun model(document: String, requestedId: String, sourceUrl: String): ImageModelMetadata? {
        val id = Regex("Model ID: `([^`]+)`").find(document)?.groupValues?.get(1) ?: return null
        val snapshots = document.substringAfter("## Snapshots\n", "").substringBefore("\n## ")
        val ids = setOf(id) + Regex("`([^`]+)`").findAll(snapshots).map { it.groupValues[1] }.toSet()
        if (requestedId !in ids || !document.lineSequence().any {
                val cells = it.trim().trim('|').split('|').map(String::trim)
                cells.contains("`v1/images/generations`") && cells.lastOrNull() == "Supported"
            }) return null
        val pricing = document.substringAfter("## Pricing\n", "").substringBefore("\n## ")
        val tariffs = mutableListOf<ImageTariff>()
        val sizes = linkedSetOf<String>()
        val qualities = linkedSetOf<String>()
        var modality = ""
        pricing.lineSequence().forEach { line ->
            if (line.startsWith("### ")) modality = line.removePrefix("### ").trim().lowercase()
            val cells = line.trim().takeIf { it.startsWith('|') }?.trim('|')?.split('|')?.map(String::trim)
                ?: return@forEach
            if (cells.size != 3) return@forEach
            if (modality == "image generation") {
                if (cells[0] == "Quality") qualities += cells[1].lowercase()
                if (cells[0].matches(Regex("[0-9]+x[0-9]+"))) sizes += cells[0]
                // The per-image tables are illustrative estimates for token-billed models.
                // They never substitute for the real token count and token rate.
                return@forEach
            }
            val amount = cells[1].removePrefix("$").toDoubleOrNull()?.takeIf { cells[1].startsWith('$') && it.isFinite() && it >= 0 }
                ?: return@forEach
            val basis = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*([km])?\\s*(tokens?|images?)").matchEntire(cells[2]) ?: return@forEach
            val quantity = basis.groupValues[1].toDouble() * when (basis.groupValues[2].lowercase()) {
                "k" -> 1e3; "m" -> 1e6; else -> 1.0
            }
            val component = when (modality) {
                "text tokens" -> "text"
                "image tokens" -> "image"
                else -> return@forEach
            }
            val metric = when (cells[0].lowercase()) {
                "input" -> "input"
                "cached input" -> "cached_input"
                "output" -> "output"
                else -> return@forEach
            }
            tariffs += ImageTariff("${component}_$metric", basis.groupValues[3].lowercase().removeSuffix("s"), amount, quantity, "USD")
        }
        val parameters = buildList {
            if (sizes.isNotEmpty()) add(ImageParameter("size", ImageParameterType.ENUM, sizes.toList()))
            if (qualities.isNotEmpty()) add(ImageParameter("quality", ImageParameterType.ENUM, qualities.toList()))
        }
        return ImageModelMetadata(requestedId, parameters, tariffs, sourceUrl, resolvedIds = ids,
            outputModalities = document.lineSequence().firstOrNull { it.startsWith("- Output modalities:") }
                ?.substringAfter(":")?.split(',')?.map { it.trim() }?.toSet().orEmpty())
    }
}

/** The latest dimension control wins, without changing unrelated saved options. */
object ImageDimensionSettings {
    private val pixelPattern = Regex("([0-9]+)[xX*]([0-9]+)")

    fun change(saved: Map<String, String>, key: String, value: String?, metadata: ImageModelMetadata?): Map<String, String> {
        val result = saved.toMutableMap()
        if (value == null) { result.remove(key); return result }
        val tiers = metadata?.parameters?.firstOrNull { it.key == "resolution" }?.values.orEmpty()
        when (key) {
            "size" -> {
                result.remove("resolution")
                if (value !in tiers) result.remove("aspect_ratio")
            }
            "resolution", "aspect_ratio" -> {
                val size = result.remove("size")
                if (key == "aspect_ratio" && result["resolution"] == null && size != null && size in tiers)
                    result["resolution"] = size
            }
        }
        result[key] = value
        return result
    }

    fun hasConflict(options: Map<String, String>): Boolean {
        val pixels = pixelPattern.matchEntire(options["size"].orEmpty()) ?: return false
        if (options["resolution"] != null) return true
        val ratio = options["aspect_ratio"]?.split(':') ?: return false
        val width = pixels.groupValues[1].toDouble()
        val height = pixels.groupValues[2].toDouble()
        val x = ratio.getOrNull(0)?.toDoubleOrNull() ?: return true
        val y = ratio.getOrNull(1)?.toDoubleOrNull() ?: return true
        return width * y != height * x
    }
}

/** Maps app-owned /imagine shape shorthand to an actual value the model publishes. */
object ImageRequestOptions {
    private val dimensions = listOf("aspect_ratio", "size", "resolution")

    fun resolve(request: ImageGenerationRequest, metadata: ImageModelMetadata?): Map<String, String> {
        val legacyShape = if (request.shape == ImageShape.AUTOMATIC && request.defaultShape != ImageShape.AUTOMATIC &&
            request.parameters.keys.none { it in dimensions })
            runCatching { resolve(request.copy(shape = request.defaultShape, quality = ImageQuality.AUTOMATIC,
                parameters = emptyMap(), defaultShape = ImageShape.AUTOMATIC, defaultQuality = ImageQuality.AUTOMATIC), metadata) }.getOrDefault(emptyMap()) else emptyMap()
        val legacyQuality = if (request.quality == ImageQuality.AUTOMATIC && request.defaultQuality != ImageQuality.AUTOMATIC)
            runCatching { resolve(request.copy(shape = ImageShape.AUTOMATIC, quality = request.defaultQuality,
                parameters = emptyMap(), defaultShape = ImageShape.AUTOMATIC, defaultQuality = ImageQuality.AUTOMATIC), metadata) }.getOrDefault(emptyMap()) else emptyMap()
        val options = (legacyShape + legacyQuality + request.parameters).toMutableMap()
        if (request.shape != ImageShape.AUTOMATIC) {
            val selected = dimensions.mapNotNull { key -> metadata?.parameters?.firstOrNull { it.key == key } }
                .firstNotNullOfOrNull { field ->
                    field.values.firstOrNull { value ->
                        val parts = value.split(Regex("[x:*]"))
                        val width = parts.getOrNull(0)?.toDoubleOrNull()
                        val height = parts.getOrNull(1)?.toDoubleOrNull()
                        width != null && height != null && when (request.shape) {
                            ImageShape.SQUARE -> width == height
                            ImageShape.LANDSCAPE -> width > height
                            ImageShape.PORTRAIT -> width < height
                            ImageShape.AUTOMATIC -> false
                        }
                    }?.let { field to it }
                } ?: throw ImageGenerationException(ImageErrorCause.UNSUPPORTED_OPTION,
                    "the selected model does not publish a supported value for this shape")
            val (field, value) = selected
            // An explicit pixel size takes precedence over both other controls.
            // Aspect ratio combines with a resolution tier, but not a saved pixel size.
            if (field.key == "aspect_ratio") {
                val size = options.remove("size")
                val resolution = metadata?.parameters?.firstOrNull { it.key == "resolution" }
                // Preserve a size shorthand only when the provider also publishes it
                // as a resolution tier; opaque size labels never imply a tier.
                if (options["resolution"] == null && size != null && size in resolution?.values.orEmpty())
                    options["resolution"] = size
            } else dimensions.forEach(options::remove)
            options[field.key] = value
        }
        if (request.quality != ImageQuality.AUTOMATIC) {
            val field = metadata?.parameters?.firstOrNull { it.key == "quality" }
            val value = field?.values?.firstOrNull { it.equals(request.quality.storedValue, true) }
                ?: throw ImageGenerationException(ImageErrorCause.UNSUPPORTED_OPTION, "the selected model does not publish this quality")
            options[field.key] = value
        }
        options.forEach { (key, value) ->
            val field = metadata?.parameters?.firstOrNull { it.key == key }
            if (field == null || !field.accepts(value)) throw ImageGenerationException(
                ImageErrorCause.UNSUPPORTED_OPTION, "the selected model does not accept the saved $key setting")
        }
        if (ImageDimensionSettings.hasConflict(options)) throw ImageGenerationException(
            ImageErrorCause.UNSUPPORTED_OPTION, "choose a pixel size or resolution and aspect ratio, then try again")
        val format = metadata?.parameters?.firstOrNull { it.key == "output_format" }
        val chosenFormat = options["output_format"] ?: format?.defaultValue
        val unknownDefaultMayBeUnusable = options["output_format"] == null &&
            (metadata?.requiresExplicitOutputFormat == true || (chosenFormat == null && format?.type == ImageParameterType.ENUM &&
                format.values.any { !ImageFormat.supportsOutputName(it) }))
        if (metadata?.hasDisplayableOutput() == false ||
            chosenFormat?.let { !ImageFormat.supportsOutputName(it) } == true || unknownDefaultMayBeUnusable) {
            throw ImageGenerationException(ImageErrorCause.UNSUPPORTED_OPTION,
                "choose an output format this app can display before generating")
        }
        val routes = listOf(metadata?.parameters.orEmpty()) + metadata?.endpointRecords.orEmpty().map { it.parameters }
        if (routes.any { parameters ->
                val background = options["background"] ?: parameters.firstOrNull { it.key == "background" }?.defaultValue
                val output = options["output_format"] ?: parameters.firstOrNull { it.key == "output_format" }?.defaultValue
                background.equals("transparent", ignoreCase = true) &&
                    output?.let { ImageFormat.fromOutputName(it)?.supportsTransparency } == false
            }) {
            throw ImageGenerationException(ImageErrorCause.UNSUPPORTED_OPTION,
                "choose an output format that supports transparency or change the background")
        }
        return options
    }
}
