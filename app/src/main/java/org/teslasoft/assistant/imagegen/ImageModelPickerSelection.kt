package org.teslasoft.assistant.imagegen

import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.RecentModelsPreferences
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/** Selecting an image model preserves each profile/model's saved image options. */
internal object ImageModelPickerSelection {
    fun select(preferences: Preferences, recents: RecentModelsPreferences, endpointId: String, modelId: String) {
        preferences.selectImageGeneratorEndpoint(endpointId)
        preferences.setImageGeneratorModel(modelId)
        recents.recordRouting(FavoriteModelObject(modelId, endpointId))
    }
}
