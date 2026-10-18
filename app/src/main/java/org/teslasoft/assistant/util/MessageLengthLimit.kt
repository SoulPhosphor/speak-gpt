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

package org.teslasoft.assistant.util

import java.text.NumberFormat

/**
 * The most text one chat message may hold (owner ruling, October 17 2026):
 * 200,000 characters of typed or pasted text. Attached documents are not
 * counted; they are handled separately. Characters are Unicode code points,
 * so an emoji counts as one.
 */
object MessageLengthLimit {

    const val MAX_CHARACTERS = 200_000

    fun characterCount(text: CharSequence): Int =
        Character.codePointCount(text, 0, text.length)

    fun exceeds(text: CharSequence): Boolean =
        // A String can't hold fewer code points than half its length, so
        // ordinary messages skip the count.
        text.length > MAX_CHARACTERS && characterCount(text) > MAX_CHARACTERS

    fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)
}
