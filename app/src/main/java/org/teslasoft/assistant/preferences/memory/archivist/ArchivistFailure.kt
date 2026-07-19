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

package org.teslasoft.assistant.preferences.memory.archivist

import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenerationErrorClassifier

/**
 * The visible failure categories of `archivist_status_wording_spec.md`
 * (reasons A–G): each maps to one on-screen sentence in the full-failure and
 * partial-failure states, and decides the action button ("Check Archivist
 * Settings" for settings-class causes, "Try Again" for everything
 * retryable). Persisted by key on the run row so Recent Memory Analysis can
 * label old runs.
 */
enum class ArchivistFailure(val key: String, val settingsRelated: Boolean) {
    UNREACHABLE("unreachable", false),   // A — service could not be reached
    REJECTED("rejected", true),          // B — access rejected (endpoint/key/model)
    LIMIT("limit", false),               // C — usage limit reached
    UNREADABLE("unreadable", false),     // D — response could not be read
    SAVE_FAILED("save_failed", false),   // E — drafts could not be saved
    INTERRUPTED("interrupted", false),   // F — run interrupted
    UNKNOWN("unknown", false);           // G — anything else

    companion object {
        fun fromKey(key: String?): ArchivistFailure? = entries.firstOrNull { it.key == key }

        /** Map an arbitrary failure through the app's shared generation-error
         *  classifier (unit-tested, dependency-name tolerant). Parse and save
         *  failures never come through here — their call sites tag them with
         *  [TaggedArchivistException] before the classifier could misfile
         *  them. */
        fun classify(error: Throwable): ArchivistFailure {
            if (error is TaggedArchivistException) return error.failure
            return when (GenerationErrorClassifier.classify(error).code) {
                GenErrorCode.N1, GenErrorCode.N2, GenErrorCode.N3, GenErrorCode.N4 -> UNREACHABLE
                GenErrorCode.A1, GenErrorCode.S1, GenErrorCode.S3,
                GenErrorCode.M1, GenErrorCode.M2 -> REJECTED
                GenErrorCode.Q1, GenErrorCode.M3 -> LIMIT
                else -> UNKNOWN
            }
        }
    }
}

/** Carries a call-site-known failure category through the per-conversation
 *  catch (a parse failure is UNREADABLE, a store insert failure SAVE_FAILED —
 *  the generic classifier cannot know that). */
class TaggedArchivistException(
    val failure: ArchivistFailure,
    cause: Throwable
) : Exception(cause.message, cause)
