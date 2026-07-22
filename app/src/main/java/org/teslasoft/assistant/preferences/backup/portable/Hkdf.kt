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

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF with HMAC-SHA256 (portable recovery format v2; owner-approved
 * design, July 22 2026).
 *
 * Salt rule (deliberate, owner ruling 4): NO salt — a zero-length salt, which
 * RFC 5869 defines as equivalent to HashLen zero bytes. The input keying
 * material here is always a uniformly random Recovery Secret, so the extract
 * step's entropy-concentration job is already done; the fixed rule keeps every
 * derivation reproducible from the secret alone (a typed Recovery Code carries
 * nothing but the secret). Domain separation lives entirely in the `info`
 * strings, which are versioned ("psbk/v1/...") so a future format revision can
 * never collide with these outputs.
 *
 * Pure JVM code — unit-tested against the RFC 5869 test vectors.
 */
object Hkdf {

    private const val HMAC_ALG = "HmacSHA256"
    private const val HASH_LEN = 32

    /** HKDF-Extract(salt, ikm) → PRK. Empty salt = HashLen zero bytes (RFC). */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val realSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(realSalt, HMAC_ALG))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand(prk, info, length) → OKM. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "invalid HKDF output length" }
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(prk, HMAC_ALG))
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - generated)
            System.arraycopy(t, 0, okm, generated, toCopy)
            generated += toCopy
            counter++
        }
        return okm
    }

    /** The one-call form used by the recovery format: no-salt extract + expand. */
    fun derive(ikm: ByteArray, info: String, length: Int): ByteArray =
        expand(extract(ByteArray(0), ikm), info.toByteArray(Charsets.US_ASCII), length)
}
