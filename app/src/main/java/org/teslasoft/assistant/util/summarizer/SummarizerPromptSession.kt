/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import java.util.concurrent.ConcurrentHashMap

/**
 * The Summary, Compaction, and Image prompts chosen in Quick Settings for one
 * open chat screen (owner ruling, Oct 3 2026). Held in memory only: opening a
 * chat screen resets its choices, so every chat starts from each collection's
 * default prompt. Leaving for the prompt editor and coming back keeps them.
 */
object SummarizerPromptSession {
    /** Chat screen intent extra: keep this chat's choices (a Quick Settings
     *  rebuild of the same screen, not a newly opened chat). */
    const val EXTRA_KEEP_CHOICES = "keep_summarizer_prompt_choices"

    private val choices = ConcurrentHashMap<String, ConcurrentHashMap<SummarizerPromptSets.Kind, String>>()

    fun reset(chatId: String) {
        choices.remove(chatId)
    }

    fun choose(chatId: String, kind: SummarizerPromptSets.Kind, promptId: String) {
        if (chatId.isBlank()) return
        choices.getOrPut(chatId) { ConcurrentHashMap() }[kind] = promptId
    }

    /** The chosen prompt id, or null when this chat screen uses the default. */
    fun chosenId(chatId: String, kind: SummarizerPromptSets.Kind): String? =
        choices[chatId]?.get(kind)
}
