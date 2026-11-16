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

import androidx.annotation.StringRes
import org.teslasoft.assistant.R

/**
 * The brief notice when the API helper fails (conversation_recall_plan.md
 * M.4 items 4 and 7): shown once when a failure starts, not again on every
 * turn while it continues. A helper call that works, or a change of the
 * helper setting, lets the next failure be shown.
 */
object ContextHelperNotice {
    private var failing = false

    /** The notice to show for [failure], or null: none when the helper
     *  worked, and none while an earlier failure is still going on. */
    @StringRes
    @Synchronized
    fun outcome(failure: ApiContextAssistant.Failure?): Int? {
        if (failure == null) {
            failing = false
            return null
        }
        if (failing) return null
        failing = true
        return if (failure == ApiContextAssistant.Failure.NO_MODEL) R.string.memory_context_helper_no_model_notice
        else R.string.memory_context_helper_failed_notice
    }

    @Synchronized
    fun reset() {
        failing = false
    }
}
