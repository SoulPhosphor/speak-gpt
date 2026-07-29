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

package org.teslasoft.assistant.imagegen

/**
 * Accumulates streamed tool-call fragments until the tool name and JSON
 * arguments are complete (image-generation-rebuild-plan.md §7.1).
 * Providers differ in how they stream tool calls — many fragments, or one
 * complete non-streamed chunk — and both land here identically. A stream
 * that dies mid-call leaves either a nameless slot (dropped) or truncated
 * argument JSON (a clean validation failure downstream); neither can hang
 * the turn.
 */
class StreamedToolCallAssembler {

    class AssembledToolCall(
        val id: String?,
        val name: String,
        val arguments: String
    )

    private class Slot {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private val slots = LinkedHashMap<Int, Slot>()

    fun accept(index: Int?, id: String?, name: String?, argumentsFragment: String?) {
        val slot = slots.getOrPut(index ?: 0) { Slot() }
        if (!id.isNullOrEmpty()) slot.id = id
        if (!name.isNullOrEmpty()) slot.name = name
        if (argumentsFragment != null) slot.arguments.append(argumentsFragment)
    }

    fun hasAnyFragments(): Boolean = slots.isNotEmpty()

    /** The calls in arrival order; a slot whose name never arrived is
     *  dropped — the §7.1 clean-failure rule for a stream cut mid-call. */
    fun assembled(): List<AssembledToolCall> = slots.values.mapNotNull { slot ->
        val name = slot.name ?: return@mapNotNull null
        AssembledToolCall(slot.id, name, slot.arguments.toString())
    }
}
