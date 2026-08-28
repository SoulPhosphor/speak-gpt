package org.teslasoft.assistant.tts.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.tts.*
import org.teslasoft.assistant.tts.voices.GoogleSpeechVoiceProvider
import kotlin.coroutines.resume

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

    /** Does not contact a service unless a previous API selection needs to be validated. */
    suspend fun reconcile(token: TtsRequestToken, confirmed: TtsFailure? = null): Result<TtsVoiceSelection> = mutex.withLock {
        try {
            val current = preferences.getSelectedTtsVoice() ?: throw TtsException(TtsFailure(
                TtsOperation.SPEECH, TtsTarget(""), "", TtsFailureKind.SOURCE_MISSING))
            if (current.kind == TtsVoiceKind.DEVICE) return@withLock Result.success(current)
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
                return@withLock Result.success(current)
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
