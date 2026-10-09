/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.teslasoft.assistant.R

/**
 * With profile images shown, gives the first message the same space between
 * the header above it and its portrait as there is between one message's
 * bubble and the next message's portrait. A portrait rises above its bubble
 * into the message's leading space, so between messages that gap is the
 * previous message's trailing space plus what is left of the leading space;
 * the first message has no previous message, so it gets the trailing space
 * (chat_message_unit_spacing) added above it.
 */
class FirstMessagePortraitInset(
    private val profileImagesShown: () -> Boolean
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.getChildAdapterPosition(view) == 0 && profileImagesShown()) {
            outRect.top = view.resources.getDimensionPixelSize(R.dimen.chat_message_unit_spacing)
        }
    }
}
