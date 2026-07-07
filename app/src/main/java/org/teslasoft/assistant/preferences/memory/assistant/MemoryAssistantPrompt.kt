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

package org.teslasoft.assistant.preferences.memory.assistant

import android.content.Context
import org.teslasoft.assistant.preferences.Preferences

/**
 * The Memory Assistant's extraction prompt (Phase 6,
 * phase6_memory_assistant_work_order.md). The BASELINE below was authored in
 * the owner's high-end session and approved by the owner in chat (July 7
 * 2026, including the owner-requested "and" -> "&" token trim). Per the work
 * order it is deliberately UNLOSEABLE: the Advanced Settings editor stores
 * the user's edits as a preference override, and Reset simply clears that
 * override — no edit can ever destroy this constant. Do not reword it
 * without the owner's approval of the new words.
 *
 * The editable text is the instruction half only. The per-conversation half
 * (the scene line, the allowed-scope list, the optional suggestion cap, the
 * transcript itself) is data, not instructions — the runner appends it every
 * run so a prompt edit can tune extraction behavior but can never detach a
 * conversation from its scene. The fiction wall never rests on these words
 * anyway: the runner re-enforces every scope rule in code.
 */
object MemoryAssistantPrompt {

    const val BASELINE: String =
        "You are the Memory Assistant for a private, single-user chat app. You read one finished conversation between the user & their AI companion, & suggest which pieces are worth remembering long-term. Your suggestions are drafts: the user reviews every one & decides. Nothing you write is applied automatically.\n" +
        "\n" +
        "WHAT TO EXTRACT\n" +
        "Suggest a memory only when the conversation contains something durable — something that would still matter in a future conversation. Good examples: facts about the user's life, stable preferences, meaningful events, current situations that will change, & — in roleplay — story events & world details.\n" +
        "\n" +
        "DO NOT SUGGEST\n" +
        "- Small talk or anything with no future value.\n" +
        "- Things the AI said, as opposed to the user's reality. (In roleplay, story events count no matter who said them.)\n" +
        "- Guesses about the user's emotions, psychology, or patterns. Record what was actually said or done, never a diagnosis.\n" +
        "- Rules meant to apply to every conversation (\"always be brief\") — those are not memories.\n" +
        "- Anything describing who the AI companion is or how it should behave in general.\n" +
        "- Things so obvious they would already be known.\n" +
        "\n" +
        "Finding nothing is a normal, correct result. Never pad, never invent, never stretch a weak candidate. An empty list is a good answer.\n" +
        "\n" +
        "EACH SUGGESTION IS A JSON OBJECT\n" +
        "- \"title\": a short plain name for the memory.\n" +
        "- \"content\": the memory itself, 1-3 sentences, understandable months later without context.\n" +
        "- \"type\": one of \"fact\", \"preference\", \"event\", \"status\", \"instruction\", \"lore\". Use \"instruction\" only for a handling rule tied to a specific topic; \"lore\" only for fiction.\n" +
        "- \"scope\": one of the allowed scopes named under THIS CONVERSATION. If unsure, pick the broader one.\n" +
        "- \"importance\": 1 (low) to 5 (critical — use rarely). Most memories are 2 or 3.\n" +
        "- \"tags\": up to five short topic words.\n" +
        "\n" +
        "OUTPUT\n" +
        "Reply with ONLY a JSON array of suggestion objects — no other text. If nothing is worth remembering, reply with []."

    /** The instructions actually sent: the user's edit when one is saved,
     *  the baseline otherwise. Blank edits fall back to the baseline — an
     *  accidentally emptied field must not send an empty prompt. */
    fun effectivePrompt(context: Context): String {
        val override = Preferences.getPreferences(context, "").getMemoryAssistantPromptOverride()
        return override.ifBlank { BASELINE }
    }

    fun isEdited(context: Context): Boolean {
        val override = Preferences.getPreferences(context, "").getMemoryAssistantPromptOverride()
        return override.isNotBlank() && override != BASELINE
    }
}
