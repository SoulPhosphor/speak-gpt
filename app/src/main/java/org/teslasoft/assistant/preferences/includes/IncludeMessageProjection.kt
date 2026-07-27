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

package org.teslasoft.assistant.preferences.includes

/**
 * Converts the persisted fields of a user turn into the exact content sent
 * to the model. Keeping this boundary pure makes persistence-to-request
 * behavior regression-testable.
 */
object IncludeMessageProjection {

    /**
     * Text-side content of a user message, with document/reduced-image/
     * artifact wrappers inline. Callers that also need to attach FULL image
     * parts should use [userMessageParts] instead.
     */
    fun userContent(typedText: String, includesJson: String?): String {
        val includes = ChatInclude.listFromJson(includesJson)
        return if (includes.isEmpty()) {
            typedText
        } else {
            IncludeRenderer.renderUserMessage(typedText, includes)
        }
    }

    /**
     * Full user-message projection: the text side plus every FULL image part
     * that must accompany it. When [imageParts] is empty the caller sends the
     * message as ordinary text content; when it is non-empty the caller sends
     * a multi-part message with the text piece first and image parts last, in
     * the returned order.
     */
    fun userMessageParts(typedText: String, includesJson: String?): ProjectedUserMessage {
        val includes = ChatInclude.listFromJson(includesJson)
        val text = if (includes.isEmpty()) {
            typedText
        } else {
            IncludeRenderer.renderUserMessage(typedText, includes)
        }
        val imageParts = if (includes.isEmpty()) {
            emptyList()
        } else {
            IncludeRenderer.imagePartsFor(includes)
        }
        return ProjectedUserMessage(text, imageParts)
    }
}

/** Text + optional trailing image parts for one user message. */
data class ProjectedUserMessage(
    val text: String,
    val imageParts: List<RenderedImagePart>
) {
    /** True when this message has no bytes-on-disk image parts to attach. */
    fun isTextOnly(): Boolean = imageParts.isEmpty()
}
