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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.teslasoft.assistant.util.StableId
import java.lang.Exception
import java.lang.reflect.Type
import androidx.core.content.edit

class LogitBiasConfigPreferences private constructor(private var preferences: SharedPreferences) {
    companion object {
        private var logitBiasConfigPreferences: LogitBiasConfigPreferences? = null

        fun getLogitBiasConfigPreferences(context: Context): LogitBiasConfigPreferences {
            if (logitBiasConfigPreferences == null) {
                logitBiasConfigPreferences = LogitBiasConfigPreferences(context.getSharedPreferences("logit_bias_config", Context.MODE_PRIVATE))
            }

            return logitBiasConfigPreferences!!
        }

        /** Test seam: build against an injected (in-memory) SharedPreferences. */
        internal fun createForTest(preferences: SharedPreferences): LogitBiasConfigPreferences =
            LogitBiasConfigPreferences(preferences)
    }

    private var listeners: ArrayList<OnLogitBiasConfigChangeListener> = ArrayList()

    fun getString(key: String, defValue: String): String {
        return preferences.getString(key, defValue)!!
    }

    fun putString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }

    /**
     * Create a new config with a fresh, stable random id. The id is minted ONCE
     * here and never recomputed from [label], so the bias values stored under
     * `logit_bias_config_<id>` and any per-chat/global selection of this config
     * survive a later rename.
     */
    fun addConfig(label: String) {
        val gson = Gson()

        var list: ArrayList<HashMap<String, String>> = getAllConfigs()

        // Bugfix for R8 minifier, yes It make no sense for regular programmer, but it's a bug in R8 minifier
        if (list == null) list = arrayListOf()

        list.add(hashMapOf("label" to label, "id" to StableId.newId("lb-")))

        // Bugfix for R8 minifier, yes It make no sense for regular programmer, but it's a bug in R8 minifier
        if (list == null) list = arrayListOf()

        putString("configs", gson.toJson(list))

        for (listener in listeners) {
            listener.onLogitBiasConfigChange()
        }
    }

    /**
     * Rename a config: change only its label, keeping the SAME id. The bias
     * values stay under `logit_bias_config_<id>` — a rename no longer deletes
     * the config and recreates it under a name-derived id, so nothing has to be
     * moved between preference files.
     */
    fun editConfig(id: String, newLabel: String) {
        val gson = Gson()

        var list: ArrayList<HashMap<String, String>> = getAllConfigs()

        if (list == null) list = arrayListOf()

        for (config in list) {
            if (config["id"] == id) {
                config["label"] = newLabel
            }
        }

        putString("configs", gson.toJson(list))

        for (listener in listeners) {
            listener.onLogitBiasConfigChange()
        }
    }

    fun deleteConfig(configId: String) {
        val gson = Gson()

        var list: ArrayList<HashMap<String, String>> = getAllConfigs()

        // Bugfix for R8 minifier, yes It make no sense for regular programmer, but it's a bug in R8 minifier
        if (list == null) list = arrayListOf()

        list = list.filter {
            it["id"] != configId
        } as ArrayList<HashMap<String, String>>

        // Bugfix for R8 minifier, yes It make no sense for regular programmer, but it's a bug in R8 minifier
        if (list == null) list = arrayListOf()

        putString("configs", gson.toJson(list))

        for (listener in listeners) {
            listener.onLogitBiasConfigChange()
        }
    }

    fun getAllConfigs(): ArrayList<HashMap<String, String>> {
        val gson = Gson()
        val json = getString("configs", "[]")
        val type: Type = TypeToken.getParameterized(ArrayList::class.java, HashMap::class.java).type

        var list =  try {
            gson.fromJson<Any>(json, type) as ArrayList<HashMap<String, String>>
        } catch (e: Exception) {
            arrayListOf()
        }

        // R8 bug fix
        if (list == null) list = arrayListOf()

        return list ?: arrayListOf()
    }

    fun getConfigById(configId: String): HashMap<String, String>? {
        val list = getAllConfigs()
        return list.find {
            it["id"] == configId
        }
    }

    fun interface OnLogitBiasConfigChangeListener {
        fun onLogitBiasConfigChange()
    }

    // movePreferences(oldId, newId) was removed with the stable-id fix (July
    // 2026). Its only caller was the rename flow, and it existed solely because
    // a rename used to change the config id (Hash.hash(label)), which orphaned
    // the bias values stored under logit_bias_config_<id>. The id is now stable
    // across a rename, so nothing needs to be moved between preference files.
}
