/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GeneratedImagePlaceholderContractTest {
    @Test
    fun missingImageUsesDedicatedPlaceholderNotFailureRetryUi() {
        val adapter = File(
            "src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt"
        ).readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(adapter.contains("showGeneratedImageMissing()"))
        assertTrue(adapter.contains("R.drawable.ic_cancel"))
        assertTrue(adapter.contains("R.string.image_gen_no_longer_available"))
        assertTrue(strings.contains(">Image No Longer Available</string>"))
        assertTrue(adapter.contains("btnImagePrompt.visibility = View.VISIBLE"))
    }

    @Test
    fun resolverMakesTombstoneAuthoritativeOverLegacyHashFallback() {
        val resolver = File(
            "src/main/java/org/teslasoft/assistant/preferences/generatedimages/GeneratedImageAssetResolver.kt"
        ).readText()
        val tombstoneCheck = resolver.indexOf("lookup.tombstoned")
        val legacyLoop = resolver.indexOf("for (format in ImageFormat.entries)")
        assertTrue(tombstoneCheck >= 0)
        assertTrue(tombstoneCheck < legacyLoop)
    }
}
