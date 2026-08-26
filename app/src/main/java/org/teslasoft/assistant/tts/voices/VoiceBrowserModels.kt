package org.teslasoft.assistant.tts.voices

import java.util.Locale

/** Provider-neutral voice information rendered by the Voice Browser. */
data class BrowserVoice(
    val providerId: String,
    val providerVoiceId: String,
    val displayName: String,
    /** Immutable label assigned by the app/provider before a user override. */
    val originalDisplayName: String = displayName,
    val providerModelId: String? = null,
    val language: VoiceFacetValue? = null,
    val region: VoiceFacetValue? = null,
    val gender: VoiceFacetValue? = null,
    /** Provider-supplied gender retained even when [gender] is user-overridden. */
    val providerGender: VoiceFacetValue? = gender,
    val userAssignedGender: VoiceFacetValue? = null,
    val quality: VoiceFacetValue? = null,
    val accent: VoiceFacetValue? = null,
    val style: VoiceFacetValue? = null,
    val requiresNetwork: Boolean? = null,
    val installedLocally: Boolean? = null,
    val downloadable: Boolean = false,
    val downloadInProgress: Boolean = false,
    val downloadedRecently: Boolean = false,
    val canPreview: Boolean = false
)

data class LastKnownGoodVoiceSelection(
    val providerId: String,
    val providerVoiceId: String,
    val providerModelId: String? = null
)

object VoiceSelectionExitPolicy {
    fun requiresUnavailableVoiceWarning(voice: BrowserVoice?): Boolean =
        voice?.providerId == "google" && voice.installedLocally == false
}

data class VoiceFacetValue(val id: String, val label: String)

enum class VoiceFacet(val label: String) {
    LANGUAGE("Language"),
    REGION("Region"),
    GENDER("Gender"),
    QUALITY("Quality"),
    ACCENT("Accent"),
    STYLE("Style")
}

data class VoiceFilterDefinition(
    val facet: VoiceFacet,
    val options: List<VoiceFacetValue>
)

enum class VoiceLocation { ALL, ON_DEVICE, NETWORK }

data class VoiceFilterState(
    var location: VoiceLocation = VoiceLocation.ALL,
    val selectedFacetValues: MutableMap<VoiceFacet, String> = mutableMapOf()
)

sealed interface VoiceLoadState {
    data object Loading : VoiceLoadState
    data class Ready(val voices: List<BrowserVoice>) : VoiceLoadState
    data class Failed(val message: String) : VoiceLoadState
}

object VoicePreviewText {
    const val DEFAULT = "Hello. This is a preview of this voice."
}

object VoiceQualityLabels {
    fun fromAndroidQuality(value: Int): VoiceFacetValue? = when (value) {
        100 -> VoiceFacetValue("very_low", "Very Low")
        200 -> VoiceFacetValue("low", "Low")
        300 -> VoiceFacetValue("normal", "Normal")
        400 -> VoiceFacetValue("high", "High")
        500 -> VoiceFacetValue("very_high", "Very High")
        else -> null
    }
}

object GoogleVoiceMetadata {
    private val explicitGenderPattern = Regex(
        "(?:^|[#_\\-=])(female|male)(?:$|[#_\\-=0-9])",
        RegexOption.IGNORE_CASE
    )

    fun explicitGender(metadata: List<String>): VoiceFacetValue? {
        val match = metadata.firstNotNullOfOrNull {
            explicitGenderPattern.find(it)?.groupValues?.get(1)
        }?.lowercase(Locale.ROOT) ?: return null
        return VoiceFacetValue(match, match.replaceFirstChar { it.titlecase(Locale.getDefault()) })
    }

    fun isDownloadRequired(requiresNetwork: Boolean, features: Set<String>, notInstalledFeature: String): Boolean =
        !requiresNetwork && features.any { it.equals(notInstalledFeature, ignoreCase = true) }
}

object VoiceBrowserFilters {
    fun definitions(voices: List<BrowserVoice>): List<VoiceFilterDefinition> =
        VoiceFacet.entries.mapNotNull { facet ->
            val values = voices.mapNotNull { it.valueFor(facet) }
                .distinctBy { it.id }
                .sortedBy { it.label.lowercase() }
            values.takeIf { it.isNotEmpty() }?.let { VoiceFilterDefinition(facet, it) }
        }

    fun apply(voices: List<BrowserVoice>, state: VoiceFilterState): List<BrowserVoice> =
        voices.filter { voice ->
            val locationMatches = when (state.location) {
                VoiceLocation.ALL -> true
                VoiceLocation.ON_DEVICE -> voice.requiresNetwork == false
                VoiceLocation.NETWORK -> voice.requiresNetwork == true
            }
            locationMatches && state.selectedFacetValues.all { (facet, selectedId) ->
                voice.valueFor(facet)?.id == selectedId
            }
        }

    fun sanitize(state: VoiceFilterState, definitions: List<VoiceFilterDefinition>) {
        val supported = definitions.associate { it.facet to it.options.map(VoiceFacetValue::id).toSet() }
        state.selectedFacetValues.entries.removeAll { (facet, value) -> value !in (supported[facet] ?: emptySet()) }
    }

    private fun BrowserVoice.valueFor(facet: VoiceFacet): VoiceFacetValue? = when (facet) {
        VoiceFacet.LANGUAGE -> language
        VoiceFacet.REGION -> region
        VoiceFacet.GENDER -> gender
        VoiceFacet.QUALITY -> quality
        VoiceFacet.ACCENT -> accent
        VoiceFacet.STYLE -> style
    }
}
