package org.teslasoft.assistant.tts.voices

interface VoiceBrowserProvider {
    val id: String
    val displayName: String
    val exposesLocationFilter: Boolean

    fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit)
    fun activeVoiceId(): String?
    fun activate(voice: BrowserVoice)
    /**
     * [onPlaybackChanged] reports the id of the voice currently sounding, or
     * null when preview playback has started, finished, or been stopped, so the
     * UI can flip a row between Preview and Stop.
     */
    fun preview(
        voice: BrowserVoice,
        sampleText: String,
        onFailure: (String) -> Unit,
        onCatalogChanged: () -> Unit = {},
        onPlaybackChanged: (String?) -> Unit = {}
    )
    fun download(voice: BrowserVoice, onFailure: (String) -> Unit, onCatalogChanged: () -> Unit = {})
    fun stopPreview()
    fun cancelLoad() {}
    fun shutdown()
}

/** Keeps browsing state independent from the active persisted provider/voice. */
class VoiceBrowserController(
    providers: List<VoiceBrowserProvider>,
    activeProviderId: String,
    private val decorateVoice: (BrowserVoice) -> BrowserVoice = { it },
    /** Supplies each provider's remembered filters the first time they are shown. */
    private val initialFilterState: (String) -> VoiceFilterState = { VoiceFilterState() }
) {
    private var providersById = providers.associateBy { it.id }
    private val filterStates = mutableMapOf<String, VoiceFilterState>()
    private val loadedVoicesByProviderId = mutableMapOf<String, List<BrowserVoice>>()
    private var loadGeneration = 0L

    var browsedProviderId: String = activeProviderId.takeIf(providersById::containsKey)
        ?: providers.first().id
        private set

    var loadState: VoiceLoadState = VoiceLoadState.Loading
        private set

    val provider: VoiceBrowserProvider get() = providersById.getValue(browsedProviderId)
    val availableProviders: List<VoiceBrowserProvider> get() = providersById.values.toList()
    val filterState: VoiceFilterState get() = filterStates.getOrPut(browsedProviderId) { initialFilterState(browsedProviderId) }

    fun browse(providerId: String, onChanged: () -> Unit) {
        if (!providersById.containsKey(providerId)) return
        provider.stopPreview()
        provider.cancelLoad()
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
                onFailure = { VoiceLoadState.Failed(it.message ?: "Voices could not be loaded.", it) }
            )
            onChanged()
        }
    }

    fun visibleVoices(): List<BrowserVoice> = when (val state = loadState) {
        is VoiceLoadState.Ready -> VoiceBrowserFilters.apply(forDisplay(state.voices), filterState)
        else -> emptyList()
    }

    fun filterDefinitions(): List<VoiceFilterDefinition> = when (val state = loadState) {
        is VoiceLoadState.Ready -> VoiceBrowserFilters.definitions(forDisplay(state.voices))
        else -> emptyList()
    }

    /** Applies the display policy for voices that still require a download. */
    private fun forDisplay(voices: List<BrowserVoice>): List<BrowserVoice> =
        if (SHOW_VOICES_REQUIRING_DOWNLOAD) voices else voices.filterNot(BrowserVoice::downloadable)

    fun loadedVoice(providerId: String, voiceId: String?): BrowserVoice? =
        voiceId?.let { id -> loadedVoicesByProviderId[providerId]?.firstOrNull { it.providerVoiceId == id } }

    fun firstUsableLoadedVoice(): BrowserVoice? = loadedVoicesByProviderId.values
        .asSequence()
        .flatten()
        .firstOrNull { !VoiceSelectionExitPolicy.requiresUnavailableVoiceWarning(it) && it.canPreview }

    fun select(voice: BrowserVoice) = providersById.getValue(voice.providerId).activate(voice)

    fun preview(
        voice: BrowserVoice,
        sampleText: String,
        onFailure: (String) -> Unit,
        onChanged: () -> Unit = {},
        onPlaybackChanged: (String?) -> Unit = {}
    ) = providersById.getValue(voice.providerId)
        .preview(voice, sampleText, onFailure, { load(onChanged) }, onPlaybackChanged)

    fun stopPreview() = provider.stopPreview()

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

    /** Stable IDs preserve browsing/filter identity across source additions and edits. */
    fun replaceProviders(providers: List<VoiceBrowserProvider>, onChanged: () -> Unit) {
        require(providers.isNotEmpty() && providers.map { it.id }.distinct().size == providers.size)
        suspendLoads()
        val replacement = providers.associateBy { it.id }
        providersById.values.filter { replacement[it.id] !== it }.forEach(VoiceBrowserProvider::shutdown)
        loadedVoicesByProviderId.keys.removeAll { providersById[it] !== replacement[it] }
        providersById = replacement
        if (browsedProviderId !in replacement) browsedProviderId = providers.first().id
        load(onChanged)
    }

    fun suspendLoads() {
        loadGeneration++
        providersById.values.forEach { it.stopPreview(); it.cancelLoad() }
    }

    fun shutdown() { suspendLoads(); providersById.values.forEach(VoiceBrowserProvider::shutdown) }

    companion object {
        // Voices whose on-device data is not installed are hidden from the list
        // for now: Android offers no reliable way to fetch a single voice, and
        // the system voice-data screen it would open only manages whole
        // languages, so the Download button led nowhere useful. All download
        // code is left in place — flip this to true to show those voices (and
        // their Download button) again without recoding anything.
        const val SHOW_VOICES_REQUIRING_DOWNLOAD = false
    }
}
