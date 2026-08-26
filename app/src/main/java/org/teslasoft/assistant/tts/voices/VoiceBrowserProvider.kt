package org.teslasoft.assistant.tts.voices

interface VoiceBrowserProvider {
    val id: String
    val displayName: String
    val exposesLocationFilter: Boolean

    fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit)
    fun activeVoiceId(): String?
    fun activate(voice: BrowserVoice)
    fun preview(voice: BrowserVoice, sampleText: String, onFailure: (String) -> Unit, onCatalogChanged: () -> Unit = {})
    fun download(voice: BrowserVoice, onFailure: (String) -> Unit, onCatalogChanged: () -> Unit = {})
    fun stopPreview()
    fun shutdown()
}

/** Keeps browsing state independent from the active persisted provider/voice. */
class VoiceBrowserController(
    providers: List<VoiceBrowserProvider>,
    activeProviderId: String,
    private val decorateVoice: (BrowserVoice) -> BrowserVoice = { it }
) {
    private val providersById = providers.associateBy { it.id }
    private val filterStates = mutableMapOf<String, VoiceFilterState>()
    private val loadedVoicesByProviderId = mutableMapOf<String, List<BrowserVoice>>()
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
                onSuccess = { loadedVoices ->
                    val voices = loadedVoices.map(decorateVoice)
                    loadedVoicesByProviderId[requestedProviderId] = voices
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

    fun loadedVoice(providerId: String, voiceId: String?): BrowserVoice? =
        voiceId?.let { id -> loadedVoicesByProviderId[providerId]?.firstOrNull { it.providerVoiceId == id } }

    fun firstUsableLoadedVoice(): BrowserVoice? = loadedVoicesByProviderId.values
        .asSequence()
        .flatten()
        .firstOrNull { !VoiceSelectionExitPolicy.requiresUnavailableVoiceWarning(it) && it.canPreview }

    fun select(voice: BrowserVoice) = providersById.getValue(voice.providerId).activate(voice)

    fun preview(voice: BrowserVoice, sampleText: String, onFailure: (String) -> Unit, onChanged: () -> Unit = {}) =
        providersById.getValue(voice.providerId).preview(voice, sampleText, onFailure) { load(onChanged) }

    fun download(voice: BrowserVoice, onFailure: (String) -> Unit, onChanged: () -> Unit = {}) =
        providersById.getValue(voice.providerId).download(voice, onFailure) { load(onChanged) }

    fun updateVoice(updated: BrowserVoice) {
        val state = loadState as? VoiceLoadState.Ready ?: return
        loadState = VoiceLoadState.Ready(state.voices.map { voice ->
            if (voice.providerId == updated.providerId && voice.providerVoiceId == updated.providerVoiceId) updated else voice
        })
        loadedVoicesByProviderId[updated.providerId] = loadedVoicesByProviderId[updated.providerId].orEmpty().map { voice ->
            if (voice.providerVoiceId == updated.providerVoiceId) updated else voice
        }
        VoiceBrowserFilters.sanitize(filterState, filterDefinitions())
    }

    fun shutdown() = providersById.values.forEach(VoiceBrowserProvider::shutdown)
}
