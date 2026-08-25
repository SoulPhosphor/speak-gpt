package org.teslasoft.assistant.tts.voices

interface VoiceBrowserProvider {
    val id: String
    val displayName: String
    val exposesLocationFilter: Boolean

    fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit)
    fun activeVoiceId(): String?
    fun activate(voice: BrowserVoice)
    fun preview(voice: BrowserVoice, sampleText: String, onFailure: (String) -> Unit, onCatalogChanged: () -> Unit = {})
    fun download(voice: BrowserVoice, onFailure: (String) -> Unit)
    fun stopPreview()
    fun shutdown()
}

/** Keeps browsing state independent from the active persisted provider/voice. */
class VoiceBrowserController(
    providers: List<VoiceBrowserProvider>,
    activeProviderId: String
) {
    private val providersById = providers.associateBy { it.id }
    private val filterStates = mutableMapOf<String, VoiceFilterState>()
    private var loadGeneration = 0L

    var browsedProviderId: String = activeProviderId.takeIf(providersById::containsKey)
        ?: providers.first().id
        private set

    var loadState: VoiceLoadState = VoiceLoadState.Loading
        private set

    val provider: VoiceBrowserProvider get() = providersById.getValue(browsedProviderId)
    val availableProviders: List<VoiceBrowserProvider> = providers
    val filterState: VoiceFilterState get() = filterStates.getOrPut(browsedProviderId) { VoiceFilterState() }

    fun browse(providerId: String, onChanged: () -> Unit) {
        if (!providersById.containsKey(providerId)) return
        provider.stopPreview()
        browsedProviderId = providerId
        load(onChanged)
    }

    fun load(onChanged: () -> Unit) {
        val requestedProviderId = browsedProviderId
        val requestGeneration = ++loadGeneration
        loadState = VoiceLoadState.Loading
        onChanged()
        providersById.getValue(requestedProviderId).loadVoices { result ->
            if (requestGeneration != loadGeneration || requestedProviderId != browsedProviderId) {
                return@loadVoices
            }
            loadState = result.fold(
                onSuccess = { voices ->
                    val definitions = VoiceBrowserFilters.definitions(voices)
                    VoiceBrowserFilters.sanitize(filterState, definitions)
                    VoiceLoadState.Ready(voices)
                },
                onFailure = { VoiceLoadState.Failed(it.message ?: "Voices could not be loaded.") }
            )
            onChanged()
        }
    }

    fun visibleVoices(): List<BrowserVoice> = when (val state = loadState) {
        is VoiceLoadState.Ready -> VoiceBrowserFilters.apply(state.voices, filterState)
        else -> emptyList()
    }

    fun filterDefinitions(): List<VoiceFilterDefinition> = when (val state = loadState) {
        is VoiceLoadState.Ready -> VoiceBrowserFilters.definitions(state.voices)
        else -> emptyList()
    }

    fun select(voice: BrowserVoice) = providersById.getValue(voice.providerId).activate(voice)

    fun preview(voice: BrowserVoice, sampleText: String, onFailure: (String) -> Unit, onChanged: () -> Unit = {}) =
        providersById.getValue(voice.providerId).preview(voice, sampleText, onFailure) { load(onChanged) }

    fun download(voice: BrowserVoice, onFailure: (String) -> Unit) =
        providersById.getValue(voice.providerId).download(voice, onFailure)

    fun shutdown() = providersById.values.forEach(VoiceBrowserProvider::shutdown)
}
