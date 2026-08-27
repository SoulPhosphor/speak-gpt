package org.teslasoft.assistant.preferences.tts

/** Routing belongs to this speech source, never to a chat favorite. */
enum class TtsRoutingMode(val key: String) {
    AUTOMATIC("automatic"), PREFERRED("preferred"), ONLY("only")
}

data class TtsRoutingSettings(
    val mode: TtsRoutingMode = TtsRoutingMode.AUTOMATIC,
    val selectedProvider: String = "",
    val providerOrder: List<String> = emptyList(),
    val allowFallbacks: Boolean = true
) {
    internal fun validate() {
        require(providerOrder.all(String::isNotBlank) && providerOrder.distinct().size == providerOrder.size)
        require(mode != TtsRoutingMode.ONLY || selectedProvider.isNotBlank())
    }

    /** Mode/fallback/priority changes alone do not create a different provider combination. */
    internal fun providerCombination(): Set<String> = buildSet {
        addAll(providerOrder)
        if (selectedProvider.isNotBlank()) add(selectedProvider)
    }
}

data class SavedTtsSource(
    val id: String,
    val endpointId: String,
    val modelId: String,
    val routing: TtsRoutingSettings = TtsRoutingSettings()
) {
    /** Stable Voice Browser provider key, independent of labels or routing edits. */
    val sourceId: String get() = "api-tts:$id"

    internal fun validate() {
        require(id.isNotBlank() && endpointId.isNotBlank() && modelId.isNotBlank())
        routing.validate()
    }
}
