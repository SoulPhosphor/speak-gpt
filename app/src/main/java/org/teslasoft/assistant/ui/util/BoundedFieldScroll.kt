/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.widget.EditText

/**
 * A bounded field (maxLines + vertical scroll bar) inside a scrolling page:
 * while the field has more text than it shows, a drag that starts inside it
 * scrolls the field instead of the page. A field that fits all its text
 * leaves the drag to the page.
 */
object BoundedFieldScroll {

    @SuppressLint("ClickableViewAccessibility")
    fun attach(field: EditText) {
        field.setOnTouchListener { view, event ->
            val canScroll = view.canScrollVertically(1) || view.canScrollVertically(-1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    if (canScroll) view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }
}
