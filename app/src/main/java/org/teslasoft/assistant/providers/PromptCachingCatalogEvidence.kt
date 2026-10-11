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

package org.teslasoft.assistant.providers

import org.teslasoft.assistant.usage.TokenPricingCatalog

/** A completed provider catalog pinned to the exact endpoint profile that loaded it. */
data class PromptCachingCatalogEvidence(
    val endpointFingerprint: String,
    val catalog: TokenPricingCatalog,
    val completedAtElapsedMs: Long
)

/**
 * Allows a recently completed catalog to cover the small interval before the
 * current request's own catalog load finishes. Evidence from another endpoint
 * profile, another model, the future, or an old provider snapshot is rejected.
 */
object PromptCachingCatalogReusePolicy {
    const val MAX_AGE_MS: Long = 5L * 60L * 1000L

    fun reusableCatalog(
        evidence: PromptCachingCatalogEvidence?,
        endpointFingerprint: String?,
        model: String,
        nowElapsedMs: Long
    ): TokenPricingCatalog? {
        if (evidence == null || endpointFingerprint.isNullOrBlank()) return null
        if (evidence.endpointFingerprint != endpointFingerprint) return null
        if (evidence.catalog.model != model) return null
        val age = nowElapsedMs - evidence.completedAtElapsedMs
        if (age !in 0..MAX_AGE_MS) return null
        return evidence.catalog
    }
}
