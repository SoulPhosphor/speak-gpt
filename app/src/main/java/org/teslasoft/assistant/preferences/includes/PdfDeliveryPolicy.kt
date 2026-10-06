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
 * - xAI: its Chat Completions accepts a `file` part referencing a PDF
 *   uploaded to its Files API ([XaiPdfFiles]), up to 50 MB.
 * - NanoGPT: its PDF request format for this endpoint is not verified.
 * - Featherless and generic endpoints: no documented PDF transport.
 *
 * UNKNOWN or UNSUPPORTED capability always means local text.
 */
object PdfDeliveryPolicy {
    /** OpenAI and OpenRouter receive the PDF inline in the request body. */
    const val MAX_INLINE_BYTES = 20L * 1024L * 1024L

    fun hasNativeTransport(provider: PdfCapabilityProvider?): Boolean = maxNativeBytes(provider) > 0

    /** The largest PDF this route can carry natively; zero when it cannot. */
    fun maxNativeBytes(provider: PdfCapabilityProvider?): Long = when (provider) {
        PdfCapabilityProvider.OPENAI, PdfCapabilityProvider.OPENROUTER -> MAX_INLINE_BYTES
        PdfCapabilityProvider.XAI -> XaiPdfFiles.MAX_BYTES
        else -> 0L
    }

    /** xAI references an uploaded copy; the others carry the bytes inline. */
    fun usesUploadedFile(provider: PdfCapabilityProvider?): Boolean =
        provider == PdfCapabilityProvider.XAI

    fun useNative(
        provider: PdfCapabilityProvider?,
        capability: PdfCapability,
        byteSize: Long
    ): Boolean = capability == PdfCapability.SUPPORTED &&
        hasNativeTransport(provider) &&
        PdfRequestSerializer.canInline(byteSize, maxNativeBytes(provider))
}
