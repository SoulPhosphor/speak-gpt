/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Provider adapters normalize their official model metadata into the shared
 * tri-state contract. Unknown fields never become Unsupported. */
object ImageCapabilityMetadata {

    fun fromModelEntry(
        entry: JsonObject?,
        provider: ImageCapabilityProvider
    ): ImageCapability {
        entry ?: return ImageCapability.UNKNOWN

        when (provider) {
            ImageCapabilityProvider.OPENROUTER -> {
                inputModalities(entry.objectOrNull("architecture"))?.let { return it }
            }
            ImageCapabilityProvider.VENICE -> {
                booleanCapability(
                    entry.objectOrNull("model_spec")
                        ?.objectOrNull("capabilities"),
                    "supportsVision"
                )?.let { return it }
            }
            ImageCapabilityProvider.FEATHERLESS -> {
                booleanCapability(entry, "vision_supported")?.let { return it }
                booleanCapability(entry.objectOrNull("features"), "image_input")?.let { return it }
                inputModalities(entry)?.let { return it }
                capabilityCollection(entry.get("capabilities"), absenceIsUnsupported = true)
                    ?.let { return it }
            }
            ImageCapabilityProvider.NANOGPT -> {
                val capabilities = entry.get("capabilities")
                booleanCapability(capabilities?.objectOrNull(), "vision")?.let { return it }
                booleanCapability(capabilities?.objectOrNull(), "Vision")?.let { return it }
                capabilityCollection(capabilities, absenceIsUnsupported = true)?.let { return it }
                inputModalities(entry)?.let { return it }
            }
            else -> Unit
        }

        // Shared machine-readable shapes used by OpenAI-compatible catalogs
        // and custom proxies. Only an actually present field is authoritative.
        inputModalities(entry.objectOrNull("architecture"))?.let { return it }
        inputModalities(entry)?.let { return it }
        for ((obj, key) in listOf(
            entry to "supportsVision",
            entry to "vision_supported",
            entry.objectOrNull("capabilities") to "supportsVision",
            entry.objectOrNull("capabilities") to "vision",
            entry.objectOrNull("model_spec")?.objectOrNull("capabilities") to "supportsVision",
            entry.objectOrNull("features") to "image_input"
        )) {
            booleanCapability(obj, key)?.let { return it }
        }
        return ImageCapability.UNKNOWN
    }

    /** Parse a model-list or model-detail response. Unknown entries are included
     * so a caller can explicitly invalidate stale metadata for a model whose
     * refreshed record no longer supplies a definitive capability. */
    fun capabilitiesFromResponse(
        body: String?,
        provider: ImageCapabilityProvider
    ): Map<String, ImageCapability>? {
        if (body.isNullOrBlank()) return null
        val root = try {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val entries = ArrayList<JsonObject>()
        val data = root.get("data")
        when {
            data?.isJsonArray == true -> data.asJsonArray.forEach { element ->
                element.objectOrNull()?.let(entries::add)
            }
            data?.isJsonObject == true && data.asJsonObject.has("id") ->
                entries.add(data.asJsonObject)
            root.has("id") -> entries.add(root)
        }
        if (entries.isEmpty()) return null

        return linkedMapOf<String, ImageCapability>().apply {
            for (entry in entries) {
                val id = entry.stringOrNull("id") ?: continue
                put(id, fromModelEntry(entry, provider))
            }
        }.takeIf { it.isNotEmpty() }
    }

    /** OpenRouter's provider-details response has model-wide architecture plus
     * optional provider records. A pinned provider is definitive only when its
     * own record carries image metadata; model-wide support is not silently
     * attributed to every downstream. */
    fun openRouterPinnedProviderCapability(
        body: String?,
        providerSlug: String?
    ): ImageCapability {
        if (body.isNullOrBlank() || providerSlug.isNullOrBlank()) return ImageCapability.UNKNOWN
        val root = try {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return ImageCapability.UNKNOWN
        val data = root.objectOrNull("data") ?: root
        val endpoints = data.get("endpoints")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return ImageCapability.UNKNOWN
        val wanted = providerSlug.trim()
        val record = endpoints.firstOrNull { element ->
            val obj = element.objectOrNull() ?: return@firstOrNull false
            listOf("tag", "provider_slug", "provider_name", "name")
                .mapNotNull { key -> obj.stringOrNull(key) }
                .any { it.equals(wanted, ignoreCase = true) }
        }?.objectOrNull() ?: return ImageCapability.UNKNOWN
        return fromModelEntry(record, ImageCapabilityProvider.GENERIC)
    }

    private fun inputModalities(obj: JsonObject?): ImageCapability? {
        obj ?: return null
        val element = obj.get("input_modalities") ?: obj.get("inputModalities") ?: return null
        val values = strings(element) ?: return null
        return if (values.any { it == "image" || it == "vision" }) {
            ImageCapability.SUPPORTED
        } else {
            ImageCapability.UNSUPPORTED
        }
    }

    private fun capabilityCollection(
        element: JsonElement?,
        absenceIsUnsupported: Boolean
    ): ImageCapability? {
        val values = strings(element) ?: return null
        val supports = values.any {
            it == "vision" || it == "image" || it == "image_input" ||
                it == "image-input" || it == "multimodal"
        }
        return when {
            supports -> ImageCapability.SUPPORTED
            absenceIsUnsupported -> ImageCapability.UNSUPPORTED
            else -> null
        }
    }

    private fun strings(element: JsonElement?): Set<String>? {
        element ?: return null
        val raw = when {
            element.isJsonArray -> element.asJsonArray.mapNotNull {
                it.takeIf { item -> item.isJsonPrimitive }?.asString
            }
            element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                listOf(element.asString)
            else -> return null
        }
        return raw.mapTo(linkedSetOf()) { it.trim().lowercase() }
    }

    private fun booleanCapability(obj: JsonObject?, key: String): ImageCapability? {
        val value = obj?.get(key)?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
        }?.asBoolean ?: return null
        return if (value) ImageCapability.SUPPORTED else ImageCapability.UNSUPPORTED
    }

    private fun JsonElement?.objectOrNull(): JsonObject? =
        this?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key).objectOrNull()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf { it.isNotBlank() }
}
