package org.teslasoft.assistant.ui.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.preferences.tts.SavedTtsSourcesPreferences
import org.teslasoft.assistant.tts.api.*

/** A confirmed deletion survives rotation; an already-started commit finishes its recovery. */
class TtsModelCleanupViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val busy: Boolean = false, val result: Result<TtsSourceRemoval>? = null,
        val targets: Set<ModelIdentity> = emptySet())

    private val state = MutableStateFlow(State())
    val ui = state.asStateFlow()

    fun remove(targets: Set<ModelIdentity>) {
        if (state.value.busy || targets.isEmpty()) return
        state.value = State(busy = true, targets = targets)
        viewModelScope.launch {
            // Do not cancel between the atomic disk replacement and global voice recovery.
            val result = withContext(NonCancellable) {
                val context = getApplication<Application>()
                val services = runCatching {
                    TtsSelectionService(context, Preferences.getPreferences(context, "")) to
                        SavedTtsSourcesPreferences.getPreferences(context)
                }
                services.fold(onSuccess = { (selection, store) ->
                    selection.removeUnavailableSources(store, targets, TtsRequestGate().begin())
                }, onFailure = {
                    Result.failure(TtsException(TtsFailure(TtsOperation.MODELS, TtsTarget(""), "",
                        TtsFailureKind.REMOVE_FAILED)))
                })
            }
            state.value = State(result = result, targets = targets)
        }
    }

    fun consumeResult() { state.value = state.value.copy(result = null) }
}
