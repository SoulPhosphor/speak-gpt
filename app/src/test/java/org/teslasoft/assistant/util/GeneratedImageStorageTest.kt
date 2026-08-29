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

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the current generated-image storage naming before the image
 * generation rebuild (image-generation-rebuild-plan.md, step 1): the cache
 * file is named by the SHA-256 of the Base64 ENCODING of the image bytes,
 * and the `~file:<id>` chat marker written by the download path derives its
 * id from that same encoded string — marker and filename must agree or the
 * saved image can never be displayed again.
 */
class GeneratedImageStorageTest {

    @Test
    fun fileIsNamedByTheHashOfTheBase64EncodingNotTheRawBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        assertEquals(
            Hash.hash(encoded) + ".png",
            GeneratedImageStorage.cacheFileName(bytes)
        )
        // The encoding step is load-bearing: hashing the raw bytes gives a
        // different name, which would orphan every stored marker.
        assertNotEquals(
            Hash.hash(bytes) + ".png",
            GeneratedImageStorage.cacheFileName(bytes)
        )
    }

    @Test
    fun downloadPathMarkerIdMatchesTheStoredFileStem() {
        // generateImageR's URL branch stores `~file:<id>` with
        // id = Hash.hash(<Base64-encoded bytes>); the file on disk must be
        // exactly that stem plus the extension.
        val bytes = "fake image bytes".toByteArray()
        val markerId = Hash.hash(java.util.Base64.getEncoder().encodeToString(bytes))
        assertEquals("$markerId.png", GeneratedImageStorage.cacheFileName(bytes))
    }

    @Test
    fun defaultImageTypeIsPngAndAnExplicitTypeControlsTheExtension() {
        val bytes = byteArrayOf(9, 8, 7)
        assertTrue(GeneratedImageStorage.cacheFileName(bytes).endsWith(".png"))
        assertTrue(GeneratedImageStorage.cacheFileName(bytes, "jpg").endsWith(".jpg"))
    }

    @Test
    fun namingIsStableForEqualBytesAndDistinctForDifferentBytes() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(3, 2, 1)
        assertEquals(
            GeneratedImageStorage.cacheFileName(a),
            GeneratedImageStorage.cacheFileName(a.copyOf())
        )
        assertNotEquals(
            GeneratedImageStorage.cacheFileName(a),
            GeneratedImageStorage.cacheFileName(b)
        )
    }

    @Test
    fun catalogNameUsesImmutableUuidNotContentOrLabel() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals("$uuid.webp", GeneratedImageStorage.catalogFileName(uuid, "webp"))
        assertEquals("$uuid.png", GeneratedImageStorage.catalogFileName(uuid, "PNG"))
        assertEquals(uuid, GeneratedImageStorage.catalogFileName(uuid, "png")!!.substringBeforeLast('.'))
    }

    @Test
    fun unsafeCatalogIdentityOrExtensionIsRejected() {
        assertEquals(null, GeneratedImageStorage.catalogFileName("../image", "png"))
        assertEquals(null, GeneratedImageStorage.catalogFileName("image-id", "../png"))
    }
}
