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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins down the pure decision logic behind the Whisper capture loop's
 * failure handling (see CaptureTurnPolicy.kt). The loop itself needs a live
 * AudioRecord and can't run on the JVM; these tests cover the parts of the
 * July 2026 capture-recovery work that CAN be verified here:
 *
 *  - an AudioRecord error return is classified as a failure (it used to be
 *    silently ignored and the loop busy-spun forever),
 *  - a stale stuck-isCapturing session is recovered, a live one refused
 *    (startRecording used to return a misleading `true`),
 *  - cleanup and the typed error callback run exactly once per session even
 *    when exit paths race,
 *  - the automatic-recovery retry budget is bounded and only reset on real
 *    progress,
 *  - the wall-clock watchdog ends an over-long listening turn the normal way
 *    and aborts a session whose end-of-turn was never collected.
 */
class CaptureTurnPolicyTest {

    // ---- AudioRecord read classification -----------------------------------

    @Test
    fun positiveReadIsData() {
        assertEquals(AudioReadClassifier.Outcome.DATA, AudioReadClassifier.classify(1))
        assertEquals(AudioReadClassifier.Outcome.DATA, AudioReadClassifier.classify(1024))
    }

    @Test
    fun zeroReadIsEmpty() {
        assertEquals(AudioReadClassifier.Outcome.EMPTY, AudioReadClassifier.classify(0))
    }

    @Test
    fun negativeReadsAreErrors() {
        // The AudioRecord error codes: ERROR, ERROR_BAD_VALUE,
        // ERROR_INVALID_OPERATION, ERROR_DEAD_OBJECT — and anything else
        // negative. Every one of these used to be swallowed by `if (read > 0)`.
        for (code in listOf(-1, -2, -3, -6, -42)) {
            assertEquals("read=$code", AudioReadClassifier.Outcome.ERROR, AudioReadClassifier.classify(code))
        }
    }

    @Test
    fun errorDescriptionsNameTheKnownCodes() {
        assertEquals("-6 (ERROR_DEAD_OBJECT)", AudioReadClassifier.describe(-6))
        assertEquals("-1 (ERROR)", AudioReadClassifier.describe(-1))
        assertEquals("-42", AudioReadClassifier.describe(-42))
    }

    // ---- Stale-session policy (stuck isCapturing) --------------------------

    @Test
    fun notCapturingProceeds() {
        assertEquals(StaleCapturePolicy.Decision.PROCEED, StaleCapturePolicy.decide(isCapturing = false, loopAlive = false))
    }

    @Test
    fun liveCaptureIsRefusedNotLiedAbout() {
        assertEquals(StaleCapturePolicy.Decision.BUSY, StaleCapturePolicy.decide(isCapturing = true, loopAlive = true))
    }

    @Test
    fun stuckFlagWithDeadLoopIsRecovered() {
        assertEquals(StaleCapturePolicy.Decision.RECOVER_STALE, StaleCapturePolicy.decide(isCapturing = true, loopAlive = false))
    }

    // ---- Cleanup / callback runs exactly once ------------------------------

    @Test
    fun onceFlagGrantsExactlyOneClaim() {
        val flag = OnceFlag()
        assertFalse(flag.isClaimed())
        assertTrue(flag.tryClaim())
        assertTrue(flag.isClaimed())
        // A duplicate caller (late callback, second cleanup path) is refused.
        assertFalse(flag.tryClaim())
        assertFalse(flag.tryClaim())
    }

    @Test
    fun onceFlagGrantsExactlyOneClaimUnderRacingThreads() {
        // The real race: the capture loop's abnormal-exit cleanup on the IO
        // thread vs. cancel()/stopAndTranscribe() on the main thread. Exactly
        // one may perform the teardown and fire the error callback.
        repeat(50) {
            val flag = OnceFlag()
            val start = CountDownLatch(1)
            val claims = AtomicInteger(0)
            val threads = (1..8).map {
                Thread {
                    start.await()
                    if (flag.tryClaim()) claims.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, claims.get())
        }
    }

    // ---- Bounded retry budget ----------------------------------------------

    @Test
    fun retryBudgetGrantsExactlyMaxAttempts() {
        val budget = BoundedRetryBudget(2)
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        // Exhausted: no third automatic recovery — the loop must stop visibly.
        assertFalse(budget.tryConsume())
        assertFalse(budget.tryConsume())
        assertEquals(2, budget.attemptsUsed())
    }

    @Test
    fun retryBudgetResetRestoresAttempts() {
        val budget = BoundedRetryBudget(1)
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
        budget.reset()
        assertTrue(budget.tryConsume())
    }

    @Test
    fun zeroBudgetNeverGrants() {
        assertFalse(BoundedRetryBudget(0).tryConsume())
    }

    // ---- Wall-clock watchdog -----------------------------------------------

    private fun watchdog() = CaptureWatchdog(maxTurnMs = 1000, postTurnGraceMs = 200, hardLimitMs = 2000)

    @Test
    fun continuesWithinAllBounds() {
        val wd = watchdog()
        assertEquals(CaptureWatchdog.Action.CONTINUE, wd.check(0, false, false, 0))
        assertEquals(CaptureWatchdog.Action.CONTINUE, wd.check(999, true, false, 0))
    }

    @Test
    fun softCapWithSpeechEndsTheTurnNormally() {
        // Speech was heard: the cap must flow into the NORMAL end-of-turn so
        // the audio is transcribed — never discarded.
        assertEquals(CaptureWatchdog.Action.FORCE_END_OF_TURN, watchdog().check(1000, true, false, 0))
    }

    @Test
    fun softCapWithoutSpeechEndsAsNoSpeech() {
        assertEquals(CaptureWatchdog.Action.FORCE_NO_SPEECH, watchdog().check(1000, false, false, 0))
    }

    @Test
    fun softCapFiresOnlyOnce() {
        val wd = watchdog()
        assertEquals(CaptureWatchdog.Action.FORCE_END_OF_TURN, wd.check(1000, true, false, 0))
        // The loop keeps running (buffering) until the audio is collected; the
        // soft cap must not fire again on the next iteration.
        assertEquals(CaptureWatchdog.Action.CONTINUE, wd.check(1001, true, true, 1))
    }

    @Test
    fun uncollectedTurnIsAbortedAfterGrace() {
        val wd = watchdog()
        assertEquals(CaptureWatchdog.Action.CONTINUE, wd.check(500, true, true, 199))
        assertEquals(CaptureWatchdog.Action.ABORT_TURN_NOT_COLLECTED, wd.check(501, true, true, 200))
    }

    @Test
    fun hardLimitAbortsRegardlessOfState() {
        assertEquals(CaptureWatchdog.Action.ABORT_HARD_LIMIT, watchdog().check(2000, true, false, 0))
        assertEquals(CaptureWatchdog.Action.ABORT_HARD_LIMIT, watchdog().check(2000, false, true, 50))
    }

    @Test
    fun defaultsKeepTheSoftCapFarAboveTheConfigurableWindows() {
        // The silence / no-speech windows are user-settable up to 120 s each;
        // the soft cap must stay far above them so it can never preempt an
        // intended hands-free pause.
        assertTrue(CaptureWatchdog.MAX_TURN_MS >= 4 * 120_000L)
        assertTrue(CaptureWatchdog.HARD_LIMIT_MS > CaptureWatchdog.MAX_TURN_MS)
        assertTrue(CaptureWatchdog.POST_TURN_GRACE_MS >= 30_000L)
    }
}
