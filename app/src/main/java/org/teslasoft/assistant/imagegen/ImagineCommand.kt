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

package org.teslasoft.assistant.imagegen

/**
 * The rebuilt `/imagine` parser (image-generation-rebuild-plan.md §2.1,
 * §4.6, §11). It inspects the RAW user input — before chat prefixes or end
 * separators are added — and recognizes the command only at the beginning
 * of the message, so mentioning `/imagine` mid-sentence never generates an
 * image. Optional trailing `--shape` / `--quality` options are parsed and
 * removed before the artistic prompt is sent; they override the saved
 * defaults for that request only.
 */
object ImagineCommand {

    private const val COMMAND = "/imagine"

    sealed class Parse {
        /** Not the `/imagine` command — an ordinary message. */
        data object NotImagine : Parse()

        /** The command with nothing left to send after option stripping. */
        data object EmptyPrompt : Parse()

        /** A trailing option with an unknown name or an invalid value
         *  (§11): a clear correctable error, and no image is generated. */
        class InvalidOption(val optionText: String) : Parse()

        /** A generatable request: the stripped prompt plus that request's
         *  overrides — null means no override, so the saved default
         *  applies (§11 precedence). */
        class Request(
            val prompt: String,
            val shapeOverride: ImageShape?,
            val qualityOverride: ImageQuality?
        ) : Parse()
    }

    fun parse(rawMessage: String): Parse {
        val trimmed = rawMessage.trim()
        if (!trimmed.lowercase().startsWith(COMMAND)) return Parse.NotImagine
        val afterCommand = trimmed.substring(COMMAND.length)
        // "/imaginefoo" is not the command; only "/imagine" alone or
        // "/imagine <prompt>".
        if (afterCommand.isNotEmpty() && !afterCommand.first().isWhitespace()) {
            return Parse.NotImagine
        }

        val tokens = afterCommand.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toMutableList()

        var shapeOverride: ImageShape? = null
        var qualityOverride: ImageQuality? = null

        // Options are TRAILING only (§2.1): strip (name, value) pairs from
        // the end; a "--" token in the middle of the prompt is prompt text.
        while (tokens.isNotEmpty()) {
            val last = tokens.last()
            val secondToLast = tokens.getOrNull(tokens.size - 2)
            if (last.startsWith("--")) {
                // An option name with no value ends the command invalidly.
                return Parse.InvalidOption(last)
            }
            if (secondToLast == null || !secondToLast.startsWith("--")) break

            when (secondToLast.lowercase()) {
                "--shape" -> {
                    val value = parseShapeValue(last)
                        ?: return Parse.InvalidOption("$secondToLast $last")
                    // The option nearest the end wins when repeated.
                    if (shapeOverride == null) shapeOverride = value
                }
                "--quality" -> {
                    val value = parseQualityValue(last)
                        ?: return Parse.InvalidOption("$secondToLast $last")
                    if (qualityOverride == null) qualityOverride = value
                }
                else -> return Parse.InvalidOption("$secondToLast $last")
            }
            tokens.removeAt(tokens.size - 1)
            tokens.removeAt(tokens.size - 1)
        }

        val prompt = tokens.joinToString(" ").trim()
        if (prompt.isEmpty()) return Parse.EmptyPrompt
        return Parse.Request(prompt, shapeOverride, qualityOverride)
    }

    /** Convenience for routing gates: anything that starts as the command
     *  (including its error forms) belongs to the image path. */
    fun isImagineAttempt(rawMessage: String): Boolean =
        parse(rawMessage) !is Parse.NotImagine

    private fun parseShapeValue(value: String): ImageShape? =
        ImageShape.entries.firstOrNull { it.storedValue == value.trim().lowercase() }

    private fun parseQualityValue(value: String): ImageQuality? =
        ImageQuality.entries.firstOrNull { it.storedValue == value.trim().lowercase() }

    /* ------------------------------ Option resolution (§11) ------------------------------ */

    /** The §11 outcome for one request: the values to send, which
     *  explicitly requested options the selected generator cannot support
     *  (they need the continue-or-cancel notice), and which saved defaults
     *  fell back silently (they belong in the Image Generation Errors log
     *  when recording is on). */
    class ResolvedOptions(
        val shape: ImageShape,
        val quality: ImageQuality,
        val unsupportedExplicit: List<String>,
        val silentFallbacks: List<String>
    )

    const val OPTION_SHAPE = "shape"
    const val OPTION_QUALITY = "quality"

    /**
     * §11 precedence — explicit per-request override, else the saved
     * default, else the provider default — combined with the adapter's
     * capabilities: an option the provider's API cannot carry resolves to
     * AUTOMATIC (omitted, so the provider default applies). An EXPLICIT
     * request for an unsupported option is reported for the notice;
     * an unsupported saved default is reported as a silent fallback.
     */
    fun resolveOptions(
        shapeOverride: ImageShape?,
        qualityOverride: ImageQuality?,
        savedShape: ImageShape,
        savedQuality: ImageQuality,
        capabilities: ImageAdapterCapabilities
    ): ResolvedOptions {
        val unsupportedExplicit = mutableListOf<String>()
        val silentFallbacks = mutableListOf<String>()

        var shape = shapeOverride ?: savedShape
        if (!capabilities.supportsShape && shape != ImageShape.AUTOMATIC) {
            if (shapeOverride != null) unsupportedExplicit.add(OPTION_SHAPE)
            else silentFallbacks.add(OPTION_SHAPE)
            shape = ImageShape.AUTOMATIC
        }

        var quality = qualityOverride ?: savedQuality
        if (!capabilities.supportsQuality && quality != ImageQuality.AUTOMATIC) {
            if (qualityOverride != null) unsupportedExplicit.add(OPTION_QUALITY)
            else silentFallbacks.add(OPTION_QUALITY)
            quality = ImageQuality.AUTOMATIC
        }

        return ResolvedOptions(shape, quality, unsupportedExplicit, silentFallbacks)
    }
}
