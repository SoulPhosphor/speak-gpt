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

package org.teslasoft.assistant.util.summarizer

/**
 * The two shipped summarizer prompts (conversation-summary-plan.md §5,
 * decision 5 — owner-authored, July 29 2026) and the fold-in request
 * scaffolding. `{length}` is replaced at runtime with the Summary Length
 * value from Summarizer Settings. The texts are the revert targets for
 * slots one and two; slots three to five ship empty.
 */
object SummarizerPrompts {

    const val SLOT_COUNT = 5

    const val STORYTELLER_NAME = "Storyteller"
    const val REPORTER_NAME = "Reporter"

    val STORYTELLER = """
You maintain a concise narrative recap of this conversation. Below are the existing recap and the messages that have just moved out of the recent-message window. Integrate the new information into one coherent updated recap.

Preserve:

- the main events and their order;
- the user's goals and concerns;
- important reasoning or context behind decisions;
- decisions that were made;
- unresolved questions, problems, or next steps;
- the current state of active topics.

Remove repetition, casual filler, and details that no longer affect the conversation. When newer information replaces or corrects older information, update the recap rather than preserving both versions. Do not present suggestions, guesses, or possibilities as settled facts.

Write in clear, compact prose under {length} words. Reply only with the updated recap.
    """.trimIndent()

    val REPORTER = """
You maintain compact reference notes for this conversation. Below are the existing notes and the messages that have just moved out of the recent-message window. Update the notes using short, standalone statements.

Keep only information that may be needed later, including:

- names and relevant identifying details;
- explicit preferences, requirements, and constraints;
- decisions that were actually made;
- current plans and commitments;
- unresolved tasks or questions;
- important facts stated by the user;
- the present status of active work.

Do not preserve conversational flow, emotional narration, repeated explanations, abandoned ideas, or temporary details with no likely future use. Do not turn an assistant suggestion into a decision. If newer information corrects, replaces, completes, or cancels an older item, revise or remove the older item.

Use brief bullet points, with one fact per bullet. Keep the complete list under {length} words. Reply only with the updated list.
    """.trimIndent()

    /** The shipped prompt text for a slot, or empty for slots three to five. */
    fun shippedPrompt(slot: Int): String = when (slot) {
        0 -> STORYTELLER
        1 -> REPORTER
        else -> ""
    }

    /** Replaces the `{length}` placeholder with the configured word limit. */
    fun render(prompt: String, lengthWords: Int): String =
        prompt.replace("{length}", lengthWords.toString())

    /**
     * The complete user-message body of one fold-in call: the rendered slot
     * prompt, the current summary, and the departing messages under plain
     * "Existing summary:" / "New messages to add to the summary:" labels.
     */
    fun foldInRequestBody(
        renderedPrompt: String,
        existingSummary: String,
        departingMessages: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder(renderedPrompt)
        sb.append("\n\nExisting summary:\n")
        sb.append(existingSummary.ifBlank { "None yet." })
        sb.append("\n\nNew messages to add to the summary:\n")
        for ((role, text) in departingMessages) {
            sb.append('\n').append(role).append(": ").append(text).append('\n')
        }
        return sb.toString()
    }
}
