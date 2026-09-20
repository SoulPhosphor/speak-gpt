/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GeneratedImageCatalogRenameTest {
    @Test
    fun titleOnlyRenameUpdatesLabelByStableChatId() {
        val source = File(
            "src/main/java/org/teslasoft/assistant/preferences/ChatPreferences.kt"
        ).readText()
        val successfulTitleOnly = source.indexOf("if (oldId == newId)")
        val labelHook = source.indexOf("GeneratedImageCatalogStore.renameOriginChat")
        assertTrue(successfulTitleOnly >= 0)
        assertTrue(labelHook > successfulTitleOnly)
        assertTrue(source.contains("renameOriginChat(context, oldId, chatName)"))
    }
}
