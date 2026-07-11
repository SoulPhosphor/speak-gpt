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

package org.teslasoft.assistant.stt

/**
 * Decides what to do after applying the saved TTS delivery tuning (speech
 * rate + pitch) to the device engine. Pure so it's unit-testable.
 *
 * Why this exists (owner report, July 11 2026: "the readback suddenly talks
 * faster on a new session"): the app applied the saved rate at engine init
 * but IGNORED the engine's answer. The Google engine can reject a
 * setSpeechRate/setPitch call made at the exact moment init completes
 * (returns ERROR, throws nothing) — the whole session then speaks at the
 * engine's default rate, faster than the owner's saved value, with no trace.
 * The rule: verify acceptance, re-apply the same saved values exactly ONCE
 * shortly after init if rejected, log only rejection/fallback (never
 * success), and never touch the saved value or re-apply per utterance.
 *
 * The engine offers no getter for the active rate, so a silent
 * accepted-but-ignored mismatch cannot be detected — only the result codes.
 * The system-wide Android speech rate (Accessibility settings) multiplies
 * the app's rate and is external; nothing in the app can see or change it.
 */
object TtsTuningPolicy {

    /** Mirrors TextToSpeech.SUCCESS; kept as a literal so this file stays
     *  free of android.* imports and runs on the plain JVM. */
    const val ENGINE_SUCCESS = 0

    enum class Next {
        /** Both values accepted — nothing to do, nothing to log. */
        DONE,

        /** Engine rejected something on the initial application — re-apply
         *  the same saved values once, shortly after init, and log it. */
        RETRY_ONCE,

        /** Rejected again on the retry — stop (never loop), log that this
         *  session may speak at the engine's default rate. */
        GIVE_UP
    }

    fun afterApply(rateResult: Int, pitchResult: Int, isRetry: Boolean): Next = when {
        rateResult == ENGINE_SUCCESS && pitchResult == ENGINE_SUCCESS -> Next.DONE
        !isRetry -> Next.RETRY_ONCE
        else -> Next.GIVE_UP
    }
}
