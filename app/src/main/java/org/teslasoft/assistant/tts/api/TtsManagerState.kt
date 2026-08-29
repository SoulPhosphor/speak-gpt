package org.teslasoft.assistant.tts.api

import org.teslasoft.assistant.preferences.tts.SavedTtsSource
import org.teslasoft.assistant.preferences.tts.SavedTtsSourcesPreferences
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings
import org.teslasoft.assistant.preferences.tts.TtsStorageException
import org.teslasoft.assistant.preferences.tts.TtsStorageFailure

/** One manager session. Disk methods run serially on IO; navigation never writes the store. */
class TtsManagerState(private val store: SavedTtsSourcesPreferences) {
    var draft = TtsTarget("")
        private set
    var rows: List<SavedTtsSource> = emptyList()
        private set
    var modelRequest: TtsPickerRequest? = null
        private set
    var providerRequest: TtsPickerRequest? = null
        private set
    // If a post-commit refresh fails, retry only the read, never the committed mutation.
    var committedAdd: TtsTarget? = null
        private set

    fun restore(draft: TtsTarget, model: TtsTarget?, provider: TtsTarget?, committed: TtsTarget?) {
        this.draft = draft.copy(sourceId = null, voiceId = null)
        modelRequest = model?.let(::TtsPickerRequest)
        providerRequest = provider?.let(::TtsPickerRequest)
        committedAdd = committed
    }

    fun endpoint(id: String) {
        if (draft.endpointId == id) return
        draft = TtsTarget(id)
        modelRequest = null
        providerRequest = null
    }

    fun mode(mode: TtsRoutingMode) {
        val picker = TtsProviderPickerState(TtsPickerRequest(draft))
        picker.mode(mode)
        draft = draft.copy(routing = picker.routing)
    }

    fun openModel(): TtsPickerRequest {
        requireEndpoint(draft)
        return TtsPickerRequest(draft).also { modelRequest = it }
    }

    fun acceptModel(result: TtsTarget?) {
        val request = modelRequest
        modelRequest = null
        if (result == null || request == null || request.target != draft || !request.acceptsModelResult(result)) return
        if (result.modelId != draft.modelId) draft = draft.copy(modelId = result.modelId, routing = TtsRoutingSettings())
    }

    fun openProvider(row: SavedTtsSource? = null): TtsPickerRequest {
        val target = row?.target() ?: draft
        requireEndpoint(target)
        if (target.modelId.isBlank()) fail(target, TtsFailureKind.MODEL_REQUIRED)
        return TtsPickerRequest(target).also { providerRequest = it }
    }

    /** A saved-row result is returned for persistence; a draft result only changes the draft. */
    fun acceptProvider(result: TtsTarget?): TtsTarget? {
        val request = providerRequest
        providerRequest = null
        if (result == null || request == null || !request.acceptsProviderResult(result)) return null
        if (result.sourceId == null) {
            if (draft == request.target) draft = draft.copy(routing = result.routing)
            return null
        }
        // Rows may not have reloaded yet after process recreation. edit() rechecks the
        // durable source identity, so a valid restored result is neither dropped nor upserted.
        return result
    }

    fun refresh() {
        rows = store.load().getOrElse { fail(draft, TtsFailureKind.STORAGE) }
        committedAdd?.let { captured ->
            if (draft == captured) draft = TtsTarget("")
            committedAdd = null
        }
    }

    fun add(captured: TtsTarget, validateEndpoint: (TtsTarget) -> Unit) {
        if (committedAdd != null) { refresh(); return }
        if (captured != draft) return
        val validated = TtsProviderPickerState(TtsPickerRequest(captured)).result()
        validateEndpoint(validated)
        // Optional Preferred without any preference is stored as Automatic. Only stays strict.
        val routing = savedRouting(validated.routing)
        store.add(captured.endpointId, captured.modelId, routing).getOrElse {
            throw storageFailure(captured, it, TtsFailureKind.SAVE_FAILED)
        }
        committedAdd = captured
        refresh()
    }

    fun edit(target: TtsTarget, validateEndpoint: (TtsTarget) -> Unit) {
        val validated = TtsProviderPickerState(TtsPickerRequest(target)).result()
        validateEndpoint(target)
        val row = store.load().getOrElse { fail(target, TtsFailureKind.STORAGE) }
            .singleOrNull { it.sourceId == target.sourceId && it.endpointId == target.endpointId && it.modelId == target.modelId }
            ?: fail(target, TtsFailureKind.SAVED_SOURCE_MISSING)
        store.replaceRouting(row.id, savedRouting(validated.routing)).getOrElse {
            throw storageFailure(target, it, TtsFailureKind.SAVE_FAILED)
        }
        refresh()
    }

    fun remove(row: SavedTtsSource) {
        store.removeEntryIds(setOf(row.id)).getOrElse {
            throw storageFailure(row.target(), it, TtsFailureKind.REMOVE_FAILED)
        }
        refresh()
        // No saved API source can be activated until Phase 5. Its active-voice resolver must
        // observe this same store and apply removal recovery before subsequent playback.
    }

    private fun requireEndpoint(target: TtsTarget) {
        if (target.endpointId.isBlank()) fail(target, TtsFailureKind.ENDPOINT_REQUIRED)
    }
    private fun savedRouting(routing: TtsRoutingSettings): TtsRoutingSettings =
        if (routing.mode == TtsRoutingMode.PREFERRED && routing.providerOrder.isEmpty() &&
            routing.selectedProvider.isBlank() && routing.allowFallbacks) TtsRoutingSettings() else routing
    private fun fail(target: TtsTarget, kind: TtsFailureKind): Nothing =
        throw TtsException(TtsFailure(TtsOperation.MODELS, target, "", kind))
    private fun storageFailure(target: TtsTarget, error: Throwable, fallback: TtsFailureKind): TtsException {
        val noSpace = generateSequence(error) { it.cause }.any {
            (it is java.nio.file.FileSystemException && it.reason == "No space left on device") ||
                (it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC)
        }
        val kind = when ((error as? TtsStorageException)?.reason) {
            TtsStorageFailure.DUPLICATE -> TtsFailureKind.DUPLICATE
            TtsStorageFailure.READ_FAILED, TtsStorageFailure.INVALID_DATA -> TtsFailureKind.STORAGE
            TtsStorageFailure.NOT_FOUND -> TtsFailureKind.SAVED_SOURCE_MISSING
            else -> if (noSpace && fallback == TtsFailureKind.SAVE_FAILED) TtsFailureKind.STORAGE_FULL else fallback
        }
        return TtsException(TtsFailure(TtsOperation.MODELS, target, "", kind))
    }
}

fun SavedTtsSource.target() = TtsTarget(endpointId, modelId, routing, sourceId)

/** Same identity selection as QuickSettingsProviderDisplay, without chat-favorite storage. */
object TtsManagerProviderDisplay {
    fun label(routing: TtsRoutingSettings, empty: String): String = when (routing.mode) {
        TtsRoutingMode.ONLY -> routing.selectedProvider
        TtsRoutingMode.PREFERRED -> routing.providerOrder.firstOrNull().orEmpty()
        TtsRoutingMode.AUTOMATIC -> ""
    }.ifBlank { empty }
}
