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

/**
 * Pure policy for the hands-free CPU wake-lock lease. Kept Android-free so
 * the safety relationship and stale-callback rule can be pinned by JVM tests.
 */
internal object WakeLockLeasePolicy {
    const val LEASE_MS = 60 * 60 * 1000L
    const val RENEW_INTERVAL_MS = 30 * 60 * 1000L
    const val RETRY_INTERVAL_MS = 60 * 1000L

    fun shouldRenew(serviceRunning: Boolean, scheduledToken: Long, activeToken: Long): Boolean =
        serviceRunning && scheduledToken == activeToken
}

/** Actual service/wake-lock state sampled when Audio Health writes a trace. */
data class HandsFreeWakeLockDiagnostics(
    val serviceRunning: Boolean,
    val serviceAgeMs: Long,
    val wakeLockHeld: Boolean,
    val leaseAgeMs: Long
) {
    fun asLogFields(): String =
        "serviceRunning=$serviceRunning serviceAge=${serviceAgeMs}ms " +
                "wakeLockHeld=$wakeLockHeld leaseAge=${leaseAgeMs}ms"
}
