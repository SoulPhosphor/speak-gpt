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

class GeneratedImageRegistrationTest {

    @Test
    fun completePublishesOnlyAfterAtomicFileAndCatalogRegistration() {
        val source = File(
            "src/main/java/org/teslasoft/assistant/imagegen/ImageGenerationJobRegistry.kt"
        ).readText()
        val fileWrite = source.indexOf("AtomicFileWriter.writeBytesAndVerify")
        val catalogWrite = source.indexOf("GeneratedImageCatalogStore.register")
        val successLog = source.indexOf("ImageGenerationEventLog.recordSuccess")
        val completion = source.indexOf("Terminal.Complete(marker, metadata)")

        assertTrue(fileWrite >= 0)
        assertTrue(fileWrite < catalogWrite)
        assertTrue(catalogWrite < successLog)
        assertTrue(successLog < completion)
    }

    @Test
    fun oneJobAllocatesOneUuidAndMetadataReusesIt() {
        val source = File(
            "src/main/java/org/teslasoft/assistant/imagegen/ImageGenerationJobRegistry.kt"
        ).readText()
        assertTrue(source.contains("imageId = UUID.randomUUID().toString()"))
        assertTrue(source.contains("imageId = record.imageId"))
        assertTrue(source.contains("originMessageId = record.imageId"))
    }
}
