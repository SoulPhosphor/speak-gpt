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

/** Stable-ID merge policy for credential-free model and endpoint settings. */
object ModelEndpointMergePlanner {
    data class Conflict(val kind: Kind, val stableId: String)
    enum class Kind { ENDPOINT, FAVORITE }

    data class Report(
        val addedEndpoints: Int,
        val addedFavorites: Int,
        val identicalSkipped: Int,
        val conflicts: List<Conflict>
    )

    data class Plan(
        val data: ModelEndpointPortableCodec.Data,
        val report: Report
    )

    fun merge(
        current: ModelEndpointPortableCodec.Data,
        backup: ModelEndpointPortableCodec.Data
    ): Plan {
        val endpoints = current.endpoints.associateByTo(LinkedHashMap()) { it.id }
        val favorites = current.favorites.associateByTo(LinkedHashMap(), ::favoriteId)
        val conflicts = ArrayList<Conflict>()
        var addedEndpoints = 0
        var addedFavorites = 0
        var identicalSkipped = 0

        backup.endpoints.forEach { incoming ->
            val existing = endpoints[incoming.id]
            when {
                existing == null -> {
                    endpoints[incoming.id] = incoming
                    addedEndpoints++
                }
                existing == incoming -> identicalSkipped++
                else -> conflicts.add(Conflict(Kind.ENDPOINT, incoming.id))
            }
        }
        backup.favorites.forEach { incoming ->
            val id = favoriteId(incoming)
            val existing = favorites[id]
            when {
                existing == null -> {
                    favorites[id] = LinkedHashMap(incoming)
                    addedFavorites++
                }
                existing == incoming -> identicalSkipped++
                else -> conflicts.add(Conflict(Kind.FAVORITE, id))
            }
        }
        return Plan(
            ModelEndpointPortableCodec.Data(endpoints.values.toList(), favorites.values.toList()),
            Report(addedEndpoints, addedFavorites, identicalSkipped, conflicts)
        )
    }

    private fun favoriteId(favorite: Map<String, String>): String =
        favorite["endpointId"].orEmpty() + "\u0000" + favorite["modelId"].orEmpty()
}
