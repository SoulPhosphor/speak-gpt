/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.ModelPickerScope

/**
 * A background job's own model lists (the Summarizer's or the Memory
 * Scanner's) inside the model settings backup: its favorites (with their
 * routing, favorite providers and saved parameters) and its Recent list. The
 * job's selected model, its own routing and its parameters are app settings
 * and travel with those.
 */
object DedicatedModelsBackup {

    data class Snapshot(
        val favorites: List<Map<String, String>>,
        val recents: RecentModelsBackup.Snapshot
    )

    fun capture(context: Context, scope: ModelPickerScope): Snapshot = Snapshot(
        FavoriteModelsPreferences.getPreferences(context, scope).getFavoriteModels().map { LinkedHashMap(it) },
        RecentModelsBackup.capture(context, scope)
    )

    fun apply(context: Context, snapshot: Snapshot, scope: ModelPickerScope) {
        FavoriteModelsPreferences.getPreferences(context, scope).setFavoriteModels(ArrayList(snapshot.favorites))
        RecentModelsBackup.apply(context, snapshot.recents, scope)
    }

    /** Only entries whose API profile is part of the same data. */
    fun forEndpoints(snapshot: Snapshot, endpointIds: Set<String>): Snapshot = snapshot.copy(
        favorites = snapshot.favorites.filter { it["endpointId"].orEmpty() in endpointIds },
        recents = RecentModelsBackup.forEndpoints(snapshot.recents, endpointIds)
    )

    /** A merge restore keeps this device's entries and adds the backup's
     *  others, the same way as the chat's lists. */
    fun merge(current: Snapshot?, incoming: Snapshot?): Snapshot? {
        if (current == null) return incoming
        if (incoming == null) return current
        val favorites = (current.favorites + incoming.favorites)
            .distinctBy { it["endpointId"].orEmpty() + "\u0000" + it["modelId"].orEmpty() }
        return Snapshot(favorites, RecentModelsBackup.merge(current.recents, incoming.recents)!!)
    }

    /** The part that does not change after every summary (see the restore
     *  fingerprint). */
    fun withoutHistory(snapshot: Snapshot?): Snapshot? =
        snapshot?.copy(recents = RecentModelsBackup.Snapshot(0, emptyList(), emptyList(), emptyMap()))

    fun encode(snapshot: Snapshot): JSONObject = JSONObject()
        .put("favorites", JSONArray().apply {
            snapshot.favorites.forEach { favorite ->
                put(JSONObject().apply { favorite.forEach { (key, value) -> put(key, value) } })
            }
        })
        .put("recents", RecentModelsBackup.encode(snapshot.recents))

    /** Null when the section is malformed. */
    fun parse(json: JSONObject): Snapshot? = try {
        require(json.keys().asSequence().toSet() == setOf("favorites", "recents"))
        val array = json.getJSONArray("favorites")
        val favorites = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            require(item.keys().asSequence().all { it in ModelEndpointPortableCodec.FAVORITE_KEYS })
            LinkedHashMap<String, String>().apply {
                item.keys().forEach { key ->
                    val value = item.getString(key)
                    require(value.length <= 65_536 && value.none { it == '\u0000' })
                    put(key, value)
                }
                require(get("endpointId").orEmpty().isNotBlank() && get("modelId").orEmpty().isNotBlank())
            }
        }
        require(favorites.map { it["endpointId"] + "\u0000" + it["modelId"] }.distinct().size == favorites.size)
        Snapshot(favorites, requireNotNull(RecentModelsBackup.parse(json.getJSONObject("recents"))))
    } catch (_: Exception) {
        null
    }
}
