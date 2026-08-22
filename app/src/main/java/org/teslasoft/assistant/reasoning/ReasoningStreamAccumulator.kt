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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Normalizes provider-supplied reasoning out of a Chat Completions stream into
 * one provider-neutral per-message representation (chat-redesign-plan.md §7.2).
 *
 * The app's typed streaming client cannot see reasoning (its delta type has no
 * such field), so reasoning is read from a split copy of the same stream by
 * feeding each SSE line here. This accumulator recognizes the supported shapes:
 *
 * - `choices[].delta.reasoning` — OpenRouter / xAI and compatibles.
 * - `choices[].delta.reasoning_content` — DeepSeek and compatibles.
 * - `choices[].delta.reasoning_details[]` — OpenRouter structured blocks. A
 *   block's human-readable content is read from `text`, and, when `text` is
 *   absent, from `summary` (the documented field for a `reasoning.summary`
 *   block); its `type` can mark a summary rather than raw reasoning (a
 *   `*.summary` type preserves it AS a summary, §7.2). The `text`-first order
 *   keeps the existing raw/text path byte-identical; `summary` is a purely
 *   additive fallback for summary-shaped blocks.
 * - reasoning token usage from `usage.completion_tokens_details.reasoning_tokens`
 *   or `usage.reasoning_tokens`, kept SEPARATE from answer tokens (§7.8).
 *
 * It only ever COLLECTS reasoning; it never touches answer content and carries
 * no notion of completion, so consuming it cannot advance the response or the
 * voice loop (the typed stream remains the sole owner of the final answer and
 * of completion). Purely additive and fail-safe: a malformed or unrelated line
 * is ignored. Not thread-safe; the observer drains one stream on one coroutine.
 */
class ReasoningStreamAccumulator {

    private val reasoning = StringBuilder()
    private var sawSummaryBlock = false
    private var sawRawBlock = false
    private var reasoningTokens: Int? = null

    // Which inbound reasoning representations were seen at all this turn, for
    // diagnostics only (field presence, never the reasoning text). Distinct from
    // sawSummaryBlock/sawRawBlock, which decide how to PRESENT collected text.
    private var inboundReasoning = false
    private var inboundReasoningContent = false
    private var inboundReasoningDetails = false
    private var inboundSummaryField = false

    // Raw `reasoning_details` blocks collected verbatim for resend across a
    // tool-call continuation (§7.2). Encrypted/signature blocks are preserved
    // unmodified; streamed text fragments that share an index are merged so the
    // resent array matches the provider's final message shape. This is
    // continuation state, NOT display content.
    private val reasoningDetailBlocks = ArrayList<JsonObject>()

    /** Feed one raw SSE line (with or without the `data:` prefix). */
    fun acceptLine(line: String) {
        val payload = payloadFromLine(line) ?: return
        if (payload == "[DONE]") return
        val root = try {
            JsonParser.parseString(payload).takeIf { it.isJsonObject }?.asJsonObject ?: return
        } catch (_: Exception) {
            return
        }
        acceptObject(root)
    }

    /** Feed one already-parsed SSE data object. */
    fun acceptObject(root: JsonObject) {
        root.get("usage")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject?.let(::readReasoningTokens)

        val choices = root.get("choices")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (element in choices) {
            val choice = element.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            // Streaming carries a `delta`; a non-streamed body carries `message`.
            val node = choice.get("delta")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject
                ?: choice.get("message")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject
                ?: continue
            readReasoningFromNode(node)
        }
    }

    private fun readReasoningFromNode(node: JsonObject) {
        val directReasoning = stringOrNull(node, "reasoning")
        val compatibilityReasoning = stringOrNull(node, "reasoning_content")
        if (directReasoning != null) inboundReasoning = true
        if (compatibilityReasoning != null) inboundReasoningContent = true
        val detailText = StringBuilder()
        var detailHasSummary = false
        var detailHasRaw = false

        node.get("reasoning_details")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.forEach { el ->
                val block = el.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                inboundReasoningDetails = true
                collectDetailBlock(block)
                val type = stringOrNull(block, "type").orEmpty().lowercase()
                if (type.contains("summary")) detailHasSummary = true else if (type.isNotEmpty()) detailHasRaw = true
                // The human-readable payload is normally a flat `text` string;
                // prefer it so the existing raw/text path is byte-identical.
                // When there is none, the reasoning came back structured: a
                // `summary` string, or — as OpenAI-style summaries do when passed
                // through OpenRouter — a `summary`/`content`/`parts` value whose
                // parts carry the text one level down. Pull the text out of those
                // shapes so a structured summary is not dropped as empty. `data`
                // blocks (encrypted continuation state) hold no readable string
                // and contribute nothing.
                val flatText = stringOrNull(block, "text")
                if (flatText != null) {
                    detailText.append(flatText)
                } else {
                    if (block.get("summary")?.takeUnless { it.isJsonNull } != null) {
                        inboundSummaryField = true
                        detailHasSummary = true
                    }
                    val nested = StringBuilder()
                    collectReadableText(block.get("summary"), nested)
                    collectReadableText(block.get("content"), nested)
                    collectReadableText(block.get("parts"), nested)
                    if (nested.isNotEmpty()) detailText.append(nested)
                }
            }

        // One provider event may expose the same display delta through several
        // compatibility representations. Choose exactly one source for display
        // in stable priority order while retaining every structured detail block
        // above for continuation/tool-call state. Deduplicating here, at the
        // field-normalization boundary, preserves legitimate repeated text that
        // arrives in separate model deltas.
        when {
            directReasoning != null -> {
                reasoning.append(directReasoning)
                sawRawBlock = true
            }
            compatibilityReasoning != null -> {
                reasoning.append(compatibilityReasoning)
                sawRawBlock = true
            }
            detailText.isNotEmpty() -> {
                reasoning.append(detailText)
                if (detailHasSummary) sawSummaryBlock = true
                if (detailHasRaw) sawRawBlock = true
            }
        }
    }

    /**
     * Fold one streamed `reasoning_details` block into [reasoningDetailBlocks].
     * Text fragments sharing an `index` are concatenated into the existing block
     * so the resent array is the final shape; every other block (encrypted,
     * signature, summary) is appended verbatim and never merged or altered.
     */
    private fun collectDetailBlock(block: JsonObject) {
        val index = intOrNull(block, "index")
        val type = stringOrNull(block, "type").orEmpty()
        val isTextFragment = type.contains("text", ignoreCase = true) &&
            block.get("text")?.takeUnless { it.isJsonNull }?.isJsonPrimitive == true
        if (index != null && isTextFragment) {
            val existing = reasoningDetailBlocks.firstOrNull {
                intOrNull(it, "index") == index &&
                    stringOrNull(it, "type").orEmpty() == type &&
                    it.get("text")?.takeUnless { t -> t.isJsonNull }?.isJsonPrimitive == true
            }
            if (existing != null) {
                val merged = (stringOrNull(existing, "text") ?: "") + (stringOrNull(block, "text") ?: "")
                existing.addProperty("text", merged)
                return
            }
        }
        reasoningDetailBlocks.add(block)
    }

    /**
     * The collected `reasoning_details` array to echo back on the assistant
     * message of a tool-call continuation (§7.2), or null when none was
     * supplied. Preserved as-is so encrypted/signature continuation state is
     * resent unmodified. Independent of Show Reasoning — the state is retained
     * for continuation whether or not it is displayed.
     */
    fun reasoningDetails(): JsonArray? {
        if (reasoningDetailBlocks.isEmpty()) return null
        val array = JsonArray()
        reasoningDetailBlocks.forEach { array.add(it) }
        return array
    }

    private fun readReasoningTokens(usage: JsonObject) {
        intOrNull(usage, "reasoning_tokens")?.let { reasoningTokens = it }
        usage.get("completion_tokens_details")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { intOrNull(it, "reasoning_tokens")?.let { n -> reasoningTokens = n } }
    }

    /** True when any reasoning text has been collected. */
    fun hasReasoning(): Boolean = reasoning.isNotBlank()

    /**
     * Field-presence snapshot for diagnostics (§7.8): which inbound reasoning
     * representations this stream carried and how many characters of reasoning
     * text were collected. Contains NO reasoning text itself, so it is safe to
     * log. Lets a diagnostic separate "nothing was returned" from "returned but
     * not displayed", which the outbound request intent cannot show.
     */
    fun inboundDiagnostics(): InboundReasoningDiagnostics = InboundReasoningDiagnostics(
        reasoning = inboundReasoning,
        reasoningContent = inboundReasoningContent,
        reasoningDetails = inboundReasoningDetails,
        summaryField = inboundSummaryField,
        characters = reasoning.length
    )

    /** Snapshot of what has accumulated so far. Safe to call repeatedly (during
     *  streaming for a live update, and once more at completion). */
    fun snapshot(): NormalizedReasoning = NormalizedReasoning(
        text = reasoning.toString().trim(),
        // A summary is reported as such only when the provider supplied summary
        // blocks and no raw reasoning alongside them (§7.2: don't dress a
        // summary up as raw reasoning).
        isSummary = sawSummaryBlock && !sawRawBlock,
        reasoningTokens = reasoningTokens
    )

    private fun payloadFromLine(line: String): String? {
        val trimmed = line.trim()
        val payload = when {
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed.substring(5).trim()
            trimmed.startsWith("{") -> trimmed
            else -> return null
        }
        return payload.ifBlank { null }
    }

    /**
     * Gather readable reasoning text from a structured `reasoning_details`
     * sub-value across the documented nested shapes. A string is appended as-is;
     * an array is walked; an object contributes its `text` (preferred) or
     * `summary` string, and otherwise is walked into `content`/`parts`. This is
     * how an OpenAI-style summary — a `summary` array of parts each carrying a
     * `text` — surfaces after passing through OpenRouter. Encrypted `data` is
     * never a readable string here, so it is naturally skipped. Fail-safe: any
     * unexpected shape simply contributes nothing.
     */
    private fun collectReadableText(element: com.google.gson.JsonElement?, out: StringBuilder) {
        if (element == null || element.isJsonNull) return
        when {
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> out.append(element.asString)
            element.isJsonArray -> element.asJsonArray.forEach { collectReadableText(it, out) }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val direct = stringOrNull(obj, "text") ?: stringOrNull(obj, "summary")
                if (direct != null) {
                    out.append(direct)
                } else {
                    collectReadableText(obj.get("content"), out)
                    collectReadableText(obj.get("parts"), out)
                }
            }
        }
    }

    private fun stringOrNull(obj: JsonObject, name: String): String? = try {
        obj.get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    private fun intOrNull(obj: JsonObject, name: String): Int? = try {
        obj.get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asInt
    } catch (_: Exception) {
        null
    }
}

/**
 * One message's normalized reasoning (§7.2). [text] is the display string;
 * [isSummary] marks provider-supplied summaries so they are never presented as
 * raw reasoning; [reasoningTokens] is the provider-reported reasoning-token
 * count kept separate from answer tokens (§7.8), or null when none was reported.
 */
data class NormalizedReasoning(
    val text: String,
    val isSummary: Boolean,
    val reasoningTokens: Int?
)

/**
 * Which inbound reasoning representations a stream carried, plus the collected
 * reasoning character count — for diagnostics only. Field presence and a count,
 * never the reasoning text itself (§7.8: log presence, not content).
 */
data class InboundReasoningDiagnostics(
    val reasoning: Boolean,
    val reasoningContent: Boolean,
    val reasoningDetails: Boolean,
    val summaryField: Boolean,
    val characters: Int
) {
    /** True when the stream carried any reasoning field at all. */
    val anyField: Boolean get() = reasoning || reasoningContent || reasoningDetails
}
