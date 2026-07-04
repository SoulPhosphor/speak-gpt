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

package org.teslasoft.assistant.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.teslasoft.assistant.preferences.memory.MemoryLog

/**
 * Encrypted-at-rest replacement for the chat-content SharedPreferences files
 * (chat list, per-chat message history, per-chat settings — the owner asked
 * for chat history to be encrypted like the memory store is).
 *
 * Every call site that used `context.getSharedPreferences(name, MODE_PRIVATE)`
 * for those files now calls [get] with the same logical name. Under the hood
 * the data lives in an EncryptedSharedPreferences file named "enc.<name>"
 * (a DIFFERENT file — mixing encrypted entries into the old plaintext XML
 * would corrupt both). On first access the old plaintext file's entries are
 * copied over, verified, and only then cleared — the plaintext file is left
 * holding a single migration marker.
 *
 * Failure rules, in the spirit of the ChatPreferences never-wipe invariant:
 *  - Migration copies only keys ABSENT from the encrypted file (idempotent:
 *    a re-run after a half-finished migration can't overwrite newer data),
 *    and the plaintext original is cleared only after the copy verifies.
 *  - If the Keystore/EncryptedSharedPreferences layer is unavailable, [get]
 *    falls back to the plaintext file and logs loudly. Before migration that
 *    is exactly the old behavior; after migration the fallback file is empty
 *    (data is safe in the encrypted file and reappears when the Keystore
 *    recovers) — chats LOOK missing rather than BEING lost, which is the
 *    least-bad failure available. Nothing is ever deleted on this path.
 */
object SecurePrefs {

    private const val ENC_PREFIX = "enc."
    private const val MIGRATED_MARKER = "__migrated_to_encrypted"

    private val cache = HashMap<String, SharedPreferences>()
    private var masterKey: MasterKey? = null

    @Synchronized
    fun get(context: Context, name: String): SharedPreferences {
        cache[name]?.let { return it }
        val appContext = context.applicationContext

        val encrypted = try {
            createEncrypted(appContext, ENC_PREFIX + name)
        } catch (e: Exception) {
            MemoryLog.log(appContext, "SecurePrefs", "error",
                "Encrypted preferences unavailable for '$name' (${e.message}); using plaintext fallback. " +
                    "If data appears missing it is still in the encrypted file and returns when the Keystore recovers."
            )
            val plain = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            cache[name] = plain
            return plain
        }

        migrateIfNeeded(appContext, name, encrypted)
        cache[name] = encrypted
        return encrypted
    }

    private fun createEncrypted(context: Context, fileName: String): SharedPreferences {
        if (masterKey == null) {
            masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey!!,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun migrateIfNeeded(context: Context, name: String, encrypted: SharedPreferences) {
        try {
            val plain = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = plain.all.filterKeys { it != MIGRATED_MARKER }
            if (entries.isEmpty()) return

            val alreadyPresent = encrypted.all.keys
            encrypted.edit(commit = true) {
                for ((key, value) in entries) {
                    if (key in alreadyPresent) continue // never clobber newer encrypted data
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                    }
                }
            }

            // Destroy the plaintext copy only once every key is readable from
            // the encrypted side.
            if (encrypted.all.keys.containsAll(entries.keys)) {
                plain.edit(commit = true) {
                    clear()
                    putBoolean(MIGRATED_MARKER, true)
                }
                MemoryLog.log(context, "SecurePrefs", "info",
                    "Migrated ${entries.size} entr${if (entries.size == 1) "y" else "ies"} of '$name' to encrypted storage."
                )
            } else {
                MemoryLog.log(context, "SecurePrefs", "error",
                    "Migration of '$name' did not verify; plaintext kept for retry on next access."
                )
            }
        } catch (e: Exception) {
            // Keep the plaintext data untouched; a failed migration retries on
            // the next access.
            MemoryLog.log(context, "SecurePrefs", "error", "Migration of '$name' failed: ${e.message}")
        }
    }
}
