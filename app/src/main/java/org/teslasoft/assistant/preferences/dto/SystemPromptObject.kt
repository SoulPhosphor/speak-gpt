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

package org.teslasoft.assistant.preferences.dto

/**
 * One saved system prompt in the user's library.
 *
 * The [id] is stable across renames (editing keeps the same id) so the chat's
 * selection and the library ordering survive a title change — unlike personas,
 * whose id is derived from the label. [title] is the short name shown on the
 * row and in Quick Settings; [body] is the actual text sent to the model as
 * the system message.
 */
data class SystemPromptObject(
    val id: String,
    val title: String,
    val body: String
)
