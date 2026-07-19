/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import android.content.Context
import android.content.SharedPreferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.util.Hash
import androidx.core.content.edit

class ApiEndpointPreferences private constructor(private var preferences: SharedPreferences) {
    companion object {
        private var apiEndpointPreferences: ApiEndpointPreferences? = null

        fun getApiEndpointPreferences(context: Context): ApiEndpointPreferences {
            if (apiEndpointPreferences == null) {
                apiEndpointPreferences = ApiEndpointPreferences(context.getSharedPreferences("api_endpoint", Context.MODE_PRIVATE))
            }

            return apiEndpointPreferences!!
        }
    }

    private var listeners: ArrayList<OnApiEndpointChangeListener> = ArrayList()

    fun getString(key: String, defValue: String): String {
        return preferences.getString(key, defValue)!!
    }

    fun putString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }

    fun getApiEndpoint(context: Context, id: String): ApiEndpointObject {
        val label = getString(id + "_label", "")
        val host = getString(id + "_host", "")
        val chatEndpoint = getString(id + "_chat_endpoint", ApiEndpointObject.DEFAULT_CHAT_ENDPOINT)
        val authType = getString(id + "_auth_type", ApiEndpointObject.AUTH_BEARER)
        val apiKey: String = EncryptedPreferences.getEncryptedPreference(context, "api_endpoint", id + "_api_key")
        val model = getString(id + "_model", ApiEndpointObject.DEFAULT_MODEL)
        val temperature = getString(id + "_temperature", ApiEndpointObject.DEFAULT_TEMPERATURE.toString()).toFloatOrNull()
            ?: ApiEndpointObject.DEFAULT_TEMPERATURE
        val topP = getString(id + "_top_p", ApiEndpointObject.DEFAULT_TOP_P.toString()).toFloatOrNull()
            ?: ApiEndpointObject.DEFAULT_TOP_P
        val frequencyPenalty = getString(id + "_frequency_penalty", ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY.toString()).toFloatOrNull()
            ?: ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY
        val presencePenalty = getString(id + "_presence_penalty", ApiEndpointObject.DEFAULT_PRESENCE_PENALTY.toString()).toFloatOrNull()
            ?: ApiEndpointObject.DEFAULT_PRESENCE_PENALTY
        val maxTokens = getString(id + "_max_tokens", ApiEndpointObject.DEFAULT_MAX_TOKENS.toString()).toIntOrNull()
            ?: ApiEndpointObject.DEFAULT_MAX_TOKENS
        val endSeparator = getString(id + "_end_separator", "")
        val prefix = getString(id + "_prefix", "")
        val provider = getString(id + "_provider", "")
        val connectTimeoutSeconds = ApiEndpointObject.coerceConnectTimeoutSeconds(
            getString(id + "_timeout", ApiEndpointObject.DEFAULT_CONNECT_TIMEOUT_SECONDS.toString()).toIntOrNull()
                ?: ApiEndpointObject.DEFAULT_CONNECT_TIMEOUT_SECONDS
        )
        val responseTimeoutSeconds = ApiEndpointObject.coerceResponseTimeoutSeconds(
            getString(id + "_response_timeout", ApiEndpointObject.DEFAULT_RESPONSE_TIMEOUT_SECONDS.toString()).toIntOrNull()
                ?: ApiEndpointObject.DEFAULT_RESPONSE_TIMEOUT_SECONDS
        )

        return ApiEndpointObject(
            label, host, apiKey, chatEndpoint, authType,
            model, temperature, topP, frequencyPenalty, presencePenalty,
            maxTokens, endSeparator, prefix, provider,
            connectTimeoutSeconds, responseTimeoutSeconds
        )
    }

    fun deleteApiEndpoint(context: Context, id: String) {
        preferences.edit { remove(id + "_label") }
        preferences.edit { remove(id + "_host") }
        preferences.edit { remove(id + "_chat_endpoint") }
        preferences.edit { remove(id + "_auth_type") }
        preferences.edit { remove(id + "_model") }
        preferences.edit { remove(id + "_temperature") }
        preferences.edit { remove(id + "_top_p") }
        preferences.edit { remove(id + "_frequency_penalty") }
        preferences.edit { remove(id + "_presence_penalty") }
        preferences.edit { remove(id + "_max_tokens") }
        preferences.edit { remove(id + "_end_separator") }
        preferences.edit { remove(id + "_prefix") }
        preferences.edit { remove(id + "_provider") }
        preferences.edit { remove(id + "_timeout") }
        preferences.edit { remove(id + "_response_timeout") }
        EncryptedPreferences.setEncryptedPreference(context, "api_endpoint", id + "_api_key", "null")

        for (listener in listeners) {
            listener.onApiEndpointChange()
        }
    }

    fun setApiEndpoint(context: Context, endpoint: ApiEndpointObject) {
        val id = Hash.hash(endpoint.label)
        putString(id + "_label", endpoint.label)
        putString(id + "_host", endpoint.host)
        putString(id + "_chat_endpoint", endpoint.chatEndpoint)
        putString(id + "_auth_type", endpoint.authType)
        putString(id + "_model", endpoint.model)
        putString(id + "_temperature", endpoint.temperature.toString())
        putString(id + "_top_p", endpoint.topP.toString())
        putString(id + "_frequency_penalty", endpoint.frequencyPenalty.toString())
        putString(id + "_presence_penalty", endpoint.presencePenalty.toString())
        putString(id + "_max_tokens", endpoint.maxTokens.toString())
        putString(id + "_end_separator", endpoint.endSeparator)
        putString(id + "_prefix", endpoint.prefix)
        putString(id + "_provider", endpoint.provider)
        putString(id + "_timeout", ApiEndpointObject.coerceConnectTimeoutSeconds(endpoint.connectTimeoutSeconds).toString())
        putString(id + "_response_timeout", ApiEndpointObject.coerceResponseTimeoutSeconds(endpoint.responseTimeoutSeconds).toString())
        EncryptedPreferences.setEncryptedPreference(context, "api_endpoint", id + "_api_key", endpoint.apiKey)

        for (listener in listeners) {
            listener.onApiEndpointChange()
        }
    }

    fun editEndpoint(context: Context, label: String, endpoint: ApiEndpointObject) {
        deleteApiEndpoint(context, Hash.hash(label))
        setApiEndpoint(context, endpoint)
    }

    fun migrateFromLegacyEndpoint(context: Context) {
        if (getApiEndpointsList(context).isEmpty()) {
            val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val label = "Default"
            val host = sp.getString("custom_host", "https://api.openai.com/v1/")
            val apiKey: String = EncryptedPreferences.getEncryptedPreference(context, "api", "api_key")

            setApiEndpoint(context, ApiEndpointObject(label, host!!, apiKey))
        }
    }

    fun getApiEndpointsList(context: Context): ArrayList<ApiEndpointObject> {
        val list = ArrayList<ApiEndpointObject>()
        for (key in preferences.all.keys) {
            if (key.endsWith("_label")) {
                val id = key.removeSuffix("_label")
                list.add(getApiEndpoint(context, id))
            }
        }

        // R8 bug fix
        if (list == null) {
            return ArrayList()
        }

        return list
    }

    fun getApiEndpointByUrlOrNull(context: Context, url: String): ApiEndpointObject? {
        val list = getApiEndpointsList(context)
        for (endpoint in list) {
            if (endpoint.host == url) {
                return endpoint
            }
        }
        return null
    }

    fun interface OnApiEndpointChangeListener {
        fun onApiEndpointChange()
    }
}
