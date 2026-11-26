/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reader that must not wait (conversation recall) never blocks behind a recovery holding the index. */
class ConversationIndexAccessTest {

    @Test fun aReadThatMustNotWaitGivesWayWhileARecoveryHoldsTheIndexAndRunsAfterIt() {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recovery = Thread { ConversationIndexAccess.exclusive { holding.countDown(); release.await() } }
        recovery.start()
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertNull(ConversationIndexAccess.sharedIfFree { "read" })
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        } finally {
            release.countDown()
            recovery.join()
        }
        assertEquals("read", ConversationIndexAccess.sharedIfFree { "read" })
    }
}
