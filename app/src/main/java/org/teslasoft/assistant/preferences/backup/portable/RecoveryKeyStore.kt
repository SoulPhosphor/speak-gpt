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

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import org.json.JSONObject
import org.teslasoft.assistant.preferences.EncryptedPreferences
import java.util.Base64

/**
 * The device's WORKING COPY of the Recovery Secret and the static password
 * blob (architecture A-prime). This copy exists only so automatic protected
 * backups run without asking the user — it is a convenience cache, never the
 * sole recovery method (the user's saved Recovery Key file / Recovery Code /
 * password open every backup regardless of this device's fate).
 *
 * Storage: the existing Keystore-wrapped [EncryptedPreferences], file
 * "recovery_keys". A Keystore outage (this codebase's Round-4 territory) makes
 * every accessor return null / UNAVAILABLE — callers MUST surface that as a
 * visible backup failure (never silently stop, never fall back to an
 * unencrypted backup — owner rulings 10 and F4).
 *
 * The static password blob is stored WITH its KDF parameters so automatic
 * backups can byte-copy the complete slot into each package header without
 * ever running the KDF (the KDF runs only at password set/change and restore).
 */
object RecoveryKeyStore {

    private const val FILE = "recovery_keys"
    private const val KEY_SECRET_B64 = "recovery_secret_b64"
    private const val KEY_PASSWORD_BLOB_JSON = "password_blob_json"

    sealed class SecretResult {
        data class Ok(val secret: ByteArray) : SecretResult()
        object NotSet : SecretResult()

        /** Keystore/encrypted-prefs failure: a visible failure state, never
         *  silence and never an unencrypted fallback. */
        object Unavailable : SecretResult()
    }

    fun getSecret(context: Context): SecretResult {
        // getEncryptedPreferenceOrNull distinguishes a Keystore outage (null)
        // from a genuinely unset value ("") — the plain getter swallows the
        // outage into "", which would masquerade as "no key set".
        val b64 = EncryptedPreferences.getEncryptedPreferenceOrNull(context, FILE, KEY_SECRET_B64)
            ?: return SecretResult.Unavailable
        if (b64.isEmpty()) return SecretResult.NotSet
        return try {
            SecretResult.Ok(Base64.getDecoder().decode(b64))
        } catch (_: Exception) {
            SecretResult.Unavailable
        }
    }

    /** Store the working copy. Returns false on a storage failure (visible to
     *  the caller — enabling protected backups must not proceed on false). */
    fun setSecret(context: Context, secret: ByteArray): Boolean =
        EncryptedPreferences.setEncryptedPreferenceCommit(
            context, FILE, KEY_SECRET_B64, Base64.getEncoder().encodeToString(secret)
        )

    data class StoredPasswordBlob(
        val salt: ByteArray,
        val iterations: Int,
        val wrappedRecoverySecret: ByteArray
    ) {
        fun toFormatBlob(): PortablePackageFormat.PasswordBlob =
            PortablePackageFormat.PasswordBlob(
                algorithm = PackageCrypto.KDF_ALG,
                salt = salt,
                iterations = iterations,
                keyBytes = PackageCrypto.KDF_KEY_BYTES,
                wrappedRecoverySecret = wrappedRecoverySecret
            )
    }

    fun getPasswordBlob(context: Context): StoredPasswordBlob? = try {
        val json = EncryptedPreferences.getEncryptedPreferenceOrNull(context, FILE, KEY_PASSWORD_BLOB_JSON)
        if (json.isNullOrEmpty()) null else {
            val obj = JSONObject(json)
            StoredPasswordBlob(
                salt = Base64.getDecoder().decode(obj.getString("salt")),
                iterations = obj.getInt("iterations"),
                wrappedRecoverySecret = Base64.getDecoder().decode(obj.getString("wrapped_rs"))
            )
        }
    } catch (_: Exception) {
        null
    }

    /** Store (or with null: clear) the static password blob. Computed at
     *  password set/change ONLY. */
    fun setPasswordBlob(context: Context, blob: StoredPasswordBlob?): Boolean = try {
        val value = if (blob == null) "" else JSONObject()
            .put("salt", Base64.getEncoder().encodeToString(blob.salt))
            .put("iterations", blob.iterations)
            .put("wrapped_rs", Base64.getEncoder().encodeToString(blob.wrappedRecoverySecret))
            .toString()
        EncryptedPreferences.setEncryptedPreferenceCommit(context, FILE, KEY_PASSWORD_BLOB_JSON, value)
    } catch (_: Exception) {
        false
    }
}
