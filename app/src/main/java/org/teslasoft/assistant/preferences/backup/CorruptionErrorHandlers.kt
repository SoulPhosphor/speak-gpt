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

/**
 * Corruption handlers for the three databases (Build Phase 3 item 6, §15.2c
 * mid-session path), passed into each SQLiteOpenHelper explicitly because the
 * DEFAULT handler both libraries fall back to DELETES the corrupt database
 * file — verified against sqlcipher-android 4.16.0's
 * DefaultDatabaseErrorHandler bytecode and true of the framework's since API
 * 1. That deletion is exactly what the preserve-the-original law (Build Phase
 * 3 item 2, §15.6 step 1) forbids: the damaged file is the user's data and is
 * quarantined, never destroyed.
 *
 * On corruption these handlers:
 *  1. set the store's degraded flag (a corruption exception IS confirmed
 *     damage — the flag's one legitimate mid-session setter), which
 *     transition-logs to the Error Log and makes the store singletons refuse
 *     further opens;
 *  2. close the broken connection so no further SQL runs against it;
 *  3. delete NOTHING. Quarantine + repair happen later, in
 *     [DatabaseRepairManager], under user control.
 *
 * ChatActivity notices the flag on its normal per-turn refresh and shows the
 * A2 banner (plus the distinct hands-free audio cue); no dialog is thrown from
 * here (§15.2c hard constraint — the store layer has no UI).
 */
object CorruptionErrorHandlers {

    /** Handler for the SQLCipher stores (memory + lorebook). */
    class Cipher(context: Context, private val type: BackupType) :
        net.zetetic.database.DatabaseErrorHandler {

        private val appContext = context.applicationContext

        override fun onCorruption(
            dbObj: net.zetetic.database.sqlcipher.SQLiteDatabase,
            exception: android.database.sqlite.SQLiteException?
        ) {
            DatabaseHealthState.markDegraded(
                appContext, type,
                exception?.javaClass?.simpleName ?: "SQLiteDatabaseCorruptException"
            )
            DatabaseHealthState.setPendingNotice(appContext, type, DatabaseHealthState.NOTICE_PROBLEM)
            // Close like the default handler would — but never delete the file.
            try { if (dbObj.isOpen) dbObj.close() } catch (_: Exception) { }
        }
    }

    /** Handler for the plain framework store (the user image catalog). It gets
     *  the same flag + banner but no feature-disable beyond the gallery
     *  (§15.16 item 5), and — critically — its file survives corruption
     *  instead of being deleted by the framework default. */
    class Plain(context: Context, private val type: BackupType) :
        android.database.DatabaseErrorHandler {

        private val appContext = context.applicationContext

        override fun onCorruption(dbObj: android.database.sqlite.SQLiteDatabase) {
            DatabaseHealthState.markDegraded(appContext, type, "SQLiteDatabaseCorruptException")
            DatabaseHealthState.setPendingNotice(appContext, type, DatabaseHealthState.NOTICE_PROBLEM)
            try { if (dbObj.isOpen) dbObj.close() } catch (_: Exception) { }
        }
    }
}
