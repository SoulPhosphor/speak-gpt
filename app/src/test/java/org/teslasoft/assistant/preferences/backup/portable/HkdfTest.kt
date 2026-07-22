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
import org.junit.Assert.assertFalse
import org.junit.Test

/** HKDF-SHA256 pinned to the RFC 5869 test vectors (Appendix A.1 and A.3 —
 *  A.3 is the no-salt case, which is the recovery format's deliberate rule). */
class HkdfTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }

    @Test
    fun rfc5869CaseA1() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        val okm = Hkdf.expand(prk, info, 42)
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm
        )
    }

    @Test
    fun rfc5869CaseA3NoSalt() {
        // Zero-length salt = HashLen zero bytes — the recovery format's rule.
        val ikm = hex("0b".repeat(22))
        val okm = Hkdf.expand(Hkdf.extract(ByteArray(0), ikm), ByteArray(0), 42)
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            okm
        )
    }

    @Test
    fun domainSeparationProducesUnrelatedOutputs() {
        val ikm = ByteArray(16) { it.toByte() }
        val a = Hkdf.derive(ikm, "psbk/v1/key-id", 32)
        val b = Hkdf.derive(ikm, "psbk/v1/slot-recovery", 32)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun deterministicAndLengthExact() {
        val ikm = ByteArray(16) { (it * 7).toByte() }
        val once = Hkdf.derive(ikm, "psbk/v1/key-id", 8)
        val twice = Hkdf.derive(ikm, "psbk/v1/key-id", 8)
        assertArrayEquals(once, twice)
        assertEquals(8, once.size)
    }
}
