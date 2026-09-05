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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchIndexJournal
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

    @Test
    fun aFreshInstallHasNothingToSettleAndProceeds() {
        assertNull(ChatSetReplacementCoordinator.settleOrRefuse(context))
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
    fun theSourceGenerationStartsAtZeroAndIsMonotonic() {
        assertEquals(0L, ChatSetReplacementCoordinator.sourceGeneration(context))
        ChatSetReplacementCoordinator.onAuthoritativeChatSetReplaced(context, emptySet())
        ChatSetReplacementCoordinator.onAuthoritativeChatSetReplaced(context, emptySet())
        assertEquals(2L, ChatSetReplacementCoordinator.sourceGeneration(context))
    }
}
