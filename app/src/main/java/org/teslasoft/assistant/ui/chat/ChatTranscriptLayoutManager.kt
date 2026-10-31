/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Layout manager for the chat transcript.
 *
 * When the composer moves its focused editor between its compact and editing
 * layouts, Android briefly hands input focus to the first focusable view in the
 * window, which can be a message row. RecyclerView's default reaction is to
 * smooth-scroll that row onto the screen, which drags the conversation away
 * from the composer while the keyboard is opening (chat-keyboard-behavior.md,
 * rule 4). A row receiving focus never moves the conversation here.
 *
 * The one exception is a text field inside a row (the image summary editor):
 * the user tapped Edit on that row, so it keeps the default behavior of being
 * brought into view.
 */
class ChatTranscriptLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean =
        if (focused is EditText) super.onRequestChildFocus(parent, state, child, focused) else true
}
