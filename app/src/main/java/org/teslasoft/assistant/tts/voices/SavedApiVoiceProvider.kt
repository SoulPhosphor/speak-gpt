package org.teslasoft.assistant.tts.voices

import android.content.Context
import kotlinx.coroutines.*
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.tts.SavedTtsSource
import org.teslasoft.assistant.tts.api.*

/** Every registration is one saved source, never an endpoint-wide or guessed voice catalog. */
class SavedApiVoiceProvider(
    context: Context,
    val source: SavedTtsSource,
    endpointName: String,
    private val preferences: Preferences,
    private val onFailure: (TtsFailure, () -> Unit) -> Unit
) : VoiceBrowserProvider {
    override val id = source.sourceId
    override var displayName = sourceLabel(endpointName, source.modelId, source.routing)
        private set
    override val exposesLocationFilter = false
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = TtsRequestGate()
    private var loadJob: Job? = null
    private val playback = TtsPlayback(app)

    override fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit) {
        cancelLoad()
        val token = gate.begin()
        loadJob = scope.launch {
            val result = try {
                val voices = withContext(Dispatchers.IO) {
                    val resolved = TtsAndroidServices.resolver(app).saved(id, "").getOrThrow()
                    val catalog = TtsDiscoveryClient().voices(resolved, token)
                    TtsFailures.voiceDiscovery(resolved, catalog)?.let { throw TtsException(it) }
                    (catalog as TtsVoiceCatalog.Known).voices.map { voice ->
                        BrowserVoice(id, voice.id, voice.displayName, providerModelId = source.modelId,
                            language = voice.language, region = voice.region, gender = voice.gender,
                            accent = voice.accent, style = voice.style, requiresNetwork = true, canPreview = true)
                    }
                }
                Result.success(voices)
            } catch (cancel: java.util.concurrent.CancellationException) { return@launch }
            catch (error: Exception) {
                val failure = (error as? TtsException)?.failure?.copy(operation = TtsOperation.VOICES)
                    ?: TtsFailure(TtsOperation.VOICES,
                        TtsTarget(source.endpointId, source.modelId, source.routing, id), "", TtsFailureKind.UNKNOWN)
                Result.failure(TtsException(failure))
            }
            token.deliver { onResult(result) }
        }
    }

    /** Optional names improve labels; unavailable metadata never hides a saved source. */
    fun loadLabel(onChanged: () -> Unit) {
        scope.launch {
            val token = labelGate.begin()
            try {
                val label = withContext(Dispatchers.IO) {
                    val resolved = TtsAndroidServices.resolver(app).saved(id, "").getOrThrow()
                    discoverLabel(resolved, token)
                }
                token.deliver { displayName = label; onChanged() }
            } catch (_: Exception) { /* Display IDs when optional labels cannot be loaded. */ }
        }
    }
    private val labelGate = TtsRequestGate()

    override fun activeVoiceId(): String? = preferences.getSelectedTtsVoice()
        ?.takeIf { it.sourceId == id }?.voiceId

    // Activation belongs to TtsSelectionService so history and identity are committed together.
    override fun activate(voice: BrowserVoice) = Unit

    override fun preview(voice: BrowserVoice, sampleText: String, onFailure: (String) -> Unit,
        onCatalogChanged: () -> Unit, onPlaybackChanged: (String?) -> Unit) {
        playback.play(id, voice.providerVoiceId, sampleText, TtsOperation.PREVIEW,
            onPlayer = { if (it == null) onPlaybackChanged(null) },
            onStart = { onPlaybackChanged(voice.providerVoiceId) },
            onDone = { onPlaybackChanged(null) },
            onFailure = { failure -> this.onFailure(failure) {
                preview(voice, sampleText, onFailure, onCatalogChanged, onPlaybackChanged)
            } })
    }

    override fun download(voice: BrowserVoice, onFailure: (String) -> Unit, onCatalogChanged: () -> Unit) = Unit
    override fun stopPreview() = playback.stop()
    override fun cancelLoad() { gate.cancel(); loadJob?.cancel(); labelGate.cancel() }
    override fun shutdown() { cancelLoad(); playback.shutdown(); scope.cancel() }

    companion object {
        fun discoverLabel(source: ResolvedTtsSource, token: TtsRequestToken): String {
            val client = TtsDiscoveryClient()
            val name = try { client.models(source, token).models.firstOrNull { it.id == source.target.modelId }?.name }
                catch (cancel: java.util.concurrent.CancellationException) { throw cancel }
                catch (_: Exception) { null }
            val requested = TtsRouting.requestedProvider(source.target.routing)
            val provider = if (requested == null) null else try {
                client.providers(source, token).providers.firstOrNull { it.id == requested }?.name
            } catch (cancel: java.util.concurrent.CancellationException) { throw cancel }
            catch (_: Exception) { null }
            return sourceLabel(source.endpoint.label, name ?: source.target.modelId, source.target.routing, provider)
        }

        fun sourceLabel(endpointName: String, modelName: String,
            routing: org.teslasoft.assistant.preferences.tts.TtsRoutingSettings, providerName: String? = null): String =
            listOf(endpointName, modelName, providerName ?: TtsRouting.requestedProvider(routing) ?: "Automatic")
                .joinToString(" · ")
    }
}
