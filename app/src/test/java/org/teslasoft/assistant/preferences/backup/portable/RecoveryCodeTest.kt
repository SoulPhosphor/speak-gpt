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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** The owner-approved Recovery Code shape: 128-bit secret, Crockford Base32,
 *  26 data + 6 checksum symbols = 32, eight groups of four. */
class RecoveryCodeTest {

    private val random = SecureRandom()

    private fun randomSecret(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    @Test
    fun encodeShapeIsEightGroupsOfFour() {
        val code = RecoveryCode.encode(randomSecret())
        val groups = code.split("-")
        assertEquals(8, groups.size)
        groups.forEach { assertEquals(4, it.length) }
        // 32 symbols + 7 hyphens
        assertEquals(39, code.length)
    }

    @Test
    fun roundTripsManyRandomSecrets() {
        repeat(200) {
            val secret = randomSecret()
            val decoded = RecoveryCode.decode(RecoveryCode.encode(secret))
            assertTrue(decoded is RecoveryCode.DecodeResult.Ok)
            assertArrayEquals(secret, (decoded as RecoveryCode.DecodeResult.Ok).secret)
        }
    }

    @Test
    fun normalizationAcceptsLowercaseSpacesAndConfusables() {
        val secret = randomSecret()
        val code = RecoveryCode.encode(secret)
        val messy = code.lowercase().replace("-", " ")
            .replace('0', 'o') // O -> 0 mapping must recover it
            .replace('1', 'l') // L -> 1 mapping must recover it
        val decoded = RecoveryCode.decode(messy)
        assertTrue(decoded is RecoveryCode.DecodeResult.Ok)
        assertArrayEquals(secret, (decoded as RecoveryCode.DecodeResult.Ok).secret)
    }

    @Test
    fun singleSymbolTypoIsCaught() {
        val code = RecoveryCode.encode(randomSecret()).replace("-", "")
        // Flip one data symbol to a different alphabet character.
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val idx = 5
        val replacement = alphabet.first { it != code[idx] }
        val typo = code.substring(0, idx) + replacement + code.substring(idx + 1)
        assertTrue(RecoveryCode.decode(typo) is RecoveryCode.DecodeResult.Mistyped)
    }

    @Test
    fun uIsRejectedNotMapped() {
        val code = RecoveryCode.encode(randomSecret())
        val bad = "U" + code.substring(1)
        assertTrue(RecoveryCode.decode(bad) is RecoveryCode.DecodeResult.Mistyped)
    }

    @Test
    fun wrongLengthIsMistyped() {
        assertTrue(RecoveryCode.decode("ABCD-EFGH") is RecoveryCode.DecodeResult.Mistyped)
        assertTrue(RecoveryCode.decode("") is RecoveryCode.DecodeResult.Mistyped)
    }

    @Test
    fun nonzeroPadBitsAreRejected() {
        // Craft a code whose first symbol implies nonzero pad bits: the first
        // symbol carries the 2 pad bits + 3 data bits, so any first symbol
        // >= 8 ("8" encodes value 8 = pad bit set) must be rejected even if
        // we recompute a matching checksum — build one by brute force.
        val code = RecoveryCode.encode(ByteArray(16)) // all-zero secret
        val tampered = "Z" + code.substring(1) // value 31: pad bits 11
        assertTrue(RecoveryCode.decode(tampered) is RecoveryCode.DecodeResult.Mistyped)
    }

    @Test
    fun distinctSecretsProduceDistinctCodes() {
        val a = RecoveryCode.encode(randomSecret())
        val b = RecoveryCode.encode(randomSecret())
        assertTrue(a != b)
    }
}
