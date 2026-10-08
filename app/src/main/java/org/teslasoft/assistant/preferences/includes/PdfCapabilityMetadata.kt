/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Converts only explicit provider PDF/file metadata into tri-state evidence. */
object PdfCapabilityMetadata {
    fun capabilitiesFromResponse(
        body: String?,
        provider: PdfCapabilityProvider
    ): Map<String, PdfCapability>? {
        val root = parse(body) ?: return null
        val entries = ArrayList<JsonObject>()
        val data = root.get("data")
        when {
            data?.isJsonArray == true -> data.asJsonArray.forEach { it.objectOrNull()?.let(entries::add) }
            data?.isJsonObject == true && data.asJsonObject.has("id") -> entries += data.asJsonObject
            root.has("id") -> entries += root
        }
        return linkedMapOf<String, PdfCapability>().apply {
            entries.forEach { entry ->
                entry.stringOrNull("id")?.let { put(it, fromModelEntry(entry, provider)) }
            }
        }.takeIf { it.isNotEmpty() }
    }

    fun openRouterPinnedProviderCapability(body: String?, providerSlug: String?): PdfCapability {
        if (providerSlug.isNullOrBlank()) return PdfCapability.UNKNOWN
        val root = parse(body) ?: return PdfCapability.UNKNOWN
        val data = root.objectOrNull("data") ?: root
        val endpoints = data.get("endpoints")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return PdfCapability.UNKNOWN
        val record = endpoints.firstOrNull { element ->
            val entry = element.objectOrNull() ?: return@firstOrNull false
            listOf("tag", "provider_slug", "provider_name", "name")
                .mapNotNull { entry.stringOrNull(it) }
                .any { it.equals(providerSlug.trim(), true) }
        }?.objectOrNull() ?: return PdfCapability.UNKNOWN
        return fileModalities(record)
            ?: nativePdfBoolean(record)
            ?: PdfCapability.UNKNOWN
    }

    private fun fromModelEntry(entry: JsonObject, provider: PdfCapabilityProvider): PdfCapability {
        if (provider == PdfCapabilityProvider.OPENROUTER) {
            fileModalities(entry.objectOrNull("architecture"))?.let { return it }
        }
        if (provider == PdfCapabilityProvider.NANOGPT) {
            nativePdfBoolean(entry)?.let { return it }
            nativePdfBoolean(entry.objectOrNull("capabilities"))?.let { return it }
            val capabilities = strings(entry.get("capabilities"))
            if (capabilities != null) {
                return if (capabilities.any { it in NATIVE_PDF_NAMES }) {
                    PdfCapability.SUPPORTED
                } else PdfCapability.UNSUPPORTED
            }
        }
        fileModalities(entry.objectOrNull("architecture"))?.let { return it }
        fileModalities(entry)?.let { return it }
        return PdfCapability.UNKNOWN
    }

    private fun fileModalities(obj: JsonObject?): PdfCapability? {
        obj ?: return null
        val values = strings(obj.get("input_modalities") ?: obj.get("inputModalities")) ?: return null
        return if ("file" in values || "pdf" in values) PdfCapability.SUPPORTED
        else PdfCapability.UNSUPPORTED
    }

    private fun nativePdfBoolean(obj: JsonObject?): PdfCapability? {
        obj ?: return null
        for (key in listOf("native_pdf", "nativePdf", "supports_native_pdf", "supportsNativePdf")) {
            val value = obj.get(key)?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
            }?.asBoolean ?: continue
            return if (value) PdfCapability.SUPPORTED else PdfCapability.UNSUPPORTED
        }
        return null
    }

    private fun strings(element: JsonElement?): Set<String>? {
        element ?: return null
        val values = when {
            element.isJsonArray -> element.asJsonArray.mapNotNull {
                it.takeIf { item -> item.isJsonPrimitive }?.asString
            }
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> listOf(element.asString)
            else -> return null
        }
        return values.mapTo(linkedSetOf()) { it.trim().lowercase().replace('-', '_').replace(' ', '_') }
    }

    private fun parse(body: String?): JsonObject? = try {
        body?.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it) }
            ?.takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) { null }

    private fun JsonElement?.objectOrNull(): JsonObject? =
        this?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key).objectOrNull()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf { it.isNotBlank() }

    private val NATIVE_PDF_NAMES = setOf("native_pdf", "pdf_input", "pdf")
}
