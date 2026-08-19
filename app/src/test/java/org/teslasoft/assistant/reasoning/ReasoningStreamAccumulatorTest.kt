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

package org.teslasoft.assistant.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningStreamAccumulatorTest {

    @Test
    fun accumulatesOpenRouterReasoningDeltas() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning":"Let me "}}]}""")
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning":"think."}}]}""")
        acc.acceptLine("""data: {"choices":[{"delta":{"content":"Answer"}}]}""")
        assertTrue(acc.hasReasoning())
        assertEquals("Let me think.", acc.snapshot().text)
    }

    @Test
    fun accumulatesDeepSeekReasoningContent() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_content":"step 1 "}}]}""")
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_content":"step 2"}}]}""")
        assertEquals("step 1 step 2", acc.snapshot().text)
    }

    @Test
    fun contentOnlyStreamYieldsNoReasoning() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"content":"Hello"}}]}""")
        acc.acceptLine("data: [DONE]")
        assertFalse(acc.hasReasoning())
        assertEquals("", acc.snapshot().text)
    }

    @Test
    fun capturesReasoningTokensFromCompletionDetails() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning":"x"}}]}""")
        acc.acceptLine("""data: {"choices":[],"usage":{"completion_tokens":10,"completion_tokens_details":{"reasoning_tokens":128}}}""")
        assertEquals(128, acc.snapshot().reasoningTokens)
    }

    @Test
    fun capturesTopLevelReasoningTokens() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"usage":{"reasoning_tokens":42},"choices":[{"delta":{"reasoning":"x"}}]}""")
        assertEquals(42, acc.snapshot().reasoningTokens)
    }

    @Test
    fun reasoningDetailsSummaryMarkedAsSummary() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.summary","text":"Short summary."}]}}]}""")
        val snap = acc.snapshot()
        assertEquals("Short summary.", snap.text)
        assertTrue(snap.isSummary)
    }

    @Test
    fun rawReasoningIsNotMarkedSummaryEvenAlongsideSummaryBlock() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning":"raw thought"}}]}""")
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.summary","text":" and summary"}]}}]}""")
        val snap = acc.snapshot()
        assertTrue(snap.text.contains("raw thought"))
        assertFalse(snap.isSummary)
    }

    @Test
    fun reasoningDetailsDataBlockWithoutTextContributesNothing() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.encrypted","data":"opaque"}]}}]}""")
        assertFalse(acc.hasReasoning())
    }

    @Test
    fun malformedLinesAreIgnored() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("data: not json")
        acc.acceptLine(": comment")
        acc.acceptLine("")
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning":"ok"}}]}""")
        assertEquals("ok", acc.snapshot().text)
        assertNull(acc.snapshot().reasoningTokens)
    }

    @Test
    fun nonStreamedMessageShapeIsAlsoRead() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""{"choices":[{"message":{"reasoning":"final-form reasoning"}}]}""")
        assertEquals("final-form reasoning", acc.snapshot().text)
    }
}
