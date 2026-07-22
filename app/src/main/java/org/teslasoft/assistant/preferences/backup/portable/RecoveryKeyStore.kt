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
 * The device's WORKING COPY of the Recovery Secret, its explicit setup state,
 * and the static password blob (architecture A-prime). This copy exists only
 * so automatic protected backups run without asking the user — it is a
 * convenience cache, never the sole recovery method.
 *
 * Storage: the existing Keystore-wrapped [EncryptedPreferences], file
 * "recovery_keys". A Keystore outage (this codebase's Round-4 territory) is
 * surfaced as a distinct UNAVAILABLE state on every accessor — callers MUST
 * treat it as a visible failure (never silence, never an unencrypted
 * fallback).
 *
 * SETUP STATE (owner ruling, July 22 2026): storing the secret is NOT the same
 * as configuring it. A freshly generated secret is UNCONFIRMED and must not
 * permit protected package creation (manual or automatic) until the user
 * re-enters the complete Recovery Code and the CONFIRMED marker is durably
 * written. A missing marker on an existing (e.g. development) secret is
 * UNCONFIRMED, never implicitly confirmed. Adding a password does NOT confirm.
 */
object RecoveryKeyStore {

    private const val FILE = "recovery_keys"
    private const val KEY_SECRET_B64 = "recovery_secret_b64"
    private const val KEY_CONFIRMED = "setup_confirmed"
    private const val KEY_PASSWORD_BLOB_JSON = "password_blob_json"
    private const val CONFIRMED_VALUE = "1"

    // ----- secret ------------------------------------------------------------

    sealed class SecretResult {
        data class Ok(val secret: ByteArray) : SecretResult()
        object NotSet : SecretResult()
        object Unavailable : SecretResult()
    }

    fun getSecret(context: Context): SecretResult {
        val b64 = EncryptedPreferences.getEncryptedPreferenceOrNull(context, FILE, KEY_SECRET_B64)
            ?: return SecretResult.Unavailable
        if (b64.isEmpty()) return SecretResult.NotSet
        return try {
            SecretResult.Ok(Base64.getDecoder().decode(b64))
        } catch (_: Exception) {
            SecretResult.Unavailable
        }
    }

    /** Store a freshly generated secret. It is UNCONFIRMED: the CONFIRMED
     *  marker is cleared as part of the same operation, so a new secret can
     *  never inherit an old confirmation. Returns false on any storage
     *  failure (enabling protected backups must not proceed on false). */
    fun setSecret(context: Context, secret: ByteArray): Boolean {
        val secretOk = EncryptedPreferences.setEncryptedPreferenceCommit(
            context, FILE, KEY_SECRET_B64, Base64.getEncoder().encodeToString(secret)
        )
        val markerCleared = EncryptedPreferences.setEncryptedPreferenceCommit(context, FILE, KEY_CONFIRMED, "")
        return secretOk && markerCleared
    }

    // ----- setup state -------------------------------------------------------

    enum class SetupState { NOT_SET, UNCONFIRMED, CONFIRMED, UNAVAILABLE }

    fun getSetupState(context: Context): SetupState {
        val b64 = EncryptedPreferences.getEncryptedPreferenceOrNull(context, FILE, KEY_SECRET_B64)
            ?: return SetupState.UNAVAILABLE
        if (b64.isEmpty()) return SetupState.NOT_SET
        val marker = EncryptedPreferences.getEncryptedPreferenceOrNull(context, FILE, KEY_CONFIRMED)
            ?: return SetupState.UNAVAILABLE
        return if (marker == CONFIRMED_VALUE) SetupState.CONFIRMED else SetupState.UNCONFIRMED
    }

    /** Durably mark the setup Confirmed. Callers proceed ONLY if this returns
     *  true (owner ruling: proceed only if the durable write succeeds). */
    fun markConfirmed(context: Context): Boolean =
        EncryptedPreferences.setEncryptedPreferenceCommit(context, FILE, KEY_CONFIRMED, CONFIRMED_VALUE)

    // ----- password blob (typed: NotSet / Ok / Unavailable) ------------------

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

    sealed class PasswordBlobResult {
        object NotSet : PasswordBlobResult()
        data class Ok(val blob: StoredPasswordBlob) : PasswordBlobResult()

        /** Keystore outage OR malformed stored data — a protected backup must
         *  fail visibly here, never lose the password restore route silently. */
        object Unavailable : PasswordBlobResult()
    }

    fun getPasswordBlob(context: Context): PasswordBlobResult {
        val json = EncryptedPreferences.getEncryptedPreferenceOrNull(context, FILE, KEY_PASSWORD_BLOB_JSON)
            ?: return PasswordBlobResult.Unavailable
        if (json.isEmpty()) return PasswordBlobResult.NotSet
        return try {
            val obj = JSONObject(json)
            PasswordBlobResult.Ok(
                StoredPasswordBlob(
                    salt = Base64.getDecoder().decode(obj.getString("salt")),
                    iterations = obj.getInt("iterations"),
                    wrappedRecoverySecret = Base64.getDecoder().decode(obj.getString("wrapped_rs"))
                )
            )
        } catch (_: Exception) {
            PasswordBlobResult.Unavailable
        }
    }

    /** Store the static password blob. Computed at password set/change ONLY. */
    fun setPasswordBlob(context: Context, blob: StoredPasswordBlob): Boolean = try {
        val value = JSONObject()
            .put("salt", Base64.getEncoder().encodeToString(blob.salt))
            .put("iterations", blob.iterations)
            .put("wrapped_rs", Base64.getEncoder().encodeToString(blob.wrappedRecoverySecret))
            .toString()
        EncryptedPreferences.setEncryptedPreferenceCommit(context, FILE, KEY_PASSWORD_BLOB_JSON, value)
    } catch (_: Exception) {
        false
    }
}
