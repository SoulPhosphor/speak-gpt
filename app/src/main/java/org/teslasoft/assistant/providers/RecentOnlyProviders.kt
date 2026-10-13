/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.providers

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.net.URLEncoder

/**
 * The last few providers the user chose in Only mode, per model and endpoint.
 * Quick Settings lists them in its Provider row so a recent provider can be
 * switched back to without opening the full Choose Provider screen.
 *
 * Only-mode choices alone are recorded: a provider OpenRouter picked under
 * Automatic or Preferred was never the user's choice. Kept on this device in
 * its own store, separate from favorites, and not part of any backup.
 */
object RecentOnlyProviders {

    const val LIMIT = 3

    /** [slug] moved to the front of [recents], trimmed to [LIMIT]. */
    fun bump(recents: List<String>, slug: String): List<String> {
        if (slug.isBlank()) return recents.take(LIMIT)
        return (listOf(slug) + recents.filterNot { it.equals(slug, ignoreCase = true) }).take(LIMIT)
    }

    /** The dropdown's entries: the provider in use first, then the other recent
     *  ones. The provider in use always appears, so a model set to Only before
     *  this list existed still shows its provider. */
    fun options(current: String, recents: List<String>): List<String> =
        if (current.isBlank()) recents.take(LIMIT) else bump(recents, current)
}

class RecentOnlyProvidersStore internal constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val FILE = "recent_only_providers"
        private const val NAME_PREFIX = "name:"
        private const val RECENTS_PREFIX = "recents:"

        @Volatile private var instance: RecentOnlyProvidersStore? = null

        fun get(context: Context): RecentOnlyProvidersStore =
            instance ?: synchronized(this) {
                instance ?: RecentOnlyProvidersStore(
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }

    private val gson = Gson()

    fun recents(endpointId: String, modelId: String): List<String> {
        val json = prefs.getString(key(endpointId, modelId), null) ?: return emptyList()
        return try {
            gson.fromJson(json, Array<String>::class.java)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(endpointId: String, modelId: String, slug: String) {
        if (slug.isBlank()) return
        val updated = RecentOnlyProviders.bump(recents(endpointId, modelId), slug)
        prefs.edit().putString(key(endpointId, modelId), gson.toJson(updated)).apply()
    }

    fun clear(endpointId: String, modelId: String) {
        prefs.edit().remove(key(endpointId, modelId)).apply()
    }

    /** The provider's display name as OpenRouter last reported it. */
    fun rememberName(slug: String, name: String?) {
        if (slug.isBlank() || name.isNullOrBlank()) return
        prefs.edit().putString(NAME_PREFIX + encode(slug.lowercase()), name).apply()
    }

    /** The remembered display name, or the slug when none was ever reported. */
    fun displayName(slug: String): String =
        prefs.getString(NAME_PREFIX + encode(slug.lowercase()), null) ?: slug

    // Preference keys are written as XML attributes, so every part is
    // URL-encoded: no control characters reach the file, and "/" stays an
    // unambiguous separator.
    private fun key(endpointId: String, modelId: String) =
        RECENTS_PREFIX + encode(endpointId) + "/" + encode(modelId)

    private fun encode(part: String): String = URLEncoder.encode(part, "UTF-8")
}
