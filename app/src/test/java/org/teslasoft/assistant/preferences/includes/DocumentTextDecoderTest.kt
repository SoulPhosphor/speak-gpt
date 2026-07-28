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

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentTextDecoderTest {

    @Test fun decodesUtf8AndStripsItsBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "Résumé 漢字".toByteArray(Charsets.UTF_8)
        assertEquals("Résumé 漢字", DocumentTextDecoder.decode(bytes))
    }

    @Test fun decodesUtf16LittleEndianFromItsBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Hello 漢字".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hello 漢字", DocumentTextDecoder.decode(bytes))
    }

    @Test fun decodesUtf16BigEndianFromItsBom() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "Hello 漢字".toByteArray(Charsets.UTF_16BE)
        assertEquals("Hello 漢字", DocumentTextDecoder.decode(bytes))
    }

    @Test fun fallsBackToWindows1252WithoutMojibake() {
        val bytes = byteArrayOf(
            'c'.code.toByte(),
            'a'.code.toByte(),
            'f'.code.toByte(),
            0xE9.toByte(),
            ' '.code.toByte(),
            0x97.toByte(),
            ' '.code.toByte(),
            'n'.code.toByte(),
            'o'.code.toByte(),
            't'.code.toByte(),
            'e'.code.toByte()
        )
        assertEquals("café — note", DocumentTextDecoder.decode(bytes))
    }

    @Test fun aCappedUtf8TailDoesNotTurnTheWholePrefixIntoMojibake() {
        val complete = "prefix 漢".toByteArray(Charsets.UTF_8)
        val capped = complete.copyOf(complete.size - 1)
        assertEquals(
            "prefix ",
            DocumentTextDecoder.decode(capped, allowTruncatedTail = true)
        )
    }
}
