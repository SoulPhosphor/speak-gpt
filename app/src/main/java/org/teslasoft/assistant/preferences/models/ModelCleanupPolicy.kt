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

package org.teslasoft.assistant.preferences.models

/** One endpoint's catalog result, or a wholly inconclusive check. */
sealed class EndpointCatalogCheck {
    data class Checked(
        val modelIds: Set<String>,
        /** Individual saved ids whose availability could not be established.
         *  Used for OpenRouter alias lookups after a conclusive catalog. */
        val indeterminateModelIds: Set<String> = emptySet()
    ) : EndpointCatalogCheck()
    data object Unchecked : EndpointCatalogCheck()
}

/**
 * The most recent saved cleanup report. [unavailable] is shared by Favorites
 * and Model Rules, so one endpoint/model result drives every warning.
 */
data class ModelCleanupReport(
    val generatedAtMillis: Long = 0L,
    val unavailable: Set<ModelIdentity> = emptySet(),
    val uncheckedEndpointIds: Set<String> = emptySet(),
    val endpointLabels: Map<String, String> = emptyMap()
) {
    val hasReport: Boolean get() = generatedAtMillis > 0L

    fun isUnavailable(endpointId: String, modelId: String): Boolean =
        ModelIdentity(endpointId, modelId) in unavailable
}

/** Pure update rules for user-triggered cleanup scans. */
object ModelCleanupPolicy {

    /**
     * Build a fresh saved report from one check per endpoint.
     *
     * A conclusive catalog replaces that endpoint's old status using exact ids.
     * An inconclusive check never creates an unavailable status and preserves a
     * previous unavailable warning for the same still-saved identity until a
     * later conclusive catalog finds it again.
     */
    fun update(
        previous: ModelCleanupReport,
        currentTargets: Set<ModelIdentity>,
        checks: Map<String, EndpointCatalogCheck>,
        endpointLabels: Map<String, String>,
        generatedAtMillis: Long
    ): ModelCleanupReport {
        val unavailable = LinkedHashSet<ModelIdentity>()
        val unchecked = LinkedHashSet<String>()

        currentTargets.groupBy { it.endpointId }.forEach { (endpointId, targets) ->
            when (val check = checks[endpointId] ?: EndpointCatalogCheck.Unchecked) {
                is EndpointCatalogCheck.Checked -> targets.forEach { target ->
                    when {
                        target.modelId in check.modelIds -> Unit
                        target.modelId in check.indeterminateModelIds -> {
                            // A failed alias lookup is not proof that the model
                            // disappeared. Preserve an old warning, but never
                            // create a new destructive cleanup candidate.
                            unchecked.add(endpointId)
                            if (target in previous.unavailable) unavailable.add(target)
                        }
                        else -> unavailable.add(target)
                    }
                }
                EndpointCatalogCheck.Unchecked -> {
                    unchecked.add(endpointId)
                    targets.filterTo(unavailable) { it in previous.unavailable }
                }
            }
        }

        val labels = LinkedHashMap(previous.endpointLabels)
        labels.putAll(endpointLabels.filterValues { it.isNotBlank() })
        return ModelCleanupReport(
            generatedAtMillis = generatedAtMillis,
            unavailable = unavailable,
            uncheckedEndpointIds = unchecked,
            endpointLabels = labels.filterKeys { endpointId ->
                currentTargets.any { it.endpointId == endpointId }
            }
        )
    }

    /** Remove status for identities no longer referenced anywhere locally. */
    fun prune(
        report: ModelCleanupReport,
        currentTargets: Set<ModelIdentity>
    ): ModelCleanupReport = report.copy(
        unavailable = report.unavailable.intersect(currentTargets),
        uncheckedEndpointIds = report.uncheckedEndpointIds.filterTo(LinkedHashSet()) { endpointId ->
            currentTargets.any { it.endpointId == endpointId }
        },
        endpointLabels = report.endpointLabels.filterKeys { endpointId ->
            currentTargets.any { it.endpointId == endpointId }
        }
    )
}
