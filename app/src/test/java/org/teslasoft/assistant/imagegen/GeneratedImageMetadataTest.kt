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

package org.teslasoft.assistant.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §12 of image-generation-rebuild-plan.md: the structured record stored
 * with a generated assistant message survives a round trip, tolerates
 * legacy and damaged input, and the shared "references a file" question is
 * answered from the record first and the legacy `~file:` text second.
 */
class GeneratedImageMetadataTest {

    private fun completeRecord() = GeneratedImageMetadata(
        imageId = "id-123",
        fileHash = "abcdef",
        mimeType = "image/webp",
        width = 1024,
        height = 768,
        endpointId = "endpoint-1",
        modelId = "example/image-model",
        prompt = "a lighthouse at dusk",
        description = "A lighthouse on a cliff at dusk",
        createdAt = 1722268800000L,
        status = GeneratedImageMetadata.STATUS_COMPLETE,
        failureCode = null
    )

    @Test
    fun completeRecordSurvivesARoundTrip() {
        val restored = GeneratedImageMetadata.fromJson(completeRecord().toJson())!!
        assertEquals("id-123", restored.imageId)
        assertEquals("abcdef", restored.fileHash)
        assertEquals("image/webp", restored.mimeType)
        assertEquals(1024, restored.width!!)
        assertEquals(768, restored.height!!)
        assertEquals("endpoint-1", restored.endpointId)
        assertEquals("example/image-model", restored.modelId)
        assertEquals("a lighthouse at dusk", restored.prompt)
        assertEquals("A lighthouse on a cliff at dusk", restored.description)
        assertEquals(1722268800000L, restored.createdAt)
        assertEquals(GeneratedImageMetadata.STATUS_COMPLETE, restored.status)
        assertNull(restored.failureCode)
    }

    @Test
    fun failedRecordKeepsItsCauseAndOmitsFileFields() {
        val restored = GeneratedImageMetadata.fromJson(
            GeneratedImageMetadata(
                imageId = "id-9",
                fileHash = null,
                mimeType = null,
                width = null,
                height = null,
                endpointId = "endpoint-1",
                modelId = "m",
                prompt = "p",
                description = null,
                createdAt = 5L,
                status = GeneratedImageMetadata.STATUS_FAILED,
                failureCode = ImageErrorCause.TIMED_OUT.name
            ).toJson()
        )!!
        assertEquals(GeneratedImageMetadata.STATUS_FAILED, restored.status)
        assertEquals("TIMED_OUT", restored.failureCode)
        assertNull(restored.fileHash)
        assertNull(restored.mimeType)
        assertNull(restored.width)
        assertNull(restored.height)
        assertNull(restored.description)
    }

    @Test
    fun legacyOrDamagedInputDegradesToNullNotACrash() {
        assertNull(GeneratedImageMetadata.fromJson(null))
        assertNull(GeneratedImageMetadata.fromJson(""))
        assertNull(GeneratedImageMetadata.fromJson("not json at all"))
    }

    // --- the one shared "references a generated file" definition ---

    @Test
    fun theStructuredRecordAnswersBeforeTheLegacyText() {
        val message = mapOf(
            "message" to "~file:legacyhash",
            "isBot" to true,
            GeneratedImageMetadata.KEY to completeRecord().toJson()
        )
        assertEquals("abcdef", GeneratedImageMetadata.referencedFileHash(message))
    }

    @Test
    fun legacyMarkerMessagesStillAnswerFromTheirText() {
        val message = mapOf("message" to "~file:legacyhash", "isBot" to true)
        assertEquals("legacyhash", GeneratedImageMetadata.referencedFileHash(message))
    }

    @Test
    fun ordinaryMessagesReferenceNothing() {
        assertNull(
            GeneratedImageMetadata.referencedFileHash(
                mapOf("message" to "just words about ~file: markers", "isBot" to true)
            )
        )
        assertNull(GeneratedImageMetadata.referencedFileHash(mapOf("isBot" to true)))
    }

    @Test
    fun statusValuesMatchThePlanSpelling() {
        assertEquals("generating", GeneratedImageMetadata.STATUS_GENERATING)
        assertEquals("complete", GeneratedImageMetadata.STATUS_COMPLETE)
        assertEquals("failed", GeneratedImageMetadata.STATUS_FAILED)
        assertEquals("cancelled", GeneratedImageMetadata.STATUS_CANCELLED)
    }
}
