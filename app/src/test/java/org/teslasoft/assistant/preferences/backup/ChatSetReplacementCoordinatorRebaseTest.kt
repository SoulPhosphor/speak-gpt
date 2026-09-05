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

package org.teslasoft.assistant.preferences.backup

import android.app.Application
import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.conversation.NewConversationCoordinator
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchIndexJournal
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchIndexManager
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The I/O behaviour of the Phase 9.1 coordinator: the settle-or-refuse gate and
 * the post-swap dependent-store rebase (Phase 9.3). The pure refuse decision is
 * covered separately; this exercises the real methods against a Robolectric
 * context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28, 36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatSetReplacementCoordinatorRebaseTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetProcessStatics() {
        // These singletons and caches live for the JVM, not the test method, and
        // Robolectric hands each test a fresh application/filesystem. Reset them
        // so each test starts from a genuinely clean state instead of inheriting
        // a prior test's cached handles or a manager bound to a stale context.
        SecurePrefs.clearCacheForTest()
        ChatSearchIndexManager.resetForTest()
        ChatSearchStore.discard(context)
    }

    @Test
    fun aFreshInstallHasNothingToSettleAndProceeds() {
        assertNull(ChatSetReplacementCoordinator.settleOrRefuse(context))
    }

    @Test
    fun pendingBlockNowCatchesAProvisionalWithoutSettling() {
        // The in-lock re-check (Phase 9.1 TOCTOU closure) is read-only: it must
        // report a provisional session that appeared after the initial settle,
        // without running any reconciler.
        assertNull(ChatSetReplacementCoordinator.pendingBlockNow(context))
        SecurePrefs.get(context, "pending_startup_conversation").edit()
            .putString("id", "late-provisional")
            .putString("name", "_autoname_1")
            .commit()
        assertEquals(
            ChatSetReplacementCoordinator.ReplacementBlock.PROVISIONAL_SESSION,
            ChatSetReplacementCoordinator.pendingBlockNow(context)
        )
    }

    @Test
    fun createPendingConversationBlocksWhileTheChatListLockIsHeld() {
        // Phase 9.1 Part B: provisional creation now takes CHAT_LIST_LOCK, so it
        // cannot write a provisional's chat-storage files while a restore holds
        // the lock and re-checks. Prove it: hold the lock, and a creation on
        // another thread must not complete until the lock is released.
        val coordinator = NewConversationCoordinator(context)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                started.countDown()
                // createPendingConversation's first act is to take CHAT_LIST_LOCK,
                // so a fixed request blocks immediately with no pre-lock work.
                coordinator.createPendingConversation(
                    NewConversationCoordinator.StartRequest("_toctou_test")
                )
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                finished.countDown()
            }
        }

        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            worker.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            // While the lock is held, the creation cannot finish.
            assertFalse(
                "createPendingConversation must block on CHAT_LIST_LOCK",
                finished.await(400, TimeUnit.MILLISECONDS)
            )
        }

        // Released — it completes cleanly.
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNull("the blocked creation must not have thrown", error.get())
        worker.join(TimeUnit.SECONDS.toMillis(5))
    }

    @Test
    fun anOpenProvisionalSessionRefusesTheReplacement() {
        // Seed the retained startup blank-session pointer: an unsaved new
        // conversation is open, so the wholesale replacement must refuse rather
        // than sweep its chat-storage files.
        SecurePrefs.get(context, "pending_startup_conversation").edit()
            .putString("id", "provisional-1")
            .putString("name", "_autoname_1")
            .commit()

        assertEquals(
            ChatSetReplacementCoordinator.ReplacementBlock.PROVISIONAL_SESSION,
            ChatSetReplacementCoordinator.settleOrRefuse(context)
        )
    }

    @Test
    fun rebaseClearsSearchDiscardsTheIndexAndBumpsTheGeneration() {
        // A stale Search dirty token and an index database exist before restore.
        val journal = ChatSearchIndexJournal.get(context)
        assertTrue(journal.record("old-chat", "old-rev"))
        val db = context.getDatabasePath("chat_search.db")
        db.parentFile?.mkdirs()
        db.writeBytes("stale-index".toByteArray())
        File(db.path + "-wal").writeBytes("wal".toByteArray())

        val before = ChatSetReplacementCoordinator.sourceGeneration(context)

        ChatSetReplacementCoordinator.onAuthoritativeChatSetReplaced(context, setOf("n1", "n2"))

        // Search journal cleared, index discarded, source generation advanced.
        assertTrue("the pre-restore dirty journal must be cleared", journal.entries().isEmpty())
        assertFalse("the derived index must be discarded", db.exists())
        assertFalse(File(db.path + "-wal").exists())
        assertEquals(before + 1, ChatSetReplacementCoordinator.sourceGeneration(context))
    }

    @Test
    fun aFailedSearchDiscardReportsTheRebaseNotDurable() {
        // Force ChatSearchStore.discard to fail: the index path is a non-empty
        // directory, so File.delete() returns false and the derived index is NOT
        // discarded. The rebase must report not-durable so the caller keeps the
        // restore journal instead of leaving a stale Search index behind.
        val db = context.getDatabasePath("chat_search.db")
        db.parentFile?.mkdirs()
        db.mkdir()
        File(db, "child").writeBytes("x".toByteArray())

        val durable = ChatSetReplacementCoordinator.onAuthoritativeChatSetReplaced(context, setOf("n1"))

        assertFalse("a Search index that could not be discarded is not a durable rebase", durable)
    }

    @Test
    fun aCleanRebaseReportsDurable() {
        assertTrue(ChatSetReplacementCoordinator.onAuthoritativeChatSetReplaced(context, setOf("n1")))
    }

    @Test
    fun theSourceGenerationStartsAtZeroAndIsMonotonic() {
        assertEquals(0L, ChatSetReplacementCoordinator.sourceGeneration(context))
        ChatSetReplacementCoordinator.onAuthoritativeChatSetReplaced(context, emptySet())
        ChatSetReplacementCoordinator.onAuthoritativeChatSetReplaced(context, emptySet())
        assertEquals(2L, ChatSetReplacementCoordinator.sourceGeneration(context))
    }
}
