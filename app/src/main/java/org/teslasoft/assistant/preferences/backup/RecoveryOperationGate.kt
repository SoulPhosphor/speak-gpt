/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One process-wide serialization boundary for operations that capture or
 * replace recovery-owned state. The lock is deliberately re-entrant: an
 * automatic-backup controller may call the portable writer, and a direct
 * restore may delegate its final publication to another recovery component.
 *
 * A process death releases this in-memory lock. Durable restore journals are
 * the cross-process authority; startup acquires this same gate while settling
 * them before ordinary backup/replace work may begin.
 */
object RecoveryOperationGate {
    private val lock = ReentrantLock(true)

    fun <T> runExclusive(operation: () -> T): T = lock.withLock(operation)
}
