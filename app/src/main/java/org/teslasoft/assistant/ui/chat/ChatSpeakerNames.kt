/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.content.Context
import org.teslasoft.assistant.R

/**
 * Resolves the names that are written above chat messages and into exports.
 *
 * User names are read from the message record first. Each new user message
 * is stamped with [activeUserName] when it is sent, so a later identity
 * change never relabels earlier messages; messages sent before stamping
 * existed fall back to "User".
 */
object ChatSpeakerNames {

    const val USER_NAME_KEY = "userName"
    const val COMPANION_NAME_KEY = "companionName"

    fun userName(
        context: Context,
        message: Map<String, *> = emptyMap<String, Any?>(),
        configuredName: String? = null
    ): String =
        message[USER_NAME_KEY]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: configuredName?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.chat_role_user)

    /**
     * The name to stamp on a user message being sent now. A Roleplay Character
     * name and a Glamour name each apply only when that identity is in use;
     * when both do, [roleplayWins] picks between them. With neither, the
     * Default Displayed Username applies; null means nothing to stamp, so the
     * message shows the built-in "User" label.
     */
    fun activeUserName(
        roleplayName: String?,
        glamourName: String?,
        defaultName: String?,
        roleplayWins: Boolean
    ): String? {
        val roleplay = roleplayName?.trim()?.takeIf { it.isNotEmpty() }
        val glamour = glamourName?.trim()?.takeIf { it.isNotEmpty() }
        val identity = if (roleplay != null && glamour != null) {
            if (roleplayWins) roleplay else glamour
        } else {
            roleplay ?: glamour
        }
        return identity ?: defaultName?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun companionName(
        context: Context,
        message: Map<String, *> = emptyMap<String, Any?>(),
        currentName: String? = null
    ): String =
        message[COMPANION_NAME_KEY]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: currentName?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.chat_role_assistant)
}
