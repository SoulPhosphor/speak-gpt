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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7.1 of image-generation-rebuild-plan.md: streamed tool-call fragments
 * accumulate until the name and JSON arguments are complete; a complete
 * non-streamed call works identically; and a stream that ends mid-call is
 * a clean failure, never a hang.
 */
class StreamedToolCallAssemblerTest {

    @Test
    fun fragmentedCallAssembles() {
        val assembler = StreamedToolCallAssembler()
        assembler.accept(0, "call_1", "create_image", null)
        assembler.accept(0, null, null, "{\"prompt\":")
        assembler.accept(0, null, null, "\"a fox\",\"description\":\"A fox\"}")

        val calls = assembler.assembled()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("create_image", calls[0].name)
        assertEquals("{\"prompt\":\"a fox\",\"description\":\"A fox\"}", calls[0].arguments)
    }

    @Test
    fun completeNonStreamedCallAssemblesIdentically() {
        val assembler = StreamedToolCallAssembler()
        assembler.accept(0, "call_9", "create_image", "{\"prompt\":\"x\",\"description\":\"y\"}")
        val calls = assembler.assembled()
        assertEquals(1, calls.size)
        assertEquals("{\"prompt\":\"x\",\"description\":\"y\"}", calls[0].arguments)
    }

    @Test
    fun multipleCallsKeepTheirOwnSlots() {
        val assembler = StreamedToolCallAssembler()
        assembler.accept(0, "call_1", "create_image", "{\"a\":1}")
        assembler.accept(1, "call_2", "create_image", "{\"b\":2}")
        val calls = assembler.assembled()
        assertEquals(2, calls.size)
        assertEquals("{\"a\":1}", calls[0].arguments)
        assertEquals("{\"b\":2}", calls[1].arguments)
    }

    @Test
    fun aNamelessSlotFromADeadStreamIsDropped() {
        val assembler = StreamedToolCallAssembler()
        assembler.accept(0, null, null, "{\"pro")
        assertTrue(assembler.assembled().isEmpty())
        assertTrue(assembler.hasAnyFragments())
    }

    @Test
    fun truncatedArgumentsStillAssembleForDownstreamValidationToReject() {
        // The clean-failure path: the assembler hands over what arrived and
        // CreateImageTool.validate rejects the truncated JSON.
        val assembler = StreamedToolCallAssembler()
        assembler.accept(0, "call_1", "create_image", "{\"prompt\": \"a fo")
        val calls = assembler.assembled()
        assertEquals(1, calls.size)
        assertTrue(
            CreateImageTool.validate(calls[0].arguments)
                is CreateImageTool.Validation.Invalid
        )
    }

    @Test
    fun nothingAssemblesFromAnEmptyStream() {
        val assembler = StreamedToolCallAssembler()
        assertTrue(assembler.assembled().isEmpty())
        assertTrue(!assembler.hasAnyFragments())
    }
}
