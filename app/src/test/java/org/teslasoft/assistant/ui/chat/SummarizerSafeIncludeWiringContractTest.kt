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
        assertTrue(activity.contains("legacyConversationProjection?.foldedIncludes"))
        assertTrue(activity.contains("legacyConversationProjection?.conversation.orEmpty()"))
        assertFalse(activity.contains("summarizerTrimmedHistory()"))
        assertFalse(activity.contains("summarizerInjectionText()"))
        assertFalse(activity.contains("legacySummarizerTrim"))
    }

    @Test
    fun attachmentsRideInTheirMessagesAndFoldedOnesFollowTheSummary() {
        // Owner ruling, Oct 6 2026: no separate attachment block after the
        // history. Only attachments of folded messages travel on their own,
        // right after the summary and ahead of the retained history.
        assertFalse(activity.contains("persistentIncludes"))

        val frozenSummary = activity.indexOf("content = conversationProjection.summaryInjection")
        val frozenFolded = activity.indexOf("msgs.addAll(conversationProjection.foldedIncludes)")
        val frozenHistory = activity.indexOf("msgs.addAll(resolvedHistory.dropLast(1))")
        assertTrue(frozenSummary > 0 && frozenFolded > frozenSummary && frozenHistory > frozenFolded)

        val legacySummary = activity.indexOf("content = legacyConversationProjection.summaryInjection")
        val legacyFolded = activity.indexOf("legacyConversationProjection?.foldedIncludes?.let(msgs::addAll)")
        val legacyHistory = activity.indexOf("msgs.addAll(legacyResolvedHistory.dropLast(1))")
        assertTrue(legacySummary > 0 && legacyFolded > legacySummary && legacyHistory > legacyFolded)

        // Memory recall keeps reading words and markers, not attachment bodies.
        assertTrue(activity.contains("conversationProjection.memoryContext"))
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
        val sectionBuild = controller.substringAfter("private suspend fun buildOneSection(")
        val incompatible = sectionBuild.substringAfter("if (!prefs.ensureSummarizerProjectionCompatibility()) {")
            .substringBefore("val current = snapshot.sources()")
        assertTrue(incompatible.contains("recordStorageFailure(prefs, chatName,"))
        assertTrue(incompatible.contains("return false"))
        assertFalse(incompatible.contains("requestSection("))
        // The summary / compaction review screen loads only compatible text.
        val review = source(
            "src/main/java/org/teslasoft/assistant/ui/activities/ConversationSummaryActivity.kt"
        )
        assertTrue(review.contains("val compatible = preferences?.ensureSummarizerProjectionCompatibility() == true"))
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
