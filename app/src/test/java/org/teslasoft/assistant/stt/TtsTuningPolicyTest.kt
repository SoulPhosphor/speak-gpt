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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the verify-and-reapply rule for the saved TTS speech rate/pitch
 * (owner-approved July 11 2026): acceptance is silent, a rejection earns
 * exactly one re-application, and a second rejection gives up — never an
 * endless reapply loop, never per-utterance reapplication.
 */
class TtsTuningPolicyTest {

    private val ok = TtsTuningPolicy.ENGINE_SUCCESS
    private val error = -1 // TextToSpeech.ERROR

    @Test
    fun acceptedTuningIsDoneAndSilent() {
        assertEquals(TtsTuningPolicy.Next.DONE, TtsTuningPolicy.afterApply(ok, ok, isRetry = false))
        assertEquals(TtsTuningPolicy.Next.DONE, TtsTuningPolicy.afterApply(ok, ok, isRetry = true))
    }

    @Test
    fun rejectedRateAtInitEarnsExactlyOneRetry() {
        assertEquals(TtsTuningPolicy.Next.RETRY_ONCE, TtsTuningPolicy.afterApply(error, ok, isRetry = false))
    }

    @Test
    fun rejectedPitchAloneAlsoRetries() {
        assertEquals(TtsTuningPolicy.Next.RETRY_ONCE, TtsTuningPolicy.afterApply(ok, error, isRetry = false))
    }

    @Test
    fun rejectionOnTheRetryGivesUpInsteadOfLooping() {
        assertEquals(TtsTuningPolicy.Next.GIVE_UP, TtsTuningPolicy.afterApply(error, ok, isRetry = true))
        assertEquals(TtsTuningPolicy.Next.GIVE_UP, TtsTuningPolicy.afterApply(error, error, isRetry = true))
    }
}
