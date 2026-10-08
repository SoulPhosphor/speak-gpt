package org.teslasoft.assistant.imagegen

/** Only precise structured rejections are reusable. No prompts, error text or credentials are retained. */
data class ImageOptionRejection(
    val parameter: String,
    /** Null means the parameter itself is unsupported. Otherwise retain the exact request context. */
    val selection: Map<String, String>? = null
) {
    fun matches(options: Map<String, String>): Boolean =
        if (selection == null) parameter in options else selection == options

    companion object {
        fun fromError(status: Int, body: String, request: ImageGenerationRequest): ImageOptionRejection? {
            if (status != 400 && status != 422) return null
            val error = imageJson(body)?.get("error").imageObject() ?: return null
            val parameter = error.imageText("param") ?: error.imageText("parameter") ?: return null
            if (parameter !in request.parameters) return null
            return when (error.imageText("code")) {
                "unsupported_parameter" -> ImageOptionRejection(parameter)
                // A value can be unsupported only in combination with another setting.
                // A named value error therefore does not blacklist it in every context.
                "unsupported_value" -> ImageOptionRejection(parameter, request.parameters.toMap())
                else -> null
            }
        }
    }
}

enum class ImageSettingAvailability { SUPPORTED, UNSUPPORTED_PARAMETER, UNSUPPORTED_VALUE, UNVERIFIED }
enum class ImageSettingControl { DROPDOWN, BOOLEAN, NUMBER, TEXT, STATUS }

/** The UI reads the same descriptors and scoped evidence as request preflight. */
data class ImageSettingState(
    val key: String,
    val selected: String?,
    val parameter: ImageParameter?,
    val availability: ImageSettingAvailability,
    val choices: List<String> = emptyList()
) {
    val control: ImageSettingControl get() = when {
        parameter == null || availability == ImageSettingAvailability.UNSUPPORTED_PARAMETER -> ImageSettingControl.STATUS
        parameter.type == ImageParameterType.BOOLEAN -> ImageSettingControl.BOOLEAN
        parameter.values.isNotEmpty() -> ImageSettingControl.DROPDOWN
        parameter.type == ImageParameterType.STRING -> ImageSettingControl.TEXT
        else -> ImageSettingControl.NUMBER
    }

    val allowsAutomatic: Boolean get() = parameter?.required != true || parameter.defaultValue != null

    /** Three states preserve omission/defaults while using the existing Material checkbox. */
    fun nextBooleanSelection(): String? {
        val values = (if (allowsAutomatic) listOf<String?>(null) else emptyList()) + choices
        if (values.isEmpty()) return null
        val index = values.indexOf(selected)
        return values[if (index < 0) 0 else (index + 1) % values.size]
    }
}

object ImageSettingSupport {
    fun rows(saved: Map<String, String>, metadata: ImageModelMetadata?,
        rejections: List<ImageOptionRejection> = emptyList()): List<ImageSettingState> {
        val parameters = metadata?.parameters.orEmpty().associateBy { it.key }
        // Rejection contexts contain dispatched options, so exclude other stale saved fields here too.
        val effective = ImageRequestOptions.effectiveParameters(saved.filter { (key, value) -> parameters[key]?.accepts(value) == true &&
            rejections.none { it.parameter == key && it.selection == null } }, metadata)
        return (parameters.keys + saved.keys).map { key ->
            val parameter = parameters[key]
            val selected = saved[key]
            val unavailable = rejections.any { it.parameter == key && it.selection == null }
            val published = if (parameter?.type == ImageParameterType.BOOLEAN)
                listOf("true", "false").filter(parameter::accepts) else parameter?.selectableValues().orEmpty()
            val choices = published.filter { value -> rejections.none {
                it.parameter == key && it.selection != null && it.matches(effective + (key to value))
            } }
            val availability = when {
                unavailable -> ImageSettingAvailability.UNSUPPORTED_PARAMETER
                parameter == null -> if (metadata?.settingsVerified == true)
                    ImageSettingAvailability.UNSUPPORTED_PARAMETER else ImageSettingAvailability.UNVERIFIED
                (selected != null && (!parameter.accepts(selected) ||
                    (key == "output_format" && !ImageFormat.supportsOutputName(selected)))) ||
                    rejections.any { it.parameter == key && it.selection != null && it.matches(effective) } ->
                    ImageSettingAvailability.UNSUPPORTED_VALUE
                else -> ImageSettingAvailability.SUPPORTED
            }
            ImageSettingState(key, selected, parameter, availability, choices)
        }
    }
}
