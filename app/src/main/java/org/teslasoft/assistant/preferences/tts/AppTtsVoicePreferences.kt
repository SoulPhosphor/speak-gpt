package org.teslasoft.assistant.preferences.tts

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.tts.voices.OPENAI_COMPATIBLE_FALLBACK_VOICE_NAMES

/** App-wide default voice, independent of conversations and future Companion overrides.
 *  Reuses the existing default-settings store, preserving its saved voice without
 *  promoting any particular chat's old preference to the global default. */
class AppTtsVoicePreferences internal constructor(private val preferences: SharedPreferences) {
    companion object {
        const val STORE_NAME = "settings."
        fun getPreferences(context: Context) = AppTtsVoicePreferences(
            SecurePrefs.get(context.applicationContext, STORE_NAME))
    }

    private fun getString(key: String, default: String): String =
        preferences.getString(key, default) ?: default

    private fun putString(key: String, value: String) {
        if (preferences.getString(key, null) != value) preferences.edit { putString(key, value) }
    }

    /**
     * Retrieves the voice model.
     *
     * @return voice model.
     */
    fun getVoice() : String {
        return getString("voice", "en-us-x-iom-network")
    }

    /**
     * Sets the voice model.
     *
     * @param model voice model.
     */
    fun setVoice(model: String) {
        val selection = getSelectedTtsVoice()
        putString("voice", model)
        if (selection?.kind == org.teslasoft.assistant.preferences.tts.TtsVoiceKind.DEVICE && selection.voiceId != model) {
            putString("selected_tts_voice", org.teslasoft.assistant.tts.api.TtsVoiceSelectionCodec.encode(selection.copy(voiceId = model)))
        }
    }

    /**
     * Set TTS engine
     *
     * @param engine - TTS engine (google or openai)
     * */
    fun setTtsEngine(engine: String) {
        putString("tts_engine", engine)
    }

    /**
     * Get TTS engine
     *
     * @return TTS engine (google or openai)
     * */
    fun getTtsEngine() : String {
        val selection = getSelectedTtsVoice()
        return when (selection?.kind) {
            org.teslasoft.assistant.preferences.tts.TtsVoiceKind.DEVICE -> "google"
            org.teslasoft.assistant.preferences.tts.TtsVoiceKind.API -> "openai"
            null -> getString("tts_engine", "google")
        }
    }

    /** Missing legacy API identity is unresolved, never guessed from the chat endpoint. */
    fun getSelectedTtsVoice(): org.teslasoft.assistant.preferences.tts.TtsVoiceSelection? {
        val raw = getString("selected_tts_voice", "")
        if (raw.isNotBlank()) return runCatching {
            org.teslasoft.assistant.tts.api.TtsVoiceSelectionCodec.decode(raw)
        }.getOrNull()
        return if (getString("tts_engine", "google") == "google") {
            org.teslasoft.assistant.preferences.tts.TtsVoiceSelection(
                org.teslasoft.assistant.preferences.tts.TtsVoiceKind.DEVICE, "google", getVoice())
        } else null
    }

    /** Call off the UI thread, after the previous-selection history has been saved. */
    fun saveSelectedTtsVoice(selection: org.teslasoft.assistant.preferences.tts.TtsVoiceSelection): Boolean {
        selection.validate()
        val keys = listOf("selected_tts_voice", "tts_engine", "voice", "openai_voice", "openai_tts_model")
        val before = keys.associateWith { preferences.getString(it, null) }
        val editor = preferences.edit().putString("selected_tts_voice",
            org.teslasoft.assistant.tts.api.TtsVoiceSelectionCodec.encode(selection))
        if (selection.kind == org.teslasoft.assistant.preferences.tts.TtsVoiceKind.DEVICE) {
            editor.putString("voice", selection.voiceId).putString("tts_engine", "google")
        } else {
            editor.putString("openai_voice", selection.voiceId)
                .putString("openai_tts_model", selection.modelId).putString("tts_engine", "openai")
        }
        if (editor.commit()) return true
        // SharedPreferences may update memory even when its disk commit fails.
        preferences.edit().also { rollback -> before.forEach { (key, value) ->
            if (value == null) rollback.remove(key) else rollback.putString(key, value)
        } }.commit()
        return false
    }

    fun isTtsVoicePermanentlyUnavailable(selection: org.teslasoft.assistant.preferences.tts.TtsVoiceSelection): Boolean =
        getString("unavailable_tts_voice", "") == org.teslasoft.assistant.tts.api.TtsVoiceSelectionCodec.encode(selection)

    fun markTtsVoicePermanentlyUnavailable(selection: org.teslasoft.assistant.preferences.tts.TtsVoiceSelection) {
        putString("unavailable_tts_voice", org.teslasoft.assistant.tts.api.TtsVoiceSelectionCodec.encode(selection))
    }


    /**
     * Set OpenAI voice
     *
     * @param voice - voice name
     * */
    fun setOpenAIVoice(voice: String) {
        putString("openai_voice", voice)
    }

    /**
     * Get OpenAI voice
     *
     * @return voice name
     * */
    fun getOpenAIVoice() : String {
        return getString("openai_voice", OPENAI_COMPATIBLE_FALLBACK_VOICE_NAMES.first())
    }

    fun setOpenAITtsModel(model: String) {
        putString("openai_tts_model", model)
    }

    fun getOpenAITtsModel(): String = getString("openai_tts_model", "")

}
