/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.teslasoft.assistant.preferences.Preferences

/**
 * Loads a chat's summary sections against its current messages, the one
 * path every reader uses. The first load carries an older single summary
 * over as a legacy block (once: afterwards the stored list exists, even when
 * empty), then repairs ownership after edits and deletions and saves any
 * change. Compact's own summary fields are read, never changed.
 */
object SummarySectionStore {

    fun load(
        prefs: Preferences,
        current: List<SummarySections.SourceMessage>
    ): List<SummarySections.Section>? {
        return try {
            val stored = prefs.getSummarySections()
            var decodeFailure: Exception? = null
            var sections = SummarySections.fromJson(stored) { decodeFailure = it }
            decodeFailure?.let {
                prefs.reportSummarizerStorageFailure("Summarizing: decode Summary Sections", it)
                return null
            }
            var changed = false
            if (stored.isBlank()) {
                changed = true
                if (!prefs.ensureSummarizerProjectionCompatibility()) return null
                run {
                    SummarySections.legacyBlock(
                        prefs.getSummarizerSummary(),
                        prefs.getSummarizerFoldedCount(),
                        current
                    )?.let { sections = listOf(it) }
                }
            }
            val reconciled = SummarySections.reconcile(sections, current)
            if (changed || reconciled.changed) {
                if (!prefs.commitSummarySections(SummarySections.toJson(reconciled.sections))) return null
            }
            reconciled.sections
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            prefs.reportSummarizerStorageFailure("Summarizing: load/reconcile Summary Sections", e)
            null
        }
    }

    fun save(prefs: Preferences, sections: List<SummarySections.Section>): Boolean = try {
        prefs.commitSummarySections(SummarySections.toJson(sections))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        prefs.reportSummarizerStorageFailure("Summarizing: encode Summary Sections", e)
        false
    }
}
