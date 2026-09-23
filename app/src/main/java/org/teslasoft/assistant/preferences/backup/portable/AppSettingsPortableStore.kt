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
import org.teslasoft.assistant.preferences.tts.ManualTtsVoicesCodec
import org.teslasoft.assistant.preferences.tts.ManualTtsVoicesPreferences
import org.teslasoft.assistant.util.AtomicFileWriter

/** Reads and replaces only the fields classified as portable configuration. */
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
        val manualVoices = File(app.filesDir, ManualTtsVoicesPreferences.RELATIVE_PATH)
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
            logitBiasConfigs = configs,
            manualTtsVoicesJson = if (manualVoices.exists()) manualVoices.readText(Charsets.UTF_8)
                else ManualTtsVoicesCodec.EMPTY
        ).also { AppSettingsPortableCodec.encode(it) }
    }

    fun write(context: Context, target: File): Result<AppSettingsPortableData> = capture(context).mapCatching {
        require(AtomicFileWriter.writeAndVerify(target, AppSettingsPortableCodec.encode(it)))
        it
    }

    fun replace(context: Context, data: AppSettingsPortableData): Boolean {
        return try {
            val app = context.applicationContext
            val defaultSettings = SecurePrefs.get(app, AppTtsVoicePreferences.STORE_NAME)
            if (SecurePrefs.isLockedName(AppTtsVoicePreferences.STORE_NAME)) return false
            val normalized = (AppSettingsPortableCodec.parse(AppSettingsPortableCodec.encode(data)) as?
                AppSettingsPortableCodec.Result.Ok)?.data ?: return false
            if (!replaceMap(app.getSharedPreferences("settings", Context.MODE_PRIVATE),
                    AppSettingsPortabilityPolicy.Store.GLOBAL_SETTINGS, normalized.globalSettings)) return false
            if (!replaceMap(defaultSettings,
                    AppSettingsPortabilityPolicy.Store.DEFAULT_SETTINGS, normalized.defaultSettings)) return false
            if (!replaceMap(app.getSharedPreferences("storage_health", Context.MODE_PRIVATE),
                    AppSettingsPortabilityPolicy.Store.STORAGE_OPTIONS, normalized.storageOptions)) return false

            val catalogPrefs = app.getSharedPreferences("logit_bias_config", Context.MODE_PRIVATE)
            val oldIds = LogitBiasConfigPreferences.getLogitBiasConfigPreferences(app).getAllConfigs()
                .mapNotNull { it["id"] }.toSet()
            if (!replaceMap(catalogPrefs, AppSettingsPortabilityPolicy.Store.LOGIT_CATALOG,
                    normalized.logitBiasCatalog)) return false
            for (id in oldIds + normalized.logitBiasConfigs.keys) {
                if (!replaceMap(app.getSharedPreferences("logit_bias_config_$id", Context.MODE_PRIVATE),
                        AppSettingsPortabilityPolicy.Store.LOGIT_CONFIG,
                        normalized.logitBiasConfigs[id].orEmpty())) return false
            }

            val savedSources = File(app.filesDir, "tts/saved_sources.json")
            val parent = savedSources.parentFile ?: return false
            if (!parent.isDirectory && !parent.mkdirs()) return false
            if (!AtomicFileWriter.writeAndVerify(savedSources, normalized.savedTtsSourcesJson)) return false
            AtomicFileWriter.writeAndVerify(File(app.filesDir, ManualTtsVoicesPreferences.RELATIVE_PATH),
                normalized.manualTtsVoicesJson)
        } catch (_: Exception) {
            false
        }
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

    private fun replaceMap(
        preferences: SharedPreferences,
        store: AppSettingsPortabilityPolicy.Store,
        values: Map<String, PortableSettingValue>
    ): Boolean {
        if (values.keys.any { !AppSettingsPortabilityPolicy.isPortable(store, it) }) return false
        val editor = preferences.edit()
        preferences.all.keys.filter { AppSettingsPortabilityPolicy.isPortable(store, it) }
            .forEach(editor::remove)
        values.forEach { (key, setting) ->
            when (setting.type) {
                "string" -> editor.putString(key, setting.value as String)
                "boolean" -> editor.putBoolean(key, setting.value as Boolean)
                "int" -> editor.putInt(key, setting.value as Int)
                "long" -> editor.putLong(key, setting.value as Long)
                "float" -> editor.putFloat(key, setting.value as Float)
                "string_set" -> @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, LinkedHashSet(setting.value as Set<String>))
                else -> return false
            }
        }
        return editor.commit()
    }
}
