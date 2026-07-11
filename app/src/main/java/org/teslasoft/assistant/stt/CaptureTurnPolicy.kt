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

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure decision logic for the on-device Whisper capture loop's failure
 * handling (no Android dependencies, unit-tested in app/src/test). The
 * capture loop in [LocalWhisperEngine] is impossible to exercise on the JVM
 * (AudioRecord, coroutines against a live mic), so every decision that CAN
 * be pulled out lives here where a test can pin it down:
 *
 *  - what a given AudioRecord.read() return value means ([AudioReadClassifier]),
 *  - when a listening turn has been running too long ([CaptureWatchdog]),
 *  - what to do when startRecording finds the previous session's flag still
 *    set ([StaleCapturePolicy]),
 *  - "exactly once" semantics for cleanup and failure callbacks ([OnceFlag]),
 *  - the bounded automatic-recovery budget ([BoundedRetryBudget]).
 */

/**
 * Why a capture session ended on its own, delivered to the UI through the
 * typed capture-error callback. Engine failure is NEVER encoded as silence,
 * a null transcript, or an empty transcript — a session that dies abnormally
 * always reports one of these instead, so the UI can tell "the mic broke"
 * apart from "the user said nothing".
 */
enum class CaptureErrorReason {
    /** AudioRecord.read() threw, returned an error code, or stopped returning data. */
    READ_FAILED,

    /** The capture loop itself crashed (VAD/diagnostics code threw). */
    CAPTURE_CRASHED,

    /** The VAD declared end-of-turn but nothing collected the audio within the
     *  grace window — the end-of-turn callback was lost (activity torn down
     *  mid-handoff) and the mic would otherwise stay open forever. */
    TURN_NOT_COLLECTED
}

/** Classification of a single AudioRecord.read() return value. */
object AudioReadClassifier {

    enum class Outcome { DATA, EMPTY, ERROR }

    fun classify(read: Int): Outcome = when {
        read > 0 -> Outcome.DATA
        read == 0 -> Outcome.EMPTY
        else -> Outcome.ERROR
    }

    /**
     * Human-readable name for a negative read() return. Values mirror the
     * AudioRecord constants (kept as literals so this file stays free of
     * android.* imports and runs on the plain JVM).
     */
    fun describe(read: Int): String = when (read) {
        -1 -> "$read (ERROR)"
        -2 -> "$read (ERROR_BAD_VALUE)"
        -3 -> "$read (ERROR_INVALID_OPERATION)"
        -6 -> "$read (ERROR_DEAD_OBJECT)"
        else -> "$read"
    }
}

/**
 * Wall-clock watchdog for one VAD-driven (hands-free) listening turn,
 * state-dependent per the owner's July 11 2026 ruling: healthy continuous
 * speech is never treated like a wedged capture, and successfully recorded
 * speech is never discarded by a time limit.
 *
 * The VAD's own silence / no-speech timers run on *audio* time (samples
 * captured) and REMAIN AUTHORITATIVE — this watchdog is only the independent
 * wall-clock backup for the states where those clocks can no longer be
 * trusted (callbacks lost, clocks legitimately paused by dead air/stalls, a
 * detector pinned "speech" by steady noise). Read failures and dead capture
 * loops are handled separately and promptly by the capture loop itself; they
 * never wait for these bounds.
 *
 *  - Soft limit ([maxTurnMs], default 10 min), speech ongoing: the turn is
 *    NOT cut off. [Action.ARM_SOFT_LIMIT] fires once and [softLimitArmed]
 *    latches: from then on the turn finishes at the next natural pause
 *    (the effective end-of-turn silence window shrinks to
 *    [SOFT_LIMIT_PAUSE_MS] via [effectiveSilenceTargetSamples]), flowing
 *    into the NORMAL end-of-turn → transcribe → submit path.
 *  - Soft limit, no speech yet: the configured no-speech clock should have
 *    ended the turn long ago (it is settable only up to 120 s), so reaching
 *    10 min without speech means that clock failed — the backup fires the
 *    normal no-speech ending ([Action.FORCE_NO_SPEECH]).
 *  - Post-turn grace ([postTurnGraceMs], default 60 s): once end-of-turn /
 *    no-speech fired, the UI must collect the audio promptly; if nothing
 *    does, the callback was lost and the session is aborted
 *    ([Action.ABORT_TURN_NOT_COLLECTED]) so the mic is released. This is the
 *    ONLY watchdog outcome reported as a capture failure.
 *  - Absolute ceiling ([hardLimitMs], default 12 min): if no natural pause
 *    ever arrived, [Action.FORCE_END_OF_TURN] ends the turn through the
 *    NORMAL end-of-turn path — everything captured is preserved, transcribed
 *    and submitted; the Event log states that the absolute limit ended the
 *    turn. Never an error, never a discard.
 *
 * Manual push-to-talk capture is deliberately NOT watched: there the user
 * explicitly owns start/stop and a cap would cut off intended long dictation.
 * Manual sessions are still cleaned up by read-failure detection, activity
 * destruction (engine cancel in onDestroy), and stale-session recovery at
 * the next arm.
 */
class CaptureWatchdog(
    private val maxTurnMs: Long = MAX_TURN_MS,
    private val postTurnGraceMs: Long = POST_TURN_GRACE_MS,
    private val hardLimitMs: Long = HARD_LIMIT_MS
) {
    enum class Action { CONTINUE, ARM_SOFT_LIMIT, FORCE_NO_SPEECH, FORCE_END_OF_TURN, ABORT_TURN_NOT_COLLECTED }

    /** Latched by the soft limit while speech is ongoing: the turn should now
     *  finish at the next natural pause instead of the full configured
     *  silence window. Read by [effectiveSilenceTargetSamples]. */
    var softLimitArmed = false
        private set

    private var softCapHandled = false

    /**
     * @param elapsedMs wall-clock ms since the session started
     * @param speechStarted whether the VAD has heard speech this turn
     * @param vadFired whether end-of-turn / no-speech has already fired
     * @param sinceVadFiredMs wall-clock ms since the VAD fired (0 if it hasn't)
     */
    fun check(elapsedMs: Long, speechStarted: Boolean, vadFired: Boolean, sinceVadFiredMs: Long): Action {
        if (vadFired) {
            // The turn already ended; the only thing left to watch is whether
            // anyone collects it. (The absolute ceiling no longer applies —
            // ending the turn again would be meaningless.)
            return if (sinceVadFiredMs >= postTurnGraceMs) Action.ABORT_TURN_NOT_COLLECTED else Action.CONTINUE
        }
        if (elapsedMs >= hardLimitMs) {
            // Absolute ceiling: end the turn NOW through the normal path.
            // With speech, everything captured is transcribed and submitted;
            // without (defensive — the soft limit should have ended it), the
            // normal no-speech ending runs.
            return if (speechStarted) Action.FORCE_END_OF_TURN else Action.FORCE_NO_SPEECH
        }
        if (!softCapHandled && elapsedMs >= maxTurnMs) {
            softCapHandled = true
            if (speechStarted) {
                softLimitArmed = true
                return Action.ARM_SOFT_LIMIT
            }
            return Action.FORCE_NO_SPEECH
        }
        return Action.CONTINUE
    }

    /**
     * The end-of-turn silence window the capture loop should apply right now:
     * the user's configured window normally; the short natural-pause window
     * once the soft limit armed (whichever is smaller, so a user-configured
     * window shorter than the pause window is never lengthened).
     */
    fun effectiveSilenceTargetSamples(configuredSamples: Long, softPauseSamples: Long): Long =
        if (softLimitArmed) minOf(configuredSamples, softPauseSamples) else configuredSamples

    companion object {
        const val MAX_TURN_MS = 10 * 60_000L
        const val POST_TURN_GRACE_MS = 60_000L
        const val HARD_LIMIT_MS = 12 * 60_000L

        /** Detected non-speech run that counts as the "natural pause" ending
         *  a soft-limited turn. Audio-time, like the silence clock it
         *  temporarily replaces. */
        const val SOFT_LIMIT_PAUSE_MS = 1_000L
    }
}

/**
 * What startRecording should do when it finds capture apparently already
 * active. The old behavior — return true and do nothing — reported a
 * successful new listening turn that did not exist (no new callbacks, no new
 * session): the "app says it's listening but nothing is" bug. Now:
 *
 *  - the previous session's loop is provably dead but left `isCapturing`
 *    stuck true → RECOVER_STALE: tear the corpse down and start fresh;
 *  - the previous session is genuinely alive → BUSY: refuse, so the caller's
 *    failure path (bounded retry / visible stop) runs instead of a lie.
 */
object StaleCapturePolicy {

    enum class Decision { PROCEED, RECOVER_STALE, BUSY }

    /**
     * @param isCapturing the engine-level "a session exists" flag
     * @param loopAlive true only when the capture coroutine is still active
     *   AND the session object hasn't been closed
     */
    fun decide(isCapturing: Boolean, loopAlive: Boolean): Decision = when {
        !isCapturing -> Decision.PROCEED
        loopAlive -> Decision.BUSY
        else -> Decision.RECOVER_STALE
    }
}

/**
 * One-shot latch: [tryClaim] returns true for exactly one caller, no matter
 * how many threads race it. Backs both "cleanup runs exactly once per
 * session" and "the failure callback fires at most once per session".
 */
class OnceFlag {
    private val done = AtomicBoolean(false)
    fun tryClaim(): Boolean = done.compareAndSet(false, true)
    fun isClaimed(): Boolean = done.get()
}

/**
 * Bounded budget for automatic voice-loop recovery, so a persistent failure
 * can never become an infinite retry cycle. [tryConsume] grants an attempt
 * while any remain; [reset] restores the budget when real progress is made
 * (a turn actually completed), not merely when a retry managed to start.
 */
class BoundedRetryBudget(private val max: Int) {
    private var used = 0

    /** Consume one attempt. False = budget exhausted; stop retrying. */
    fun tryConsume(): Boolean {
        if (used >= max) return false
        used++
        return true
    }

    fun reset() { used = 0 }

    fun attemptsUsed(): Int = used
}
