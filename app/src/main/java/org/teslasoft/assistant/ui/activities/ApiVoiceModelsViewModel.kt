package org.teslasoft.assistant.ui.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.tts.SavedTtsSource
import org.teslasoft.assistant.preferences.tts.SavedTtsSourcesPreferences
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.tts.api.*

data class TtsEndpointChoice(val id: String, val label: String)
data class TtsManagerUi(
    val draft: TtsTarget = TtsTarget(""),
    val rows: List<SavedTtsSource> = emptyList(),
    val endpoints: List<TtsEndpointChoice> = emptyList(),
    val busy: Boolean = false,
    // True only while a save/remove is writing to storage. A read-only refresh
    // sets busy (progress + re-entrancy guard) but not this, so the pickers stay
    // live and openable while the list reloads.
    val mutating: Boolean = false,
    val notice: TtsFailure? = null
)

/** Retains in-flight local writes through rotation; saved state contains identities, never keys. */
class ApiVoiceModelsViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val session = TtsManagerState(SavedTtsSourcesPreferences.getPreferences(application))
    private val mutableUi = MutableStateFlow(TtsManagerUi())
    val ui = mutableUi.asStateFlow()
    private var retry: (() -> Unit)? = null
    private var pendingEdit: TtsTarget? = null
    private var queuedEdit: TtsTarget? = null

    init {
        fun read(key: String) = saved.get<String>(key)?.let { runCatching { TtsPickerCodec.decode(it) }.getOrNull() }
        session.restore(read("draft") ?: TtsTarget(""), read("model"), read("provider"), read("committed"))
        pendingEdit = read("edit")
        publish()
    }

    private fun publish(busy: Boolean = false, mutating: Boolean = false, notice: TtsFailure? = null) {
        saved["draft"] = TtsPickerCodec.encode(session.draft)
        saved["model"] = session.modelRequest?.target?.let(TtsPickerCodec::encode)
        saved["provider"] = session.providerRequest?.target?.let(TtsPickerCodec::encode)
        saved["committed"] = session.committedAdd?.let(TtsPickerCodec::encode)
        saved["edit"] = pendingEdit?.let(TtsPickerCodec::encode)
        mutableUi.value = mutableUi.value.copy(draft = session.draft, rows = session.rows,
            busy = busy, mutating = mutating, notice = notice)
    }

    private fun endpoints(): List<TtsEndpointChoice> = try {
        val app = getApplication<Application>()
        ApiEndpointPreferences.getApiEndpointPreferences(app).getApiEndpointsList(app)
            .map { TtsEndpointChoice(it.id, it.label.ifBlank { it.id }) }.sortedBy { it.label.lowercase() }
    } catch (_: Exception) {
        throw TtsException(TtsFailure(TtsOperation.MODELS, session.draft, "", TtsFailureKind.ENDPOINT_LIST_FAILED))
    }

    private fun validateEndpoint(target: TtsTarget) {
        if (endpoints().none { it.id == target.endpointId })
            throw TtsException(TtsFailure(TtsOperation.MODELS, target, "", TtsFailureKind.PROFILE_MISSING))
    }

    fun refresh() {
        if (ui.value.busy || ui.value.notice != null) return
        run(TtsFailureKind.STORAGE, ::refresh, mutating = false) {
            // Keep endpoint and saved-source failures separate. A broken profile list cannot
            // turn the saved table into an empty writable list.
            session.refresh()
            endpoints()
        }
    }

    private fun run(fallback: TtsFailureKind, retryAction: () -> Unit,
        mutating: Boolean = true, work: () -> List<TtsEndpointChoice>?) {
        if (ui.value.busy) return
        retry = null
        publish(busy = true, mutating = mutating)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(work) }
            result.onSuccess { choices ->
                if (choices != null) mutableUi.value = mutableUi.value.copy(endpoints = choices)
                publish()
            }.onFailure { error ->
                val failure = (error as? TtsException)?.failure
                    ?: TtsFailure(TtsOperation.MODELS, session.draft, "", fallback)
                // A committed write followed by an unreadable refresh must never be replayed.
                retry = if (failure.kind == TtsFailureKind.STORAGE) ::refresh else retryAction
                publish(notice = failure)
            }
            queuedEdit?.let { target -> queuedEdit = null; edit(target) }
        }
    }

    fun endpoint(id: String) { if (!ui.value.mutating) { pendingEdit = null; session.endpoint(id); publish(busy = ui.value.busy) } }
    fun mode(mode: TtsRoutingMode) { if (!ui.value.mutating) { session.mode(mode); publish(busy = ui.value.busy) } }
    fun openModel(): TtsPickerRequest? = navigation { session.openModel() }
    fun openProvider(row: SavedTtsSource? = null): TtsPickerRequest? = navigation {
        val request = session.openProvider(row)
        // A failed saved-row edit can be reopened with its unsaved routing intact.
        pendingEdit?.takeIf { it.sourceId == request.target.sourceId && it.sourceId != null &&
            it.endpointId == request.target.endpointId && it.modelId == request.target.modelId }
            ?.let { TtsPickerRequest(it) } ?: request
    }

    private fun navigation(action: () -> TtsPickerRequest): TtsPickerRequest? {
        // A read-only refresh no longer blocks opening a picker; only an in-flight
        // write does. Preserve busy so a concurrent refresh keeps its guard/spinner.
        if (ui.value.mutating) return null
        return try { action().also { publish(busy = ui.value.busy) } } catch (error: TtsException) {
            publish(busy = ui.value.busy, notice = error.failure); null
        }
    }

    fun modelResult(target: TtsTarget?) { session.acceptModel(target); publish(busy = ui.value.busy, mutating = ui.value.mutating) }
    fun providerResult(target: TtsTarget?) {
        val selection = session.acceptProvider(target)
        publish(busy = ui.value.busy, mutating = ui.value.mutating)
        if (selection != null) {
            pendingEdit = selection
            if (ui.value.busy) queuedEdit = selection else edit(selection)
        }
    }
    fun add() {
        val captured = session.draft
        run(TtsFailureKind.SAVE_FAILED, { if (session.draft == captured) add() }) {
            session.add(captured, ::validateEndpoint)
            null
        }
    }
    private fun edit(target: TtsTarget) {
        run(TtsFailureKind.SAVE_FAILED, { if (pendingEdit == target) edit(target) }) {
            session.edit(target, ::validateEndpoint)
            pendingEdit = null
            null
        }
    }
    fun remove(row: SavedTtsSource) {
        run(TtsFailureKind.REMOVE_FAILED, { remove(row) }) {
            session.remove(row)
            if (pendingEdit?.sourceId == row.sourceId) pendingEdit = null
            null
        }
    }
    fun takeRetry(): () -> Unit {
        val action = retry ?: {}
        mutableUi.value = mutableUi.value.copy(notice = null)
        return action
    }
}
