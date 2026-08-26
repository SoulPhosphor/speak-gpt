package org.teslasoft.assistant.tts.voices

import android.content.Context
import androidx.core.content.edit
import org.teslasoft.assistant.preferences.SecurePrefs

class LastKnownGoodVoiceRegistry(context: Context, chatId: String) {
    private val preferences = SecurePrefs.get(context.applicationContext, "settings.$chatId")

    fun save(selection: LastKnownGoodVoiceSelection) {
        preferences.edit {
            putString(KEY_PROVIDER, selection.providerId)
            putString(KEY_VOICE, selection.providerVoiceId)
            putString(KEY_MODEL, selection.providerModelId.orEmpty())
        }
    }

    fun load(): LastKnownGoodVoiceSelection? {
        val providerId = preferences.getString(KEY_PROVIDER, null).orEmpty()
        val voiceId = preferences.getString(KEY_VOICE, null).orEmpty()
        if (providerId.isBlank() || voiceId.isBlank()) return null
        return LastKnownGoodVoiceSelection(
            providerId = providerId,
            providerVoiceId = voiceId,
            providerModelId = preferences.getString(KEY_MODEL, null)?.takeIf(String::isNotBlank)
        )
    }

    private companion object {
        const val KEY_PROVIDER = "last_known_good_tts_provider"
        const val KEY_VOICE = "last_known_good_tts_voice"
        const val KEY_MODEL = "last_known_good_tts_model"
    }
}
