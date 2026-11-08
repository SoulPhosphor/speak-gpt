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
import org.teslasoft.assistant.preferences.ModelPickerScope
import java.net.URLEncoder

/**
 * The last five providers the user chose in Only mode, per model and endpoint.
 * Quick Settings lists them in its Provider row (after the model's favorite
 * providers) and the picker's Recent row offers them in its provider
 * dropdown, so a recent provider can be switched back to without opening the
 * full Choose Provider screen.
 *
 * Only-mode choices alone are recorded: a provider OpenRouter picked under
 * Automatic or Preferred was never the user's choice. Kept on this device in
 * its own store, separate from favorites, and not part of any backup.
 */
object RecentOnlyProviders {

    const val LIMIT = 5

    /** [slug] moved to the front of [recents], trimmed to [LIMIT]. */
    fun bump(recents: List<String>, slug: String): List<String> {
        if (slug.isBlank()) return recents.take(LIMIT)
        return (listOf(slug) + recents.filterNot { it.equals(slug, ignoreCase = true) }).take(LIMIT)
    }

    /** [recents] without the providers OpenRouter's complete list no longer
     *  shows for the model. The provider in use ([current]) is always kept:
     *  the row must keep showing what is actually set. */
    fun withoutUnavailable(recents: List<String>, current: String, available: Set<String>): List<String> =
        recents.filter { it.equals(current, ignoreCase = true) || it.lowercase() in available }

    /** The dropdown's entries: the provider in use first, then the other recent
     *  ones. The provider in use always appears, so a model set to Only before
     *  this list existed still shows its provider. */
    fun options(current: String, recents: List<String>): List<String> =
        if (current.isBlank()) recents.take(LIMIT) else bump(recents, current)

    /** Quick Settings' Provider choices: the model's favorite providers first,
     *  then the last [LIMIT] used that are not favorites. The provider in use
     *  is always listed, first when it is in neither group. */
    fun quickSettingsOptions(current: String, favorites: List<String>, recents: List<String>): List<String> {
        val listed = (favorites + recents.take(LIMIT)).filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        return if (current.isBlank() || listed.any { it.equals(current, ignoreCase = true) }) listed
        else listOf(current) + listed
    }
}

class RecentOnlyProvidersStore internal constructor(
    private val prefs: SharedPreferences,
    /** Where providers' display names are kept. Names describe a provider,
     *  not a picker, so the Summarizer's store shares the chat's. */
    private val namePrefs: SharedPreferences = prefs
) {

    companion object {
        private const val FILE = "recent_only_providers"
        private const val SUMMARIZER_FILE = "summarizer_recent_only_providers"
        private const val NAME_PREFIX = "name:"
        private const val RECENTS_PREFIX = "recents:"

        @Volatile private var instance: RecentOnlyProvidersStore? = null
        @Volatile private var summarizerInstance: RecentOnlyProvidersStore? = null

        fun get(context: Context): RecentOnlyProvidersStore =
            instance ?: synchronized(this) {
                instance ?: RecentOnlyProvidersStore(
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }

        /** The last providers used for [scope]'s models. */
        fun get(context: Context, scope: ModelPickerScope): RecentOnlyProvidersStore = when (scope) {
            ModelPickerScope.CHAT -> get(context)
            ModelPickerScope.SUMMARIZER -> summarizerInstance ?: synchronized(this) {
                summarizerInstance ?: RecentOnlyProvidersStore(
                    context.applicationContext.getSharedPreferences(SUMMARIZER_FILE, Context.MODE_PRIVATE),
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { summarizerInstance = it }
            }
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

    /** Drops recents that a complete provider list shows are gone. */
    fun dropUnavailable(endpointId: String, modelId: String, current: String, available: Set<String>) {
        val recents = recents(endpointId, modelId)
        val kept = RecentOnlyProviders.withoutUnavailable(recents, current, available)
        if (kept.size != recents.size) {
            prefs.edit().putString(key(endpointId, modelId), gson.toJson(kept)).apply()
        }
    }

    fun clear(endpointId: String, modelId: String) {
        prefs.edit().remove(key(endpointId, modelId)).apply()
    }

    /** Clears every model's recents under [endpointId]. */
    fun clearEndpoint(endpointId: String) {
        val prefix = RECENTS_PREFIX + encode(endpointId) + "/"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** The provider's display name as OpenRouter last reported it. */
    fun rememberName(slug: String, name: String?) {
        if (slug.isBlank() || name.isNullOrBlank()) return
        namePrefs.edit().putString(NAME_PREFIX + encode(slug.lowercase()), name).apply()
    }

    /** The remembered display name, or the slug when none was ever reported. */
    fun displayName(slug: String): String =
        namePrefs.getString(NAME_PREFIX + encode(slug.lowercase()), null) ?: slug

    /** Every model's recent providers, for backups: (endpointId, modelId, slugs). */
    fun exportRecents(): List<Triple<String, String, List<String>>> =
        prefs.all.keys.filter { it.startsWith(RECENTS_PREFIX) }.mapNotNull { key ->
            val parts = key.removePrefix(RECENTS_PREFIX).split("/")
            if (parts.size != 2) return@mapNotNull null
            val endpointId = java.net.URLDecoder.decode(parts[0], "UTF-8")
            val modelId = java.net.URLDecoder.decode(parts[1], "UTF-8")
            Triple(endpointId, modelId, recents(endpointId, modelId))
        }

    /** Every remembered provider display name, for backups (slug to name). */
    fun exportNames(): Map<String, String> =
        namePrefs.all.entries.filter { it.key.startsWith(NAME_PREFIX) && it.value is String }
            .associate { java.net.URLDecoder.decode(it.key.removePrefix(NAME_PREFIX), "UTF-8") to it.value as String }

    /** Replaces every model's recent providers and the remembered names. */
    fun replaceAll(recents: List<Triple<String, String, List<String>>>, names: Map<String, String>) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(RECENTS_PREFIX) || namePrefs === prefs }.forEach { editor.remove(it) }
        recents.forEach { (endpointId, modelId, slugs) ->
            val kept = slugs.filter { it.isNotBlank() }.take(RecentOnlyProviders.LIMIT)
            if (kept.isNotEmpty()) editor.putString(key(endpointId, modelId), gson.toJson(kept))
        }
        // A store that shares another's names (the Summarizer's) adds to them
        // and never clears them.
        val nameEditor = if (namePrefs === prefs) editor else namePrefs.edit()
        names.forEach { (slug, name) ->
            if (slug.isNotBlank() && name.isNotBlank()) nameEditor.putString(NAME_PREFIX + encode(slug.lowercase()), name)
        }
        editor.commit()
        if (nameEditor !== editor) nameEditor.commit()
    }

    // Preference keys are written as XML attributes, so every part is
    // URL-encoded: no control characters reach the file, and "/" stays an
    // unambiguous separator.
    private fun key(endpointId: String, modelId: String) =
        RECENTS_PREFIX + encode(endpointId) + "/" + encode(modelId)

    private fun encode(part: String): String = URLEncoder.encode(part, "UTF-8")
}
