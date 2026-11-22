package org.teslasoft.assistant.ui.chat.setup

import android.content.Context
import com.google.gson.Gson
import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.conversation.setup.SetupModel
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelCleanupReportStore
import java.util.UUID

internal object SetupModelResolver {
    fun capture(context: Context, chatId: String, chosen: FavoriteModelObject): SetupModel {
        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(context)
            .getApiEndpointsList(context).first { it.id == chosen.endpointId }
        val settings = chosen.copy()
        val chat = Preferences.getPreferences(context, chatId)
        val parameters = if (FavoriteModelParameters.isEnabled(context)) {
            FavoriteModelParameters.startingParameters(context, chosen.modelId, chosen.endpointId)
        } else FavoriteModelObject(chosen.modelId, chosen.endpointId).apply {
            streaming = chat.getStreaming()
            temperature = chat.getTemperature()
            topP = chat.getTopP()
            frequencyPenalty = chat.getFrequencyPenalty()
            presencePenalty = chat.getPresencePenalty()
        }
        parameters.copySamplingTo(settings)
        return SetupModel(UUID.randomUUID().toString(), chosen.endpointId, chosen.modelId,
            endpoint.host, endpoint.chatEndpoint, endpoint.authType, endpoint.identity, Gson().toJson(settings), endpoint.label.ifBlank { endpoint.host })
            .also { it.validate() }
    }

    /** Uses current protected credentials without ever serializing them into setup. */
    fun unavailable(context: Context, setup: ExperimentalSetup): List<SetupModel> {
        val endpoints = ApiEndpointPreferences.getApiEndpointPreferences(context)
            .getApiEndpointsList(context).associateBy { it.id }
        val missing = ModelCleanupReportStore.get(context).load().unavailable
        return unavailable(setup, endpoints, missing)
    }

    internal fun unavailable(setup: ExperimentalSetup,
        endpoints: Map<String, org.teslasoft.assistant.preferences.dto.ApiEndpointObject>,
        missing: Set<org.teslasoft.assistant.preferences.models.ModelIdentity>
    ): List<SetupModel> = setup.models.filter { model ->
            val endpoint = endpoints[model.endpointId]
            endpoint == null || model.identity in missing || endpoint.host != model.endpointHost ||
                endpoint.chatEndpoint != model.chatPath || endpoint.authType != model.authType ||
                endpoint.identity != model.endpointIdentity || endpoint.apiKey.isBlank()
        }
}
