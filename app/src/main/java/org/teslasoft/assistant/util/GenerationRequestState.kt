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
 * The minimum live request state needed to distinguish a timeout before the
 * first response event from a stream that began and then stalled. It contains
 * no prompt, response, header, URL, or credential data.
 */
data class GenerationRequestState(
    val startedAtMillis: Long,
    val operation: String,
    val provider: String,
    val model: String,
    val streaming: Boolean,
    @Volatile var firstStreamEventArrived: Boolean = false,
    @Volatile var responseTextArrived: Boolean = false,
)
