/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences

import org.teslasoft.assistant.preferences.chatsearch.SearchableMessageProjection
import java.util.UUID

/**
 * Permanent identity for stored chat messages (owner ruling, Oct 4 2026):
 * the existing "message_id" UUID that new messages already receive and that
 * chat search and backup restore rely on. Every message keeps it for life:
 * editing a message, switching or regenerating its reply versions, and
 * renaming the chat never change it. Only deleting a message removes its ID.
 * Messages saved before IDs existed receive one here, once. Summary sections
 * record which messages they cover by these IDs, so deleting an earlier
 * message never shifts what a section owns.
 */
object MessageIdentity {
    const val KEY = SearchableMessageProjection.MESSAGE_ID_KEY

    /** The message's ID, or "" when it has none yet. */
    fun idOf(message: Map<String, Any>): String = message[KEY]?.toString().orEmpty()

    /** Gives every message without an ID a new one. True when any was added. */
    fun ensure(messages: List<MutableMap<String, Any>>): Boolean {
        var added = false
        for (message in messages) {
            if (idOf(message).isBlank()) {
                message[KEY] = UUID.randomUUID().toString()
                added = true
            }
        }
        return added
    }
}
