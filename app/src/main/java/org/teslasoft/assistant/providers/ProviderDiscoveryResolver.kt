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

import org.json.JSONObject
import java.net.URI

/**
 * Resolves OpenRouter's current canonical provider-details URL from the
 * single-model lookup response. This keeps aliases and newly revised model
 * slugs working without guessing which id belongs in `/models/.../endpoints`.
 */
object ProviderDiscoveryResolver {

    fun modelLookupUrl(baseHost: String, modelId: String): String =
        baseHost.trimEnd('/') + "/model/" + modelId

    fun detailsUrl(baseHost: String, responseBody: String): String? {
        val details = try {
            JSONObject(responseBody)
                .optJSONObject("data")
                ?.optJSONObject("links")
                ?.optString("details")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            val base = URI(baseHost.trimEnd('/') + "/")
            val resolved = base.resolve(details)
            // The caller attaches the endpoint profile's Authorization header.
            // Never let an API-supplied absolute link move that credential to
            // another origin.
            if (resolved.scheme == base.scheme && resolved.rawAuthority == base.rawAuthority) {
                resolved.toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
