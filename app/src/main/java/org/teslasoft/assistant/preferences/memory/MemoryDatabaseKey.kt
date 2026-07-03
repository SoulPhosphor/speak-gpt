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

package org.teslasoft.assistant.preferences.memory

import android.content.Context
import org.teslasoft.assistant.preferences.EncryptedPreferences
import java.security.SecureRandom

/**
 * SQLCipher passphrase for companion_memory.db: a random 32-byte key minted on
 * first use and kept in EncryptedPreferences (androidx security-crypto, master
 * key in the Android Keystore). There is deliberately no user-facing password —
 * a forgotten password would orphan the store; the plain-JSON export is the
 * recovery path.
 *
 * Safety rule: if the database file already exists but the stored key can't be
 * read (Keystore hiccup, cleared credential storage), we must NOT mint a fresh
 * key — opening with a new key would present as a corrupt database and a
 * retry-with-the-old-key becomes impossible once it's overwritten. In that
 * state [getOrCreate] returns null and callers surface "store locked" instead
 * of destroying the one copy of the key that might come back next launch.
 */
object MemoryDatabaseKey {

    private const val PREF_FILE = "memory_db"
    private const val PREF_KEY = "db_key_hex"

    fun getOrCreate(context: Context, databaseExists: Boolean): ByteArray? {
        val existing = EncryptedPreferences.getEncryptedPreference(context, PREF_FILE, PREF_KEY)
        if (existing.isNotEmpty()) return decodeHex(existing)
        if (databaseExists) return null

        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        val hex = encodeHex(key)
        EncryptedPreferences.setEncryptedPreference(context, PREF_FILE, PREF_KEY, hex)

        // Verify the write round-trips before any database is created with this
        // key; a silent persist failure here would make the store unopenable on
        // the next launch.
        val readBack = EncryptedPreferences.getEncryptedPreference(context, PREF_FILE, PREF_KEY)
        return if (readBack == hex) key else null
    }

    private fun encodeHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun decodeHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) + hex[it * 2 + 1].digitToInt(16)).toByte() }
}
