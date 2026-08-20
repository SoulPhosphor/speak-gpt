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

package org.teslasoft.assistant.ui.reasoning

import android.content.Context
import androidx.annotation.StringRes
import org.teslasoft.assistant.R
import org.teslasoft.assistant.reasoning.ReasoningEffort

/**
 * The user-facing name for each reasoning-effort level (chat-redesign-plan.md
 * §7.4/§7.5). Shared by the Quick Settings Thinking dropdown and the favorite
 * Reasoning Settings screen so both render the levels identically. The strings
 * live in resources; this only maps the enum to the right one.
 */
object ReasoningEffortLabels {

    @StringRes
    fun labelRes(effort: ReasoningEffort): Int = when (effort) {
        ReasoningEffort.AUTO -> R.string.reasoning_effort_auto
        ReasoningEffort.OFF -> R.string.reasoning_effort_off
        ReasoningEffort.MINIMAL -> R.string.reasoning_effort_minimal
        ReasoningEffort.LOW -> R.string.reasoning_effort_low
        ReasoningEffort.MEDIUM -> R.string.reasoning_effort_medium
        ReasoningEffort.HIGH -> R.string.reasoning_effort_high
        ReasoningEffort.XHIGH -> R.string.reasoning_effort_xhigh
        ReasoningEffort.MAX -> R.string.reasoning_effort_max
    }

    fun label(context: Context, effort: ReasoningEffort): String =
        context.getString(labelRes(effort))
}
