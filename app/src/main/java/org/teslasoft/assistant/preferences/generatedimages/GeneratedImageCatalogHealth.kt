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
    NEEDS_RECOVERY,
    UNAVAILABLE
}

/** Plaintext health metadata only. User data remains in SQLCipher. This flag
 * prevents a corrupt catalog from being silently reopened as an empty one. */
object GeneratedImageCatalogHealth {
    private const val FILE = "storage_health"
    private const val KEY_CORRUPT = "health.generated_images.corrupt"
    private const val KEY_PROVISIONED = "health.generated_images.provisioned"
    private const val KEY_NEEDS_RECOVERY = "health.generated_images.needs_recovery"

    private val catalogAssetPattern = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
            "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.[A-Za-z0-9]{2,8}" +
            "(?:\\.catalogtmp)?$"
    )

    fun isCorrupt(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_CORRUPT, false)
    } catch (_: Exception) {
        false
    }

    fun isProvisioned(context: Context): Boolean = boolean(context, KEY_PROVISIONED)

    fun needsRecovery(context: Context): Boolean = boolean(context, KEY_NEEDS_RECOVERY)

    fun missingDatabaseRequiresRecovery(context: Context): Boolean {
        if (isProvisioned(context) || needsRecovery(context)) return true
        val images = context.applicationContext.getExternalFilesDir("images")
        return images?.listFiles()?.any { it.isFile && catalogAssetPattern.matches(it.name) } == true
    }

    fun markProvisioned(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        try {
            prefs.edit(commit = true) { putBoolean(KEY_PROVISIONED, true) }
        } catch (_: Exception) { }
    }

    fun markNeedsRecovery(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        try {
            prefs.edit(commit = true) { putBoolean(KEY_NEEDS_RECOVERY, true) }
        } catch (_: Exception) { }
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
            .edit(commit = true) {
                remove(KEY_CORRUPT)
                remove(KEY_PROVISIONED)
                remove(KEY_NEEDS_RECOVERY)
            }
    }

    private fun boolean(context: Context, key: String): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(key, false)
    } catch (_: Exception) {
        false
    }
}
