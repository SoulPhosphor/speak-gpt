/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.chat

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatTurnMemoryScopeTest {

    private fun user() = hashMapOf<String, Any>("isBot" to false, "message" to "q")
    private fun reply(text: String) = hashMapOf<String, Any>("isBot" to true, "message" to text)

    @Test fun aFinishedTurnRecordsScopeOnTheReplyAndOnAnUnscopedUserMessage() {
        val chat = listOf(user(), reply("a"))
        val scope = ScanMessageScope(companionPersonaId = "p1")
        assertTrue(ChatTurnMemoryScope.stamp(chat, scope))
        assertEquals(scope, ScanMessageScope.read(chat[0]))
        assertEquals(scope, ScanMessageScope.read(chat[1]))
        assertFalse(ChatTurnMemoryScope.stamp(chat, scope))
    }

    @Test fun eachRegeneratedVersionKeepsTheScopeItWasGeneratedUnder() {
        val first = ScanMessageScope(projectId = "old-project")
        val second = ScanMessageScope(projectId = "new-project")
        val chat = listOf(user(), reply("first"))
        ChatTurnMemoryScope.stamp(chat, first)
        val older = ChatAdapter.snapshotVariant(chat[1])

        // A regeneration under a different project becomes the current version.
        val regenerated = reply("second")
        RegenerationRecovery.foldInto(regenerated, mutableListOf(older), null)
        val chat2 = listOf(chat[0], regenerated)
        ChatTurnMemoryScope.stamp(chat2, second)
        assertEquals(second, ScanMessageScope.read(regenerated))

        // Making the older version current brings its own scope back.
        val versions = ChatAdapter.parseVariants(regenerated[ChatAdapter.KEY_VARIANTS]?.toString())
        assertEquals(second, ScanMessageScope.decode(versions[1][ScanMessageScope.KEY]))
        ChatAdapter.applyVariant(regenerated, versions[0])
        assertEquals("first", regenerated["message"])
        assertEquals(first, ScanMessageScope.read(regenerated))
    }

    @Test fun aVersionSavedWithoutScopeReadsAsUnknown() {
        val withScope = reply("new").apply { ScanMessageScope.write(this, ScanMessageScope(projectId = "p")) }
        ChatAdapter.applyVariant(withScope, mapOf("message" to "old version"))
        assertNull(ScanMessageScope.read(withScope))
    }
}
