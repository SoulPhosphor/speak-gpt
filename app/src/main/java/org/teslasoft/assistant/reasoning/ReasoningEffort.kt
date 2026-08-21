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
 * Provider-neutral reasoning-effort level (chat-redesign-plan.md §7).
 *
 * This is the app's single internal vocabulary for "how hard should the model
 * think". Each provider path (OpenRouter's `reasoning` object, OpenAI-style
 * `reasoning_effort`, or a provider that returns reasoning but exposes no
 * control) translates FROM this enum at request time; nothing upstream of the
 * request serializer speaks a provider's raw wire values.
 *
 * The two non-numeric members carry explicit product meaning from §7.9 and are
 * NOT aliases for a middle level:
 *
 * - [AUTO] — "send no explicit effort and allow the provider/model default to
 *   apply". It is a real, persistable choice, never a stand-in for [MEDIUM].
 * - [OFF] — "disable the model's reasoning", emitted only where the active
 *   model/provider path explicitly supports disabling (capability-driven).
 *   [OFF] is distinct from the separate Show Reasoning display toggle: [OFF]
 *   asks the model not to reason at all, while Show Reasoning only governs
 *   whether returned reasoning is displayed.
 *
 * [serialized] is the stable storage/token form. It is written into the
 * favorite store and per-conversation settings, so these strings must never
 * change once shipped. [fromSerialized] tolerates unknown/legacy strings by
 * returning null, letting callers fall back to a safe default (§7.8: an
 * unreadable stored level must resolve to safe behavior, not crash).
 */
enum class ReasoningEffort(val serialized: String) {
    /** Send no explicit effort; provider/model default applies (§7.9 default). */
    AUTO("auto"),

    /** Disable reasoning via the provider-appropriate signal (capability-driven). */
    OFF("off"),

    /** Fast, minimal reasoning (e.g. OpenAI gpt-5 `reasoning_effort=minimal`). */
    MINIMAL("minimal"),

    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),

    /** Extra-high reasoning, above high. Offered only where authoritative
     *  provider metadata lists it (or, on a metadata path without a published
     *  ladder, learned). Never guessed onto a model whose ladder is known. */
    XHIGH("xhigh"),

    /** The highest reasoning tier some current models expose, above extra high
     *  (OpenRouter reports it in a model's supported_efforts, e.g. DeepSeek V4,
     *  GLM). Offered only where authoritative metadata lists it. */
    MAX("max");

    /**
     * True for a concrete effort the app actually sends as a level
     * (everything except [AUTO], which sends nothing, and [OFF], which sends a
     * disable signal rather than a level).
     */
    val isExplicitLevel: Boolean
        get() = this != AUTO && this != OFF

    companion object {
        /**
         * Parse a stored/serialized value back to an effort, or null when the
         * string is blank, unknown, or from a newer build. Callers treat null
         * as "fall back to [AUTO]" and keep a diagnostic (§7.8).
         */
        fun fromSerialized(value: String?): ReasoningEffort? {
            val v = value?.trim()?.lowercase() ?: return null
            if (v.isEmpty()) return null
            return entries.firstOrNull { it.serialized == v }
        }
    }
}
