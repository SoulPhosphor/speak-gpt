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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two behaviors the avatar refresh depends on (Profile Images refresh fix):
 * a request made before the adapter exists is retained and replayed, and an
 * older async resolve can never overwrite a newer one.
 */
class AvatarRefreshCoordinatorTest {

    @Test fun requestBeforeTargetReadyIsRetainedThenReplayed() {
        val c = AvatarRefreshCoordinator()

        // onResume asks for a refresh before the adapter is attached.
        c.newRequest()
        assertFalse("target not ready yet", c.isTargetReady())
        assertTrue("the request is remembered, not discarded", c.hasRetainedRequest())

        // The adapter attaches: the retained request must be reported so it
        // can be replayed, and the flag cleared.
        assertTrue("markTargetReady reports the retained request", c.markTargetReady())
        assertTrue(c.isTargetReady())
        assertFalse("retained flag is consumed exactly once", c.hasRetainedRequest())
        assertFalse("a second markTargetReady has nothing to replay", c.markTargetReady())
    }

    @Test fun requestAfterTargetReadyIsNotRetained() {
        val c = AvatarRefreshCoordinator()
        c.markTargetReady()
        c.newRequest()
        assertFalse("a request while ready runs immediately, nothing to retain", c.hasRetainedRequest())
    }

    @Test fun olderResultCannotOverwriteNewerSelection() {
        val c = AvatarRefreshCoordinator()
        c.markTargetReady()

        // Two refreshes launched close together (e.g. onResume + a Quick
        // Settings update); the first resolve is slow.
        val older = c.newRequest()
        val newer = c.newRequest()

        // The newer resolve finishes first and applies.
        assertTrue("newest request applies", c.isCurrent(newer))
        // The older resolve finishes later — it must be dropped.
        assertFalse("stale result is discarded", c.isCurrent(older))
    }

    @Test fun latestRequestAlwaysApplies() {
        val c = AvatarRefreshCoordinator()
        c.markTargetReady()
        var last = 0L
        repeat(5) { last = c.newRequest() }
        assertTrue(c.isCurrent(last))
    }

    @Test fun replayedRequestIsAlsoStaleGuarded() {
        val c = AvatarRefreshCoordinator()

        // Pre-adapter request (retained), then the adapter attaches and the
        // caller issues the replay as a fresh request.
        val preAdapter = c.newRequest()
        c.markTargetReady()
        val replay = c.newRequest()

        assertFalse("the pre-adapter token is now stale", c.isCurrent(preAdapter))
        assertTrue("the replay token is current", c.isCurrent(replay))
    }
}
