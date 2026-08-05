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

package org.teslasoft.assistant.preferences.backup.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.util.Hash
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The ZIP half of the §6.1 validation ladder, against real archives built on
 * the JVM: every rejection cause produces its matching verdict and a valid
 * file (with a content-true image) passes.
 */
class CompanionBackupValidatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val imageBytes = "fake-jpeg-bytes".toByteArray()
    private val imageHash = Hash.hash(imageBytes)

    private fun manifest(images: List<CompanionBackupImage>): String =
        CompanionBackupCodec.toJson(
            CompanionBackupManifest(
                formatVersion = CompanionBackupFormat.FORMAT_VERSION,
                appVersion = "1.0",
                exportedAt = "2026-08-05T00:00:00Z",
                companionProfiles = emptyList(),
                activationPrompts = emptyList(),
                systemPrompts = emptyList(),
                selectedSystemPromptId = "",
                roleplayTables = CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { emptyList() },
                images = images
            )
        )

    private fun writeZip(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile("backup.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun notAZipIsWrongFile() {
        val file = temp.newFile("junk.zip")
        file.writeText("this is not a zip archive")
        assertEquals(CompanionBackupValidator.Verdict.WrongFile, CompanionBackupValidator.validate(file))
    }

    @Test
    fun zipWithoutManifestIsWrongFile() {
        val file = writeZip("something-else.txt" to "hello".toByteArray())
        assertEquals(CompanionBackupValidator.Verdict.WrongFile, CompanionBackupValidator.validate(file))
    }

    @Test
    fun manifestWithForeignMarkerIsWrongFile() {
        val file = writeZip(
            CompanionBackupFormat.MANIFEST_ENTRY to
                """{"format":"another-app-export","format_version":1}""".toByteArray()
        )
        assertEquals(CompanionBackupValidator.Verdict.WrongFile, CompanionBackupValidator.validate(file))
    }

    @Test
    fun unparseableManifestIsDamaged() {
        val file = writeZip(CompanionBackupFormat.MANIFEST_ENTRY to "{ broken".toByteArray())
        assertEquals(CompanionBackupValidator.Verdict.Damaged, CompanionBackupValidator.validate(file))
    }

    @Test
    fun newerFormatVersionIsNewerFormat() {
        val marker = CompanionBackupFormat.FORMAT_MARKER
        val newer = CompanionBackupFormat.FORMAT_VERSION + 1
        val file = writeZip(
            CompanionBackupFormat.MANIFEST_ENTRY to
                """{"format":"$marker","format_version":$newer}""".toByteArray()
        )
        assertEquals(CompanionBackupValidator.Verdict.NewerFormat, CompanionBackupValidator.validate(file))
    }

    @Test
    fun manifestListedImageMissingFromArchiveIsDamaged() {
        val entryName = CompanionBackupFormat.imageEntryName(imageHash)
        val file = writeZip(
            CompanionBackupFormat.MANIFEST_ENTRY to
                manifest(listOf(CompanionBackupImage(imageHash, entryName))).toByteArray()
        )
        assertEquals(CompanionBackupValidator.Verdict.Damaged, CompanionBackupValidator.validate(file))
    }

    @Test
    fun imageBytesNotMatchingDeclaredHashIsDamaged() {
        val entryName = CompanionBackupFormat.imageEntryName(imageHash)
        val file = writeZip(
            CompanionBackupFormat.MANIFEST_ENTRY to
                manifest(listOf(CompanionBackupImage(imageHash, entryName))).toByteArray(),
            entryName to "different bytes".toByteArray()
        )
        assertEquals(CompanionBackupValidator.Verdict.Damaged, CompanionBackupValidator.validate(file))
    }

    @Test
    fun validArchiveWithContentTrueImagePasses() {
        val entryName = CompanionBackupFormat.imageEntryName(imageHash)
        val image = CompanionBackupImage(imageHash, entryName)
        val file = writeZip(
            CompanionBackupFormat.MANIFEST_ENTRY to manifest(listOf(image)).toByteArray(),
            entryName to imageBytes
        )
        val verdict = CompanionBackupValidator.validate(file)
        assertTrue("expected Valid, got $verdict", verdict is CompanionBackupValidator.Verdict.Valid)
        assertArrayEquals(imageBytes, CompanionBackupValidator.readImageBytes(file, image))
    }

    @Test
    fun validArchiveWithNoImagesPasses() {
        val file = writeZip(
            CompanionBackupFormat.MANIFEST_ENTRY to manifest(emptyList()).toByteArray()
        )
        assertTrue(CompanionBackupValidator.validate(file) is CompanionBackupValidator.Verdict.Valid)
    }
}
