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

package org.teslasoft.assistant.preferences.memory.enforcer

import org.teslasoft.assistant.preferences.memory.ModeRecord

/**
 * The enforcer's operating defaults, provisioned as origin='system' rows the
 * first time the full memory engine runs against a store that has none
 * (seed-safety audit: the app bundles NO example memory data — these are
 * neutral machinery, not memories, and only ever fill EMPTY tables; imported
 * or user-authored modes/policy are never touched).
 */
object DefaultOperatingData {

    /** Matches the schema's retrieval_policy object; the enforcer-specific
     *  knobs (memory_char_budget, mode_threshold) are optional extensions the
     *  parser falls back on when absent, so imported policies keep working. */
    const val DEFAULT_POLICY_JSON: String = """{
  "always_include": ["owner_profile", "directives", "active_companion_record", "always_load_memories"],
  "top_k": 8,
  "weights": {"similarity": 0.6, "importance": 0.3, "recency": 0.1},
  "memory_char_budget": 6000,
  "mode_threshold": 0.45
}"""

    /**
     * The protective gradient the spec's tie-break and stickiness rules name
     * (steady presence > emotional support > companion presence) plus the two
     * free-switching task modes. Names are load-bearing: [ModeSelection]
     * recognizes the gradient by normalized name.
     */
    fun defaultModes(): List<ModeRecord> = listOf(
        ModeRecord(
            modeId = "mode-companion-presence",
            name = "Companion Presence",
            purpose = "Ordinary, warm conversation — being with them rather than performing for them.",
            signalsJson = """["Casual conversation, checking in, or sharing how the day went","Talking through everyday things without a task attached"]""",
            respondJson = """["Be present and unhurried; follow their lead","Ask about what they mention rather than steering elsewhere"]""",
            avoidJson = """["Turning a chat into a productivity session","Overloading a light moment with questions"]""",
            transitionNote = null,
            overridesJson = "[]",
            scope = "global",
            companionIdsJson = "[]",
            origin = "system"
        ),
        ModeRecord(
            modeId = "mode-emotional-support",
            name = "Emotional Support",
            purpose = "They're carrying something heavy right now and need to be heard first.",
            signalsJson = """["User expresses sadness, stress, frustration, loneliness, or being overwhelmed","Talking about something painful, a loss, or a hard day"]""",
            respondJson = """["Listen first; reflect what you heard before anything else","Validate the feeling without rushing to fix it","Stay with their pace; let silence be okay"]""",
            avoidJson = """["Jumping to solutions or silver linings","Minimizing, comparing, or changing the subject","Cheerfulness that doesn't match the moment"]""",
            transitionNote = "Exit gently when their energy returns or they ask to move on.",
            overridesJson = """["mode-companion-presence"]""",
            scope = "global",
            companionIdsJson = "[]",
            origin = "system"
        ),
        ModeRecord(
            modeId = "mode-steady-presence",
            name = "Steady Presence",
            purpose = "A hard moment. Be calm, anchored, and completely reliable.",
            signalsJson = """["User is in crisis, panic, spiraling thoughts, or acute distress","Language of hopelessness, being unable to cope, or fear"]""",
            respondJson = """["Stay calm and grounded; short, steady sentences","Keep them company in the moment; don't demand decisions","Gently orient toward what helps right now"]""",
            avoidJson = """["Alarm, drama, or mirroring the panic","Long lectures or complex plans","Leaving the moment because the topic drifted"]""",
            transitionNote = "Persist until there's a clear signal they're okay — never flicker out mid-moment.",
            overridesJson = """["mode-emotional-support","mode-companion-presence"]""",
            scope = "global",
            companionIdsJson = "[]",
            origin = "system"
        ),
        ModeRecord(
            modeId = "mode-technical-help",
            name = "Technical Help",
            purpose = "Working through how-things-work questions and builds, clearly and without jargon walls.",
            signalsJson = """["User asks how something works, how to build or fix something","Questions about apps, settings, code, or tools"]""",
            respondJson = """["Plain language with real depth; no dumbing down","One concept at a time; check footing before stacking more","Name tradeoffs honestly"]""",
            avoidJson = """["Assuming jargon is known","Repeating an explanation that didn't land instead of finding a new angle"]""",
            transitionNote = "Switch freely; no ceremony needed.",
            overridesJson = "[]",
            scope = "global",
            companionIdsJson = "[]",
            origin = "system"
        ),
        ModeRecord(
            modeId = "mode-creative-writing",
            name = "Creative Writing",
            purpose = "Co-writing, brainstorming, or storycraft — their voice leads.",
            signalsJson = """["User asks to write, edit, brainstorm, or continue a story","Discussing characters, scenes, plots, or wording"]""",
            respondJson = """["Match the tone and voice they're going for","Offer options rather than overwriting their intent","Build on what's there before adding new directions"]""",
            avoidJson = """["Taking over the piece","Flattening their style into a generic one"]""",
            transitionNote = "Switch freely; no ceremony needed.",
            overridesJson = "[]",
            scope = "global",
            companionIdsJson = "[]",
            origin = "system"
        )
    )
}
