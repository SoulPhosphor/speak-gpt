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

package org.teslasoft.assistant.util.summarizer

/** Turns the Summarizer Model's suggested tags into the Tags line's text. */
object ChatOutlineTags {

    /** "Pepper, Writing Contest" from a reply that may add a label, quotes,
     *  bullets or one tag per line. Null when nothing usable is left. */
    fun clean(reply: String): String? {
        val withoutLabel = reply.trim().removePrefix("Tags:").removePrefix("tags:")
        val tags = withoutLabel
            .split(',', '\n')
            .map { it.trim().trimStart('-', '*', '•', '#').trim().trim('"', '\'', '`').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return tags.joinToString(", ").ifBlank { null }
    }
}
