package org.teslasoft.assistant.tts.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.preferences.tts.*
import org.teslasoft.assistant.tts.voices.GoogleSpeechVoiceProvider
import kotlin.coroutines.resume

/** Persistence and voice recovery have distinct outcomes: a recovery error does not undo deletion. */
data class TtsSourceRemoval(val removed: Int, val recoveryFailure: TtsFailure? = null)

/** Activation and removal recovery for the app-wide default voice. */
class TtsSelectionService internal constructor(
    private val preferences: Preferences,
    private val resolver: TtsSourceResolver,
    private val history: PreviousTtsVoicePreferences,
    private val usable: suspend (TtsVoiceSelection, TtsRequestToken) -> Boolean
) {
    constructor(context: Context, preferences: Preferences) : this(preferences,
        TtsAndroidServices.resolver(context),
        PreviousTtsVoicePreferences.getPreferences(context),
        { selection, token -> usableOnAndroid(context.applicationContext, preferences, selection, token) })

    suspend fun activate(next: TtsVoiceSelection): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (next.kind == TtsVoiceKind.API) {
                    val source = resolver.saved(next.sourceId, next.voiceId).getOrThrow()
                    require(source.target.modelId == next.modelId)
                }
                history.activate(preferences.getSelectedTtsVoice(), next) {
                    preferences.saveSelectedTtsVoice(next)
                }.getOrElse { throw TtsException(TtsFailure(TtsOperation.SPEECH,
                    TtsTarget("", sourceId = next.sourceId, voiceId = next.voiceId), "", TtsFailureKind.SAVE_FAILED)) }
            }

        }
    }

    /** One confirmed batch, serialized with activation. No model availability check at deletion time. */
    suspend fun removeUnavailableSources(store: SavedTtsSourcesPreferences, targets: Set<ModelIdentity>,
        token: TtsRequestToken): Result<TtsSourceRemoval> = mutex.withLock {
        token.check()
        val current = runCatching { preferences.getSelectedTtsVoice() }.getOrElse {
            return@withLock Result.failure(TtsException(TtsFailure(TtsOperation.MODELS,
                TtsTarget(""), "", TtsFailureKind.REMOVE_FAILED)))
        }
        val removal = withContext(Dispatchers.IO) {
            runCatching {
                val matching = store.load().getOrThrow().filter {
                    ModelIdentity(it.endpointId, it.modelId) in targets
                }
                val removed = store.removeTargets(targets).getOrThrow()
                removed to (current?.kind == TtsVoiceKind.API && matching.any { it.sourceId == current.sourceId })
            }
        }
        val (count, activeRemoved) = removal.getOrElse {
            return@withLock Result.failure(TtsException(TtsFailure(TtsOperation.MODELS,
                TtsTarget(""), "", if ((it as? TtsStorageException)?.reason in
                    setOf(TtsStorageFailure.READ_FAILED, TtsStorageFailure.INVALID_DATA)) TtsFailureKind.STORAGE
                    else TtsFailureKind.REMOVE_FAILED)))
        }
        val recovery = if (count > 0 && activeRemoved) reconcileLocked(token) else null
        val recoveryFailure = recovery?.exceptionOrNull()?.let { error ->
            // The source is already removed. Every failed restoration uses the
            // approved permanent-unavailability dialog, never a save-error message
            // claiming that the saved source list was left unchanged.
            ((error as? TtsException)?.failure ?: TtsFailure(TtsOperation.SPEECH,
                TtsTarget("", sourceId = current?.sourceId, voiceId = current?.voiceId), "",
                TtsFailureKind.PERMANENT_UNAVAILABLE)).copy(kind = TtsFailureKind.PERMANENT_UNAVAILABLE)
        }
        Result.success(TtsSourceRemoval(count, recoveryFailure))
    }

    /** Does not contact a service unless a previous API selection needs to be validated. */
    suspend fun reconcile(token: TtsRequestToken, confirmed: TtsFailure? = null): Result<TtsVoiceSelection> = mutex.withLock {
        reconcileLocked(token, confirmed)
    }

    private suspend fun reconcileLocked(token: TtsRequestToken, confirmed: TtsFailure? = null): Result<TtsVoiceSelection> {
        return try {
            val current = preferences.getSelectedTtsVoice() ?: throw TtsException(TtsFailure(
                TtsOperation.SPEECH, TtsTarget(""), "", TtsFailureKind.SOURCE_MISSING))
            if (current.kind == TtsVoiceKind.DEVICE) return Result.success(current)
            val resolved = withContext(Dispatchers.IO) { resolver.saved(current.sourceId, current.voiceId) }
            token.check()
            val missing = (resolved.exceptionOrNull() as? TtsException)?.failure
            val confirmedCurrent = confirmed?.takeIf { it.kind == TtsFailureKind.VOICE_DELETED &&
                it.target.sourceId == current.sourceId && it.target.voiceId == current.voiceId }
            if (confirmedCurrent != null) preferences.markTtsVoicePermanentlyUnavailable(current)
            val permanent = confirmedCurrent != null || preferences.isTtsVoicePermanentlyUnavailable(current) ||
                missing?.kind in setOf(TtsFailureKind.SOURCE_MISSING, TtsFailureKind.PROFILE_MISSING)
            if (!permanent) {
                resolved.getOrThrow()
                return Result.success(current)
            }
            val previous = withContext(Dispatchers.IO) { history.load().getOrNull() }
            val canRestore = previous != null && previous != current &&
                !preferences.isTtsVoicePermanentlyUnavailable(previous) && usable(previous, token)
            val replacement = TtsVoiceRecovery.replacement(current, previous, permanent) { canRestore }
            token.check()
            if (preferences.getSelectedTtsVoice() != current) throw java.util.concurrent.CancellationException()
            if (replacement != null) {
                val saved = withContext(Dispatchers.IO) { preferences.saveSelectedTtsVoice(replacement) }
                if (!saved) throw TtsException(TtsFailure(TtsOperation.SPEECH,
                    TtsTarget("", sourceId = current.sourceId), "", TtsFailureKind.SAVE_FAILED))
                // Automatic recovery never records the deleted voice as the previous selection.
                Result.success(replacement)
            } else Result.failure(TtsException((confirmedCurrent ?: missing ?: TtsFailure(
                TtsOperation.SPEECH, TtsTarget("", sourceId = current.sourceId, voiceId = current.voiceId), "",
                TtsFailureKind.PERMANENT_UNAVAILABLE)).copy(kind = TtsFailureKind.PERMANENT_UNAVAILABLE)))
        } catch (cancel: java.util.concurrent.CancellationException) {
            throw cancel
        } catch (error: Exception) { Result.failure(error) }
    }

    companion object {
        private val mutex = Mutex()
        private suspend fun usableOnAndroid(app: Context, preferences: Preferences,
            selection: TtsVoiceSelection, token: TtsRequestToken): Boolean {
        val resolver = TtsAndroidServices.resolver(app)
        if (preferences.isTtsVoicePermanentlyUnavailable(selection)) return false
        if (selection.kind == TtsVoiceKind.API) return withContext(Dispatchers.IO) {
            try {
                val source = resolver.saved(selection.sourceId, selection.voiceId).getOrThrow()
                source.target.modelId == selection.modelId &&
                    (TtsDiscoveryClient().voices(source, token) as? TtsVoiceCatalog.Known)
                        ?.voices?.any { it.id == selection.voiceId } == true
            } catch (cancel: java.util.concurrent.CancellationException) { throw cancel }
            catch (_: Exception) { false }
        }
        if (selection.sourceId != "google") return false
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val provider = GoogleSpeechVoiceProvider(app, preferences)
                continuation.invokeOnCancellation { provider.shutdown() }
                provider.loadVoices { result ->
                    val found = result.getOrNull()?.any { it.providerVoiceId == selection.voiceId &&
                        it.installedLocally != false && it.canPreview } == true
                    provider.shutdown()
                    if (continuation.isActive) continuation.resume(found)
                }
            }
        }
    }

    }
}
