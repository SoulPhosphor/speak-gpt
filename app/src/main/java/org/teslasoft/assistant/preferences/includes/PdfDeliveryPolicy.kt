/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

/**
 * Chooses how a FULL PDF reaches the model for one request: the original file
 * (native) or locally extracted text. Shared by normal sends and Condense.
 *
 * Native delivery needs both confirmed model capability and a transport this
 * app can actually use to carry the file. Every provider here is reached
 * through its OpenAI-compatible Chat Completions endpoint, so only routes whose
 * Chat Completions `file` part is documented to work count:
 *
 * - OpenAI: documented `file` content parts with inline file data.
 * - OpenRouter: documented `file` content parts, with the native PDF engine.
 * - Anthropic: its OpenAI-compatible endpoint documents `file` parts as
 *   ignored, which would silently drop the PDF.
 * - Gemini: its OpenAI-compatible endpoint rejects `file` parts.
 * - xAI: file input is documented for its Files and Responses APIs, not this
 *   Chat Completions path.
 * - NanoGPT: its PDF request format for this endpoint is not verified.
 * - Featherless and generic endpoints: no documented PDF transport.
 *
 * UNKNOWN or UNSUPPORTED capability always means local text.
 */
object PdfDeliveryPolicy {
    private val NATIVE_TRANSPORTS = setOf(
        PdfCapabilityProvider.OPENAI,
        PdfCapabilityProvider.OPENROUTER
    )

    fun hasNativeTransport(provider: PdfCapabilityProvider?): Boolean =
        provider in NATIVE_TRANSPORTS

    fun useNative(
        provider: PdfCapabilityProvider?,
        capability: PdfCapability,
        byteSize: Long,
        maxInlineBytes: Long
    ): Boolean = capability == PdfCapability.SUPPORTED &&
        hasNativeTransport(provider) &&
        PdfRequestSerializer.canInline(byteSize, maxInlineBytes)
}
