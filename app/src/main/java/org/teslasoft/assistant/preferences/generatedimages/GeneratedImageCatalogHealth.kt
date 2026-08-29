/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context
import androidx.core.content.edit

enum class GeneratedImageCatalogStorageState {
    AVAILABLE,
    LOCKED,
    CORRUPT,
    UNAVAILABLE
}

/** Plaintext health metadata only. User data remains in SQLCipher. This flag
 * prevents a corrupt catalog from being silently reopened as an empty one. */
object GeneratedImageCatalogHealth {
    private const val FILE = "storage_health"
    private const val KEY_CORRUPT = "health.generated_images.corrupt"

    fun isCorrupt(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_CORRUPT, false)
    } catch (_: Exception) {
        false
    }

    @Suppress("UNUSED_PARAMETER")
    fun markCorrupt(context: Context, reason: String) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        try {
            if (!prefs.getBoolean(KEY_CORRUPT, false)) {
                prefs.edit(commit = true) { putBoolean(KEY_CORRUPT, true) }
            }
        } catch (_: Exception) { }
        // The state itself is the durable signal. Phase 1 has no gallery UI,
        // and does not invent unapproved recovery wording; later readers must
        // surface CORRUPT rather than treating it as an empty catalog.
    }

    @androidx.annotation.VisibleForTesting
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit(commit = true) { remove(KEY_CORRUPT) }
    }
}
