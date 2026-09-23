/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.teslasoft.assistant.preferences.tts.ManualTtsVoicesCodec

/** Type-preserving value from an audited SharedPreferences store. */
data class PortableSettingValue(val type: String, val value: Any)

data class AppSettingsPortableData(
    val globalSettings: Map<String, PortableSettingValue>,
    val defaultSettings: Map<String, PortableSettingValue>,
    val storageOptions: Map<String, PortableSettingValue>,
    val savedTtsSourcesJson: String,
    val logitBiasCatalog: Map<String, PortableSettingValue>,
    val logitBiasConfigs: Map<String, Map<String, PortableSettingValue>>,
    /** Voice IDs entered by hand for saved TTS sources; empty in backups made before they existed. */
    val manualTtsVoicesJson: String = ManualTtsVoicesCodec.EMPTY
) {
    val recordCount: Long
        get() = globalSettings.size.toLong() + defaultSettings.size + storageOptions.size +
            logitBiasCatalog.size + logitBiasConfigs.values.sumOf { it.size.toLong() } +
            JSONObject(savedTtsSourcesJson).getJSONArray("entries").length() +
            JSONObject(manualTtsVoicesJson).getJSONArray("entries").length()
}

/**
 * Maintained classification boundary for general app configuration.
 *
 * The two app settings stores are portable by default. Only content already
 * owned by another recovery category and explicitly unsafe/runtime keys are
 * denied. The mixed-purpose storage-health file is the opposite: only the
 * listed user choices are portable. Unknown keys in a settings payload must
 * pass this same policy before any live write.
 */
object AppSettingsPortabilityPolicy {
    enum class Store { GLOBAL_SETTINGS, DEFAULT_SETTINGS, STORAGE_OPTIONS, LOGIT_CATALOG, LOGIT_CONFIG }

    private val portableStorageOptions = setOf(
        "backup.last_recovery_protected",
        "backup.auto_frequency",
        "readable.scope_all",
        "readable.format",
        "readable.categories"
    )

    private val exactDenied = setOf(
        "api_key",
        "system_message",
        "unavailable_tts_voice",
        "last_known_good_tts_provider",
        "last_known_good_tts_voice",
        "last_known_good_tts_model",
        "playground_input",
        "playground_output",
        "__migrated_to_encrypted"
    )

    private val runtimePrefixes = listOf(
        "summarizer_summary", "summarizer_folded", "summarizer_episode",
        "summarizer_errors", "summarizer_catch_up", "summarizer_projection",
        "regeneration_", "manual_compaction_boundary", "pending_",
        "conversation_pending", "conversation_mode_pending", "last_success"
    )

    private val forbiddenSegments = Regex(
        "(^|[_\\-.])(api[_-]?key|private[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|id[_-]?token|session[_-]?token|oauth|bearer|authorization|password|passwd|secret|cookie|credential)([_\\-.]|$)",
        RegexOption.IGNORE_CASE
    )

    fun isPortable(store: Store, key: String): Boolean {
        if (key.isBlank() || key.length > 256 || key.any { it.code < 0x20 }) return false
        if (forbiddenSegments.containsMatchIn(key)) return false
        return when (store) {
            Store.STORAGE_OPTIONS -> key in portableStorageOptions
            Store.GLOBAL_SETTINGS, Store.DEFAULT_SETTINGS ->
                key !in exactDenied && !key.endsWith("_seeded") &&
                    runtimePrefixes.none(key::startsWith)
            Store.LOGIT_CATALOG -> key == "configs"
            Store.LOGIT_CONFIG -> true
        }
    }
}

object AppSettingsPortableCodec {
    const val ENTRY_NAME = "app_settings.json"
    const val SCHEMA_VERSION = 1
    const val FORMAT = "app-settings-v1"

    sealed interface Result {
        data class Ok(val data: AppSettingsPortableData) : Result
        data object Invalid : Result
    }

    fun encode(data: AppSettingsPortableData): String {
        validateData(data)
        return JSONObject()
            .put("format", FORMAT)
            .put("schema_version", SCHEMA_VERSION)
            .put("global_settings", encodeMap(data.globalSettings))
            .put("default_settings", encodeMap(data.defaultSettings))
            .put("storage_options", encodeMap(data.storageOptions))
            .put("saved_tts_sources", strictObject(data.savedTtsSourcesJson))
            .put("manual_tts_voices", strictObject(data.manualTtsVoicesJson))
            .put("logit_bias", JSONObject()
                .put("catalog", encodeMap(data.logitBiasCatalog))
                .put("configs", JSONObject().apply {
                    data.logitBiasConfigs.toSortedMap().forEach { (id, values) ->
                        put(id, encodeMap(values))
                    }
                }))
            .toString()
    }

    fun parse(json: String): Result = try {
        val root = strictObject(json)
        val keys = setOf(
            "format", "schema_version", "global_settings", "default_settings",
            "storage_options", "saved_tts_sources", "logit_bias"
        )
        // Backups made before manual Voice IDs existed simply have none.
        require(root.keys().asSequence().toSet() in setOf(keys, keys + "manual_tts_voices"))
        require(root.get("format") == FORMAT && root.get("schema_version") == SCHEMA_VERSION)
        val logit = root.getJSONObject("logit_bias")
        requireExactKeys(logit, setOf("catalog", "configs"))
        val configsObject = logit.getJSONObject("configs")
        val configs = linkedMapOf<String, Map<String, PortableSettingValue>>()
        configsObject.keys().forEach { id ->
            require(isSafeLogitId(id))
            configs[id] = decodeMap(
                configsObject.getJSONArray(id), AppSettingsPortabilityPolicy.Store.LOGIT_CONFIG
            )
        }
        val data = AppSettingsPortableData(
            decodeMap(root.getJSONArray("global_settings"), AppSettingsPortabilityPolicy.Store.GLOBAL_SETTINGS),
            decodeMap(root.getJSONArray("default_settings"), AppSettingsPortabilityPolicy.Store.DEFAULT_SETTINGS),
            decodeMap(root.getJSONArray("storage_options"), AppSettingsPortabilityPolicy.Store.STORAGE_OPTIONS),
            validateSavedTtsSources(root.getJSONObject("saved_tts_sources")),
            decodeMap(logit.getJSONArray("catalog"), AppSettingsPortabilityPolicy.Store.LOGIT_CATALOG),
            configs,
            if (root.has("manual_tts_voices")) ManualTtsVoicesCodec.encode(
                ManualTtsVoicesCodec.decode(root.getJSONObject("manual_tts_voices").toString()))
            else ManualTtsVoicesCodec.EMPTY
        )
        validateData(data)
        Result.Ok(data)
    } catch (_: Exception) {
        Result.Invalid
    }

    private fun encodeMap(values: Map<String, PortableSettingValue>) = JSONArray().apply {
        values.toSortedMap().forEach { (key, setting) ->
            put(JSONObject().put("key", key).put("type", setting.type).put("value",
                when (setting.type) {
                    "string_set" -> JSONArray((setting.value as Set<*>).map { it as String }.sorted())
                    else -> setting.value
                }))
        }
    }

    private fun decodeMap(
        array: JSONArray,
        store: AppSettingsPortabilityPolicy.Store
    ): Map<String, PortableSettingValue> {
        val values = linkedMapOf<String, PortableSettingValue>()
        repeat(array.length()) { index ->
            val item = array.getJSONObject(index)
            requireExactKeys(item, setOf("key", "type", "value"))
            val key = item.get("key") as? String ?: error("key")
            require(AppSettingsPortabilityPolicy.isPortable(store, key) && key !in values)
            val type = item.get("type") as? String ?: error("type")
            val raw = item.get("value")
            val value: Any = when (type) {
                "string" -> raw as? String ?: error("string")
                "boolean" -> raw as? Boolean ?: error("boolean")
                "int" -> (raw as? Int) ?: error("int")
                "long" -> when (raw) {
                    is Long -> raw
                    is Int -> raw.toLong()
                    else -> error("long")
                }
                "float" -> when (raw) {
                    is Number -> raw.toFloat().also { require(it.isFinite()) }
                    else -> error("float")
                }
                "string_set" -> (raw as? JSONArray)?.let { json ->
                    (0 until json.length()).map { json.get(it) as? String ?: error("set") }.toSet()
                        .also { require(it.size == json.length()) }
                } ?: error("set")
                else -> error("type")
            }
            values[key] = PortableSettingValue(type, value)
        }
        return values
    }

    private fun validateData(data: AppSettingsPortableData) {
        listOf(
            AppSettingsPortabilityPolicy.Store.GLOBAL_SETTINGS to data.globalSettings,
            AppSettingsPortabilityPolicy.Store.DEFAULT_SETTINGS to data.defaultSettings,
            AppSettingsPortabilityPolicy.Store.STORAGE_OPTIONS to data.storageOptions,
            AppSettingsPortabilityPolicy.Store.LOGIT_CATALOG to data.logitBiasCatalog
        ).forEach { (store, values) ->
            require(values.keys.all { AppSettingsPortabilityPolicy.isPortable(store, it) })
            encodeMap(values)
        }
        data.logitBiasConfigs.forEach { (id, values) ->
            require(isSafeLogitId(id))
            require(values.keys.all {
                AppSettingsPortabilityPolicy.isPortable(AppSettingsPortabilityPolicy.Store.LOGIT_CONFIG, it)
            })
            encodeMap(values)
        }
        validateSavedTtsSources(strictObject(data.savedTtsSourcesJson))
        ManualTtsVoicesCodec.decode(data.manualTtsVoicesJson)
        val catalogIds = catalogIds(data.logitBiasCatalog)
        require(data.logitBiasConfigs.keys == catalogIds)
    }

    private fun validateSavedTtsSources(root: JSONObject): String {
        requireExactKeys(root, setOf("version", "entries"))
        require(root.get("version") == 1)
        val entries = root.getJSONArray("entries")
        val ids = HashSet<String>()
        repeat(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            requireExactKeys(item, setOf("id", "endpointId", "modelId", "routing"))
            val id = item.get("id") as? String ?: error("id")
            val endpointId = item.get("endpointId") as? String ?: error("endpoint")
            val modelId = item.get("modelId") as? String ?: error("model")
            require(id.isNotBlank() && endpointId.isNotBlank() && modelId.isNotBlank() && ids.add(id))
            val routing = item.getJSONObject("routing")
            requireExactKeys(routing, setOf("mode", "selectedProvider", "providerOrder", "allowFallbacks"))
            val mode = routing.get("mode") as? String ?: error("mode")
            val selected = routing.get("selectedProvider") as? String ?: error("provider")
            require(mode in setOf("automatic", "preferred", "only"))
            require(routing.get("allowFallbacks") is Boolean)
            val order = routing.getJSONArray("providerOrder")
            val providers = (0 until order.length()).map {
                (order.get(it) as? String)?.also { provider -> require(provider.isNotBlank()) }
                    ?: error("provider")
            }
            require(providers.distinct().size == providers.size)
            require(mode != "only" || selected.isNotBlank())
        }
        return root.toString()
    }

    private fun catalogIds(catalog: Map<String, PortableSettingValue>): Set<String> {
        val raw = catalog["configs"] ?: return emptySet()
        require(raw.type == "string")
        val array = JSONTokener(raw.value as String).nextValue() as? JSONArray ?: error("catalog")
        val ids = linkedSetOf<String>()
        repeat(array.length()) { index ->
            val item = array.getJSONObject(index)
            require(item.length() == 2 && item.has("id") && item.has("label"))
            val id = item.get("id") as? String ?: error("id")
            require(item.get("label") is String && isSafeLogitId(id) && ids.add(id))
        }
        return ids
    }

    private fun isSafeLogitId(id: String): Boolean =
        id.length in 1..128 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun strictObject(json: String): JSONObject {
        val tokens = JSONTokener(json)
        val value = tokens.nextValue()
        require(value is JSONObject && tokens.nextClean() == '\u0000')
        return value
    }

    private fun requireExactKeys(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected)
    }
}
