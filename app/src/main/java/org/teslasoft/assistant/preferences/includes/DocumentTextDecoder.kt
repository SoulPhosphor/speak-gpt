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

package org.teslasoft.assistant.preferences.includes

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Best-effort decoding for plain-text document formats. */
object DocumentTextDecoder {

    private val windows1252: Charset = Charset.forName("windows-1252")

    fun decode(bytes: ByteArray, allowTruncatedTail: Boolean = false): String? {
        if (bytes.isEmpty()) return ""

        return try {
            when {
                bytes.hasPrefix(UTF8_BOM) ->
                    decodeStrict(
                        bytes, UTF8_BOM.size, Charsets.UTF_8,
                        if (allowTruncatedTail) 3 else 0
                    )
                bytes.hasPrefix(UTF16_LE_BOM) ->
                    decodeStrict(
                        bytes, UTF16_LE_BOM.size, Charsets.UTF_16LE,
                        if (allowTruncatedTail) 1 else 0
                    )
                bytes.hasPrefix(UTF16_BE_BOM) ->
                    decodeStrict(
                        bytes, UTF16_BE_BOM.size, Charsets.UTF_16BE,
                        if (allowTruncatedTail) 1 else 0
                    )
                else -> try {
                    decodeStrict(
                        bytes, 0, Charsets.UTF_8,
                        if (allowTruncatedTail) 3 else 0
                    )
                } catch (_: CharacterCodingException) {
                    String(bytes, windows1252)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeStrict(
        bytes: ByteArray,
        offset: Int,
        charset: Charset,
        maxTailBytesToDrop: Int
    ): String {
        var lastError: CharacterCodingException? = null
        for (tailBytesToDrop in 0..maxTailBytesToDrop) {
            val length = bytes.size - offset - tailBytesToDrop
            if (length < 0) break
            try {
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
            } catch (error: CharacterCodingException) {
                lastError = error
            }
        }
        throw lastError ?: CharacterCodingException()
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private val UTF8_BOM =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM =
        byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
