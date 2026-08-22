/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningConsumerWiringTest {

    @Test
    fun selectorFavoriteAndQuickSettingsConsumeTheSharedResolver() {
        val modelList = source("ui/adapters/ModelListAdapter.kt")
        val favorites = source("ui/adapters/FavoriteModelListAdapter.kt")
        val quickSettings = source("ui/fragments/dialogs/QuickSettingsBottomSheetDialogFragment.kt")

        assertTrue(modelList.contains("EndpointReasoningCapability.index("))
        assertTrue(modelList.contains("resolvedReasoningCapabilityIndex.resolve(modelId)"))
        assertTrue(modelList.contains("capability.isReasoningCapable"))
        assertTrue(favorites.contains("EndpointReasoningCapability.index("))
        assertTrue(favorites.contains("reasoningIndex(endpointId).resolve(modelId)"))
        assertTrue(favorites.contains("capability.isReasoningCapable"))
        assertTrue(favorites.contains("capability.hasConfigurableSetting"))
        assertTrue(quickSettings.contains("EndpointReasoningCapability.resolveWithLearnedRejections("))
    }

    @Test
    fun observedReasoningPromotesCapabilityAndStampsTheStoredMessage() {
        val chat = source("ui/activities/ChatActivity.kt")
        assertTrue(chat.contains("ReasoningSupport.UNKNOWN"))
        assertTrue(chat.contains("learnFromObservedResponse"))
        assertTrue(chat.contains("KEY_MESSAGE_REASONING_LEVEL"))
    }

    @Test
    fun quickSettingsSavePreservesEndpointCapabilityCaches() {
        val quickSettings = source("ui/fragments/dialogs/QuickSettingsBottomSheetDialogFragment.kt")
        assertTrue(quickSettings.contains("imageCapabilityByModel = currentProfile.imageCapabilityByModel"))
        assertTrue(quickSettings.contains("toolCapabilityByModel = currentProfile.toolCapabilityByModel"))
        assertTrue(quickSettings.contains("reasoningCapabilityByModel = currentProfile.reasoningCapabilityByModel"))
        assertTrue(quickSettings.contains("reasoningRejectedLevelsByModel = currentProfile.reasoningRejectedLevelsByModel"))
    }

    @Test
    fun endpointEditorInvalidatesCachesOnlyThroughSharedPathPolicy() {
        val editor = source("ui/activities/ApiEndpointEditorActivity.kt")
        assertTrue(editor.contains("EndpointCapabilityCachePolicy.effectivePathChanged("))
        assertTrue(editor.contains("reasoningCapabilityByModel = if (effectivePathChanged) \"\""))
        assertTrue(editor.contains("imageCapabilityByModel = if (effectivePathChanged) \"\""))
    }

    private fun source(relative: String): String {
        val projectRelative = "src/main/java/org/teslasoft/assistant/$relative"
        val file = listOf(
            File(projectRelative),
            File("app/$projectRelative"),
            File(System.getProperty("user.dir"), projectRelative),
            File(System.getProperty("user.dir"), "app/$projectRelative")
        ).firstOrNull { it.isFile }
            ?: error("Could not locate $relative")
        return file.readText()
    }
}
