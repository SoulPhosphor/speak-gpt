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
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LegacyAvatarResolverTest {

    private fun tempDir(): File = Files.createTempDirectory("legacy-avatar-test").toFile()

    @Test
    fun resolvesAnExistingPngFile() {
        val dir = tempDir()
        val avatarId = "abc123"
        File(dir, "avatar_$avatarId.png").writeText("png-bytes")

        val resolved = LegacyAvatarResolver.resolve(dir, avatarId)

        assertEquals(File(dir, "avatar_$avatarId.png"), resolved)
    }

    @Test
    fun resolvesAnExistingJpgFileWhenNoPngExists() {
        val dir = tempDir()
        val avatarId = "def456"
        File(dir, "avatar_$avatarId.jpg").writeText("jpg-bytes")

        val resolved = LegacyAvatarResolver.resolve(dir, avatarId)

        assertEquals(File(dir, "avatar_$avatarId.jpg"), resolved)
    }

    @Test
    fun prefersPngOverJpgWhenBothExist() {
        val dir = tempDir()
        val avatarId = "ghi789"
        File(dir, "avatar_$avatarId.png").writeText("png-bytes")
        File(dir, "avatar_$avatarId.jpg").writeText("jpg-bytes")

        val resolved = LegacyAvatarResolver.resolve(dir, avatarId)

        assertEquals(File(dir, "avatar_$avatarId.png"), resolved)
    }

    @Test
    fun neverMatchesAJpegExtension() {
        val dir = tempDir()
        val avatarId = "jkl012"
        File(dir, "avatar_$avatarId.jpeg").writeText("jpeg-bytes")

        val resolved = LegacyAvatarResolver.resolve(dir, avatarId)

        assertNull("the writer never produces .jpeg, so the resolver must not look for it", resolved)
    }

    @Test
    fun returnsNullWhenNeitherFileExists() {
        val dir = tempDir()

        val resolved = LegacyAvatarResolver.resolve(dir, "missing")

        assertNull(resolved)
    }

    @Test
    fun returnsNullForANullDirectory() {
        assertNull(LegacyAvatarResolver.resolve(null, "anything"))
    }

    @Test
    fun returnsNullForAnEmptyAvatarId() {
        val dir = tempDir()
        assertNull(LegacyAvatarResolver.resolve(dir, ""))
    }
}
