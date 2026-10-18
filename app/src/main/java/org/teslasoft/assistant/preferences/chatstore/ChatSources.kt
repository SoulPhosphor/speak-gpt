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
import org.teslasoft.assistant.preferences.backup.RecoveryBackupReceipt

/** The [ChatSource] that holds the authoritative chats, per [ChatStorageAuthority]. */
object ChatSources {

    /** The source, and how a backup captured from it is described in its receipt. */
    class Authoritative(val source: ChatSource, val receiptSource: RecoveryBackupReceipt.Source)

    /**
     * The authoritative source, or null when it cannot be used. Until the
     * database becomes authoritative (plan stage 9) this is always the legacy
     * storage; opening chats.db for readers arrives with the compatibility
     * layer (plan stage 6), so a database or unreadable authority yields null
     * and callers refuse rather than read the wrong storage.
     */
    fun authoritative(context: Context): Authoritative? =
        when (ChatStorageAuthority.current(context)) {
            ChatStorageAuthority.Authority.LEGACY -> Authoritative(
                LegacyChatSource(context),
                RecoveryBackupReceipt.Source(RecoveryBackupReceipt.STORAGE_LEGACY)
            )
            ChatStorageAuthority.Authority.DATABASE,
            ChatStorageAuthority.Authority.UNREADABLE -> null
        }
}
