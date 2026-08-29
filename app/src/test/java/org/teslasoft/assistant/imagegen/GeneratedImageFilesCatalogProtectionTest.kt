/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.imagegen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source contract for the asynchronous Android cleanup path. The SQLCipher
 * behavior itself is covered by the arm64 catalog instrumentation suite. */
class GeneratedImageFilesCatalogProtectionTest {

    @Test
    fun cleanupChecksCatalogBeforeHistoryOrFileDeletion() {
        val source = File(
            "src/main/java/org/teslasoft/assistant/imagegen/GeneratedImageFiles.kt"
        ).readText()
        val catalogCheck = source.indexOf("hasActiveFileHash")
        val chatScan = source.indexOf("getChatListResult")
        val fileDelete = source.lastIndexOf("file.delete()")

        assertTrue(catalogCheck >= 0)
        assertTrue(catalogCheck < chatScan)
        assertTrue(catalogCheck < fileDelete)
        assertTrue(source.contains("state != GeneratedImageCatalogStorageState.AVAILABLE"))
        assertTrue(source.contains("return@launch"))
    }
}
