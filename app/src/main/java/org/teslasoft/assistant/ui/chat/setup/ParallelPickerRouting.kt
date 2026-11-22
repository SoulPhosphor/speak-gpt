package org.teslasoft.assistant.ui.chat.setup

import android.content.Intent
import com.google.gson.Gson
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.ui.activities.ChooseProviderActivity

/** Result-only use of the existing provider editor. Nothing is written to favorites or chats. */
internal object ParallelPickerRouting {
    private const val INITIAL = "parallelInitialRouting"

    fun prepare(intent: Intent, settings: FavoriteModelObject): Intent = intent
        .putExtra(ChooseProviderActivity.EXTRA_PERSIST_DIRECTLY, false)
        .putExtra(ChooseProviderActivity.EXTRA_RETURN_ON_BACK_AFTER_SAVE, false)
        .putExtra(ChooseProviderActivity.EXTRA_CHAT_ID, "")
        .putExtra(ChooseProviderActivity.EXTRA_ROUTING_TYPE, settings.routingType)
        .putExtra(INITIAL, Gson().toJson(settings))

    fun initial(intent: Intent, endpointId: String, modelId: String): FavoriteModelObject? {
        if (intent.getBooleanExtra(ChooseProviderActivity.EXTRA_PERSIST_DIRECTLY, false)) return null
        return intent.getStringExtra(INITIAL)?.let { raw ->
            runCatching { Gson().fromJson(raw, FavoriteModelObject::class.java) }.getOrNull()
        }?.takeIf { it.endpointId == endpointId && it.modelId == modelId }
    }

    fun result(data: Intent, original: FavoriteModelObject): FavoriteModelObject = original.copy().apply {
        modelId = data.getStringExtra(ChooseProviderActivity.EXTRA_MODEL) ?: modelId
        routingType = data.getStringExtra(ChooseProviderActivity.EXTRA_ROUTING_TYPE) ?: routingType
        selectedProvider = data.getStringExtra(ChooseProviderActivity.EXTRA_SELECTED_PROVIDER).orEmpty()
        allowFallbacks = data.getBooleanExtra(ChooseProviderActivity.EXTRA_ALLOW_FALLBACKS, true)
        providerOrder = data.getStringArrayListExtra(ChooseProviderActivity.EXTRA_PROVIDER_ORDER)?.toList().orEmpty()
        ignoredProviders = data.getStringArrayListExtra(ChooseProviderActivity.EXTRA_IGNORED_PROVIDERS)?.toList().orEmpty()
    }
}
