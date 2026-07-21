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
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageDb

/**
 * The backend of the "Check Database Integrity" button (Build Phase 2 item 8;
 * owner directive July 21 2026: the button must be functional in Phase 2, never
 * present-and-inert). Runs `PRAGMA integrity_check` on the three DATABASES —
 * memory, lorebook, user image — and returns a typed result per database.
 *
 * Chats are NOT a database (they are EncryptedSharedPreferences files) and are
 * deliberately absent here — they are checked by the app's existing Round-4
 * chat-storage health/lock machinery, which the helper text tells the user.
 *
 * Phase 2 scope is DETECTION ONLY. A [Status.DAMAGED] result is reported; the
 * degraded flag, the repair/revert dialogs and the actual repair are Build
 * Phase 3. [Status.UNAVAILABLE] (a locked/absent key, a not-yet-created store,
 * or an open failure) is a distinct state and must never be presented as
 * corruption — it is a health signal, not confirmed damage.
 *
 * This performs disk/Keystore work and must be called off the main thread.
 */
object DatabaseHealthChecker {

    enum class Status { OK, DAMAGED, UNAVAILABLE }

    data class Result(val type: BackupType, val status: Status, val detail: String?)

    fun checkAll(context: Context): List<Result> = listOf(
        checkMemory(context),
        checkLorebook(context),
        checkUserImage(context)
    )

    fun checkMemory(context: Context): Result {
        // Not yet provisioned = there is no store to damage; report OK rather
        // than provision one as a side effect of a health check.
        if (!MemoryStore.isProvisioned(context)) return Result(BackupType.MEMORY, Status.OK, null)
        return try {
            fromProblem(BackupType.MEMORY, MemoryStore.getInstance(context).integrityCheck())
        } catch (e: Exception) {
            // Locked key / open failure: a health signal, not confirmed damage.
            Result(BackupType.MEMORY, Status.UNAVAILABLE, e.message ?: e.javaClass.simpleName)
        }
    }

    fun checkLorebook(context: Context): Result = try {
        fromProblem(BackupType.LOREBOOK, LoreBookStore.getInstance(context).integrityCheck())
    } catch (e: Exception) {
        Result(BackupType.LOREBOOK, Status.UNAVAILABLE, e.message ?: e.javaClass.simpleName)
    }

    fun checkUserImage(context: Context): Result = try {
        fromProblem(BackupType.USER_IMAGE, ProfileImageDb.getInstance(context).integrityCheck())
    } catch (e: Exception) {
        Result(BackupType.USER_IMAGE, Status.UNAVAILABLE, e.message ?: e.javaClass.simpleName)
    }

    private fun fromProblem(type: BackupType, problem: String?): Result =
        if (problem == null) Result(type, Status.OK, null)
        else Result(type, Status.DAMAGED, problem)
}
