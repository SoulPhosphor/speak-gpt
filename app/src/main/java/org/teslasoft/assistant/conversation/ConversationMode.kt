/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.conversation

/** Durable conversation behavior. Missing metadata is intentionally Chat. */
enum class ConversationMode(val storedValue: String) {
    CHAT("chat"),
    PLAYGROUND("playground");

    companion object {
        const val SCHEMA_VERSION = 1
        const val MODE_KEY = "conversation_mode"
        const val MODE_VERSION_KEY = "conversation_mode_version"
        const val PENDING_KEY = "conversation_pending"

        /** The provisional conversation's title, stored with the chat itself.
         *  Recovery needs the real name when the launching Intent is gone. */
        const val PENDING_NAME_KEY = "conversation_pending_name"

        fun fromStored(value: String?): ConversationMode =
            entries.firstOrNull { it.storedValue == value } ?: CHAT
    }
}
