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

package org.teslasoft.assistant.conversation.reply

import org.teslasoft.assistant.reasoning.LegacyReasoningRepair
import org.teslasoft.assistant.reasoning.ReasoningCapability
import org.teslasoft.assistant.reasoning.ReasoningEffort
import org.teslasoft.assistant.reasoning.ReasoningIndicator
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter

/** The reasoning a provider returned for a reply, stamped on that reply's row. */
internal object ObservedReasoning {
    /** Stamps [observation]'s reasoning on [row]; false when there is none to show. */
    fun applyTo(
        row: MutableMap<String, Any>,
        observation: ReasoningObservationContext,
        learnedCapability: ReasoningCapability?
    ): Boolean {
        val acc = observation.accumulator
        if (!acc.hasReasoning()) return false
        val snapshot = acc.snapshot()
        if (snapshot.text.isBlank()) return false
        row[ChatAdapter.KEY_MESSAGE_REASONING] = snapshot.text
        row[ChatAdapter.KEY_MESSAGE_REASONING_FORMAT] = LegacyReasoningRepair.FORMAT_VERSION
        row[ChatAdapter.KEY_MESSAGE_REASONING_SUMMARY] = snapshot.isSummary.toString()
        if (!row.containsKey(ChatAdapter.KEY_MESSAGE_REASONING_LEVEL)) {
            ReasoningIndicator.forGeneration(learnedCapability ?: ReasoningCapability.UNKNOWN, ReasoningEffort.AUTO)
                ?.let { row[ChatAdapter.KEY_MESSAGE_REASONING_LEVEL] = it.token }
        }
        snapshot.reasoningTokens?.let { row[ChatAdapter.KEY_MESSAGE_REASONING_TOKENS] = it.toString() }
        return true
    }

    /** Whether [row] is the reply [observation] belongs to. */
    fun belongsTo(row: Map<String, Any>, observation: ReasoningObservationContext): Boolean =
        row["isBot"] == true && org.teslasoft.assistant.preferences.MessageIdentity.idOf(row) == observation.messageId
}
