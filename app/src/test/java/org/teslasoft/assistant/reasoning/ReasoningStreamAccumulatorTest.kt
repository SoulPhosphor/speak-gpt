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
    fun reasoningDetailsSummaryFieldIsExtractedWhenThereIsNoText() {
        // Documented summary shape that carries its content in `summary`, not
        // `text`. The block must not be silently dropped.
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.summary","summary":"A concise summary."}]}}]}""")
        val snap = acc.snapshot()
        assertEquals("A concise summary.", snap.text)
        assertTrue(snap.isSummary)
        assertTrue(acc.inboundDiagnostics().summaryField)
    }

    @Test
    fun reasoningDetailsTextIsPreferredOverSummaryWhenBothPresent() {
        // Keep the existing raw/text path byte-identical: when a block has both,
        // `text` is used for display and `summary` is not appended on top.
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"The text.","summary":"The summary."}]}}]}""")
        assertEquals("The text.", acc.snapshot().text)
    }

    @Test
    fun inboundDiagnosticsReportWhichFieldsWereSeenAndCharCount() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning":"abc"}}]}""")
        val d = acc.inboundDiagnostics()
        assertTrue(d.reasoning)
        assertFalse(d.reasoningContent)
        assertFalse(d.reasoningDetails)
        assertFalse(d.summaryField)
        assertEquals(3, d.characters)
        assertTrue(d.anyField)
    }

    @Test
    fun inboundDiagnosticsReportNoFieldsForAContentOnlyStream() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"content":"Hello"}}]}""")
        val d = acc.inboundDiagnostics()
        assertFalse(d.anyField)
        assertEquals(0, d.characters)
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

    @Test
    fun noReasoningDetailsMeansNullContinuationState() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning":"x"}}]}""")
        assertNull(acc.reasoningDetails())
    }

    @Test
    fun encryptedReasoningDetailBlockPreservedVerbatimForResend() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine(
            """data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.encrypted","data":"OPAQUE==","signature":"sig123","index":0}]}}]}"""
        )
        val details = acc.reasoningDetails()!!
        assertEquals(1, details.size())
        val block = details[0].asJsonObject
        assertEquals("reasoning.encrypted", block.get("type").asString)
        assertEquals("OPAQUE==", block.get("data").asString)
        assertEquals("sig123", block.get("signature").asString)
        // An encrypted block carries no display text.
        assertFalse(acc.hasReasoning())
    }

    @Test
    fun streamedTextFragmentsWithSameIndexAreMergedButSignatureBlockKept() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"Let me ","index":0}]}}]}""")
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"think.","index":0}]}}]}""")
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.encrypted","data":"E==","index":1}]}}]}""")
        val details = acc.reasoningDetails()!!
        assertEquals(2, details.size())
        assertEquals("Let me think.", details[0].asJsonObject.get("text").asString)
        assertEquals("reasoning.encrypted", details[1].asJsonObject.get("type").asString)
        // Display text also reflects the fragments.
        assertEquals("Let me think.", acc.snapshot().text)
    }

    @Test
    fun equivalentReasoningAndStructuredTextInOneDeltaDisplaysOnce() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine(
            """data: {"choices":[{"delta":{"reasoning":"We need to respond","reasoning_details":[{"type":"reasoning.text","text":"We need to respond","index":0}]}}]}"""
        )
        assertEquals("We need to respond", acc.snapshot().text)
        assertEquals("We need to respond", acc.reasoningDetails()!![0].asJsonObject.get("text").asString)
    }

    @Test
    fun equivalentReasoningContentAndStructuredTextInOneDeltaDisplaysOnce() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine(
            """data: {"choices":[{"delta":{"reasoning_content":"One copy","reasoning_details":[{"type":"reasoning.summary","text":"One copy"}]}}]}"""
        )
        assertEquals("One copy", acc.snapshot().text)
        // The chosen unstructured field is raw reasoning; a duplicate summary
        // representation must not relabel the displayed content as a summary.
        assertFalse(acc.snapshot().isSummary)
    }

    @Test
    fun structuredEncryptedAndSignatureBlocksRemainWhileDirectTextWinsDisplay() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine(
            """data: {"choices":[{"delta":{"reasoning":"Visible once","reasoning_details":[{"type":"reasoning.text","text":"Visible once","index":0},{"type":"reasoning.encrypted","data":"OPAQUE==","index":1},{"type":"reasoning.signature","signature":"sig","index":2}]}}]}"""
        )
        assertEquals("Visible once", acc.snapshot().text)
        val details = acc.reasoningDetails()!!
        assertEquals(3, details.size())
        assertEquals("OPAQUE==", details[1].asJsonObject.get("data").asString)
        assertEquals("sig", details[2].asJsonObject.get("signature").asString)
    }

    @Test
    fun repeatedTextAcrossSeparateDeltasIsPreserved() {
        val acc = ReasoningStreamAccumulator()
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning":"again "}}]}""")
        acc.acceptLine("""data: {"choices":[{"delta":{"reasoning":"again"}}]}""")
        assertEquals("again again", acc.snapshot().text)
    }
}
