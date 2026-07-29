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

/**
 * The current `/imagine` detection and routing logic, extracted verbatim
 * from ChatActivity so regression tests can pin the behavior before the
 * image generation rebuild replaces it (image-generation-rebuild-plan.md,
 * step 1).
 *
 * Deliberately preserved quirks, each captured by LegacyImagineCommandTest:
 * the command is recognized anywhere inside the stored message rather than
 * only at the start of the raw input; the prompt is sliced at a fixed
 * character position, so a chat prefix or a mid-text mention garbles it;
 * and the length guard counts the whole stored message, prefixes included.
 */
object LegacyImagineCommand {

    /** parseMessage's image branch: the stored message (prefix + typed text
     *  + end separator) contains the command and has text beyond the fixed
     *  slice position. */
    fun triggersImageGeneration(storedMessage: String, imagineCommandEnabled: Boolean): Boolean {
        return storedMessage.lowercase().contains("/imagine") &&
            storedMessage.length > 9 && imagineCommandEnabled
    }

    /** parseMessage's empty-prompt branch: the command is present but the
     *  stored message is too short to carry a prompt. */
    fun showsEmptyPromptError(storedMessage: String, imagineCommandEnabled: Boolean): Boolean {
        return storedMessage.lowercase().contains("/imagine") &&
            storedMessage.length <= 9 && imagineCommandEnabled
    }

    /** The prompt sent to the generator: everything after the first nine
     *  characters of the stored message, wherever the command appears. */
    fun extractPrompt(storedMessage: String): String {
        return storedMessage.substring(9)
    }

    /** prepareTypedTurn's gate: which raw messages skip the frozen typed-send
     *  path and fall back to the legacy parseMessage pipeline. */
    fun divertsTypedTurnToLegacyPipeline(
        rawMessage: String,
        imagineCommandEnabled: Boolean,
        selectedModel: String,
        functionCallingEnabled: Boolean
    ): Boolean {
        val imagineCommand = rawMessage.lowercase().contains("/imagine") && imagineCommandEnabled
        return imagineCommand ||
            selectedModel.contains(":ft") || selectedModel.contains("ft:") ||
            functionCallingEnabled
    }
}
