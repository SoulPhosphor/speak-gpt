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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryKeyFileTest {

    @Test
    fun roundTripsTheSecret() {
        val secret = PackageCrypto.newRecoverySecret()
        val parsed = RecoveryKeyFile.parse(RecoveryKeyFile.serialize(secret))
        assertTrue(parsed is RecoveryKeyFile.ParseResult.Ok)
        assertArrayEquals(secret, (parsed as RecoveryKeyFile.ParseResult.Ok).secret)
    }

    @Test
    fun neverContainsAPasswordField() {
        // Owner ruling: the Recovery Key file must never contain the password.
        val json = RecoveryKeyFile.serialize(PackageCrypto.newRecoverySecret())
        assertFalse(json.contains(RecoveryKeyFile.FORBIDDEN_PASSWORD_FIELD))
        assertFalse(json.contains("wrapped_rs"))
        assertFalse(json.contains("salt"))
        assertFalse(json.contains("iterations"))
    }

    @Test
    fun tamperedFingerprintIsRejected() {
        val secret = PackageCrypto.newRecoverySecret()
        val json = RecoveryKeyFile.serialize(secret)
            .replace(Regex("\"key_fingerprint\"\\s*:\\s*\"[0-9a-f]+\""), "\"key_fingerprint\":\"deadbeefdeadbeef\"")
        assertTrue(RecoveryKeyFile.parse(json) is RecoveryKeyFile.ParseResult.Invalid)
    }

    @Test
    fun foreignOrGarbageIsRejected() {
        assertTrue(RecoveryKeyFile.parse("not json") is RecoveryKeyFile.ParseResult.Invalid)
        assertTrue(RecoveryKeyFile.parse("""{"format":"something-else"}""") is RecoveryKeyFile.ParseResult.Invalid)
        assertTrue(RecoveryKeyFile.parse("""{"format":"recovery-key-v1","recovery_secret":"AAAA"}""")
            is RecoveryKeyFile.ParseResult.Invalid)
    }

    @Test
    fun theFileAndTheCodeEncodeTheSameSecret() {
        // The two representations are interchangeable: a secret saved to a file
        // decodes to the same bytes the Recovery Code decodes to.
        val secret = PackageCrypto.newRecoverySecret()
        val fromFile = (RecoveryKeyFile.parse(RecoveryKeyFile.serialize(secret)) as RecoveryKeyFile.ParseResult.Ok).secret
        val fromCode = (RecoveryCode.decode(RecoveryCode.encode(secret)) as RecoveryCode.DecodeResult.Ok).secret
        assertArrayEquals(fromFile, fromCode)
    }
}
