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
 * User names are intentionally read from the message record first. The
 * current chat does not set that field, so it falls back to "User"; keeping
 * the lookup here means a future configurable user name needs no export
 * format change.
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

    fun companionName(
        context: Context,
        message: Map<String, *> = emptyMap<String, Any?>(),
        currentName: String? = null
    ): String =
        message[COMPANION_NAME_KEY]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: currentName?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.chat_role_assistant)
}
