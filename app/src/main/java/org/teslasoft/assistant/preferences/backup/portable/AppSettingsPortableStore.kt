/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.LogitBiasConfigPreferences
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.tts.AppTtsVoicePreferences
import org.teslasoft.assistant.util.AtomicFileWriter

/** Reads only the fields classified as portable configuration (export side). */
object AppSettingsPortableStore {
    fun capture(context: Context): Result<AppSettingsPortableData> = runCatching {
        val app = context.applicationContext
        val defaultSettings = SecurePrefs.get(app, AppTtsVoicePreferences.STORE_NAME)
        require(!SecurePrefs.isLockedName(AppTtsVoicePreferences.STORE_NAME))
        val catalog = app.getSharedPreferences("logit_bias_config", Context.MODE_PRIVATE)
        val catalogValues = read(catalog, AppSettingsPortabilityPolicy.Store.LOGIT_CATALOG)
        val configs = LogitBiasConfigPreferences.getLogitBiasConfigPreferences(app).getAllConfigs()
            .associate { config ->
                val id = requireNotNull(config["id"])
                id to read(
                    app.getSharedPreferences("logit_bias_config_$id", Context.MODE_PRIVATE),
                    AppSettingsPortabilityPolicy.Store.LOGIT_CONFIG
                )
            }
        val savedSources = File(app.filesDir, "tts/saved_sources.json")
        AppSettingsPortableData(
            globalSettings = read(
                app.getSharedPreferences("settings", Context.MODE_PRIVATE),
                AppSettingsPortabilityPolicy.Store.GLOBAL_SETTINGS
            ),
            defaultSettings = read(
                defaultSettings,
                AppSettingsPortabilityPolicy.Store.DEFAULT_SETTINGS
            ),
            storageOptions = read(
                app.getSharedPreferences("storage_health", Context.MODE_PRIVATE),
                AppSettingsPortabilityPolicy.Store.STORAGE_OPTIONS
            ),
            savedTtsSourcesJson = if (savedSources.exists()) savedSources.readText(Charsets.UTF_8)
                else JSONObject().put("version", 1).put("entries", JSONArray()).toString(),
            logitBiasCatalog = catalogValues,
            logitBiasConfigs = configs
        ).also { AppSettingsPortableCodec.encode(it) }
    }

    fun write(context: Context, target: File): Result<AppSettingsPortableData> = capture(context).mapCatching {
        require(AtomicFileWriter.writeAndVerify(target, AppSettingsPortableCodec.encode(it)))
        it
    }

    private fun read(
        preferences: SharedPreferences,
        store: AppSettingsPortabilityPolicy.Store
    ): Map<String, PortableSettingValue> = preferences.all.entries
        .filter { AppSettingsPortabilityPolicy.isPortable(store, it.key) }
        .associate { (key, value) -> key to portableValue(requireNotNull(value)) }

    private fun portableValue(value: Any): PortableSettingValue = when (value) {
        is String -> PortableSettingValue("string", value)
        is Boolean -> PortableSettingValue("boolean", value)
        is Int -> PortableSettingValue("int", value)
        is Long -> PortableSettingValue("long", value)
        is Float -> PortableSettingValue("float", value)
        is Set<*> -> {
            require(value.all { it is String })
            PortableSettingValue("string_set", value.filterIsInstance<String>().toSet())
        }
        else -> error("Unsupported preference value")
    }
}
