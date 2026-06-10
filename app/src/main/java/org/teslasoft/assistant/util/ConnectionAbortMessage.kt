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

package org.teslasoft.assistant.util

/**
 * Builds the chat-visible message for an ECONNABORTED failure ("Software
 * caused connection abort"). This used to be mapped to a generic
 * "weak connection or high demand" string, which hid the one piece of
 * information that helps diagnose it: the connection was torn down
 * mid-request, and which profile/model it happened with. In practice the
 * model matters — some servers drop the socket instead of returning a
 * proper HTTP error when a request names a model they can't serve.
 */
fun connectionAbortMessage(profileLabel: String?, host: String?, model: String?, detail: String?): String {
    return "The connection was closed before the response finished (\"connection abort\").\n\n" +
        "Profile: ${profileLabel ?: "unknown"}\n" +
        "Base URL: ${host ?: "unknown"}\n" +
        "Model: ${model?.ifBlank { "unknown" } ?: "unknown"}\n\n" +
        "This is a transport-level disconnect, not an authentication or quota error. " +
        "If it happens every time with this model but stops when you switch models, " +
        "the server is dropping the request for this model (unsupported, overloaded, " +
        "or timing out) without sending a readable error. If it is intermittent and " +
        "model-independent, it is usually Wi-Fi sleep or a network switch mid-stream.\n\n" +
        "Detail: ${detail?.ifBlank { null } ?: "no further detail provided by the network stack"}"
}
