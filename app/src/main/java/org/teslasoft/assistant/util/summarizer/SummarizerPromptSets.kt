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

import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant

/**
 * The three Summarizer prompt collections edited on the Summarizer Prompts
 * screen (owner ruling, Oct 3 2026). Each collection is a list of named
 * prompts in the same shape as a companion's prompt variants; the one marked
 * default is the prompt in use. Built-in prompts ship with every collection
 * and cannot be deleted.
 *
 * A collection that has never been saved is built from the older five-slot
 * Summary Prompt and single Image Summary Prompt settings, so existing work
 * carries over. Compaction used the selected Summary Prompt before it had its
 * own collection, so its first build copies the summary collection.
 */
object SummarizerPromptSets {

    enum class Kind(val storageKey: String) {
        SUMMARY("summary"),
        COMPACTION("compaction"),
        IMAGE("image")
    }

    const val STORYTELLER_ID = "builtin_storyteller"
    const val REPORTER_ID = "builtin_reporter"
    const val IMAGE_SUMMARY_ID = "builtin_image_summary"
    const val IMAGE_SUMMARY_NAME = "Image Summary"

    /** The shipped prompts of a collection, in display order. */
    fun builtIns(kind: Kind): List<CompanionPromptVariant> = when (kind) {
        Kind.SUMMARY, Kind.COMPACTION -> listOf(
            CompanionPromptVariant(
                id = STORYTELLER_ID,
                name = SummarizerPrompts.STORYTELLER_NAME,
                text = SummarizerPrompts.STORYTELLER,
                isDefault = true
            ),
            CompanionPromptVariant(
                id = REPORTER_ID,
                name = SummarizerPrompts.REPORTER_NAME,
                text = SummarizerPrompts.REPORTER,
                isDefault = false
            )
        )
        Kind.IMAGE -> listOf(
            CompanionPromptVariant(
                id = IMAGE_SUMMARY_ID,
                name = IMAGE_SUMMARY_NAME,
                text = SummarizerPrompts.IMAGE_SUMMARY,
                isDefault = true
            )
        )
    }

    fun isBuiltIn(kind: Kind, id: String): Boolean = builtIns(kind).any { it.id == id }

    /** The collection as last saved, or as carried over from older settings. */
    fun load(prefs: Preferences, kind: Kind): List<CompanionPromptVariant> {
        val stored = CompanionPromptVariant.fromJson(prefs.getSummarizerPromptSet(kind.storageKey))
        val base = stored.ifEmpty {
            when (kind) {
                Kind.SUMMARY -> legacySlots(prefs)
                Kind.COMPACTION -> {
                    val savedSummary = CompanionPromptVariant.fromJson(
                        prefs.getSummarizerPromptSet(Kind.SUMMARY.storageKey)
                    )
                    savedSummary.ifEmpty { legacySlots(prefs) }
                }
                Kind.IMAGE -> fromLegacyImagePrompt(prefs.getImageSummaryPrompt())
            }
        }
        return normalize(kind, base)
    }

    fun save(prefs: Preferences, kind: Kind, variants: List<CompanionPromptVariant>) {
        prefs.setSummarizerPromptSet(kind.storageKey, CompanionPromptVariant.toJson(normalize(kind, variants)))
    }

    /**
     * The prompt text a request for [chatId] uses: the prompt chosen in Quick
     * Settings for this chat screen when it still exists, otherwise the
     * collection's default prompt. A blank prompt falls back to the first
     * built-in prompt, so a request can never run on empty instructions.
     */
    fun activeText(prefs: Preferences, kind: Kind, chatId: String = ""): String =
        activeTextOf(kind, load(prefs, kind), SummarizerPromptSession.chosenId(chatId, kind))

    /** Index of the prompt in use: the chosen prompt when present, else the default. */
    fun selectedIndex(variants: List<CompanionPromptVariant>, chosenId: String?): Int {
        val chosen = chosenId?.let { id -> variants.indexOfFirst { it.id == id } } ?: -1
        if (chosen >= 0) return chosen
        return variants.indexOfFirst { it.isDefault }.coerceAtLeast(0)
    }

    internal fun activeTextOf(
        kind: Kind,
        variants: List<CompanionPromptVariant>,
        chosenId: String? = null
    ): String =
        variants.getOrNull(selectedIndex(variants, chosenId))?.text.orEmpty()
            .ifBlank { builtIns(kind).first().text }

    private fun legacySlots(prefs: Preferences): List<CompanionPromptVariant> = fromLegacySlots(
        selectedSlot = prefs.getSummarizerSelectedSlot(),
        recencyCsv = prefs.getSummarizerSlotRecency(),
        slotName = prefs::getSummarizerSlotName,
        slotPrompt = prefs::getSummarizerSlotPrompt
    )

    /** Built-ins are restored if missing, and exactly one prompt is default. */
    internal fun normalize(kind: Kind, variants: List<CompanionPromptVariant>): List<CompanionPromptVariant> {
        val result = ArrayList(variants.map { it.copy() })
        for ((index, builtIn) in builtIns(kind).withIndex()) {
            if (result.none { it.id == builtIn.id }) {
                result.add(index.coerceAtMost(result.size), builtIn.copy(isDefault = false))
            }
        }
        val defaultIndex = result.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        result.forEachIndexed { index, variant -> variant.isDefault = index == defaultIndex }
        return result
    }

    /** Older five-slot Summary Prompt settings, in slot order. Empty custom
     *  slots are left out; the default is the prompt the older settings used. */
    internal fun fromLegacySlots(
        selectedSlot: Int,
        recencyCsv: String,
        slotName: (Int) -> String,
        slotPrompt: (Int) -> String
    ): List<CompanionPromptVariant> {
        fun slotText(slot: Int): String =
            slotPrompt(slot).ifBlank { SummarizerPrompts.shippedPrompt(slot) }

        val selected = selectedSlot
        val recency = recencyCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
        val inUse = (listOf(selected) + recency).firstOrNull { slotText(it).isNotBlank() } ?: 0

        val variants = ArrayList<CompanionPromptVariant>()
        for (slot in 0 until SummarizerPrompts.SLOT_COUNT) {
            val text = slotText(slot)
            val storedName = slotName(slot)
            when (slot) {
                0 -> variants.add(
                    CompanionPromptVariant(
                        id = STORYTELLER_ID,
                        name = storedName.ifBlank { SummarizerPrompts.STORYTELLER_NAME },
                        text = text,
                        isDefault = slot == inUse
                    )
                )
                1 -> variants.add(
                    CompanionPromptVariant(
                        id = REPORTER_ID,
                        name = storedName.ifBlank { SummarizerPrompts.REPORTER_NAME },
                        text = text,
                        isDefault = slot == inUse
                    )
                )
                else -> if (text.isNotBlank()) {
                    variants.add(
                        CompanionPromptVariant(
                            id = "legacy_slot_$slot",
                            name = storedName.ifBlank { "Preset ${slot + 1}" },
                            text = text,
                            isDefault = slot == inUse
                        )
                    )
                }
            }
        }
        return variants
    }

    /** The older single Image Summary Prompt. A custom prompt becomes a second,
     *  in-use prompt beside the built-in one. */
    internal fun fromLegacyImagePrompt(legacy: String): List<CompanionPromptVariant> {
        val builtIn = builtIns(Kind.IMAGE).first()
        if (legacy.isBlank() || legacy == builtIn.text) return listOf(builtIn)
        return listOf(
            builtIn.copy(isDefault = false),
            CompanionPromptVariant(id = "legacy_image_prompt", name = "Prompt 1", text = legacy, isDefault = true)
        )
    }
}
