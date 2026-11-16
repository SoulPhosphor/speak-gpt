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

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Wires a whole-number settings field (Widget.App.Row.Number.Field) to its
 * stored value. Values are saved while typing; a field never shows a number
 * that is not in force once it loses focus.
 */
object NumberSettingField {

    /** A field with a default: an empty or out-of-range entry keeps the
     *  stored value, which the field shows again when it loses focus. */
    fun bindRequired(field: EditText, range: IntRange, read: () -> Int, write: (Int) -> Unit) {
        field.setText(read().toString())
        field.addTextChangedListener(watcher { text ->
            text.toIntOrNull()?.takeIf { it in range }?.let(write)
        })
        field.setOnFocusChangeListener { _, focused ->
            if (!focused && field.text.toString().toIntOrNull()?.takeIf { it in range } == null) {
                field.setText(read().toString())
            }
        }
    }

    /** A field with no default: it starts empty, and an empty or
     *  out-of-range entry stores no value. */
    fun bindOptional(field: EditText, range: IntRange, read: () -> Int?, write: (Int?) -> Unit) {
        field.setText(read()?.toString().orEmpty())
        field.addTextChangedListener(watcher { text ->
            write(text.toIntOrNull()?.takeIf { it in range })
        })
        field.setOnFocusChangeListener { _, focused ->
            if (!focused && field.text.toString().toIntOrNull()?.takeIf { it in range } == null) {
                field.setText("")
            }
        }
    }

    private fun watcher(changed: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = changed(s?.toString()?.trim().orEmpty())
    }
}
