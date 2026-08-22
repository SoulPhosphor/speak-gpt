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

package org.teslasoft.assistant.reasoning

import java.net.URI

/** Exact provider path used to select official adapter knowledge and request
 * serialization. Generic custom endpoints stay GENERIC even when a model id
 * resembles a well-known family. */
enum class ReasoningProviderPath {
    OPENROUTER,
    OPENAI,
    GEMINI_OPENAI_COMPATIBLE,
    ANTHROPIC_OPENAI_COMPATIBLE,
    DEEPSEEK,
    GENERIC_OPENAI_COMPATIBLE;

    companion object {
        fun forEndpoint(host: String?, isOpenRouter: Boolean): ReasoningProviderPath {
            if (isOpenRouter) return OPENROUTER
            val hostname = try {
                URI(host.orEmpty().trim()).host?.lowercase().orEmpty()
            } catch (_: Exception) {
                ""
            }
            return when {
                hostname == "api.openai.com" -> OPENAI
                hostname == "generativelanguage.googleapis.com" -> GEMINI_OPENAI_COMPATIBLE
                hostname == "api.anthropic.com" -> ANTHROPIC_OPENAI_COMPATIBLE
                hostname == "api.deepseek.com" -> DEEPSEEK
                else -> GENERIC_OPENAI_COMPATIBLE
            }
        }
    }
}
