/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.PerChatSettingKeys
import org.teslasoft.assistant.preferences.chatsearch.SearchableMessageProjection
import java.io.File

/**
 * Backup and restore must carry a chat's summary sections, Compact's summary
 * state, and every message's permanent ID (owner ruling, Oct 4 2026). Chat
 * backups copy each chat's messages whole and its whole settings file except
 * external credentials, and restore writes both back unchanged.
 */
class SummaryStateBackupContractTest {

    private fun source(relative: String): String {
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.exists() }
            ?: throw AssertionError("$relative not found")
        return file.readText()
    }

    @Test
    fun messageIdsAreTheIdsRestoreValidates() {
        assertTrue(MessageIdentity.KEY == SearchableMessageProjection.MESSAGE_ID_KEY)
        val plan = source("src/main/java/org/teslasoft/assistant/preferences/backup/portable/PortableChatRestorePlan.kt")
        assertTrue(plan.contains("SearchableMessageProjection.MESSAGE_ID_KEY"))
    }

    @Test
    fun chatBackupsCopyMessagesWholeAndEverySettingExceptCredentials() {
        val serializer = source(
            "src/main/java/org/teslasoft/assistant/preferences/backup/portable/ChatLogicalSerializer.kt"
        )
        assertTrue(serializer.contains("private val EXCLUDED_SETTINGS_KEYS = setOf(\"api_key\")"))
        assertTrue(serializer.contains("obj.put(\"messages\", JSONArray(gson.toJson(history.messages)))"))
        val importer = source(
            "src/main/java/org/teslasoft/assistant/preferences/backup/portable/ChatLogicalImporter.kt"
        )
        assertTrue(importer.contains("for (entry in chat.settings)"))
    }

    @Test
    fun summaryAndCompactStateAreRegisteredPerChatSettings() {
        for (key in listOf(
            "summary_sections",
            "summarizer_summary",
            "summarizer_folded",
            "manual_compaction_boundary",
            "use_summarized_conversation_projection",
            "summary_regeneration_lock_boundary",
            "compaction_regeneration_lock_boundary",
            "hide_uncompact_hint",
            "hide_recompact_hint",
            "hide_unsummarize_hint",
            "hide_resummarize_hint"
        )) {
            assertTrue(key, key in PerChatSettingKeys.ALL)
        }
    }
}
