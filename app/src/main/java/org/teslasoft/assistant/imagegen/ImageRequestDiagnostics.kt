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

package org.teslasoft.assistant.imagegen

/**
 * Request diagnostics for image generation
 * (image-generation-rebuild-plan.md §13, owner ruling 2026-07-29):
 * provider, endpoint, model, timestamp, HTTP status, the final outcome,
 * total request duration — and, when downloading the finished image is a
 * separate step, generation time and download time separately. The
 * provider-issued request id is sanitized and length-limited, and kept
 * distinct from any app-internal id.
 *
 * Never carried here (§13 never-log list): API keys, authorization
 * headers, signed image URLs, request bodies, raw image data, prompts, or
 * private conversation content.
 */
class ImageRequestDiagnostics(
    val provider: String,
    val endpointLabel: String,
    val modelId: String,
    val timestamp: Long,
    val totalMs: Long,
    val generationMs: Long? = null,
    val downloadMs: Long? = null,
    val httpStatus: Int? = null,
    val providerRequestId: String? = null
) {
    companion object {
        private const val MAX_REQUEST_ID_LENGTH = 120

        /** Keeps only filename-safe request-id characters and bounds the
         *  length; null when nothing usable remains. */
        fun sanitizeRequestId(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim().filter {
                it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':'
            }
            if (cleaned.isBlank()) return null
            return cleaned.take(MAX_REQUEST_ID_LENGTH)
        }
    }
}
