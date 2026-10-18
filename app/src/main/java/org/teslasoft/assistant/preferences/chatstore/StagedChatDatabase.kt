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

package org.teslasoft.assistant.preferences.chatstore

import android.content.Context
import java.io.File
import java.util.UUID
import org.teslasoft.assistant.preferences.memory.DatabaseKeys

/**
 * A chats.db built beside the live one (chat-storage-and-backup-plan.md §4.2):
 * migration and restore fill one, it is trusted only after [validate], and it
 * is either published or discarded. A staged database never holds the only
 * copy of anything, so discarding it is always safe.
 *
 * Publishing a validated stage as the live chats.db is not done here; that
 * goes through the app's crash-safe database restore path (plan §5, PR 4).
 */
class StagedChatDatabase private constructor(
    context: Context,
    val databaseName: String,
    val store: ChatStore
) {
    private val app = context.applicationContext

    val file: File get() = app.getDatabasePath(databaseName)

    /** SQLite integrity and every row's parent present. */
    fun validate(): Boolean = try {
        store.integrityOk()
    } catch (_: Exception) {
        false
    }

    /** Close the connection so the file and its sidecars are complete on disk. */
    fun close() {
        try { store.close() } catch (_: Exception) { }
    }

    /** Close and delete the staged file and its sidecars. True when nothing is left. */
    fun discard(): Boolean {
        close()
        return deleteFiles(app, databaseName)
    }

    companion object {
        const val NAME_PREFIX = "chats-stage-"
        private val PURPOSE = Regex("[a-z0-9]{1,32}")
        private val SIDECARS = listOf("", "-wal", "-shm", "-journal")

        /**
         * The chats.db key, or null when it cannot be read. A key is minted
         * only when no live chats.db exists, so a stage built now opens under
         * the same key the live database will use.
         */
        fun chatsKey(context: Context): ByteArray? {
            val app = context.applicationContext
            val liveExists = app.getDatabasePath(ChatStore.DATABASE_NAME).exists()
            return DatabaseKeys.getOrCreate(app, DatabaseKeys.KEY_CHATS, liveExists)
        }

        /**
         * Create a new, empty staged database for [purpose] (lowercase letters
         * and digits). Never opens or reuses an existing file.
         */
        fun create(context: Context, purpose: String, password: ByteArray): StagedChatDatabase {
            require(PURPOSE.matches(purpose)) { "invalid stage purpose" }
            val app = context.applicationContext
            val name = "$NAME_PREFIX$purpose-${UUID.randomUUID()}.db"
            check(SIDECARS.none { app.getDatabasePath(name + it).exists() }) { "stage name in use" }
            val store = ChatStore.open(app, password, ChatStore.CloseOnCorruption, name)
            return StagedChatDatabase(app, name, store)
        }

        /** Names of staged databases left on disk (for example after an interruption). */
        fun leftovers(context: Context): List<String> {
            val dir = context.applicationContext.getDatabasePath(ChatStore.DATABASE_NAME).parentFile
                ?: return emptyList()
            return dir.list()
                ?.filter { it.startsWith(NAME_PREFIX) && it.endsWith(".db") }
                ?.sorted()
                ?: emptyList()
        }

        /** Delete a leftover staged database by name. True when nothing is left. */
        fun discardLeftover(context: Context, databaseName: String): Boolean {
            require(databaseName.startsWith(NAME_PREFIX) && databaseName.endsWith(".db"))
            return deleteFiles(context.applicationContext, databaseName)
        }

        private fun deleteFiles(app: Context, databaseName: String): Boolean =
            SIDECARS.all { suffix ->
                val f = app.getDatabasePath(databaseName + suffix)
                !f.exists() || f.delete()
            }
    }
}
