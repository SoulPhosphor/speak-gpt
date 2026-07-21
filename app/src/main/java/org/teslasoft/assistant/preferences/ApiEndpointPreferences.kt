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
import org.teslasoft.assistant.util.StableId
import androidx.core.content.edit

class ApiEndpointPreferences private constructor(
    private var preferences: SharedPreferences,
    private val secrets: SecretStore
) {
    companion object {
        private var apiEndpointPreferences: ApiEndpointPreferences? = null

        fun getApiEndpointPreferences(context: Context): ApiEndpointPreferences {
            if (apiEndpointPreferences == null) {
                apiEndpointPreferences = ApiEndpointPreferences(
                    context.getSharedPreferences("api_endpoint", Context.MODE_PRIVATE),
                    EncryptedSecretStore(context.applicationContext)
                )
            }

            return apiEndpointPreferences!!
        }

        /** Test seam: inject an in-memory SharedPreferences and secret store. */
        internal fun createForTest(preferences: SharedPreferences, secrets: SecretStore): ApiEndpointPreferences =
            ApiEndpointPreferences(preferences, secrets)
    }

    /**
     * The endpoint's API key lives in the encrypted store keyed by
     * `<id>_api_key`. Kept behind an interface so the identity/field logic can
     * be unit-tested without the Android Keystore, AND so credential handling
     * during a rename is explicit: a rename no longer moves the key between
     * ids — the id is stable, so the same encrypted entry stays in place.
     */
    interface SecretStore {
        fun get(key: String): String
        fun set(key: String, value: String)
    }

    private class EncryptedSecretStore(private val appContext: Context) : SecretStore {
        override fun get(key: String): String =
            EncryptedPreferences.getEncryptedPreference(appContext, "api_endpoint", key)
        override fun set(key: String, value: String) =
            EncryptedPreferences.setEncryptedPreference(appContext, "api_endpoint", key, value)
    }

    private var listeners: ArrayList<OnApiEndpointChangeListener> = ArrayList()

    fun getString(key: String, defValue: String): String {
        return preferences.getString(key, defValue)!!
    }

    fun putString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }

    // The public methods keep their `context` parameter for source
    // compatibility with the many call sites, but the API key now flows through
    // the [secrets] seam (which captured its context at construction), so the
    // parameter is no longer read here — the id-only overloads below do the work
    // and are what the unit tests drive.

    fun getApiEndpoint(context: Context, id: String): ApiEndpointObject = getApiEndpoint(id)

    internal fun getApiEndpoint(id: String): ApiEndpointObject {
        val label = getString(id + "_label", "")
        val host = getString(id + "_host", "")
        val chatEndpoint = getString(id + "_chat_endpoint", ApiEndpointObject.DEFAULT_CHAT_ENDPOINT)
        val authType = getString(id + "_auth_type", ApiEndpointObject.AUTH_BEARER)
        val apiKey: String = secrets.get(id + "_api_key")
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
            connectTimeoutSeconds, responseTimeoutSeconds, id
        )
    }

    fun deleteApiEndpoint(context: Context, id: String) = deleteApiEndpoint(id)

    internal fun deleteApiEndpoint(id: String) {
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
        secrets.set(id + "_api_key", "null")

        for (listener in listeners) {
            listener.onApiEndpointChange()
        }
    }

    fun setApiEndpoint(context: Context, endpoint: ApiEndpointObject): String = setApiEndpoint(endpoint)

    /**
     * Save under the endpoint's stable [ApiEndpointObject.id]. A brand-new
     * profile (blank id) is minted a fresh id ONCE, in place; an existing
     * profile keeps its id, so a rename (same id, new label) updates the record
     * — the encrypted API key, favorite-model links and per-chat selection all
     * stay attached because nothing moves to a new, name-derived id. Returns the
     * id the profile was saved under.
     */
    internal fun setApiEndpoint(endpoint: ApiEndpointObject): String {
        val id = StableId.resolve(endpoint.id, "ep-")
        endpoint.id = id
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
        secrets.set(id + "_api_key", endpoint.apiKey)

        for (listener in listeners) {
            listener.onApiEndpointChange()
        }
        return id
    }

    /**
     * Rename-safe edit: the endpoint carries its stable id, so this simply
     * re-saves under it. Kept for source compatibility (older callers passed the
     * old label); the label argument is no longer used to locate the record.
     */
    fun editEndpoint(context: Context, label: String, endpoint: ApiEndpointObject) {
        setApiEndpoint(endpoint)
    }

    fun migrateFromLegacyEndpoint(context: Context) {
        if (getApiEndpointsList(context).isEmpty()) {
            val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val label = "Default"
            val host = sp.getString("custom_host", "https://api.openai.com/v1/")
            val apiKey: String = EncryptedPreferences.getEncryptedPreference(context, "api", "api_key")

            // The built-in Default profile keeps the reserved constant id so the
            // default per-chat reference (Preferences.getApiEndpointId) resolves.
            setApiEndpoint(ApiEndpointObject(label, host!!, apiKey, id = ApiEndpointObject.DEFAULT_ENDPOINT_ID))
        }
    }

    fun getApiEndpointsList(context: Context): ArrayList<ApiEndpointObject> = getApiEndpointsList()

    internal fun getApiEndpointsList(): ArrayList<ApiEndpointObject> {
        val list = ArrayList<ApiEndpointObject>()
        for (key in preferences.all.keys) {
            if (key.endsWith("_label")) {
                val id = key.removeSuffix("_label")
                list.add(getApiEndpoint(id))
            }
        }

        // R8 bug fix
        if (list == null) {
            return ArrayList()
        }

        return list
    }

    fun getApiEndpointByUrlOrNull(context: Context, url: String): ApiEndpointObject? {
        val list = getApiEndpointsList()
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
