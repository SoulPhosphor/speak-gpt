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

/**
 * Failure episodes for the Memory Context Helper's error pop-up
 * (conversation_recall_plan.md M.4 item 4), the same rule as Summarizer
 * Errors (conversation-summary-errors.md section 3): an episode starts with
 * the first failure after a success or a different kind of failure, and only
 * that first failure is shown. Repeats of the same kind show nothing, so
 * hands-free use is never interrupted by a flood of pop-ups. A change of the
 * helper setting starts afresh.
 */
object ContextHelperNotice {
    private var episode: HelperFailure.Category? = null

    /** Whether [failure] starts a new episode and should be shown. A null
     *  failure (the helper worked) ends the episode. */
    @Synchronized
    fun shouldShow(failure: HelperFailure?): Boolean {
        if (failure == null) {
            episode = null
            return false
        }
        if (episode == failure.category) return false
        episode = failure.category
        return true
    }

    @Synchronized
    fun reset() {
        episode = null
    }
}
