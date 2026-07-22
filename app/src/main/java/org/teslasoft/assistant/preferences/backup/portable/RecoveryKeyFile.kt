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

import org.json.JSONObject
import java.util.Base64

/**
 * The saveable Recovery Key FILE ({Brand}-Recovery-Key.json) — one of the two
 * representations of the Recovery Secret (the typeable Recovery Code is the
 * other; they encode the SAME 16-byte secret).
 *
 * Owner ruling (July 22 2026): the file contains ONLY what is needed to
 * identify and reconstruct the Recovery Key representation — the secret itself
 * and a non-secret fingerprint to identify which key it is — and it must NEVER
 * contain the optional password (the password is a separate, independent
 * unlock route wrapped only into package headers, never written to this file).
 *
 * Pure codec, unit-tested. This is a plaintext file that IS a recovery secret:
 * the UI wording warns the user to store it safely and apart from the backups,
 * exactly as it warns about the Recovery Code.
 */
object RecoveryKeyFile {

    const val FORMAT = "recovery-key-v1"

    /** The field name a password would occupy IF this were mis-implemented —
     *  named here only so the test can assert it is categorically absent. */
    const val FORBIDDEN_PASSWORD_FIELD = "password"

    /** Serialize the Recovery Secret to the file's JSON text. */
    fun serialize(recoverySecret: ByteArray): String {
        require(recoverySecret.size == RecoveryCode.SECRET_BYTES) { "Recovery Secret must be 16 bytes" }
        return JSONObject()
            .put("format", FORMAT)
            .put("key_fingerprint", PackageCrypto.fingerprint(recoverySecret).toHex())
            .put("recovery_secret", Base64.getEncoder().encodeToString(recoverySecret))
            .toString(2)
    }

    sealed class ParseResult {
        data class Ok(val secret: ByteArray) : ParseResult()

        /** Wrong format, missing/garbled secret, or a fingerprint that does not
         *  match the secret it accompanies (a corrupted or foreign file). */
        object Invalid : ParseResult()
    }

    /** Parse a saved Recovery Key file back to the secret, verifying the
     *  fingerprint matches the secret it carries. */
    fun parse(jsonText: String): ParseResult {
        return try {
            val obj = JSONObject(jsonText)
            if (obj.optString("format") != FORMAT) return ParseResult.Invalid
            val secret = Base64.getDecoder().decode(obj.optString("recovery_secret", ""))
            if (secret.size != RecoveryCode.SECRET_BYTES) return ParseResult.Invalid
            val statedFingerprint = obj.optString("key_fingerprint", "")
            if (statedFingerprint != PackageCrypto.fingerprint(secret).toHex()) return ParseResult.Invalid
            ParseResult.Ok(secret)
        } catch (_: Exception) {
            ParseResult.Invalid
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
