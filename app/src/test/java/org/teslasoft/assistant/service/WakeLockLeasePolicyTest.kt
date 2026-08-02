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

package org.teslasoft.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLockLeasePolicyTest {

    @Test
    fun leaseRemainsSixtyMinutesAndRenewsHalfwayThrough() {
        assertEquals(60 * 60 * 1000L, WakeLockLeasePolicy.LEASE_MS)
        assertEquals(30 * 60 * 1000L, WakeLockLeasePolicy.RENEW_INTERVAL_MS)
        assertTrue(WakeLockLeasePolicy.RENEW_INTERVAL_MS < WakeLockLeasePolicy.LEASE_MS)
        assertEquals(
            30 * 60 * 1000L,
            WakeLockLeasePolicy.LEASE_MS - WakeLockLeasePolicy.RENEW_INTERVAL_MS
        )
    }

    @Test
    fun activeSessionWithMatchingTokenRenews() {
        assertTrue(
            WakeLockLeasePolicy.shouldRenew(
                serviceRunning = true,
                scheduledToken = 7L,
                activeToken = 7L
            )
        )
    }

    @Test
    fun stoppedSessionDoesNotRenew() {
        assertFalse(
            WakeLockLeasePolicy.shouldRenew(
                serviceRunning = false,
                scheduledToken = 7L,
                activeToken = 7L
            )
        )
    }

    @Test
    fun callbackFromEarlierSessionDoesNotRenew() {
        assertFalse(
            WakeLockLeasePolicy.shouldRenew(
                serviceRunning = true,
                scheduledToken = 7L,
                activeToken = 8L
            )
        )
    }

    @Test
    fun failedRenewalRetriesBeforeCurrentLeaseCanExpire() {
        assertTrue(WakeLockLeasePolicy.RETRY_INTERVAL_MS < WakeLockLeasePolicy.RENEW_INTERVAL_MS)
        assertTrue(WakeLockLeasePolicy.RETRY_INTERVAL_MS < WakeLockLeasePolicy.LEASE_MS)
    }
}
