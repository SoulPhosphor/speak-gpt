package org.teslasoft.assistant.ui.chat.setup

import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.conversation.setup.ExperimentalSetupCodec
import org.teslasoft.assistant.conversation.setup.SetupModel
import org.teslasoft.assistant.preferences.models.ModelIdentity

/** Ordered picker draft. Cancel never writes chat settings. */
internal class ParallelModelSelection(raw: String) {
    var models: List<SetupModel> = ExperimentalSetupCodec.decode(raw).models
        private set

    fun contains(identity: ModelIdentity) = models.any { it.identity == identity }

    fun toggle(model: SetupModel): Boolean {
        if (contains(model.identity)) models = models.filterNot { it.identity == model.identity }
        else {
            if (models.size == ExperimentalSetup.MAX_MODELS) return false
            models = models + model
        }
        return true
    }

    fun update(model: SetupModel): Boolean {
        val existing = models.firstOrNull { it.identity == model.identity } ?: return toggle(model)
        models = models.map { if (it.id == existing.id) model.copy(id = existing.id) else it }
        return true
    }

    fun raw(): String = ExperimentalSetupCodec.encode(ExperimentalSetup(models = models))
}
