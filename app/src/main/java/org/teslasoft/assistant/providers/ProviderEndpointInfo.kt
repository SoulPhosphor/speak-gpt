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

/**
 * One provider endpoint for a model, as reported by the provider-discovery API
 * (OpenRouter: GET /models/{model}/endpoints).
 *
 * Every field the API may omit is nullable. Null means genuinely unknown and is
 * rendered as "?" in the provider chart (owner rule: never invent a value; show
 * "?" when we don't know).
 *
 * Prices are per token, exactly as the API reports them; the chart formats them
 * per million tokens for display.
 */
data class ProviderEndpointInfo(
    /** Display name, e.g. "OpenAI". */
    val providerName: String,
    /** API identity used in order/ignore/only arrays, e.g. "openai". Falls back
     *  to [providerName] when the API sends no tag. */
    val slug: String,
    val quantization: String?,
    val promptPrice: Double?,
    val completionPrice: Double?,
    val cacheReadPrice: Double?,
    val cacheWritePrice: Double?,
    /** Seconds, when the API reports latency; usually absent → "?". */
    val latency: Double?,
    /** Tokens/second, when reported; used only by the Throughput filter sort. */
    val throughput: Double?,
    /** Percentage, e.g. 99.7. */
    val uptime: Double?,
    /** Null = the API sent no parameter list, so tool support is unknown. */
    val supportsTools: Boolean?,
    /** Implicit prompt caching. Derived from cache pricing when present. */
    val supportsCaching: Boolean?,
    /** Zero Data Retention endpoint. Only set from an explicit API field. */
    val zdr: Boolean?
)
