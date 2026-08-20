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
 * The effective reasoning behavior for one generation, after applying the
 * §7.9 precedence and the §7.8 safety clamp. Pure data; the request serializer
 * turns it into provider wire fields.
 */
data class ResolvedReasoning(
    /** The effective, capability-clamped effort. [ReasoningEffort.AUTO] means
     *  send no explicit effort. */
    val effort: ReasoningEffort,

    /** Whether provider-supplied reasoning should be requested/displayed
     *  (§7.4 Show Reasoning). Never disables the model's reasoning itself. */
    val showReasoning: Boolean,

    /** Which layer supplied the effective effort, for diagnostics. */
    val source: Source,

    /** When a saved effort had to be dropped because the active path does not
     *  support it, the original saved value — kept so diagnostics can explain
     *  that a previously saved choice became unavailable (§7.8). Null when no
     *  clamp occurred. */
    val clampedFrom: ReasoningEffort? = null
) {
    /** Whether the effective effort is an explicit level to send on the wire
     *  (not AUTO, not OFF). */
    val sendsExplicitLevel: Boolean get() = effort.isExplicitLevel

    /** Whether the effective choice is the capability-driven disable signal. */
    val disablesReasoning: Boolean get() = effort == ReasoningEffort.OFF

    enum class Source { CONVERSATION_OVERRIDE, FAVORITE_DEFAULT, DEFAULT_AUTO }
}

/**
 * Resolves the effective reasoning settings for a turn (chat-redesign-plan.md
 * §7.5 inheritance and §7.9 precedence/edge behavior).
 *
 * Precedence, highest first (§7.9):
 *  1. a persisted per-conversation override, while it exists;
 *  2. otherwise the current favorite's saved default;
 *  3. otherwise Auto (send no explicit effort — provider/model default).
 *
 * Two safety rules ride on top:
 *  - **Capability clamp (§7.8):** an effective effort the active path does not
 *    support is never sent. It resolves to [ReasoningEffort.AUTO] (the safe,
 *    always-valid behavior with no unambiguous equivalent), and the dropped
 *    value is reported in [ResolvedReasoning.clampedFrom].
 *  - **Not-capable path:** when the path is not known to reason, effort is
 *    forced to Auto so no reasoning parameter is sent to a model that may not
 *    understand it.
 *
 * Every input is nullable so the resolver models the real states directly:
 * a conversation with no override (null), a model with no favorite (null), and
 * a favorite that predates reasoning (null saved effort) all fall through
 * correctly.
 */
object ReasoningSettingsResolver {

    /**
     * @param conversationOverride the conversation's own persisted effort, or
     *   null when the conversation has never set one (inherit).
     * @param favoriteEffort the favorite model's saved default effort, or null
     *   when the model is not a favorite or the favorite predates reasoning.
     * @param favoriteShowReasoning the favorite's saved Show Reasoning value, or
     *   null to use the §7.9 default (On).
     * @param capability the reasoning capability of the effective path.
     */
    fun resolve(
        conversationOverride: ReasoningEffort?,
        favoriteEffort: ReasoningEffort?,
        favoriteShowReasoning: Boolean?,
        capability: ReasoningCapability
    ): ResolvedReasoning {
        // Default Show Reasoning is On (§7.9). It is a display/return preference
        // only and is meaningful whenever the path can return visible reasoning;
        // it never turns the model's reasoning on or off.
        val showReasoning = favoriteShowReasoning ?: true

        // Layer precedence: pick the requested effort and remember which layer
        // won, before any capability clamp.
        val (requested, source) = when {
            conversationOverride != null ->
                conversationOverride to ResolvedReasoning.Source.CONVERSATION_OVERRIDE
            favoriteEffort != null ->
                favoriteEffort to ResolvedReasoning.Source.FAVORITE_DEFAULT
            else ->
                ReasoningEffort.AUTO to ResolvedReasoning.Source.DEFAULT_AUTO
        }

        // A path that is not known to reason must never receive a reasoning
        // parameter. Auto sends nothing, so it is always safe here.
        if (!capability.isReasoningCapable) {
            return ResolvedReasoning(
                effort = ReasoningEffort.AUTO,
                showReasoning = showReasoning,
                source = source,
                clampedFrom = requested.takeIf { it != ReasoningEffort.AUTO }
            )
        }

        // Capability clamp (§7.8): drop an unsupported effort to Auto, keeping
        // the original for diagnostics. AUTO always passes (it sends nothing).
        return if (capability.supports(requested)) {
            ResolvedReasoning(
                effort = requested,
                showReasoning = showReasoning,
                source = source,
                clampedFrom = null
            )
        } else {
            ResolvedReasoning(
                effort = ReasoningEffort.AUTO,
                showReasoning = showReasoning,
                source = source,
                clampedFrom = requested
            )
        }
    }
}
