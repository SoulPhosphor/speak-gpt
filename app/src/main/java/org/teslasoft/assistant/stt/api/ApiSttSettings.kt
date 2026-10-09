/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.stt.api

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.tts.api.TtsPickerCodec
import org.teslasoft.assistant.tts.api.TtsTarget

/**
 * API Voice Service (speech-to-text) settings. Stored in the app's global
 * "settings" preferences, so the app-settings backup carries them with every
 * other preference; no key here holds a credential.
 */
class ApiSttSettings(private val prefs: SharedPreferences) {
    companion object {
        const val LANGUAGE_AUTOMATIC = "auto"
        internal const val KEY_TARGET = "stt_api_target"
        internal const val KEY_LANGUAGE = "stt_api_language"
        internal const val KEY_VOCABULARY = "stt_api_vocabulary"

        fun get(context: Context) = ApiSttSettings(
            context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE))

        /** True when the saved endpoint still exists and a model (and any Only provider) is chosen. */
        fun isConfigured(context: Context): Boolean {
            val endpointIds = try {
                ApiEndpointPreferences.getApiEndpointPreferences(context).getApiEndpointsList(context).map { it.id }.toSet()
            } catch (_: Exception) { return false }
            return get(context).isConfigured(endpointIds)
        }
    }

    var target: TtsTarget
        get() = prefs.getString(KEY_TARGET, null)
            ?.let { runCatching { TtsPickerCodec.decode(it) }.getOrNull() } ?: TtsTarget("")
        set(value) = prefs.edit { putString(KEY_TARGET, TtsPickerCodec.encode(value.copy(sourceId = null, voiceId = null))) }

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANGUAGE_AUTOMATIC) ?: LANGUAGE_AUTOMATIC
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value) }

    var vocabulary: List<String>
        get() = runCatching {
            JsonParser.parseString(prefs.getString(KEY_VOCABULARY, "[]")).asJsonArray.map { it.asString }
        }.getOrDefault(emptyList())
        set(value) = prefs.edit { putString(KEY_VOCABULARY, JsonArray().apply { value.forEach(::add) }.toString()) }

    fun isConfigured(endpointIds: Set<String>): Boolean {
        val t = target
        if (t.endpointId.isBlank() || t.endpointId !in endpointIds || t.modelId.isBlank()) return false
        return t.routing.mode != TtsRoutingMode.ONLY || t.routing.selectedProvider.isNotBlank()
    }
}
