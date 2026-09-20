/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.playground

import android.content.Context
import org.teslasoft.assistant.preferences.SecurePrefs

/** Per-conversation Playground panel state; the empty id retains legacy global behavior. */
class PlaygroundSessionStore(context: Context, chatId: String) {
    private val preferences = SecurePrefs.get(context.applicationContext, "settings.$chatId")

    fun input(): String = preferences.getString(INPUT_KEY, "").orEmpty()
    fun output(): String = preferences.getString(OUTPUT_KEY, "").orEmpty()
    fun saveInput(value: String) = preferences.edit().putString(INPUT_KEY, value).apply()
    fun saveOutput(value: String) = preferences.edit().putString(OUTPUT_KEY, value).apply()

    companion object {
        const val INPUT_KEY = "playground_input"
        const val OUTPUT_KEY = "playground_output"
    }
}
