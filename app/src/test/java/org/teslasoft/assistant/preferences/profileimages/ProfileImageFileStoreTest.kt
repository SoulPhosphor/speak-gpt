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

package org.teslasoft.assistant.preferences.profileimages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.teslasoft.assistant.util.Hash
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfileImageFileStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("profile-image-store-test").toFile()

    @Test
    fun writesANewFileUnderItsContentHash() {
        val dir = tempDir()
        val bytes = "fake-jpeg-bytes-1".toByteArray()

        val result = ProfileImageFileStore.writeEncodedImage(dir, bytes)

        assertTrue(result != null)
        assertEquals(Hash.hash(bytes), result!!.hash)
        assertTrue(result.wasNewFile)
        assertTrue(result.file.exists())
        assertArrayEquals(bytes, result.file.readBytes())
        assertEquals("profile_${result.hash}.jpg", result.file.name)
    }

    @Test
    fun leavesNoTempFileBehindAfterASuccessfulWrite() {
        val dir = tempDir()
        ProfileImageFileStore.writeEncodedImage(dir, "content".toByteArray())

        val leftovers = dir.listFiles()?.filter { it.name.contains(".tmp-") }.orEmpty()
        assertTrue("no .tmp- file should survive a successful write", leftovers.isEmpty())
    }

    @Test
    fun dedupesIdenticalBytesOntoTheExistingFile() {
        val dir = tempDir()
        val bytes = "identical-content".toByteArray()

        val first = ProfileImageFileStore.writeEncodedImage(dir, bytes)!!
        val originalModified = first.file.lastModified()

        val second = ProfileImageFileStore.writeEncodedImage(dir, bytes)

        assertTrue(second != null)
        assertEquals(first.hash, second!!.hash)
        assertFalse("dedup must not report a new file", second.wasNewFile)
        assertEquals(1, dir.listFiles()?.size)
        assertEquals(originalModified, second.file.lastModified())
    }

    @Test
    fun differentContentProducesDifferentFiles() {
        val dir = tempDir()
        ProfileImageFileStore.writeEncodedImage(dir, "content-a".toByteArray())
        ProfileImageFileStore.writeEncodedImage(dir, "content-b".toByteArray())

        assertEquals(2, dir.listFiles()?.size)
    }

    @Test
    fun createsTheStorageDirectoryWhenMissing() {
        val parent = tempDir()
        val dir = File(parent, "not-yet-created")

        val result = ProfileImageFileStore.writeEncodedImage(dir, "content".toByteArray())

        assertTrue(dir.exists())
        assertTrue(result != null)
    }

    @Test
    fun deletingAnExistingFileSucceedsAndRemovesIt() {
        val dir = tempDir()
        val result = ProfileImageFileStore.writeEncodedImage(dir, "to-delete".toByteArray())!!

        assertTrue(ProfileImageFileStore.deleteImageFile(dir, result.hash))
        assertFalse(result.file.exists())
    }

    @Test
    fun deletingAMissingFileCountsAsSuccess() {
        val dir = tempDir()
        assertTrue(ProfileImageFileStore.deleteImageFile(dir, "f".repeat(64)))
    }
}
