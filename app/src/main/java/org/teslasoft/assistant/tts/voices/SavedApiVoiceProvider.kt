package org.teslasoft.assistant.tts.voices

import android.content.Context
import kotlinx.coroutines.*
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.tts.ManualTtsVoicesPreferences
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

    private val manualVoices = ManualTtsVoicesPreferences.getPreferences(app)
    /** The last discovery answer, reused when only the saved Voice IDs changed. */
    @Volatile private var lastDiscovery: Result<List<BrowserVoice>>? = null

    override fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit) =
        loadCatalog(true) { result -> onResult(result.map { it.voices }) }

    override fun loadCatalog(rediscover: Boolean, onResult: (Result<LoadedVoices>) -> Unit) {
        cancelLoad()
        val token = gate.begin()
        loadJob = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val discovered = lastDiscovery.takeUnless { rediscover } ?: discover(token)
                    lastDiscovery = discovered
                    val saved = manualVoices.voicesFor(source.id)
                    merge(id, source.modelId, discovered, saved.getOrDefault(emptyList()), saved.exceptionOrNull())
                }
            } catch (cancel: java.util.concurrent.CancellationException) { return@launch }
            catch (error: Exception) { Result.failure(TtsException(failureFor(error))) }
            token.deliver { onResult(result) }
        }
    }

    private fun discover(token: TtsRequestToken): Result<List<BrowserVoice>> = try {
        val resolved = TtsAndroidServices.resolver(app).saved(id, "").getOrThrow()
        val (catalog, evidence) = TtsDiscoveryClient().voiceDiscovery(resolved, token)
        TtsFailures.voiceDiscovery(resolved, catalog, evidence)?.let { throw TtsException(it) }
        Result.success((catalog as TtsVoiceCatalog.Known).voices.map { voice ->
            BrowserVoice(id, voice.id, voice.displayName, providerModelId = source.modelId,
                language = voice.language, region = voice.region, gender = voice.gender,
                accent = voice.accent, style = voice.style, requiresNetwork = true, canPreview = true)
        })
    } catch (cancel: java.util.concurrent.CancellationException) { throw cancel }
    catch (error: Exception) { Result.failure(TtsException(failureFor(error))) }

    private fun failureFor(error: Exception): TtsFailure =
        (error as? TtsException)?.failure?.copy(operation = TtsOperation.VOICES)
            ?: TtsFailure(TtsOperation.VOICES,
                TtsTarget(source.endpointId, source.modelId, source.routing, id), "", TtsFailureKind.UNKNOWN)

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
        /**
         * One row per provider Voice ID. A saved ID the provider also lists keeps the provider's
         * row, marked as saved; saved IDs the provider does not list follow in the order entered.
         */
        fun merge(providerId: String, modelId: String, discovered: Result<List<BrowserVoice>>,
            saved: List<String>, savedFailure: Throwable? = null): Result<LoadedVoices> {
            val failure = discovered.exceptionOrNull()
            val manualEntry = (failure as? TtsException)?.failure?.let {
                it.operation == TtsOperation.VOICES && TtsFailures.voiceOutcome(it.kind) in
                    setOf(TtsVoiceOutcome.NONE_USABLE, TtsVoiceOutcome.UNREADABLE)
            } == true
            if (failure != null && saved.isEmpty() && !manualEntry && savedFailure == null) return Result.failure(failure)
            val rows = discovered.getOrDefault(emptyList())
            val listed = rows.map { it.providerVoiceId }.toSet()
            val merged = rows.map { if (it.providerVoiceId in saved) it.copy(manuallySaved = true) else it } +
                saved.filterNot(listed::contains).map { voiceId ->
                    BrowserVoice(providerId, voiceId, voiceId, providerModelId = modelId,
                        requiresNetwork = true, canPreview = true, manuallySaved = true)
                }
            return Result.success(LoadedVoices(merged, failure, manualEntry, savedFailure))
        }

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
