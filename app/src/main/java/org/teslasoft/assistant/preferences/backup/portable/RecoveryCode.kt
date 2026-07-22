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

import java.security.MessageDigest

/**
 * The typeable Recovery Code — one of the two representations of the 128-bit
 * Recovery Secret (owner-approved specification, July 21-22 2026):
 *
 *  - 128-bit random secret.
 *  - Crockford Base32 alphabet (0-9 and A-Z excluding I, L, O, U).
 *  - 26 data symbols: 2 leading zero pad bits + the 128 secret bits (130 bits).
 *    The decoder REJECTS nonzero pad bits.
 *  - 6 checksum symbols: the first 30 bits of SHA-256 over the 16 secret
 *    bytes. Unkeyed by design — the checksum's job is TYPO DETECTION only, it
 *    proves nothing about which backup the code belongs to (that is the key
 *    fingerprint's job, and even that is only trustworthy after full package
 *    authentication — owner ruling 4).
 *  - 32 symbols total, displayed as eight groups of four, hyphen-separated.
 *  - Entry normalization: strip hyphens/spaces/whitespace, uppercase, map
 *    O→0, I→1, L→1. U and any other non-alphabet character are rejected.
 *
 * Error contract (owner-approved wording lives in strings.xml; this layer
 * returns types): a checksum failure is ALWAYS reported as a mistyped code,
 * never as damage or a wrong key — those states are distinguished later by
 * the fingerprint and package authentication.
 */
object RecoveryCode {

    const val SECRET_BYTES = 16
    const val DATA_SYMBOLS = 26
    const val CHECKSUM_SYMBOLS = 6
    const val TOTAL_SYMBOLS = DATA_SYMBOLS + CHECKSUM_SYMBOLS
    const val GROUP_SIZE = 4

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    sealed class DecodeResult {
        /** The code decoded and its checksum verified: this is the secret. */
        data class Ok(val secret: ByteArray) : DecodeResult()

        /** Wrong length, invalid character, bad pad bits, or checksum
         *  mismatch — user-facing meaning: "Invalid or mistyped Recovery
         *  Code." (owner wording). */
        object Mistyped : DecodeResult()
    }

    /** Encode a 16-byte secret as the grouped display form
     *  ("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX"). */
    fun encode(secret: ByteArray): String {
        require(secret.size == SECRET_BYTES) { "Recovery Secret must be $SECRET_BYTES bytes" }
        val symbols = StringBuilder(TOTAL_SYMBOLS)

        // 130 data bits: 2 zero pad bits then the 128 secret bits, 5 at a time.
        val dataBits = BitReader(byteArrayOf(0) + secret, startBit = 6) // skip 6 of the 8 leading zero bits -> 2 pad bits remain
        repeat(DATA_SYMBOLS) { symbols.append(ALPHABET[dataBits.read5()]) }

        // 30 checksum bits from SHA-256(secret).
        val checkBits = BitReader(sha256(secret), startBit = 0)
        repeat(CHECKSUM_SYMBOLS) { symbols.append(ALPHABET[checkBits.read5()]) }

        return symbols.chunked(GROUP_SIZE).joinToString("-")
    }

    /** Normalize + decode + checksum-verify a user-entered code. */
    fun decode(entered: String): DecodeResult {
        val normalized = StringBuilder(TOTAL_SYMBOLS)
        for (raw in entered) {
            val c = when (val u = raw.uppercaseChar()) {
                '-', ' ', '\t', '\n', '\r' -> continue
                'O' -> '0'
                'I', 'L' -> '1'
                else -> u
            }
            if (ALPHABET.indexOf(c) < 0) return DecodeResult.Mistyped
            normalized.append(c)
        }
        if (normalized.length != TOTAL_SYMBOLS) return DecodeResult.Mistyped

        val values = IntArray(TOTAL_SYMBOLS) { ALPHABET.indexOf(normalized[it]) }

        // Reassemble the 130 data bits; the 2 pad bits must be zero.
        val dataBits = BitWriter()
        for (i in 0 until DATA_SYMBOLS) dataBits.write5(values[i])
        val dataBytes = dataBits.toByteArray() // 130 bits -> 17 bytes, 6 trailing pad bits (zero-filled by writer)
        val padBits = (dataBytes[0].toInt() and 0xC0) ushr 6
        if (padBits != 0) return DecodeResult.Mistyped
        val secret = ByteArray(SECRET_BYTES)
        // The secret occupies bits 2..129: shift the 17-byte buffer left by 2.
        for (i in 0 until SECRET_BYTES) {
            val hi = (dataBytes[i].toInt() and 0x3F) shl 2
            val lo = (dataBytes[i + 1].toInt() and 0xC0) ushr 6
            secret[i] = (hi or lo).toByte()
        }

        // Verify the 30-bit checksum.
        val expected = BitReader(sha256(secret), startBit = 0)
        for (i in DATA_SYMBOLS until TOTAL_SYMBOLS) {
            if (values[i] != expected.read5()) return DecodeResult.Mistyped
        }
        return DecodeResult.Ok(secret)
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Reads consecutive 5-bit values from a big-endian bit stream. */
    private class BitReader(private val bytes: ByteArray, startBit: Int) {
        private var pos = startBit
        fun read5(): Int {
            var v = 0
            repeat(5) {
                val byte = bytes[pos ushr 3].toInt() and 0xFF
                val bit = (byte ushr (7 - (pos and 7))) and 1
                v = (v shl 1) or bit
                pos++
            }
            return v
        }
    }

    /** Writes consecutive 5-bit values into a big-endian bit stream. */
    private class BitWriter {
        private val bits = ArrayList<Int>(TOTAL_SYMBOLS * 5)
        fun write5(v: Int) {
            for (i in 4 downTo 0) bits.add((v ushr i) and 1)
        }
        fun toByteArray(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8)
            for (i in bits.indices) {
                if (bits[i] == 1) {
                    out[i ushr 3] = (out[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
                }
            }
            return out
        }
    }
}
