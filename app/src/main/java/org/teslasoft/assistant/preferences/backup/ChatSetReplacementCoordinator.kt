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

import android.content.Context
import androidx.core.content.edit
import org.teslasoft.assistant.conversation.NewConversationCoordinator
import org.teslasoft.assistant.preferences.RenameJournal
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionCoordinator
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionJournalRead
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionJournalStore
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchIndexManager
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore

/**
 * The single boundary for "the authoritative chat set was replaced" (Phase 9.1).
 * [ChatRestoreManager] calls it; a future portable replace/import must reuse it
 * rather than scattering the same settle/rebase steps through an Activity.
 *
 * It owns two ordered halves of the replacement transaction that surround the
 * raw file swap the engine performs:
 *
 *  - BEFORE staging: [settleOrRefuse] blocks new chat reads/writes (the engine
 *    holds CHAT_LIST_LOCK and requires a restart) and refuses to start while any
 *    incompatible journal is still pending. It first SETTLES the recoverable
 *    ones by running their existing reconcilers — the same in-order,
 *    authority-checked, idempotent passes startup runs — then refuses with a
 *    typed reason if any is still pending or unreadable. It never silently
 *    clears an unexamined journal.
 *
 *  - AFTER the verified swap: [onAuthoritativeChatSetReplaced] stamps a new
 *    opaque source generation, invalidates the cached preference handles for the
 *    replaced names, and rebases every dependent store (Phase 9.3): Search is
 *    discarded and rebuilt from the new source, and the generated-image catalog
 *    is requeued for the restored chats while its rows, tombstones and locks are
 *    preserved.
 *
 * Search is intentionally NOT in the refuse set: it is derived and disposable,
 * so a pending Search update is subsumed by the post-swap rebuild rather than
 * blocking the restore.
 *
 * ENGINE-ONLY: reached only from [ChatRestoreManager], which itself has no
 * reachable caller until the approved restore UI exists.
 */
object ChatSetReplacementCoordinator {

    private const val HEALTH_FILE = "storage_health"
    private const val KEY_SOURCE_GENERATION = "chatset.source_generation"

    /** The recoverable state of the chat-deletion journal at refuse time. */
    enum class DeletionJournalState { SETTLED, PENDING, UNAVAILABLE }

    /** Why a chat-set replacement must not start yet. Each is a distinct,
     *  reportable cause; nothing collapses into a generic "busy". */
    enum class ReplacementBlock {
        /** A chat rename's memory re-point has not finished settling. */
        RENAME,

        /** A chat/folder deletion is still journaled and did not settle. */
        DELETION,

        /** The deletion journal is locked or unreadable, so it cannot be judged
         *  settled — the safe answer is to refuse, not to replace over it. */
        DELETION_UNAVAILABLE,

        /** A first-conversation commit is still journaled. */
        PENDING_CONVERSATION,

        /** A provisional (unsaved) conversation is still open; its chat-storage
         *  files would be swept by the wholesale replacement. */
        PROVISIONAL_SESSION;

        /** A short, cause-specific detail for the engine [ChatRestoreManager.Result]. */
        fun detail(): String = when (this) {
            RENAME -> "a chat rename is still being reconciled"
            DELETION -> "a chat deletion is still being finished"
            DELETION_UNAVAILABLE -> "the chat deletion journal is unavailable"
            PENDING_CONVERSATION -> "a new conversation's first save is still being finished"
            PROVISIONAL_SESSION -> "an unsaved new conversation is still open"
        }
    }

    /**
     * Pure refuse decision from the residual pending state after settling. The
     * checks run in a fixed order so a given mix of pending work always reports
     * the same, most-specific cause. Returns null when nothing blocks.
     */
    fun blockingReason(
        renamePending: Boolean,
        deletion: DeletionJournalState,
        pendingFirstCommit: Boolean,
        provisionalSession: Boolean
    ): ReplacementBlock? = when {
        renamePending -> ReplacementBlock.RENAME
        deletion == DeletionJournalState.UNAVAILABLE -> ReplacementBlock.DELETION_UNAVAILABLE
        deletion == DeletionJournalState.PENDING -> ReplacementBlock.DELETION
        pendingFirstCommit -> ReplacementBlock.PENDING_CONVERSATION
        provisionalSession -> ReplacementBlock.PROVISIONAL_SESSION
        else -> null
    }

    /**
     * Settle every recoverable pending operation, then decide whether the
     * replacement may proceed. Returns null to proceed, or the first blocking
     * cause. MUST run off the main thread (SQLCipher + chat-list reads).
     *
     * Settling runs the existing reconcilers — deliberately, so the pre-restore
     * state is coherent before its files are quarantined and replaced. They are
     * idempotent and defer (leave the journal pending) when the chat list is not
     * authoritative, which this method then reports as a refusal.
     */
    fun settleOrRefuse(context: Context): ReplacementBlock? {
        val appContext = context.applicationContext

        // Settle — best effort; each reconciler is idempotent and guards its own
        // authority. A throw here must not be read as "settled", so the residual
        // checks below decide, not these calls.
        try { RenameJournal.reconcile(appContext) } catch (_: Exception) { }
        try { ChatDeletionCoordinator.get(appContext).recover() } catch (_: Exception) { }
        try { NewConversationCoordinator(appContext).recoverPendingCommits() } catch (_: Exception) { }

        // Residual state.
        val renamePending = try { RenameJournal.hasPending(appContext) } catch (_: Exception) { true }
        val deletion = deletionState(appContext)
        val conversations = NewConversationCoordinator(appContext)
        val pendingFirstCommit = conversations.hasPendingFirstCommit()
        val provisionalSession = conversations.hasProvisionalSession()

        return blockingReason(renamePending, deletion, pendingFirstCommit, provisionalSession)
    }

    private fun deletionState(context: Context): DeletionJournalState =
        when (val read = ChatDeletionJournalStore.get(context).read()) {
            is ChatDeletionJournalRead.Available ->
                if (read.entries.isEmpty()) DeletionJournalState.SETTLED else DeletionJournalState.PENDING
            ChatDeletionJournalRead.Unavailable -> DeletionJournalState.UNAVAILABLE
        }

    /**
     * Run the post-swap half of the transaction once the live set is verified
     * (Phase 9.1 steps 9–11): stamp a new source generation, evict the cached
     * preference handles for the replaced names, and rebase the dependent
     * stores. [restoredChatIds] are the stored ids the archive brought in.
     *
     * Returns whether the rebase is DURABLE. The caller must NOT clear the
     * restore journal on false: the swap is verified, but a dependent store was
     * not rebased, so the journal is kept and the resume path retries the rebase
     * idempotently at the next start. This is what closes the stale-derived-store
     * hole — the Search rebuild test only checks generation/policy/locale, not
     * the restore source generation, so a Search index that survives a failed
     * discard would otherwise never be rebuilt and could serve pre-restore
     * results (plan step 12: clear the journal only after dependent invalidation
     * is durable).
     *
     * The source-generation stamp and the cache eviction are defense-in-depth
     * and do not gate. Search is the strict gate (a stale index shows WRONG
     * results). The generated-image catalog gates only when it is operable and
     * the requeue write actually failed; a catalog that cannot open here (absent,
     * locked, or — under a JVM test — without its native library) has no markers
     * to clear now and is handled by the post-restart maintenance, so it must
     * not block the restore forever.
     */
    fun onAuthoritativeChatSetReplaced(context: Context, restoredChatIds: Set<String>): Boolean {
        val appContext = context.applicationContext

        // Step 9: a new opaque source generation marks the replacement committed.
        runCatching { bumpSourceGeneration(appContext) }

        // Step 10: evict cached handles for every replaced name (the chat list
        // plus each restored chat's history and settings).
        runCatching { SecurePrefs.invalidateCache(replacedPrefNames(restoredChatIds)) }

        // Step 11: rebase dependent stores.
        // Search — derived: clear the stale journal and discard the index so the
        // next ensureReady rebuilds from the new source; no stale row survives.
        // A store can raise an Error (e.g. the SQLCipher native library missing),
        // so failure/throw both read as "not durable".
        val searchOk = runCatching {
            ChatSearchIndexManager.get(appContext).onAuthoritativeChatSetReplaced()
        }.getOrDefault(false)
        // Generated-image catalog — NOT derived: requeue the restored chats for a
        // backfill rescan; rows, tombstones and locks are preserved.
        val catalogOk = runCatching {
            val result = GeneratedImageCatalogStore.requeueBackfill(appContext, restoredChatIds)
            result.success || result.state != GeneratedImageCatalogStorageState.AVAILABLE
        }.getOrDefault(true)

        return searchOk && catalogOk
    }

    /** The current source generation (0 when never stamped). Opaque; only its
     *  change across a restore is meaningful. */
    fun sourceGeneration(context: Context): Long =
        health(context).getLong(KEY_SOURCE_GENERATION, 0L)

    private fun bumpSourceGeneration(context: Context): Long {
        val next = sourceGeneration(context) + 1
        health(context).edit(commit = true) { putLong(KEY_SOURCE_GENERATION, next) }
        return next
    }

    /** The SecurePrefs handle names replaced by a restore: the chat list, plus
     *  each restored chat's history and settings. */
    private fun replacedPrefNames(restoredChatIds: Set<String>): Set<String> {
        val names = LinkedHashSet<String>()
        names.add("chat_list")
        for (id in restoredChatIds) {
            names.add("chat_$id")
            names.add("settings.$id")
        }
        return names
    }

    private fun health(context: Context) =
        context.applicationContext.getSharedPreferences(HEALTH_FILE, Context.MODE_PRIVATE)
}
