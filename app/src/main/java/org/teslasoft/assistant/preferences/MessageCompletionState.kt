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

package org.teslasoft.assistant.preferences

/**
 * Completion state for a streamed assistant reply.
 *
 * An assistant reply is saved incrementally while it streams. If the process
 * is killed, the activity is destroyed, or generation is interrupted before
 * the stream completes, the partially-saved reply on disk is byte-identical
 * to a finished one — it could reopen looking complete and be treated as
 * finished conversation content by export, transcript capture, memory
 * extraction and RAG. This is the persisted completion marker that closes
 * that failure class.
 *
 * It lives in the assistant message map under [KEY_STATE] (an optional key on
 * the same JSON blob as the reply text, so state and text are ALWAYS written
 * atomically — there is no window where the text is final but the flag is
 * stale). Only assistant messages ever carry it.
 *
 * The safe default is the load-bearing rule: a message with NO state
 * (every historical message, and every message written by an older build) is
 * [isComplete] — historical replies must never suddenly appear incomplete.
 * The ONLY other complete value is [DONE]. Any other non-blank value —
 * including an unrecognized one written by some future build — is treated as
 * NOT complete and preserved as-is, never silently upgraded.
 *
 * Pure Kotlin (no Android) so it is unit-testable on the JVM — see
 * MessageCompletionStateTest.
 */
object MessageCompletionState {

    /** Map key holding the state string (see the constants below). */
    const val KEY_STATE = "state"

    /** Map key holding a machine-readable sub-reason for diagnostics only
     *  (never shown to the user; the user-facing marker keys off [KEY_STATE]). */
    const val KEY_STATE_DETAIL = "stateDetail"

    /** Map key holding the coded error text of a [FAILED] reply, kept SEPARATE
     *  from the reply text so the model's own words are never contaminated by
     *  error prose. Shown next to the marker only when "Show chat errors" is on. */
    const val KEY_ERROR_TEXT = "errorText"

    // ---- State values -----------------------------------------------------

    /** The reply is being generated right now. Found on disk at load time it
     *  can only mean the previous session died mid-stream (nothing wrote a
     *  terminal state) — the load reconciler turns it into [INTERRUPTED]. */
    const val STREAMING = "streaming"

    /** The stream completed normally. Complete. */
    const val DONE = "done"

    /** The user deliberately ended the reply (stop control / progress cancel /
     *  notification Hang Up) while the screen was alive. */
    const val STOPPED = "stopped"

    /** Generation threw — provider error, timeout, or network failure. The
     *  classifier code is carried in [KEY_STATE_DETAIL]. */
    const val FAILED = "failed"

    /** The app never got to write a terminal state: process death, app
     *  shutdown, or the activity being torn down mid-stream. */
    const val INTERRUPTED = "interrupted"

    // ---- State detail values (diagnostic only) ----------------------------

    /** The activity-destruction cancellation handler ran and stamped this. */
    const val DETAIL_SCREEN_CLOSED = "screen_closed"

    /** The load-time reconciler found a stale [STREAMING] row — a hard kill
     *  that ran no code on the way out. */
    const val DETAIL_PROCESS_DEATH = "process_death"

    /** The user deliberately pressed Stop. A benign, non-error terminal state —
     *  the chat shows no failure marker for it (owner ruling, Aug 8 2026). */
    const val DETAIL_USER_STOP = "user_stop"

    /** The reply ended early and the cause is genuinely unknown — carried on a
     *  [FAILED] reply so the chat shows the "ended unexpectedly" wording. A
     *  diagnostic problem, never treated as a user stop. */
    const val DETAIL_UNKNOWN_END = "unknown_end"

    /** Generation failed before any assistant reply was created — carried on a
     *  [FAILED] reply created to satisfy the invariant that a saved user message
     *  never ends with no explanation and no Regenerate target. */
    const val DETAIL_START_FAILED = "start_failed"

    /**
     * Whether a reply with this state should be treated as a finished,
     * reliable reply everywhere (model context, transcript, export, memory).
     *
     * Absent/blank (legacy) and [DONE] are the only complete states. Every
     * other non-blank value is NOT complete — including any unrecognized
     * future value, which is preserved and gated conservatively rather than
     * assumed finished.
     */
    fun isComplete(state: String?): Boolean =
        state.isNullOrBlank() || state == DONE

    /** Inverse of [isComplete]: the reply did not finish normally and should
     *  carry a visible marker + be gated from long-term memory. */
    fun isIncomplete(state: String?): Boolean = !isComplete(state)

    /**
     * A reply that has come to rest in a terminal non-complete state — failed,
     * stopped, interrupted, or any unrecognized non-streaming value. Excludes
     * [STREAMING] (still generating) and complete/legacy replies. This is the
     * "a real reply attempt has finished and it did not succeed" test.
     */
    fun isTerminalFailure(state: String?): Boolean =
        isIncomplete(state) && state != STREAMING

    /**
     * Whether a chat should show the "no successful reply yet" error avatar in
     * place of its Companion picture / glyph (owner ruling, July 31 2026).
     *
     * True when the conversation contains at least one assistant reply that
     * ended in a terminal failure ([isTerminalFailure]) and NOT ONE completed
     * reply ([isComplete]). A single completed reply anywhere clears it
     * permanently: the first good reply hands the avatar back to the Companion
     * even if a later reply in the same chat fails. A chat with only user
     * messages, or one still streaming its very first reply, does not qualify.
     *
     * Only assistant replies are considered (`isBot`); the state lives under
     * [KEY_STATE]. Pure Kotlin (no Android) so it is unit-testable on the JVM.
     */
    fun chatShowsErrorAvatar(messages: List<Map<String, Any?>>): Boolean {
        var sawFailure = false
        for (m in messages) {
            val isBot = m["isBot"] == true || m["isBot"]?.toString() == "true"
            if (!isBot) continue
            val state = m[KEY_STATE]?.toString()
            if (isComplete(state)) return false
            if (isTerminalFailure(state)) sawFailure = true
        }
        return sawFailure
    }

    /**
     * Classify how a **cancelled** generation should be recorded, from the real
     * cause signals rather than the mere fact that the coroutine was cancelled
     * (owner ruling, Aug 8 2026 — a cancelled coroutine never by itself proves
     * the user stopped it):
     *
     *  - [userStop]  — an actual Stop / Hang-Up / cancel action fired for THIS
     *                  generation. The only thing that counts as a user stop.
     *  - [destroying]— the activity was being torn down (app lifecycle).
     *  - [replyStarted] — an assistant reply bubble already existed.
     *
     * Returns the (state, detail) to stamp:
     *  - user stop                         -> [STOPPED] / [DETAIL_USER_STOP]
     *    (a benign, non-error state the chat shows no marker for).
     *  - not started (nothing streamed)    -> [FAILED] / [DETAIL_START_FAILED].
     *  - a reply in progress, teardown     -> [INTERRUPTED] / [DETAIL_SCREEN_CLOSED].
     *  - a reply in progress, cause unknown-> [FAILED] / [DETAIL_UNKNOWN_END].
     *
     * Every non-user outcome is a visible failure so a turn is never left with a
     * saved user message and no explanation. Pure Kotlin — unit-tested.
     */
    fun classifyCancellation(
        userStop: Boolean,
        destroying: Boolean,
        replyStarted: Boolean
    ): Pair<String, String> = when {
        userStop -> STOPPED to DETAIL_USER_STOP
        !replyStarted -> FAILED to DETAIL_START_FAILED
        destroying -> INTERRUPTED to DETAIL_SCREEN_CLOSED
        else -> FAILED to DETAIL_UNKNOWN_END
    }

    /**
     * The terminal state a stale on-disk state should be reconciled to at
     * load time, or null if the state needs no change. Only a still-open
     * [STREAMING] row is reconciled (to [INTERRUPTED]); every already-terminal
     * or legacy state is left untouched. Idempotent.
     */
    fun reconcileOnLoad(state: String?): String? =
        if (state == STREAMING) INTERRUPTED else null
}
