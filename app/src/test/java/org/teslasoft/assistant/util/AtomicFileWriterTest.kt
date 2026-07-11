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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicFileWriterTest {

    private fun tempDir(): File = Files.createTempDirectory("atomic-writer-test").toFile()

    @Test
    fun writesContentAndLeavesNoTempFile() {
        val dir = tempDir()
        val target = File(dir, "backup.json")
        val content = """{"memories":[1,2,3]}"""

        assertTrue(AtomicFileWriter.writeAndVerify(target, content))
        assertEquals(content, target.readText())
        assertFalse("temp file must not survive a successful write", File(dir, "backup.json.tmp").exists())
    }

    @Test
    fun overwritesAnExistingTarget() {
        val dir = tempDir()
        val target = File(dir, "backup.json")
        assertTrue(AtomicFileWriter.writeAndVerify(target, "first"))
        assertTrue(AtomicFileWriter.writeAndVerify(target, "second"))
        assertEquals("second", target.readText())
    }

    @Test
    fun failsCleanlyWhenTheDirectoryDoesNotExist() {
        val target = File(File(tempDir(), "missing-subdir"), "backup.json")
        assertFalse(AtomicFileWriter.writeAndVerify(target, "content"))
        assertFalse(target.exists())
    }

    @Test
    fun handlesLargeContent() {
        val dir = tempDir()
        val target = File(dir, "backup.json")
        val content = "x".repeat(5_000_000)
        assertTrue(AtomicFileWriter.writeAndVerify(target, content))
        assertEquals(content.length.toLong(), target.length())
    }
}
