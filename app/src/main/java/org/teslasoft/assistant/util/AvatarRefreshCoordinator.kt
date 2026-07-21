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

package org.teslasoft.assistant.util

/**
 * Coordinates avatar-refresh requests for a chat's adapter-backed avatars
 * (Profile Images refresh fix, July 21 2026). One instance per side
 * (assistant, user). It holds NO Android state and touches no view — it only
 * decides two things, so both can be unit-tested on the JVM:
 *
 *  1. **Retain-until-ready.** A refresh can be asked for before the chat
 *     adapter exists: ChatActivity's onResume runs before the async chat
 *     load attaches the adapter. Such a request must be REMEMBERED and
 *     replayed the moment the adapter is ready, never silently dropped —
 *     that silent drop was the "image only updates after an app restart"
 *     bug. [newRequest] records the request as retained while the target is
 *     not ready; [markTargetReady] reports it back so the caller replays it.
 *
 *  2. **Newest-wins.** Refreshes resolve the picture off the main thread, so
 *     two can be in flight at once (e.g. onResume and a Quick Settings
 *     update). An OLDER resolve finishing LATER must not overwrite a NEWER
 *     selection. Every request takes a monotonic token; [isCurrent] tells the
 *     completing coroutine whether its token is still the latest, so a stale
 *     result is discarded instead of clobbering the current one.
 *
 * Not thread-safe by design: ChatActivity only touches it on the main thread
 * (request bookkeeping and the apply decision both run there; only the file
 * resolve hops to IO).
 */
class AvatarRefreshCoordinator {

    private var latestToken: Long = 0L
    private var targetReady: Boolean = false
    private var retainedRequest: Boolean = false

    /**
     * Registers a new refresh request and returns its token. Stamp the async
     * resolve with this token and pass it back to [isCurrent] before applying
     * the result. If the target (adapter) is not ready yet, the request is
     * retained so [markTargetReady] can replay it.
     */
    fun newRequest(): Long {
        latestToken++
        if (!targetReady) retainedRequest = true
        return latestToken
    }

    /**
     * True iff [token] is still the most recent request — nothing newer has
     * superseded it — so its resolved picture is safe to apply.
     */
    fun isCurrent(token: Long): Boolean = token == latestToken

    /**
     * Marks the adapter attached. Returns true if a refresh was requested
     * while the target was not ready (and clears that retained flag), so the
     * caller can replay it now. Idempotent apart from consuming the retained
     * flag.
     */
    fun markTargetReady(): Boolean {
        targetReady = true
        val retained = retainedRequest
        retainedRequest = false
        return retained
    }

    /** True while the adapter is attached. */
    fun isTargetReady(): Boolean = targetReady

    /** True while a pre-adapter request is still waiting to be replayed. */
    fun hasRetainedRequest(): Boolean = retainedRequest
}
