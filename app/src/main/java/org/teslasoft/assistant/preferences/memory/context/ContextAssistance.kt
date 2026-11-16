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

package org.teslasoft.assistant.preferences.memory.context

import org.teslasoft.assistant.preferences.Preferences

/**
 * The app-wide context-assistance setting (Memory Context Helper Settings;
 * conversation_recall_plan.md G38): off until the user turns it on; when on,
 * the chosen method. On-device (Code-Based) is the only method built.
 */
object ContextAssistance {
    const val METHOD_CODE_BASED = "code_based"

    private val codeBased = CodeBasedAssistant()

    fun forSettings(preferences: Preferences): ContextAssistant {
        if (!preferences.getMemoryContextHelperEnabled()) return ContextAssistant.Off
        return when (preferences.getMemoryContextHelperMethod()) {
            METHOD_CODE_BASED -> codeBased
            else -> ContextAssistant.Off
        }
    }

    /** Drops every chat's subject, so a change of setting starts afresh. */
    fun settingChanged() = SubjectStore.shared.clear()

    /** A deleted chat's subject is removed with it. */
    fun chatDeleted(chatId: String) = SubjectStore.shared.forget(chatId)
}
