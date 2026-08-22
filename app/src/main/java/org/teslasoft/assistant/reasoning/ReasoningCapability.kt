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
 * What SpeakGPT knows about one model/provider path's reasoning behavior
 * (chat-redesign-plan.md §7.7).
 *
 * Reasoning capability is deliberately NOT a single "is this a thinking model"
 * boolean and NOT a hard-coded list of model names. It is a small bundle of
 * independent facts, each of which the app may know, know to be absent, or not
 * know at all. Every field is derived by the confidence ladder in
 * [ReasoningCapabilityResolver]; a low-confidence source fills in fewer fields
 * than structured provider metadata.
 *
 * The three-state [support] is the anchor:
 *
 * - [ReasoningSupport.KNOWN]   — this path is known to reason.
 * - [ReasoningSupport.ABSENT]  — this path is known NOT to reason.
 * - [ReasoningSupport.UNKNOWN] — capability could not be established; the app
 *   preserves the uncertainty rather than converting it to "false" (§7.7 #4).
 *   Unknown is an internal state only — it never shows an extra icon (§7.7).
 */
data class ReasoningCapability(
    /** Whether this path reasons: known-yes, known-no, or unestablished. */
    val support: ReasoningSupport,

    /** Whether the effort level can be chosen at all for this path. When false,
     *  the Thinking dropdown is not offered even though the model reasons
     *  (§7.4: a reasoning model may have no Thinking dropdown). */
    val effortConfigurable: Boolean = false,

    /** The explicit effort levels this path is known to accept, e.g. LOW /
     *  MEDIUM / HIGH. Never contains [ReasoningEffort.AUTO] (always available
     *  when [effortConfigurable]) or [ReasoningEffort.OFF] (added by
     *  [canDisableReasoning]). Order is presentation order for the dropdown. */
    val supportedEfforts: List<ReasoningEffort> = emptyList(),

    /** Whether reasoning can be turned OFF. False means reasoning is mandatory
     *  for this path; the app must never present an Off choice or send a
     *  disable signal (§7.9: never synthesize Off for a mandatory model). */
    val canDisableReasoning: Boolean = false,

    /** Whether user-visible reasoning content or summaries can actually come
     *  back on this path. Some models reason but return nothing visible
     *  (e.g. OpenAI o-series over chat completions); there Show Reasoning
     *  simply produces no Thinking row, which is not a failure (§7.8). */
    val canReturnVisibleReasoning: Boolean = false,

    /** Whether this path supports a raw reasoning token budget. Recorded for a
     *  future explicit budget UI; §7.9 forbids inventing a budget slider now,
     *  so nothing in this design surfaces it as a control yet. */
    val tokenBudgetSupported: Boolean = false,

    /** Where this capability came from, so diagnostics can explain confidence
     *  and §7.8 can name the capability source in a mismatch report. */
    val source: CapabilitySource = CapabilitySource.NONE,

    /** True when [supportedEfforts] is the provider's own published effort list
     *  (OpenRouter's `reasoning.supported_efforts`) rather than a conservative
     *  adapter default. An authoritative list is exact and must never be widened
     *  or narrowed speculatively. */
    val effortsAuthoritative: Boolean = false
) {
    /**
     * True when SpeakGPT knows this path reasons AND at least one reasoning
     * setting is meaningfully available to the user — an effort choice, an
     * Off choice, or returnable reasoning content. This is the exact rule for
     * showing the reasoning lightbulb on a favorite (§7.4) and the
     * informational lightbulb in View All (§7.6). A path that reasons but
     * exposes no setting at all still counts as reasoning-capable for the
     * informational indicator, because the model genuinely reasons.
     */
    val isReasoningCapable: Boolean
        get() = support == ReasoningSupport.KNOWN

    /**
     * True when a favorite of this path has any reasoning setting worth a
     * dedicated Reasoning Settings screen control: a Thinking dropdown
     * ([effortConfigurable]) and/or a Show Reasoning toggle (which exists
     * whenever visible reasoning can be returned). A model that reasons but
     * offers neither still shows the lightbulb (informational), but its
     * settings screen would be empty.
     */
    val hasConfigurableSetting: Boolean
        get() = isReasoningCapable && (effortConfigurable || canReturnVisibleReasoning)

    /**
     * The full ordered set of choices for the Thinking dropdown on this path:
     * [ReasoningEffort.AUTO] first (always, when effort is configurable), then
     * the known explicit levels, then [ReasoningEffort.OFF] last when disabling
     * is supported. Empty when effort is not configurable — the caller then
     * shows no dropdown (§7.4).
     */
    fun thinkingChoices(): List<ReasoningEffort> {
        if (!effortConfigurable) return emptyList()
        val choices = ArrayList<ReasoningEffort>()
        choices.add(ReasoningEffort.AUTO)
        choices.addAll(supportedEfforts.filter { it.isExplicitLevel })
        if (canDisableReasoning) choices.add(ReasoningEffort.OFF)
        return choices
    }

    /**
     * Whether [effort] is a choice this path can actually honor right now.
     * [ReasoningEffort.AUTO] is always valid (it sends nothing). Used by §7.8
     * to reject a saved level that is no longer supported before it is sent.
     */
    fun supports(effort: ReasoningEffort): Boolean = when (effort) {
        ReasoningEffort.AUTO -> true
        ReasoningEffort.OFF -> canDisableReasoning
        else -> effortConfigurable && supportedEfforts.contains(effort)
    }

    companion object {
        /** Capability could not be established. Distinct from [absent]: the app
         *  does not claim the model fails to reason, only that it does not know
         *  (§7.7 #4). Shows no lightbulb, offers no controls. */
        val UNKNOWN = ReasoningCapability(ReasoningSupport.UNKNOWN)

        /** Known to not reason. Shows no lightbulb, offers no controls. */
        val ABSENT = ReasoningCapability(ReasoningSupport.ABSENT)
    }
}

/** Three-state knowledge of whether a path reasons (§7.7). */
enum class ReasoningSupport { KNOWN, ABSENT, UNKNOWN }

/**
 * Provenance of a [ReasoningCapability], mirroring the §7.7 confidence ladder
 * from strongest to weakest. Kept on the capability so diagnostics can report
 * how a decision was reached and §7.8 can name the source in a mismatch.
 */
enum class CapabilitySource {
    /** Structured capability metadata from the provider/model-list API
     *  (strongest; OpenRouter's `supported_parameters`). */
    PROVIDER_METADATA,

    /** Current official provider capability knowledge held in a direct-provider
     *  adapter, for providers that expose no equivalent list metadata. */
    PROVIDER_ADAPTER,

    /** A real response on this exact endpoint/model path returned separately
     *  supplied reasoning text. This proves that reasoning and visible
     *  reasoning exist, but says nothing about supported effort controls. */
    OBSERVED_RESPONSE,

    /** A provider-defined variant marker that unambiguously denotes a reasoning
     *  SKU (lowest confidence; never a generic name substring). */
    VARIANT_MARKER,

    /** No capability was established. */
    NONE
}
