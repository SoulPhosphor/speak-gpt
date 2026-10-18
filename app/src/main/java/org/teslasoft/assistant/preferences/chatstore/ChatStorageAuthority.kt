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
import androidx.annotation.VisibleForTesting

/**
 * Which storage holds the authoritative chats: the encrypted preference files
 * or chats.db (chat-storage-and-backup-plan.md §4.2). One durable value, read
 * once per process.
 *
 * Until the activation step (plan §5, PR 9) nothing in the app sets it, so
 * every installation reads [Authority.LEGACY]. Tests set it to exercise the
 * database paths.
 *
 * A stored value this build does not recognize is [Authority.UNREADABLE], never
 * a guess: after activation, guessing "legacy" could show older or missing
 * chats as if they were current.
 */
object ChatStorageAuthority {

    enum class Authority { LEGACY, DATABASE, UNREADABLE }

    private const val PREFS = "chat_storage_authority"
    private const val KEY = "authority"
    private const val VALUE_LEGACY = "legacy"
    private const val VALUE_DATABASE = "database"

    @Volatile private var cached: Authority? = null

    fun current(context: Context): Authority =
        cached ?: synchronized(this) {
            cached ?: read(context).also { cached = it }
        }

    private fun read(context: Context): Authority {
        val stored = try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
        } catch (_: Exception) {
            return Authority.UNREADABLE
        }
        return when (stored) {
            null, VALUE_LEGACY -> Authority.LEGACY
            VALUE_DATABASE -> Authority.DATABASE
            else -> Authority.UNREADABLE
        }
    }

    /** Durably store [authority] (LEGACY or DATABASE) for tests. */
    @VisibleForTesting
    internal fun setForTest(context: Context, authority: Authority): Boolean {
        val value = when (authority) {
            Authority.LEGACY -> VALUE_LEGACY
            Authority.DATABASE -> VALUE_DATABASE
            Authority.UNREADABLE -> return false
        }
        return synchronized(this) {
            val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, value).commit()
            cached = null
            saved
        }
    }

    /** Forget the value read this process, as a new process would. */
    @VisibleForTesting
    internal fun forgetCachedForTest() {
        cached = null
    }

    /** Remove the stored value for tests, returning to the default. */
    @VisibleForTesting
    internal fun clearForTest(context: Context): Boolean = synchronized(this) {
        val cleared = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).commit()
        cached = null
        cleared
    }
}
