/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guards for Phase 6.2's shared request-projection boundary. */
class SummarizerSafeIncludeWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val activity by lazy {
        source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
    }

    @Test
    fun typedMeasurementAndTransmissionShareOneFrozenProviderRequest() {
        assertTrue(activity.contains("val conversationProjection = freezeConversationProjection("))
        assertTrue(activity.contains("conversationProjection = conversationProjection"))
        assertTrue(activity.contains("RequestCapacity.measure(frozen.payload)"))
        assertTrue(activity.contains("request = frozen.request"))
        assertTrue(activity.contains("payload = frozen.payload"))
        assertTrue(activity.contains("preparedTurn.request"))
    }

    @Test
    fun typedAndLegacyPathsUseTheSameProjectionResolver() {
        assertTrue(activity.split("freezeConversationProjection(").size - 1 >= 3)
        assertTrue(activity.contains("legacyConversationProjection?.persistentIncludes"))
        assertTrue(activity.contains("legacyConversationProjection?.conversation.orEmpty()"))
        assertFalse(activity.contains("summarizerTrimmedHistory()"))
        assertFalse(activity.contains("summarizerInjectionText()"))
        assertFalse(activity.contains("legacySummarizerTrim"))
    }

    @Test
    fun attachmentPayloadsFollowTheHistoryOnBothRequestPaths() {
        val frozenHistory = activity.indexOf("msgs.addAll(resolvedHistory.dropLast(1))")
        val frozenPayload = activity.indexOf("msgs.addAll(conversationProjection.persistentIncludes)")
        assertTrue(frozenHistory > 0 && frozenPayload > frozenHistory)

        val legacyHistory = activity.indexOf("msgs.addAll(legacyResolvedHistory.dropLast(1))")
        val legacyPayload = activity.indexOf(
            "legacyConversationProjection?.persistentIncludes?.let(msgs::addAll)"
        )
        assertTrue(legacyHistory > 0 && legacyPayload > legacyHistory)

        // Memory and Lorebook are rebuilt every turn, so the payload block must
        // land ahead of them and keep its own cacheable position.
        assertTrue(activity.indexOf("assembly.prompt", frozenPayload) > frozenPayload)
    }

    @Test
    fun everySendSplitsMarkerFromPayloadWithNoSummarizerOptOut() {
        assertFalse(activity.contains("summarizerActive ="))
        val projection = source(
            "src/main/java/org/teslasoft/assistant/preferences/includes/" +
                "SummarizerSafeIncludeProjection.kt"
        )
        assertFalse(projection.contains("summarizerActive"))
        assertFalse(projection.contains("inlineMessage"))
    }

    @Test
    fun visibleFullImageCannotBeSilentlyOmittedFromOutboundProjection() {
        assertTrue(activity.contains("is visible but has no outbound image file"))
        assertTrue(activity.contains("is visible but its outbound image file is missing"))
        assertTrue(activity.contains("is visible but its outbound image file is unreadable"))
        assertTrue(activity.contains("protectRequestImagePayloads(canonical)"))
        assertTrue(activity.contains("releaseRequestImagePayloads()"))
    }

    @Test
    fun compatibilityFailureUsesReferencesAndFullConversationNotStaleSummary() {
        assertTrue(activity.contains("if (!prefs.ensureSummarizerProjectionCompatibility())"))
        assertTrue(activity.contains("return FrozenSummarizerState(true, 0, null)"))
        val controller = source(
            "src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt"
        )
        assertTrue(controller.contains("if (!prefs.ensureSummarizerProjectionCompatibility()) return false"))
        assertTrue(activity.contains("val compatible = preferences?.ensureSummarizerProjectionCompatibility() == true"))
    }

    @Test
    fun historicalIncludeChangesBelongToTheNextFrozenRequest() {
        val dispatchGuard = activity.substring(
            activity.indexOf("if (preparedTurn != null)"),
            activity.indexOf("// Put timestamp to chat")
        )
        assertTrue(dispatchGuard.contains("pendingIncludes.toList() == preparedTurn.pendingIncludes"))
        assertFalse(dispatchGuard.contains("chatMessages.toList()"))
        assertFalse(dispatchGuard.contains("historyBeforeSend"))
    }

    @Test
    fun capacityFallbackDoesNotTransformOrDropIncludes() {
        val preparation = activity.substring(
            activity.indexOf("private fun prepareTypedTurn"),
            activity.indexOf("private fun commitPreparedTurn")
        )
        assertFalse(preparation.contains("condenseInclude("))
        assertFalse(preparation.contains("reduceInclude("))
        assertFalse(preparation.contains("removeInclude("))
        assertFalse(preparation.contains("withoutImageBytes("))
    }
}
