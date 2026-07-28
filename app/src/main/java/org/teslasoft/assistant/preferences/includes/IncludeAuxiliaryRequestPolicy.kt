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
 * The model-facing contract for include maintenance requests.
 *
 * Keeping the selected model, output limit, and prompt together makes it hard
 * for Condense, Reduce, and Remove to accidentally share a purpose or silently
 * lose a request setting when their call sites change.
 */
object IncludeAuxiliaryRequestPolicy {

    const val ARTIFACT_MAX_TOKENS = 120

    data class RequestSpec(
        val model: String,
        val maxTokens: Int,
        val prompt: String
    )

    fun condense(
        include: ChatInclude,
        selectedModel: String,
        configuredMaxTokens: Int
    ): RequestSpec = RequestSpec(
        model = selectedModel,
        maxTokens = configuredMaxTokens.coerceAtLeast(1),
        prompt = """
            Create substantially shorter, editable Cliff Notes or a structured outline of the document below. The result will replace the full document in future AI requests, so retain enough specific information to understand it and discuss it intelligently later.

            Preserve, when relevant:
            - what the document is and its purpose;
            - its main sections, subjects, arguments, or sequence;
            - important facts, findings, decisions, instructions, and conclusions;
            - notable names, dates, numbers, examples, relationships, and distinctive details;
            - warnings, limitations, uncertainty, caveats, and unresolved issues.

            Adapt the notes to the document type. For a résumé, retain experience, roles, dates, skills, accomplishments, and notable qualifications. For a report, retain findings, evidence, conclusions, and limitations. For a plan, retain requirements, decisions, stages, dependencies, risks, and open questions.

            Do not return only a vague description, invent information, erase important uncertainty, or produce notes as long as the original. Reply with the Cliff Notes or structured outline only.

            File name: ${include.fileName}

            <document>
            ${include.fullText}
            </document>
        """.trimIndent()
    )

    fun artifact(
        include: ChatInclude,
        selectedModel: String,
        excerptCharacters: Int
    ): RequestSpec = RequestSpec(
        model = selectedModel,
        maxTokens = ARTIFACT_MAX_TOKENS,
        prompt = """
            Create a very short reminder of the document for future AI requests after the document is removed. State what the document was and its general subject or purpose. Include at most one or two especially important details. Use no more than three short sentences.

            Do not try to preserve the document's full contents, invent information, or return an outline. Reply with the reminder only.

            File name: ${include.fileName}

            <document>
            ${include.modelText().take(excerptCharacters)}
            </document>
        """.trimIndent()
    )

    /**
     * Reduce request for a FULL image. The caller sends this prompt as the
     * text side of a multi-part user message and attaches the image itself as
     * an image content part.
     *
     * The accompanying user message is passed in as context so the model can
     * prioritise details the user cared about, not everything in the frame.
     */
    fun reduceImage(
        include: ChatInclude,
        accompanyingUserMessage: String,
        selectedModel: String,
        configuredMaxTokens: Int
    ): RequestSpec = RequestSpec(
        model = selectedModel,
        maxTokens = configuredMaxTokens.coerceAtLeast(1),
        prompt = """
            Create a concise, self-contained text memory of this image. This text will replace the image in future requests, so preserve the information a person would need to remember in order to understand later discussion.

            Use the accompanying message as context for what matters most.

            Include, when relevant:

            - the main subject and apparent purpose of the image;
            - important people, objects, actions, relationships, or surroundings;
            - distinctive visual details needed to identify or compare it later;
            - visible text that matters to understanding the image, quoted exactly when practical;
            - charts, diagrams, interfaces, or data, including important labels, values, trends, and structure;
            - uncertainty, obscured details, or anything that cannot be determined reliably.

            Prioritize useful information over exhaustive description. Summarize repetitive or lengthy text unless exact wording is important. Do not invent identities, facts, text, or details that are not visible or supplied in the context. Do not give instructions, commentary, or analysis beyond what is needed to preserve the image's meaning.

            Return only the text memory.

            File name: ${include.fileName}
            Accompanying message: ${accompanyingUserMessage.ifBlank { "(none)" }}
        """.trimIndent()
    )
}
