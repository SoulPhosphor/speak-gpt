/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.preferences.memory

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Durable, version-local references to the Memory and Lorebook entries that
 * reached one assistant response's final request. Text is deliberately not
 * copied: the read-only viewer resolves these stable ids against the stores.
 */
data class ActiveMemoryReference(
    val source: String,
    val id: String
) {
    companion object {
        const val SOURCE_MEMORY = "memory"
        const val SOURCE_LOREBOOK = "lorebook"
    }
}

object ActiveMemoryAttribution {
    fun encode(references: List<ActiveMemoryReference>): String? {
        val valid = references.filter {
            it.id.isNotBlank() &&
                (it.source == ActiveMemoryReference.SOURCE_MEMORY ||
                    it.source == ActiveMemoryReference.SOURCE_LOREBOOK)
        }
        return valid.takeIf { it.isNotEmpty() }?.let(Gson()::toJson)
    }

    fun decode(json: String?): List<ActiveMemoryReference> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<ActiveMemoryReference>>() {}.type
            Gson().fromJson<List<ActiveMemoryReference>>(json, type).orEmpty()
                .filter {
                    it.id.isNotBlank() &&
                        (it.source == ActiveMemoryReference.SOURCE_MEMORY ||
                            it.source == ActiveMemoryReference.SOURCE_LOREBOOK)
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun fromFinalSelection(memoryIds: List<String>, lorebookIds: List<String>): List<ActiveMemoryReference> =
        memoryIds.map { ActiveMemoryReference(ActiveMemoryReference.SOURCE_MEMORY, it) } +
            lorebookIds.map { ActiveMemoryReference(ActiveMemoryReference.SOURCE_LOREBOOK, it) }
}
