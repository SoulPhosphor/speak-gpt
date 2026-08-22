/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.adapters

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListLaunchDebounceTest {

    @Test
    fun rapidRowTapsCannotLaunchDuplicateChatActivities() {
        val source = adapterSource().readText()
        assertTrue(source.contains("CHAT_LAUNCH_DEBOUNCE_MS"))
        assertTrue(source.contains("SystemClock.elapsedRealtime()"))
        assertTrue(source.contains("return@setOnClickListener"))
    }

    private fun adapterSource(): File {
        val relative = "src/main/java/org/teslasoft/assistant/ui/adapters/ChatListAdapter.kt"
        return listOf(
            File(relative),
            File("app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "app/$relative")
        ).firstOrNull { it.isFile }
            ?: error("Could not locate ChatListAdapter.kt")
    }
}
