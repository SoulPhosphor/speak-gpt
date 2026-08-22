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

/**
 * The per-message reasoning indicator (owner design, Aug 2026): a single
 * Material Wi-Fi-strength glyph shown on an assistant reply that tells the user,
 * at a glance, the reasoning effort that reply was generated with — as far as
 * the app can truthfully know it.
 *
 * The state is computed ONCE, when the reply begins (from the same capability
 * and resolved effort the request itself uses), and persisted with the message,
 * so it survives reopening and travels with a regenerated turn's version
 * snapshot. It is never re-derived from a later model switch.
 *
 * The rules, in order:
 *  - A path not known to reason has NO indicator (null) — no icon at all.
 *  - A reasoning path the app positively knows is mandatory AND offers no
 *    adjustable control is [FIXED]: it reasons, the level cannot be chosen, and
 *    reasoning cannot be turned off (e.g. a mandatory-reasoning model). The lock
 *    glyph signals "not changeable".
 *  - A path that reasons but exposes an adjustable control maps the effective
 *    effort straight across: [OFF] for a disabled reasoning model, any explicit
 *    provider-supported level, or [AUTOMATIC] when the effort was left to the
 *    provider.
 *  - A path that reasons but whose configuration the app has NOT established
 *    (no ladder, no known Off, no mandatory evidence) is [AUTOMATIC], never
 *    [FIXED]: the app does not claim the reasoning is unchangeable when it only
 *    knows the model reasons (§7.7 #4).
 *
 * [AUTOMATIC] means exactly "reasoning was left automatic and the app cannot
 * confirm the level the provider actually served" — never a guess at a level.
 *
 * Every explicit enum value exists so authoritative provider ladders can map
 * directly without SpeakGPT inventing or collapsing levels.
 */
enum class ReasoningIndicator(val token: String) {
    OFF("off"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max"),
    AUTOMATIC("automatic"),
    FIXED("fixed");

    companion object {
        /**
         * The indicator for a reply generated with [effort] on a path whose
         * reasoning [capability] is [capability], or null when the path is not
         * known to reason (no icon is shown).
         */
        fun forGeneration(capability: ReasoningCapability, effort: ReasoningEffort): ReasoningIndicator? {
            if (!capability.isReasoningCapable) return null
            // No adjustable control: only a positively-known mandatory model is
            // locked/fixed. An unestablished configuration is left automatic, not
            // falsely reported as fixed.
            if (!capability.isEffortAdjustable) {
                return if (capability.reasoningMandatory) FIXED else AUTOMATIC
            }
            return when (effort) {
                ReasoningEffort.OFF -> OFF
                ReasoningEffort.MINIMAL -> MINIMAL
                ReasoningEffort.LOW -> LOW
                ReasoningEffort.MEDIUM -> MEDIUM
                ReasoningEffort.HIGH -> HIGH
                ReasoningEffort.XHIGH -> XHIGH
                ReasoningEffort.MAX -> MAX
                // Auto sends no explicit level and the provider does not report
                // the one it served, so the app does not claim a bar count.
                ReasoningEffort.AUTO -> AUTOMATIC
            }
        }

        /** Parse a persisted indicator token, or null when blank/unknown. */
        fun fromToken(token: String?): ReasoningIndicator? {
            val t = token?.trim()?.lowercase() ?: return null
            if (t.isEmpty()) return null
            return entries.firstOrNull { it.token == t }
        }
    }
}
